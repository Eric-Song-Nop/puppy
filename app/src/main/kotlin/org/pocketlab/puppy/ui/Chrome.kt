package org.pocketlab.puppy.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import org.pocketlab.puppy.terminal.TerminalColors

/** Colors for the app bar, sidebar and panels around the terminal. */
@Immutable
data class Chrome(
    val terminalBackground: Color,
    val bar: Color,
    val sidebar: Color,
    val onBar: Color,
    val muted: Color,
    val divider: Color,
    val selected: Color,
    val accent: Color,
    val danger: Color,
    val isDark: Boolean,
) {
    companion object {
        /** Derives chrome from the terminal theme so the workspace reads as one surface. */
        fun from(colors: TerminalColors): Chrome {
            val bg = Color(colors.background)
            val fg = Color(colors.foreground)
            val dark = bg.luminance() < 0.4f
            val lift = if (dark) Color.White else Color.Black
            val accentArgb = colors.palette?.getOrNull(if (dark) 12 else 4) ?: if (dark) 0xFF8BE9FD.toInt() else 0xFF2A6FDB.toInt()
            val accent = ensureContrast(Color(accentArgb), bg)
            val onBar = ensureContrast(fg, bg)
            return Chrome(
                terminalBackground = bg,
                bar = lift.copy(alpha = 0.04f).compositeOver(bg),
                sidebar = lift.copy(alpha = 0.06f).compositeOver(bg),
                onBar = onBar,
                muted = onBar.copy(alpha = 0.66f),
                divider = onBar.copy(alpha = 0.12f),
                selected = accent.copy(alpha = 0.18f),
                accent = accent,
                danger = if (dark) Color(0xFFFF6E6E) else Color(0xFFC62828),
                isDark = dark,
            )
        }

        fun contrast(a: Color, b: Color): Float {
            val la = a.luminance() + 0.05f
            val lb = b.luminance() + 0.05f
            return if (la > lb) la / lb else lb / la
        }

        /** Returns [fg], or black/white if [fg] would be unreadable on [bg]. */
        fun ensureContrast(fg: Color, bg: Color, min: Float = 4.5f): Color {
            if (contrast(fg, bg) >= min) return fg
            return if (bg.luminance() < 0.5f) Color.White else Color.Black
        }
    }
}

val LocalChrome = staticCompositionLocalOf { Chrome.from(TerminalColors.DEFAULT) }

private fun schemeFor(chrome: Chrome): ColorScheme {
    val base = if (chrome.isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = chrome.accent,
        onPrimary = chrome.terminalBackground,
        background = chrome.terminalBackground,
        onBackground = chrome.onBar,
        surface = chrome.bar,
        onSurface = chrome.onBar,
        surfaceVariant = chrome.sidebar,
        onSurfaceVariant = chrome.muted,
        surfaceContainer = chrome.sidebar,
        surfaceContainerHigh = chrome.sidebar,
        surfaceContainerHighest = chrome.sidebar,
        surfaceContainerLow = chrome.bar,
        outline = chrome.divider,
        outlineVariant = chrome.divider,
        error = chrome.danger,
    )
}

@Composable
fun PuppyTheme(chrome: Chrome, content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(LocalChrome provides chrome) {
        MaterialTheme(colorScheme = schemeFor(chrome), typography = Typography(), content = content)
    }
}

val MonoStyle = TextStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 13.sp)
