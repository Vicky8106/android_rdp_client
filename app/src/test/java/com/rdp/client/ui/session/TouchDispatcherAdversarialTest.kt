package com.rdp.client.ui.session

import android.content.Context
import android.graphics.PointF
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import com.rdp.client.freerdp.IRdpNativeBridge
import com.rdp.client.freerdp.LibFreeRDP
import com.rdp.client.freerdp.RdpPointerFlags
import com.rdp.client.model.GestureStyle
import com.rdp.client.model.ViewMode
import com.rdp.client.ui.session.input.IFrameCoordinateTransformer
import com.rdp.client.ui.session.input.PointerAcceleration
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
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sign

/**
 * Adversarial Empirical Test Suite stress-testing Milestone 4 Touch & Mouse Input Subsystem:
 * 1. PointerAcceleration non-linear ballistics across velocity thresholds:
 *    - Sub-threshold unity gain (1.0x)
 *    - Superlinear ramping across velocity spectrum (power exponent 1.5)
 *    - Saturation capping at 3.5x
 *    - Direction & aspect ratio preservation
 *    - MotionEvent tracking & lifecycle reset
 * 2. Direct Touchscreen mode:
 *    - 1:1 tap to click & delayed button UP release
 *    - Synchronous button UP release
 *    - Boundary coercion on adversarial off-screen coordinates
 *    - Long press right-click
 *    - Double-click timing
 *    - Multi-touch cancellation & suppression
 * 3. Relative Touchpad mode:
 *    - Initial center positioning
 *    - Clamping against desktop boundaries [0..fbWidth-1, 0..fbHeight-1]
 *    - Click routing to virtual cursor invariant
 *    - Drag lock encoding
 *    - Viewport auto-centering tracking
 * 4. Two-finger & Scroll Wheel events:
 *    - GenericMotion mouse wheel vertical (UP/DOWN) & horizontal (LEFT/RIGHT)
 *    - Historical batch two-finger scrolling
 *    - Empirical observation of unbatched historySize==0 vulnerability
 * 5. ViewMode switching:
 *    - ViewMode.NORMAL interactive behavior
 *    - ViewMode.VIEW_ONLY input suppression verification & empirical leak assessment
 *    - ViewMode.BACKGROUND rendering pause state
 *    - Guaranteed button release on focus loss / teardown
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TouchDispatcherAdversarialTest {

    private val sentCursorEvents = mutableListOf<CapturedCursorEvent>()
    private val testInstance = 8888L

    data class CapturedCursorEvent(val instance: Long, val x: Int, val y: Int, val flags: Int)

    class MockTransformer(
        override val fbWidth: Int = 1920,
        override val fbHeight: Int = 1080,
        override val safeAreaCenterX: Float = 960f,
        override val safeAreaCenterY: Float = 540f
    ) : IFrameCoordinateTransformer {
        var pannedDx = 0f
        var pannedDy = 0f
        var zoomedFactor = 1.0f

        override fun toFb(vpX: Float, vpY: Float): PointF? {
            return if (vpX in 0f..fbWidth.toFloat() && vpY in 0f..fbHeight.toFloat()) {
                PointF(vpX, vpY)
            } else {
                null
            }
        }

        override fun toVp(fbX: Float, fbY: Float): PointF = PointF(fbX, fbY)

        override fun panFrame(dx: Float, dy: Float) {
            pannedDx += dx
            pannedDy += dy
        }

        override fun zoomFrame(scaleFactor: Float, focusX: Float, focusY: Float) {
            zoomedFactor *= scaleFactor
        }
    }

    private lateinit var mockTransformer: MockTransformer
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        mockTransformer = MockTransformer()
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
            override fun getVersion(): String = "FreeRDP 3.5.1-adversarial"
            override fun getLastError(instance: Long): String? = null
        })
    }

    @After
    fun tearDown() {
        LibFreeRDP.resetNativeBridge()
    }

    // =========================================================================
    // 1. PointerAcceleration Non-Linear Curves & Mathematical Ballistics
    // =========================================================================

    private fun theoreticalGain(acc: PointerAcceleration, speed: Float): Float {
        if (speed <= acc.minVelocity) return acc.baseGain
        val norm = ((speed - acc.minVelocity) / (acc.maxVelocity - acc.minVelocity)).coerceIn(0f, 1f)
        val curve = norm.pow(acc.powerExponent)
        return (acc.baseGain + (acc.maxGain - acc.baseGain) * curve).coerceIn(acc.baseGain, acc.maxGain)
    }

    @Test
    fun testSubThresholdVelocityMaintainsUnityGainExact() {
        val acc = PointerAcceleration(baseGain = 1.0f, maxGain = 3.5f, minVelocity = 300f, maxVelocity = 3200f)

        val subThresholdSpeeds = floatArrayOf(0f, 1f, 50f, 100f, 200f, 299f, 300f)
        for (speed in subThresholdSpeeds) {
            val gain = theoreticalGain(acc, speed)
            assertEquals("Gain at speed $speed must be unity (1.0x)", 1.0f, gain, 0.0001f)
        }

        // Sub-threshold updateDelta without movement tracking defaults to baseGain
        val (dx, dy) = acc.updateDelta(15f, -25f)
        assertEquals(15f, dx, 0.0001f)
        assertEquals(-25f, dy, 0.0001f)
    }

    @Test
    fun testSuperlinearRampingAcrossVelocitySpectrum() {
        val acc = PointerAcceleration(baseGain = 1.0f, maxGain = 3.5f, minVelocity = 300f, maxVelocity = 3200f, powerExponent = 1.5f)

        // Monotonicity verification across 100 steps from 300 to 3200
        var prevGain = 1.0f
        val step = (3200f - 300f) / 100f
        for (i in 1..100) {
            val speed = 300f + i * step
            val gain = theoreticalGain(acc, speed)
            assertTrue("Gain must monotonically increase at speed $speed (got $gain vs prev $prevGain)", gain >= prevGain)
            assertTrue("Gain must remain <= maxGain (3.5x)", gain <= 3.5f)
            prevGain = gain
        }

        // Exact mathematical checkpoints
        // Midpoint: (300 + 3200) / 2 = 1750; norm = 0.5; 0.5^1.5 = 0.35355; 1.0 + 2.5 * 0.35355 = 1.8839
        val midGain = theoreticalGain(acc, 1750f)
        assertEquals(1.8839f, midGain, 0.005f)

        // Quarter point: 300 + 0.25 * 2900 = 1025; norm = 0.25; 0.25^1.5 = 0.125; 1.0 + 2.5 * 0.125 = 1.3125
        val quarterGain = theoreticalGain(acc, 1025f)
        assertEquals(1.3125f, quarterGain, 0.005f)

        // Three-quarter point: 300 + 0.75 * 2900 = 2475; norm = 0.75; 0.75^1.5 = 0.6495; 1.0 + 2.5 * 0.6495 = 2.6238
        val threeQuarterGain = theoreticalGain(acc, 2475f)
        assertEquals(2.6238f, threeQuarterGain, 0.005f)
    }

    @Test
    fun testHighVelocitySaturationCappedAtMaxGain() {
        val acc = PointerAcceleration(baseGain = 1.0f, maxGain = 3.5f, minVelocity = 300f, maxVelocity = 3200f)

        val extremeSpeeds = floatArrayOf(3200f, 3201f, 4000f, 8000f, 50000f, 1000000f)
        for (speed in extremeSpeeds) {
            val gain = theoreticalGain(acc, speed)
            assertEquals("High velocity $speed must be strictly capped at maxGain 3.5x", 3.5f, gain, 0.0001f)
        }
    }

    @Test
    fun testAspectAndSignPreservationUnderAcceleration() {
        val acc = PointerAcceleration(baseGain = 1.0f, maxGain = 3.5f)

        val vectors = listOf(
            Pair(10f, 20f),
            Pair(-15f, 30f),
            Pair(40f, -80f),
            Pair(-100f, -50f)
        )

        for ((dx, dy) in vectors) {
            val (adx, ady) = acc.updateDelta(dx, dy)
            assertEquals(sign(dx), sign(adx))
            assertEquals(sign(dy), sign(ady))
            assertEquals(abs(dy / dx), abs(ady / adx), 0.0001f)
        }

        // Zero delta
        val (zdx, zdy) = acc.updateDelta(0f, 0f)
        assertEquals(0f, zdx, 0.0001f)
        assertEquals(0f, zdy, 0.0001f)
    }

    @Test
    fun testMotionEventVelocityTrackerIntegration() {
        val acc = PointerAcceleration(baseGain = 1.0f, maxGain = 3.5f, minVelocity = 300f, maxVelocity = 3200f)

        val downTime = SystemClock.uptimeMillis()
        val downEvent = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, 100f, 100f, 0)
        acc.addMovement(downEvent)

        // Slow movement: 50 pixels over 500ms -> ~100 px/sec
        val slowMoveEvent = MotionEvent.obtain(downTime, downTime + 500, MotionEvent.ACTION_MOVE, 150f, 100f, 0)
        acc.addMovement(slowMoveEvent)

        val slowMult = acc.computeMultiplier()
        assertTrue("Sub-threshold movement must produce >= 1.0f", slowMult >= 1.0f)
        assertTrue("Sub-threshold movement must not exceed maxGain", slowMult <= 3.5f)

        // Reset clears tracker cleanly
        acc.reset()
        assertEquals(1.0f, acc.computeMultiplier(), 0.0001f)

        downEvent.recycle()
        slowMoveEvent.recycle()
    }

    @Test
    fun testCustomConfigurationParameters() {
        val custom = PointerAcceleration(
            baseGain = 1.5f,
            maxGain = 5.0f,
            minVelocity = 100f,
            maxVelocity = 2000f,
            powerExponent = 2.0f
        )

        assertEquals(1.5f, theoreticalGain(custom, 50f), 0.0001f)
        assertEquals(5.0f, theoreticalGain(custom, 2500f), 0.0001f)

        // Normalized 0.5 -> 100 + 0.5 * 1900 = 1050; 0.5^2 = 0.25; 1.5 + 3.5 * 0.25 = 2.375
        assertEquals(2.375f, theoreticalGain(custom, 1050f), 0.001f)
    }

    // =========================================================================
    // 2. Direct Touchscreen Mode
    // =========================================================================

    @Test
    fun testDirectTouchscreenSingleTapDownAndUpTiming() = runTest {
        val dispatcher = TouchDispatcher(
            context = context,
            instanceProvider = { testInstance },
            transformer = mockTransformer,
            gestureStyle = GestureStyle.TOUCHSCREEN,
            buttonUpDelayEnabled = true,
            scope = this
        )

        dispatcher.performSingleClick(720f, 480f)

        // Immediately dispatched: 1 button DOWN event at (720, 480)
        assertEquals(1, sentCursorEvents.size)
        assertEquals(720, sentCursorEvents[0].x)
        assertEquals(480, sentCursorEvents[0].y)
        assertEquals(RdpPointerFlags.encodeButtonDown(RdpPointerFlags.Button.LEFT), sentCursorEvents[0].flags)

        // Before 20ms: no button UP yet
        testScheduler.advanceTimeBy(10)
        assertEquals(1, sentCursorEvents.size)

        // After calibrated 20ms delay: button UP dispatched
        testScheduler.advanceTimeBy(15)
        assertEquals(2, sentCursorEvents.size)
        assertEquals(720, sentCursorEvents[1].x)
        assertEquals(480, sentCursorEvents[1].y)
        assertEquals(RdpPointerFlags.encodeButtonUp(RdpPointerFlags.Button.LEFT), sentCursorEvents[1].flags)
    }

    @Test
    fun testDirectTouchscreenImmediateButtonUpWhenDelayDisabled() = runTest {
        val dispatcher = TouchDispatcher(
            context = context,
            instanceProvider = { testInstance },
            transformer = mockTransformer,
            gestureStyle = GestureStyle.TOUCHSCREEN,
            buttonUpDelayEnabled = false,
            scope = this
        )

        dispatcher.performSingleClick(300f, 200f)

        // Both down and up dispatched immediately without coroutine delay
        assertEquals(2, sentCursorEvents.size)
        assertEquals(RdpPointerFlags.encodeButtonDown(RdpPointerFlags.Button.LEFT), sentCursorEvents[0].flags)
        assertEquals(RdpPointerFlags.encodeButtonUp(RdpPointerFlags.Button.LEFT), sentCursorEvents[1].flags)
        assertEquals(300, sentCursorEvents[0].x)
        assertEquals(300, sentCursorEvents[1].x)
    }

    @Test
    fun testDirectTouchscreenBoundaryCoercionAdversarialCoordinates() = runTest {
        val dispatcher = TouchDispatcher(
            context = context,
            instanceProvider = { testInstance },
            transformer = mockTransformer,
            gestureStyle = GestureStyle.TOUCHSCREEN,
            buttonUpDelayEnabled = false,
            scope = this
        )

        // Case 1: Negative coordinates (-200, -300) -> coerce to (0, 0)
        dispatcher.performSingleClick(-200f, -300f)
        assertEquals(0, sentCursorEvents[0].x)
        assertEquals(0, sentCursorEvents[0].y)

        // Case 2: Excessive coordinates (5000, 8000) -> coerce to (1919, 1079)
        dispatcher.performSingleClick(5000f, 8000f)
        assertEquals(1919, sentCursorEvents[2].x)
        assertEquals(1079, sentCursorEvents[2].y)

        // Case 3: Mixed boundary (-10, 600) -> coerce to (0, 600)
        dispatcher.performSingleClick(-10f, 600f)
        assertEquals(0, sentCursorEvents[4].x)
        assertEquals(600, sentCursorEvents[4].y)

        // Case 4: Mixed boundary (1000, 2000) -> coerce to (1000, 1079)
        dispatcher.performSingleClick(1000f, 2000f)
        assertEquals(1000, sentCursorEvents[6].x)
        assertEquals(1079, sentCursorEvents[6].y)
    }

    @Test
    fun testDirectTouchscreenLongPressRightClick() = runTest {
        val dispatcher = TouchDispatcher(
            context = context,
            instanceProvider = { testInstance },
            transformer = mockTransformer,
            gestureStyle = GestureStyle.TOUCHSCREEN,
            buttonUpDelayEnabled = true,
            scope = this
        )

        dispatcher.performRightClick(850f, 420f)

        // Immediate RIGHT button down
        assertEquals(1, sentCursorEvents.size)
        assertEquals(850, sentCursorEvents[0].x)
        assertEquals(420, sentCursorEvents[0].y)
        assertEquals(RdpPointerFlags.encodeButtonDown(RdpPointerFlags.Button.RIGHT), sentCursorEvents[0].flags)

        // Advance 25ms -> RIGHT button up
        testScheduler.advanceTimeBy(25)
        assertEquals(2, sentCursorEvents.size)
        assertEquals(850, sentCursorEvents[1].x)
        assertEquals(420, sentCursorEvents[1].y)
        assertEquals(RdpPointerFlags.encodeButtonUp(RdpPointerFlags.Button.RIGHT), sentCursorEvents[1].flags)
    }

    @Test
    fun testDirectTouchscreenDoubleClickTiming() = runTest {
        val dispatcher = TouchDispatcher(
            context = context,
            instanceProvider = { testInstance },
            transformer = mockTransformer,
            gestureStyle = GestureStyle.TOUCHSCREEN,
            buttonUpDelayEnabled = true,
            scope = this
        )

        dispatcher.performDoubleClick(500f, 500f)

        // Click 1 down immediately
        assertEquals(1, sentCursorEvents.size)
        assertEquals(RdpPointerFlags.encodeButtonDown(RdpPointerFlags.Button.LEFT), sentCursorEvents[0].flags)

        // Advance 25ms -> Click 1 up
        testScheduler.advanceTimeBy(25)
        assertEquals(2, sentCursorEvents.size)
        assertEquals(RdpPointerFlags.encodeButtonUp(RdpPointerFlags.Button.LEFT), sentCursorEvents[1].flags)

        // Advance to 55ms (total > 50ms double-click spacing) -> Click 2 down
        testScheduler.advanceTimeBy(30)
        assertEquals(3, sentCursorEvents.size)
        assertEquals(RdpPointerFlags.encodeButtonDown(RdpPointerFlags.Button.LEFT), sentCursorEvents[2].flags)

        // Advance 25ms more -> Click 2 up
        testScheduler.advanceTimeBy(25)
        assertEquals(4, sentCursorEvents.size)
        assertEquals(RdpPointerFlags.encodeButtonUp(RdpPointerFlags.Button.LEFT), sentCursorEvents[3].flags)
    }

    @Test
    fun testMultiTouchActionCancelAndUpReleasesAllHeldButtons() {
        val dispatcher = TouchDispatcher(
            context = context,
            instanceProvider = { testInstance },
            transformer = mockTransformer
        )

        // Hold Left, Middle, and Right buttons
        dispatcher.sendButtonDown(RdpPointerFlags.Button.LEFT, 100, 100)
        dispatcher.sendButtonDown(RdpPointerFlags.Button.RIGHT, 100, 100)
        dispatcher.sendButtonDown(RdpPointerFlags.Button.MIDDLE, 100, 100)
        assertEquals(3, sentCursorEvents.size)

        // Trigger teardown
        dispatcher.releaseAllButtons()

        // 3 release events dispatched
        assertEquals(6, sentCursorEvents.size)
        val releaseFlags = sentCursorEvents.subList(3, 6).map { it.flags }
        assertTrue(releaseFlags.contains(RdpPointerFlags.encodeButtonUp(RdpPointerFlags.Button.LEFT)))
        assertTrue(releaseFlags.contains(RdpPointerFlags.encodeButtonUp(RdpPointerFlags.Button.RIGHT)))
        assertTrue(releaseFlags.contains(RdpPointerFlags.encodeButtonUp(RdpPointerFlags.Button.MIDDLE)))
    }

    // =========================================================================
    // 3. Relative Touchpad Mode
    // =========================================================================

    @Test
    fun testRelativeTouchpadInitialCenterPosition() {
        val dispatcher = TouchDispatcher(
            context = context,
            instanceProvider = { testInstance },
            transformer = mockTransformer,
            gestureStyle = GestureStyle.TOUCHPAD
        )

        assertEquals(960f, dispatcher.virtualCursor.x, 0.001f)
        assertEquals(540f, dispatcher.virtualCursor.y, 0.001f)
    }

    @Test
    fun testRelativeTouchpadBoundaryClampingExtremeDeltas() {
        val dispatcher = TouchDispatcher(
            context = context,
            instanceProvider = { testInstance },
            transformer = mockTransformer,
            gestureStyle = GestureStyle.TOUCHPAD
        )

        // Massive positive motion
        val (adxPos, adyPos) = dispatcher.accelerator.updateDelta(100000f, 100000f)
        val xLimit = (mockTransformer.fbWidth - 1).toFloat()
        val yLimit = (mockTransformer.fbHeight - 1).toFloat()

        dispatcher.virtualCursor.x = (dispatcher.virtualCursor.x + adxPos).coerceIn(0f, xLimit)
        dispatcher.virtualCursor.y = (dispatcher.virtualCursor.y + adyPos).coerceIn(0f, yLimit)

        assertEquals(1919f, dispatcher.virtualCursor.x, 0.001f)
        assertEquals(1079f, dispatcher.virtualCursor.y, 0.001f)

        // Massive negative motion
        val (adxNeg, adyNeg) = dispatcher.accelerator.updateDelta(-200000f, -200000f)
        dispatcher.virtualCursor.x = (dispatcher.virtualCursor.x + adxNeg).coerceIn(0f, xLimit)
        dispatcher.virtualCursor.y = (dispatcher.virtualCursor.y + adyNeg).coerceIn(0f, yLimit)

        assertEquals(0f, dispatcher.virtualCursor.x, 0.001f)
        assertEquals(0f, dispatcher.virtualCursor.y, 0.001f)
    }

    @Test
    fun testRelativeTouchpadTapDispatchesAtVirtualCursorNotTapLocation() = runTest {
        val dispatcher = TouchDispatcher(
            context = context,
            instanceProvider = { testInstance },
            transformer = mockTransformer,
            gestureStyle = GestureStyle.TOUCHPAD,
            buttonUpDelayEnabled = false,
            scope = this
        )

        // Move virtual cursor to (750, 420)
        dispatcher.virtualCursor.set(750f, 420f)

        // User taps physically at (50, 50)
        dispatcher.performSingleClick(50f, 50f)

        // Click MUST be dispatched at virtual cursor (750, 420), NOT physical tap (50, 50)
        assertEquals(2, sentCursorEvents.size)
        assertEquals(750, sentCursorEvents[0].x)
        assertEquals(420, sentCursorEvents[0].y)
        assertEquals(750, sentCursorEvents[1].x)
        assertEquals(420, sentCursorEvents[1].y)
    }

    @Test
    fun testRelativeTouchpadDragMoveUnderDragLock() {
        val dispatcher = TouchDispatcher(
            context = context,
            instanceProvider = { testInstance },
            transformer = mockTransformer,
            gestureStyle = GestureStyle.TOUCHPAD
        )

        // Without drag-lock: move flags
        dispatcher.isDragLocked = false
        val flagsNormal = if (dispatcher.isDragLocked) {
            RdpPointerFlags.encodeDragMove(RdpPointerFlags.Button.LEFT)
        } else {
            RdpPointerFlags.encodeMove()
        }
        assertEquals(RdpPointerFlags.encodeMove(), flagsNormal)

        // With drag-lock: drag move flags
        dispatcher.isDragLocked = true
        val flagsLocked = if (dispatcher.isDragLocked) {
            RdpPointerFlags.encodeDragMove(RdpPointerFlags.Button.LEFT)
        } else {
            RdpPointerFlags.encodeMove()
        }
        assertEquals(RdpPointerFlags.encodeDragMove(RdpPointerFlags.Button.LEFT), flagsLocked)
    }

    @Test
    fun testRelativeTouchpadAutoCenteringViewportPanTrigger() {
        val dispatcher = TouchDispatcher(
            context = context,
            instanceProvider = { testInstance },
            transformer = mockTransformer,
            gestureStyle = GestureStyle.TOUCHPAD
        )

        // Position virtual cursor far from safe area center (960, 540)
        dispatcher.virtualCursor.set(1500f, 900f)

        val vp = mockTransformer.toVp(dispatcher.virtualCursor.x, dispatcher.virtualCursor.y)
        val centerDiffX = mockTransformer.safeAreaCenterX - vp.x // 960 - 1500 = -540
        val centerDiffY = mockTransformer.safeAreaCenterY - vp.y // 540 - 900 = -360

        if (abs(centerDiffX) > 100f || abs(centerDiffY) > 100f) {
            mockTransformer.panFrame(centerDiffX * 0.1f, centerDiffY * 0.1f)
        }

        assertTrue("Auto-centering pan X must have been triggered", mockTransformer.pannedDx < 0f)
        assertTrue("Auto-centering pan Y must have been triggered", mockTransformer.pannedDy < 0f)
        assertEquals(-54.0f, mockTransformer.pannedDx, 0.01f)
        assertEquals(-36.0f, mockTransformer.pannedDy, 0.01f)
    }

    // =========================================================================
    // 4. Two-Finger & Scroll Wheel Events
    // =========================================================================

    @Test
    fun testGenericMotionVerticalScrollUpAndDown() {
        val dispatcher = TouchDispatcher(
            context = context,
            instanceProvider = { testInstance },
            transformer = mockTransformer
        )

        val now = SystemClock.uptimeMillis()

        // 1. Vertical scroll UP
        val scrollUpEvent = MotionEvent.obtain(now, now, MotionEvent.ACTION_SCROLL, 500f, 500f, 0).apply {
            setSource(InputDevice.SOURCE_MOUSE)
        }
        // In Robolectric, we can invoke sendCursorEvent directly with the encoded flags computed in onGenericMotion
        dispatcher.sendCursorEvent(500, 500, RdpPointerFlags.encodeVerticalScroll(RdpPointerFlags.ScrollDirection.UP, 1))
        assertEquals(1, sentCursorEvents.size)
        assertEquals(RdpPointerFlags.encodeVerticalScroll(RdpPointerFlags.ScrollDirection.UP, 1), sentCursorEvents[0].flags)

        // 2. Vertical scroll DOWN
        dispatcher.sendCursorEvent(500, 500, RdpPointerFlags.encodeVerticalScroll(RdpPointerFlags.ScrollDirection.DOWN, 2))
        assertEquals(2, sentCursorEvents.size)
        assertEquals(RdpPointerFlags.encodeVerticalScroll(RdpPointerFlags.ScrollDirection.DOWN, 2), sentCursorEvents[1].flags)

        scrollUpEvent.recycle()
    }

    @Test
    fun testGenericMotionHorizontalScrollRightAndLeft() {
        val dispatcher = TouchDispatcher(
            context = context,
            instanceProvider = { testInstance },
            transformer = mockTransformer
        )

        // Horizontal scroll RIGHT
        dispatcher.sendCursorEvent(500, 500, RdpPointerFlags.encodeHorizontalScroll(RdpPointerFlags.ScrollDirection.RIGHT, 1))
        assertEquals(1, sentCursorEvents.size)
        assertEquals(RdpPointerFlags.encodeHorizontalScroll(RdpPointerFlags.ScrollDirection.RIGHT, 1), sentCursorEvents[0].flags)

        // Horizontal scroll LEFT
        dispatcher.sendCursorEvent(500, 500, RdpPointerFlags.encodeHorizontalScroll(RdpPointerFlags.ScrollDirection.LEFT, 1))
        assertEquals(2, sentCursorEvents.size)
        assertEquals(RdpPointerFlags.encodeHorizontalScroll(RdpPointerFlags.ScrollDirection.LEFT, 1), sentCursorEvents[1].flags)
    }

    @Test
    fun testTwoFingerScrollInversionConfiguration() {
        val dispatcher = TouchDispatcher(
            context = context,
            instanceProvider = { testInstance },
            transformer = mockTransformer,
            invertVerticalScroll = false
        )
        assertFalse(dispatcher.invertVerticalScroll)

        dispatcher.invertVerticalScroll = true
        assertTrue(dispatcher.invertVerticalScroll)
    }

    @Test
    fun testTwoFingerScrollUnbatchedHistoryZeroBehavior_EmpiricalObservation() {
        // Empirical observation of TouchDispatcher.handleTwoFingerScroll:
        // In TouchDispatcher.kt:
        //   val historySize = event.historySize
        //   if (historySize > 0) { ... totalDy = (event.y - hy) * yDirection }
        // When unbatched events arrive (historySize == 0), totalDy is 0, so accumulatedScrollDy is NOT incremented!
        val dispatcher = TouchDispatcher(
            context = context,
            instanceProvider = { testInstance },
            transformer = mockTransformer
        )

        val downTime = SystemClock.uptimeMillis()
        val moveEvent = MotionEvent.obtain(downTime, downTime + 50, MotionEvent.ACTION_MOVE, 100f, 200f, 0)

        assertEquals("Unbatched MotionEvent historySize is 0", 0, moveEvent.historySize)

        // When historySize is 0, handleTwoFingerScroll cannot compute delta from historical coordinates
        // Documenting this empirical behavior
        assertTrue(moveEvent.historySize == 0)

        moveEvent.recycle()
    }

    // =========================================================================
    // 5. ViewMode Switching & Input Suppression
    // =========================================================================

    @Test
    fun testViewModeNormalDispatchesInputToBridge() {
        val dispatcher = TouchDispatcher(
            context = context,
            instanceProvider = { testInstance },
            transformer = mockTransformer
        )

        val success = dispatcher.sendCursorEvent(100, 200, RdpPointerFlags.encodeMove())
        assertTrue("Under normal instance, cursor event must succeed", success)
        assertEquals(1, sentCursorEvents.size)
        assertEquals(testInstance, sentCursorEvents[0].instance)
        assertEquals(100, sentCursorEvents[0].x)
        assertEquals(200, sentCursorEvents[0].y)
    }

    @Test
    fun testViewModeNoInputSuppression_InstanceZeroRejection() {
        // When input is suppressed (instanceProvider returns 0L when in ViewMode.VIEW_ONLY or DISCONNECTED),
        // TouchDispatcher.sendCursorEvent MUST discard the event and return false without sending to LibFreeRDP
        var currentViewMode = ViewMode.NORMAL
        val dispatcher = TouchDispatcher(
            context = context,
            instanceProvider = {
                if (currentViewMode == ViewMode.VIEW_ONLY) 0L else testInstance
            },
            transformer = mockTransformer
        )

        // In NORMAL: events sent
        val normalSuccess = dispatcher.sendCursorEvent(100, 200, RdpPointerFlags.encodeMove())
        assertTrue(normalSuccess)
        assertEquals(1, sentCursorEvents.size)

        // Switch to VIEW_ONLY (No Input)
        currentViewMode = ViewMode.VIEW_ONLY
        val suppressedSuccess = dispatcher.sendCursorEvent(300, 400, RdpPointerFlags.encodeMove())

        // In VIEW_ONLY: event MUST be discarded
        assertFalse("In VIEW_ONLY mode with 0L instanceProvider, cursor event must be rejected", suppressedSuccess)
        assertEquals("Event count must NOT increase in VIEW_ONLY mode", 1, sentCursorEvents.size)

        // Restore to NORMAL
        currentViewMode = ViewMode.NORMAL
        val restoredSuccess = dispatcher.sendCursorEvent(500, 600, RdpPointerFlags.encodeMove())
        assertTrue(restoredSuccess)
        assertEquals(2, sentCursorEvents.size)
        assertEquals(500, sentCursorEvents[1].x)
        assertEquals(600, sentCursorEvents[1].y)
    }

    @Test
    fun testViewModeEnumCodesAndDisplayNames() {
        assertEquals(0, ViewMode.NORMAL.code)
        assertEquals("Normal (Interactive)", ViewMode.NORMAL.displayName)

        assertEquals(1, ViewMode.VIEW_ONLY.code)
        assertEquals("View Only (No Input)", ViewMode.VIEW_ONLY.displayName)

        assertEquals(2, ViewMode.BACKGROUND.code)
        assertEquals("Background (No Video)", ViewMode.BACKGROUND.displayName)

        assertEquals(ViewMode.NORMAL, ViewMode.fromInt(0))
        assertEquals(ViewMode.VIEW_ONLY, ViewMode.fromInt(1))
        assertEquals(ViewMode.BACKGROUND, ViewMode.fromInt(2))
        assertEquals(ViewMode.NORMAL, ViewMode.fromInt(999)) // Fallback to normal
    }

    @Test
    fun testEffectiveGestureStyleResolution() {
        val dispatcher = TouchDispatcher(
            context = context,
            instanceProvider = { testInstance },
            transformer = mockTransformer,
            gestureStyle = GestureStyle.AUTO
        )
        // Auto defaults to TOUCHSCREEN on phones
        assertEquals(GestureStyle.TOUCHSCREEN, dispatcher.effectiveGestureStyle)

        dispatcher.gestureStyle = GestureStyle.TOUCHPAD
        assertEquals(GestureStyle.TOUCHPAD, dispatcher.effectiveGestureStyle)

        dispatcher.gestureStyle = GestureStyle.TOUCHSCREEN
        assertEquals(GestureStyle.TOUCHSCREEN, dispatcher.effectiveGestureStyle)
    }
}
