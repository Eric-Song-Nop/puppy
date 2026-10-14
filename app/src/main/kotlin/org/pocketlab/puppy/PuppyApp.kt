package org.pocketlab.puppy

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.pocketlab.puppy.data.SettingsRepository
import org.pocketlab.puppy.runtime.RuntimeManager
import org.pocketlab.puppy.session.SessionManager
import org.pocketlab.puppy.session.SessionService
import org.pocketlab.puppy.session.ssh.HostSshBackend
import org.pocketlab.puppy.terminal.TerminalColors
import org.pocketlab.puppy.terminal.TerminalView
import org.pocketlab.puppy.theme.TerminalTheme
import org.pocketlab.puppy.theme.ThemeRepository

class PuppyApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var sessions: SessionManager
        private set
    lateinit var settings: SettingsRepository
        private set
    lateinit var runtime: RuntimeManager
        private set
    lateinit var themes: ThemeRepository
        private set

    private val _colors = MutableStateFlow(TerminalColors.DEFAULT)
    /** Default terminal colors of the selected theme. */
    val colors: StateFlow<TerminalColors> = _colors.asStateFlow()

    /** The single terminal view of the visible workspace, for menu actions. */
    var terminalView: TerminalView? = null

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(this)
        runtime = RuntimeManager(this)
        themes = ThemeRepository(this)
        sessions = SessionManager(this)
        sessions.onSessionsChanged = { count -> SessionService.update(this, count) }
        sessions.sshFactory = SessionManager.SshBackendFactory { hostId -> HostSshBackend(this, hostId) }

        scope.launch {
            val initial = settings.settings.first()
            sessions.scrollbackLines = initial.scrollbackLines
            sessions.restore()
            launch {
                settings.settings.map { it.clipboardWrite }.distinctUntilChanged().collect {
                    sessions.clipboardWritesAllowed = it != org.pocketlab.puppy.data.ClipboardPolicy.DENY
                }
            }
            settings.settings.map { it.scrollbackLines }.distinctUntilChanged().collect { sessions.scrollbackLines = it }
        }
        scope.launch {
            settings.settings.map { it.themeId }.distinctUntilChanged().collect { id ->
                setColors(themes.loadOrDefault(id).toTerminalColors())
            }
        }
        installRuntime()
    }

    fun setColors(colors: TerminalColors) {
        _colors.value = colors
        sessions.setColors(colors)
    }

    fun installRuntime() {
        scope.launch {
            runtime.refresh()
            if (runtime.state.value is RuntimeManager.State.Missing || runtime.state.value is RuntimeManager.State.Failed) {
                runtime.install()
            }
        }
    }

    /**
     * Rebuilds the runtime. Refused while a local shell runs, because PREFIX
     * must never be replaced behind active processes (design §5.3).
     */
    fun reinstallRuntime(): Boolean {
        val busy = sessions.sessions.value.any { it.spec is org.pocketlab.puppy.session.SessionSpec.Local && it.status.value.isLive }
        if (busy) return false
        scope.launch {
            runtime.uninstall()
            runtime.install()
        }
        return true
    }
}

fun TerminalTheme.toTerminalColors() = TerminalColors(
    background = background,
    foreground = foreground,
    cursor = cursorColor?.fixed,
    palette = palette,
    // Per-cell references aren't drawn by the renderer; it inverts instead.
    selectionBackground = selectionBackground?.fixed,
    selectionForeground = selectionForeground?.fixed,
)
