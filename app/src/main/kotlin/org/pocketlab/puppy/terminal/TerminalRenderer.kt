package org.pocketlab.puppy.terminal

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import kotlin.math.ceil
import kotlin.math.max
import org.pocketlab.puppy.terminal.TerminalFrame.Companion.ATTR_BOLD
import org.pocketlab.puppy.terminal.TerminalFrame.Companion.ATTR_FAINT
import org.pocketlab.puppy.terminal.TerminalFrame.Companion.ATTR_INVISIBLE
import org.pocketlab.puppy.terminal.TerminalFrame.Companion.ATTR_ITALIC
import org.pocketlab.puppy.terminal.TerminalFrame.Companion.ATTR_OVERLINE
import org.pocketlab.puppy.terminal.TerminalFrame.Companion.ATTR_SELECTED
import org.pocketlab.puppy.terminal.TerminalFrame.Companion.ATTR_STRIKETHROUGH
import org.pocketlab.puppy.terminal.TerminalFrame.Companion.ATTR_UNDERLINE_SHIFT
import org.pocketlab.puppy.terminal.TerminalFrame.Companion.WIDE_SPACER_HEAD
import org.pocketlab.puppy.terminal.TerminalFrame.Companion.WIDE_SPACER_TAIL
import org.pocketlab.puppy.terminal.TerminalFrame.Companion.WIDE_WIDE

/**
 * Draws a [TerminalFrame] with Android's Canvas text stack (design §7.1).
 *
 * The VT core decides cell widths; the renderer only positions glyphs on
 * that grid. Printable ASCII is drawn in runs (a monospace font's advance
 * equals the cell width); everything else is drawn per cell and squeezed
 * horizontally if a fallback glyph is wider than its cells.
 */
