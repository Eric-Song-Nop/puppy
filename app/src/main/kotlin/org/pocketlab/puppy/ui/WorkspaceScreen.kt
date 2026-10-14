package org.pocketlab.puppy.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.inputmethod.InputMethodManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import org.pocketlab.puppy.PuppyApp
import org.pocketlab.puppy.data.ClipboardPolicy
import org.pocketlab.puppy.data.Settings
import org.pocketlab.puppy.runtime.RuntimeManager
import org.pocketlab.puppy.session.SessionKind
import org.pocketlab.puppy.session.SessionSpec
import org.pocketlab.puppy.session.SessionStatus
import org.pocketlab.puppy.session.TerminalSession
import org.pocketlab.puppy.terminal.TerminalView

@Composable
fun WorkspaceScreen(app: PuppyApp, settings: Settings, navigate: (Screen) -> Unit) {
    val chrome = LocalChrome.current
    val manager = app.sessions
    val sessions by manager.sessions.collectAsState()
    val activeId by manager.activeId.collectAsState()
    val active = sessions.firstOrNull { it.id == activeId }
    val runtime by app.runtime.state.collectAsState()

    BoxWithConstraints(Modifier.fillMaxSize().background(chrome.terminalBackground)) {
        val wide = maxWidth >= 720.dp
        var sidebarOpen by rememberSaveable { mutableStateOf<Boolean?>(null) }
        val showSidebar = sidebarOpen ?: wide

        Row(Modifier.fillMaxSize()) {
            if (wide && showSidebar) {
                Sidebar(sessions, activeId, Modifier.widthIn(min = 200.dp, max = 260.dp).fillMaxHeight(), onSelect = manager::setActive, onClose = { closeRequest(app, it) })
                VerticalDivider(color = chrome.divider)
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                TopBar(
                    active = active,
                    onToggleSidebar = { sidebarOpen = !showSidebar },
                    onNewLocal = { manager.newLocal() },
                    onNewSsh = { navigate(Screen.Hosts(pick = true)) },
                    onThemes = { navigate(Screen.Themes) },
                    navigate = navigate,
                    app = app,
                )
                HorizontalDivider(color = chrome.divider)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (active == null) {
                        EmptyState(runtime, onLocal = { manager.newLocal() }, onSsh = { navigate(Screen.Hosts(pick = true)) }, onRetry = {
                            app.installRuntime()
                        })
                    } else {
                        TerminalArea(app, active, settings, onTap = { if (!wide) sidebarOpen = false })
                        SessionBanner(active, onClose = { manager.close(active.id) })
                    }
                }
            }
        }

        if (!wide) {
            AnimatedVisibility(showSidebar, enter = fadeIn(), exit = fadeOut()) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)).clickable(onClickLabel = "Close sessions") { sidebarOpen = false })
            }
            AnimatedVisibility(showSidebar, enter = slideInHorizontally { -it }, exit = slideOutHorizontally { -it }) {
                Surface(color = chrome.sidebar, modifier = Modifier.fillMaxHeight().widthIn(max = 300.dp).fillMaxWidth(0.82f)) {
                    Sidebar(sessions, activeId, Modifier.fillMaxSize(), onSelect = {
                        manager.setActive(it)
                        sidebarOpen = false
                    }, onClose = { closeRequest(app, it) })
                }
            }
        }
    }

    // Questions from any session (passwords, host keys), even background ones.
    for (s in sessions) key(s.id) { PromptHost(s) }
    ClipboardRequests(sessions, settings)
    CloseRequestHost(app)
}

// ------------------------------------------------------------------ top bar

