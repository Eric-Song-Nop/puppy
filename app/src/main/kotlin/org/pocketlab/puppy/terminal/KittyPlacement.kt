package org.pocketlab.puppy.terminal

import android.graphics.Bitmap

/**
 * One visible Kitty graphics placement (layout from native/src/graphics.zig).
 * Sizes and offsets are in terminal pixels (the cell size the core was told).
 */
class KittyPlacement(
    val bitmap: Bitmap,
    val col: Int,
    val row: Int,
    val xOffset: Int,
    val yOffset: Int,
    val width: Int,
    val height: Int,
    val sourceX: Int,
    val sourceY: Int,
    val sourceWidth: Int,
    val sourceHeight: Int,
    /** Negative z draws below text, otherwise above. */
    val z: Int,
) {
    companion object {
        const val WORDS = 14
        const val MAX = 64
    }
}
