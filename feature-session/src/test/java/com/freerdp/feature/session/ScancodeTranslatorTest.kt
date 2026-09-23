package com.freerdp.feature.session

import android.view.KeyEvent
import com.freerdp.feature.session.keyboard.ScancodeTranslator
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
    fun testModifierKeyScancodeMappings() {
        // Left Ctrl
        val ctrl = ScancodeTranslator.getScancodeForModifierKey(ModifierKey.CTRL)
        assertEquals(0x1D, ctrl.scancode)
        assertFalse(ctrl.isExtended)
        assertEquals(0x0000, ctrl.flags)

        // Left Alt
        val alt = ScancodeTranslator.getScancodeForModifierKey(ModifierKey.ALT)
        assertEquals(0x38, alt.scancode)
        assertFalse(alt.isExtended)

        // Left Shift
        val shift = ScancodeTranslator.getScancodeForModifierKey(ModifierKey.SHIFT)
        assertEquals(0x2A, shift.scancode)
        assertFalse(shift.isExtended)

        // Left Win (Extended)
        val win = ScancodeTranslator.getScancodeForModifierKey(ModifierKey.WIN)
        assertEquals(0x5B, win.scancode)
        assertTrue(win.isExtended)
        assertEquals(0x0100, win.flags)

        // Esc & Tab
        val esc = ScancodeTranslator.getScancodeForModifierKey(ModifierKey.ESC)
        assertEquals(0x01, esc.scancode)
        assertFalse(esc.isExtended)

        val tab = ScancodeTranslator.getScancodeForModifierKey(ModifierKey.TAB)
        assertEquals(0x0F, tab.scancode)
        assertFalse(tab.isExtended)

        // Del & Ins (Extended)
        val del = ScancodeTranslator.getScancodeForModifierKey(ModifierKey.DEL)
        assertEquals(0x53, del.scancode)
        assertTrue(del.isExtended)

        val ins = ScancodeTranslator.getScancodeForModifierKey(ModifierKey.INS)
        assertEquals(0x52, ins.scancode)
        assertTrue(ins.isExtended)
    }

    @Test
    fun testFunctionKeysScancodeRange() {
        val f1 = ScancodeTranslator.getScancodeForModifierKey(ModifierKey.F1)
        assertEquals(0x3B, f1.scancode)
        assertFalse(f1.isExtended)

        val f10 = ScancodeTranslator.getScancodeForModifierKey(ModifierKey.F10)
        assertEquals(0x44, f10.scancode)
        assertFalse(f10.isExtended)

        val f11 = ScancodeTranslator.getScancodeForModifierKey(ModifierKey.F11)
        assertEquals(0x57, f11.scancode)
        assertFalse(f11.isExtended)

        val f12 = ScancodeTranslator.getScancodeForModifierKey(ModifierKey.F12)
        assertEquals(0x58, f12.scancode)
        assertFalse(f12.isExtended)
    }

    @Test
    fun testNavigationAndArrowKeys() {
        val up = ScancodeTranslator.getScancodeForModifierKey(ModifierKey.ARROW_UP)
        assertEquals(0x48, up.scancode)
        assertTrue(up.isExtended)

        val down = ScancodeTranslator.getScancodeForModifierKey(ModifierKey.ARROW_DOWN)
        assertEquals(0x50, down.scancode)
        assertTrue(down.isExtended)

        val left = ScancodeTranslator.getScancodeForModifierKey(ModifierKey.ARROW_LEFT)
        assertEquals(0x4B, left.scancode)
        assertTrue(left.isExtended)

        val right = ScancodeTranslator.getScancodeForModifierKey(ModifierKey.ARROW_RIGHT)
        assertEquals(0x4D, right.scancode)
        assertTrue(right.isExtended)

        val home = ScancodeTranslator.getScancodeForModifierKey(ModifierKey.HOME)
        assertEquals(0x47, home.scancode)
        assertTrue(home.isExtended)

        val end = ScancodeTranslator.getScancodeForModifierKey(ModifierKey.END)
        assertEquals(0x4F, end.scancode)
        assertTrue(end.isExtended)

        val pgUp = ScancodeTranslator.getScancodeForModifierKey(ModifierKey.PAGE_UP)
        assertEquals(0x49, pgUp.scancode)
        assertTrue(pgUp.isExtended)

        val pgDn = ScancodeTranslator.getScancodeForModifierKey(ModifierKey.PAGE_DOWN)
        assertEquals(0x51, pgDn.scancode)
        assertTrue(pgDn.isExtended)
    }

    @Test
    fun testFromAndroidKeyCode() {
        val aKey = ScancodeTranslator.fromAndroidKeyCode(KeyEvent.KEYCODE_A)
        assertNotNull(aKey)
        assertEquals(0x1E, aKey?.scancode)

        val zKey = ScancodeTranslator.fromAndroidKeyCode(KeyEvent.KEYCODE_Z)
        assertNotNull(zKey)
        assertEquals(0x2C, zKey?.scancode)

        val enter = ScancodeTranslator.fromAndroidKeyCode(KeyEvent.KEYCODE_ENTER)
        assertNotNull(enter)
        assertEquals(0x1C, enter?.scancode)

        val backspace = ScancodeTranslator.fromAndroidKeyCode(KeyEvent.KEYCODE_DEL)
        assertNotNull(backspace)
        assertEquals(0x0E, backspace?.scancode)

        val delete = ScancodeTranslator.fromAndroidKeyCode(KeyEvent.KEYCODE_FORWARD_DEL)
        assertNotNull(delete)
        assertEquals(0x53, delete?.scancode)
        assertTrue(delete?.isExtended == true)

        val unknown = ScancodeTranslator.fromAndroidKeyCode(99999)
        assertNull(unknown)
    }

    @Test
    fun testFromChar() {
        assertEquals(0x1E, ScancodeTranslator.fromChar('a')?.scancode)
        assertEquals(0x1E, ScancodeTranslator.fromChar('A')?.scancode)
        assertEquals(0x02, ScancodeTranslator.fromChar('1')?.scancode)
        assertEquals(0x39, ScancodeTranslator.fromChar(' ')?.scancode)
        assertEquals(0x1C, ScancodeTranslator.fromChar('\n')?.scancode)
        assertEquals(0x0F, ScancodeTranslator.fromChar('\t')?.scancode)
        assertNull(ScancodeTranslator.fromChar('§'))
    }

    @Test
    fun testCtrlAltDelMacroSteps() {
        val steps = ScancodeTranslator.getMacroSteps(MacroAction.CTRL_ALT_DEL)
        assertEquals(6, steps.size)

        // 1. Down Left Ctrl
        assertEquals(0x1D, steps[0].scancode)
        assertFalse(steps[0].isExtended)
        assertTrue(steps[0].down)

        // 2. Down Left Alt
        assertEquals(0x38, steps[1].scancode)
        assertFalse(steps[1].isExtended)
        assertTrue(steps[1].down)

        // 3. Down Del (Extended)
        assertEquals(0x53, steps[2].scancode)
        assertTrue(steps[2].isExtended)
        assertTrue(steps[2].down)

        // 4. Up Del (Extended)
        assertEquals(0x53, steps[3].scancode)
        assertTrue(steps[3].isExtended)
        assertFalse(steps[3].down)

        // 5. Up Left Alt
        assertEquals(0x38, steps[4].scancode)
        assertFalse(steps[4].isExtended)
        assertFalse(steps[4].down)

        // 6. Up Left Ctrl
        assertEquals(0x1D, steps[5].scancode)
        assertFalse(steps[5].isExtended)
        assertFalse(steps[5].down)
    }

    @Test
    fun testAllMacroActionsDefinitions() {
        val altTab = ScancodeTranslator.getMacroSteps(MacroAction.ALT_TAB)
        assertEquals(4, altTab.size)
        assertEquals(0x38, altTab[0].scancode)
        assertEquals(0x0F, altTab[1].scancode)

        val altF4 = ScancodeTranslator.getMacroSteps(MacroAction.ALT_F4)
        assertEquals(4, altF4.size)
        assertEquals(0x38, altF4[0].scancode)
        assertEquals(0x3E, altF4[1].scancode)

        val winD = ScancodeTranslator.getMacroSteps(MacroAction.WIN_D)
        assertEquals(4, winD.size)
        assertEquals(0x5B, winD[0].scancode)
        assertTrue(winD[0].isExtended)
        assertEquals(0x20, winD[1].scancode)

        val winR = ScancodeTranslator.getMacroSteps(MacroAction.WIN_R)
        assertEquals(4, winR.size)
        assertEquals(0x5B, winR[0].scancode)
        assertTrue(winR[0].isExtended)
        assertEquals(0x13, winR[1].scancode)
    }

    // ---------------------------------------------------------------------
    // Boundary & corner cases (complete Set 1 coverage, unknown keys, macro
    // down/up pairing discipline, Ctrl+C / Ctrl+V clipboard macros)
    // ---------------------------------------------------------------------

    @Test
    fun testCompleteLetterMapMatchesWindowsScancodeSet1() {
        // Authoritative Windows Scan Code Set 1 letter row mapping
        val expected = mapOf(
            'a' to 0x1E, 'b' to 0x30, 'c' to 0x2E, 'd' to 0x20, 'e' to 0x12,
            'f' to 0x21, 'g' to 0x22, 'h' to 0x23, 'i' to 0x17, 'j' to 0x24,
            'k' to 0x25, 'l' to 0x26, 'm' to 0x32, 'n' to 0x31, 'o' to 0x18,
            'p' to 0x19, 'q' to 0x10, 'r' to 0x13, 's' to 0x1F, 't' to 0x14,
            'u' to 0x16, 'v' to 0x2F, 'w' to 0x11, 'x' to 0x2D, 'y' to 0x15,
            'z' to 0x2C
        )
        assertEquals("All 26 letters must be covered", 26, expected.size)
        assertEquals("Every letter must own a distinct physical key", 26, expected.values.toSet().size)

        for ((ch, code) in expected) {
            assertEquals("fromChar('$ch')", code, ScancodeTranslator.fromChar(ch)?.scancode)
            assertEquals(
                "fromChar('${ch.uppercase()}')",
                code,
                ScancodeTranslator.fromChar(ch.uppercaseChar())?.scancode
            )
            // Android KEYCODE_A..KEYCODE_Z are contiguous (29..54)
            val keyCode = KeyEvent.KEYCODE_A + (ch - 'a')
            assertEquals(
                "fromAndroidKeyCode($keyCode) for '$ch'",
                code,
                ScancodeTranslator.fromAndroidKeyCode(keyCode)?.scancode
            )
        }
    }

    @Test
    fun testDigitMapMatchesScancodeSet1() {
        // '1'..'9' -> 0x02..0x0A, '0' -> 0x0B
        val expected = mapOf(
            '1' to 0x02, '2' to 0x03, '3' to 0x04, '4' to 0x05, '5' to 0x06,
            '6' to 0x07, '7' to 0x08, '8' to 0x09, '9' to 0x0A, '0' to 0x0B
        )
        for ((ch, code) in expected) {
            assertEquals("fromChar('$ch')", code, ScancodeTranslator.fromChar(ch)?.scancode)
        }

        // Android KEYCODE digits map identically
        for (digit in 1..9) {
            val keyCode = KeyEvent.KEYCODE_1 + (digit - 1)
            val expectedCode = 0x01 + digit // KEYCODE_1 -> 0x02 ... KEYCODE_9 -> 0x0A
            assertEquals(
                "fromAndroidKeyCode($keyCode)",
                expectedCode,
                ScancodeTranslator.fromAndroidKeyCode(keyCode)?.scancode
            )
        }
        assertEquals(
            "KEYCODE_0 -> 0x0B",
            0x0B,
            ScancodeTranslator.fromAndroidKeyCode(KeyEvent.KEYCODE_0)?.scancode
        )
    }

    @Test
    fun testFunctionKeyRangeIsContiguous() {
        // F1..F10 = 0x3B..0x44, F11 = 0x57, F12 = 0x58 (never extended)
        val expected = intArrayOf(0x3B, 0x3C, 0x3D, 0x3E, 0x3F, 0x40, 0x41, 0x42, 0x43, 0x44, 0x57, 0x58)
        for (i in expected.indices) {
            val keyCode = KeyEvent.KEYCODE_F1 + i // KEYCODE_F1..KEYCODE_F12 are contiguous
            assertEquals(
                "fromAndroidKeyCode for F${i + 1}",
                expected[i],
                ScancodeTranslator.fromAndroidKeyCode(keyCode)?.scancode
            )

            val modifierKey = ModifierKey.entries[ModifierKey.F1.ordinal + i]
            val result = ScancodeTranslator.getScancodeForModifierKey(modifierKey)
            assertEquals("getScancodeForModifierKey for F${i + 1}", expected[i], result.scancode)
            assertFalse("F${i + 1} must not be extended", result.isExtended)
        }
    }

    @Test
    fun testModifierAndArrowSideMappingsMatchWindowsConventions() {
        // Right Ctrl = E0 1D, Right Alt = E0 38, Right Shift = plain 0x36
        val rightCtrl = ScancodeTranslator.fromAndroidKeyCode(KeyEvent.KEYCODE_CTRL_RIGHT)
        assertNotNull(rightCtrl)
        assertEquals(0x1D, rightCtrl?.scancode)
        assertTrue("Right Ctrl is extended", rightCtrl!!.isExtended)

        val rightAlt = ScancodeTranslator.fromAndroidKeyCode(KeyEvent.KEYCODE_ALT_RIGHT)
        assertNotNull(rightAlt)
        assertEquals(0x38, rightAlt?.scancode)
        assertTrue("Right Alt is extended", rightAlt!!.isExtended)

        val rightShift = ScancodeTranslator.fromAndroidKeyCode(KeyEvent.KEYCODE_SHIFT_RIGHT)
        assertNotNull(rightShift)
        assertEquals(0x36, rightShift?.scancode)
        assertFalse("Right Shift is NOT extended", rightShift!!.isExtended)

        // DPAD arrows are extended Set 1 navigation codes
        val arrowExpectations = listOf(
            KeyEvent.KEYCODE_DPAD_UP to 0x48,
            KeyEvent.KEYCODE_DPAD_DOWN to 0x50,
            KeyEvent.KEYCODE_DPAD_LEFT to 0x4B,
            KeyEvent.KEYCODE_DPAD_RIGHT to 0x4D
        )
        for ((keyCode, code) in arrowExpectations) {
            val result = ScancodeTranslator.fromAndroidKeyCode(keyCode)
            assertEquals("keyCode $keyCode", code, result?.scancode)
            assertTrue("Arrow 0x${code.toString(16)} must be extended", result!!.isExtended)
        }
    }

    @Test
    fun testUnknownKeysUseDefinedNullFallback() {
        // Unknown Android keycodes resolve to null (callers fall back to Unicode dispatch)
        for (keyCode in listOf(-1, 0, 99999, 1000, Int.MAX_VALUE, Int.MIN_VALUE)) {
            assertNull("KeyCode $keyCode must map to null", ScancodeTranslator.fromAndroidKeyCode(keyCode))
        }

        // Characters outside the ASCII map resolve to null as well
        for (ch in listOf('§', 'é', '€', 'ß', '中', '\u0000')) {
            assertNull("Char '$ch' must map to null", ScancodeTranslator.fromChar(ch))
        }

        // And the known-scancode predicate draws the boundary precisely
        assertTrue(ScancodeTranslator.isKnownScancode(0x01))
        assertTrue(ScancodeTranslator.isKnownScancode(0x39))
        assertTrue(ScancodeTranslator.isKnownScancode(0x7F))
        assertFalse(ScancodeTranslator.isKnownScancode(0x00))
        assertFalse(ScancodeTranslator.isKnownScancode(0x80))
        assertFalse(ScancodeTranslator.isKnownScancode(0x200))
        assertFalse(ScancodeTranslator.isKnownScancode(-1))
    }

    @Test
    fun testCtrlCAndCtrlVMacroSteps() {
        val ctrlC = ScancodeTranslator.getMacroSteps(MacroAction.CTRL_C)
        assertEquals(4, ctrlC.size)
        assertEquals(0x1D, ctrlC[0].scancode)
        assertFalse(ctrlC[0].isExtended)
        assertTrue(ctrlC[0].down)
        assertEquals(0x2E, ctrlC[1].scancode) // C
        assertTrue(ctrlC[1].down)
        assertEquals(0x2E, ctrlC[2].scancode)
        assertFalse(ctrlC[2].down)
        assertEquals(0x1D, ctrlC[3].scancode)
        assertFalse(ctrlC[3].down)

        val ctrlV = ScancodeTranslator.getMacroSteps(MacroAction.CTRL_V)
        assertEquals(4, ctrlV.size)
        assertEquals(0x1D, ctrlV[0].scancode)
        assertTrue(ctrlV[0].down)
        assertEquals(0x2F, ctrlV[1].scancode) // V
        assertTrue(ctrlV[1].down)
        assertEquals(0x2F, ctrlV[2].scancode)
        assertFalse(ctrlV[2].down)
        assertEquals(0x1D, ctrlV[3].scancode)
        assertFalse(ctrlV[3].down)
    }

    @Test
    fun testEveryMacroHasBalancedLifoDownUpPairing() {
        for (macro in MacroAction.entries) {
            val steps = ScancodeTranslator.getMacroSteps(macro)
            assertTrue("$macro must have at least 4 steps", steps.size >= 4)

            // Strict stack discipline: releases occur in LIFO order and the
            // stack must be empty when the macro finishes.
            val held = ArrayDeque<Int>()
            for ((index, step) in steps.withIndex()) {
                if (step.down) {
                    held.addLast(step.scancode)
                } else {
                    assertTrue("$macro step $index released with empty stack", held.isNotEmpty())
                    assertEquals(
                        "$macro step $index must release keys LIFO",
                        held.removeLast(),
                        step.scancode
                    )
                }
            }
            assertTrue("$macro must finish with every key released", held.isEmpty())

            // Per-scancode down/up balance
            val downsPer = steps.filter { it.down }.groupingBy { it.scancode }.eachCount()
            val upsPer = steps.filter { !it.down }.groupingBy { it.scancode }.eachCount()
            assertEquals("$macro must touch the same scancodes on both edges", downsPer.keys, upsPer.keys)
            for ((scancode, downCount) in downsPer) {
                assertEquals(
                    "$macro scancode 0x${scancode.toString(16)} must be balanced",
                    downCount,
                    upsPer.getValue(scancode)
                )
            }
        }
    }
}
