package org.pocketlab.puppy.terminal

import android.view.KeyEvent

/**
 * Maps Android key events to Ghostty's key encoder inputs. Ghostty decides
 * the bytes from the terminal's current modes (cursor keys, Kitty keyboard
 * protocol...), so nothing here hard-codes escape sequences.
 */
object KeyMapper {
    // Ghostty `Mods` bit layout (src/input/key_mods.zig).
    const val MOD_SHIFT = 1 shl 0
    const val MOD_CTRL = 1 shl 1
    const val MOD_ALT = 1 shl 2
    const val MOD_SUPER = 1 shl 3
    const val MOD_CAPS = 1 shl 4
    const val MOD_NUM = 1 shl 5

    const val ACTION_RELEASE = 0
    const val ACTION_PRESS = 1
    const val ACTION_REPEAT = 2

    data class Encoded(
        val key: String,
        val text: String?,
        val mods: Int,
        val consumedMods: Int,
        val action: Int,
        val unshifted: Int,
    )

    fun mods(meta: Int): Int {
        var m = 0
        if (meta and KeyEvent.META_SHIFT_ON != 0) m = m or MOD_SHIFT
        if (meta and KeyEvent.META_CTRL_ON != 0) m = m or MOD_CTRL
        if (meta and KeyEvent.META_ALT_ON != 0) m = m or MOD_ALT
        if (meta and KeyEvent.META_META_ON != 0) m = m or MOD_SUPER
        if (meta and KeyEvent.META_CAPS_LOCK_ON != 0) m = m or MOD_CAPS
        if (meta and KeyEvent.META_NUM_LOCK_ON != 0) m = m or MOD_NUM
        return m
    }

