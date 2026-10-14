package org.pocketlab.puppy.terminal

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Owns one native Ghostty terminal and the single serial executor every
 * native call runs on (docs/design.md §6.2).
 *
 * Output bytes are fed through a bounded window: [feed] blocks the caller
 * (a backend reader thread) once [MAX_PENDING_CHUNKS] chunks are queued,
 * which pushes back on the pty or socket instead of dropping bytes.
 */
class TerminalCore(
    cols: Int,
    rows: Int,
    scrollbackLines: Int,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        /** Bytes the terminal answers with (DA, DSR...). Runs on the core thread. */
        fun onReply(bytes: ByteArray)
        fun onTitleChanged(title: String?)
        fun onBell() {}
        /** A program asked to write [text] to the clipboard (OSC 52). */
        fun onClipboardWrite(text: String) {}
        fun onPwdChanged(pwd: String?) {}
        /** A new frame is available through [TerminalCore.frame]. Any thread. */
        fun onFrame()
    }

    private val id = ids.incrementAndGet()
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "terminal-$id").apply { isDaemon = true }
    }
    private val window = Semaphore(MAX_PENDING_CHUNKS)
    private var handle: Long = NativeTerminal.create(cols, rows, scrollbackLines)
    private var buffer: ByteBuffer = allocate(cols, rows)
    private val snapshotPending = AtomicBoolean(false)
    private var forceNext = true
    private var lastSnapshotNanos = 0L

    /** Latest decoded frame; replaced atomically, never mutated. */
    @Volatile var frame: TerminalFrame? = null
        private set

    /** Whether a view is showing this terminal; hidden terminals keep parsing but skip frames. */
    @Volatile var visible = false
        set(value) {
            field = value
            if (value) requestFrame(force = true)
        }

    @Volatile var cols = cols
        private set
    @Volatile var rows = rows
        private set

    init {
        check(handle != 0L) { "failed to create terminal" }
    }

    private fun allocate(cols: Int, rows: Int): ByteBuffer =
        ByteBuffer.allocateDirect(NativeTerminal.frameCapacity(cols, rows)).order(ByteOrder.LITTLE_ENDIAN)

    /** Runs [block] on the core thread. */
    fun post(block: () -> Unit) {
        if (executor.isShutdown) return
        executor.execute {
            if (handle != 0L) block()
        }
    }

    /**
     * Queues raw output for parsing. Blocks while too much output is pending.
     * [data] must not be modified afterwards.
     */
    fun feed(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        window.acquire()
        try {
            executor.execute {
                try {
                    if (handle != 0L) feedNow(data, offset, length)
                } finally {
                    window.release()
                }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            window.release()
        }
    }

    /** Feeds bytes the app generates (not program output) without blocking the caller. */
    fun inject(data: ByteArray) = post { feedNow(data, 0, data.size) }

    private fun feedNow(data: ByteArray, offset: Int, length: Int) {
        val events = NativeTerminal.feed(handle, data, offset, length)
        if (events and NativeTerminal.EVENT_REPLIES != 0) {
            NativeTerminal.takeReplies(handle)?.let(callbacks::onReply)
        }
        if (events and NativeTerminal.EVENT_TITLE != 0) {
            callbacks.onTitleChanged(NativeTerminal.title(handle)?.decodeToString())
        }
        if (events and NativeTerminal.EVENT_PWD != 0) {
            callbacks.onPwdChanged(NativeTerminal.pwd(handle)?.decodeToString())
        }
        if (events and NativeTerminal.EVENT_BELL != 0) callbacks.onBell()
        if (events and NativeTerminal.EVENT_CLIPBOARD != 0) {
            NativeTerminal.takeClipboard(handle)?.decodeToString()?.let(callbacks::onClipboardWrite)
        }
        if (events and NativeTerminal.EVENT_RENDER_HOLD != 0 &&
            NativeTerminal.modes(handle) and TerminalFrame.FLAG_RENDER_HOLD != 0
        ) {
            // A program that never releases synchronized output must not freeze the screen.
            executor.schedule({ if (handle != 0L) snapshotNow(force = true) }, RENDER_HOLD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
        requestFrame()
    }

    /** Schedules a snapshot, coalescing bursts of output into one frame. */
    fun requestFrame(force: Boolean = false) {
        if (force) post { forceNext = true }
        if (!visible) return
        if (!snapshotPending.compareAndSet(false, true)) return
        val sinceLast = System.nanoTime() - lastSnapshotNanos
        val delay = (MIN_FRAME_NANOS - sinceLast).coerceAtLeast(0)
        try {
            executor.schedule({
                snapshotPending.set(false)
                if (handle != 0L) snapshotNow(forceNext)
            }, delay, TimeUnit.NANOSECONDS)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            snapshotPending.set(false)
        }
    }

    private fun snapshotNow(force: Boolean) {
        if (!visible && !force) return
        var n = NativeTerminal.snapshot(handle, buffer, force)
        if (n < 0) {
            // The program may have resized the grid itself (DECCOLM); size from native.
            val grid = NativeTerminal.gridSize(handle)
            buffer = allocate(maxOf(cols, grid ushr 16), maxOf(rows, grid and 0xFFFF))
            n = NativeTerminal.snapshot(handle, buffer, true)
        }
        forceNext = false
        lastSnapshotNanos = System.nanoTime()
        if (n > 0) {
            frame = TerminalFrame.decode(buffer, n)
            callbacks.onFrame()
        }
    }

    fun resize(cols: Int, rows: Int, cellWidth: Int, cellHeight: Int) = post {
        if (NativeTerminal.resize(handle, cols, rows, cellWidth, cellHeight)) {
            this.cols = cols
            this.rows = rows
            buffer = allocate(cols, rows)
            forceNext = true
            requestFrame()
        }
    }

    fun setColors(background: Int, foreground: Int, cursor: Int?, palette: IntArray?) = post {
        NativeTerminal.setColors(handle, background, foreground, cursor ?: 0, cursor != null, palette)
        forceNext = true
        requestFrame()
    }

    fun setClipboardWriteAllowed(allowed: Boolean) = post {
        NativeTerminal.setClipboardWriteAllowed(handle, allowed)
    }

    fun scrollBy(lines: Int) = post {
        NativeTerminal.scroll(handle, NativeTerminal.SCROLL_DELTA, lines)
        requestFrame()
    }

    fun scrollToBottom() = post {
        NativeTerminal.scroll(handle, NativeTerminal.SCROLL_BOTTOM, 0)
        requestFrame()
    }

    fun scrollToRow(row: Int) = post {
        NativeTerminal.scroll(handle, NativeTerminal.SCROLL_ROW, row)
        requestFrame()
    }

    /** Encodes a key event on the core thread and passes the bytes to [out]. */
    fun encodeKey(key: String, text: String?, mods: Int, consumedMods: Int, action: Int, unshifted: Int, out: (ByteArray) -> Unit) =
        post {
            NativeTerminal.encodeKey(handle, key.toByteArray(), text?.toByteArray(), mods, consumedMods, action, unshifted)
                ?.let(out)
        }

    fun encodeMouse(action: Int, button: Int, mods: Int, x: Float, y: Float, width: Int, height: Int, anyPressed: Boolean, out: (ByteArray) -> Unit) =
        post {
            NativeTerminal.encodeMouse(handle, action, button, mods, x, y, width, height, anyPressed)?.let(out)
        }

    fun encodePaste(text: String, out: (ByteArray) -> Unit) = post {
        NativeTerminal.encodePaste(handle, text.toByteArray())?.let(out)
    }

    fun encodeFocus(gained: Boolean, out: (ByteArray) -> Unit) = post {
        NativeTerminal.encodeFocus(handle, gained)?.let(out)
    }

    /** Frees native state. Pending work is dropped. */
    fun close() {
        executor.execute {
            if (handle != 0L) {
                NativeTerminal.destroy(handle)
                handle = 0L
            }
        }
        executor.shutdown()
        // Unblock any reader still waiting for window space.
        window.release(MAX_PENDING_CHUNKS)
    }

    companion object {
        const val MAX_PENDING_CHUNKS = 64
        private const val MIN_FRAME_NANOS = 8_000_000L
        private const val RENDER_HOLD_TIMEOUT_MS = 1000L
        private val ids = AtomicInteger()
    }
}
