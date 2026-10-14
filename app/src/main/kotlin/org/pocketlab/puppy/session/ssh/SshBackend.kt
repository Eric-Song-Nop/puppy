package org.pocketlab.puppy.session.ssh

import android.content.Context
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.PublicKey
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.DisconnectReason
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.connection.channel.direct.SessionChannel
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.method.AuthKeyboardInteractive
import net.schmizz.sshj.userauth.method.AuthMethod
import net.schmizz.sshj.userauth.method.AuthPassword
import net.schmizz.sshj.userauth.method.AuthPublickey
import net.schmizz.sshj.userauth.method.ChallengeResponseProvider
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource
import org.pocketlab.puppy.data.AppDatabase
import org.pocketlab.puppy.data.HostProfile
import org.pocketlab.puppy.data.KnownHost
import org.pocketlab.puppy.data.vault.Vault
import org.pocketlab.puppy.data.vault.VaultException
import org.pocketlab.puppy.session.BackendCapabilities
import org.pocketlab.puppy.session.SessionBackend
import org.pocketlab.puppy.session.SessionKind
import org.pocketlab.puppy.session.SessionPrompt
import org.pocketlab.puppy.session.SessionStatus
import org.pocketlab.puppy.session.TerminalSize

/**
 * An interactive SSH shell over SSHJ (design §9.1).
 *
 * Host keys are checked against confirmed [KnownHost] entries; unknown or
 * changed keys are shown to the user through [SessionPrompt.HostKey] and
 * never accepted in the background. Authentication tries the profile's
 * identity, then a saved password, then keyboard-interactive and password
 * prompts. Passwords and passphrases are char arrays zeroed after use.
 * Decrypted private keys pass through a String because SSHJ's key parsers
 * only accept one (and keep their own copies), so they stay in memory
 * until collected. Secrets are never logged or put in status messages.
 */