    /** Returns null for keys the terminal should not consume (e.g. Back, volume). */
    fun encode(event: KeyEvent): Encoded? {
        val code = event.keyCode
        if (isSystemKey(code)) return null
        val name = keyName(code)
        val meta = event.metaState
        val mods = mods(meta)

        // Text as the layout produces it, ignoring Ctrl/Alt/Meta so that
        // Ctrl+C still reports "c" and the encoder applies the modifier.
        val textMeta = meta and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK or KeyEvent.META_META_MASK).inv()
        val unicode = event.getUnicodeChar(textMeta)
        val text = if (unicode > 0 && unicode and KeyCharacterMapCombining == 0 && unicode >= 0x20 && unicode != 0x7F) {
            String(Character.toChars(unicode))
        } else {
            null
        }
        var effectiveMods = mods
        var consumed = if (text != null) mods and (MOD_SHIFT or MOD_CAPS) else 0
        // AltGr (right Alt) selects layout characters such as @ on German
        // layouts: send that character, not Alt+key.
        if (meta and KeyEvent.META_ALT_RIGHT_ON != 0) {
            val altGrMeta = meta and (KeyEvent.META_CTRL_MASK or KeyEvent.META_META_MASK).inv()
            val altGr = event.getUnicodeChar(altGrMeta)
            if (altGr >= 0x20 && altGr != 0x7F && altGr and KeyCharacterMapCombining == 0 && altGr != unicode) {
                effectiveMods = mods and MOD_ALT.inv()
                consumed = consumed or MOD_ALT
                return Encoded(name ?: "unidentified", String(Character.toChars(altGr)), effectiveMods, consumed, actionOf(event), unshiftedOf(event))
            }
        }
        if (name == null && text == null) return null
        return Encoded(name ?: "unidentified", text, effectiveMods, consumed, actionOf(event), unshiftedOf(event))
    }

    private fun actionOf(event: KeyEvent) = when {
        event.action == KeyEvent.ACTION_UP -> ACTION_RELEASE
        event.repeatCount > 0 -> ACTION_REPEAT
        else -> ACTION_PRESS
    }

    private fun unshiftedOf(event: KeyEvent) =
        event.getUnicodeChar(0).takeIf { it > 0 && it and KeyCharacterMapCombining == 0 } ?: 0

    private const val KeyCharacterMapCombining = 0x80000000.toInt()

    private val punctuation = mapOf(
        '`' to "backquote", '\\' to "backslash", '[' to "bracket_left", ']' to "bracket_right",
        ',' to "comma", '=' to "equal", '-' to "minus", '.' to "period", '\'' to "quote",
        ';' to "semicolon", '/' to "slash", ' ' to "space",
    )

    /**
     * A key press for one printable ASCII character committed by an input
     * method (soft keyboards send text, not key events). Going through the
     * key encoder keeps it correct under the Kitty keyboard protocol, which
     * programs like herdr enable; in legacy mode it still encodes as the
     * plain character. Returns null for anything else.
     */
    fun forChar(c: Char): Encoded? {
        if (c.code !in 0x20..0x7E) return null
        val name = when (c) {
            in 'a'..'z' -> "key_$c"
            in 'A'..'Z' -> "key_" + c.lowercaseChar()
            in '0'..'9' -> "digit_$c"
            else -> punctuation[c] ?: "unidentified"
        }
        val shifted = c in 'A'..'Z'
        val mods = if (shifted) MOD_SHIFT else 0
        val unshifted = if (shifted) c.lowercaseChar().code else c.code
        return Encoded(name, c.toString(), mods, mods, ACTION_PRESS, unshifted)
    }

    fun isSystemKey(code: Int) = when (code) {
        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
        KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_APP_SWITCH, KeyEvent.KEYCODE_MENU,
        KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT, KeyEvent.KEYCODE_CTRL_LEFT,
        KeyEvent.KEYCODE_CTRL_RIGHT, KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT,
        KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT, KeyEvent.KEYCODE_CAPS_LOCK,
        KeyEvent.KEYCODE_NUM_LOCK, KeyEvent.KEYCODE_SCROLL_LOCK, KeyEvent.KEYCODE_FUNCTION,
        KeyEvent.KEYCODE_LANGUAGE_SWITCH, KeyEvent.KEYCODE_SWITCH_CHARSET -> true
        else -> false
    }

    /** Ghostty `Key` enum name for a physical key, or null if unmapped. */
    fun keyName(code: Int): String? = when (code) {
        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> "key_" + ('a' + (code - KeyEvent.KEYCODE_A))
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> "digit_" + (code - KeyEvent.KEYCODE_0)
        in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> "numpad_" + (code - KeyEvent.KEYCODE_NUMPAD_0)
        in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> "f" + (code - KeyEvent.KEYCODE_F1 + 1)
        KeyEvent.KEYCODE_GRAVE -> "backquote"
        KeyEvent.KEYCODE_BACKSLASH -> "backslash"
        KeyEvent.KEYCODE_LEFT_BRACKET -> "bracket_left"
        KeyEvent.KEYCODE_RIGHT_BRACKET -> "bracket_right"
        KeyEvent.KEYCODE_COMMA -> "comma"
        KeyEvent.KEYCODE_EQUALS -> "equal"
        KeyEvent.KEYCODE_MINUS -> "minus"
        KeyEvent.KEYCODE_PERIOD -> "period"
        KeyEvent.KEYCODE_APOSTROPHE -> "quote"
        KeyEvent.KEYCODE_SEMICOLON -> "semicolon"
        KeyEvent.KEYCODE_SLASH -> "slash"
        KeyEvent.KEYCODE_DEL -> "backspace"
        KeyEvent.KEYCODE_FORWARD_DEL -> "delete"
        KeyEvent.KEYCODE_ENTER -> "enter"
        KeyEvent.KEYCODE_SPACE -> "space"
        KeyEvent.KEYCODE_TAB -> "tab"
        KeyEvent.KEYCODE_ESCAPE -> "escape"
        KeyEvent.KEYCODE_MOVE_END -> "end"
        KeyEvent.KEYCODE_MOVE_HOME -> "home"
        KeyEvent.KEYCODE_INSERT -> "insert"
        KeyEvent.KEYCODE_PAGE_DOWN -> "page_down"
        KeyEvent.KEYCODE_PAGE_UP -> "page_up"
        KeyEvent.KEYCODE_DPAD_DOWN -> "arrow_down"
        KeyEvent.KEYCODE_DPAD_LEFT -> "arrow_left"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "arrow_right"
        KeyEvent.KEYCODE_DPAD_UP -> "arrow_up"
        KeyEvent.KEYCODE_NUMPAD_ADD -> "numpad_add"
        KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> "numpad_subtract"
        KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> "numpad_multiply"
        KeyEvent.KEYCODE_NUMPAD_DIVIDE -> "numpad_divide"
        KeyEvent.KEYCODE_NUMPAD_DOT -> "numpad_decimal"
        KeyEvent.KEYCODE_NUMPAD_ENTER -> "numpad_enter"
        KeyEvent.KEYCODE_NUMPAD_EQUALS -> "numpad_equal"
        KeyEvent.KEYCODE_NUMPAD_COMMA -> "numpad_comma"
        KeyEvent.KEYCODE_BREAK -> "pause"
        KeyEvent.KEYCODE_SYSRQ -> "print_screen"
        else -> null
    }
}
