package com.freerdp.feature.mouse

import android.graphics.PointF
import com.freerdp.core.engine.MockRdpEngine
import com.freerdp.core.protocol.RdpPointerFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PointerModesTest {

    private lateinit var mockEngine: MockRdpEngine
    private lateinit var transformer: CoordinateTransformer
    private lateinit var directMode: DirectPointerMode
    private lateinit var relativeMode: RelativePointerMode

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

        directMode = DirectPointerMode(mockEngine, transformer)
        relativeMode = RelativePointerMode(mockEngine, transformer)
    }

    @Test
    fun testDirectModeClickInsideFrame() {
        val touch = PointF(300f, 400f)
        directMode.doClick(PointerButton.Left, touch)

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
    fun testDirectModeEdgeCoercionOutsideFrame() {
        // Configure transformer with letterbox centering:
        // Viewport 1080x2400, remote 1920x1080, fit scale = 0.5625
        // Content height = 607.5, translationY = (2400 - 607.5) / 2 = 896.25
        val letterboxedTransformer = CoordinateTransformer(
            remoteWidth = 1920,
            remoteHeight = 1080,
            viewWidth = 1080,
            viewHeight = 2400
        )
        letterboxedTransformer.resetToFit()
        val letterboxDirectMode = DirectPointerMode(mockEngine, letterboxedTransformer)

        // Point in top letterbox bar (y = 50f, well above content top 896.25f)
        val outsidePoint = PointF(540f, 50f)
        assertNull(letterboxedTransformer.toFb(outsidePoint))

        val coercedDesktop = letterboxedTransformer.coerceToFbEdgeDesktop(outsidePoint)
        assertNotNull(coercedDesktop)
        assertEquals(0f, coercedDesktop!!.y, 0.001f) // Coerced to top edge (y = 0)

        // Clicking in letterbox bar executes edge coercion
        letterboxDirectMode.doClick(PointerButton.Left, outsidePoint)
        assertEquals(2, mockEngine.recordedPointerEvents.size)
        val down = mockEngine.recordedPointerEvents[0]
        val up = mockEngine.recordedPointerEvents[1]

        assertEquals(RdpPointerFlags.LEFT_BUTTON_DOWN, down.flags)
        assertEquals(0, down.y) // Clamped to remote desktop top edge
        assertEquals(RdpPointerFlags.LEFT_BUTTON_UP, up.flags)
        assertEquals(0, up.y)
    }

    @Test
    fun testDirectModeRightAndMiddleClick() {
        val touch = PointF(100f, 200f)

        directMode.doClick(PointerButton.Right, touch)
        assertEquals(2, mockEngine.recordedPointerEvents.size)
        assertEquals(RdpPointerFlags.RIGHT_BUTTON_DOWN, mockEngine.recordedPointerEvents[0].flags)
        assertEquals(RdpPointerFlags.RIGHT_BUTTON_UP, mockEngine.recordedPointerEvents[1].flags)

        mockEngine.clearRecordedEvents()

        directMode.doClick(PointerButton.Middle, touch)
        assertEquals(2, mockEngine.recordedPointerEvents.size)
        assertEquals(RdpPointerFlags.MIDDLE_BUTTON_DOWN, mockEngine.recordedPointerEvents[0].flags)
        assertEquals(RdpPointerFlags.MIDDLE_BUTTON_UP, mockEngine.recordedPointerEvents[1].flags)
    }

    @Test
    fun testDirectModeTwoFingerPanAndPinchZoom() {
        val initialScale = transformer.scale
        val initialTransX = transformer.translationX

        // Zoom in to 2.0x so panning is allowed
        directMode.handlePinchZoom(2.0f, 500f, 500f)
        assertEquals(2.0f, transformer.scale, 0.001f)

        // Apply two-finger pan
        directMode.handleTwoFingerPan(-50f, -30f)
        assertTrue(transformer.translationX != initialTransX)
    }

    @Test
    fun testDirectModeRemoteScroll() {
        val touch = PointF(400f, 400f)
        // Accumulated dy = +25 -> triggers SCROLL_UP once
        directMode.doRemoteScroll(touch, dx = 0f, dy = 25f)

        assertEquals(1, mockEngine.recordedPointerEvents.size)
        assertEquals(RdpPointerFlags.SCROLL_UP, mockEngine.recordedPointerEvents[0].flags)

        // Accumulated dy = -45 -> triggers SCROLL_DOWN twice (2 * 20 = 40)
        directMode.doRemoteScroll(touch, dx = 0f, dy = -45f)
        val downs = mockEngine.recordedPointerEvents.filter { it.flags == RdpPointerFlags.SCROLL_DOWN }
        assertEquals(2, downs.size)
    }

    @Test
    fun testRelativeModeMovementAndClamping() {
        relativeMode.setCursorPosition(500f, 500f)
        mockEngine.clearRecordedEvents()

        // Relative move without acceleration
        relativeMode.doMovePointer(50f, 30f, accelerate = false)
        assertEquals(1, mockEngine.recordedPointerEvents.size)
        val event = mockEngine.recordedPointerEvents[0]
        assertEquals(RdpPointerFlags.MOVE, event.flags)
        assertEquals(550, event.x)
        assertEquals(530, event.y)

        // Relative move beyond extreme bounds clamps to remote bounds (1919, 1079)
        relativeMode.doMovePointer(3000f, 3000f, accelerate = false)
        val clampedEvent = mockEngine.recordedPointerEvents.last()
        assertEquals(1919, clampedEvent.x)
        assertEquals(1079, clampedEvent.y)
    }

    @Test
    fun testRelativeModeAutoCenteringWhenZoomed() {
        // Zoom in to 2x so content (3840x2160) exceeds viewport (1920x1080)
        transformer.setScale(2.0f, 960f, 540f)
        val origTransX = transformer.translationX

        relativeMode.setCursorPosition(100f, 100f)
        relativeMode.autoCenterViewport()

        // Panning should have adjusted translation to keep cursor near center
        assertTrue(transformer.translationX != origTransX || transformer.translationY != 0f)
    }

    @Test
    fun testRelativeModeRemoteDragAndStop() {
        relativeMode.setCursorPosition(300f, 300f)
        mockEngine.clearRecordedEvents()

        relativeMode.doRemoteDrag(PointerButton.Left, PointF(0f, 0f), 20f, 20f)
        assertTrue(relativeMode.isDragging)
        assertTrue(mockEngine.recordedPointerEvents.isNotEmpty())
        assertEquals(RdpPointerFlags.LEFT_BUTTON_DOWN, mockEngine.recordedPointerEvents[0].flags)

        relativeMode.onGestureStop(PointF(0f, 0f))
        assertFalse(relativeMode.isDragging)
        val lastEvent = mockEngine.recordedPointerEvents.last()
        assertEquals(RdpPointerFlags.LEFT_BUTTON_UP, lastEvent.flags)
    }

    @Test
    fun testSwipeVsScaleDisambiguation() {
        val swipeVsScale = SwipeVsScale()

        // Two fingers moving in parallel (both moving right: delta (100, 0))
        // Theta 1 = 0 deg, Theta 2 = 0 deg -> diff = 0 deg < 30 deg -> SWIPE
        val decisionSwipe = swipeVsScale.decide(
            p1Start = PointF(100f, 100f),
            p1Current = PointF(200f, 100f),
            p2Start = PointF(100f, 200f),
            p2Current = PointF(200f, 200f)
        )
        assertEquals(SwipeVsScale.Decision.SWIPE, decisionSwipe)

        // Two fingers moving in opposite directions (pinching: F1 moving left, F2 moving right)
        // Theta 1 = 180 deg, Theta 2 = 0 deg -> diff = 180 deg > 45 deg -> SCALE
        val decisionScale = swipeVsScale.decide(
            p1Start = PointF(200f, 200f),
            p1Current = PointF(100f, 200f),
            p2Start = PointF(300f, 200f),
            p2Current = PointF(400f, 200f)
        )
        assertEquals(SwipeVsScale.Decision.SCALE, decisionScale)
    }
}
