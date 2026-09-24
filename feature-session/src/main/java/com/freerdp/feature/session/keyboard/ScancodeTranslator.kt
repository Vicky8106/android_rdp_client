package com.freerdp.feature.session.keyboard

import android.view.KeyEvent
import com.freerdp.feature.session.modifier.MacroAction
import com.freerdp.feature.session.modifier.ModifierKey

data class ScancodeResult(
    val scancode: Int,
    val isExtended: Boolean = false,
    val vkCode: Int = 0
) {
    val flags: Int get() = if (isExtended) 0x0100 else 0x0000
    val extendedScancode: Int get() = if (isExtended) scancode or 0x0100 else scancode
}

data class MacroStep(
    val scancode: Int,
    val isExtended: Boolean,
    val down: Boolean
)

object ScancodeTranslator {

    // Extended flag mask
    const val EXTENDED_KEY_FLAG = 0x0100

    // Windows Virtual-Key (VK) Code Constants
    const val VK_BACK = 0x08
    const val VK_TAB = 0x09
    const val VK_RETURN = 0x0D
    const val VK_SHIFT = 0x10
    const val VK_CONTROL = 0x11
    const val VK_MENU = 0x12 // Alt
    const val VK_CAPITAL = 0x14 // Caps Lock
    const val VK_ESCAPE = 0x1B
    const val VK_SPACE = 0x20
    const val VK_PRIOR = 0x21 // Page Up
    const val VK_NEXT = 0x22  // Page Down
    const val VK_END = 0x23
    const val VK_HOME = 0x24
    const val VK_LEFT = 0x25
    const val VK_UP = 0x26
    const val VK_RIGHT = 0x27
    const val VK_DOWN = 0x28
    const val VK_INSERT = 0x2D
    const val VK_DELETE = 0x2E
    const val VK_0 = 0x30
    const val VK_1 = 0x31
    const val VK_2 = 0x32
    const val VK_3 = 0x33
    const val VK_4 = 0x34
    const val VK_5 = 0x35
    const val VK_6 = 0x36
    const val VK_7 = 0x37
    const val VK_8 = 0x38
    const val VK_9 = 0x39
    const val VK_A = 0x41
    const val VK_B = 0x42
    const val VK_C = 0x43
    const val VK_D = 0x44
    const val VK_E = 0x45
    const val VK_F = 0x46
    const val VK_G = 0x47
    const val VK_H = 0x48
    const val VK_I = 0x49
    const val VK_J = 0x4A
    const val VK_K = 0x4B
    const val VK_L = 0x4C
    const val VK_M = 0x4D
    const val VK_N = 0x4E
    const val VK_O = 0x4F
    const val VK_P = 0x50
    const val VK_Q = 0x51
    const val VK_R = 0x52
    const val VK_S = 0x53
    const val VK_T = 0x54
    const val VK_U = 0x55
    const val VK_V = 0x56
    const val VK_W = 0x57
    const val VK_X = 0x58
    const val VK_Y = 0x59
    const val VK_Z = 0x5A
    const val VK_LWIN = 0x5B
    const val VK_RWIN = 0x5C
    const val VK_APPS = 0x5D
    const val VK_F1 = 0x70
    const val VK_F2 = 0x71
    const val VK_F3 = 0x72
    const val VK_F4 = 0x73
    const val VK_F5 = 0x74
    const val VK_F6 = 0x75
    const val VK_F7 = 0x76
    const val VK_F8 = 0x77
    const val VK_F9 = 0x78
    const val VK_F10 = 0x79
    const val VK_F11 = 0x7A
    const val VK_F12 = 0x7B
    const val VK_LSHIFT = 0xA0
    const val VK_RSHIFT = 0xA1
    const val VK_LCONTROL = 0xA2
    const val VK_RCONTROL = 0xA3
    const val VK_LMENU = 0xA4
    const val VK_RMENU = 0xA5

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
    fun isKnownScancode(scancode: Int): Boolean = (scancode and 0xFF) in 0x01..0x7F

