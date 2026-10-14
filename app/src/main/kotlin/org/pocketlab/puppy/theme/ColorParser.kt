package org.pocketlab.puppy.theme

import java.io.Reader

/**
 * Color values as Ghostty's `RGB.parse` accepts them (src/terminal/color.zig
 * at the pinned commit): `#rgb`, `#rrggbb`, `#rrrgggbbb`, `#rrrrggggbbbb`,
 * the same without `#` for 3/6 digits, X11 names, and `rgb:`/`rgbi:` specs.
 */
object ColorParser {
    /** Returns 0xFFRRGGBB, or null if [value] is not a color. */
    fun parse(value: String, x11: X11Colors = X11Colors.EMPTY): Int? {
        val input = value.trim(' ', '\t')
        if (input.isEmpty()) return null
        if (input[0] == '#') {
            val hex = input.substring(1)
            val n = when (hex.length) {
                3, 6, 9, 12 -> hex.length / 3
                else -> return null
            }
            return rgb(fromHex(hex.substring(0, n)), fromHex(hex.substring(n, 2 * n)), fromHex(hex.substring(2 * n)))
        }
        x11[input]?.let { return it }
        if (input.length == 3 || input.length == 6) {
            val n = input.length / 3
            return rgb(fromHex(input.substring(0, n)), fromHex(input.substring(n, 2 * n)), fromHex(input.substring(2 * n)))
        }
        if (input.length < "rgb:a/a/a".length || !input.startsWith("rgb")) return null
        var i = 3
        val intensity = input[i] == 'i'
        if (intensity) i++
        if (input.getOrNull(i) != ':') return null
        val parts = input.substring(i + 1).split('/')
        if (parts.size != 3) return null
        val conv: (String) -> Int? = if (intensity) ::fromIntensity else ::fromHex
        return rgb(conv(parts[0]), conv(parts[1]), conv(parts[2]))
    }

    private fun rgb(r: Int?, g: Int?, b: Int?): Int? {
        if (r == null || g == null || b == null) return null
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** 1-4 hex digits scaled to 8 bits, as Ghostty's `fromHex`. */
    private fun fromHex(s: String): Int? {
        if (s.isEmpty() || s.length > 4) return null
        if (!s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        val v = s.toInt(16)
        val max = (1 shl (4 * s.length)) - 1
        return v * 255 / max
    }

    private fun fromIntensity(s: String): Int? {
        val f = s.toDoubleOrNull() ?: return null
        if (f < 0.0 || f > 1.0) return null
        return (f * 255).toInt()
    }
}

/** X11 color names, matched case-insensitively like Ghostty's table. */
class X11Colors private constructor(private val map: Map<String, Int>) {
    operator fun get(name: String): Int? = map[name.lowercase()]

    val size get() = map.size

    companion object {
        val EMPTY = X11Colors(emptyMap())

        /** Parses Ghostty's rgb.txt format: "RRR GGG BBB<tab(s)>name". */
        fun parse(reader: Reader): X11Colors {
            val map = HashMap<String, Int>()
            reader.forEachLine { raw ->
                val line = raw.trimEnd('\r')
                if (line.length < 13) return@forEachLine
                val r = line.substring(0, 3).trim().toIntOrNull() ?: return@forEachLine
                val g = line.substring(4, 7).trim().toIntOrNull() ?: return@forEachLine
                val b = line.substring(8, 11).trim().toIntOrNull() ?: return@forEachLine
                val name = line.substring(12).trim(' ', '\t')
                if (name.isNotEmpty()) map[name.lowercase()] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            return X11Colors(map)
        }
    }
}
