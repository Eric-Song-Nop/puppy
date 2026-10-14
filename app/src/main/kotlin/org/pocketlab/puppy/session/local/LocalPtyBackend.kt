package org.pocketlab.puppy.session.local

import android.os.ParcelFileDescriptor
import android.system.OsConstants
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.pocketlab.puppy.session.BackendCapabilities
import org.pocketlab.puppy.session.SessionBackend
import org.pocketlab.puppy.session.SessionKind
import org.pocketlab.puppy.session.SessionStatus
import org.pocketlab.puppy.session.TerminalSize

/** How to start a local process. */
data class LocalCommand(
    val executable: String,
    val args: List<String>,
    val env: Map<String, String>,
    val cwd: String?,
)

/**
 * A process on a pty owned by this app. The child leads its own session;
 * closing sends SIGHUP to its process group, then SIGKILL after a grace period.
 */
class LocalPtyBackend(private val command: LocalCommand) : SessionBackend {
    override val capabilities = BackendCapabilities(SessionKind.LOCAL, reconnectable = false, hasLocalProcess = true)

    @Volatile private var fd = -1
    @Volatile private var pid = -1
    @Volatile private var exited = false
    /** Bounded: a full queue blocks the core thread (backpressure) rather than dropping input. */
    private val outgoing = LinkedBlockingQueue<ByteArray>(MAX_QUEUED)
    private var pfd: ParcelFileDescriptor? = null

    override fun open(size: TerminalSize, listener: SessionBackend.Listener) {
        listener.onStatus(SessionStatus.Starting)
        val outPid = IntArray(1)
        val masterFd = Pty.spawn(
            command.executable.toByteArray(),
            (listOf(command.executable) + command.args).map { it.toByteArray() }.toTypedArray(),
            command.env.map { (k, v) -> "$k=$v".toByteArray() }.toTypedArray(),
            command.cwd?.toByteArray(),
            size.rows, size.cols, size.widthPx, size.heightPx, outPid,
        )
        if (masterFd < 0) {
            listener.onStatus(SessionStatus.Failed("Could not create a pseudo-terminal"))
            return
        }
        fd = masterFd
        pid = outPid[0]
        // ParcelFileDescriptor owns and eventually closes the master fd.
        val descriptor = ParcelFileDescriptor.adoptFd(masterFd)
        pfd = descriptor
        listener.onStatus(SessionStatus.Running)

        Thread({
            val input = FileInputStream(descriptor.fileDescriptor)
            try {
                while (true) {
                    val buf = ByteArray(READ_SIZE)
                    val n = input.read(buf)
                    if (n <= 0) break
                    listener.onOutput(buf, n)
                }
            } catch (_: IOException) {
                // EIO once the last slave fd closes: the process has exited.
            }
        }, "pty-reader-$pid").apply { isDaemon = true }.start()

        Thread({
            val output = FileOutputStream(descriptor.fileDescriptor)
            try {
                while (!exited) {
                    val data = outgoing.poll(500, TimeUnit.MILLISECONDS) ?: continue
                    output.write(data)
                }
            } catch (_: IOException) {
            } catch (_: InterruptedException) {
            }
        }, "pty-writer-$pid").apply { isDaemon = true }.start()

        Thread({
            val code = Pty.waitFor(pid)
            exited = true
            listener.onStatus(SessionStatus.Exited(code))
        }, "pty-waiter-$pid").apply { isDaemon = true }.start()
    }

    override fun write(data: ByteArray) {
        if (exited || fd < 0) return
        while (!exited && fd >= 0) {
            if (outgoing.offer(data, 500, TimeUnit.MILLISECONDS)) return
        }
    }

    override fun resize(size: TerminalSize) {
        if (fd >= 0 && !exited) Pty.setWindowSize(fd, size.rows, size.cols, size.widthPx, size.heightPx)
    }

    override fun close() {
        val p = pid
        if (p > 0 && !exited) {
            Pty.signalGroup(p, OsConstants.SIGHUP)
            Thread({
                Thread.sleep(KILL_GRACE_MS)
                if (!exited) Pty.signalGroup(p, OsConstants.SIGKILL)
            }, "pty-reaper-$p").apply { isDaemon = true }.start()
        }
        try {
            pfd?.close()
        } catch (_: IOException) {
        }
        pfd = null
        fd = -1
    }

    /** Force-kills the process group immediately. */
    fun kill() {
        val p = pid
        if (p > 0 && !exited) Pty.signalGroup(p, OsConstants.SIGKILL)
    }

    companion object {
        private const val READ_SIZE = 8192
        private const val KILL_GRACE_MS = 3000L
        private const val MAX_QUEUED = 4096
    }
}
