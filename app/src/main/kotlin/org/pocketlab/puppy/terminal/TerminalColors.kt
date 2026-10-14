package org.pocketlab.puppy.terminal

/** Default colors handed to the VT core; program OSC overrides stay in effect. */
data class TerminalColors(
    val background: Int,
    val foreground: Int,
    val cursor: Int? = null,
    /** 256 ARGB entries, or null for the xterm defaults. */
    val palette: IntArray? = null,
    val selectionBackground: Int? = null,
    val selectionForeground: Int? = null,
) {
    override fun equals(other: Any?) = other is TerminalColors && background == other.background &&
        foreground == other.foreground && cursor == other.cursor && selectionBackground == other.selectionBackground &&
        selectionForeground == other.selectionForeground && palette.contentEquals(other.palette)

    override fun hashCode() = ((background * 31 + foreground) * 31 + (cursor ?: 0)) * 31 + palette.contentHashCode()

    companion object {
        val DEFAULT = TerminalColors(background = 0xFF1E1F29.toInt(), foreground = 0xFFF8F8F2.toInt())
    }
}
