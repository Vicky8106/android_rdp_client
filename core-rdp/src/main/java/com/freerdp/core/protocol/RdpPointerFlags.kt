package com.freerdp.core.protocol

object RdpPointerFlags {
    // MS-RDPBCGR §2.2.8.1.1.3.1.1 Pointer Event (TS_POINTER_EVENT)
    const val PTR_FLAGS_HWHEEL         = 0x0400 // Horizontal scroll
    const val PTR_FLAGS_WHEEL          = 0x0200 // Vertical scroll
    const val PTR_FLAGS_WHEEL_NEGATIVE = 0x0100 // Negative wheel rotation direction (down/left)
    const val PTR_FLAGS_MOVE           = 0x0800 // Pointer movement
    const val PTR_FLAGS_DOWN           = 0x8000 // Button pressed down (if omitted: button released)
    const val PTR_FLAGS_BUTTON1        = 0x1000 // Left mouse button
    const val PTR_FLAGS_BUTTON2        = 0x2000 // Right mouse button
    const val PTR_FLAGS_BUTTON3        = 0x4000 // Middle mouse button
    const val WHEEL_ROTATION_MASK      = 0x01FF // Mask for wheel rotation delta
    const val WHEEL_STEP_DEFAULT       = 0x0078 // Standard Windows wheel delta: 120 units

    // Pre-computed common event masks
    const val MOVE = PTR_FLAGS_MOVE
    const val LEFT_BUTTON_DOWN = PTR_FLAGS_BUTTON1 or PTR_FLAGS_DOWN
    const val LEFT_BUTTON_UP = PTR_FLAGS_BUTTON1
    const val RIGHT_BUTTON_DOWN = PTR_FLAGS_BUTTON2 or PTR_FLAGS_DOWN
    const val RIGHT_BUTTON_UP = PTR_FLAGS_BUTTON2
    const val MIDDLE_BUTTON_DOWN = PTR_FLAGS_BUTTON3 or PTR_FLAGS_DOWN
    const val MIDDLE_BUTTON_UP = PTR_FLAGS_BUTTON3

    // Scroll helpers
    const val SCROLL_UP = PTR_FLAGS_WHEEL or WHEEL_STEP_DEFAULT
    const val SCROLL_DOWN = PTR_FLAGS_WHEEL or PTR_FLAGS_WHEEL_NEGATIVE or WHEEL_STEP_DEFAULT
    const val SCROLL_LEFT = PTR_FLAGS_HWHEEL or PTR_FLAGS_WHEEL_NEGATIVE or WHEEL_STEP_DEFAULT
    const val SCROLL_RIGHT = PTR_FLAGS_HWHEEL or WHEEL_STEP_DEFAULT

    fun isButtonDown(flags: Int): Boolean = (flags and PTR_FLAGS_DOWN) != 0
    fun isMove(flags: Int): Boolean = (flags and PTR_FLAGS_MOVE) != 0
    fun isButton1(flags: Int): Boolean = (flags and PTR_FLAGS_BUTTON1) != 0
    fun isButton2(flags: Int): Boolean = (flags and PTR_FLAGS_BUTTON2) != 0
    fun isButton3(flags: Int): Boolean = (flags and PTR_FLAGS_BUTTON3) != 0
    fun isWheel(flags: Int): Boolean = (flags and PTR_FLAGS_WHEEL) != 0
    fun isHWheel(flags: Int): Boolean = (flags and PTR_FLAGS_HWHEEL) != 0
    fun isWheelNegative(flags: Int): Boolean = (flags and PTR_FLAGS_WHEEL_NEGATIVE) != 0
}
