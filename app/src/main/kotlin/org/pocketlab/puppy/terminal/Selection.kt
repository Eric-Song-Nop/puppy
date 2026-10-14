package org.pocketlab.puppy.terminal

/** A text selection between two viewport cells, in reading order once [normalized]. */
data class Selection(val startX: Int, val startY: Int, val endX: Int, val endY: Int) {
    fun normalized(): Selection =
        if (startY < endY || (startY == endY && startX <= endX)) this else Selection(endX, endY, startX, startY)

    fun contains(x: Int, y: Int): Boolean {
        val n = normalized()
        if (y < n.startY || y > n.endY) return false
        if (y == n.startY && x < n.startX) return false
        if (y == n.endY && x > n.endX) return false
        return true
    }

    fun text(frame: TerminalFrame): String {
        val n = normalized()
        val sy = n.startY.coerceIn(0, frame.rows - 1)
        val ey = n.endY.coerceIn(0, frame.rows - 1)
        return frame.text(n.startX.coerceIn(0, frame.cols - 1), sy, n.endX.coerceIn(0, frame.cols - 1), ey)
    }

    companion object {
        /** Selects the word (non-blank run) around a cell. */
        fun word(frame: TerminalFrame, x: Int, y: Int): Selection {
            fun blank(cx: Int) = frame.codepoints[frame.index(cx, y)].let { it == 0 || it == ' '.code }
            if (blank(x)) return Selection(x, y, x, y)
            var s = x
            while (s > 0 && !blank(s - 1)) s--
            var e = x
            while (e < frame.cols - 1 && !blank(e + 1)) e++
            return Selection(s, y, e, y)
        }
    }
}
