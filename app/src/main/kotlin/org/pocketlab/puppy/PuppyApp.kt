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
import org.pocketlab.puppy.terminal.TerminalColors
import org.pocketlab.puppy.terminal.TerminalView

class PuppyApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var sessions: SessionManager
        private set
    lateinit var settings: SettingsRepository
        private set
    lateinit var runtime: RuntimeManager
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
        sessions = SessionManager(this)
        sessions.onSessionsChanged = { count -> SessionService.update(this, count) }

        scope.launch {
            val initial = settings.settings.first()
            sessions.scrollbackLines = initial.scrollbackLines
            sessions.restore()
            settings.settings.map { it.scrollbackLines }.distinctUntilChanged().collect { sessions.scrollbackLines = it }
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

    fun reinstallRuntime() {
        scope.launch {
            runtime.uninstall()
            runtime.install()
        }
    }
}