    fun getExtendedScancode(scancode: Int, isExtended: Boolean): Int =
        if (isExtended) scancode or EXTENDED_KEY_FLAG else scancode

    fun isExtendedScancode(codeWithFlags: Int): Boolean =
        (codeWithFlags and EXTENDED_KEY_FLAG) != 0

    fun stripExtendedFlag(codeWithFlags: Int): Int =
        codeWithFlags and 0xFF

    fun getScancodeForModifierKey(key: ModifierKey): ScancodeResult {
        return when (key) {
            ModifierKey.CTRL -> ScancodeResult(SCANCODE_LEFT_CTRL, isExtended = false, vkCode = VK_LCONTROL)
            ModifierKey.ALT -> ScancodeResult(SCANCODE_LEFT_ALT, isExtended = false, vkCode = VK_LMENU)
            ModifierKey.SHIFT -> ScancodeResult(SCANCODE_LEFT_SHIFT, isExtended = false, vkCode = VK_LSHIFT)
            ModifierKey.WIN -> ScancodeResult(SCANCODE_LEFT_WIN, isExtended = true, vkCode = VK_LWIN)
            ModifierKey.ESC -> ScancodeResult(SCANCODE_ESCAPE, isExtended = false, vkCode = VK_ESCAPE)
            ModifierKey.TAB -> ScancodeResult(SCANCODE_TAB, isExtended = false, vkCode = VK_TAB)
            ModifierKey.DEL -> ScancodeResult(SCANCODE_DELETE, isExtended = true, vkCode = VK_DELETE)
            ModifierKey.INS -> ScancodeResult(SCANCODE_INSERT, isExtended = true, vkCode = VK_INSERT)
            ModifierKey.HOME -> ScancodeResult(SCANCODE_HOME, isExtended = true, vkCode = VK_HOME)
            ModifierKey.END -> ScancodeResult(SCANCODE_END, isExtended = true, vkCode = VK_END)
            ModifierKey.PAGE_UP -> ScancodeResult(SCANCODE_PAGE_UP, isExtended = true, vkCode = VK_PRIOR)
            ModifierKey.PAGE_DOWN -> ScancodeResult(SCANCODE_PAGE_DOWN, isExtended = true, vkCode = VK_NEXT)
            ModifierKey.F1 -> ScancodeResult(SCANCODE_F1, isExtended = false, vkCode = VK_F1)
            ModifierKey.F2 -> ScancodeResult(SCANCODE_F2, isExtended = false, vkCode = VK_F2)
            ModifierKey.F3 -> ScancodeResult(SCANCODE_F3, isExtended = false, vkCode = VK_F3)
            ModifierKey.F4 -> ScancodeResult(SCANCODE_F4, isExtended = false, vkCode = VK_F4)
            ModifierKey.F5 -> ScancodeResult(SCANCODE_F5, isExtended = false, vkCode = VK_F5)
            ModifierKey.F6 -> ScancodeResult(SCANCODE_F6, isExtended = false, vkCode = VK_F6)
            ModifierKey.F7 -> ScancodeResult(SCANCODE_F7, isExtended = false, vkCode = VK_F7)
            ModifierKey.F8 -> ScancodeResult(SCANCODE_F8, isExtended = false, vkCode = VK_F8)
            ModifierKey.F9 -> ScancodeResult(SCANCODE_F9, isExtended = false, vkCode = VK_F9)
            ModifierKey.F10 -> ScancodeResult(SCANCODE_F10, isExtended = false, vkCode = VK_F10)
            ModifierKey.F11 -> ScancodeResult(SCANCODE_F11, isExtended = false, vkCode = VK_F11)
            ModifierKey.F12 -> ScancodeResult(SCANCODE_F12, isExtended = false, vkCode = VK_F12)
            ModifierKey.ARROW_LEFT -> ScancodeResult(SCANCODE_LEFT_ARROW, isExtended = true, vkCode = VK_LEFT)
            ModifierKey.ARROW_UP -> ScancodeResult(SCANCODE_UP_ARROW, isExtended = true, vkCode = VK_UP)
            ModifierKey.ARROW_RIGHT -> ScancodeResult(SCANCODE_RIGHT_ARROW, isExtended = true, vkCode = VK_RIGHT)
            ModifierKey.ARROW_DOWN -> ScancodeResult(SCANCODE_DOWN_ARROW, isExtended = true, vkCode = VK_DOWN)
            ModifierKey.ENTER -> ScancodeResult(SCANCODE_ENTER, isExtended = false, vkCode = VK_RETURN)
            ModifierKey.BACKSPACE -> ScancodeResult(SCANCODE_BACKSPACE, isExtended = false, vkCode = VK_BACK)
            ModifierKey.SPACE -> ScancodeResult(SCANCODE_SPACE, isExtended = false, vkCode = VK_SPACE)
            ModifierKey.CAPS_LOCK -> ScancodeResult(SCANCODE_CAPS_LOCK, isExtended = false, vkCode = VK_CAPITAL)
        }
    }