class TerminalRenderer(
    typeface: Typeface,
    textSizePx: Float,
    private val density: Float,
    /** Nerd Font icons (Powerline, Font Awesome, Material Design...), or null. */
    private val symbols: Typeface? = null,
) {
    private val regular = Typeface.create(typeface, Typeface.NORMAL)
    private val bold = Typeface.create(typeface, Typeface.BOLD)

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        this.typeface = regular
        textSize = textSizePx
    }
    private val fillPaint = Paint()
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dotted = DashPathEffect(floatArrayOf(density, density * 2), 0f)
    private val dashed = DashPathEffect(floatArrayOf(density * 4, density * 3), 0f)
    private val wave = Path()
    private val runChars = CharArray(1024)
    private val glyphWidths = HashMap<Int, Float>()

    val cellWidth: Float
    val cellHeight: Float
    private val baseline: Float

    init {
        cellWidth = textPaint.measureText("M")
        val fm = textPaint.fontMetrics
        val lineHeight = ceil(fm.descent - fm.ascent)
        cellHeight = ceil(lineHeight * LINE_SPACING)
        baseline = (cellHeight - lineHeight) / 2f - fm.ascent
    }

    /** The user's text selection in viewport cells, if any. */
    var selection: Selection? = null

    /** Selection fill, or null to invert the cell colors. */
    var selectionBackground: Int? = null
    var selectionForeground: Int? = null

    fun draw(canvas: Canvas, frame: TerminalFrame, focused: Boolean, cursorOn: Boolean, preedit: String?) {
        canvas.drawColor(frame.defaultBackground)
        for (y in 0 until frame.rows) drawBackgrounds(canvas, frame, y)
        for (y in 0 until frame.rows) drawText(canvas, frame, y)
        if (frame.cursorVisible && !frame.scrolledBack && preedit.isNullOrEmpty()) {
            drawCursor(canvas, frame, focused, cursorOn)
        }
        if (!preedit.isNullOrEmpty()) drawPreedit(canvas, frame, preedit)
        if (frame.scrolledBack) drawScrollbar(canvas, frame)
    }

    private fun cellColors(frame: TerminalFrame, i: Int): Long {
        var fg = frame.foreground[i]
        var bg = frame.background[i]
        val sel = selection
        if (frame.attrs[i] and ATTR_SELECTED != 0 || (sel != null && sel.contains(i % frame.cols, i / frame.cols))) {
            val sb = selectionBackground
            if (sb != null) {
                bg = sb
                fg = selectionForeground ?: fg
            } else {
                val t = fg; fg = bg; bg = t
            }
        }
        return (fg.toLong() shl 32) or (bg.toLong() and 0xFFFFFFFFL)
    }

    private fun drawBackgrounds(canvas: Canvas, frame: TerminalFrame, y: Int) {
        val top = y * cellHeight
        var x = 0
        while (x < frame.cols) {
            val bg = cellColors(frame, frame.index(x, y)).toInt()
            var end = x + 1
            while (end < frame.cols && cellColors(frame, frame.index(end, y)).toInt() == bg) end++
            if (bg != frame.defaultBackground) {
                fillPaint.color = bg
                canvas.drawRect(x * cellWidth, top, end * cellWidth, top + cellHeight, fillPaint)
            }
            x = end
        }
    }

    private fun styleKey(frame: TerminalFrame, i: Int): Long {
        val fg = (cellColors(frame, i) ushr 32).toInt()
        val a = frame.attrs[i] and (ATTR_BOLD or ATTR_ITALIC or ATTR_FAINT or ATTR_INVISIBLE)
        return (fg.toLong() shl 32) or a.toLong()
    }

    private fun isRunnable(frame: TerminalFrame, i: Int): Boolean {
        val cp = frame.codepoints[i]
        return cp in 0x21..0x7E && frame.wide(i) == 0 && !frame.graphemes.containsKey(i)
    }

    private fun applyStyle(fg: Int, attrs: Int, codepoint: Int = 0) {
        textPaint.color = if (attrs and ATTR_FAINT != 0) (fg and 0x00FFFFFF) or (0x99 shl 24) else fg
        textPaint.typeface = when {
            symbols != null && isNerdSymbol(codepoint) -> symbols
            attrs and ATTR_BOLD != 0 -> bold
            else -> regular
        }
        textPaint.textSkewX = if (attrs and ATTR_ITALIC != 0) -0.2f else 0f
        textPaint.textScaleX = 1f
    }

    private fun drawText(canvas: Canvas, frame: TerminalFrame, y: Int) {
        val top = y * cellHeight
        val base = top + baseline
        var x = 0
        while (x < frame.cols) {
            val i = frame.index(x, y)
            val attrs = frame.attrs[i]
            val fg = (cellColors(frame, i) ushr 32).toInt()
            drawDecorations(canvas, frame, i, x, top, fg)
            val w = frame.wide(i)
            if (frame.codepoints[i] == 0 || attrs and ATTR_INVISIBLE != 0 || w == WIDE_SPACER_TAIL || w == WIDE_SPACER_HEAD) {
                x++
                continue
            }
            if (isRunnable(frame, i)) {
                val key = styleKey(frame, i)
                var n = 0
                var end = x
                while (end < frame.cols && n < runChars.size) {
                    val j = frame.index(end, y)
                    if (!isRunnable(frame, j) || styleKey(frame, j) != key) break
                    if (end != x) drawDecorations(canvas, frame, j, end, top, fg)
                    runChars[n++] = frame.codepoints[j].toChar()
                    end++
                }
                applyStyle(fg, attrs)
                canvas.drawText(runChars, 0, n, x * cellWidth, base, textPaint)
                x = end
                continue
            }
            // Single cell: wide characters, graphemes, symbols, fallback fonts.
            val text = frame.cellText(i)
            val cp = frame.codepoints[i]
            applyStyle(fg, attrs, cp)
            val target = cellWidth * (if (w == WIDE_WIDE) 2 else 1)
            val measured = glyphWidths.getOrPut(cp xor (text.length shl 24)) { textPaint.measureText(text) }
            if (measured > target * 1.05f) textPaint.textScaleX = target / measured
            // Center wide glyphs and icons that are narrower than their cells.
            val dx = if (measured < target && (w == WIDE_WIDE || isNerdSymbol(cp))) (target - measured) / 2 else 0f
            canvas.drawText(text, x * cellWidth + dx, base, textPaint)
            x += if (w == WIDE_WIDE) 2 else 1
        }
    }

    private fun drawDecorations(canvas: Canvas, frame: TerminalFrame, i: Int, x: Int, top: Float, fg: Int) {
        val attrs = frame.attrs[i]
        val underline = (attrs ushr ATTR_UNDERLINE_SHIFT) and 7
        if (underline == 0 && attrs and (ATTR_STRIKETHROUGH or ATTR_OVERLINE) == 0) return
        val left = x * cellWidth
        val right = left + cellWidth
        val thickness = max(1f, density)
        linePaint.color = fg
        linePaint.strokeWidth = thickness
        linePaint.pathEffect = null
        val uy = top + baseline + thickness * 2
        when (underline) {
            1 -> canvas.drawLine(left, uy, right, uy, linePaint)
            2 -> {
                canvas.drawLine(left, uy, right, uy, linePaint)
                canvas.drawLine(left, uy + thickness * 2, right, uy + thickness * 2, linePaint)
            }
            3 -> {
                wave.reset()
                val amp = thickness * 1.5f
                wave.moveTo(left, uy)
                wave.quadTo(left + cellWidth / 4, uy - amp, left + cellWidth / 2, uy)
                wave.quadTo(left + cellWidth * 3 / 4, uy + amp, right, uy)
                canvas.drawPath(wave, linePaint)
            }
            4 -> {
                linePaint.pathEffect = dotted
                canvas.drawLine(left, uy, right, uy, linePaint)
            }
            5 -> {
                linePaint.pathEffect = dashed
                canvas.drawLine(left, uy, right, uy, linePaint)
            }
        }
        linePaint.pathEffect = null
        if (attrs and ATTR_STRIKETHROUGH != 0) {
            val sy = top + cellHeight / 2
            canvas.drawLine(left, sy, right, sy, linePaint)
        }
        if (attrs and ATTR_OVERLINE != 0) {
            canvas.drawLine(left, top + thickness, right, top + thickness, linePaint)
        }
    }

    private fun drawCursor(canvas: Canvas, frame: TerminalFrame, focused: Boolean, on: Boolean) {
        if (!on && frame.cursorBlinking && focused) return
        val i = frame.index(frame.cursorX, frame.cursorY)
        val width = if (frame.wide(i) == WIDE_WIDE) 2 else 1
        val left = frame.cursorX * cellWidth
        val top = frame.cursorY * cellHeight
        val right = left + cellWidth * width
        val bottom = top + cellHeight
        val color = frame.cursorColor ?: frame.defaultForeground
        fillPaint.color = color
        linePaint.color = color
        linePaint.strokeWidth = max(1f, density)
        val style = if (!focused) TerminalFrame.CursorStyle.BLOCK_HOLLOW else frame.cursorStyle
        when (style) {
            TerminalFrame.CursorStyle.BLOCK -> {
                canvas.drawRect(left, top, right, bottom, fillPaint)
                val text = frame.cellText(i)
                if (text.isNotEmpty()) {
                    applyStyle(frame.background[i].let { if (it == color) frame.defaultBackground else it }, frame.attrs[i], frame.codepoints[i])
                    canvas.drawText(text, left, top + baseline, textPaint)
                }
            }
            TerminalFrame.CursorStyle.BAR -> canvas.drawRect(left, top, left + max(2f, density * 2), bottom, fillPaint)
            TerminalFrame.CursorStyle.UNDERLINE -> canvas.drawRect(left, bottom - max(2f, density * 2), right, bottom, fillPaint)
            TerminalFrame.CursorStyle.BLOCK_HOLLOW -> {
                val h = linePaint.strokeWidth / 2
                canvas.drawRect(left + h, top + h, right - h, bottom - h, linePaint)
            }
        }
    }

    /** IME composing text, shown at the cursor and never sent until committed. */
    private fun drawPreedit(canvas: Canvas, frame: TerminalFrame, text: String) {
        val cx = if (frame.cursorX in 0 until frame.cols) frame.cursorX else 0
        val cy = if (frame.cursorY in 0 until frame.rows) frame.cursorY else frame.rows - 1
        applyStyle(frame.defaultForeground, 0)
        val width = textPaint.measureText(text)
        val left = (cx * cellWidth).coerceAtMost(max(0f, frame.cols * cellWidth - width))
        val top = cy * cellHeight
        fillPaint.color = frame.defaultBackground
        canvas.drawRect(left, top, left + width, top + cellHeight, fillPaint)
        canvas.drawText(text, left, top + baseline, textPaint)
        linePaint.color = frame.defaultForeground
        linePaint.strokeWidth = max(1f, density)
        val uy = top + cellHeight - linePaint.strokeWidth
        canvas.drawLine(left, uy, left + width, uy, linePaint)
    }

    private fun drawScrollbar(canvas: Canvas, frame: TerminalFrame) {
        if (frame.scrollTotal <= frame.scrollLength) return
        val height = frame.rows * cellHeight
        val thumb = max(density * 24, height * frame.scrollLength / frame.scrollTotal)
        val top = (height - thumb) * frame.scrollOffset / (frame.scrollTotal - frame.scrollLength).coerceAtLeast(1)
        fillPaint.color = (frame.defaultForeground and 0x00FFFFFF) or (0x66 shl 24)
        val right = frame.cols * cellWidth
        canvas.drawRect(right - density * 3, top, right, top + thumb, fillPaint)
    }

    companion object {
        const val LINE_SPACING = 1.15f

        /**
         * Codepoints Nerd Fonts v3 assigns to icons: the BMP private use area
         * (Powerline, Seti, Devicons, Codicons, Font Awesome, Octicons...), the
         * plane-15 private use area (Material Design) and the IEC power symbols.
         */
        fun isNerdSymbol(cp: Int): Boolean =
            cp in 0xE000..0xF8FF || cp in 0xF0000..0xFFFFD || cp in 0x23FB..0x23FE || cp == 0x2B58
    }
}
