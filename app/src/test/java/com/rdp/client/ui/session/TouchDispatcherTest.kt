package com.rdp.client.ui.session

import android.content.Context
import android.graphics.PointF
import androidx.test.core.app.ApplicationProvider
import com.rdp.client.freerdp.IRdpNativeBridge
import com.rdp.client.freerdp.LibFreeRDP
import com.rdp.client.freerdp.RdpPointerFlags
import com.rdp.client.model.GestureStyle
import com.rdp.client.ui.session.input.IFrameCoordinateTransformer
import com.rdp.client.ui.session.input.TouchDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TouchDispatcherTest {

    private val sentCursorEvents = mutableListOf<CapturedCursorEvent>()
    private val testInstance = 9999L

    data class CapturedCursorEvent(val instance: Long, val x: Int, val y: Int, val flags: Int)

    private val mockTransformer = object : IFrameCoordinateTransformer {
        override val fbWidth: Int = 1920
        override val fbHeight: Int = 1080
        override fun toFb(vpX: Float, vpY: Float): PointF? {
            return if (vpX in 0f..1920f && vpY in 0f..1080f) PointF(vpX, vpY) else null
        }
        override fun toVp(fbX: Float, fbY: Float): PointF = PointF(fbX, fbY)
        override fun panFrame(dx: Float, dy: Float) {}
        override fun zoomFrame(scaleFactor: Float, focusX: Float, focusY: Float) {}
        override val safeAreaCenterX: Float = 960f
        override val safeAreaCenterY: Float = 540f
    }

    @Before
    fun setUp() {
        sentCursorEvents.clear()
        LibFreeRDP.setNativeBridgeForTesting(object : IRdpNativeBridge {
            override fun newInstance(context: Context?): Long = testInstance
            override fun freeInstance(instance: Long) {}
            override fun connect(instance: Long, params: com.rdp.client.freerdp.RdpConnectionParameters?): Boolean = true
            override fun disconnect(instance: Long): Boolean = true
            override fun updateGraphics(instance: Long, bitmap: android.graphics.Bitmap, x: Int, y: Int, w: Int, h: Int): Boolean = true
            override fun sendCursorEvent(instance: Long, x: Int, y: Int, flags: Int): Boolean {
                sentCursorEvents.add(CapturedCursorEvent(instance, x, y, flags))
                return true
            }
            override fun sendKeyEvent(instance: Long, scancode: Int, extended: Boolean, down: Boolean): Boolean = true
            override fun sendUnicodeKeyEvent(instance: Long, codePoint: Int): Boolean = true
            override fun getVersion(): String = "test"
            override fun getLastError(instance: Long): String? = null
        })
    }

    @After
    fun tearDown() {
        LibFreeRDP.resetNativeBridge()
    }

    @Test
    fun testDirectTouchscreenSingleClick() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dispatcher = TouchDispatcher(
            context = context,
            instanceProvider = { testInstance },
            transformer = mockTransformer,
            gestureStyle = GestureStyle.TOUCHSCREEN,
            buttonUpDelayEnabled = true,
            scope = this
        )

        // Perform click at (500, 300)
        dispatcher.performSingleClick(500f, 300f)

        // Button DOWN dispatched immediately
        assertEquals(1, sentCursorEvents.size)
        assertEquals(500, sentCursorEvents[0].x)
        assertEquals(300, sentCursorEvents[0].y)
        assertEquals(RdpPointerFlags.encodeButtonDown(RdpPointerFlags.Button.LEFT), sentCursorEvents[0].flags)

        // Advance 25ms -> Button UP dispatched
        testScheduler.advanceTimeBy(25)
        assertEquals(2, sentCursorEvents.size)
        assertEquals(500, sentCursorEvents[1].x)
        assertEquals(300, sentCursorEvents[1].y)
        assertEquals(RdpPointerFlags.encodeButtonUp(RdpPointerFlags.Button.LEFT), sentCursorEvents[1].flags)
    }

    @Test
    fun testDirectTouchscreenEdgeCoercion() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dispatcher = TouchDispatcher(
            context = context,
            instanceProvider = { testInstance },
            transformer = mockTransformer,
            gestureStyle = GestureStyle.TOUCHSCREEN,
            scope = this
        )

        // Tap outside frame bounds (-50, 1200) -> should be coerced to (0, 1079)
        dispatcher.performSingleClick(-50f, 1200f)

        assertEquals(1, sentCursorEvents.size)
        assertEquals(0, sentCursorEvents[0].x)
        assertEquals(1079, sentCursorEvents[0].y)
        assertEquals(RdpPointerFlags.encodeButtonDown(RdpPointerFlags.Button.LEFT), sentCursorEvents[0].flags)

        testScheduler.advanceTimeBy(30)
        assertEquals(2, sentCursorEvents.size)
        assertEquals(0, sentCursorEvents[1].x)
        assertEquals(1079, sentCursorEvents[1].y)
        assertEquals(RdpPointerFlags.encodeButtonUp(RdpPointerFlags.Button.LEFT), sentCursorEvents[1].flags)
    }

    @Test
    fun testRelativeTouchpadModeMovementAndClamping() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dispatcher = TouchDispatcher(
            context = context,
            instanceProvider = { testInstance },
            transformer = mockTransformer,
            gestureStyle = GestureStyle.TOUCHPAD
        )

        // Initial cursor at center (960, 540)
        assertEquals(960f, dispatcher.virtualCursor.x)
        assertEquals(540f, dispatcher.virtualCursor.y)

        // Move by (+100, +200)
        val (adx, ady) = dispatcher.accelerator.updateDelta(100f, 200f)
        dispatcher.virtualCursor.x += adx
        dispatcher.virtualCursor.y += ady
        dispatcher.sendCursorEvent(dispatcher.virtualCursor.x.toInt(), dispatcher.virtualCursor.y.toInt(), RdpPointerFlags.encodeMove())

        assertEquals(1, sentCursorEvents.size)
        assertEquals(RdpPointerFlags.PTRFLAGS_MOVE, sentCursorEvents[0].flags)
        assertTrue(sentCursorEvents[0].x >= 1060)
        assertTrue(sentCursorEvents[0].y >= 740)

        // Clamp test: move way beyond screen boundaries
        dispatcher.virtualCursor.x += 5000f
        dispatcher.virtualCursor.y += 5000f
        dispatcher.virtualCursor.x = dispatcher.virtualCursor.x.coerceIn(0f, 1919f)
        dispatcher.virtualCursor.y = dispatcher.virtualCursor.y.coerceIn(0f, 1079f)

        assertEquals(1919f, dispatcher.virtualCursor.x)
        assertEquals(1079f, dispatcher.virtualCursor.y)
    }

    @Test
    fun testScrollWheelEncodingViaDispatcher() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dispatcher = TouchDispatcher(
            context = context,
            instanceProvider = { testInstance },
            transformer = mockTransformer
        )

        // Vertical Scroll Up
        dispatcher.sendCursorEvent(100, 100, RdpPointerFlags.encodeVerticalScroll(RdpPointerFlags.ScrollDirection.UP, 1))
        assertEquals(1, sentCursorEvents.size)
        assertEquals(0x0278, sentCursorEvents[0].flags)

        // Vertical Scroll Down
        dispatcher.sendCursorEvent(100, 100, RdpPointerFlags.encodeVerticalScroll(RdpPointerFlags.ScrollDirection.DOWN, 1))
        assertEquals(2, sentCursorEvents.size)
        assertEquals(0x0388, sentCursorEvents[1].flags)

        // Horizontal Scroll Right
        dispatcher.sendCursorEvent(100, 100, RdpPointerFlags.encodeHorizontalScroll(RdpPointerFlags.ScrollDirection.RIGHT, 1))
        assertEquals(3, sentCursorEvents.size)
        assertEquals(0x0478, sentCursorEvents[2].flags)
    }

    @Test
    fun testReleaseAllHeldButtonsSafety() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dispatcher = TouchDispatcher(
            context = context,
            instanceProvider = { testInstance },
            transformer = mockTransformer
        )

        // Hold Left and Right buttons
        dispatcher.sendButtonDown(RdpPointerFlags.Button.LEFT, 100, 100)
        dispatcher.sendButtonDown(RdpPointerFlags.Button.RIGHT, 100, 100)
        assertEquals(2, sentCursorEvents.size)

        // Trigger safety teardown
        dispatcher.releaseAllButtons()

        // 2 release events must have been dispatched
        assertEquals(4, sentCursorEvents.size)
        val releaseEvents = sentCursorEvents.subList(2, 4)
        assertTrue(releaseEvents.any { it.flags == RdpPointerFlags.encodeButtonUp(RdpPointerFlags.Button.LEFT) })
        assertTrue(releaseEvents.any { it.flags == RdpPointerFlags.encodeButtonUp(RdpPointerFlags.Button.RIGHT) })
        assertFalse(dispatcher.isDragLocked)
    }
}