    fun fromAndroidKeyCode(keyCode: Int): ScancodeResult? {
        return when (keyCode) {
            KeyEvent.KEYCODE_A -> ScancodeResult(SCANCODE_A, vkCode = VK_A)
            KeyEvent.KEYCODE_B -> ScancodeResult(SCANCODE_B, vkCode = VK_B)
            KeyEvent.KEYCODE_C -> ScancodeResult(SCANCODE_C, vkCode = VK_C)
            KeyEvent.KEYCODE_D -> ScancodeResult(SCANCODE_D, vkCode = VK_D)
            KeyEvent.KEYCODE_E -> ScancodeResult(SCANCODE_E, vkCode = VK_E)
            KeyEvent.KEYCODE_F -> ScancodeResult(SCANCODE_F, vkCode = VK_F)
            KeyEvent.KEYCODE_G -> ScancodeResult(SCANCODE_G, vkCode = VK_G)
            KeyEvent.KEYCODE_H -> ScancodeResult(SCANCODE_H, vkCode = VK_H)
            KeyEvent.KEYCODE_I -> ScancodeResult(SCANCODE_I, vkCode = VK_I)
            KeyEvent.KEYCODE_J -> ScancodeResult(SCANCODE_J, vkCode = VK_J)
            KeyEvent.KEYCODE_K -> ScancodeResult(SCANCODE_K, vkCode = VK_K)
            KeyEvent.KEYCODE_L -> ScancodeResult(SCANCODE_L, vkCode = VK_L)
            KeyEvent.KEYCODE_M -> ScancodeResult(SCANCODE_M, vkCode = VK_M)
            KeyEvent.KEYCODE_N -> ScancodeResult(SCANCODE_N, vkCode = VK_N)
            KeyEvent.KEYCODE_O -> ScancodeResult(SCANCODE_O, vkCode = VK_O)
            KeyEvent.KEYCODE_P -> ScancodeResult(SCANCODE_P, vkCode = VK_P)
            KeyEvent.KEYCODE_Q -> ScancodeResult(SCANCODE_Q, vkCode = VK_Q)
            KeyEvent.KEYCODE_R -> ScancodeResult(SCANCODE_R, vkCode = VK_R)
            KeyEvent.KEYCODE_S -> ScancodeResult(SCANCODE_S, vkCode = VK_S)
            KeyEvent.KEYCODE_T -> ScancodeResult(SCANCODE_T, vkCode = VK_T)
            KeyEvent.KEYCODE_U -> ScancodeResult(SCANCODE_U, vkCode = VK_U)
            KeyEvent.KEYCODE_V -> ScancodeResult(SCANCODE_V, vkCode = VK_V)
            KeyEvent.KEYCODE_W -> ScancodeResult(SCANCODE_W, vkCode = VK_W)
            KeyEvent.KEYCODE_X -> ScancodeResult(SCANCODE_X, vkCode = VK_X)
            KeyEvent.KEYCODE_Y -> ScancodeResult(SCANCODE_Y, vkCode = VK_Y)
            KeyEvent.KEYCODE_Z -> ScancodeResult(SCANCODE_Z, vkCode = VK_Z)

            KeyEvent.KEYCODE_0 -> ScancodeResult(SCANCODE_0, vkCode = VK_0)
            KeyEvent.KEYCODE_1 -> ScancodeResult(SCANCODE_1, vkCode = VK_1)
            KeyEvent.KEYCODE_2 -> ScancodeResult(SCANCODE_2, vkCode = VK_2)
            KeyEvent.KEYCODE_3 -> ScancodeResult(SCANCODE_3, vkCode = VK_3)
            KeyEvent.KEYCODE_4 -> ScancodeResult(SCANCODE_4, vkCode = VK_4)
            KeyEvent.KEYCODE_5 -> ScancodeResult(SCANCODE_5, vkCode = VK_5)
            KeyEvent.KEYCODE_6 -> ScancodeResult(SCANCODE_6, vkCode = VK_6)
            KeyEvent.KEYCODE_7 -> ScancodeResult(SCANCODE_7, vkCode = VK_7)
            KeyEvent.KEYCODE_8 -> ScancodeResult(SCANCODE_8, vkCode = VK_8)
            KeyEvent.KEYCODE_9 -> ScancodeResult(SCANCODE_9, vkCode = VK_9)

            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> ScancodeResult(SCANCODE_ENTER, vkCode = VK_RETURN)
            KeyEvent.KEYCODE_DEL -> ScancodeResult(SCANCODE_BACKSPACE, vkCode = VK_BACK)
            KeyEvent.KEYCODE_FORWARD_DEL -> ScancodeResult(SCANCODE_DELETE, isExtended = true, vkCode = VK_DELETE)
            KeyEvent.KEYCODE_SPACE -> ScancodeResult(SCANCODE_SPACE, vkCode = VK_SPACE)
            KeyEvent.KEYCODE_TAB -> ScancodeResult(SCANCODE_TAB, vkCode = VK_TAB)
            KeyEvent.KEYCODE_ESCAPE -> ScancodeResult(SCANCODE_ESCAPE, vkCode = VK_ESCAPE)
            KeyEvent.KEYCODE_CAPS_LOCK -> ScancodeResult(SCANCODE_CAPS_LOCK, vkCode = VK_CAPITAL)

            KeyEvent.KEYCODE_CTRL_LEFT -> ScancodeResult(SCANCODE_LEFT_CTRL, vkCode = VK_LCONTROL)
            KeyEvent.KEYCODE_CTRL_RIGHT -> ScancodeResult(SCANCODE_LEFT_CTRL, isExtended = true, vkCode = VK_RCONTROL)
            KeyEvent.KEYCODE_ALT_LEFT -> ScancodeResult(SCANCODE_LEFT_ALT, vkCode = VK_LMENU)
            KeyEvent.KEYCODE_ALT_RIGHT -> ScancodeResult(SCANCODE_LEFT_ALT, isExtended = true, vkCode = VK_RMENU)
            KeyEvent.KEYCODE_SHIFT_LEFT -> ScancodeResult(SCANCODE_LEFT_SHIFT, vkCode = VK_LSHIFT)
            KeyEvent.KEYCODE_SHIFT_RIGHT -> ScancodeResult(SCANCODE_RIGHT_SHIFT, vkCode = VK_RSHIFT)
            KeyEvent.KEYCODE_META_LEFT -> ScancodeResult(SCANCODE_LEFT_WIN, isExtended = true, vkCode = VK_LWIN)
            KeyEvent.KEYCODE_META_RIGHT -> ScancodeResult(SCANCODE_RIGHT_WIN, isExtended = true, vkCode = VK_RWIN)

            KeyEvent.KEYCODE_DPAD_UP -> ScancodeResult(SCANCODE_UP_ARROW, isExtended = true, vkCode = VK_UP)
            KeyEvent.KEYCODE_DPAD_DOWN -> ScancodeResult(SCANCODE_DOWN_ARROW, isExtended = true, vkCode = VK_DOWN)
            KeyEvent.KEYCODE_DPAD_LEFT -> ScancodeResult(SCANCODE_LEFT_ARROW, isExtended = true, vkCode = VK_LEFT)
            KeyEvent.KEYCODE_DPAD_RIGHT -> ScancodeResult(SCANCODE_RIGHT_ARROW, isExtended = true, vkCode = VK_RIGHT)

            KeyEvent.KEYCODE_MOVE_HOME -> ScancodeResult(SCANCODE_HOME, isExtended = true, vkCode = VK_HOME)
            KeyEvent.KEYCODE_MOVE_END -> ScancodeResult(SCANCODE_END, isExtended = true, vkCode = VK_END)
            KeyEvent.KEYCODE_PAGE_UP -> ScancodeResult(SCANCODE_PAGE_UP, isExtended = true, vkCode = VK_PRIOR)
            KeyEvent.KEYCODE_PAGE_DOWN -> ScancodeResult(SCANCODE_PAGE_DOWN, isExtended = true, vkCode = VK_NEXT)
            KeyEvent.KEYCODE_INSERT -> ScancodeResult(SCANCODE_INSERT, isExtended = true, vkCode = VK_INSERT)

            KeyEvent.KEYCODE_F1 -> ScancodeResult(SCANCODE_F1, vkCode = VK_F1)
            KeyEvent.KEYCODE_F2 -> ScancodeResult(SCANCODE_F2, vkCode = VK_F2)
            KeyEvent.KEYCODE_F3 -> ScancodeResult(SCANCODE_F3, vkCode = VK_F3)
            KeyEvent.KEYCODE_F4 -> ScancodeResult(SCANCODE_F4, vkCode = VK_F4)
            KeyEvent.KEYCODE_F5 -> ScancodeResult(SCANCODE_F5, vkCode = VK_F5)
            KeyEvent.KEYCODE_F6 -> ScancodeResult(SCANCODE_F6, vkCode = VK_F6)
            KeyEvent.KEYCODE_F7 -> ScancodeResult(SCANCODE_F7, vkCode = VK_F7)
            KeyEvent.KEYCODE_F8 -> ScancodeResult(SCANCODE_F8, vkCode = VK_F8)
            KeyEvent.KEYCODE_F9 -> ScancodeResult(SCANCODE_F9, vkCode = VK_F9)
            KeyEvent.KEYCODE_F10 -> ScancodeResult(SCANCODE_F10, vkCode = VK_F10)
            KeyEvent.KEYCODE_F11 -> ScancodeResult(SCANCODE_F11, vkCode = VK_F11)
            KeyEvent.KEYCODE_F12 -> ScancodeResult(SCANCODE_F12, vkCode = VK_F12)

            else -> null
        }
    }

