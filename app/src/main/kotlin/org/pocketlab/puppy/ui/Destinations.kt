package org.pocketlab.puppy.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import org.pocketlab.puppy.PuppyApp
import org.pocketlab.puppy.data.Settings
import org.pocketlab.puppy.theme.ThemeRepository
import org.pocketlab.puppy.theme.ui.ThemePickerScreen
import org.pocketlab.puppy.toTerminalColors
import org.pocketlab.puppy.ui.hosts.HostEditScreen
import org.pocketlab.puppy.ui.hosts.HostListScreen
import org.pocketlab.puppy.ui.hosts.IdentitiesScreen
import org.pocketlab.puppy.ui.hosts.KnownHostsScreen

/** Screens provided by the SSH and theme packages. */
@Composable
fun Destinations(app: PuppyApp, screen: Screen, navigate: (Screen) -> Unit, back: () -> Unit, backToWorkspace: () -> Unit) {
    val scope = rememberCoroutineScope()
    val settings by app.settings.settings.collectAsState(initial = Settings())
    when (screen) {
        Screen.Themes -> {
            // Previews change only the active tab's default colors; programs' OSC colors survive.
            val previewId = app.sessions.activeId.collectAsState().value
            ThemePickerScreen(
                repository = app.themes,
                currentId = settings.themeId ?: ThemeRepository.DEFAULT_THEME_ID,
                onPreview = { theme -> previewId?.let { app.sessions.previewColors(it, theme.toTerminalColors()) } },
                onApply = { theme ->
                    scope.launch { app.settings.setTheme(theme.id) }
                    app.setColors(theme.toTerminalColors())
                    back()
                },
                onCancel = {
                    previewId?.let { app.sessions.endPreview(it) }
                    back()
                },
            )
        }
        is Screen.Hosts -> HostListScreen(
            onConnect = { host ->
                app.sessions.newSsh(host.id, host.label.ifBlank { host.address })
                backToWorkspace()
            },
            onEdit = { navigate(Screen.HostEdit(it.id)) },
            onAdd = { navigate(Screen.HostEdit(null)) },
            onBack = back,
            onManageIdentities = { navigate(Screen.Identities) },
            onManageKnownHosts = { navigate(Screen.KnownHosts) },
        )
        is Screen.HostEdit -> HostEditScreen(screen.id, onDone = back, onManageIdentities = { navigate(Screen.Identities) })
        Screen.Identities -> IdentitiesScreen(onBack = back)
        Screen.KnownHosts -> KnownHostsScreen(onBack = back)
        else -> PlaceholderScreen(screen.toString(), back)
    }
}
