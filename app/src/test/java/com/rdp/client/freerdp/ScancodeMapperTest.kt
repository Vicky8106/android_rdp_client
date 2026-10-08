package com.rdp.client.freerdp

import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test

class ScancodeMapperTest {

    @Test
    fun testLettersMappingAndBijectiveReverse() {
        val letterKeyCodes = listOf(
            KeyEvent.KEYCODE_A to 0x1E,
            KeyEvent.KEYCODE_B to 0x30,
            KeyEvent.KEYCODE_C to 0x2E,
            KeyEvent.KEYCODE_D to 0x20,
            KeyEvent.KEYCODE_E to 0x12,
            KeyEvent.KEYCODE_F to 0x21,
            KeyEvent.KEYCODE_G to 0x22,
            KeyEvent.KEYCODE_H to 0x23,
            KeyEvent.KEYCODE_I to 0x17,
            KeyEvent.KEYCODE_J to 0x24,
            KeyEvent.KEYCODE_K to 0x25,
            KeyEvent.KEYCODE_L to 0x26,
            KeyEvent.KEYCODE_M to 0x32,
            KeyEvent.KEYCODE_N to 0x31,
            KeyEvent.KEYCODE_O to 0x18,
            KeyEvent.KEYCODE_P to 0x19,
            KeyEvent.KEYCODE_Q to 0x10,
            KeyEvent.KEYCODE_R to 0x13,
            KeyEvent.KEYCODE_S to 0x1F,
            KeyEvent.KEYCODE_T to 0x14,
            KeyEvent.KEYCODE_U to 0x16,
            KeyEvent.KEYCODE_V to 0x2F,
            KeyEvent.KEYCODE_W to 0x11,
            KeyEvent.KEYCODE_X to 0x2D,
            KeyEvent.KEYCODE_Y to 0x15,
            KeyEvent.KEYCODE_Z to 0x2C
        )

        for ((keyCode, expectedScancode) in letterKeyCodes) {
            val scancode = ScancodeMapper.toScancode(keyCode)
            assertNotNull("Scancode for keyCode $keyCode should not be null", scancode)
            assertEquals("Scancode mismatch for keyCode $keyCode", expectedScancode, scancode!!.code)
            assertFalse("Letter should not be extended", scancode.isExtended)

            val reverseKeyCode = ScancodeMapper.toAndroidKeyCode(scancode.code, scancode.isExtended)
            assertEquals("Reverse lookup failed for keyCode $keyCode", keyCode, reverseKeyCode)
        }
    }

    @Test
    fun testDigitsMappingAndBijectiveReverse() {
        val digitKeyCodes = listOf(
            KeyEvent.KEYCODE_0 to 0x0B,
            KeyEvent.KEYCODE_1 to 0x02,
            KeyEvent.KEYCODE_2 to 0x03,
            KeyEvent.KEYCODE_3 to 0x04,
            KeyEvent.KEYCODE_4 to 0x05,
            KeyEvent.KEYCODE_5 to 0x06,
            KeyEvent.KEYCODE_6 to 0x07,
            KeyEvent.KEYCODE_7 to 0x08,
            KeyEvent.KEYCODE_8 to 0x09,
            KeyEvent.KEYCODE_9 to 0x0A
        )

        for ((keyCode, expectedScancode) in digitKeyCodes) {
            val scancode = ScancodeMapper.toScancode(keyCode)
            assertNotNull("Scancode for digit $keyCode should not be null", scancode)
            assertEquals(expectedScancode, scancode!!.code)
            assertFalse(scancode.isExtended)

            val reverse = ScancodeMapper.toAndroidKeyCode(scancode.code, scancode.isExtended)
            assertEquals(keyCode, reverse)
        }
    }

