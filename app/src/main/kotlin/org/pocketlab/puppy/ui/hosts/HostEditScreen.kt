package org.pocketlab.puppy.ui.hosts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.pocketlab.puppy.data.AppDatabase
import org.pocketlab.puppy.data.HostProfile
import org.pocketlab.puppy.data.vault.Vault
import org.pocketlab.puppy.data.vault.VaultException
import org.pocketlab.puppy.session.ssh.HostValidation

/** Adds ([hostId] null) or edits a saved host. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostEditScreen(hostId: Long?, onDone: () -> Unit, onManageIdentities: (() -> Unit)? = null) {
    val context = LocalContext.current
    val db = remember { AppDatabase.get(context) }
    val vault = remember { Vault.get(context) }
    val scope = rememberCoroutineScope()
    val identities by db.identities().observeAll().collectAsState(initial = emptyList())

    var loaded by remember { mutableStateOf(hostId == null) }
    var original by remember { mutableStateOf<HostProfile?>(null) }
    var label by remember { mutableStateOf("") }
    var hostname by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("22") }
    var username by remember { mutableStateOf("") }
    var identityId by remember { mutableStateOf<Long?>(null) }
    var savePassword by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var hasSavedPassword by remember { mutableStateOf(false) }
    var initialCommand by remember { mutableStateOf("") }
    var group by remember { mutableStateOf("") }
    var errors by remember { mutableStateOf(HostValidation()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(hostId) {
        if (hostId == null) return@LaunchedEffect
        val h = db.hosts().get(hostId) ?: return@LaunchedEffect onDone()
        original = h
        label = h.label
        hostname = h.hostname
        port = h.port.toString()
        username = h.username
        identityId = h.identityId
        savePassword = h.savePassword
        initialCommand = h.initialCommand.orEmpty()
        group = h.group.orEmpty()
        hasSavedPassword = withContext(Dispatchers.IO) { vault.exists(Vault.passwordEntry(h.id)) }
        loaded = true
    }

    fun save() {
        val v = HostValidation.validate(label, hostname, port, username)
        errors = v
        if (!v.isValid) return
        val profile = (original ?: HostProfile(label = "", hostname = "", username = "")).copy(
            label = label.trim(),
            hostname = hostname.trim(),
            port = port.trim().toInt(),
            username = username.trim(),
            identityId = identityId,
            savePassword = savePassword,
            initialCommand = initialCommand.trim().ifEmpty { null },
            group = group.trim().ifEmpty { null },
        )
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val id = if (original == null) db.hosts().insert(profile) else profile.id.also { db.hosts().update(profile) }
                    val entry = Vault.passwordEntry(id)
                    when {
                        !savePassword -> vault.delete(entry)
                        password.isNotEmpty() -> vault.putChars(entry, password.toCharArray())
                    }
                }
                password = ""
                onDone()
            } catch (e: VaultException) {
                saveError = e.message
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (hostId == null) "New host" else "Edit host") },
                navigationIcon = {
                    IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Cancel") }
                },
                actions = {
                    if (original != null) {
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Delete host") }
                    }
                    TextButton(onClick = ::save, enabled = loaded) { Text("Save") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            Field("Name", label, { label = it }, errors.label)
            Row {
                Field("Host", hostname, { hostname = it }, errors.hostname, Modifier.weight(1f), KeyboardType.Uri)
                Spacer(Modifier.width(8.dp))
                Field("Port", port, { port = it.filter(Char::isDigit).take(5) }, errors.port, Modifier.width(96.dp), KeyboardType.Number)
            }
            Field("Username", username, { username = it }, errors.username)

            IdentityChooser(
                identities = identities.map { it.id to "${it.name} (${it.type})" },
                selected = identityId,
                onSelect = { identityId = it },
            )
            if (onManageIdentities != null) {
                TextButton(onClick = onManageIdentities) { Text("Manage keys") }
            }

            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = savePassword, onCheckedChange = { savePassword = it })
                Text("Save password on this device")
            }
            if (savePassword) {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(if (hasSavedPassword) "Password (leave empty to keep)" else "Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Stored encrypted with a device key. Local programs in this app run as the same user.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
            Field("Command after login (optional)", initialCommand, { initialCommand = it }, null)
            Field("Group (optional)", group, { group = it }, null)
            saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete host?") },
            text = { Text("\"$label\" and its saved password will be removed. Open sessions stay open.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    val h = original ?: return@TextButton
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            db.hosts().delete(h)
                            vault.delete(Vault.passwordEntry(h.id))
                        }
                        onDone()
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    error: String?,
    modifier: Modifier = Modifier.fillMaxWidth(),
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, autoCorrectEnabled = false),
        modifier = modifier.padding(bottom = 4.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IdentityChooser(identities: List<Pair<Long, String>>, selected: Long?, onSelect: (Long?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val text = identities.firstOrNull { it.first == selected }?.second ?: "None (password only)"
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = text,
            onValueChange = {},
            readOnly = true,
            label = { Text("Key") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("None (password only)") }, onClick = { onSelect(null); expanded = false })
            identities.forEach { (id, name) ->
                DropdownMenuItem(text = { Text(name) }, onClick = { onSelect(id); expanded = false })
            }
        }
    }
}
