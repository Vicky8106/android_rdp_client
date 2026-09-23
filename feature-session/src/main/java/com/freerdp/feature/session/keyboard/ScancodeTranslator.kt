package com.freerdp.feature.session.keyboard

import android.view.KeyEvent
import com.freerdp.feature.session.modifier.MacroAction
import com.freerdp.feature.session.modifier.ModifierKey

data class ScancodeResult(
    val scancode: Int,
    val isExtended: Boolean = false
) {
    val flags: Int get() = if (isExtended) 0x0100 else 0x0000
}

data class MacroStep(
    val scancode: Int,
    val isExtended: Boolean,
    val down: Boolean
)

object ScancodeTranslator {

    // Windows PC Scancode Set 1 Constants
    const val SCANCODE_ESCAPE = 0x01
    const val SCANCODE_1 = 0x02
    const val SCANCODE_2 = 0x03
    const val SCANCODE_3 = 0x04
    const val SCANCODE_4 = 0x05
    const val SCANCODE_5 = 0x06
    const val SCANCODE_6 = 0x07
    const val SCANCODE_7 = 0x08
    const val SCANCODE_8 = 0x09
    const val SCANCODE_9 = 0x0A
    const val SCANCODE_0 = 0x0B
    const val SCANCODE_MINUS = 0x0C
    const val SCANCODE_EQUALS = 0x0D
    const val SCANCODE_BACKSPACE = 0x0E
    const val SCANCODE_TAB = 0x0F

    const val SCANCODE_Q = 0x10
    const val SCANCODE_W = 0x11
    const val SCANCODE_E = 0x12
    const val SCANCODE_R = 0x13
    const val SCANCODE_T = 0x14
    const val SCANCODE_Y = 0x15
    const val SCANCODE_U = 0x16
    const val SCANCODE_I = 0x17
    const val SCANCODE_O = 0x18
    const val SCANCODE_P = 0x19
    const val SCANCODE_LEFT_BRACKET = 0x1A
    const val SCANCODE_RIGHT_BRACKET = 0x1B
    const val SCANCODE_ENTER = 0x1C
    const val SCANCODE_LEFT_CTRL = 0x1D

    const val SCANCODE_A = 0x1E
    const val SCANCODE_S = 0x1F
    const val SCANCODE_D = 0x20
    const val SCANCODE_F = 0x21
    const val SCANCODE_G = 0x22
    const val SCANCODE_H = 0x23
    const val SCANCODE_J = 0x24
    const val SCANCODE_K = 0x25
    const val SCANCODE_L = 0x26
    const val SCANCODE_SEMICOLON = 0x27
    const val SCANCODE_APOSTROPHE = 0x28
    const val SCANCODE_GRAVE = 0x29
    const val SCANCODE_LEFT_SHIFT = 0x2A
    const val SCANCODE_BACKSLASH = 0x2B

    const val SCANCODE_Z = 0x2C
    const val SCANCODE_X = 0x2D
    const val SCANCODE_C = 0x2E
    const val SCANCODE_V = 0x2F
    const val SCANCODE_B = 0x30
    const val SCANCODE_N = 0x31
    const val SCANCODE_M = 0x32
    const val SCANCODE_COMMA = 0x33
    const val SCANCODE_PERIOD = 0x34
    const val SCANCODE_SLASH = 0x35
    const val SCANCODE_RIGHT_SHIFT = 0x36
    const val SCANCODE_MULTIPLY = 0x37
    const val SCANCODE_LEFT_ALT = 0x38
    const val SCANCODE_SPACE = 0x39
    const val SCANCODE_CAPS_LOCK = 0x3A

    const val SCANCODE_F1 = 0x3B
    const val SCANCODE_F2 = 0x3C
    const val SCANCODE_F3 = 0x3D
    const val SCANCODE_F4 = 0x3E
    const val SCANCODE_F5 = 0x3F
    const val SCANCODE_F6 = 0x40
    const val SCANCODE_F7 = 0x41
    const val SCANCODE_F8 = 0x42
    const val SCANCODE_F9 = 0x43
    const val SCANCODE_F10 = 0x44
    const val SCANCODE_F11 = 0x57
    const val SCANCODE_F12 = 0x58

