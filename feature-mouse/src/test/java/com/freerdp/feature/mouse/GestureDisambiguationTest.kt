package com.freerdp.feature.mouse

import android.os.SystemClock
import android.view.MotionEvent
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
class GestureDisambiguationTest {

    private lateinit var engine: GestureDisambiguationEngine
    private lateinit var recordingListener: MockGestureListener

    class MockGestureListener : GestureEventListener {
        var singleTapCount = 0
        var doubleTapCount = 0
        var longPressCount = 0
        var panCount = 0
        var panEndCount = 0
        var pinchZoomCount = 0
        var twoFingerScrollCount = 0
        var cancelCount = 0

        var lastTapX = 0f
        var lastTapY = 0f
        var lastPinchScale = 1.0f

        override fun onSingleTap(screenX: Float, screenY: Float) {
            singleTapCount++
            lastTapX = screenX
            lastTapY = screenY
        }

        override fun onDoubleTap(screenX: Float, screenY: Float) {
            doubleTapCount++
            lastTapX = screenX
            lastTapY = screenY
        }

        override fun onLongPress(screenX: Float, screenY: Float) {
            longPressCount++
        }

        override fun onPan(deltaX: Float, deltaY: Float) {
            panCount++
        }

        override fun onPanEnd() {
            panEndCount++
        }

        override fun onPinchZoom(focusX: Float, focusY: Float, scaleFactor: Float) {
            pinchZoomCount++
            lastPinchScale = scaleFactor
        }

        override fun onTwoFingerScroll(deltaX: Float, deltaY: Float) {
            twoFingerScrollCount++
        }

        override fun onCancel() {
            cancelCount++
        }
    }

    @Before
    fun setUp() {
        recordingListener = MockGestureListener()
        engine = GestureDisambiguationEngine(
            touchSlop = 16f,
            doubleTapSlop = 48f,
            longPressTimeoutMs = 500L,
            doubleTapTimeoutMs = 300L,
            handler = null, // Test without real async delay
            listener = recordingListener
        )
    }

    private fun createSingleTouchEvent(action: Int, x: Float, y: Float, downTime: Long, eventTime: Long): MotionEvent {
        return MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
    }

    private fun createMultiTouchEvent(
        action: Int,
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        downTime: Long,
        eventTime: Long
    ): MotionEvent {
        val pp0 = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
        val pp1 = MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER }
        val pc0 = MotionEvent.PointerCoords().apply { x = x0; y = y0 }
        val pc1 = MotionEvent.PointerCoords().apply { x = x1; y = y1 }

