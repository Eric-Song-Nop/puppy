package org.pocketlab.puppy.session

import java.util.UUID
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.pocketlab.puppy.terminal.TerminalCore
import org.pocketlab.puppy.terminal.TerminalFrame

/** How a session was started, so a tab can be restored or restarted. */
sealed interface SessionSpec {
    data object Local : SessionSpec
    data class Ssh(val hostId: Long) : SessionSpec
}

/**
 * One tab: a backend (local pty or SSH) connected to one Ghostty core.
 * UI code only attaches to it; closing the UI does not end the session.
 */
class TerminalSession(
    val spec: SessionSpec,
    defaultTitle: String,
    scrollbackLines: Int,
    initialSize: TerminalSize,
    private val backendFactory: () -> SessionBackend,
    val id: String = UUID.randomUUID().toString(),
) {
    private val _status = MutableStateFlow<SessionStatus>(SessionStatus.Starting)
    val status: StateFlow<SessionStatus> = _status.asStateFlow()

    private val _prompt = MutableStateFlow<SessionPrompt?>(null)
    /** A pending question for the user, such as a password or host key. */
    val prompt: StateFlow<SessionPrompt?> = _prompt.asStateFlow()

    private val _clipboardRequests = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** Text a program asked to put on the clipboard; the UI asks the user first. */
    val clipboardRequests: SharedFlow<String> = _clipboardRequests.asSharedFlow()

    private val _bells = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val bells: SharedFlow<Unit> = _bells.asSharedFlow()

    private val defaultTitle = defaultTitle
    private var remoteTitle: String? = null
    private val _customTitle = MutableStateFlow<String?>(null)
    private val _title = MutableStateFlow(defaultTitle)
    /** Custom alias if set, otherwise the program's title, otherwise the default. */
    val title: StateFlow<String> = _title.asStateFlow()

    @Volatile var size: TerminalSize = initialSize
        private set
    @Volatile private var cellWidth = 1
    @Volatile private var cellHeight = 1

    /** Notified on any thread when a new frame is ready. */
    @Volatile var frameListener: (() -> Unit)? = null

    val core = TerminalCore(initialSize.cols, initialSize.rows, scrollbackLines, object : TerminalCore.Callbacks {
        override fun onReply(bytes: ByteArray) = backend?.write(bytes) ?: Unit
        override fun onTitleChanged(title: String?) {
            remoteTitle = title?.takeIf { it.isNotBlank() }
            updateTitle()
        }
        override fun onBell() {
            _bells.tryEmit(Unit)
        }
        override fun onClipboardWrite(text: String) {
            _clipboardRequests.tryEmit(text)
        }
        override fun onFrame() {
            frameListener?.invoke()
        }
    })

    @Volatile private var backend: SessionBackend? = null
    @Volatile private var generation = 0

    val capabilities: BackendCapabilities? get() = backend?.capabilities

    val frame: TerminalFrame? get() = core.frame

    fun start() {
        val gen = ++generation
        val b = backendFactory()
        backend = b
        b.open(size, object : SessionBackend.Listener {
            override fun onOutput(data: ByteArray, length: Int) {
                if (gen == generation) core.feed(data, 0, length)
            }

            override fun onStatus(status: SessionStatus) {
                if (gen != generation) return
                _status.value = status
                if (!status.isLive) _prompt.value = null
            }

            override fun onPrompt(prompt: SessionPrompt) {
                if (gen == generation) _prompt.value = prompt
            }
        })
    }

    /** Marks a tab restored from disk whose connection was not resumed. */
    fun markRestored() {
        _status.value = SessionStatus.Disconnected("Not connected since the app restarted")
    }

    @Volatile private var startWhenSized = false

    /**
     * Starts once a view has measured the real terminal size, so the program
     * doesn't begin at a placeholder size and immediately get SIGWINCH.
     */
    fun startOnFirstLayout() {
        startWhenSized = true
    }

    /** Whether [start] has been called at least once. */
    val started: Boolean get() = generation > 0

    /** Starts a new backend in place of one that exited or failed, keeping the screen. */
    fun restart() {
        backend?.close()
        if (started) core.inject("\r\n".toByteArray())
        start()
    }

    fun clearPrompt(prompt: SessionPrompt) {
        _prompt.compareAndSet(prompt, null)
    }

    fun setCustomTitle(title: String?) {
        _customTitle.value = title?.trim()?.takeIf { it.isNotEmpty() }
        updateTitle()
    }

    val customTitle: String? get() = _customTitle.value

    private fun updateTitle() {
        _title.value = _customTitle.value ?: remoteTitle ?: defaultTitle
    }

    /**
     * Sends raw user input. All input (text, encoded keys, mouse, paste) goes
     * through the core thread so it reaches the backend in the order typed;
     * encoders already run there and call [writeFromCore] directly.
     */
    fun write(data: ByteArray) {
        if (data.isEmpty()) return
        core.post { writeFromCore(data) }
    }

    /** Writes input; must be called on the core thread. Returns the viewport to the bottom. */
    fun writeFromCore(data: ByteArray) {
        if (data.isEmpty()) return
        core.scrollToBottom()
        backend?.write(data)
    }

    fun sendText(text: String) = write(text.toByteArray())

    fun paste(text: String) {
        core.encodePaste(text) { writeFromCore(it) }
    }

    fun resize(size: TerminalSize, cellWidth: Int, cellHeight: Int) {
        if (startWhenSized) {
            startWhenSized = false
            this.size = size
            this.cellWidth = cellWidth
            this.cellHeight = cellHeight
            core.resize(size.cols, size.rows, cellWidth, cellHeight)
            start()
            return
        }
        if (size == this.size && cellWidth == this.cellWidth && cellHeight == this.cellHeight) return
        this.size = size
        this.cellWidth = cellWidth
        this.cellHeight = cellHeight
        core.resize(size.cols, size.rows, cellWidth, cellHeight)
        // On the core thread, in order with input, and never blocking the UI.
        core.post { backend?.resize(size) }
    }

    /** Ends the backend (process group or connection) and frees native state. */
    fun close() {
        generation++
        backend?.close()
        backend = null
        _status.value = SessionStatus.Closing
        frameListener = null
        core.close()
    }
}
