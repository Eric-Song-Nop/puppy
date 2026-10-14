package org.pocketlab.puppy.session

/** Terminal geometry in cells and pixels. */
data class TerminalSize(val cols: Int, val rows: Int, val widthPx: Int, val heightPx: Int)

/** What a backend can do; the UI shows actions accordingly (design §3.2). */
data class BackendCapabilities(
    val kind: SessionKind,
    val reconnectable: Boolean,
    val hasLocalProcess: Boolean,
)

enum class SessionKind { LOCAL, SSH }

/**
 * Transport for one terminal session: a local pty or an SSH channel.
 *
 * Output must be delivered raw and in order through [Listener.onOutput]
 * from a backend-owned thread; that call may block for backpressure.
 */
interface SessionBackend {
    val capabilities: BackendCapabilities

    interface Listener {
        /** Raw output. The array is owned by the receiver afterwards. */
        fun onOutput(data: ByteArray, length: Int)
        fun onStatus(status: SessionStatus)
        /** The backend needs the user (password, host key...). */
        fun onPrompt(prompt: SessionPrompt) {}
    }

    /** Starts the backend. Must not block the caller. */
    fun open(size: TerminalSize, listener: Listener)

    /** Writes input bytes in order. May block briefly when the outgoing queue is full. */
    fun write(data: ByteArray)

    fun resize(size: TerminalSize)

    /** Ends the session: hang up the connection or terminate the local process group. */
    fun close()
}

/** Public session states (design §6.3). */
sealed interface SessionStatus {
    data object Starting : SessionStatus
    data object Connecting : SessionStatus
    data object Authenticating : SessionStatus
    data object Running : SessionStatus
    data object Closing : SessionStatus
    data class Exited(val code: Int) : SessionStatus
    data class Disconnected(val reason: String) : SessionStatus
    data class Failed(val message: String) : SessionStatus

    val isLive get() = this is Starting || this is Connecting || this is Authenticating || this is Running
}

/** Requests that need an answer from the user. */
sealed interface SessionPrompt {
    /** [answer] null cancels. */
    class Password(val message: String, val answer: (CharArray?) -> Unit) : SessionPrompt
    class KeyboardInteractive(val name: String, val instruction: String, val prompts: List<Pair<String, Boolean>>, val answer: (List<CharArray>?) -> Unit) : SessionPrompt
    class HostKey(val host: String, val port: Int, val algorithm: String, val fingerprint: String, val changed: Boolean, val answer: (Boolean) -> Unit) : SessionPrompt
}
