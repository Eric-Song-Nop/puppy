package org.pocketlab.puppy.ui.hosts

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.pocketlab.puppy.data.AppDatabase
import org.pocketlab.puppy.data.Identity
import org.pocketlab.puppy.data.vault.VaultException
import org.pocketlab.puppy.session.ssh.IdentityRepository
import org.pocketlab.puppy.session.ssh.SshKeyImporter

/** Private keys held in the vault: import, generate, copy public key, delete. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdentitiesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val db = remember { AppDatabase.get(context) }
    val repo = remember { IdentityRepository(context) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val identities by db.identities().observeAll().collectAsState(initial = emptyList())

    var addMenu by remember { mutableStateOf(false) }
    var generateName by remember { mutableStateOf<String?>(null) }
    var pendingImport by remember { mutableStateOf<PendingImport?>(null) }
    var deleting by remember { mutableStateOf<Identity?>(null) }

    fun copy(identity: Identity) {
        context.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("Public key", identity.publicKey))
        scope.launch { snackbar.showSnackbar("Public key copied") }
    }

    fun share(identity: Identity) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, identity.publicKey)
        context.startActivity(Intent.createChooser(send, "Share public key"))
    }

    fun runImport(p: PendingImport, passphrase: CharArray?) {
        scope.launch {
            val result = try {
                repo.import(p.name, p.text, passphrase)
            } catch (e: VaultException) {
                IdentityRepository.ImportResult.Invalid(e.message ?: "Vault error")
            } finally {
                passphrase?.fill('\u0000')
            }
            when (result) {
                is IdentityRepository.ImportResult.Imported -> {
                    pendingImport = null
                    snackbar.showSnackbar("Imported ${result.identity.name}")
                }
                IdentityRepository.ImportResult.NeedsPassphrase -> {
                    pendingImport = p.copy(askPassphrase = true, wrongPassphrase = passphrase != null)
                }
                is IdentityRepository.ImportResult.Invalid -> {
                    pendingImport = null
                    snackbar.showSnackbar(result.reason)
                }
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "key"
                    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                        val buf = java.io.ByteArrayOutputStream()
                        val chunk = ByteArray(8192)
                        while (buf.size() <= SshKeyImporter.MAX_KEY_BYTES) {
                            val n = input.read(chunk)
                            if (n < 0) break
                            buf.write(chunk, 0, n)
                        }
                        buf.toByteArray()
                    } ?: ByteArray(0)
                    if (bytes.size > SshKeyImporter.MAX_KEY_BYTES) null else name to String(bytes, Charsets.UTF_8).also { bytes.fill(0) }
                }.getOrNull()
            }
            if (loaded == null) {
                snackbar.showSnackbar("Could not read the file")
            } else {
                runImport(PendingImport(loaded.first.substringBeforeLast('.').ifBlank { loaded.first }, loaded.second), null)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Keys") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        floatingActionButton = {
            Box {
                FloatingActionButton(onClick = { addMenu = true }) { Icon(Icons.Default.Add, "Add key") }
                DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                    DropdownMenuItem(text = { Text("Import private key…") }, onClick = {
                        addMenu = false
                        picker.launch(arrayOf("*/*"))
                    })
                    DropdownMenuItem(text = { Text("Generate Ed25519 key") }, onClick = {
                        addMenu = false
                        generateName = "puppy@" + android.os.Build.MODEL.replace(' ', '-').lowercase()
                    })
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (identities.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No keys", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Import an existing private key or generate a new one. Keys here are separate from ~/.ssh in the local shell.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(identities, key = { it.id }) { identity ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                            Text(
                                identity.name + if (identity.hasPassphrase) "  (passphrase)" else "",
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${identity.type} · ${identity.fingerprint}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = { copy(identity) }) { Icon(Icons.Default.ContentCopy, "Copy public key") }
                        IconButton(onClick = { share(identity) }) { Icon(Icons.Default.Share, "Share public key") }
                        IconButton(onClick = { deleting = identity }) { Icon(Icons.Default.Delete, "Delete key") }
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    generateName?.let { initial ->
        var name by remember(initial) { mutableStateOf(initial) }
        AlertDialog(
            onDismissRequest = { generateName = null },
            title = { Text("Generate Ed25519 key") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name / comment") }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    generateName = null
                    scope.launch {
                        try {
                            val id = repo.generateEd25519(name)
                            copy(id)
                        } catch (e: VaultException) {
                            snackbar.showSnackbar(e.message ?: "Vault error")
                        }
                    }
                }) { Text("Generate") }
            },
            dismissButton = { TextButton(onClick = { generateName = null }) { Text("Cancel") } },
        )
    }

    pendingImport?.takeIf { it.askPassphrase }?.let { p ->
        var passphrase by remember(p) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("Key passphrase") },
            text = {
                Column {
                    Text(
                        if (p.wrongPassphrase) "Wrong passphrase for \"${p.name}\"." else "\"${p.name}\" is encrypted. Enter its passphrase to verify it. It is not stored and will be asked when connecting.",
                    )
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val chars = passphrase.toCharArray()
                    passphrase = ""
                    pendingImport = p.copy(askPassphrase = false)
                    runImport(p, chars)
                }) { Text("Import") }
            },
            dismissButton = { TextButton(onClick = { pendingImport = null }) { Text("Cancel") } },
        )
    }

    deleting?.let { identity ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete key?") },
            text = { Text("\"${identity.name}\" will be removed from this device. Hosts using it will fall back to passwords. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch { repo.delete(identity) }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

private data class PendingImport(
    val name: String,
    val text: String,
    val askPassphrase: Boolean = false,
    val wrongPassphrase: Boolean = false,
)
