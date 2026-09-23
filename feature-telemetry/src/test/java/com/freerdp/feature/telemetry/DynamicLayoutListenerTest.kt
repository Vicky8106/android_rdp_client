package com.freerdp.feature.telemetry

import com.freerdp.core.engine.MockRdpEngine
import com.freerdp.feature.telemetry.display.DynamicLayoutListener
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DynamicLayoutListenerTest {

    private lateinit var mockEngine: MockRdpEngine

    @Before
    fun setUp() {
        mockEngine = MockRdpEngine()
    }

    @Test
    fun testImmediateLayoutResizeDispatchesPdu() = runTest {
        val listener = DynamicLayoutListener(
            engine = mockEngine,
            coroutineScope = this,
            debounceDelayMs = 200L
        )

        listener.onLayoutChanged(
            newWidth = 1920,
            newHeight = 1080,
            physicalWidthMm = 344,
            physicalHeightMm = 193,
            orientation = 0,
            immediate = true
        )

        assertEquals(1, mockEngine.recordedResolutions.size)
        val event = mockEngine.recordedResolutions[0]
        assertEquals(1920, event.width)
        assertEquals(1080, event.height)
        assertEquals(344, event.physicalWidthMm)
        assertEquals(193, event.physicalHeightMm)
    }

    @Test
    fun testDebouncedOrientationChangesCoalesce() = runTest {
        val listener = DynamicLayoutListener(
            engine = mockEngine,
            coroutineScope = this,
            debounceDelayMs = 200L
        )

        // Rapid stream of resize events (e.g. fold/unfold animation)
        listener.onLayoutChanged(1080, 1920, 100, 200, 0, immediate = false)
        advanceTimeBy(50L)
        listener.onLayoutChanged(1200, 1800, 110, 190, 0, immediate = false)
        advanceTimeBy(50L)
        listener.onLayoutChanged(1400, 1600, 130, 170, 0, immediate = false)
        advanceTimeBy(50L)
        listener.onLayoutChanged(2200, 1400, 200, 150, 1, immediate = false)

        // Still within debounce delay: no PDU should be sent yet
        assertEquals(0, mockEngine.recordedResolutions.size)

        // Advance beyond debounce delay
        advanceTimeBy(250L)
        advanceUntilIdle()

        // Only the final stable layout should be sent
        assertEquals(1, mockEngine.recordedResolutions.size)
        val finalEvent = mockEngine.recordedResolutions[0]
        assertEquals(2200, finalEvent.width)
        assertEquals(1400, finalEvent.height)
        assertEquals(1, finalEvent.orientation)
    }

    @Test
    fun testDuplicateResolutionsIgnored() = runTest {
        val listener = DynamicLayoutListener(
            engine = mockEngine,
            coroutineScope = this,
            debounceDelayMs = 0L // Immediate mode
        )

        listener.onLayoutChanged(1080, 2400, orientation = 0)
        listener.onLayoutChanged(1080, 2400, orientation = 0)
        listener.onLayoutChanged(1080, 2400, orientation = 0)

        assertEquals("Identical duplicate layout changes must be suppressed", 1, mockEngine.recordedResolutions.size)
    }

    @Test
    fun testWindowSizeWithDpiCalculatesPhysicalDimensions() = runTest {
        val listener = DynamicLayoutListener(
            engine = mockEngine,
            coroutineScope = this,
            debounceDelayMs = 0L
        )

        // 1080px at 160 DPI -> (1080 * 25.4) / 160 = 171.45 -> 171 mm
        listener.onWindowSizeChanged(
            widthPx = 1080,
            heightPx = 1920,
            densityDpi = 160,
            orientation = 0,
            immediate = true
        )

        assertEquals(1, mockEngine.recordedResolutions.size)
        val event = mockEngine.recordedResolutions[0]
        assertEquals(171, event.physicalWidthMm)
        assertEquals(304, event.physicalHeightMm)
    }

    // ---- Boundary / debounce tests (F: dynamic layout, Tier-2 style) ----

    @Test
    fun testDefaultDebounceIsExactly250ms() = runTest {
        // Default construction — no explicit debounceDelayMs (spec: 250 ms window).
        val listener = DynamicLayoutListener(engine = mockEngine, coroutineScope = this)
        assertEquals(250L, listener.debounceDelayMs)

        listener.onLayoutChanged(1080, 1920, orientation = 0)     // t = 0
        advanceTimeBy(200L)                                       // t = 200
        listener.onLayoutChanged(2200, 1080, orientation = 1)     // restarts debounce at t=200

        // Window closes strictly at t = 200 + 250 = 450ms.
        advanceTimeBy(249L)                                       // t = 449: not yet
        assertEquals("Must not dispatch 1ms before the 250ms window closes", 0, mockEngine.recordedResolutions.size)

        advanceTimeBy(1L)                                         // t = 450: fire
        advanceUntilIdle()
        assertEquals(1, mockEngine.recordedResolutions.size)
        assertEquals(2200, mockEngine.recordedResolutions[0].width)
        assertEquals(1, mockEngine.recordedResolutions[0].orientation)
    }

    @Test
    fun testZeroAndNegativeDimensionsAreRejected() = runTest {
        val listener = DynamicLayoutListener(
            engine = mockEngine,
            coroutineScope = this,
            debounceDelayMs = 0L
        )

        listener.onLayoutChanged(0, 1080, immediate = true)
        listener.onLayoutChanged(1920, 0, immediate = true)
        listener.onLayoutChanged(-1920, 1080, immediate = true)
        listener.onLayoutChanged(1920, -1080, immediate = true)
        listener.onWindowSizeChanged(widthPx = 0, heightPx = 1920, densityDpi = 320, immediate = true)

        assertEquals(
            "Degenerate dimensions must never produce a resolution PDU",
            0,
            mockEngine.recordedResolutions.size
        )

        listener.onLayoutChanged(1920, 1080, immediate = true)
        assertEquals(1, mockEngine.recordedResolutions.size)
    }

    @Test
    fun testCancelPendingPreventsDispatch() = runTest {
        val listener = DynamicLayoutListener(
            engine = mockEngine,
            coroutineScope = this,
            debounceDelayMs = 250L
        )

        listener.onLayoutChanged(1080, 1920, immediate = false)
        advanceTimeBy(100L)
        listener.cancelPending() // e.g. app backgrounded mid-resize

        advanceTimeBy(10_000L)
        advanceUntilIdle()

        assertEquals(
            "Cancelled debounce must never reach the engine",
            0,
            mockEngine.recordedResolutions.size
        )
        assertEquals(0, listener.resizeEventsCount)
    }

    @Test
    fun testRapidOrientationFlappingCoalescesToFinalState() = runTest {
        val listener = DynamicLayoutListener(
            engine = mockEngine,
            coroutineScope = this,
            debounceDelayMs = 250L
        )

        // 10 orientation flips at 30ms intervals (rotation animation).
        repeat(10) { i ->
            listener.onLayoutChanged(1080, 1920, orientation = i % 2)
            advanceTimeBy(30L)
        }

        advanceTimeBy(300L)
        advanceUntilIdle()

        assertEquals(
            "All intermediate flips must coalesce into a single PDU",
            1,
            mockEngine.recordedResolutions.size
        )
        val finalEvent = mockEngine.recordedResolutions[0]
        assertEquals(9 % 2, finalEvent.orientation)
        assertEquals(1, listener.resizeEventsCount)
    }

    @Test
    fun testDebounceZeroAppliesSynchronously() = runTest {
        val listener = DynamicLayoutListener(
            engine = mockEngine,
            coroutineScope = this,
            debounceDelayMs = 0L
        )

        listener.onLayoutChanged(800, 600, orientation = 2)
        // No time advance: debounce<=0 must apply inline.
        assertEquals(1, mockEngine.recordedResolutions.size)
        assertEquals(2, mockEngine.recordedResolutions[0].orientation)
    }
}
