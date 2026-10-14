package org.pocketlab.puppy.terminal

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalFrameTest {
    /** Builds a frame in the native layout (native/src/frame.zig). */
    private fun frame(cols: Int, rows: Int, cells: Map<Int, Pair<Int, Int>> = emptyMap(), extra: Map<Int, IntArray> = emptyMap(), flags: Int = 0): TerminalFrame {
        val words = TerminalFrame.HEADER_WORDS + cols * rows * TerminalFrame.CELL_WORDS + extra.values.sumOf { it.size }
        val b = ByteBuffer.allocate(words * 4).order(ByteOrder.LITTLE_ENDIAN)
        val w = b.asIntBuffer()
        w.put(0, 1); w.put(1, cols); w.put(2, rows)
        w.put(3, 1); w.put(4, 0); w.put(5, 1 or (1 shl 8))
        w.put(6, 0xFF000000.toInt()); w.put(7, 0xFFFFFFFF.toInt())
        w.put(12, flags)
        var extraPos = TerminalFrame.HEADER_WORDS + cols * rows * TerminalFrame.CELL_WORDS
        for (i in 0 until cols * rows) {
            val o = TerminalFrame.HEADER_WORDS + i * TerminalFrame.CELL_WORDS
            val (cp, attrs) = cells[i] ?: (0 to 0)
            var a = attrs
            var c = cp
            extra[i]?.let { e ->
                c = c or TerminalFrame.GRAPHEME_FLAG
                a = a or (e.size shl TerminalFrame.ATTR_EXTRA_LEN_SHIFT)
                e.forEach { w.put(extraPos++, it) }
            }
            w.put(o, c); w.put(o + 1, 0xFFFFFFFF.toInt()); w.put(o + 2, 0xFF000000.toInt()); w.put(o + 3, a)
        }
        return TerminalFrame.decode(b, words * 4)
    }

    @Test fun decodesHeaderAndCells() {
        val f = frame(4, 2, mapOf(0 to ('h'.code to 0), 1 to ('i'.code to TerminalFrame.ATTR_BOLD)))
        assertEquals(4, f.cols)
        assertEquals(2, f.rows)
        assertEquals("hi", f.lineText(0))
        assertEquals("", f.lineText(1))
        assertTrue(f.cursorVisible)
        assertEquals(TerminalFrame.CursorStyle.BLOCK, f.cursorStyle)
        assertEquals(TerminalFrame.ATTR_BOLD, f.attrs[1])
        assertNull(f.cursorColor)
    }

    @Test fun decodesGraphemes() {
        // 👍🏽 = U+1F44D U+1F3FD
        val f = frame(3, 1, mapOf(0 to (0x1F44D to (TerminalFrame.WIDE_WIDE shl TerminalFrame.ATTR_WIDE_SHIFT)),
            1 to (0 to (TerminalFrame.WIDE_SPACER_TAIL shl TerminalFrame.ATTR_WIDE_SHIFT)), 2 to ('x'.code to 0)),
            extra = mapOf(0 to intArrayOf(0x1F3FD)))
        assertEquals("👍🏽", f.cellText(0))
        assertEquals("👍🏽x", f.lineText(0))
    }

    @Test fun flags() {
        val f = frame(1, 1, flags = TerminalFrame.FLAG_MOUSE_TRACKING or TerminalFrame.FLAG_ALT_SCREEN)
        assertTrue(f.mouseTracking)
        assertTrue(f.altScreen)
        assertFalse(f.bracketedPaste)
    }

    @Test fun selectionTextAcrossLines() {
        val cells = HashMap<Int, Pair<Int, Int>>()
        "ab cd".forEachIndexed { i, c -> cells[i] = c.code to 0 }
        "ef".forEachIndexed { i, c -> cells[5 + i] = c.code to 0 }
        val f = frame(5, 2, cells)
        assertEquals("cd\nef", Selection(3, 0, 1, 1).text(f))
        // Reversed selections normalize.
        assertEquals("cd\nef", Selection(1, 1, 3, 0).text(f))
        assertEquals(Selection(3, 0, 4, 0), Selection.word(f, 4, 0))
        assertTrue(Selection(3, 0, 1, 1).contains(0, 1))
        assertFalse(Selection(3, 0, 1, 1).contains(2, 0))
    }
}
