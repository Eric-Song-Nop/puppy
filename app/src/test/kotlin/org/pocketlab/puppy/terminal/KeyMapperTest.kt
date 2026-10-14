package org.pocketlab.puppy.terminal

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyMapperTest {
    @Test fun physicalKeyNamesMatchGhostty() {
        assertEquals("key_a", KeyMapper.keyName(KeyEvent.KEYCODE_A))
        assertEquals("key_z", KeyMapper.keyName(KeyEvent.KEYCODE_Z))
        assertEquals("digit_7", KeyMapper.keyName(KeyEvent.KEYCODE_7))
        assertEquals("f12", KeyMapper.keyName(KeyEvent.KEYCODE_F12))
        assertEquals("arrow_up", KeyMapper.keyName(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals("backspace", KeyMapper.keyName(KeyEvent.KEYCODE_DEL))
        assertEquals("escape", KeyMapper.keyName(KeyEvent.KEYCODE_ESCAPE))
        assertEquals("numpad_enter", KeyMapper.keyName(KeyEvent.KEYCODE_NUMPAD_ENTER))
        assertNull(KeyMapper.keyName(KeyEvent.KEYCODE_CAMERA))
    }

    @Test fun systemKeysAreLeftToAndroid() {
        assertTrue(KeyMapper.isSystemKey(KeyEvent.KEYCODE_BACK))
        assertTrue(KeyMapper.isSystemKey(KeyEvent.KEYCODE_VOLUME_UP))
        assertTrue(KeyMapper.isSystemKey(KeyEvent.KEYCODE_SHIFT_LEFT))
    }

    @Test fun modifierBitsMatchGhosttyMods() {
        val meta = KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON
        assertEquals(KeyMapper.MOD_CTRL or KeyMapper.MOD_SHIFT, KeyMapper.mods(meta))
        assertEquals(KeyMapper.MOD_ALT, KeyMapper.mods(KeyEvent.META_ALT_ON))
    }
}