    // Extended Scancodes (require extended flag)
    const val SCANCODE_HOME = 0x47
    const val SCANCODE_UP_ARROW = 0x48
    const val SCANCODE_PAGE_UP = 0x49
    const val SCANCODE_LEFT_ARROW = 0x4B
    const val SCANCODE_RIGHT_ARROW = 0x4D
    const val SCANCODE_END = 0x4F
    const val SCANCODE_DOWN_ARROW = 0x50
    const val SCANCODE_PAGE_DOWN = 0x51
    const val SCANCODE_INSERT = 0x52
    const val SCANCODE_DELETE = 0x53
    const val SCANCODE_LEFT_WIN = 0x5B
    const val SCANCODE_RIGHT_WIN = 0x5C
    const val SCANCODE_APPS = 0x5D

    /**
     * Returns true when [scancode] lies inside the valid Windows Scancode Set 1
     * keyboard range (0x01..0x7F). Values such as 0x00, negatives, or leaked
     * Android keycodes beyond the Set 1 range are unknown and must not be sent
     * to the remote session.
     */
    fun isKnownScancode(scancode: Int): Boolean = scancode in 0x01..0x7F

    fun getScancodeForModifierKey(key: ModifierKey): ScancodeResult {
        return when (key) {
            ModifierKey.CTRL -> ScancodeResult(SCANCODE_LEFT_CTRL, isExtended = false)
            ModifierKey.ALT -> ScancodeResult(SCANCODE_LEFT_ALT, isExtended = false)
            ModifierKey.SHIFT -> ScancodeResult(SCANCODE_LEFT_SHIFT, isExtended = false)
            ModifierKey.WIN -> ScancodeResult(SCANCODE_LEFT_WIN, isExtended = true)
            ModifierKey.ESC -> ScancodeResult(SCANCODE_ESCAPE, isExtended = false)
            ModifierKey.TAB -> ScancodeResult(SCANCODE_TAB, isExtended = false)
            ModifierKey.DEL -> ScancodeResult(SCANCODE_DELETE, isExtended = true)
            ModifierKey.INS -> ScancodeResult(SCANCODE_INSERT, isExtended = true)
            ModifierKey.HOME -> ScancodeResult(SCANCODE_HOME, isExtended = true)
            ModifierKey.END -> ScancodeResult(SCANCODE_END, isExtended = true)
            ModifierKey.PAGE_UP -> ScancodeResult(SCANCODE_PAGE_UP, isExtended = true)
            ModifierKey.PAGE_DOWN -> ScancodeResult(SCANCODE_PAGE_DOWN, isExtended = true)
            ModifierKey.F1 -> ScancodeResult(SCANCODE_F1, isExtended = false)
            ModifierKey.F2 -> ScancodeResult(SCANCODE_F2, isExtended = false)
            ModifierKey.F3 -> ScancodeResult(SCANCODE_F3, isExtended = false)
            ModifierKey.F4 -> ScancodeResult(SCANCODE_F4, isExtended = false)
            ModifierKey.F5 -> ScancodeResult(SCANCODE_F5, isExtended = false)
            ModifierKey.F6 -> ScancodeResult(SCANCODE_F6, isExtended = false)
            ModifierKey.F7 -> ScancodeResult(SCANCODE_F7, isExtended = false)
            ModifierKey.F8 -> ScancodeResult(SCANCODE_F8, isExtended = false)
            ModifierKey.F9 -> ScancodeResult(SCANCODE_F9, isExtended = false)
            ModifierKey.F10 -> ScancodeResult(SCANCODE_F10, isExtended = false)
            ModifierKey.F11 -> ScancodeResult(SCANCODE_F11, isExtended = false)
            ModifierKey.F12 -> ScancodeResult(SCANCODE_F12, isExtended = false)
            ModifierKey.ARROW_LEFT -> ScancodeResult(SCANCODE_LEFT_ARROW, isExtended = true)
            ModifierKey.ARROW_UP -> ScancodeResult(SCANCODE_UP_ARROW, isExtended = true)
            ModifierKey.ARROW_RIGHT -> ScancodeResult(SCANCODE_RIGHT_ARROW, isExtended = true)
            ModifierKey.ARROW_DOWN -> ScancodeResult(SCANCODE_DOWN_ARROW, isExtended = true)
            ModifierKey.ENTER -> ScancodeResult(SCANCODE_ENTER, isExtended = false)
            ModifierKey.BACKSPACE -> ScancodeResult(SCANCODE_BACKSPACE, isExtended = false)
            ModifierKey.SPACE -> ScancodeResult(SCANCODE_SPACE, isExtended = false)
        }
    }

