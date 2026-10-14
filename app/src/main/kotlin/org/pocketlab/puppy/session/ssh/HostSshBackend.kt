package org.pocketlab.puppy.session.ssh

import android.content.Context
import org.pocketlab.puppy.data.AppDatabase
import org.pocketlab.puppy.session.BackendCapabilities
import org.pocketlab.puppy.session.SessionBackend
import org.pocketlab.puppy.session.SessionKind
import org.pocketlab.puppy.session.SessionStatus
import org.pocketlab.puppy.session.TerminalSize

/**
 * Looks up a saved host off the main thread, then runs an [SshBackend] for it.
 * Each (re)connect reads the profile again, so edits take effect on Reconnect.
 */
class HostSshBackend(private val context: Context, private val hostId: Long) : SessionBackend {
    override val capabilities = BackendCapabilities(SessionKind.SSH, reconnectable = true, hasLocalProcess = false)

    @Volatile private var delegate: SessionBackend? = null
    @Volatile private var closed = false
    @Volatile private var size: TerminalSize? = null

    override fun open(size: TerminalSize, listener: SessionBackend.Listener) {
        this.size = size
        listener.onStatus(SessionStatus.Connecting)
        Thread({
            val profile = AppDatabase.get(context).hosts().getBlocking(hostId)
            if (profile == null) {
                listener.onStatus(SessionStatus.Failed("This host was deleted"))
                return@Thread
            }
            synchronized(this) {
                if (closed) return@Thread
                val backend = SshBackend(context, profile)
                delegate = backend
                backend.open(this.size ?: size, listener)
            }
        }, "ssh-lookup-$hostId").apply { isDaemon = true }.start()
    }

    override fun write(data: ByteArray) {
        delegate?.write(data)
    }

    override fun resize(size: TerminalSize) {
        this.size = size
        delegate?.resize(size)
    }

    override fun close() {
        synchronized(this) {
            closed = true
            delegate?.close()
        }
    }
}