    @Test
    fun testFunctionKeysMappingAndBijectiveReverse() {
        val fKeyCodes = listOf(
            KeyEvent.KEYCODE_F1 to 0x3B,
            KeyEvent.KEYCODE_F2 to 0x3C,
            KeyEvent.KEYCODE_F3 to 0x3D,
            KeyEvent.KEYCODE_F4 to 0x3E,
            KeyEvent.KEYCODE_F5 to 0x3F,
            KeyEvent.KEYCODE_F6 to 0x40,
            KeyEvent.KEYCODE_F7 to 0x41,
            KeyEvent.KEYCODE_F8 to 0x42,
            KeyEvent.KEYCODE_F9 to 0x43,
            KeyEvent.KEYCODE_F10 to 0x44,
            KeyEvent.KEYCODE_F11 to 0x57,
            KeyEvent.KEYCODE_F12 to 0x58
        )

        for ((keyCode, expectedScancode) in fKeyCodes) {
            val scancode = ScancodeMapper.toScancode(keyCode)
            assertNotNull(scancode)
            assertEquals(expectedScancode, scancode!!.code)
            assertFalse(scancode.isExtended)

            val reverse = ScancodeMapper.toAndroidKeyCode(scancode.code, scancode.isExtended)
            assertEquals(keyCode, reverse)
        }
    }

    @Test
    fun testExtendedNavigationKeys() {
        val extendedKeys = listOf(
            KeyEvent.KEYCODE_DPAD_UP to 0x48,
            KeyEvent.KEYCODE_DPAD_DOWN to 0x50,
            KeyEvent.KEYCODE_DPAD_LEFT to 0x4B,
            KeyEvent.KEYCODE_DPAD_RIGHT to 0x4D,
            KeyEvent.KEYCODE_MOVE_HOME to 0x47,
            KeyEvent.KEYCODE_MOVE_END to 0x4F,
            KeyEvent.KEYCODE_PAGE_UP to 0x49,
            KeyEvent.KEYCODE_PAGE_DOWN to 0x51,
            KeyEvent.KEYCODE_FORWARD_DEL to 0x53,
            KeyEvent.KEYCODE_INSERT to 0x52
        )

        for ((keyCode, expectedScancode) in extendedKeys) {
            val scancode = ScancodeMapper.toScancode(keyCode)
            assertNotNull(scancode)
            assertEquals(expectedScancode, scancode!!.code)
            assertTrue("Key $keyCode must be extended", scancode.isExtended)

            val flagsDown = scancode.toRdpFlags(isDown = true)
            assertTrue((flagsDown and RdpScancode.KBD_FLAGS_EXTENDED) != 0)
            assertEquals(0, flagsDown and RdpScancode.KBD_FLAGS_RELEASE)

            val flagsUp = scancode.toRdpFlags(isDown = false)
            assertTrue((flagsUp and RdpScancode.KBD_FLAGS_EXTENDED) != 0)
            assertTrue((flagsUp and RdpScancode.KBD_FLAGS_RELEASE) != 0)

            val reverse = ScancodeMapper.toAndroidKeyCode(scancode.code, scancode.isExtended)
            assertEquals(keyCode, reverse)
        }
    }

