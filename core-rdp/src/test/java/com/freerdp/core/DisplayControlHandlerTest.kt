package com.freerdp.core

import com.freerdp.core.protocol.DisplayControlHandler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DisplayControlHandlerTest {

    @Test
    fun testAlignDimensionMultiplesOf4AndMinimums() {
        // Enforces minimum width of 640
        assertEquals(640, DisplayControlHandler.alignDimension(100, min = 640))
        assertEquals(640, DisplayControlHandler.alignDimension(639, min = 640))

        // Aligns to multiple of 4
        assertEquals(1920, DisplayControlHandler.alignDimension(1920, min = 640))
        assertEquals(1920, DisplayControlHandler.alignDimension(1923, min = 640))
        assertEquals(1076, DisplayControlHandler.alignDimension(1079, min = 640))
        assertEquals(2396, DisplayControlHandler.alignDimension(2399, min = 640))
    }

    @Test
    fun testCalculatePhysicalDimensionMm() {
        // 1920 px at 160 dpi -> 12 inches * 25.4 mm = 304.8 mm -> 304
        val mm = DisplayControlHandler.calculatePhysicalDimensionMm(1920, 160f)
        assertEquals(304, mm)

        // 1080 px at 320 dpi -> 3.375 inches * 25.4 mm = 85.725 mm -> 85
        val mm2 = DisplayControlHandler.calculatePhysicalDimensionMm(1080, 320f)
        assertEquals(85, mm2)

        // Non-positive dpi guard
        assertEquals(0, DisplayControlHandler.calculatePhysicalDimensionMm(1080, 0f))
        assertEquals(0, DisplayControlHandler.calculatePhysicalDimensionMm(1080, -10f))
    }

    @Test
    fun testImmediateDispatch() {
        var receivedLayout: DisplayControlHandler.MonitorLayout? = null
        val handler = DisplayControlHandler(debounceDelayMs = 250L) { layout ->
            receivedLayout = layout
        }

        handler.requestLayoutUpdate(width = 1920, height = 1080, dpi = 160f, immediate = true)

        assertNotNull(receivedLayout)
        assertEquals(1920, receivedLayout?.width)
        assertEquals(1080, receivedLayout?.height)
        assertEquals(304, receivedLayout?.physicalWidthMm)
        assertEquals(171, receivedLayout?.physicalHeightMm)
        assertEquals(DisplayControlHandler.MonitorLayout.ORIENTATION_LANDSCAPE, receivedLayout?.orientation)
    }

    @Test
    fun testDebouncingCoalescesRapidResizeCalls() = runTest {
        val layoutsReceived = mutableListOf<DisplayControlHandler.MonitorLayout>()
        val handler = DisplayControlHandler(
            debounceDelayMs = 250L,
            scope = backgroundScope
        ) { layout ->
            layoutsReceived.add(layout)
        }

        // Send 3 rapid resize requests
        handler.requestLayoutUpdate(width = 1000, height = 2000, dpi = 160f)
        advanceTimeBy(50)
        handler.requestLayoutUpdate(width = 1200, height = 2200, dpi = 160f)
        advanceTimeBy(50)
        handler.requestLayoutUpdate(width = 1400, height = 2400, dpi = 160f)

        // No dispatch should have occurred yet before 250ms
        assertEquals(0, layoutsReceived.size)

        // Advance by remaining 250ms + margin to trigger debounced execution
        advanceTimeBy(260)
        runCurrent()

        // Exactly one layout should be received, representing the latest dimensions
        assertEquals(1, layoutsReceived.size)
        assertEquals(1400, layoutsReceived.first().width)
        assertEquals(2400, layoutsReceived.first().height)
        assertEquals(DisplayControlHandler.MonitorLayout.ORIENTATION_PORTRAIT, layoutsReceived.first().orientation)
    }

    @Test
    fun testCancelPendingDebounce() = runTest {
        var dispatched = false
        val handler = DisplayControlHandler(debounceDelayMs = 250L, scope = backgroundScope) {
            dispatched = true
        }

        handler.requestLayoutUpdate(width = 1920, height = 1080)
        advanceTimeBy(100)
        handler.cancelPending()
        advanceTimeBy(300)
        runCurrent()

        assertEquals(false, dispatched)
    }
}