    fun fromChar(char: Char): ScancodeResult? {
        val upper = char.uppercaseChar()
        return when (upper) {
            'A' -> ScancodeResult(SCANCODE_A, vkCode = VK_A)
            'B' -> ScancodeResult(SCANCODE_B, vkCode = VK_B)
            'C' -> ScancodeResult(SCANCODE_C, vkCode = VK_C)
            'D' -> ScancodeResult(SCANCODE_D, vkCode = VK_D)
            'E' -> ScancodeResult(SCANCODE_E, vkCode = VK_E)
            'F' -> ScancodeResult(SCANCODE_F, vkCode = VK_F)
            'G' -> ScancodeResult(SCANCODE_G, vkCode = VK_G)
            'H' -> ScancodeResult(SCANCODE_H, vkCode = VK_H)
            'I' -> ScancodeResult(SCANCODE_I, vkCode = VK_I)
            'J' -> ScancodeResult(SCANCODE_J, vkCode = VK_J)
            'K' -> ScancodeResult(SCANCODE_K, vkCode = VK_K)
            'L' -> ScancodeResult(SCANCODE_L, vkCode = VK_L)
            'M' -> ScancodeResult(SCANCODE_M, vkCode = VK_M)
            'N' -> ScancodeResult(SCANCODE_N, vkCode = VK_N)
            'O' -> ScancodeResult(SCANCODE_O, vkCode = VK_O)
            'P' -> ScancodeResult(SCANCODE_P, vkCode = VK_P)
            'Q' -> ScancodeResult(SCANCODE_Q, vkCode = VK_Q)
            'R' -> ScancodeResult(SCANCODE_R, vkCode = VK_R)
            'S' -> ScancodeResult(SCANCODE_S, vkCode = VK_S)
            'T' -> ScancodeResult(SCANCODE_T, vkCode = VK_T)
            'U' -> ScancodeResult(SCANCODE_U, vkCode = VK_U)
            'V' -> ScancodeResult(SCANCODE_V, vkCode = VK_V)
            'W' -> ScancodeResult(SCANCODE_W, vkCode = VK_W)
            'X' -> ScancodeResult(SCANCODE_X, vkCode = VK_X)
            'Y' -> ScancodeResult(SCANCODE_Y, vkCode = VK_Y)
            'Z' -> ScancodeResult(SCANCODE_Z, vkCode = VK_Z)
            '0' -> ScancodeResult(SCANCODE_0, vkCode = VK_0)
            '1' -> ScancodeResult(SCANCODE_1, vkCode = VK_1)
            '2' -> ScancodeResult(SCANCODE_2, vkCode = VK_2)
            '3' -> ScancodeResult(SCANCODE_3, vkCode = VK_3)
            '4' -> ScancodeResult(SCANCODE_4, vkCode = VK_4)
            '5' -> ScancodeResult(SCANCODE_5, vkCode = VK_5)
            '6' -> ScancodeResult(SCANCODE_6, vkCode = VK_6)
            '7' -> ScancodeResult(SCANCODE_7, vkCode = VK_7)
            '8' -> ScancodeResult(SCANCODE_8, vkCode = VK_8)
            '9' -> ScancodeResult(SCANCODE_9, vkCode = VK_9)
            ' ' -> ScancodeResult(SCANCODE_SPACE, vkCode = VK_SPACE)
            '\n', '\r' -> ScancodeResult(SCANCODE_ENTER, vkCode = VK_RETURN)
            '\t' -> ScancodeResult(SCANCODE_TAB, vkCode = VK_TAB)
            else -> null
        }
    }

