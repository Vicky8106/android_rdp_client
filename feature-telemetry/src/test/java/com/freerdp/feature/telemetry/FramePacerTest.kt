package com.freerdp.feature.telemetry

import android.graphics.Bitmap
import com.freerdp.feature.telemetry.pacer.FramePacer
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class FramePacerTest {

    private lateinit var framePacer: FramePacer

    // Thread-safe: the drop callback fires from concurrent decoder threads.
    private val droppedBitmaps: MutableList<Bitmap> = Collections.synchronizedList(mutableListOf())

    @Before
    fun setUp() {
        droppedBitmaps.clear()
        framePacer = FramePacer(onFrameDroppedCallback = { dropped ->
            droppedBitmaps.add(dropped)
        })
    }

    @Test
    fun testSingleSlotFrameReplacementDropsStaleFrame() {
        val bitmap1 = mockk<Bitmap>()
        val bitmap2 = mockk<Bitmap>()

        val droppedFirst = framePacer.onFrameDecoded(bitmap1)
        assertFalse("First frame decoded should not drop anything", droppedFirst)
        assertEquals(1L, framePacer.totalDecoded)
        assertEquals(0L, framePacer.totalDropped)
        assertTrue("Pacer should have pending frame", framePacer.hasPendingFrame)

        // Decode second frame before first is acquired -> bitmap1 is stale and dropped
        val droppedSecond = framePacer.onFrameDecoded(bitmap2)
        assertTrue("Second frame decoded without render should drop previous frame", droppedSecond)
        assertEquals(2L, framePacer.totalDecoded)
        assertEquals(1L, framePacer.totalDropped)
        assertEquals(1, droppedBitmaps.size)
        assertSame("Dropped bitmap must be bitmap1", bitmap1, droppedBitmaps[0])

        // Acquire frame for rendering -> should deliver bitmap2
        val rendered = framePacer.acquireFrameForRendering()
        assertNotNull(rendered)
        assertSame("Renderer must receive freshest frame (bitmap2)", bitmap2, rendered)
        assertEquals(1L, framePacer.totalRendered)
        assertFalse("Pacer slot should now be empty", framePacer.hasPendingFrame)
    }

    @Test
    fun testNewestFrameAlwaysDeliveredOnRender() {
        val bitmaps = List(10) { mockk<Bitmap>() }

        // Rapid decoder burst of 10 frames
        for (bmp in bitmaps) {
            framePacer.onFrameDecoded(bmp)
        }

        assertEquals(10L, framePacer.totalDecoded)
        assertEquals(9L, framePacer.totalDropped)
        assertEquals(9, droppedBitmaps.size)

        // Renderer consumes slot on next VSYNC
        val consumed = framePacer.acquireFrameForRendering()
        assertSame("Renderer must consume the 10th (newest) frame", bitmaps[9], consumed)
        assertEquals(1L, framePacer.totalRendered)

        // Subsequent render on empty slot returns null (zero spurious renders)
        val nextRender = framePacer.acquireFrameForRendering()
        assertNull("Subsequent render on empty slot must return null", nextRender)

        assertEquals(0.9f, framePacer.dropRatio, 0.001f)
    }

    @Test
    fun testAcquireOnEmptySlotReturnsNull() {
        val frame = framePacer.acquireFrameForRendering()
        assertNull(frame)
        assertEquals(0L, framePacer.totalRendered)
        assertEquals(0L, framePacer.totalDropped)
    }

    @Test
    fun testPacerReset() {
        val bmp1 = mockk<Bitmap>()
        val bmp2 = mockk<Bitmap>()
        framePacer.onFrameDecoded(bmp1)
        framePacer.onFrameDecoded(bmp2)

        framePacer.reset()

        assertEquals(0L, framePacer.totalDecoded)
        assertEquals(0L, framePacer.totalRendered)
        assertEquals(0L, framePacer.totalDropped)
        assertFalse(framePacer.hasPendingFrame)
        assertNull(framePacer.acquireFrameForRendering())
    }

    @Test
    fun testConcurrentDecodersAndRendererThreadSafety() {
        val numDecoders = 4
        val framesPerDecoder = 250
        val executor = Executors.newFixedThreadPool(numDecoders + 1)
        val latch = CountDownLatch(numDecoders + 1)
        val renderLoopRunning = java.util.concurrent.atomic.AtomicBoolean(true)
        val renderedCount = AtomicInteger(0)

        // 1 Renderer thread continuously acquiring frames
        executor.submit {
            while (renderLoopRunning.get()) {
                val f = framePacer.acquireFrameForRendering()
                if (f != null) {
                    renderedCount.incrementAndGet()
                }
                Thread.yield()
            }
            // Drain remaining
            val finalFrame = framePacer.acquireFrameForRendering()
            if (finalFrame != null) {
                renderedCount.incrementAndGet()
            }
            latch.countDown()
        }

        // 4 Decoder threads pushing frames concurrently
        for (i in 0 until numDecoders) {
            executor.submit {
                for (j in 0 until framesPerDecoder) {
                    val bmp = mockk<Bitmap>()
                    framePacer.onFrameDecoded(bmp)
                }
                latch.countDown()
            }
        }

        // Wait for decoders to finish
        Thread.sleep(200)
        renderLoopRunning.set(false)
        val completed = latch.await(5, TimeUnit.SECONDS)
        executor.shutdown()

        assertTrue("Concurrent execution completed within timeout", completed)

        val totalDecoded = framePacer.totalDecoded
        val totalRendered = framePacer.totalRendered
        val totalDropped = framePacer.totalDropped
        val pendingCount = if (framePacer.hasPendingFrame) 1 else 0

        assertEquals(
            "Total decoded (${numDecoders * framesPerDecoder}) must equal rendered + dropped + pending",
            (numDecoders * framesPerDecoder).toLong(),
            totalDecoded
        )
        assertEquals(
            "Conservation invariant: decoded == rendered + dropped + pending",
            totalDecoded,
            totalRendered + totalDropped + pendingCount
        )
    }

    // ---- Stale-frame drop boundaries (F22, Tier-2 style) ----

    @Test
    fun testStaleFrameIsDroppedNotBlitted() {
        val maxAge = 250_000_000L
        val drops = mutableListOf<Bitmap>()
        val pacer = FramePacer(onFrameDroppedCallback = { drops.add(it) }, maxFrameAgeNanos = maxAge)

        val staleBitmap = mockk<Bitmap>()
        pacer.onFrameDecoded(staleBitmap, timestampNanos = 1_000L)

        // Acquire one nanosecond past the max age => stale.
        val blit = pacer.acquireFrameForRendering(nowNanos = 1_000L + maxAge + 1L)

        assertNull("Stale frame must never be blitted", blit)
        assertEquals(1L, pacer.totalStaleDropped)
        assertEquals(1L, pacer.totalDropped)
        assertEquals(0L, pacer.totalRendered)
        assertFalse("Slot must be cleared after stale drop", pacer.hasPendingFrame)
        assertEquals(1, drops.size)
        assertSame(staleBitmap, drops[0])
    }

    @Test
    fun testFrameExactlyAtAgeThresholdIsStillFresh() {
        val maxAge = 250_000_000L
        val pacer = FramePacer(maxFrameAgeNanos = maxAge)

        val bitmap = mockk<Bitmap>()
        pacer.onFrameDecoded(bitmap, timestampNanos = 1_000L)

        // Age == maxFrameAgeNanos exactly: threshold is exclusive (>) => fresh.
        val blit = pacer.acquireFrameForRendering(nowNanos = 1_000L + maxAge)

        assertNotNull("Exact-threshold frame must still blit", blit)
        assertSame(bitmap, blit)
        assertEquals(0L, pacer.totalStaleDropped)
        assertEquals(1L, pacer.totalRendered)
    }

    @Test
    fun testFutureTimestampedFrameTreatedAsFresh() {
        val pacer = FramePacer(maxFrameAgeNanos = 250_000_000L)
        val bitmap = mockk<Bitmap>()
        // Clock-skew guard: frame timestamped in the future => negative age => fresh.
        pacer.onFrameDecoded(bitmap, timestampNanos = 10_000_000L)

        val blit = pacer.acquireFrameForRendering(nowNanos = 0L)

        assertSame(bitmap, blit)
        assertEquals(0L, pacer.totalStaleDropped)
    }

    @Test
    fun testStaleDropPreservesConservationInvariant() {
        val drops = mutableListOf<Bitmap>()
        val pacer = FramePacer(onFrameDroppedCallback = { drops.add(it) }, maxFrameAgeNanos = 100L)

        val old1 = mockk<Bitmap>()
        val old2 = mockk<Bitmap>()
        pacer.onFrameDecoded(old1, timestampNanos = 0L)      // slot: old1
        pacer.onFrameDecoded(old2, timestampNanos = 10L)     // old1 overwritten (dropped)
        pacer.acquireFrameForRendering(nowNanos = 1_000L)    // old2 stale => dropped

        // decoded(2) == rendered(0) + dropped(2) + pending(0)
        assertEquals(2L, pacer.totalDecoded)
        assertEquals(0L, pacer.totalRendered)
        assertEquals(2L, pacer.totalDropped)
        assertEquals(1L, pacer.totalStaleDropped)
        assertEquals(2, drops.size)
        assertFalse(pacer.hasPendingFrame)
        assertEquals(1.0f, pacer.dropRatio, 0.0001f)
    }

    // ---- VSYNC-aligned blit scheduling boundaries ----

    @Test
    fun testVsyncRejectsNonMonotonicTimestamps() {
        val pacer = FramePacer(vsyncPeriodNanos = 1_000_000L)
        val bitmap = mockk<Bitmap>()
        pacer.onFrameDecoded(bitmap)

        pacer.acquireFrameForVsync(1_000L) // first callback accepted
        assertThrows(IllegalArgumentException::class.java) {
            pacer.acquireFrameForVsync(1_000L) // equal timestamp: not strictly increasing
        }
        assertThrows(IllegalArgumentException::class.java) {
            pacer.acquireFrameForVsync(999L) // backwards timestamp
        }
    }

    @Test
    fun testVsyncCoalescesDuplicateCallbacksWithinSamePeriod() {
        val period = 1_000_000L
        val pacer = FramePacer(vsyncPeriodNanos = period)

        val first = mockk<Bitmap>()
        pacer.onFrameDecoded(first)
        assertSame(first, pacer.acquireFrameForVsync(0L))
        assertEquals(1L, pacer.totalVsyncBlits)

        // Second frame arrives; a duplicate callback inside the same period must NOT blit.
        val second = mockk<Bitmap>()
        pacer.onFrameDecoded(second)
        assertNull(
            "Second blit within one vsync period must be coalesced",
            pacer.acquireFrameForVsync(period / 2)
        )
        assertTrue("Frame must stay pending for the next period", pacer.hasPendingFrame)
        assertEquals(1L, pacer.totalVsyncBlits)

        // Exactly one period after the last blit: blitting allowed again.
        assertSame(second, pacer.acquireFrameForVsync(period))
        assertEquals(2L, pacer.totalVsyncBlits)
        assertFalse(pacer.hasPendingFrame)
    }

    @Test
    fun testVsyncEmptyCallbackDoesNotReserveNextPeriod() {
        val period = 1_000_000L
        val pacer = FramePacer(vsyncPeriodNanos = period)

        // Empty-slot callback: observed but no blit recorded.
        assertNull(pacer.acquireFrameForVsync(0L))
        assertEquals(1L, pacer.totalVsyncCallbacks)
        assertEquals(0L, pacer.totalVsyncBlits)

        // A frame arriving before the next period must still blit immediately —
        // an empty callback must not lock out the frame window.
        val bitmap = mockk<Bitmap>()
        pacer.onFrameDecoded(bitmap)
        assertSame(bitmap, pacer.acquireFrameForVsync(100L))
        assertEquals(1L, pacer.totalVsyncBlits)
    }

    @Test
    fun testVsyncStaleFrameDroppedAtCallback() {
        val pacer = FramePacer(maxFrameAgeNanos = 250_000_000L, vsyncPeriodNanos = 1_000_000L)
        val bitmap = mockk<Bitmap>()
        pacer.onFrameDecoded(bitmap, timestampNanos = 0L)

        val blit = pacer.acquireFrameForVsync(250_000_001L)

        assertNull("Frame older than maxFrameAge at VSYNC must be dropped", blit)
        assertEquals(1L, pacer.totalStaleDropped)
        assertEquals(0L, pacer.totalVsyncBlits)
        assertFalse(pacer.hasPendingFrame)
    }

    @Test
    fun testNextVsyncDeadlineBoundaryMath() {
        val period = 100L
        // On-grid: next deadline is a full period away.
        assertEquals(100L, FramePacer.nextVsyncDeadline(0L, period))
        assertEquals(200L, FramePacer.nextVsyncDeadline(100L, period))
        // One nanosecond before grid: snaps to the grid line.
        assertEquals(100L, FramePacer.nextVsyncDeadline(99L, period))
        // Negative clock (nanoTime may be negative): still resolves forward.
        assertEquals(0L, FramePacer.nextVsyncDeadline(-50L, period))
        // Non-zero phase offset: grid anchored at phase 10.
        assertEquals(10L, FramePacer.nextVsyncDeadline(0L, period, phaseOffsetNanos = 10L))
        assertEquals(110L, FramePacer.nextVsyncDeadline(10L, period, phaseOffsetNanos = 10L))
        assertEquals(110L, FramePacer.nextVsyncDeadline(11L, period, phaseOffsetNanos = 10L))
    }

    @Test
    fun testNextVsyncDeadlineRejectsNonPositivePeriod() {
        assertThrows(IllegalArgumentException::class.java) {
            FramePacer.nextVsyncDeadline(0L, 0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FramePacer.nextVsyncDeadline(0L, -1L)
        }
    }

    @Test
    fun testPacerConstructorValidatesTunables() {
        assertThrows(IllegalArgumentException::class.java) {
            FramePacer(maxFrameAgeNanos = 0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FramePacer(maxFrameAgeNanos = -1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FramePacer(vsyncPeriodNanos = 0L)
        }
    }
}
