package com.freerdp.feature.session.keyboard

import android.view.KeyEvent
import com.freerdp.feature.session.modifier.MacroAction
import com.freerdp.feature.session.modifier.ModifierKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ScancodeTranslatorTest {

    @Test
    fun testWindowsVkConstantsAndScancodeMappings() {
        // Escape
        val esc = ScancodeTranslator.getScancodeForModifierKey(ModifierKey.ESC)
        assertEquals(0x01, esc.scancode)
        assertEquals(ScancodeTranslator.VK_ESCAPE, esc.vkCode)
        assertFalse(esc.isExtended)
        assertEquals(0x01, esc.extendedScancode)

        // Tab
        val tab = ScancodeTranslator.getScancodeForModifierKey(ModifierKey.TAB)
        assertEquals(0x0F, tab.scancode)
        assertEquals(ScancodeTranslator.VK_TAB, tab.vkCode)
        assertFalse(tab.isExtended)

        // Delete (Extended flag must be set and 0x0100 preserved)
        val del = ScancodeTranslator.getScancodeForModifierKey(ModifierKey.DEL)
        assertEquals(0x53, del.scancode)
        assertEquals(ScancodeTranslator.VK_DELETE, del.vkCode)
        assertTrue(del.isExtended)
        assertEquals(0x0100, del.flags)
        assertEquals(0x0153, del.extendedScancode)

        // Windows / Super key (Extended)
        val win = ScancodeTranslator.getScancodeForModifierKey(ModifierKey.WIN)
        assertEquals(0x5B, win.scancode)
        assertEquals(ScancodeTranslator.VK_LWIN, win.vkCode)
        assertTrue(win.isExtended)
        assertEquals(0x015B, win.extendedScancode)

        // Caps Lock
        val caps = ScancodeTranslator.getScancodeForModifierKey(ModifierKey.CAPS_LOCK)
        assertEquals(0x3A, caps.scancode)
        assertEquals(ScancodeTranslator.VK_CAPITAL, caps.vkCode)
        assertFalse(caps.isExtended)

        // Inverted-T arrows (all extended)
        val up = ScancodeTranslator.getScancodeForModifierKey(ModifierKey.ARROW_UP)
        assertEquals(0x48, up.scancode)
        assertEquals(ScancodeTranslator.VK_UP, up.vkCode)
        assertTrue(up.isExtended)
        assertEquals(0x0148, up.extendedScancode)

        val down = ScancodeTranslator.getScancodeForModifierKey(ModifierKey.ARROW_DOWN)
        assertEquals(0x50, down.scancode)
        assertEquals(ScancodeTranslator.VK_DOWN, down.vkCode)
        assertTrue(down.isExtended)
        assertEquals(0x0150, down.extendedScancode)

        val left = ScancodeTranslator.getScancodeForModifierKey(ModifierKey.ARROW_LEFT)
        assertEquals(0x4B, left.scancode)
        assertEquals(ScancodeTranslator.VK_LEFT, left.vkCode)
        assertTrue(left.isExtended)
        assertEquals(0x014B, left.extendedScancode)

        val right = ScancodeTranslator.getScancodeForModifierKey(ModifierKey.ARROW_RIGHT)
        assertEquals(0x4D, right.scancode)
        assertEquals(ScancodeTranslator.VK_RIGHT, right.vkCode)
        assertTrue(right.isExtended)
        assertEquals(0x014D, right.extendedScancode)
    }

    @Test
    fun testExtendedScancodeBitPreservationHelpers() {
        val normalCode = ScancodeTranslator.SCANCODE_ENTER // 0x1C
        val extendedCode = ScancodeTranslator.SCANCODE_DELETE // 0x53

        val resNormal = ScancodeTranslator.getExtendedScancode(normalCode, isExtended = false)
        assertEquals(0x1C, resNormal)
        assertFalse(ScancodeTranslator.isExtendedScancode(resNormal))
        assertEquals(0x1C, ScancodeTranslator.stripExtendedFlag(resNormal))

        val resExtended = ScancodeTranslator.getExtendedScancode(extendedCode, isExtended = true)
        assertEquals(0x0153, resExtended)
        assertTrue(ScancodeTranslator.isExtendedScancode(resExtended))
        assertEquals(0x53, ScancodeTranslator.stripExtendedFlag(resExtended))
    }

    @Test
    fun testBidirectionalWindowsVkConversions() {
        // Android KeyCode -> Windows VK Code
        assertEquals(ScancodeTranslator.VK_A, ScancodeTranslator.toWindowsVkCode(KeyEvent.KEYCODE_A))
        assertEquals(ScancodeTranslator.VK_RETURN, ScancodeTranslator.toWindowsVkCode(KeyEvent.KEYCODE_ENTER))
        assertEquals(ScancodeTranslator.VK_ESCAPE, ScancodeTranslator.toWindowsVkCode(KeyEvent.KEYCODE_ESCAPE))
        assertEquals(ScancodeTranslator.VK_TAB, ScancodeTranslator.toWindowsVkCode(KeyEvent.KEYCODE_TAB))
        assertEquals(ScancodeTranslator.VK_DELETE, ScancodeTranslator.toWindowsVkCode(KeyEvent.KEYCODE_FORWARD_DEL))
        assertEquals(ScancodeTranslator.VK_CAPITAL, ScancodeTranslator.toWindowsVkCode(KeyEvent.KEYCODE_CAPS_LOCK))
        assertEquals(ScancodeTranslator.VK_UP, ScancodeTranslator.toWindowsVkCode(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(ScancodeTranslator.VK_DOWN, ScancodeTranslator.toWindowsVkCode(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(ScancodeTranslator.VK_LEFT, ScancodeTranslator.toWindowsVkCode(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(ScancodeTranslator.VK_RIGHT, ScancodeTranslator.toWindowsVkCode(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(ScancodeTranslator.VK_F1, ScancodeTranslator.toWindowsVkCode(KeyEvent.KEYCODE_F1))
        assertEquals(ScancodeTranslator.VK_F12, ScancodeTranslator.toWindowsVkCode(KeyEvent.KEYCODE_F12))

        // Windows VK Code -> ScancodeResult
        val aResult = ScancodeTranslator.fromWindowsVkCode(ScancodeTranslator.VK_A)
        assertNotNull(aResult)
        assertEquals(ScancodeTranslator.SCANCODE_A, aResult?.scancode)
        assertFalse(aResult?.isExtended == true)

        val delResult = ScancodeTranslator.fromWindowsVkCode(ScancodeTranslator.VK_DELETE)
        assertNotNull(delResult)
        assertEquals(ScancodeTranslator.SCANCODE_DELETE, delResult?.scancode)
        assertTrue(delResult?.isExtended == true)
        assertEquals(0x0153, delResult?.extendedScancode)

        val upResult = ScancodeTranslator.fromWindowsVkCode(ScancodeTranslator.VK_UP)
        assertNotNull(upResult)
        assertEquals(ScancodeTranslator.SCANCODE_UP_ARROW, upResult?.scancode)
        assertTrue(upResult?.isExtended == true)

        val unmapped = ScancodeTranslator.fromWindowsVkCode(0xFFFF)
        assertNull(unmapped)
    }

    @Test
    fun testFromCharPopulatesVkCodeAndScancode() {
        val a = ScancodeTranslator.fromChar('a')
        assertNotNull(a)
        assertEquals(0x1E, a?.scancode)
        assertEquals(ScancodeTranslator.VK_A, a?.vkCode)

        val capA = ScancodeTranslator.fromChar('A')
        assertNotNull(capA)
        assertEquals(0x1E, capA?.scancode)
        assertEquals(ScancodeTranslator.VK_A, capA?.vkCode)

        val one = ScancodeTranslator.fromChar('1')
        assertNotNull(one)
        assertEquals(0x02, one?.scancode)
        assertEquals(ScancodeTranslator.VK_1, one?.vkCode)

        val space = ScancodeTranslator.fromChar(' ')
        assertNotNull(space)
        assertEquals(0x39, space?.scancode)
        assertEquals(ScancodeTranslator.VK_SPACE, space?.vkCode)

        val enter = ScancodeTranslator.fromChar('\n')
        assertNotNull(enter)
        assertEquals(0x1C, enter?.scancode)
        assertEquals(ScancodeTranslator.VK_RETURN, enter?.vkCode)

        val tab = ScancodeTranslator.fromChar('\t')
        assertNotNull(tab)
        assertEquals(0x0F, tab?.scancode)
        assertEquals(ScancodeTranslator.VK_TAB, tab?.vkCode)
    }

    @Test
    fun testIsKnownScancodeRangeDiscipline() {
        assertTrue(ScancodeTranslator.isKnownScancode(0x01)) // ESC
        assertTrue(ScancodeTranslator.isKnownScancode(0x1E)) // A
        assertTrue(ScancodeTranslator.isKnownScancode(0x58)) // F12
        assertTrue(ScancodeTranslator.isKnownScancode(0x0153)) // Extended Delete (with 0x0100 bit preserved)

        assertFalse(ScancodeTranslator.isKnownScancode(0x00))
        assertFalse(ScancodeTranslator.isKnownScancode(-1))
        assertFalse(ScancodeTranslator.isKnownScancode(0x80))
        assertFalse(ScancodeTranslator.isKnownScancode(0xFF))
    }

    @Test
    fun testMacroStepsAreBalanced() {
        for (macro in MacroAction.entries) {
            val steps = ScancodeTranslator.getMacroSteps(macro)
            assertTrue("Macro $macro must not be empty", steps.isNotEmpty())

            val downs = steps.filter { it.down }
            val ups = steps.filter { !it.down }
            assertEquals("Macro $macro must have balanced down and up counts", downs.size, ups.size)
        }
    }
}
