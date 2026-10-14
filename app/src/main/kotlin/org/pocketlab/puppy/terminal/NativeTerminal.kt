package org.pocketlab.puppy.terminal

import java.nio.ByteBuffer

/**
 * JNI bindings to the Ghostty VT core in libpuppy.so (native/src/main.zig).
 *
 * A handle must only be used from one thread at a time; [TerminalCore]
 * enforces that. Text crosses as UTF-8 byte arrays.
 */
internal object NativeTerminal {
    init {
        System.loadLibrary("puppy")
    }

    /** Effect bits returned by [feed]; mirror `term.Event`. */
    const val EVENT_REPLIES = 1 shl 0
    const val EVENT_TITLE = 1 shl 1
    const val EVENT_BELL = 1 shl 2
    const val EVENT_CLIPBOARD = 1 shl 3
    const val EVENT_PWD = 1 shl 4
    const val EVENT_RENDER_HOLD = 1 shl 5
    const val EVENT_RESET = 1 shl 6

    const val SCROLL_DELTA = 0
    const val SCROLL_TOP = 1
    const val SCROLL_BOTTOM = 2
    const val SCROLL_ROW = 3

    @JvmStatic external fun create(cols: Int, rows: Int, scrollbackLines: Int): Long
    @JvmStatic external fun destroy(handle: Long)
    @JvmStatic external fun feed(handle: Long, data: ByteArray, offset: Int, length: Int): Int
    @JvmStatic external fun takeReplies(handle: Long): ByteArray?
    @JvmStatic external fun takeClipboard(handle: Long): ByteArray?
    @JvmStatic external fun resize(handle: Long, cols: Int, rows: Int, cellWidth: Int, cellHeight: Int): Boolean
    @JvmStatic external fun setColors(handle: Long, background: Int, foreground: Int, cursor: Int, hasCursor: Boolean, palette: IntArray?)
    @JvmStatic external fun scroll(handle: Long, kind: Int, value: Int)
    @JvmStatic external fun frameCapacity(cols: Int, rows: Int): Int
    @JvmStatic external fun snapshot(handle: Long, buffer: ByteBuffer, force: Boolean): Int
    @JvmStatic external fun invalidate(handle: Long)
    @JvmStatic external fun setClipboardWriteAllowed(handle: Long, allowed: Boolean)
    /** (cols shl 16) or rows, as the terminal currently is. */
    @JvmStatic external fun gridSize(handle: Long): Int
    @JvmStatic external fun title(handle: Long): ByteArray?
    @JvmStatic external fun pwd(handle: Long): ByteArray?
    @JvmStatic external fun modes(handle: Long): Int

    /** [action]: 0 release, 1 press, 2 repeat. [key] is a Ghostty key name. */
    @JvmStatic external fun encodeKey(
        handle: Long, key: ByteArray, text: ByteArray?, mods: Int, consumedMods: Int, action: Int, unshifted: Int,
    ): ByteArray?

    /** [action]: 0 press, 1 release, 2 motion. [button]: 0 none, 1 left, 2 right, 3 middle, 4/5 wheel up/down. */
    @JvmStatic external fun encodeMouse(
        handle: Long, action: Int, button: Int, mods: Int, x: Float, y: Float, width: Int, height: Int, anyPressed: Boolean,
    ): ByteArray?

    /** Visible Kitty graphics placements, [KittyPlacement.WORDS] ints each; returns the count. */
    @JvmStatic external fun kittyPlacements(handle: Long, out: IntArray): Int

    /** Premultiplied RGBA pixels of an image; width and height go to `sizeOut`. */
    @JvmStatic external fun kittyImage(handle: Long, id: Int, sizeOut: IntArray): ByteArray?

    @JvmStatic external fun encodePaste(handle: Long, data: ByteArray): ByteArray?
    @JvmStatic external fun encodeFocus(handle: Long, gained: Boolean): ByteArray?
}
