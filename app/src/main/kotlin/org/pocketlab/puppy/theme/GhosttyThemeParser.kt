package org.pocketlab.puppy.theme

/**
 * Parses the color subset of Ghostty's configuration syntax (design §8.2).
 *
 * Line handling follows Ghostty's `LineIterator` (src/cli/args.zig at the
 * pinned commit): lines are trimmed of spaces, tabs and CR; blank lines and
 * lines starting with `#` are ignored; the key/value split is at the first
 * `=`, both sides trimmed; a value wrapped in double quotes loses the quotes.
 * Later assignments win, and an empty value resets a field to its default.
 *
 * Only appearance fields are applied. Everything else is kept verbatim in
 * [Result.unsupported]; nothing is ever executed or loaded from elsewhere.
 */
class GhosttyThemeParser(private val x11: X11Colors = X11Colors.EMPTY) {

    data class Entry(val line: Int, val key: String, val value: String)
    data class Problem(val line: Int, val message: String)

    class Result(
        val background: Int?,
        val foreground: Int?,
        val cursorColor: ThemeColor?,
        val cursorText: ThemeColor?,
        val selectionBackground: ThemeColor?,
        val selectionForeground: ThemeColor?,
        /** Palette overrides set by the file, by index. */
        val palette: Map<Int, Int>,
        /** Keys this app does not apply, in file order. */
        val unsupported: List<Entry>,
        /** Lines whose value could not be parsed; the field keeps its previous value. */
        val errors: List<Problem>,
    ) {
        val ok get() = errors.isEmpty()

        fun toTheme(id: String, name: String, source: ThemeSource): TerminalTheme {
            val pal = TerminalTheme.DEFAULT_PALETTE.copyOf()
            for ((i, c) in palette) pal[i] = c
            return TerminalTheme(
                id = id,
                name = name,
                background = background ?: TerminalTheme.DEFAULT_BACKGROUND,
                foreground = foreground ?: TerminalTheme.DEFAULT_FOREGROUND,
                cursorColor = cursorColor,
                cursorText = cursorText,
                selectionBackground = selectionBackground,
                selectionForeground = selectionForeground,
                palette = pal,
                source = source,
            )
        }
    }

    fun parse(text: String): Result {
        var background: Int? = null
        var foreground: Int? = null
        var cursorColor: ThemeColor? = null
        var cursorText: ThemeColor? = null
        var selectionBackground: ThemeColor? = null
        var selectionForeground: ThemeColor? = null
        val palette = LinkedHashMap<Int, Int>()
        val unsupported = ArrayList<Entry>()
        val errors = ArrayList<Problem>()

        text.split('\n').forEachIndexed { index, raw ->
            val lineNo = index + 1
            val line = raw.trim(' ', '\t', '\r')
            if (line.isEmpty() || line[0] == '#') return@forEachIndexed
            val eq = line.indexOf('=')
            val key = (if (eq < 0) line else line.substring(0, eq)).trim(' ', '\t')
            var value = if (eq < 0) "" else line.substring(eq + 1).trim(' ', '\t')
            if (value.length >= 2 && value.first() == '"' && value.last() == '"') {
                value = value.substring(1, value.length - 1)
            }

            fun color(): Int? = ColorParser.parse(value, x11).also {
                if (it == null) errors += Problem(lineNo, "invalid color for $key: \"$value\"")
            }

            fun terminalColor(): ThemeColor? = when (value.trim(' ', '\t')) {
                "cell-foreground" -> ThemeColor.CellForeground
                "cell-background" -> ThemeColor.CellBackground
                else -> color()?.let { ThemeColor.Rgb(it) }
            }

            when (key) {
                "background" -> if (value.isEmpty()) background = null else color()?.let { background = it }
                "foreground" -> if (value.isEmpty()) foreground = null else color()?.let { foreground = it }
                "cursor-color" -> if (value.isEmpty()) cursorColor = null else terminalColor()?.let { cursorColor = it }
                "cursor-text" -> if (value.isEmpty()) cursorText = null else terminalColor()?.let { cursorText = it }
                "selection-background" ->
                    if (value.isEmpty()) selectionBackground = null else terminalColor()?.let { selectionBackground = it }
                "selection-foreground" ->
                    if (value.isEmpty()) selectionForeground = null else terminalColor()?.let { selectionForeground = it }
                "palette" -> {
                    if (value.isEmpty()) {
                        palette.clear()
                        return@forEachIndexed
                    }
                    val sep = value.indexOf('=')
                    val idx = if (sep < 0) null else parseIndex(value.substring(0, sep).trim(' ', '\t'))
                    val c = if (sep < 0) null else ColorParser.parse(value.substring(sep + 1), x11)
                    if (idx == null || c == null) {
                        errors += Problem(lineNo, "invalid palette entry: \"$value\"")
                    } else {
                        palette[idx] = c
                    }
                }
                "" -> errors += Problem(lineNo, "missing key")
                else -> unsupported += Entry(lineNo, key, value)
            }
        }
        return Result(
            background, foreground, cursorColor, cursorText, selectionBackground, selectionForeground,
            palette, unsupported, errors,
        )
    }

    /** Zig `parseInt(u8, s, 0)`: decimal or 0x/0o/0b prefixed, 0..255. */
    private fun parseIndex(s: String): Int? {
        if (s.isEmpty()) return null
        val (digits, radix) = when {
            s.startsWith("0x") || s.startsWith("0X") -> s.substring(2) to 16
            s.startsWith("0o") || s.startsWith("0O") -> s.substring(2) to 8
            s.startsWith("0b") || s.startsWith("0B") -> s.substring(2) to 2
            else -> s to 10
        }
        if (digits.isEmpty() || digits.contains('_')) return null
        val v = digits.toIntOrNull(radix) ?: return null
        return v.takeIf { it in 0..255 }
    }

    companion object {
        /** Keys this parser applies. */
        val SUPPORTED_KEYS = setOf(
            "background", "foreground", "palette", "cursor-color", "cursor-text",
            "selection-background", "selection-foreground",
        )
    }
}