    fun fromAndroidKeyCode(keyCode: Int): ScancodeResult? {
        return when (keyCode) {
            KeyEvent.KEYCODE_A -> ScancodeResult(SCANCODE_A)
            KeyEvent.KEYCODE_B -> ScancodeResult(SCANCODE_B)
            KeyEvent.KEYCODE_C -> ScancodeResult(SCANCODE_C)
            KeyEvent.KEYCODE_D -> ScancodeResult(SCANCODE_D)
            KeyEvent.KEYCODE_E -> ScancodeResult(SCANCODE_E)
            KeyEvent.KEYCODE_F -> ScancodeResult(SCANCODE_F)
            KeyEvent.KEYCODE_G -> ScancodeResult(SCANCODE_G)
            KeyEvent.KEYCODE_H -> ScancodeResult(SCANCODE_H)
            KeyEvent.KEYCODE_I -> ScancodeResult(SCANCODE_I)
            KeyEvent.KEYCODE_J -> ScancodeResult(SCANCODE_J)
            KeyEvent.KEYCODE_K -> ScancodeResult(SCANCODE_K)
            KeyEvent.KEYCODE_L -> ScancodeResult(SCANCODE_L)
            KeyEvent.KEYCODE_M -> ScancodeResult(SCANCODE_M)
            KeyEvent.KEYCODE_N -> ScancodeResult(SCANCODE_N)
            KeyEvent.KEYCODE_O -> ScancodeResult(SCANCODE_O)
            KeyEvent.KEYCODE_P -> ScancodeResult(SCANCODE_P)
            KeyEvent.KEYCODE_Q -> ScancodeResult(SCANCODE_Q)
            KeyEvent.KEYCODE_R -> ScancodeResult(SCANCODE_R)
            KeyEvent.KEYCODE_S -> ScancodeResult(SCANCODE_S)
            KeyEvent.KEYCODE_T -> ScancodeResult(SCANCODE_T)
            KeyEvent.KEYCODE_U -> ScancodeResult(SCANCODE_U)
            KeyEvent.KEYCODE_V -> ScancodeResult(SCANCODE_V)
            KeyEvent.KEYCODE_W -> ScancodeResult(SCANCODE_W)
            KeyEvent.KEYCODE_X -> ScancodeResult(SCANCODE_X)
            KeyEvent.KEYCODE_Y -> ScancodeResult(SCANCODE_Y)
            KeyEvent.KEYCODE_Z -> ScancodeResult(SCANCODE_Z)

            KeyEvent.KEYCODE_0 -> ScancodeResult(SCANCODE_0)
            KeyEvent.KEYCODE_1 -> ScancodeResult(SCANCODE_1)
            KeyEvent.KEYCODE_2 -> ScancodeResult(SCANCODE_2)
            KeyEvent.KEYCODE_3 -> ScancodeResult(SCANCODE_3)
            KeyEvent.KEYCODE_4 -> ScancodeResult(SCANCODE_4)
            KeyEvent.KEYCODE_5 -> ScancodeResult(SCANCODE_5)
            KeyEvent.KEYCODE_6 -> ScancodeResult(SCANCODE_6)
            KeyEvent.KEYCODE_7 -> ScancodeResult(SCANCODE_7)
            KeyEvent.KEYCODE_8 -> ScancodeResult(SCANCODE_8)
            KeyEvent.KEYCODE_9 -> ScancodeResult(SCANCODE_9)

            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> ScancodeResult(SCANCODE_ENTER)
            KeyEvent.KEYCODE_DEL -> ScancodeResult(SCANCODE_BACKSPACE)
            KeyEvent.KEYCODE_FORWARD_DEL -> ScancodeResult(SCANCODE_DELETE, isExtended = true)
            KeyEvent.KEYCODE_SPACE -> ScancodeResult(SCANCODE_SPACE)
            KeyEvent.KEYCODE_TAB -> ScancodeResult(SCANCODE_TAB)
            KeyEvent.KEYCODE_ESCAPE -> ScancodeResult(SCANCODE_ESCAPE)

            KeyEvent.KEYCODE_CTRL_LEFT -> ScancodeResult(SCANCODE_LEFT_CTRL)
            KeyEvent.KEYCODE_CTRL_RIGHT -> ScancodeResult(SCANCODE_LEFT_CTRL, isExtended = true)
            KeyEvent.KEYCODE_ALT_LEFT -> ScancodeResult(SCANCODE_LEFT_ALT)
            KeyEvent.KEYCODE_ALT_RIGHT -> ScancodeResult(SCANCODE_LEFT_ALT, isExtended = true)
            KeyEvent.KEYCODE_SHIFT_LEFT -> ScancodeResult(SCANCODE_LEFT_SHIFT)
            KeyEvent.KEYCODE_SHIFT_RIGHT -> ScancodeResult(SCANCODE_RIGHT_SHIFT)
            KeyEvent.KEYCODE_META_LEFT -> ScancodeResult(SCANCODE_LEFT_WIN, isExtended = true)
            KeyEvent.KEYCODE_META_RIGHT -> ScancodeResult(SCANCODE_RIGHT_WIN, isExtended = true)

            KeyEvent.KEYCODE_DPAD_UP -> ScancodeResult(SCANCODE_UP_ARROW, isExtended = true)
            KeyEvent.KEYCODE_DPAD_DOWN -> ScancodeResult(SCANCODE_DOWN_ARROW, isExtended = true)
            KeyEvent.KEYCODE_DPAD_LEFT -> ScancodeResult(SCANCODE_LEFT_ARROW, isExtended = true)
            KeyEvent.KEYCODE_DPAD_RIGHT -> ScancodeResult(SCANCODE_RIGHT_ARROW, isExtended = true)

            KeyEvent.KEYCODE_MOVE_HOME -> ScancodeResult(SCANCODE_HOME, isExtended = true)
            KeyEvent.KEYCODE_MOVE_END -> ScancodeResult(SCANCODE_END, isExtended = true)
            KeyEvent.KEYCODE_PAGE_UP -> ScancodeResult(SCANCODE_PAGE_UP, isExtended = true)
            KeyEvent.KEYCODE_PAGE_DOWN -> ScancodeResult(SCANCODE_PAGE_DOWN, isExtended = true)
            KeyEvent.KEYCODE_INSERT -> ScancodeResult(SCANCODE_INSERT, isExtended = true)

            KeyEvent.KEYCODE_F1 -> ScancodeResult(SCANCODE_F1)
            KeyEvent.KEYCODE_F2 -> ScancodeResult(SCANCODE_F2)
            KeyEvent.KEYCODE_F3 -> ScancodeResult(SCANCODE_F3)
            KeyEvent.KEYCODE_F4 -> ScancodeResult(SCANCODE_F4)
            KeyEvent.KEYCODE_F5 -> ScancodeResult(SCANCODE_F5)
            KeyEvent.KEYCODE_F6 -> ScancodeResult(SCANCODE_F6)
            KeyEvent.KEYCODE_F7 -> ScancodeResult(SCANCODE_F7)
            KeyEvent.KEYCODE_F8 -> ScancodeResult(SCANCODE_F8)
            KeyEvent.KEYCODE_F9 -> ScancodeResult(SCANCODE_F9)
            KeyEvent.KEYCODE_F10 -> ScancodeResult(SCANCODE_F10)
            KeyEvent.KEYCODE_F11 -> ScancodeResult(SCANCODE_F11)
            KeyEvent.KEYCODE_F12 -> ScancodeResult(SCANCODE_F12)

            else -> null
        }
    }

