package org.pocketlab.puppy.terminal

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * An immutable copy of one terminal frame, decoded from the native layout
 * in native/src/frame.zig. Safe to hand to the UI thread.
 */
class TerminalFrame(
    val cols: Int,
    val rows: Int,
    /** Base codepoint per cell; 0 for an empty cell. */
    val codepoints: IntArray,
    val foreground: IntArray,
    val background: IntArray,
    val attrs: IntArray,
    /** Grapheme continuation codepoints, keyed by cell index. */
    val graphemes: Map<Int, IntArray>,
    val cursorX: Int,
    val cursorY: Int,
    val cursorVisible: Boolean,
    val cursorBlinking: Boolean,
    val cursorStyle: CursorStyle,
    val defaultBackground: Int,
    val defaultForeground: Int,
    /** Explicit cursor color, or null to use the foreground. */
    val cursorColor: Int?,
    val scrollTotal: Int,
    val scrollOffset: Int,
    val scrollLength: Int,
    val flags: Int,
) {
    enum class CursorStyle { BAR, BLOCK, UNDERLINE, BLOCK_HOLLOW }

    val altScreen get() = flags and FLAG_ALT_SCREEN != 0
    val mouseTracking get() = flags and FLAG_MOUSE_TRACKING != 0
    val bracketedPaste get() = flags and FLAG_BRACKETED_PASTE != 0
    val scrolledBack get() = flags and FLAG_SCROLLED_BACK != 0

    fun index(x: Int, y: Int) = y * cols + x

    fun wide(i: Int) = (attrs[i] ushr ATTR_WIDE_SHIFT) and 3

    /** The text of one cell, including grapheme continuations; "" when empty. */
    fun cellText(i: Int): String {
        val cp = codepoints[i]
        if (cp == 0) return ""
        val extra = graphemes[i]
        if (extra == null) return String(Character.toChars(cp))
        val sb = StringBuilder()
        sb.appendCodePoint(cp)
        for (c in extra) sb.appendCodePoint(c)
        return sb.toString()
    }

    /** Text between two cells in reading order, trailing blanks trimmed per line. */
    fun text(startX: Int, startY: Int, endX: Int, endY: Int): String {
        val sb = StringBuilder()
        for (y in startY..endY) {
            val from = if (y == startY) startX else 0
            val to = if (y == endY) endX else cols - 1
            val line = StringBuilder()
            for (x in from..to) {
                val i = index(x, y)
                if (wide(i) == WIDE_SPACER_TAIL) continue
                val t = cellText(i)
                line.append(t.ifEmpty { " " })
            }
            sb.append(line.trimEnd())
            if (y != endY) sb.append('\n')
        }
        return sb.toString()
    }

    fun lineText(y: Int) = text(0, y, cols - 1, y)

    companion object {
        const val HEADER_WORDS = 16
        const val CELL_WORDS = 4
        const val GRAPHEME_FLAG = 1 shl 31

        const val FLAG_ALT_SCREEN = 1 shl 0
        const val FLAG_MOUSE_TRACKING = 1 shl 1
        const val FLAG_BRACKETED_PASTE = 1 shl 2
        const val FLAG_FOCUS_EVENTS = 1 shl 3
        const val FLAG_CURSOR_KEYS = 1 shl 4
        const val FLAG_RENDER_HOLD = 1 shl 5
        const val FLAG_SCROLLED_BACK = 1 shl 6

        const val ATTR_BOLD = 1 shl 0
        const val ATTR_ITALIC = 1 shl 1
        const val ATTR_FAINT = 1 shl 2
        const val ATTR_STRIKETHROUGH = 1 shl 3
        const val ATTR_OVERLINE = 1 shl 4
        const val ATTR_INVISIBLE = 1 shl 5
        const val ATTR_BLINK = 1 shl 6
        const val ATTR_SELECTED = 1 shl 7
        const val ATTR_UNDERLINE_SHIFT = 8
        const val ATTR_WIDE_SHIFT = 12
        const val ATTR_HYPERLINK = 1 shl 14
        const val ATTR_EXTRA_LEN_SHIFT = 16

        const val WIDE_NARROW = 0
        const val WIDE_WIDE = 1
        const val WIDE_SPACER_TAIL = 2
        const val WIDE_SPACER_HEAD = 3

        /** Decodes a frame written by `frame.write`; [length] is the byte count. */
        fun decode(buffer: ByteBuffer, length: Int): TerminalFrame {
            val b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            b.position(0).limit(length)
            val w = b.asIntBuffer()
            val cols = w.get(1)
            val rows = w.get(2)
            val n = cols * rows
            val cp = IntArray(n)
            val fg = IntArray(n)
            val bg = IntArray(n)
            val at = IntArray(n)
            val raw = IntArray(n * CELL_WORDS)
            w.position(HEADER_WORDS)
            w.get(raw)
            var graphemes: HashMap<Int, IntArray>? = null
            var extraPos = HEADER_WORDS + n * CELL_WORDS
            for (i in 0 until n) {
                val o = i * CELL_WORDS
                var c = raw[o]
                fg[i] = raw[o + 1]
                bg[i] = raw[o + 2]
                at[i] = raw[o + 3]
                if (c and GRAPHEME_FLAG != 0) {
                    c = c and GRAPHEME_FLAG.inv()
                    val len = (at[i] ushr ATTR_EXTRA_LEN_SHIFT) and 0xFF
                    val extra = IntArray(len) { w.get(extraPos + it) }
                    extraPos += len
                    if (graphemes == null) graphemes = HashMap()
                    graphemes[i] = extra
                }
                cp[i] = c
            }
            val cx = w.get(3)
            val cy = w.get(4)
            val cursor = w.get(5)
            val styles = CursorStyle.entries
            val cursorColor = w.get(8)
            return TerminalFrame(
                cols = cols,
                rows = rows,
                codepoints = cp,
                foreground = fg,
                background = bg,
                attrs = at,
                graphemes = graphemes ?: emptyMap(),
                cursorX = cx,
                cursorY = cy,
                cursorVisible = cursor and 1 != 0 && cx in 0 until cols && cy in 0 until rows,
                cursorBlinking = cursor and 2 != 0,
                cursorStyle = styles.getOrElse((cursor ushr 8) and 0xFF) { CursorStyle.BLOCK },
                defaultBackground = w.get(6),
                defaultForeground = w.get(7),
                cursorColor = if (cursorColor == 0) null else cursorColor,
                scrollTotal = w.get(9),
                scrollOffset = w.get(10),
                scrollLength = w.get(11),
                flags = w.get(12),
            )
        }
    }
}