        return MotionEvent.obtain(
            downTime,
            eventTime,
            action,
            2,
            arrayOf(pp0, pp1),
            arrayOf(pc0, pc1),
            0,
            0,
            1.0f,
            1.0f,
            0,
            0,
            0,
            0
        )
    }

    @Test
    fun testSingleTapDetection() {
        val now = SystemClock.uptimeMillis()
        val down = createSingleTouchEvent(MotionEvent.ACTION_DOWN, 200f, 300f, now, now)
        val up = createSingleTouchEvent(MotionEvent.ACTION_UP, 201f, 301f, now, now + 50)

        engine.onTouchEvent(down)
        engine.onTouchEvent(up)

        assertEquals(1, recordingListener.singleTapCount)
        assertEquals(0, recordingListener.doubleTapCount)
        assertEquals(0, recordingListener.panCount)
        assertEquals(201f, recordingListener.lastTapX, 0.01f)
        assertEquals(301f, recordingListener.lastTapY, 0.01f)
    }

    @Test
    fun testPanWithoutClickingWhenMovementExceedsTouchSlop() {
        val now = SystemClock.uptimeMillis()
        val down = createSingleTouchEvent(MotionEvent.ACTION_DOWN, 100f, 100f, now, now)
        // Move by 30px (exceeds 16px touch slop)
        val move = createSingleTouchEvent(MotionEvent.ACTION_MOVE, 130f, 100f, now, now + 40)
        val up = createSingleTouchEvent(MotionEvent.ACTION_UP, 130f, 100f, now, now + 100)

        engine.onTouchEvent(down)
        engine.onTouchEvent(move)
        assertTrue(engine.isPanning)

        engine.onTouchEvent(up)

        // Must emit pan and panEnd, but ZERO taps/clicks!
        assertTrue(recordingListener.panCount > 0)
        assertEquals(1, recordingListener.panEndCount)
        assertEquals(0, recordingListener.singleTapCount)
        assertEquals(0, recordingListener.doubleTapCount)
    }

    @Test
    fun testDoubleTapDetection() {
        val now = SystemClock.uptimeMillis()
        // Tap 1
        val down1 = createSingleTouchEvent(MotionEvent.ACTION_DOWN, 150f, 150f, now, now)
        val up1 = createSingleTouchEvent(MotionEvent.ACTION_UP, 150f, 150f, now, now + 40)
        engine.onTouchEvent(down1)
        engine.onTouchEvent(up1)
        assertEquals(1, recordingListener.singleTapCount)

        // Tap 2 within 100ms
        val down2 = createSingleTouchEvent(MotionEvent.ACTION_DOWN, 152f, 151f, now, now + 100)
        val up2 = createSingleTouchEvent(MotionEvent.ACTION_UP, 152f, 151f, now, now + 140)
        engine.onTouchEvent(down2)
        engine.onTouchEvent(up2)

        assertEquals(1, recordingListener.doubleTapCount)
    }

    @Test
    fun testLongPressFiresOnceAbortedByMoveAndSuppressesTapOnRelease() {
        // Renamed+strengthened from testLongPressTriggersRightClickAndIsAbortedOnMove
        // (reviewer_w2 F6): the old name claimed a right-click dispatch and a move abort
        // that this module cannot / did not assert. feature-mouse's contract is the
        // onLongPress CALLBACK plus abort semantics — the long-press → right-click
        // mapping itself lives app-side (RemoteCanvasView.onLongPress →
        // mouseController.handleRightClick) and is not observable from this module.
        val now = SystemClock.uptimeMillis()

        // Part A — long-press fires exactly once; releasing afterwards emits no tap.
        val down = createSingleTouchEvent(MotionEvent.ACTION_DOWN, 300f, 300f, now, now)
        engine.onTouchEvent(down)

        engine.triggerPendingLongPress()
        assertEquals("long-press callback must fire exactly once", 1, recordingListener.longPressCount)
        assertTrue(engine.isLongPressTriggered)

        val up = createSingleTouchEvent(MotionEvent.ACTION_UP, 300f, 300f, now, now + 600)
        engine.onTouchEvent(up)
        assertEquals("release after long-press must not emit a tap", 0, recordingListener.singleTapCount)

        // Part B — a move beyond touch slop ABORTS the pending long press: the armed
        // runnable must no longer fire when its trigger is pumped.
        val down2 = createSingleTouchEvent(MotionEvent.ACTION_DOWN, 100f, 100f, now, now + 700)
        engine.onTouchEvent(down2)
        val panMove = createSingleTouchEvent(MotionEvent.ACTION_MOVE, 170f, 100f, now, now + 740)
        engine.onTouchEvent(panMove)
        assertTrue("move beyond slop starts the pan (and cancels the long press)", engine.isPanning)

        engine.triggerPendingLongPress() // the armed timer fires late — must be suppressed
        assertEquals(
            "move must abort the pending long press — no NEW long-press callback",
            1,
            recordingListener.longPressCount
        )
        assertFalse(engine.isLongPressTriggered)

        val up2 = createSingleTouchEvent(MotionEvent.ACTION_UP, 170f, 100f, now, now + 800)
        engine.onTouchEvent(up2)
        assertEquals("aborted long-press gesture ends as a pan, never a tap", 0, recordingListener.singleTapCount)
        assertEquals(1, recordingListener.panEndCount)
    }

    /**
     * reviewer_w2 F4: onPanEnd used to be dropped when the multi-touch latch released
     * an active pan — consumers tracking pan sessions never saw end-of-pan. The latch
     * must still suppress the spurious tap on the same lift (don't regress the latch).
     */
    @Test
    fun testPanEndFiresAfterLatchReleaseWhileLatchStillSuppressesTap() {
        val now = SystemClock.uptimeMillis()

        // 1. Finger down, then pan beyond touch slop (pan session is open).
        engine.onTouchEvent(createSingleTouchEvent(MotionEvent.ACTION_DOWN, 100f, 100f, now, now))
        engine.onTouchEvent(createSingleTouchEvent(MotionEvent.ACTION_MOVE, 160f, 140f, now, now + 40))
        assertTrue(engine.isPanning)
        assertTrue(recordingListener.panCount > 0)
        assertEquals("pan not yet ended", 0, recordingListener.panEndCount)

        // 2. Second finger lands: latch engages mid-pan.
        val pointerDown = createMultiTouchEvent(
            MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            160f, 140f, 300f, 300f, now, now + 60
        )
        engine.onTouchEvent(pointerDown)
        assertTrue(engine.isMultiTouchLatched)

        // 3. Finger 0 lifts — terminal ACTION_UP takes the latch branch.
        engine.onTouchEvent(createSingleTouchEvent(MotionEvent.ACTION_UP, 160f, 140f, now, now + 100))

        assertEquals(
            "latch release of an active pan must dispatch exactly one onPanEnd",
            1,
            recordingListener.panEndCount
        )
        // The latch contract is untouched: no spurious tap on the multi-finger lift.
        assertEquals("latch must still suppress the spurious single tap", 0, recordingListener.singleTapCount)
        assertEquals("latch must still suppress the spurious double tap", 0, recordingListener.doubleTapCount)
        assertFalse("latch resets after terminal ACTION_UP", engine.isMultiTouchLatched)
        assertFalse(engine.isPanning)
    }

    /**
     * Non-pan latch lifts must NOT invent an onPanEnd (no pan session was ever open).
     */
    @Test
    fun testLatchReleaseWithoutPanDoesNotEmitPanEnd() {
        val now = SystemClock.uptimeMillis()
        engine.onTouchEvent(createSingleTouchEvent(MotionEvent.ACTION_DOWN, 200f, 200f, now, now))
        val pointerDown = createMultiTouchEvent(
            MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            200f, 200f, 400f, 400f, now, now + 20
        )
        engine.onTouchEvent(pointerDown)
        assertTrue(engine.isMultiTouchLatched)

        engine.onTouchEvent(createSingleTouchEvent(MotionEvent.ACTION_UP, 200f, 200f, now, now + 60))

        assertEquals("no pan session → no panEnd", 0, recordingListener.panEndCount)
        assertEquals(0, recordingListener.singleTapCount)
    }

    @Test
    fun testPinchToZoomGesture() {
        val now = SystemClock.uptimeMillis()
        val down = createSingleTouchEvent(MotionEvent.ACTION_DOWN, 200f, 200f, now, now)
        engine.onTouchEvent(down)

        val pointerDown = createMultiTouchEvent(
            MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            200f, 200f, 300f, 300f, now, now + 10
        )
        engine.onTouchEvent(pointerDown)
        assertTrue(engine.isMultiTouchLatched)

        // Move fingers apart
        val moveApart = createMultiTouchEvent(
            MotionEvent.ACTION_MOVE,
            150f, 150f, 350f, 350f, now, now + 30
        )
        engine.onTouchEvent(moveApart)

        assertTrue(recordingListener.pinchZoomCount > 0)
        assertTrue(recordingListener.lastPinchScale > 1.0f) // Scaled up
    }

    /**
     * CRUCIAL ACCEPTANCE TEST (R2.3 / Edge Case 13):
     * Verifies that multiTouchLatch eliminates spurious click triggers
     * when fingers lift sequentially from a multi-touch pinch/pan gesture.
     */
    @Test
    fun testMultiTouchLatchEliminatesSpuriousClickOnSequentialFingerLift() {
        val now = SystemClock.uptimeMillis()

        // 1. First finger down
        val down = createSingleTouchEvent(MotionEvent.ACTION_DOWN, 200f, 200f, now, now)
        engine.onTouchEvent(down)
        assertFalse(engine.isMultiTouchLatched)

        // 2. Second finger down (multi-touch begins)
        val pointerDown = createMultiTouchEvent(
            MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            200f, 200f, 400f, 400f, now, now + 20
        )
        engine.onTouchEvent(pointerDown)
        assertTrue("multiTouchLatch must activate on ACTION_POINTER_DOWN", engine.isMultiTouchLatched)

        // 3. Move both fingers
        val move = createMultiTouchEvent(
            MotionEvent.ACTION_MOVE,
            210f, 210f, 410f, 410f, now, now + 40
        )
        engine.onTouchEvent(move)
        assertTrue("multiTouchLatch must remain true during multi-touch move", engine.isMultiTouchLatched)

        // 4. Finger 1 lifts (ACTION_POINTER_UP)
        val pointerUp = createMultiTouchEvent(
            MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            210f, 210f, 410f, 410f, now, now + 60
        )
        engine.onTouchEvent(pointerUp)
        assertTrue(
            "multiTouchLatch MUST remain active after ACTION_POINTER_UP to prevent phantom click on finger 0 lift",
            engine.isMultiTouchLatched
        )

        // 5. Finger 0 lifts 25ms later (ACTION_UP)
        val finalUp = createSingleTouchEvent(MotionEvent.ACTION_UP, 210f, 210f, now, now + 85)
        engine.onTouchEvent(finalUp)

        // Assert: Latch suppressed the tap, count must be strictly 0!
        assertEquals("Single tap MUST be suppressed on multi-touch release!", 0, recordingListener.singleTapCount)
        assertEquals("Double tap MUST be suppressed on multi-touch release!", 0, recordingListener.doubleTapCount)
        assertFalse("multiTouchLatch must reset after terminal ACTION_UP", engine.isMultiTouchLatched)
    }

    @Test
    fun testActionCancelResetsState() {
        val now = SystemClock.uptimeMillis()
        val down = createSingleTouchEvent(MotionEvent.ACTION_DOWN, 100f, 100f, now, now)
        engine.onTouchEvent(down)

        val cancel = createSingleTouchEvent(MotionEvent.ACTION_CANCEL, 100f, 100f, now, now + 50)
        engine.onTouchEvent(cancel)

        assertEquals(1, recordingListener.cancelCount)
        assertFalse(engine.isMultiTouchLatched)
        assertFalse(engine.isPanning)
        assertEquals(0, recordingListener.singleTapCount)
    }
}
