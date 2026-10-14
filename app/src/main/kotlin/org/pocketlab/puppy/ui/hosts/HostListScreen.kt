package org.pocketlab.puppy.ui.hosts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.pocketlab.puppy.data.AppDatabase
import org.pocketlab.puppy.data.HostProfile

/**
 * Saved SSH hosts. Tapping a host connects (a new session each time);
 * the edit button manages it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostListScreen(
    onConnect: (HostProfile) -> Unit,
    onEdit: (HostProfile) -> Unit,
    onAdd: () -> Unit,
    onBack: () -> Unit,
    onManageIdentities: (() -> Unit)? = null,
    onManageKnownHosts: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val db = remember { AppDatabase.get(context) }
    val hosts by db.hosts().observeAll().collectAsState(initial = null)
    var menu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SSH hosts") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    if (onManageIdentities != null || onManageKnownHosts != null) {
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                onManageIdentities?.let {
                                    DropdownMenuItem(text = { Text("Keys") }, onClick = { menu = false; it() })
                                }
                                onManageKnownHosts?.let {
                                    DropdownMenuItem(text = { Text("Known hosts") }, onClick = { menu = false; it() })
                                }
                            }
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) { Icon(Icons.Default.Add, "Add host") }
        },
    ) { padding ->
        val list = hosts
        when {
            list == null -> Unit
            list.isEmpty() -> Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("No saved hosts", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Add a host to connect over SSH.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onAdd, modifier = Modifier.padding(top = 8.dp)) { Text("Add host") }
            }
            else -> LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(list, key = { it.id }) { host ->
                    HostRow(host, onClick = { onConnect(host) }, onEdit = { onEdit(host) })
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun HostRow(host: HostProfile, onClick: () -> Unit, onEdit: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(host.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                host.address,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "Edit ${host.label}") }
    }
}
