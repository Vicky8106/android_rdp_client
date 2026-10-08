package com.rdp.client.freerdp

/**
 * Pure encoder functions for RDP pointer flags (PTRFLAGS) per MS-RDPBCGR 2.2.8.1.1.3.1.
 */
object RdpPointerFlags {

    const val PTRFLAGS_MOVE = 0x0800
    const val PTRFLAGS_DOWN = 0x8000
    const val PTRFLAGS_BUTTON1 = 0x1000 // Left button
    const val PTRFLAGS_BUTTON2 = 0x2000 // Right button
    const val PTRFLAGS_BUTTON3 = 0x4000 // Middle button
    const val PTRFLAGS_WHEEL = 0x0200
    const val PTRFLAGS_WHEEL_NEGATIVE = 0x0100
    const val PTRFLAGS_HWHEEL = 0x0400
    const val WHEEL_DELTA = 0x0078 // 120 decimal

    enum class Button {
        LEFT,
        RIGHT,
        MIDDLE
    }

    enum class ScrollDirection {
        UP,
        DOWN,
        LEFT,
        RIGHT
    }

    /**
     * Encodes pointer movement flag.
     */
    fun encodeMove(): Int = PTRFLAGS_MOVE

    /**
     * Encodes button press (down) flag.
     */
    fun encodeButtonDown(button: Button): Int {
        val buttonFlag = when (button) {
            Button.LEFT -> PTRFLAGS_BUTTON1
            Button.RIGHT -> PTRFLAGS_BUTTON2
            Button.MIDDLE -> PTRFLAGS_BUTTON3
        }
        return buttonFlag or PTRFLAGS_DOWN
    }

    /**
     * Encodes button release (up) flag.
     */
    fun encodeButtonUp(button: Button): Int {
        return when (button) {
            Button.LEFT -> PTRFLAGS_BUTTON1
            Button.RIGHT -> PTRFLAGS_BUTTON2
            Button.MIDDLE -> PTRFLAGS_BUTTON3
        }
    }

    /**
     * Encodes vertical scroll wheel event.
     * [notches]: Positive integer number of scroll notches (default 1).
     * Multi-notch deltas are clamped to prevent 8-bit wrap-around truncation.
     */
    fun encodeVerticalScroll(direction: ScrollDirection, notches: Int = 1): Int {
        val clampedNotches = notches.coerceIn(0, 2)
        val delta = WHEEL_DELTA * clampedNotches
        return if (direction == ScrollDirection.UP) {
            PTRFLAGS_WHEEL or (delta and 0xFF)
        } else {
            PTRFLAGS_WHEEL or PTRFLAGS_WHEEL_NEGATIVE or ((-delta) and 0xFF)
        }
    }

    /**
     * Encodes horizontal scroll wheel event.
     * Multi-notch deltas are clamped to prevent 8-bit wrap-around truncation.
     */
    fun encodeHorizontalScroll(direction: ScrollDirection, notches: Int = 1): Int {
        val clampedNotches = notches.coerceIn(0, 2)
        val delta = WHEEL_DELTA * clampedNotches
        return if (direction == ScrollDirection.RIGHT) {
            PTRFLAGS_HWHEEL or (delta and 0xFF)
        } else {
            PTRFLAGS_HWHEEL or PTRFLAGS_WHEEL_NEGATIVE or ((-delta) and 0xFF)
        }
    }

    /**
     * Encodes a drag move event with a held button.
     */
    fun encodeDragMove(button: Button): Int {
        return PTRFLAGS_MOVE or encodeButtonDown(button)
    }
}