    fun fromChar(char: Char): ScancodeResult? {
        val upper = char.uppercaseChar()
        return when (upper) {
            'A' -> ScancodeResult(SCANCODE_A)
            'B' -> ScancodeResult(SCANCODE_B)
            'C' -> ScancodeResult(SCANCODE_C)
            'D' -> ScancodeResult(SCANCODE_D)
            'E' -> ScancodeResult(SCANCODE_E)
            'F' -> ScancodeResult(SCANCODE_F)
            'G' -> ScancodeResult(SCANCODE_G)
            'H' -> ScancodeResult(SCANCODE_H)
            'I' -> ScancodeResult(SCANCODE_I)
            'J' -> ScancodeResult(SCANCODE_J)
            'K' -> ScancodeResult(SCANCODE_K)
            'L' -> ScancodeResult(SCANCODE_L)
            'M' -> ScancodeResult(SCANCODE_M)
            'N' -> ScancodeResult(SCANCODE_N)
            'O' -> ScancodeResult(SCANCODE_O)
            'P' -> ScancodeResult(SCANCODE_P)
            'Q' -> ScancodeResult(SCANCODE_Q)
            'R' -> ScancodeResult(SCANCODE_R)
            'S' -> ScancodeResult(SCANCODE_S)
            'T' -> ScancodeResult(SCANCODE_T)
            'U' -> ScancodeResult(SCANCODE_U)
            'V' -> ScancodeResult(SCANCODE_V)
            'W' -> ScancodeResult(SCANCODE_W)
            'X' -> ScancodeResult(SCANCODE_X)
            'Y' -> ScancodeResult(SCANCODE_Y)
            'Z' -> ScancodeResult(SCANCODE_Z)
            '0' -> ScancodeResult(SCANCODE_0)
            '1' -> ScancodeResult(SCANCODE_1)
            '2' -> ScancodeResult(SCANCODE_2)
            '3' -> ScancodeResult(SCANCODE_3)
            '4' -> ScancodeResult(SCANCODE_4)
            '5' -> ScancodeResult(SCANCODE_5)
            '6' -> ScancodeResult(SCANCODE_6)
            '7' -> ScancodeResult(SCANCODE_7)
            '8' -> ScancodeResult(SCANCODE_8)
            '9' -> ScancodeResult(SCANCODE_9)
            ' ' -> ScancodeResult(SCANCODE_SPACE)
            '\n', '\r' -> ScancodeResult(SCANCODE_ENTER)
            '\t' -> ScancodeResult(SCANCODE_TAB)
            else -> null
        }
    }

