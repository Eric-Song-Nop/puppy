package org.pocketlab.puppy.theme.ui

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.pocketlab.puppy.theme.TerminalTheme
import org.pocketlab.puppy.theme.ThemeColor
import org.pocketlab.puppy.theme.ThemeRepository
import org.pocketlab.puppy.theme.ThemeSource

/**
 * Browse, preview, import and apply Ghostty themes (design §8).
 *
 * Selecting a row calls [onPreview] so the host can show the theme on the
 * current session; [onCancel] means the host should restore the previous
 * theme, [onApply] makes the selection permanent.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemePickerScreen(
    repository: ThemeRepository,
    currentId: String,
    onPreview: (TerminalTheme) -> Unit,
    onApply: (TerminalTheme) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reload by remember { mutableStateOf(0) }
    val themes by produceState(emptyList<ThemeRepository.ThemeInfo>(), reload) { value = repository.list() }
    var query by remember { mutableStateOf("") }
    var selectedId by remember { mutableStateOf(currentId) }
    val selected by produceState<TerminalTheme?>(null, selectedId) { value = repository.loadOrDefault(selectedId) }
    var importResult by remember { mutableStateOf<ThemeRepository.ImportResult?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<ThemeRepository.ThemeInfo?>(null) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    val resolver = context.contentResolver
                    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    } ?: "Imported"
                    val text = resolver.openInputStream(uri)?.use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val buf = ByteArray(8192)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            require(out.size() <= MAX_IMPORT_BYTES) { "File is too large for a theme" }
                        }
                        out.toByteArray().decodeToString()
                    } ?: error("Could not read the file")
                    name.substringBeforeLast('.').ifEmpty { name } to text
                }
            }
            loaded.fold(
                onSuccess = { (name, text) ->
                    val result = repository.import(name, text)
                    importResult = result
                    result.theme?.let {
                        reload++
                        selectedId = it.id
                        onPreview(it)
                    }
                },
                onFailure = { importError = it.message ?: "Import failed" },
            )
        }
    }

    val filtered = remember(themes, query) {
        val q = query.trim()
        if (q.isEmpty()) themes else themes.filter { it.name.contains(q, ignoreCase = true) }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Theme") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Cancel")
                    }
                },
                actions = {
                    IconButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Default.FileOpen, contentDescription = "Import theme file")
                    }
                    TextButton(enabled = selected != null, onClick = { selected?.let(onApply) }) {
                        Icon(Icons.Default.Check, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Apply")
                    }
                },
            )
        },
    ) { padding ->
        BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
            val wide = maxWidth >= 600.dp
            val list: @Composable (Modifier) -> Unit = { m ->
                Column(m) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        placeholder = { Text("Search ${themes.size} themes") },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                    val listState = rememberLazyListState()
                    LaunchedEffect(themes.isNotEmpty()) {
                        val index = themes.indexOfFirst { it.id == currentId }
                        if (index > 0) listState.scrollToItem(index)
                    }
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        items(filtered, key = { it.id }) { info ->
                            ThemeRow(
                                repository = repository,
                                info = info,
                                selected = info.id == selectedId,
                                onClick = {
                                    selectedId = info.id
                                    scope.launch { repository.load(info.id)?.let(onPreview) }
                                },
                                onDelete = if (info.kind == ThemeSource.Kind.USER) ({ pendingDelete = info }) else null,
                            )
                        }
                    }
                }
            }
            val preview: @Composable (Modifier) -> Unit = { m ->
                Box(m.padding(12.dp)) {
                    selected?.let { ThemePreview(it, Modifier.fillMaxWidth()) }
                }
            }
            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    list(Modifier.weight(1f).fillMaxHeight())
                    preview(Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    preview(Modifier.fillMaxWidth())
                    HorizontalDivider()
                    list(Modifier.weight(1f))
                }
            }
        }
    }

    importResult?.let { result ->
        AlertDialog(
            onDismissRequest = { importResult = null },
            confirmButton = { TextButton(onClick = { importResult = null }) { Text("OK") } },
            title = { Text(if (result.theme != null) "Theme imported" else "Theme not imported") },
            text = {
                Column(Modifier.heightIn(max = 320.dp)) {
                    if (result.theme != null) Text("Imported “${result.theme.name}”.")
                    if (result.errors.isNotEmpty()) {
                        Text("Invalid lines:", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                        result.errors.take(MAX_LISTED).forEach { Text("Line ${it.line}: ${it.message}", style = MaterialTheme.typography.bodySmall) }
                    }
                    if (result.unsupported.isNotEmpty()) {
                        Text(
                            "Kept but not applied (not appearance settings):",
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        result.unsupported.map { it.key }.distinct().take(MAX_LISTED).forEach {
                            Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            },
        )
    }

    importError?.let { message ->
        AlertDialog(
            onDismissRequest = { importError = null },
            confirmButton = { TextButton(onClick = { importError = null }) { Text("OK") } },
            title = { Text("Import failed") },
            text = { Text(message) },
        )
    }

    pendingDelete?.let { info ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    scope.launch {
                        repository.delete(info.id)
                        if (selectedId == info.id) selectedId = currentId.takeIf { it != info.id } ?: ThemeRepository.DEFAULT_THEME_ID
                        reload++
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
            title = { Text("Delete “${info.name}”?") },
            text = { Text("The imported theme file will be removed from this device.") },
        )
    }
}

@Composable
private fun ThemeRow(
    repository: ThemeRepository,
    info: ThemeRepository.ThemeInfo,
    selected: Boolean,
    onClick: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    val theme by produceState<TerminalTheme?>(null, info.id) { value = repository.load(info.id) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .semantics { if (selected) contentDescription = "${info.name}, selected" },
    ) {
        Swatch(theme)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(info.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            if (info.kind == ThemeSource.Kind.USER) {
                Text("Imported", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (onDelete != null) {
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Delete ${info.name}") }
        }
    }
}

/** Background with "Aa" in the foreground, plus the eight normal ANSI colors. */
@Composable
private fun Swatch(theme: TerminalTheme?) {
    val shape = RoundedCornerShape(6.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(shape)
            .background(theme?.let { Color(it.background) } ?: MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Text(
            "Aa",
            color = theme?.let { Color(it.foreground) } ?: Color.Transparent,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
        )
        Spacer(Modifier.width(4.dp))
        for (i in 0 until 8) {
            Box(
                Modifier.padding(1.dp).size(7.dp).clip(CircleShape)
                    .background(theme?.let { Color(it.paletteColor(i)) } ?: Color.Transparent),
            )
        }
    }
}

/**
 * Sample terminal content in the theme's colors: shell output, the 16 ANSI
 * colors, Chinese text, emoji, a selection and a block cursor.
 */
@Composable
fun ThemePreview(theme: TerminalTheme, modifier: Modifier = Modifier) {
    val fg = Color(theme.foreground)
    val bg = Color(theme.background)
    fun pal(i: Int) = Color(theme.paletteColor(i))
    fun resolve(c: ThemeColor?, cellFg: Color, cellBg: Color, fallback: Color) = when (c) {
        is ThemeColor.Rgb -> Color(c.argb)
        ThemeColor.CellForeground -> cellFg
        ThemeColor.CellBackground -> cellBg
        null -> fallback
    }
    val selBg = resolve(theme.selectionBackground, fg, bg, fg)
    val selFg = resolve(theme.selectionForeground, fg, bg, bg)
    val cursorBg = resolve(theme.cursorColor, fg, bg, fg)
    val cursorFg = resolve(theme.cursorText, fg, bg, bg)

    val text: AnnotatedString = buildAnnotatedString {
        fun prompt() {
            withStyle(SpanStyle(color = pal(2), fontWeight = FontWeight.Bold)) { append("puppy") }
            append(":")
            withStyle(SpanStyle(color = pal(4), fontWeight = FontWeight.Bold)) { append("~/project") }
            append(" $ ")
        }
        prompt(); append("ls\n")
        withStyle(SpanStyle(color = pal(4), fontWeight = FontWeight.Bold)) { append("src") }
        append("  README.md  ")
        withStyle(SpanStyle(color = pal(2), fontWeight = FontWeight.Bold)) { append("build.sh") }
        append("\n")
        prompt(); append("git status\n")
        append("  ")
        withStyle(SpanStyle(color = pal(1))) { append("modified:   app.kt") }
        append("\n  ")
        withStyle(SpanStyle(color = pal(2))) { append("new file:   theme.conf") }
        append("\n")
        append("中文显示 宽字符  🐶 ✨\n")
        append("Selected: ")
        withStyle(SpanStyle(background = selBg, color = selFg)) { append("highlighted text") }
        append("\n")
        prompt()
        withStyle(SpanStyle(background = cursorBg, color = cursorFg)) { append(" ") }
    }

    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(text, color = fg, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp)
        for (row in 0 until 2) {
            Row {
                for (i in 0 until 8) {
                    Box(Modifier.weight(1f).height(14.dp).background(pal(row * 8 + i)))
                }
            }
        }
        Text(
            theme.name + if (theme.isDark) " · dark" else " · light",
            color = fg.copy(alpha = 0.7f),
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private const val MAX_IMPORT_BYTES = 256 * 1024
private const val MAX_LISTED = 20
