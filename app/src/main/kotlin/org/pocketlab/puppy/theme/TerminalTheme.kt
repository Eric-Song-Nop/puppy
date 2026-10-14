package org.pocketlab.puppy.theme

/** A color that may refer to the colors of the cell it is drawn over. */
sealed interface ThemeColor {
    data class Rgb(val argb: Int) : ThemeColor
    /** Ghostty `cell-foreground`. */
    data object CellForeground : ThemeColor
    /** Ghostty `cell-background`. */
    data object CellBackground : ThemeColor

    /** The fixed color, or null for cell references. */
    val fixed: Int? get() = (this as? Rgb)?.argb
}

/** Where a theme came from (design §11 `ThemeRecord`). */
data class ThemeSource(
    val kind: Kind,
    /** Upstream snapshot or "user". */
    val revision: String,
    /** SHA-256 of the theme file. */
    val sha256: String,
) {
    enum class Kind { BUILT_IN, USER }
}

/**
 * Terminal colors from a Ghostty theme. Palette entries a theme does not set
 * keep Ghostty's defaults, so the result matches what Ghostty would show.
 */
class TerminalTheme(
    val id: String,
    val name: String,
    val background: Int,
    val foreground: Int,
    val cursorColor: ThemeColor?,
    val cursorText: ThemeColor?,
    val selectionBackground: ThemeColor?,
    val selectionForeground: ThemeColor?,
    palette: IntArray,
    val source: ThemeSource,
) {
    init {
        require(palette.size == 256) { "palette must have 256 entries" }
    }

    private val paletteCopy = palette.copyOf()

    /** 256 ARGB colors; a fresh copy each call. */
    val palette: IntArray get() = paletteCopy.copyOf()

    fun paletteColor(index: Int): Int = paletteCopy[index]

    val isDark: Boolean get() = Contrast.luminance(background) < 0.5

    val ui: UiColors by lazy { UiColors.derive(this) }

    override fun equals(other: Any?) = other is TerminalTheme && other.id == id && other.source == source
    override fun hashCode() = id.hashCode() * 31 + source.hashCode()
    override fun toString() = "TerminalTheme($id)"

    companion object {
        /** Ghostty's default background/foreground when a theme sets none. */
        const val DEFAULT_BACKGROUND = 0xFF282C34.toInt()
        const val DEFAULT_FOREGROUND = 0xFFFFFFFF.toInt()

        /** Ghostty's built-in default palette (src/terminal/color.zig `default`). */
        val DEFAULT_PALETTE: IntArray = IntArray(256).also { p ->
            val named = intArrayOf(
                0x1D1F21, 0xCC6666, 0xB5BD68, 0xF0C674, 0x81A2BE, 0xB294BB, 0x8ABEB7, 0xC5C8C6,
                0x666666, 0xD54E53, 0xB9CA4A, 0xE7C547, 0x7AA6DA, 0xC397D8, 0x70C0B1, 0xEAEAEA,
            )
            for (i in 0 until 16) p[i] = 0xFF000000.toInt() or named[i]
            var i = 16
            for (r in 0 until 6) for (g in 0 until 6) for (b in 0 until 6) {
                fun c(v: Int) = if (v == 0) 0 else v * 40 + 55
                p[i++] = (0xFF shl 24) or (c(r) shl 16) or (c(g) shl 8) or c(b)
            }
            while (i < 256) {
                val v = (i - 232) * 10 + 8
                p[i++] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        }
    }
}
