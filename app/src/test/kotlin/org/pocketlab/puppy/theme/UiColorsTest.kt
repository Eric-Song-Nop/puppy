package org.pocketlab.puppy.theme

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiColorsTest {
    private val source = ThemeSource(ThemeSource.Kind.USER, "user", "")

    private fun theme(bg: Int, fg: Int, palette: IntArray = TerminalTheme.DEFAULT_PALETTE) =
        TerminalTheme("t", "t", bg, fg, null, null, null, null, palette, source)

    @Test fun contrastRatioKnownValues() {
        assertEquals(21.0, Contrast.ratio(Contrast.WHITE, Contrast.BLACK), 0.01)
        assertEquals(1.0, Contrast.ratio(0xFF777777.toInt(), 0xFF777777.toInt()), 0.001)
    }

    @Test fun ensureRaisesLowContrast() {
        val bg = 0xFF808080.toInt()
        val c = Contrast.ensure(0xFF888888.toInt(), bg, 4.5)
        assertTrue(Contrast.ratio(c, bg) >= 4.5)
    }

    @Test fun lowContrastThemeStillGivesReadableChrome() {
        // Foreground almost equal to background.
        val ui = theme(0xFF202020.toInt(), 0xFF262626.toInt()).ui
        assertTrue(Contrast.ratio(ui.onSurface, ui.surface) >= UiColors.TEXT_MIN)
        assertTrue(Contrast.ratio(ui.onBackground, ui.background) >= UiColors.TEXT_MIN)
        assertTrue(Contrast.ratio(ui.onAccent, ui.accent) >= UiColors.TEXT_MIN)
    }

    @Test fun darkAndLight() {
        assertTrue(theme(0xFF101010.toInt(), Contrast.WHITE).isDark)
        assertFalse(theme(0xFFF5F5F5.toInt(), Contrast.BLACK).isDark)
    }

    /** Every bundled theme yields chrome colors that meet the contrast targets. */
    @Test fun allBundledThemesMeetContrast() {
        val dir = File("src/main/assets/themes/ghostty")
        val parser = GhosttyThemeParser()
        val failures = ArrayList<String>()
        for (f in dir.listFiles()!!.filter { it.isFile }) {
            val ui = parser.parse(f.readText()).toTheme(f.name, f.name, source).ui
            if (Contrast.ratio(ui.onSurface, ui.surface) < UiColors.TEXT_MIN) failures += "${f.name}: onSurface"
            if (Contrast.ratio(ui.onSurfaceMuted, ui.surface) < UiColors.MUTED_MIN) failures += "${f.name}: muted"
            if (Contrast.ratio(ui.onBackground, ui.background) < UiColors.TEXT_MIN) failures += "${f.name}: onBackground"
            if (Contrast.ratio(ui.accent, ui.surface) < UiColors.ACCENT_MIN) failures += "${f.name}: accent"
            if (Contrast.ratio(ui.onAccent, ui.accent) < UiColors.TEXT_MIN) failures += "${f.name}: onAccent"
            if (Contrast.ratio(ui.error, ui.surface) < UiColors.TEXT_MIN) failures += "${f.name}: error"
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
