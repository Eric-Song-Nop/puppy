package org.pocketlab.puppy.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import org.pocketlab.puppy.session.SessionPrompt

/** Shows the dialog for a pending session prompt. [onDone] runs after it is answered. */
@Composable
fun SessionPromptDialog(sessionTitle: String, prompt: SessionPrompt, onDone: () -> Unit) {
    when (prompt) {
        is SessionPrompt.Password -> PasswordDialog(sessionTitle, prompt.message) {
            prompt.answer(it)
            onDone()
        }
        is SessionPrompt.KeyboardInteractive -> KeyboardInteractiveDialog(sessionTitle, prompt) {
            prompt.answer(it)
            onDone()
        }
        is SessionPrompt.HostKey -> HostKeyDialog(prompt) {
            prompt.answer(it)
            onDone()
        }
    }
}

@Composable
private fun PasswordDialog(title: String, message: String, onAnswer: (CharArray?) -> Unit) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { onAnswer(null) },
        title = { Text(title) },
        text = {
            Column {
                Text(message)
                Spacer(Modifier.padding(4.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onAnswer(value.toCharArray()); value = "" }) { Text("Continue") } },
        dismissButton = { TextButton(onClick = { onAnswer(null) }) { Text("Cancel") } },
    )
}

@Composable
private fun KeyboardInteractiveDialog(title: String, prompt: SessionPrompt.KeyboardInteractive, onAnswer: (List<CharArray>?) -> Unit) {
    val values = remember { mutableStateListOf<String>().apply { repeat(prompt.prompts.size) { add("") } } }
    AlertDialog(
        onDismissRequest = { onAnswer(null) },
        title = { Text(prompt.name.ifBlank { title }) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (prompt.instruction.isNotBlank()) Text(prompt.instruction)
                prompt.prompts.forEachIndexed { i, (label, echo) ->
                    OutlinedTextField(
                        value = values[i],
                        onValueChange = { values[i] = it },
                        label = { Text(label.trim()) },
                        singleLine = true,
                        visualTransformation = if (echo) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onAnswer(values.map { it.toCharArray() }) }) { Text("Continue") } },
        dismissButton = { TextButton(onClick = { onAnswer(null) }) { Text("Cancel") } },
    )
}

@Composable
private fun HostKeyDialog(prompt: SessionPrompt.HostKey, onAnswer: (Boolean) -> Unit) {
    val chrome = LocalChrome.current
    AlertDialog(
        onDismissRequest = { onAnswer(false) },
        title = { Text(if (prompt.changed) "Host key changed" else "Unknown host") },
        text = {
            Column {
                if (prompt.changed) {
                    Text(
                        "The key for this server is different from the one you trusted before. " +
                            "This can mean the server was reinstalled, or that someone is intercepting the connection. " +
                            "Check the fingerprint with the server's administrator before continuing.",
                        color = chrome.danger,
                    )
                } else {
                    Text("Check that this fingerprint matches the server before trusting it.")
                }
                Spacer(Modifier.padding(6.dp))
                Text("${prompt.host}:${prompt.port}", fontWeight = FontWeight.SemiBold)
                Text(prompt.algorithm, style = MaterialTheme.typography.bodySmall)
                Text(prompt.fingerprint, style = MonoStyle)
            }
        },
        confirmButton = {
            TextButton(onClick = { onAnswer(true) }) {
                Text(if (prompt.changed) "Replace key and connect" else "Trust and connect", color = if (prompt.changed) chrome.danger else chrome.accent)
            }
        },
        dismissButton = { TextButton(onClick = { onAnswer(false) }) { Text("Cancel") } },
    )
}

@Composable
fun PasteConfirmDialog(text: String, onPaste: () -> Unit, onCancel: () -> Unit) {
    val lines = text.lines()
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Paste ${lines.size} lines?") },
        text = {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                Text(
                    text,
                    style = MonoStyle,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()).padding(8.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onPaste) { Text("Paste") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
fun ClipboardWriteDialog(sessionTitle: String, text: String, onAllow: () -> Unit, onDeny: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDeny,
        title = { Text("Copy to clipboard?") },
        text = {
            Column {
                Text("A program in “$sessionTitle” wants to put ${text.length} characters on your clipboard.")
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small, modifier = Modifier.padding(top = 8.dp)) {
                    Text(text.take(500), style = MonoStyle, modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState()).padding(8.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onAllow) { Text("Allow") } },
        dismissButton = { TextButton(onClick = onDeny) { Text("Deny") } },
    )
}

@Composable
fun RenameDialog(current: String, onRename: (String?) -> Unit, onCancel: () -> Unit) {
    var value by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Rename tab") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                placeholder = { Text("Use the program's title") },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onRename(value) }) { Text("Rename") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
fun CloseSessionDialog(title: String, local: Boolean, onClose: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (local) "End “$title”?" else "Disconnect “$title”?") },
        text = {
            Text(
                if (local) "The shell and every program started from it will be stopped."
                else "The SSH connection will be closed. Programs on the server may stop unless they run in tmux or similar.",
            )
        },
        confirmButton = { TextButton(onClick = onClose) { Text(if (local) "End" else "Disconnect", color = LocalChrome.current.danger) } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
