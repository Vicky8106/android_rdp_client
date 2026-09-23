package com.freerdp.core

import com.freerdp.core.protocol.RdpPointerFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RdpPointerFlagsTest {

    @Test
    fun testMsRdpbcgrConstantsMatchSpecification() {
        assertEquals(0x0400, RdpPointerFlags.PTR_FLAGS_HWHEEL)
        assertEquals(0x0200, RdpPointerFlags.PTR_FLAGS_WHEEL)
        assertEquals(0x0100, RdpPointerFlags.PTR_FLAGS_WHEEL_NEGATIVE)
        assertEquals(0x0800, RdpPointerFlags.PTR_FLAGS_MOVE)
        assertEquals(0x8000, RdpPointerFlags.PTR_FLAGS_DOWN)
        assertEquals(0x1000, RdpPointerFlags.PTR_FLAGS_BUTTON1)
        assertEquals(0x2000, RdpPointerFlags.PTR_FLAGS_BUTTON2)
        assertEquals(0x4000, RdpPointerFlags.PTR_FLAGS_BUTTON3)
        assertEquals(0x01FF, RdpPointerFlags.WHEEL_ROTATION_MASK)
        assertEquals(0x0078, RdpPointerFlags.WHEEL_STEP_DEFAULT)
    }

    @Test
    fun testPrecomputedScrollFlags() {
        // Vertical Scroll Up: WHEEL (0x0200) | 120 (0x0078) = 0x0278
        assertEquals(0x0278, RdpPointerFlags.SCROLL_UP)

        // Vertical Scroll Down: WHEEL (0x0200) | NEGATIVE (0x0100) | 120 (0x0078) = 0x0378
        assertEquals(0x0378, RdpPointerFlags.SCROLL_DOWN)

        // Horizontal Scroll Left: HWHEEL (0x0400) | NEGATIVE (0x0100) | 120 (0x0078) = 0x0578
        assertEquals(0x0578, RdpPointerFlags.SCROLL_LEFT)

        // Horizontal Scroll Right: HWHEEL (0x0400) | 120 (0x0078) = 0x0478
        assertEquals(0x0478, RdpPointerFlags.SCROLL_RIGHT)
    }

    @Test
    fun testPrecomputedButtonFlags() {
        assertEquals(0x9000, RdpPointerFlags.LEFT_BUTTON_DOWN)
        assertEquals(0x1000, RdpPointerFlags.LEFT_BUTTON_UP)
        assertEquals(0xA000, RdpPointerFlags.RIGHT_BUTTON_DOWN)
        assertEquals(0x2000, RdpPointerFlags.RIGHT_BUTTON_UP)
        assertEquals(0xC000, RdpPointerFlags.MIDDLE_BUTTON_DOWN)
        assertEquals(0x4000, RdpPointerFlags.MIDDLE_BUTTON_UP)
    }

    @Test
    fun testHelperPredicates() {
        val lmbDown = RdpPointerFlags.LEFT_BUTTON_DOWN
        assertTrue(RdpPointerFlags.isButtonDown(lmbDown))
        assertTrue(RdpPointerFlags.isButton1(lmbDown))
        assertFalse(RdpPointerFlags.isButton2(lmbDown))
        assertFalse(RdpPointerFlags.isMove(lmbDown))

        val moveFlags = RdpPointerFlags.PTR_FLAGS_MOVE or RdpPointerFlags.PTR_FLAGS_BUTTON1 or RdpPointerFlags.PTR_FLAGS_DOWN
        assertTrue(RdpPointerFlags.isMove(moveFlags))
        assertTrue(RdpPointerFlags.isButtonDown(moveFlags))
        assertTrue(RdpPointerFlags.isButton1(moveFlags))

        val scrollDown = RdpPointerFlags.SCROLL_DOWN
        assertTrue(RdpPointerFlags.isWheel(scrollDown))
        assertTrue(RdpPointerFlags.isWheelNegative(scrollDown))
        assertFalse(RdpPointerFlags.isHWheel(scrollDown))
    }
}