@Composable
private fun TopBar(
    active: TerminalSession?,
    onToggleSidebar: () -> Unit,
    onNewLocal: () -> Unit,
    onNewSsh: () -> Unit,
    onThemes: () -> Unit,
    navigate: (Screen) -> Unit,
    app: PuppyApp,
) {
    val chrome = LocalChrome.current
    var newMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(48.dp).background(chrome.bar),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onToggleSidebar) { Icon(Icons.Default.Menu, contentDescription = "Sessions", tint = chrome.onBar) }
        Column(Modifier.weight(1f)) {
            if (active != null) {
                val title by active.title.collectAsState()
                val status by active.status.collectAsState()
                Text(title, color = chrome.onBar, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                statusText(status)?.let {
                    Text(it, color = chrome.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                }
            } else {
                Text("Puppy", color = chrome.onBar, style = MaterialTheme.typography.titleSmall)
            }
        }
        Box {
            IconButton(onClick = { newMenu = true }) { Icon(Icons.Default.Add, contentDescription = "New session", tint = chrome.onBar) }
            DropdownMenu(newMenu, onDismissRequest = { newMenu = false }) {
                DropdownMenuItem(text = { Text("Local terminal") }, leadingIcon = { Icon(Icons.Default.Terminal, null) }, onClick = { newMenu = false; onNewLocal() })
                DropdownMenuItem(text = { Text("SSH connection…") }, leadingIcon = { Icon(Icons.Default.Cloud, null) }, onClick = { newMenu = false; onNewSsh() })
            }
        }
        IconButton(onClick = onThemes) { Icon(Icons.Default.Palette, contentDescription = "Theme", tint = chrome.onBar) }
        Box {
            IconButton(onClick = { moreMenu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More", tint = chrome.onBar) }
            DropdownMenu(moreMenu, onDismissRequest = { moreMenu = false }) {
                if (active != null) {
                    DropdownMenuItem(text = { Text("Paste") }, onClick = { moreMenu = false; app.terminalView?.pasteFromClipboard() })
                    DropdownMenuItem(text = { Text("Rename tab") }, onClick = { moreMenu = false; renaming = true })
                    DropdownMenuItem(text = { Text("Close tab") }, onClick = { moreMenu = false; closeRequest(app, active.id) })
                    HorizontalDivider()
                }
                DropdownMenuItem(text = { Text("Hosts") }, onClick = { moreMenu = false; navigate(Screen.Hosts(pick = false)) })
                DropdownMenuItem(text = { Text("Keys") }, onClick = { moreMenu = false; navigate(Screen.Identities) })
                DropdownMenuItem(text = { Text("Known hosts") }, onClick = { moreMenu = false; navigate(Screen.KnownHosts) })
                DropdownMenuItem(text = { Text("Settings") }, onClick = { moreMenu = false; navigate(Screen.Settings) })
            }
        }
    }
    if (renaming && active != null) {
        RenameDialog(active.customTitle ?: "", onRename = {
            app.sessions.rename(active.id, it)
            renaming = false
        }, onCancel = { renaming = false })
    }
}

fun statusText(status: SessionStatus): String? = when (status) {
    SessionStatus.Running -> null
    SessionStatus.Starting -> "Starting…"
    SessionStatus.Connecting -> "Connecting…"
    SessionStatus.Authenticating -> "Authenticating…"
    SessionStatus.Closing -> "Closing…"
    is SessionStatus.Exited -> if (status.code >= 0) "Exited with code ${status.code}" else "Killed by signal ${-status.code}"
    is SessionStatus.Disconnected -> "Disconnected: ${status.reason}"
    is SessionStatus.Failed -> status.message
}

// ------------------------------------------------------------------ sidebar

@Composable
private fun Sidebar(
    sessions: List<TerminalSession>,
    activeId: String?,
    modifier: Modifier,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
) {
    val chrome = LocalChrome.current
    Column(modifier.background(chrome.sidebar)) {
        Text(
            "Sessions",
            color = chrome.muted,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 6.dp),
        )
        if (sessions.isEmpty()) {
            Text("No open sessions", color = chrome.muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
        }
        LazyColumn(Modifier.weight(1f)) {
            items(sessions, key = { it.id }) { s ->
                SessionRow(s, s.id == activeId, onSelect = { onSelect(s.id) }, onClose = { onClose(s.id) })
            }
        }
    }
}

@Composable
private fun SessionRow(session: TerminalSession, selected: Boolean, onSelect: () -> Unit, onClose: () -> Unit) {
    val chrome = LocalChrome.current
    val title by session.title.collectAsState()
    val status by session.status.collectAsState()
    val ssh = session.spec is SessionSpec.Ssh
    val statusLabel = statusText(status) ?: "Running"
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = 6.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) chrome.selected else Color.Transparent)
            .clickable(onClick = onSelect)
            .semantics { contentDescription = "${if (ssh) "SSH" else "Local"} session $title, $statusLabel" }
            .padding(start = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Icon(if (ssh) Icons.Default.Cloud else Icons.Default.Terminal, null, tint = if (selected) chrome.accent else chrome.muted, modifier = Modifier.size(20.dp))
            Box(
                Modifier.align(Alignment.BottomEnd).size(8.dp).clip(CircleShape).background(
                    when (status) {
                        SessionStatus.Running -> chrome.accent
                        is SessionStatus.Failed -> chrome.danger
                        is SessionStatus.Exited, is SessionStatus.Disconnected, SessionStatus.Closing -> chrome.muted.copy(alpha = 0.5f)
                        else -> chrome.muted
                    },
                ),
            )
        }
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(title, color = chrome.onBar, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            if (status != SessionStatus.Running) {
                Text(statusLabel, color = chrome.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
            }
        }
        IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close $title", tint = chrome.muted, modifier = Modifier.size(18.dp)) }
    }
}