    fun getMacroSteps(macro: MacroAction): List<MacroStep> {
        return when (macro) {
            MacroAction.CTRL_ALT_DEL -> listOf(
                MacroStep(SCANCODE_LEFT_CTRL, isExtended = false, down = true),
                MacroStep(SCANCODE_LEFT_ALT, isExtended = false, down = true),
                MacroStep(SCANCODE_DELETE, isExtended = true, down = true),
                MacroStep(SCANCODE_DELETE, isExtended = true, down = false),
                MacroStep(SCANCODE_LEFT_ALT, isExtended = false, down = false),
                MacroStep(SCANCODE_LEFT_CTRL, isExtended = false, down = false)
            )
            MacroAction.ALT_TAB -> listOf(
                MacroStep(SCANCODE_LEFT_ALT, isExtended = false, down = true),
                MacroStep(SCANCODE_TAB, isExtended = false, down = true),
                MacroStep(SCANCODE_TAB, isExtended = false, down = false),
                MacroStep(SCANCODE_LEFT_ALT, isExtended = false, down = false)
            )
            MacroAction.ALT_F4 -> listOf(
                MacroStep(SCANCODE_LEFT_ALT, isExtended = false, down = true),
                MacroStep(SCANCODE_F4, isExtended = false, down = true),
                MacroStep(SCANCODE_F4, isExtended = false, down = false),
                MacroStep(SCANCODE_LEFT_ALT, isExtended = false, down = false)
            )
            MacroAction.WIN_D -> listOf(
                MacroStep(SCANCODE_LEFT_WIN, isExtended = true, down = true),
                MacroStep(SCANCODE_D, isExtended = false, down = true),
                MacroStep(SCANCODE_D, isExtended = false, down = false),
                MacroStep(SCANCODE_LEFT_WIN, isExtended = true, down = false)
            )
            MacroAction.WIN_R -> listOf(
                MacroStep(SCANCODE_LEFT_WIN, isExtended = true, down = true),
                MacroStep(SCANCODE_R, isExtended = false, down = true),
                MacroStep(SCANCODE_R, isExtended = false, down = false),
                MacroStep(SCANCODE_LEFT_WIN, isExtended = true, down = false)
            )
            MacroAction.CTRL_C -> listOf(
                MacroStep(SCANCODE_LEFT_CTRL, isExtended = false, down = true),
                MacroStep(SCANCODE_C, isExtended = false, down = true),
                MacroStep(SCANCODE_C, isExtended = false, down = false),
                MacroStep(SCANCODE_LEFT_CTRL, isExtended = false, down = false)
            )
            MacroAction.CTRL_V -> listOf(
                MacroStep(SCANCODE_LEFT_CTRL, isExtended = false, down = true),
                MacroStep(SCANCODE_V, isExtended = false, down = true),
                MacroStep(SCANCODE_V, isExtended = false, down = false),
                MacroStep(SCANCODE_LEFT_CTRL, isExtended = false, down = false)
            )
        }
    }
}
