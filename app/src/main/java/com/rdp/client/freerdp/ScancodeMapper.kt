package com.rdp.client.freerdp

import android.view.KeyEvent

/**
 * Bidirectional mapper between Android [KeyEvent] keycodes and IBM PC AT 8042 scancodes.
 */
object ScancodeMapper {

    private val forwardMap = HashMap<Int, RdpScancode>()
    private val reverseMap = HashMap<Pair<Int, Boolean>, Int>()

    init {
        fun map(androidKeyCode: Int, scancode: Int, isExtended: Boolean = false) {
            val rdpScancode = RdpScancode(scancode, isExtended)
            forwardMap[androidKeyCode] = rdpScancode
            reverseMap[Pair(scancode, isExtended)] = androidKeyCode
        }

        // Letters (A-Z)
        map(KeyEvent.KEYCODE_A, 0x1E)
        map(KeyEvent.KEYCODE_B, 0x30)
        map(KeyEvent.KEYCODE_C, 0x2E)
        map(KeyEvent.KEYCODE_D, 0x20)
        map(KeyEvent.KEYCODE_E, 0x12)
        map(KeyEvent.KEYCODE_F, 0x21)
        map(KeyEvent.KEYCODE_G, 0x22)
        map(KeyEvent.KEYCODE_H, 0x23)
        map(KeyEvent.KEYCODE_I, 0x17)
        map(KeyEvent.KEYCODE_J, 0x24)
        map(KeyEvent.KEYCODE_K, 0x25)
        map(KeyEvent.KEYCODE_L, 0x26)
        map(KeyEvent.KEYCODE_M, 0x32)
        map(KeyEvent.KEYCODE_N, 0x31)
        map(KeyEvent.KEYCODE_O, 0x18)
        map(KeyEvent.KEYCODE_P, 0x19)
        map(KeyEvent.KEYCODE_Q, 0x10)
        map(KeyEvent.KEYCODE_R, 0x13)
        map(KeyEvent.KEYCODE_S, 0x1F)
        map(KeyEvent.KEYCODE_T, 0x14)
        map(KeyEvent.KEYCODE_U, 0x16)
        map(KeyEvent.KEYCODE_V, 0x2F)
        map(KeyEvent.KEYCODE_W, 0x11)
        map(KeyEvent.KEYCODE_X, 0x2D)
        map(KeyEvent.KEYCODE_Y, 0x15)
        map(KeyEvent.KEYCODE_Z, 0x2C)

        // Numbers (0-9)
        map(KeyEvent.KEYCODE_1, 0x02)
        map(KeyEvent.KEYCODE_2, 0x03)
        map(KeyEvent.KEYCODE_3, 0x04)
        map(KeyEvent.KEYCODE_4, 0x05)
        map(KeyEvent.KEYCODE_5, 0x06)
        map(KeyEvent.KEYCODE_6, 0x07)
        map(KeyEvent.KEYCODE_7, 0x08)
        map(KeyEvent.KEYCODE_8, 0x09)
        map(KeyEvent.KEYCODE_9, 0x0A)
        map(KeyEvent.KEYCODE_0, 0x0B)

        // Function Keys (F1-F12)
        map(KeyEvent.KEYCODE_F1, 0x3B)
        map(KeyEvent.KEYCODE_F2, 0x3C)
        map(KeyEvent.KEYCODE_F3, 0x3D)
        map(KeyEvent.KEYCODE_F4, 0x3E)
        map(KeyEvent.KEYCODE_F5, 0x3F)
        map(KeyEvent.KEYCODE_F6, 0x40)
        map(KeyEvent.KEYCODE_F7, 0x41)
        map(KeyEvent.KEYCODE_F8, 0x42)
        map(KeyEvent.KEYCODE_F9, 0x43)
        map(KeyEvent.KEYCODE_F10, 0x44)
        map(KeyEvent.KEYCODE_F11, 0x57)
        map(KeyEvent.KEYCODE_F12, 0x58)

        // Editing & Standard Keys
        map(KeyEvent.KEYCODE_ESCAPE, 0x01)
        map(KeyEvent.KEYCODE_TAB, 0x0F)
        map(KeyEvent.KEYCODE_DEL, 0x0E) // Backspace
        map(KeyEvent.KEYCODE_ENTER, 0x1C)
        map(KeyEvent.KEYCODE_SPACE, 0x39)
        map(KeyEvent.KEYCODE_FORWARD_DEL, 0x53, isExtended = true)
        map(KeyEvent.KEYCODE_INSERT, 0x52, isExtended = true)

        // Navigation Keys (Extended 0xE0)
        map(KeyEvent.KEYCODE_DPAD_UP, 0x48, isExtended = true)
        map(KeyEvent.KEYCODE_DPAD_DOWN, 0x50, isExtended = true)
        map(KeyEvent.KEYCODE_DPAD_LEFT, 0x4B, isExtended = true)
        map(KeyEvent.KEYCODE_DPAD_RIGHT, 0x4D, isExtended = true)
        map(KeyEvent.KEYCODE_MOVE_HOME, 0x47, isExtended = true)
        map(KeyEvent.KEYCODE_MOVE_END, 0x4F, isExtended = true)
        map(KeyEvent.KEYCODE_PAGE_UP, 0x49, isExtended = true)
        map(KeyEvent.KEYCODE_PAGE_DOWN, 0x51, isExtended = true)

        // Modifiers & System Keys
        map(KeyEvent.KEYCODE_META_LEFT, 0x5B, isExtended = true) // Windows / Super
        map(KeyEvent.KEYCODE_META_RIGHT, 0x5C, isExtended = true)
        map(KeyEvent.KEYCODE_MENU, 0x5D, isExtended = true) // Apps menu
        map(KeyEvent.KEYCODE_CTRL_LEFT, 0x1D, isExtended = false)
        map(KeyEvent.KEYCODE_CTRL_RIGHT, 0x1D, isExtended = true)
        map(KeyEvent.KEYCODE_ALT_LEFT, 0x38, isExtended = false)
        map(KeyEvent.KEYCODE_ALT_RIGHT, 0x38, isExtended = true)
        map(KeyEvent.KEYCODE_SHIFT_LEFT, 0x2A, isExtended = false)
        map(KeyEvent.KEYCODE_SHIFT_RIGHT, 0x36, isExtended = false)
        map(KeyEvent.KEYCODE_CAPS_LOCK, 0x3A, isExtended = false)
        map(KeyEvent.KEYCODE_SCROLL_LOCK, 0x46, isExtended = false)
        map(KeyEvent.KEYCODE_NUM_LOCK, 0x45, isExtended = true)

        // Numeric Keypad Keys
        map(KeyEvent.KEYCODE_NUMPAD_0, 0x52, isExtended = false)
        map(KeyEvent.KEYCODE_NUMPAD_1, 0x4F, isExtended = false)
        map(KeyEvent.KEYCODE_NUMPAD_2, 0x50, isExtended = false)
        map(KeyEvent.KEYCODE_NUMPAD_3, 0x51, isExtended = false)
        map(KeyEvent.KEYCODE_NUMPAD_4, 0x4B, isExtended = false)
        map(KeyEvent.KEYCODE_NUMPAD_5, 0x4C, isExtended = false)
        map(KeyEvent.KEYCODE_NUMPAD_6, 0x4D, isExtended = false)
        map(KeyEvent.KEYCODE_NUMPAD_7, 0x47, isExtended = false)
        map(KeyEvent.KEYCODE_NUMPAD_8, 0x48, isExtended = false)
        map(KeyEvent.KEYCODE_NUMPAD_9, 0x49, isExtended = false)
        map(KeyEvent.KEYCODE_NUMPAD_DIVIDE, 0x35, isExtended = true)
        map(KeyEvent.KEYCODE_NUMPAD_MULTIPLY, 0x37, isExtended = false)
        map(KeyEvent.KEYCODE_NUMPAD_SUBTRACT, 0x4A, isExtended = false)
        map(KeyEvent.KEYCODE_NUMPAD_ADD, 0x4E, isExtended = false)
        map(KeyEvent.KEYCODE_NUMPAD_DOT, 0x53, isExtended = false)
        map(KeyEvent.KEYCODE_NUMPAD_ENTER, 0x1C, isExtended = true)
        map(KeyEvent.KEYCODE_NUMPAD_EQUALS, 0x59, isExtended = false)

        // Punctuation & OEM Keys
        map(KeyEvent.KEYCODE_MINUS, 0x0C)
        map(KeyEvent.KEYCODE_EQUALS, 0x0D)
        map(KeyEvent.KEYCODE_LEFT_BRACKET, 0x1A)
        map(KeyEvent.KEYCODE_RIGHT_BRACKET, 0x1B)
        map(KeyEvent.KEYCODE_BACKSLASH, 0x2B)
        map(KeyEvent.KEYCODE_SEMICOLON, 0x27)
        map(KeyEvent.KEYCODE_APOSTROPHE, 0x28)
        map(KeyEvent.KEYCODE_GRAVE, 0x29)
        map(KeyEvent.KEYCODE_COMMA, 0x33)
        map(KeyEvent.KEYCODE_PERIOD, 0x34)
        map(KeyEvent.KEYCODE_SLASH, 0x35)
    }

    /**
     * Translates an Android [KeyEvent] keycode to an [RdpScancode].
     * Returns null if no hardware scancode mapping exists.
     */
    fun toScancode(androidKeyCode: Int): RdpScancode? {
        return forwardMap[androidKeyCode]
    }

    /**
     * Reverse lookup: Translates an IBM PC AT 8042 scancode and extended flag back
     * to the corresponding Android [KeyEvent] keycode.
     */
    fun toAndroidKeyCode(scancode: Int, isExtended: Boolean = false): Int? {
        return reverseMap[Pair(scancode, isExtended)]
    }

    /**
     * Returns true if the keycode represents a modifier key (Ctrl, Alt, Shift, Meta).
     */
    fun isModifierKey(androidKeyCode: Int): Boolean {
        return when (androidKeyCode) {
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT,
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT,
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT,
            KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT -> true
            else -> false
        }
    }
}