// ----------------------------------------------------------------- terminal

@Composable
private fun TerminalArea(app: PuppyApp, session: TerminalSession, settings: Settings, onTap: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingPaste by remember { mutableStateOf<String?>(null) }
    val colors = app.sessions.colors
    val view = remember {
        TerminalView(context).also { app.terminalView = it }
    }
    AndroidView(
        factory = { view },
        modifier = Modifier.fillMaxSize(),
        update = { v ->
            if (v.fontSizeSp != settings.fontSizeSp) v.setFont(Typeface.MONOSPACE, settings.fontSizeSp)
            v.setSelectionColors(colors.selectionBackground, colors.selectionForeground)
            v.keepScreenOn = settings.keepScreenOn
            v.onTap = onTap
            v.onFontSizeChanged = { sp -> scope.launch { app.settings.setFontSize(sp) } }
            v.pasteHandler = { text ->
                if (settings.confirmMultilinePaste && text.contains('\n')) pendingPaste = text
                else app.sessions.active?.paste(text)
            }
            v.session = session
            app.sessions.lastSize = session.size
        },
    )
    LaunchedEffect(session.id) {
        view.requestFocus()
    }
    DisposableEffect(view) {
        onDispose {
            // Other screens don't type into the terminal; don't leave its keyboard up.
            context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(view.windowToken, 0)
            view.clearSelection()
            // Detach so the session stops producing frames nobody sees.
            view.session = null
        }
    }
    LaunchedEffect(session.id, settings.bell) {
        session.bells.collect {
            if (settings.bell) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }
    pendingPaste?.let { text ->
        PasteConfirmDialog(text, onPaste = {
            session.paste(text)
            pendingPaste = null
        }, onCancel = { pendingPaste = null })
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.SessionBanner(session: TerminalSession, onClose: () -> Unit) {
    val chrome = LocalChrome.current
    val status by session.status.collectAsState()
    if (status.isLive || status == SessionStatus.Closing) return
    val ssh = session.capabilities?.kind == SessionKind.SSH || session.spec is SessionSpec.Ssh
    Surface(
        color = chrome.bar,
        shape = RoundedCornerShape(12.dp),
        shadowElevation = 4.dp,
        modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp).widthIn(max = 520.dp).fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                statusText(status) ?: "",
                color = if (status is SessionStatus.Failed) chrome.danger else chrome.onBar,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { session.restart() }) { Text(if (ssh) "Reconnect" else "Restart") }
            TextButton(onClick = onClose) { Text("Close") }
        }
    }
}

