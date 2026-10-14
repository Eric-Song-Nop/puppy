package org.pocketlab.puppy.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import org.pocketlab.puppy.PuppyApp
import org.pocketlab.puppy.data.Settings

/** Top-level destinations (design §10.1). */
sealed interface Screen {
    data object Workspace : Screen
    /** Saved hosts; with [pick] choosing one opens a new SSH session. */
    data class Hosts(val pick: Boolean) : Screen
    data class HostEdit(val id: Long?) : Screen
    data object Identities : Screen
    data object KnownHosts : Screen
    data object Themes : Screen
    data object Settings : Screen
}

@Composable
fun PuppyRoot(app: PuppyApp) {
    val settings by app.settings.settings.collectAsState(initial = Settings())
    val colors by app.colors.collectAsState()
    val stack = remember { mutableStateListOf<Screen>(Screen.Workspace) }
    val navigate: (Screen) -> Unit = { stack.add(it) }
    val back: () -> Unit = { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    BackHandler(enabled = stack.size > 1, onBack = back)

    val chrome = Chrome.from(if (settings.uiFollowsTerminal) colors else org.pocketlab.puppy.terminal.TerminalColors.DEFAULT)
    PuppyTheme(chrome) {
        Box(Modifier.fillMaxSize().background(chrome.terminalBackground).windowInsetsPadding(WindowInsets.safeDrawing)) {
            when (val screen = stack.last()) {
                Screen.Workspace -> WorkspaceScreen(app, settings, navigate)
                Screen.Settings -> SettingsScreen(app, settings, navigate, back)
                else -> Destinations(app, screen, navigate, back)
            }
        }
    }
}
