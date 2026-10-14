package org.pocketlab.puppy.session

import android.content.Context
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import org.pocketlab.puppy.runtime.LocalEnvironment
import org.pocketlab.puppy.session.local.LocalPtyBackend
import org.pocketlab.puppy.terminal.TerminalColors

/**
 * Process-wide registry of open sessions (tabs) and the active one.
 * Owned by the application so UI recreation never ends a session; the
 * foreground [SessionService] keeps the process alive while any exist.
 */
class SessionManager(private val context: Context) {
    /** Creates SSH backends; installed by the SSH layer. */
    fun interface SshBackendFactory {
        fun create(hostId: Long): SessionBackend
    }

    var sshFactory: SshBackendFactory? = null

    private val env = LocalEnvironment(context)
    private val workspaceFile = File(context.filesDir, "workspace.json")

    private val _sessions = MutableStateFlow<List<TerminalSession>>(emptyList())
    val sessions: StateFlow<List<TerminalSession>> = _sessions.asStateFlow()

    private val _activeId = MutableStateFlow<String?>(null)
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    /** Last measured terminal size, used to start sessions with a sensible pty size. */
    @Volatile var lastSize = TerminalSize(80, 24, 800, 480)

    @Volatile var scrollbackLines = 10_000

    var colors: TerminalColors = TerminalColors.DEFAULT
        private set

    /** Starts or stops the foreground service as sessions come and go. */
    var onSessionsChanged: ((count: Int) -> Unit)? = null

    val active: TerminalSession? get() = _sessions.value.firstOrNull { it.id == _activeId.value }

    fun newLocal(): TerminalSession {
        val s = TerminalSession(SessionSpec.Local, "Local", scrollbackLines, lastSize, {
            LocalPtyBackend(env.shellCommand())
        })
        add(s)
        s.start()
        return s
    }

    fun newSsh(hostId: Long, label: String): TerminalSession {
        val factory = checkNotNull(sshFactory) { "SSH is not available" }
        val s = TerminalSession(SessionSpec.Ssh(hostId), label, scrollbackLines, lastSize, { factory.create(hostId) })
        add(s)
        s.start()
        return s
    }

    private fun add(s: TerminalSession) {
        applyColors(s)
        _sessions.update { it + s }
        _activeId.value = s.id
        changed()
    }

    fun setActive(id: String) {
        if (_sessions.value.any { it.id == id }) {
            _activeId.value = id
            save()
        }
    }

    fun move(id: String, delta: Int) {
        _sessions.update { list ->
            val i = list.indexOfFirst { it.id == id }
            val j = i + delta
            if (i < 0 || j !in list.indices) list else list.toMutableList().apply { add(j, removeAt(i)) }
        }
        save()
    }

    /** Ends the session's process or connection and removes its tab. */
    fun close(id: String) {
        val list = _sessions.value
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return
        val s = list[index]
        _sessions.value = list - s
        if (_activeId.value == id) {
            val rest = _sessions.value
            _activeId.value = rest.getOrNull(index.coerceAtMost(rest.size - 1))?.id
        }
        s.close()
        changed()
    }

    fun closeAll() {
        val list = _sessions.value
        _sessions.value = emptyList()
        _activeId.value = null
        list.forEach { it.close() }
        changed()
    }

    fun rename(id: String, title: String?) {
        _sessions.value.firstOrNull { it.id == id }?.setCustomTitle(title)
        save()
    }

    fun setColors(colors: TerminalColors) {
        this.colors = colors
        _sessions.value.forEach(::applyColors)
    }

    /** Temporarily shows [colors] on one session (theme preview); [setColors] or [endPreview] restores. */
    fun previewColors(id: String, colors: TerminalColors) {
        _sessions.value.firstOrNull { it.id == id }?.core?.setColors(colors.background, colors.foreground, colors.cursor, colors.palette)
    }

    fun endPreview(id: String) {
        _sessions.value.firstOrNull { it.id == id }?.let(::applyColors)
    }

    private fun applyColors(s: TerminalSession) {
        s.core.setColors(colors.background, colors.foreground, colors.cursor, colors.palette)
    }

    private fun changed() {
        save()
        onSessionsChanged?.invoke(_sessions.value.size)
    }

    // ------------------------------------------------------- persistence

    /** Saves tab order, kinds and custom titles. Never PIDs or live state. */
    fun save() {
        val tabs = JSONArray()
        for (s in _sessions.value) {
            val o = JSONObject()
            when (val spec = s.spec) {
                SessionSpec.Local -> o.put("kind", "local")
                is SessionSpec.Ssh -> o.put("kind", "ssh").put("hostId", spec.hostId)
            }
            o.put("title", s.title.value)
            s.customTitle?.let { o.put("customTitle", it) }
            o.put("active", s.id == _activeId.value)
            tabs.put(o)
        }
        runCatching {
            val tmp = File(workspaceFile.path + ".tmp")
            tmp.writeText(JSONObject().put("version", 1).put("tabs", tabs).toString())
            tmp.renameTo(workspaceFile)
        }
    }

    /**
     * Restores the tab layout after the process was killed. Local tabs get a
     * fresh shell; SSH tabs wait for the user to reconnect. Nothing claims the
     * old processes are still running.
     */
    fun restore() {
        if (_sessions.value.isNotEmpty() || !workspaceFile.exists()) return
        val tabs = runCatching { JSONObject(workspaceFile.readText()).getJSONArray("tabs") }.getOrNull() ?: return
        var activeId: String? = null
        val restored = ArrayList<TerminalSession>()
        for (i in 0 until tabs.length()) {
            val o = tabs.getJSONObject(i)
            val custom = o.optString("customTitle").takeIf { it.isNotEmpty() }
            val s = when (o.optString("kind")) {
                "local" -> TerminalSession(SessionSpec.Local, "Local", scrollbackLines, lastSize, {
                    LocalPtyBackend(env.shellCommand())
                })
                "ssh" -> {
                    val hostId = o.optLong("hostId")
                    val factory = sshFactory ?: continue
                    TerminalSession(SessionSpec.Ssh(hostId), o.optString("title", "SSH"), scrollbackLines, lastSize, {
                        factory.create(hostId)
                    })
                }
                else -> continue
            }
            s.setCustomTitle(custom)
            applyColors(s)
            if (s.spec is SessionSpec.Local) s.start() else s.markRestored()
            if (o.optBoolean("active")) activeId = s.id
            restored += s
        }
        if (restored.isEmpty()) return
        _sessions.value = restored
        _activeId.value = activeId ?: restored.first().id
        onSessionsChanged?.invoke(restored.size)
    }
}
