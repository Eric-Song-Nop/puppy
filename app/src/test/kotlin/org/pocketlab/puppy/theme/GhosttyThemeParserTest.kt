package org.pocketlab.puppy.theme

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GhosttyThemeParserTest {
    private val parser = GhosttyThemeParser()

    private fun rgb(v: Int) = 0xFF000000.toInt() or v

    @Test fun basicFieldsAndPalette() {
        val r = parser.parse(
            """
            # comment
            background = #282a36
            foreground=#f8f8f2
            palette = 1=#ff5555
            palette = 255 = #010203
            cursor-color = #aabbcc
            """.trimIndent(),
        )
        assertTrue(r.errors.toString(), r.ok)
        assertEquals(rgb(0x282a36), r.background)
        assertEquals(rgb(0xf8f8f2), r.foreground)
        assertEquals(rgb(0xff5555), r.palette[1])
        assertEquals(rgb(0x010203), r.palette[255])
        assertEquals(ThemeColor.Rgb(rgb(0xaabbcc)), r.cursorColor)
    }

    @Test fun whitespaceCrlfAndIndentedComments() {
        val r = parser.parse("\t background\t=\t#000000 \r\n   # indented comment\r\n\r\nforeground = ffffff\r\n")
        assertTrue(r.ok)
        assertEquals(rgb(0), r.background)
        assertEquals(rgb(0xffffff), r.foreground)
    }

    @Test fun quotedValuesLoseQuotes() {
        val r = parser.parse("background = \"#102030\"\nselection-background = \"cell-foreground\"")
        assertTrue(r.ok)
        assertEquals(rgb(0x102030), r.background)
        assertEquals(ThemeColor.CellForeground, r.selectionBackground)
    }

    @Test fun hashInsideValueIsNotAComment() {
        val r = parser.parse("foreground = #123456")
        assertEquals(rgb(0x123456), r.foreground)
    }

    @Test fun laterAssignmentsWinAndEmptyResets() {
        val r = parser.parse("background = #111111\nbackground = #222222\nforeground = #333333\nforeground =")
        assertEquals(rgb(0x222222), r.background)
        assertNull(r.foreground)
    }

    @Test fun colorForms() {
        assertEquals(rgb(0xffffff), ColorParser.parse("#fff"))
        assertEquals(rgb(0x112233), ColorParser.parse("112233"))
        assertEquals(rgb(0xaabbcc), ColorParser.parse("abc"))
        assertEquals(rgb(0xff0080), ColorParser.parse("rgb:ff/00/80"))
        assertEquals(rgb(0xff0000), ColorParser.parse("rgb:ffff/0/0"))
        assertEquals(rgb(0xff0000), ColorParser.parse("rgbi:1/0/0"))
        assertEquals(rgb(0x123456), ColorParser.parse("#123456"))
        assertNull(ColorParser.parse("#12345"))
        assertNull(ColorParser.parse("notacolor"))
        assertNull(ColorParser.parse(""))
    }

    @Test fun x11Names() {
        val x11 = X11Colors.parse("255 250 250\t\tsnow\n  0   0 128\t\tnavy blue\n".reader())
        assertEquals(rgb(0xfffafa), ColorParser.parse("Snow", x11))
        assertEquals(rgb(0x000080), ColorParser.parse("navy blue", x11))
        val r = GhosttyThemeParser(x11).parse("background = snow")
        assertEquals(rgb(0xfffafa), r.background)
    }

    @Test fun cellReferencesOnlyWhereAllowed() {
        val r = parser.parse("cursor-text = cell-background\nbackground = cell-background")
        assertEquals(ThemeColor.CellBackground, r.cursorText)
        assertEquals(1, r.errors.size)
        assertEquals(2, r.errors[0].line)
        assertNull(r.background)
    }

    @Test fun paletteIndexForms() {
        val r = parser.parse("palette = 0x10=#010101\npalette = 0b11=#020202\npalette = 256=#030303\npalette = x\npalette = 3")
        assertEquals(rgb(0x010101), r.palette[16])
        assertEquals(rgb(0x020202), r.palette[3])
        assertEquals(3, r.errors.size)
    }

    @Test fun unsupportedKeysAreKeptNotApplied() {
        val r = parser.parse("font-family = Iosevka\ncommand = rm -rf ~\nbackground = #000000\nwindow-decoration")
        assertTrue(r.ok)
        assertEquals(listOf("font-family", "command", "window-decoration"), r.unsupported.map { it.key })
        assertEquals("rm -rf ~", r.unsupported[1].value)
    }

    @Test fun unsetPaletteKeepsGhosttyDefaults() {
        val theme = parser.parse("palette = 1=#ff0000").toTheme("t", "t", ThemeSource(ThemeSource.Kind.USER, "user", ""))
        assertEquals(rgb(0xff0000), theme.paletteColor(1))
        assertEquals(rgb(0x1d1f21), theme.paletteColor(0))
        assertEquals(rgb(0x000000), theme.paletteColor(16))
        assertEquals(rgb(0xffffff), theme.paletteColor(231))
        assertEquals(rgb(0x080808), theme.paletteColor(232))
        assertEquals(rgb(0xeeeeee), theme.paletteColor(255))
        assertEquals(TerminalTheme.DEFAULT_BACKGROUND, theme.background)
    }

    /**
     * Gate G4: every theme in the bundled snapshot parses without errors and
     * uses only keys this app applies, and the index matches the files.
     */
    @Test fun fullSnapshotScan() {
        val dir = File("src/main/assets/themes")
        val x11 = File(dir, "x11-rgb.txt").reader().use { X11Colors.parse(it) }
        assertTrue("x11 table loaded", x11.size > 500)
        val themeParser = GhosttyThemeParser(x11)
        val files = File(dir, "ghostty").listFiles()!!.filter { it.isFile }.sortedBy { it.name }
        assertTrue("snapshot present", files.size > 100)

        val failures = ArrayList<String>()
        val unknownKeys = sortedSetOf<String>()
        for (f in files) {
            val r = themeParser.parse(f.readText())
            r.errors.forEach { failures += "${f.name}:${it.line}: ${it.message}" }
            r.unsupported.forEach { unknownKeys += it.key }
            if (r.background == null || r.foreground == null) failures += "${f.name}: missing background/foreground"
        }
        assertTrue("parse failures:\n" + failures.joinToString("\n"), failures.isEmpty())
        assertTrue("keys not applied by the app: $unknownKeys", unknownKeys.isEmpty())

        val index = File(dir, "index.json").readText()
        val indexed = Regex("\"name\": \"((?:[^\"\\\\]|\\\\.)*)\"").findAll(index).map { it.groupValues[1] }.toList()
        assertEquals("index lists every file", files.size, indexed.size)
        assertTrue("default theme bundled", files.any { "ghostty:" + it.name == ThemeRepository.DEFAULT_THEME_ID })
    }
}