class SshBackend(
    context: Context,
    private val profile: HostProfile,
    private val db: AppDatabase = AppDatabase.get(context),
    private val vault: Vault = Vault.get(context),
) : SessionBackend {

    override val capabilities = BackendCapabilities(SessionKind.SSH, reconnectable = true, hasLocalProcess = false)

    @Volatile private var closed = false
    @Volatile private var size: TerminalSize = TerminalSize(80, 24, 0, 0)
    @Volatile private var client: SSHClient? = null
    @Volatile private var shell: Session.Shell? = null
    /**
     * Keystrokes ([ByteArray]) and window-change markers ([RESIZE]), sent in
     * order by one thread. Bounded: when full, writers block (backpressure);
     * input is never dropped.
     */
    private val outgoing = LinkedBlockingQueue<Any>(MAX_QUEUED)
    /** Latest size not yet sent; resizes coalesce instead of queuing each one. */
    private val pendingSize = AtomicReference<TerminalSize?>(null)
    private val pending = CopyOnWriteArraySet<CountDownLatch>()
    private lateinit var listener: SessionBackend.Listener

    override fun open(size: TerminalSize, listener: SessionBackend.Listener) {
        this.size = size
        this.listener = listener
        Thread({ run() }, "ssh-${profile.id}").apply { isDaemon = true }.start()
    }

    private fun run() {
        listener.onStatus(SessionStatus.Connecting)
        val ssh = SSHClient(DefaultConfig())
        client = ssh
        try {
            SshSecurity.init()
            ssh.connectTimeout = CONNECT_TIMEOUT_MS
            // Host key and auth prompts wait for the user; don't let key exchange time out under them.
            ssh.transport.timeoutMs = PROMPT_TIMEOUT_MS
            ssh.addHostKeyVerifier(Verifier())
            ssh.connect(profile.hostname, profile.port)
            if (closed) return
            ssh.connection.keepAlive.keepAliveInterval = KEEPALIVE_SECONDS

            listener.onStatus(SessionStatus.Authenticating)
            authenticate(ssh)
            if (closed) return
            ssh.transport.timeoutMs = DEFAULT_TIMEOUT_MS

            val session = ssh.startSession()
            val s = size
            session.allocatePTY(TERM, s.cols, s.rows, s.widthPx, s.heightPx, emptyMap())
            val sh = session.startShell()
            shell = sh
            listener.onStatus(SessionStatus.Running)
            startWriter(sh)
            profile.initialCommand?.takeIf { it.isNotBlank() }?.let { write((it.trim() + "\r").toByteArray()) }
            // Apply any resize that raced with startup.
            if (size != s) resize(size)
            readLoop(sh)
        } catch (e: Exception) {
            if (!closed) listener.onStatus(statusFor(e))
        } finally {
            cancelPrompts()
            disconnect()
        }
    }

    private fun readLoop(sh: Session.Shell) {
        val input = sh.inputStream
        try {
            while (!closed) {
                val buf = ByteArray(READ_SIZE)
                val n = input.read(buf)
                if (n < 0) break
                if (n > 0) listener.onOutput(buf, n)
            }
        } catch (e: IOException) {
            if (!closed) {
                listener.onStatus(SessionStatus.Disconnected(networkReason(e)))
                return
            }
        }
        if (closed) return
        try {
            sh.join(2, TimeUnit.SECONDS)
        } catch (_: Exception) {
        }
        val code = (sh as? SessionChannel)?.exitStatus
        listener.onStatus(
            if (code != null) SessionStatus.Exited(code) else SessionStatus.Disconnected("Connection closed by server"),
        )
    }

    private fun startWriter(sh: Session.Shell) {
        Thread({
            val out = sh.outputStream
            try {
                while (!closed) {
                    var item: Any = outgoing.poll(500, TimeUnit.MILLISECONDS) ?: continue
                    // Batch consecutive keystrokes into one packet; a window change
                    // flushes first so requests keep their order with the data.
                    while (true) {
                        when (item) {
                            is ByteArray -> out.write(item)
                            RESIZE -> pendingSize.getAndSet(null)?.let { sz ->
                                out.flush()
                                sh.changeWindowDimensions(sz.cols, sz.rows, sz.widthPx, sz.heightPx)
                            }
                        }
                        item = outgoing.poll() ?: break
                    }
                    out.flush()
                }
            } catch (_: IOException) {
            } catch (_: InterruptedException) {
            }
        }, "ssh-writer-${profile.id}").apply { isDaemon = true }.start()
    }

    override fun write(data: ByteArray) {
        // Typed before the shell is up (connecting/authenticating) is kept and sent once it is.
        while (!closed) {
            if (outgoing.offer(data, 500, TimeUnit.MILLISECONDS)) return
        }
    }

    override fun resize(size: TerminalSize) {
        this.size = size
        // Sent from the writer thread: SSHJ's packet encoder must not be used
        // from two threads at once, or the stream's MAC breaks.
        if (shell == null || closed) return
        // Only enqueue a marker if none is pending; the writer sends the latest size.
        if (pendingSize.getAndSet(size) == null) {
            while (!closed && !outgoing.offer(RESIZE, 500, TimeUnit.MILLISECONDS)) Unit
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        cancelPrompts()
        Thread({ disconnect() }, "ssh-close-${profile.id}").apply { isDaemon = true }.start()
    }

    private fun disconnect() {
        val c = client ?: return
        try {
            shell?.close()
        } catch (_: Exception) {
        }
        try {
            c.disconnect()
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------ prompts

    /** Shows a prompt and blocks this (non-UI) thread until it is answered or the session closes. */
    private fun <T> ask(build: ((T?) -> Unit) -> SessionPrompt): T? {
        if (closed) return null
        val latch = CountDownLatch(1)
        val result = AtomicReference<T?>(null)
        pending += latch
        // close() may have run between the first check and registering the latch.
        if (closed) {
            pending -= latch
            return null
        }
        listener.onPrompt(build { answer ->
            if (latch.count > 0) {
                result.set(answer)
                latch.countDown()
            }
        })
        try {
            latch.await(PROMPT_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
        } finally {
            pending -= latch
        }
        return if (closed) null else result.get()
    }

    private fun cancelPrompts() {
        pending.forEach { it.countDown() }
        pending.clear()
    }

    private inner class Verifier : HostKeyVerifier {
        override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
            val algorithm = SshKeyCodec.algorithm(key)
            val blob = SshKeyCodec.blob(key)
            val encoded = Base64.getEncoder().encodeToString(blob)
            val fingerprint = SshKeyCodec.fingerprint(blob)
            val known = db.knownHosts().forHost(profile.hostname, profile.port)
            val decision = HostKeys.decide(known, algorithm, encoded)
            if (decision == HostKeyDecision.Match) return true
            val changed = decision is HostKeyDecision.Mismatch
            val accepted = ask<Boolean> { answer ->
                SessionPrompt.HostKey(profile.hostname, profile.port, algorithm, fingerprint, changed) { answer(it) }
            } == true
            if (accepted) {
                // A confirmed replacement supersedes every old key for this host.
                if (changed) known.forEach { db.knownHosts().deleteBlocking(it) }
                db.knownHosts().upsert(KnownHost(host = profile.hostname, port = profile.port, algorithm = algorithm, publicKey = encoded, fingerprint = fingerprint))
            }
            return accepted
        }

        override fun findExistingAlgorithms(hostname: String, port: Int): List<String> =
            db.knownHosts().forHost(profile.hostname, profile.port).map { it.algorithm }
    }

    // --------------------------------------------------------------- auth

    private fun authenticate(ssh: SSHClient) {
        val methods = ArrayList<AuthMethod>()
        identityMethod()?.let(methods::add)
        if (profile.savePassword && vault.exists(Vault.passwordEntry(profile.id))) {
            methods += AuthPassword(SavedPassword())
        }
        methods += AuthKeyboardInteractive(Interactive())
        methods += AuthPassword(PromptedPassword())
        try {
            ssh.auth(profile.username, methods)
        } catch (e: UserAuthException) {
            throw AuthFailed()
        }
    }

    private fun identityMethod(): AuthMethod? {
        val id = profile.identityId ?: return null
        val identity = db.identities().getBlocking(id) ?: return null
        val bytes = try {
            vault.get(identity.encryptedFile)
        } catch (e: VaultException) {
            // Shown in the terminal so the user knows why key auth was skipped.
            notice("Key \"${identity.name}\" is unavailable: ${e.message}")
            return null
        }
        val text = String(bytes, Charsets.UTF_8)
        bytes.fill(0)
        val finder = object : PasswordFinder {
            private var tries = 0
            override fun reqPassword(resource: Resource<*>?): CharArray? {
                tries++
                return ask<CharArray> { answer ->
                    SessionPrompt.Password("Passphrase for key \"${identity.name}\"") { answer(it) }
                }
            }

            override fun shouldRetry(resource: Resource<*>?) = tries < MAX_TRIES && !closed
        }
        return try {
            AuthPublickey(SshKeyImporter.provider(text, finder))
        } catch (e: Exception) {
            notice("Key \"${identity.name}\" could not be read")
            null
        }
    }

    private inner class SavedPassword : PasswordFinder {
        private var used = false
        override fun reqPassword(resource: Resource<*>?): CharArray? {
            if (used) return null
            used = true
            return try {
                vault.getChars(Vault.passwordEntry(profile.id))
            } catch (e: VaultException) {
                notice("Saved password is unavailable: ${e.message}")
                null
            }
        }

        override fun shouldRetry(resource: Resource<*>?) = false
    }

    private inner class PromptedPassword : PasswordFinder {
        private var tries = 0
        override fun reqPassword(resource: Resource<*>?): CharArray? {
            tries++
            val message = if (tries == 1) "Password for ${profile.address}" else "Wrong password. Password for ${profile.address}"
            return ask<CharArray> { answer -> SessionPrompt.Password(message) { answer(it) } }
        }

        override fun shouldRetry(resource: Resource<*>?) = tries < MAX_TRIES && !closed
    }

    private inner class Interactive : ChallengeResponseProvider {
        private var name = ""
        private var instruction = ""
        override fun getSubmethods(): List<String> = emptyList()

        override fun init(resource: Resource<*>?, name: String?, instruction: String?) {
            this.name = name.orEmpty()
            this.instruction = instruction.orEmpty()
        }

        override fun getResponse(prompt: String, echo: Boolean): CharArray? {
            val answers = ask<List<CharArray>> { answer ->
                SessionPrompt.KeyboardInteractive(name, instruction, listOf(prompt to echo)) { answer(it) }
            } ?: return null
            return answers.firstOrNull()
        }

        override fun shouldRetry() = false
    }

    /** Prints an informational line into the terminal before the shell starts. */
    private fun notice(text: String) {
        val bytes = "\u001b[33m$text\u001b[0m\r\n".toByteArray()
        listener.onOutput(bytes, bytes.size)
    }

    // ------------------------------------------------------------ errors

    private class AuthFailed : Exception("Authentication failed")

    private fun statusFor(e: Throwable): SessionStatus = when {
        e is AuthFailed -> SessionStatus.Failed("Authentication failed")
        e is TransportException && e.disconnectReason == DisconnectReason.HOST_KEY_NOT_VERIFIABLE ->
            SessionStatus.Failed("Host key not accepted")
        e is UnknownHostException -> SessionStatus.Failed("Unknown host ${profile.hostname}")
        e is ConnectException -> SessionStatus.Failed("Connection refused by ${profile.hostname}:${profile.port}")
        e is NoRouteToHostException -> SessionStatus.Failed("No route to ${profile.hostname}")
        e is SocketTimeoutException -> SessionStatus.Failed("Connection timed out")
        e is TransportException -> SessionStatus.Disconnected(networkReason(e))
        e is IOException -> SessionStatus.Disconnected(networkReason(e))
        else -> SessionStatus.Failed("SSH error (${e.javaClass.simpleName})")
    }

    /** A user-facing reason that never includes exception messages (which may echo input). */
    private fun networkReason(e: Throwable): String = when (e) {
        is SocketTimeoutException, is InterruptedIOException -> "Connection timed out"
        is TransportException -> when (e.disconnectReason) {
            DisconnectReason.BY_APPLICATION -> "Closed by server"
            DisconnectReason.CONNECTION_LOST -> "Connection lost"
            DisconnectReason.PROTOCOL_ERROR -> "Protocol error"
            else -> "Connection lost"
        }
        else -> "Connection lost"
    }

    companion object {
        const val TERM = "xterm-256color"
        private val RESIZE = Any()
        private const val MAX_QUEUED = 4096
        private const val READ_SIZE = 16 * 1024
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val DEFAULT_TIMEOUT_MS = 30_000
        private const val PROMPT_TIMEOUT_MS = 5 * 60_000
        private const val KEEPALIVE_SECONDS = 30
        private const val MAX_TRIES = 3
    }
}
