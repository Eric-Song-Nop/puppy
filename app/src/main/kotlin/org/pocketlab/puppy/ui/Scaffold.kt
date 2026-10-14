package org.pocketlab.puppy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** A management screen with the same single-row app bar as the workspace. */
@Composable
fun ScreenScaffold(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val chrome = LocalChrome.current
    Column(Modifier.fillMaxSize().background(chrome.terminalBackground)) {
        Row(Modifier.fillMaxWidth().height(48.dp).background(chrome.bar), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = chrome.onBar) }
            Text(title, color = chrome.onBar, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            actions()
        }
        HorizontalDivider(color = chrome.divider)
        content()
    }
}

@Composable
fun SectionHeader(text: String) {
    Text(
        text,
        color = LocalChrome.current.accent,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
fun SettingRow(
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val chrome = LocalChrome.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = chrome.onBar, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, color = chrome.muted, style = MaterialTheme.typography.bodySmall)
        }
        trailing?.invoke()
    }
}

@Composable
fun PlaceholderScreen(title: String, onBack: () -> Unit) {
    ScreenScaffold(title, onBack) {
        Text("Not available in this build.", color = LocalChrome.current.muted, modifier = Modifier.padding(16.dp))
    }
}