    @Test
    fun testModifiersAndWindowsKeys() {
        // Left vs Right Ctrl
        val leftCtrl = ScancodeMapper.toScancode(KeyEvent.KEYCODE_CTRL_LEFT)
        assertNotNull(leftCtrl)
        assertEquals(0x1D, leftCtrl!!.code)
        assertFalse(leftCtrl.isExtended)

        val rightCtrl = ScancodeMapper.toScancode(KeyEvent.KEYCODE_CTRL_RIGHT)
        assertNotNull(rightCtrl)
        assertEquals(0x1D, rightCtrl!!.code)
        assertTrue(rightCtrl.isExtended)

        // Left vs Right Alt
        val leftAlt = ScancodeMapper.toScancode(KeyEvent.KEYCODE_ALT_LEFT)
        assertNotNull(leftAlt)
        assertEquals(0x38, leftAlt!!.code)
        assertFalse(leftAlt.isExtended)

        val rightAlt = ScancodeMapper.toScancode(KeyEvent.KEYCODE_ALT_RIGHT)
        assertNotNull(rightAlt)
        assertEquals(0x38, rightAlt!!.code)
        assertTrue(rightAlt.isExtended)

        // Windows / Meta keys
        val leftWin = ScancodeMapper.toScancode(KeyEvent.KEYCODE_META_LEFT)
        assertNotNull(leftWin)
        assertEquals(0x5B, leftWin!!.code)
        assertTrue(leftWin.isExtended)

        val rightWin = ScancodeMapper.toScancode(KeyEvent.KEYCODE_META_RIGHT)
        assertNotNull(rightWin)
        assertEquals(0x5C, rightWin!!.code)
        assertTrue(rightWin.isExtended)

        // Modifier check helper
        assertTrue(ScancodeMapper.isModifierKey(KeyEvent.KEYCODE_CTRL_LEFT))
        assertTrue(ScancodeMapper.isModifierKey(KeyEvent.KEYCODE_CTRL_RIGHT))
        assertTrue(ScancodeMapper.isModifierKey(KeyEvent.KEYCODE_ALT_LEFT))
        assertTrue(ScancodeMapper.isModifierKey(KeyEvent.KEYCODE_ALT_RIGHT))
        assertTrue(ScancodeMapper.isModifierKey(KeyEvent.KEYCODE_SHIFT_LEFT))
        assertTrue(ScancodeMapper.isModifierKey(KeyEvent.KEYCODE_SHIFT_RIGHT))
        assertTrue(ScancodeMapper.isModifierKey(KeyEvent.KEYCODE_META_LEFT))
        assertTrue(ScancodeMapper.isModifierKey(KeyEvent.KEYCODE_META_RIGHT))
        assertFalse(ScancodeMapper.isModifierKey(KeyEvent.KEYCODE_A))
        assertFalse(ScancodeMapper.isModifierKey(KeyEvent.KEYCODE_ENTER))
    }

    @Test
    fun testKeypadKeysDistinction() {
        // Main Enter vs Numpad Enter
        val mainEnter = ScancodeMapper.toScancode(KeyEvent.KEYCODE_ENTER)
        val numpadEnter = ScancodeMapper.toScancode(KeyEvent.KEYCODE_NUMPAD_ENTER)
        assertNotNull(mainEnter)
        assertNotNull(numpadEnter)
        assertEquals(0x1C, mainEnter!!.code)
        assertFalse(mainEnter.isExtended)
        assertEquals(0x1C, numpadEnter!!.code)
        assertTrue(numpadEnter.isExtended)

        assertEquals(KeyEvent.KEYCODE_ENTER, ScancodeMapper.toAndroidKeyCode(0x1C, false))
        assertEquals(KeyEvent.KEYCODE_NUMPAD_ENTER, ScancodeMapper.toAndroidKeyCode(0x1C, true))

        // Main Slash vs Numpad Divide
        val mainSlash = ScancodeMapper.toScancode(KeyEvent.KEYCODE_SLASH)
        val numpadDivide = ScancodeMapper.toScancode(KeyEvent.KEYCODE_NUMPAD_DIVIDE)
        assertNotNull(mainSlash)
        assertNotNull(numpadDivide)
        assertEquals(0x35, mainSlash!!.code)
        assertFalse(mainSlash.isExtended)
        assertEquals(0x35, numpadDivide!!.code)
        assertTrue(numpadDivide.isExtended)

        assertEquals(KeyEvent.KEYCODE_SLASH, ScancodeMapper.toAndroidKeyCode(0x35, false))
        assertEquals(KeyEvent.KEYCODE_NUMPAD_DIVIDE, ScancodeMapper.toAndroidKeyCode(0x35, true))
    }

    @Test
    fun testUnmappedKeycodeReturnsNull() {
        assertNull(ScancodeMapper.toScancode(99999))
        assertNull(ScancodeMapper.toAndroidKeyCode(0xFF, false))
    }
}
