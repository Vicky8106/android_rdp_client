package com.rdp.client.freerdp

import org.junit.Assert.assertEquals
import org.junit.Test

class RdpPointerFlagsTest {

    @Test
    fun testMoveFlag() {
        assertEquals(0x0800, RdpPointerFlags.encodeMove())
    }

    @Test
    fun testButtonDownFlags() {
        assertEquals(0x9000, RdpPointerFlags.encodeButtonDown(RdpPointerFlags.Button.LEFT))
        assertEquals(0xA000, RdpPointerFlags.encodeButtonDown(RdpPointerFlags.Button.RIGHT))
        assertEquals(0xC000, RdpPointerFlags.encodeButtonDown(RdpPointerFlags.Button.MIDDLE))
    }

    @Test
    fun testButtonUpFlags() {
        assertEquals(0x1000, RdpPointerFlags.encodeButtonUp(RdpPointerFlags.Button.LEFT))
        assertEquals(0x2000, RdpPointerFlags.encodeButtonUp(RdpPointerFlags.Button.RIGHT))
        assertEquals(0x4000, RdpPointerFlags.encodeButtonUp(RdpPointerFlags.Button.MIDDLE))
    }

    @Test
    fun testVerticalScrollWheelEncoding() {
        // Vertical Up 1 notch (+120 delta)
        val up = RdpPointerFlags.encodeVerticalScroll(RdpPointerFlags.ScrollDirection.UP, 1)
        assertEquals(0x0278, up)

        // Vertical Down 1 notch (-120 delta with WHEEL_NEGATIVE flag)
        val down = RdpPointerFlags.encodeVerticalScroll(RdpPointerFlags.ScrollDirection.DOWN, 1)
        assertEquals(0x0388, down)

        // Vertical Up 2 notches (+240 delta = 0xF0)
        val up2 = RdpPointerFlags.encodeVerticalScroll(RdpPointerFlags.ScrollDirection.UP, 2)
        assertEquals(0x02F0, up2)
    }

    @Test
    fun testHorizontalScrollWheelEncoding() {
        // Horizontal Right 1 notch (+120 delta)
        val right = RdpPointerFlags.encodeHorizontalScroll(RdpPointerFlags.ScrollDirection.RIGHT, 1)
        assertEquals(0x0478, right)

        // Horizontal Left 1 notch (-120 delta with WHEEL_NEGATIVE flag)
        val left = RdpPointerFlags.encodeHorizontalScroll(RdpPointerFlags.ScrollDirection.LEFT, 1)
        assertEquals(0x0588, left)
    }

    @Test
    fun testDragMoveEncoding() {
        val dragLeft = RdpPointerFlags.encodeDragMove(RdpPointerFlags.Button.LEFT)
        assertEquals(0x9800, dragLeft)
        assertEquals(RdpPointerFlags.PTRFLAGS_MOVE or RdpPointerFlags.encodeButtonDown(RdpPointerFlags.Button.LEFT), dragLeft)
    }
}
