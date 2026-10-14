package org.pocketlab.puppy.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.pocketlab.puppy.BuildConfig
import org.pocketlab.puppy.PuppyApp
import org.pocketlab.puppy.data.ClipboardPolicy
import org.pocketlab.puppy.data.Settings
import org.pocketlab.puppy.runtime.RuntimeManager
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(app: PuppyApp, settings: Settings, navigate: (Screen) -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val repo = app.settings
    val chrome = LocalChrome.current
    val runtime by app.runtime.state.collectAsState()
    var showLicenses by remember { mutableStateOf(false) }

    ScreenScaffold("Settings", onBack) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            SectionHeader("Terminal")
            var font by remember(settings.fontSizeSp) { mutableFloatStateOf(settings.fontSizeSp) }
            SettingRow("Font size", "${font.roundToInt()} sp · pinch the terminal to change it too")
            Slider(
                value = font,
                onValueChange = { font = it },
                onValueChangeFinished = { scope.launch { repo.setFontSize(font.roundToInt().toFloat()) } },
                valueRange = 8f..28f,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            SettingRow("Scrollback", "${settings.scrollbackLines} lines for new sessions")
            Row(Modifier.padding(horizontal = 16.dp)) {
                for (lines in listOf(1_000, 10_000, 50_000)) {
                    FilterChip(
                        selected = settings.scrollbackLines == lines,
                        onClick = { scope.launch { repo.setScrollback(lines) } },
                        label = { Text("%,d".format(lines)) },
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
            }
            SettingRow("Theme", "Ghostty themes and imported color schemes", onClick = { navigate(Screen.Themes) })
            SettingRow("App colors follow the terminal theme", trailing = {
                Switch(settings.uiFollowsTerminal, onCheckedChange = { scope.launch { repo.setUiFollowsTerminal(it) } })
            })
            SettingRow("Keep screen on", "While a terminal is visible", trailing = {
                Switch(settings.keepScreenOn, onCheckedChange = { scope.launch { repo.setKeepScreenOn(it) } })
            })
            SettingRow("Vibrate on bell", trailing = {
                Switch(settings.bell, onCheckedChange = { scope.launch { repo.setBell(it) } })
            })

            SectionHeader("Clipboard")
            SettingRow("Confirm multi-line paste", "Preview before pasting several lines", trailing = {
                Switch(settings.confirmMultilinePaste, onCheckedChange = { scope.launch { repo.setConfirmMultilinePaste(it) } })
            })
            SettingRow("Programs writing the clipboard", "OSC 52. Programs can never read your clipboard.")
            Row(Modifier.padding(horizontal = 16.dp)) {
                for ((policy, label) in listOf(ClipboardPolicy.ASK to "Ask", ClipboardPolicy.ALLOW to "Allow", ClipboardPolicy.DENY to "Deny")) {
                    FilterChip(
                        selected = settings.clipboardWrite == policy,
                        onClick = { scope.launch { repo.setClipboardPolicy(policy) } },
                        label = { Text(label) },
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
            }

            SectionHeader("SSH")
            SettingRow("Hosts", onClick = { navigate(Screen.Hosts(pick = false)) })
            SettingRow("Keys", onClick = { navigate(Screen.Identities) })
            SettingRow("Known hosts", onClick = { navigate(Screen.KnownHosts) })

            SectionHeader("Local environment")
            val (status, action) = when (val r = runtime) {
                is RuntimeManager.State.Ready -> "Installed (revision ${r.revision})" to "Reinstall"
                RuntimeManager.State.Unavailable -> "Not bundled in this build; local terminals use /system/bin/sh" to null
                RuntimeManager.State.Missing -> "Not installed" to "Install"
                is RuntimeManager.State.Failed -> "Failed: ${r.message}" to "Retry"
                RuntimeManager.State.Verifying -> "Verifying…" to null
                is RuntimeManager.State.Extracting -> "Installing… ${r.files} files" to null
                RuntimeManager.State.Activating -> "Activating…" to null
            }
            SettingRow("Runtime", status, trailing = action?.let { label ->
                { TextButton(onClick = { app.reinstallRuntime() }) { Text(label) } }
            })
            Text(
                "Reinstalling keeps your home directory. Close local sessions first.",
                color = chrome.muted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            SectionHeader("About")
            SettingRow("Puppy ${BuildConfig.VERSION_NAME}", "Terminal core: Ghostty libghostty-vt @ ${BuildConfig.GHOSTTY_REVISION.take(12)}")
            SettingRow("Licenses", "GPL-3.0-only; third-party notices", onClick = { showLicenses = !showLicenses })
            if (showLicenses) {
                Text(
                    app.assets.open("licenses/NOTICE.txt").bufferedReader().use { it.readText() },
                    style = MonoStyle,
                    color = chrome.onBar,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            }
        }
    }
}
