package org.pocketlab.puppy.theme

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** WCAG 2 relative luminance and contrast. Colors are 0xAARRGGBB, alpha ignored. */
object Contrast {
    fun luminance(argb: Int): Double {
        fun ch(v: Int): Double {
            val c = v / 255.0
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * ch((argb shr 16) and 0xFF) + 0.7152 * ch((argb shr 8) and 0xFF) + 0.0722 * ch(argb and 0xFF)
    }

    fun ratio(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** Linear blend from [a] (t=0) to [b] (t=1), opaque result. */
    fun mix(a: Int, b: Int, t: Double): Int {
        fun ch(shift: Int): Int {
            val x = (a shr shift) and 0xFF
            val y = (b shr shift) and 0xFF
            return (x + (y - x) * t).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /**
     * Returns [color] if it reaches [minRatio] against [background]; otherwise
     * moves it toward black or white (whichever contrasts more) until it does.
     */
    fun ensure(color: Int, background: Int, minRatio: Double): Int {
        if (ratio(color, background) >= minRatio) return color
        val target = if (ratio(WHITE, background) >= ratio(BLACK, background)) WHITE else BLACK
        var lo = 0.0
        var hi = 1.0
        repeat(16) {
            val mid = (lo + hi) / 2
            if (ratio(mix(color, target, mid), background) >= minRatio) hi = mid else lo = mid
        }
        return mix(color, target, hi)
    }

    const val WHITE = 0xFFFFFFFF.toInt()
    const val BLACK = 0xFF000000.toInt()
}

/**
 * App chrome colors derived from a terminal theme (design §8.2): the top bar,
 * sidebar and dialogs blend with the terminal while text and accents keep at
 * least WCAG AA contrast. The terminal's own colors are never altered.
 */
data class UiColors(
    val isDark: Boolean,
    /** Behind the terminal and app bar. */
    val background: Int,
    /** Sidebar, sheets and dialogs. */
    val surface: Int,
    /** Selected or hovered rows. */
    val surfaceVariant: Int,
    val onBackground: Int,
    val onSurface: Int,
    /** Secondary text on [surface]. */
    val onSurfaceMuted: Int,
    val accent: Int,
    val onAccent: Int,
    val divider: Int,
    val error: Int,
) {
    companion object {
        const val TEXT_MIN = 4.5
        const val MUTED_MIN = 4.5
        const val ACCENT_MIN = 3.0

        fun derive(theme: TerminalTheme): UiColors {
            val bg = theme.background
            val dark = Contrast.luminance(bg) < 0.5
            val lift = if (dark) Contrast.WHITE else Contrast.BLACK
            val surface = Contrast.mix(bg, lift, 0.05)
            val variant = Contrast.mix(bg, lift, 0.12)
            val onBg = Contrast.ensure(theme.foreground, bg, TEXT_MIN)
            val onSurface = Contrast.ensure(theme.foreground, surface, TEXT_MIN)
            val muted = Contrast.ensure(Contrast.mix(theme.foreground, surface, 0.35), surface, MUTED_MIN)
            // Prefer the theme's own blue, then cyan/magenta, as the accent.
            val candidates = intArrayOf(
                theme.paletteColor(12), theme.paletteColor(4), theme.paletteColor(14),
                theme.paletteColor(6), theme.paletteColor(13), theme.paletteColor(5),
            )
            val accentBase = candidates.maxByOrNull { Contrast.ratio(it, surface) } ?: theme.foreground
            val accent = Contrast.ensure(accentBase, surface, ACCENT_MIN)
            val onAccent = if (Contrast.ratio(Contrast.WHITE, accent) >= Contrast.ratio(Contrast.BLACK, accent)) {
                Contrast.WHITE
            } else {
                Contrast.BLACK
            }
            val divider = Contrast.mix(bg, theme.foreground, 0.16)
            val error = Contrast.ensure(theme.paletteColor(9), surface, TEXT_MIN)
            return UiColors(dark, bg, surface, variant, onBg, onSurface, muted, accent, onAccent, divider, error)
        }
    }
}