    fun toWindowsVkCode(androidKeyCode: Int): Int? {
        return fromAndroidKeyCode(androidKeyCode)?.vkCode
    }

    fun fromWindowsVkCode(vkCode: Int): ScancodeResult? {
        val baseVk = vkCode and 0xFF
        val isExtended = (vkCode and EXTENDED_KEY_FLAG) != 0
        return when (baseVk) {
            VK_A -> ScancodeResult(SCANCODE_A, isExtended = isExtended, vkCode = VK_A)
            VK_B -> ScancodeResult(SCANCODE_B, isExtended = isExtended, vkCode = VK_B)
            VK_C -> ScancodeResult(SCANCODE_C, isExtended = isExtended, vkCode = VK_C)
            VK_D -> ScancodeResult(SCANCODE_D, isExtended = isExtended, vkCode = VK_D)
            VK_E -> ScancodeResult(SCANCODE_E, isExtended = isExtended, vkCode = VK_E)
            VK_F -> ScancodeResult(SCANCODE_F, isExtended = isExtended, vkCode = VK_F)
            VK_G -> ScancodeResult(SCANCODE_G, isExtended = isExtended, vkCode = VK_G)
            VK_H -> ScancodeResult(SCANCODE_H, isExtended = isExtended, vkCode = VK_H)
            VK_I -> ScancodeResult(SCANCODE_I, isExtended = isExtended, vkCode = VK_I)
            VK_J -> ScancodeResult(SCANCODE_J, isExtended = isExtended, vkCode = VK_J)
            VK_K -> ScancodeResult(SCANCODE_K, isExtended = isExtended, vkCode = VK_K)
            VK_L -> ScancodeResult(SCANCODE_L, isExtended = isExtended, vkCode = VK_L)
            VK_M -> ScancodeResult(SCANCODE_M, isExtended = isExtended, vkCode = VK_M)
            VK_N -> ScancodeResult(SCANCODE_N, isExtended = isExtended, vkCode = VK_N)
            VK_O -> ScancodeResult(SCANCODE_O, isExtended = isExtended, vkCode = VK_O)
            VK_P -> ScancodeResult(SCANCODE_P, isExtended = isExtended, vkCode = VK_P)
            VK_Q -> ScancodeResult(SCANCODE_Q, isExtended = isExtended, vkCode = VK_Q)
            VK_R -> ScancodeResult(SCANCODE_R, isExtended = isExtended, vkCode = VK_R)
            VK_S -> ScancodeResult(SCANCODE_S, isExtended = isExtended, vkCode = VK_S)
            VK_T -> ScancodeResult(SCANCODE_T, isExtended = isExtended, vkCode = VK_T)
            VK_U -> ScancodeResult(SCANCODE_U, isExtended = isExtended, vkCode = VK_U)
            VK_V -> ScancodeResult(SCANCODE_V, isExtended = isExtended, vkCode = VK_V)
            VK_W -> ScancodeResult(SCANCODE_W, isExtended = isExtended, vkCode = VK_W)
            VK_X -> ScancodeResult(SCANCODE_X, isExtended = isExtended, vkCode = VK_X)
            VK_Y -> ScancodeResult(SCANCODE_Y, isExtended = isExtended, vkCode = VK_Y)
            VK_Z -> ScancodeResult(SCANCODE_Z, isExtended = isExtended, vkCode = VK_Z)
            VK_0 -> ScancodeResult(SCANCODE_0, isExtended = isExtended, vkCode = VK_0)
            VK_1 -> ScancodeResult(SCANCODE_1, isExtended = isExtended, vkCode = VK_1)
            VK_2 -> ScancodeResult(SCANCODE_2, isExtended = isExtended, vkCode = VK_2)
            VK_3 -> ScancodeResult(SCANCODE_3, isExtended = isExtended, vkCode = VK_3)
            VK_4 -> ScancodeResult(SCANCODE_4, isExtended = isExtended, vkCode = VK_4)
            VK_5 -> ScancodeResult(SCANCODE_5, isExtended = isExtended, vkCode = VK_5)
            VK_6 -> ScancodeResult(SCANCODE_6, isExtended = isExtended, vkCode = VK_6)
            VK_7 -> ScancodeResult(SCANCODE_7, isExtended = isExtended, vkCode = VK_7)
            VK_8 -> ScancodeResult(SCANCODE_8, isExtended = isExtended, vkCode = VK_8)
            VK_9 -> ScancodeResult(SCANCODE_9, isExtended = isExtended, vkCode = VK_9)
            VK_BACK -> ScancodeResult(SCANCODE_BACKSPACE, isExtended = false, vkCode = VK_BACK)
            VK_TAB -> ScancodeResult(SCANCODE_TAB, isExtended = false, vkCode = VK_TAB)
            VK_RETURN -> ScancodeResult(SCANCODE_ENTER, isExtended = isExtended, vkCode = VK_RETURN)
            VK_ESCAPE -> ScancodeResult(SCANCODE_ESCAPE, isExtended = false, vkCode = VK_ESCAPE)
            VK_SPACE -> ScancodeResult(SCANCODE_SPACE, isExtended = false, vkCode = VK_SPACE)
            VK_PRIOR -> ScancodeResult(SCANCODE_PAGE_UP, isExtended = true, vkCode = VK_PRIOR)
            VK_NEXT -> ScancodeResult(SCANCODE_PAGE_DOWN, isExtended = true, vkCode = VK_NEXT)
            VK_END -> ScancodeResult(SCANCODE_END, isExtended = true, vkCode = VK_END)
            VK_HOME -> ScancodeResult(SCANCODE_HOME, isExtended = true, vkCode = VK_HOME)
            VK_LEFT -> ScancodeResult(SCANCODE_LEFT_ARROW, isExtended = true, vkCode = VK_LEFT)
            VK_UP -> ScancodeResult(SCANCODE_UP_ARROW, isExtended = true, vkCode = VK_UP)
            VK_RIGHT -> ScancodeResult(SCANCODE_RIGHT_ARROW, isExtended = true, vkCode = VK_RIGHT)
            VK_DOWN -> ScancodeResult(SCANCODE_DOWN_ARROW, isExtended = true, vkCode = VK_DOWN)
            VK_INSERT -> ScancodeResult(SCANCODE_INSERT, isExtended = true, vkCode = VK_INSERT)
            VK_DELETE -> ScancodeResult(SCANCODE_DELETE, isExtended = true, vkCode = VK_DELETE)
            VK_LWIN -> ScancodeResult(SCANCODE_LEFT_WIN, isExtended = true, vkCode = VK_LWIN)
            VK_RWIN -> ScancodeResult(SCANCODE_RIGHT_WIN, isExtended = true, vkCode = VK_RWIN)
            VK_APPS -> ScancodeResult(SCANCODE_APPS, isExtended = true, vkCode = VK_APPS)
            VK_CAPITAL -> ScancodeResult(SCANCODE_CAPS_LOCK, isExtended = false, vkCode = VK_CAPITAL)
            VK_LCONTROL, VK_CONTROL -> ScancodeResult(SCANCODE_LEFT_CTRL, isExtended = isExtended, vkCode = VK_LCONTROL)
            VK_RCONTROL -> ScancodeResult(SCANCODE_LEFT_CTRL, isExtended = true, vkCode = VK_RCONTROL)
            VK_LMENU, VK_MENU -> ScancodeResult(SCANCODE_LEFT_ALT, isExtended = isExtended, vkCode = VK_LMENU)
            VK_RMENU -> ScancodeResult(SCANCODE_LEFT_ALT, isExtended = true, vkCode = VK_RMENU)
            VK_LSHIFT, VK_SHIFT -> ScancodeResult(SCANCODE_LEFT_SHIFT, isExtended = false, vkCode = VK_LSHIFT)
            VK_RSHIFT -> ScancodeResult(SCANCODE_RIGHT_SHIFT, isExtended = false, vkCode = VK_RSHIFT)
            VK_F1 -> ScancodeResult(SCANCODE_F1, isExtended = false, vkCode = VK_F1)
            VK_F2 -> ScancodeResult(SCANCODE_F2, isExtended = false, vkCode = VK_F2)
            VK_F3 -> ScancodeResult(SCANCODE_F3, isExtended = false, vkCode = VK_F3)
            VK_F4 -> ScancodeResult(SCANCODE_F4, isExtended = false, vkCode = VK_F4)
            VK_F5 -> ScancodeResult(SCANCODE_F5, isExtended = false, vkCode = VK_F5)
            VK_F6 -> ScancodeResult(SCANCODE_F6, isExtended = false, vkCode = VK_F6)
            VK_F7 -> ScancodeResult(SCANCODE_F7, isExtended = false, vkCode = VK_F7)
            VK_F8 -> ScancodeResult(SCANCODE_F8, isExtended = false, vkCode = VK_F8)
            VK_F9 -> ScancodeResult(SCANCODE_F9, isExtended = false, vkCode = VK_F9)
            VK_F10 -> ScancodeResult(SCANCODE_F10, isExtended = false, vkCode = VK_F10)
            VK_F11 -> ScancodeResult(SCANCODE_F11, isExtended = false, vkCode = VK_F11)
            VK_F12 -> ScancodeResult(SCANCODE_F12, isExtended = false, vkCode = VK_F12)
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
