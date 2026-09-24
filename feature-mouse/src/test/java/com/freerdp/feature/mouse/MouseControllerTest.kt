package com.freerdp.feature.mouse

import com.freerdp.core.engine.MockRdpEngine
import com.freerdp.core.protocol.RdpPointerFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MouseControllerTest {

    private lateinit var mockEngine: MockRdpEngine
    private lateinit var transformer: CoordinateTransformer
    private lateinit var controller: DefaultMouseController

    @Before
    fun setUp() {
        mockEngine = MockRdpEngine()
        // Remote 1920x1080, Viewport 1920x1080 (1:1 mapping with 0 translation)
        transformer = CoordinateTransformer(
            remoteWidth = 1920,
            remoteHeight = 1080,
            viewWidth = 1920,
            viewHeight = 1080
        )
        transformer.setTransform(newScale = 1.0f, transX = 0f, transY = 0f)
        controller = DefaultMouseController(mockEngine, transformer)
    }

    @Test
    fun testLeftClickGeneratesDownAndUpSequence() {
        controller.handleLeftClick(300f, 400f)

        assertEquals(2, mockEngine.recordedPointerEvents.size)
        val down = mockEngine.recordedPointerEvents[0]
        val up = mockEngine.recordedPointerEvents[1]

        assertEquals(RdpPointerFlags.LEFT_BUTTON_DOWN, down.flags)
        assertEquals(300, down.x)
        assertEquals(400, down.y)

        assertEquals(RdpPointerFlags.LEFT_BUTTON_UP, up.flags)
        assertEquals(300, up.x)
        assertEquals(400, up.y)
    }

    @Test
    fun testRightClickGeneratesDownAndUpSequence() {
        controller.handleRightClick(500f, 600f)

        assertEquals(2, mockEngine.recordedPointerEvents.size)
        val down = mockEngine.recordedPointerEvents[0]
        val up = mockEngine.recordedPointerEvents[1]

        assertEquals(RdpPointerFlags.RIGHT_BUTTON_DOWN, down.flags)
        assertEquals(500, down.x)
        assertEquals(600, down.y)

        assertEquals(RdpPointerFlags.RIGHT_BUTTON_UP, up.flags)
        assertEquals(500, up.x)
        assertEquals(600, up.y)
    }

    @Test
    fun testMiddleClickGeneratesDownAndUpSequence() {
        controller.handleMiddleClick(350f, 450f)

        assertEquals(2, mockEngine.recordedPointerEvents.size)
        val down = mockEngine.recordedPointerEvents[0]
        val up = mockEngine.recordedPointerEvents[1]

        assertEquals(RdpPointerFlags.MIDDLE_BUTTON_DOWN, down.flags)
        assertEquals(350, down.x)
        assertEquals(450, down.y)

        assertEquals(RdpPointerFlags.MIDDLE_BUTTON_UP, up.flags)
        assertEquals(350, up.x)
        assertEquals(450, up.y)
    }

    @Test
    fun testTouchpadModeMiddleClickAtVirtualCursor() {
        controller.setTouchpadMode(true)
        controller.setVirtualCursorPosition(700f, 400f)
        mockEngine.clearRecordedEvents()

        controller.handleMiddleClick(100f, 100f)

        assertEquals(2, mockEngine.recordedPointerEvents.size)
        val down = mockEngine.recordedPointerEvents[0]
        val up = mockEngine.recordedPointerEvents[1]

        assertEquals(RdpPointerFlags.MIDDLE_BUTTON_DOWN, down.flags)
        assertEquals(700, down.x)
        assertEquals(400, down.y)

        assertEquals(RdpPointerFlags.MIDDLE_BUTTON_UP, up.flags)
        assertEquals(700, up.x)
        assertEquals(400, up.y)
    }

    @Test
    fun testDoubleClickGeneratesFourPointerEvents() {
        controller.handleDoubleClick(200f, 250f)

        assertEquals(4, mockEngine.recordedPointerEvents.size)
        assertEquals(RdpPointerFlags.LEFT_BUTTON_DOWN, mockEngine.recordedPointerEvents[0].flags)
        assertEquals(RdpPointerFlags.LEFT_BUTTON_UP, mockEngine.recordedPointerEvents[1].flags)
        assertEquals(RdpPointerFlags.LEFT_BUTTON_DOWN, mockEngine.recordedPointerEvents[2].flags)
        assertEquals(RdpPointerFlags.LEFT_BUTTON_UP, mockEngine.recordedPointerEvents[3].flags)

        for (event in mockEngine.recordedPointerEvents) {
            assertEquals(200, event.x)
            assertEquals(250, event.y)
        }
    }

    @Test
    fun testDragStartMoveEndSequence() {
        controller.handleDragStart(100f, 100f)
        assertTrue(controller.isDragging)
        assertEquals(1, mockEngine.recordedPointerEvents.size)
        assertEquals(RdpPointerFlags.LEFT_BUTTON_DOWN, mockEngine.recordedPointerEvents[0].flags)

        controller.handleDragMove(150f, 160f)
        assertTrue(controller.isDragging)
        assertEquals(2, mockEngine.recordedPointerEvents.size)
        val moveEvent = mockEngine.recordedPointerEvents[1]
        val expectedMoveFlags = RdpPointerFlags.MOVE or RdpPointerFlags.PTR_FLAGS_BUTTON1 or RdpPointerFlags.PTR_FLAGS_DOWN
        assertEquals(expectedMoveFlags, moveEvent.flags)
        assertEquals(150, moveEvent.x)
        assertEquals(160, moveEvent.y)

        controller.handleDragEnd(200f, 220f)
        assertFalse(controller.isDragging)
        assertEquals(3, mockEngine.recordedPointerEvents.size)
        val endEvent = mockEngine.recordedPointerEvents[2]
        assertEquals(RdpPointerFlags.LEFT_BUTTON_UP, endEvent.flags)
        assertEquals(200, endEvent.x)
        assertEquals(220, endEvent.y)
    }

    @Test
    fun testVerticalScrollFlags() {
        // Up scroll
        controller.handleScroll(400f, 500f, deltaY = 1.0f)
        // Down scroll
        controller.handleScroll(400f, 500f, deltaY = -1.0f)

        assertEquals(2, mockEngine.recordedPointerEvents.size)
        val scrollUp = mockEngine.recordedPointerEvents[0]
        val scrollDown = mockEngine.recordedPointerEvents[1]

        assertEquals(RdpPointerFlags.SCROLL_UP, scrollUp.flags)
        assertEquals(0x0278, scrollUp.flags)

        assertEquals(RdpPointerFlags.SCROLL_DOWN, scrollDown.flags)
        assertEquals(0x0378, scrollDown.flags)
    }

    @Test
    fun testHorizontalScrollFlags() {
        controller.handleHorizontalScroll(400f, 500f, deltaX = 1.0f)
        controller.handleHorizontalScroll(400f, 500f, deltaX = -1.0f)

        assertEquals(2, mockEngine.recordedPointerEvents.size)
        assertEquals(RdpPointerFlags.SCROLL_RIGHT, mockEngine.recordedPointerEvents[0].flags)
        assertEquals(0x0478, mockEngine.recordedPointerEvents[0].flags)

        assertEquals(RdpPointerFlags.SCROLL_LEFT, mockEngine.recordedPointerEvents[1].flags)
        assertEquals(0x0578, mockEngine.recordedPointerEvents[1].flags)
    }

    @Test
    fun testTouchpadModeRelativeMovementAndClamping() {
        controller.setTouchpadMode(true)
        assertTrue(controller.isTouchpadMode)
        assertTrue(controller.isCursorVisible)

        // Set initial cursor to (500, 500)
        controller.setVirtualCursorPosition(500f, 500f)
        mockEngine.clearRecordedEvents()

        // Move by (+50, +30)
        controller.handleTouchpadMove(50f, 30f)
        assertEquals(1, mockEngine.recordedPointerEvents.size)
        val event = mockEngine.recordedPointerEvents[0]
        assertEquals(RdpPointerFlags.MOVE, event.flags)
        assertEquals(550, event.x)
        assertEquals(530, event.y)

        // Move beyond extreme boundary (remoteWidth = 1920 -> max 1919, remoteHeight = 1080 -> max 1079)
        controller.handleTouchpadMove(5000f, 5000f)
        val clampedEvent = mockEngine.recordedPointerEvents.last()
        assertEquals(1919, clampedEvent.x)
        assertEquals(1079, clampedEvent.y)
    }

    @Test
    fun testTouchpadModeClicksAtVirtualCursorPosition() {
        controller.setTouchpadMode(true)
        controller.setVirtualCursorPosition(800f, 600f)
        mockEngine.clearRecordedEvents()

        // Single click at arbitrary screen coordinate; must click at virtual cursor (800, 600)
        controller.handleLeftClick(100f, 100f)
        assertEquals(2, mockEngine.recordedPointerEvents.size)
        assertEquals(800, mockEngine.recordedPointerEvents[0].x)
        assertEquals(600, mockEngine.recordedPointerEvents[0].y)
        assertEquals(800, mockEngine.recordedPointerEvents[1].x)
        assertEquals(600, mockEngine.recordedPointerEvents[1].y)
    }

    @Test
    fun testSafetyReleaseWhenExitingTouchpadModeDuringDrag() {
        controller.setTouchpadMode(true)
        controller.handleDragStart(0f, 0f)
        assertTrue(controller.isDragging)

        // Disable touchpad mode while drag is active: must release LMB
        controller.setTouchpadMode(false)
        assertFalse(controller.isDragging)
        val lastEvent = mockEngine.recordedPointerEvents.last()
        assertEquals(RdpPointerFlags.LEFT_BUTTON_UP, lastEvent.flags)
    }

    /**
     * reviewer_w2 F3 regression: exiting touchpad mode mid-drag used to feed the
     * DESKTOP virtual-cursor coordinates back through resolveTargetCoordinates() after
     * isTouchpadMode had already flipped false — applying screen→desktop a second time.
     * Fails on the old code whenever the transform is not the identity (here scale 2,
     * translation (100,50): the up coordinate would be (280,220) instead of (660,490)).
     */
    @Test
    fun testExitingTouchpadModeMidDragReleasesAtVirtualCursorWithSingleTransform() {
        transformer.setTransform(newScale = 2.0f, transX = 100f, transY = 50f)
        controller.setTouchpadMode(true)
        controller.setVirtualCursorPosition(640f, 480f)
        mockEngine.clearRecordedEvents()

        // Button-down in touchpad basis: virtual cursor IS the desktop coordinate.
        controller.handleDragStart(300f, 300f)
        assertTrue(controller.isDragging)
        assertEquals(1, mockEngine.recordedPointerEvents.size)
        assertEquals(RdpPointerFlags.LEFT_BUTTON_DOWN, mockEngine.recordedPointerEvents[0].flags)
        assertEquals(640, mockEngine.recordedPointerEvents[0].x)
        assertEquals(480, mockEngine.recordedPointerEvents[0].y)

        // Drag the virtual cursor by (+20, +10) desktop pixels.
        controller.handleTouchpadMove(20f, 10f)
        assertEquals(660, mockEngine.recordedPointerEvents[1].x)
        assertEquals(490, mockEngine.recordedPointerEvents[1].y)

        // Exit touchpad mode while still dragging.
        controller.setTouchpadMode(false)
        assertFalse(controller.isDragging)

        val ups = mockEngine.recordedPointerEvents.filter { it.flags == RdpPointerFlags.LEFT_BUTTON_UP }
        assertEquals("exactly one safety button-up", 1, ups.size)
        val up = ups.single()
        assertEquals(
            "button-up must use the SAME (touchpad/desktop) basis as button-down — " +
                "single transform, not screen→desktop applied twice",
            660, up.x
        )
        assertEquals(490, up.y)
        // The old code produced screenToDesktopInt(660, 490) = ((660-100)/2, (490-50)/2):
        assertEquals(
            "old double-transform result must NOT be what reaches the engine",
            280,
            transformer.screenToDesktopInt(660f, 490f).first
        )
        assertEquals(220, transformer.screenToDesktopInt(660f, 490f).second)
    }

    @Test
    fun testReleaseButtonsMethod() {
        controller.handleDragStart(100f, 100f)
        assertTrue(controller.isDragging)

        controller.releaseButtons()
        assertFalse(controller.isDragging)
        val lastEvent = mockEngine.recordedPointerEvents.last()
        assertEquals(RdpPointerFlags.LEFT_BUTTON_UP, lastEvent.flags)
    }
}