// --------------------------------------------------------------- empty state

@Composable
private fun EmptyState(runtime: RuntimeManager.State, onLocal: () -> Unit, onSsh: () -> Unit, onRetry: () -> Unit) {
    val chrome = LocalChrome.current
    val installing = runtime is RuntimeManager.State.Verifying || runtime is RuntimeManager.State.Extracting ||
        runtime is RuntimeManager.State.Activating
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            EntryButton("Local terminal", Icons.Default.Terminal, enabled = !installing, onClick = onLocal)
            EntryButton("SSH connection", Icons.Default.Cloud, enabled = true, onClick = onSsh)
        }
        Spacer(Modifier.height(20.dp))
        val note = when (runtime) {
            RuntimeManager.State.Verifying -> "Checking the local environment…"
            is RuntimeManager.State.Extracting -> "Installing the local environment… ${runtime.files} files"
            RuntimeManager.State.Activating -> "Activating the local environment…"
            RuntimeManager.State.Unavailable -> "This build has no bundled local environment for this device. Local terminals use Android's system shell."
            is RuntimeManager.State.Failed -> "Local environment install failed: ${runtime.message}"
            else -> null
        }
        if (note != null) {
            Text(note, color = if (runtime is RuntimeManager.State.Failed) chrome.danger else chrome.muted, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.widthIn(max = 420.dp))
        }
        if (runtime is RuntimeManager.State.Failed) {
            TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}

@Composable
private fun EntryButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, onClick: () -> Unit) {
    val chrome = LocalChrome.current
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = chrome.onBar),
        modifier = Modifier.widthIn(min = 150.dp).height(96.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = chrome.accent, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(8.dp))
            Text(label)
        }
    }
}

// ---------------------------------------------------- prompts and clipboard

@Composable
private fun PromptHost(session: TerminalSession) {
    val prompt by session.prompt.collectAsState()
    val title by session.title.collectAsState()
    prompt?.let { p -> SessionPromptDialog(title, p) { session.clearPrompt(p) } }
}

@Composable
private fun ClipboardRequests(sessions: List<TerminalSession>, settings: Settings) {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<Pair<String, String>?>(null) }
    fun write(text: String) {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Terminal", text))
    }
    for (s in sessions) key(s.id) {
        LaunchedEffect(s.id, settings.clipboardWrite) {
            s.clipboardRequests.collect { text ->
                when (settings.clipboardWrite) {
                    ClipboardPolicy.ALLOW -> write(text)
                    ClipboardPolicy.DENY -> Unit
                    ClipboardPolicy.ASK -> pending = s.title.value to text
                }
            }
        }
    }
    pending?.let { (title, text) ->
        ClipboardWriteDialog(title, text, onAllow = { write(text); pending = null }, onDeny = { pending = null })
    }
}

private val closeTarget = mutableStateOf<String?>(null)

/** Asks before ending a running session; finished ones close directly. */
private fun closeRequest(app: PuppyApp, id: String) {
    val s = app.sessions.sessions.value.firstOrNull { it.id == id } ?: return
    if (s.status.value.isLive) closeTarget.value = id else app.sessions.close(id)
}

@Composable
private fun CloseRequestHost(app: PuppyApp) {
    val id = closeTarget.value ?: return
    val s = app.sessions.sessions.collectAsState().value.firstOrNull { it.id == id }
    if (s == null) {
        closeTarget.value = null
        return
    }
    CloseSessionDialog(s.title.value, local = s.spec is SessionSpec.Local, onClose = {
        app.sessions.close(id)
        closeTarget.value = null
    }, onCancel = { closeTarget.value = null })
}
