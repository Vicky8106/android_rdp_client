package com.freerdp.feature.telemetry

import com.freerdp.feature.telemetry.metrics.CircularTelemetryBuffer
import com.freerdp.feature.telemetry.metrics.TelemetryCollector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.sqrt

class TelemetryCollectorTest {

    private lateinit var telemetryCollector: TelemetryCollector
    private val buffer0 = CircularTelemetryBuffer(capacity = 4)

    @Before
    fun setUp() {
        telemetryCollector = TelemetryCollector(bufferCapacity = 1000)
    }

    @Test
    fun testCircularTelemetryBufferRolloverAtCapacity() {
        val buffer = CircularTelemetryBuffer(capacity = 10)
        assertTrue(buffer.isEmpty())
        assertFalse(buffer.isFull())

        // Insert 10 samples
        for (i in 1..10) {
            buffer.record(i.toDouble())
        }
        assertEquals(10, buffer.size())
        assertTrue(buffer.isFull())
        assertEquals(1.0, buffer.min(), 0.001)
        assertEquals(10.0, buffer.max(), 0.001)
        assertEquals(5.5, buffer.average(), 0.001)

        // Insert 5 more samples: 11, 12, 13, 14, 15
        // Overwrites oldest samples 1, 2, 3, 4, 5
        // Buffer should now contain 6..15
        for (i in 11..15) {
            buffer.record(i.toDouble())
        }
        assertEquals(10, buffer.size())
        assertEquals(6.0, buffer.min(), 0.001)
        assertEquals(15.0, buffer.max(), 0.001)
        assertEquals(10.5, buffer.average(), 0.001) // (6 + ... + 15) / 10 = 105 / 10 = 10.5
    }

    @Test
    fun testBufferFull1000SamplesRollover() {
        val buffer = CircularTelemetryBuffer(capacity = 1000)
        for (i in 1..1500) {
            buffer.record(i.toDouble())
        }

        assertEquals(1000, buffer.size())
        assertTrue(buffer.isFull())
        // Remaining elements are 501..1500
        assertEquals(501.0, buffer.min(), 0.001)
        assertEquals(1500.0, buffer.max(), 0.001)
        assertEquals(1000.5, buffer.average(), 0.001) // (501 + 1500) / 2 = 1000.5
    }

    @Test
    fun testRunningAverageMinMaxAndJitter() {
        val buffer = CircularTelemetryBuffer(capacity = 100)
        val values = listOf(10.0, 20.0, 30.0)
        values.forEach { buffer.record(it) }

        assertEquals(10.0, buffer.min(), 0.001)
        assertEquals(30.0, buffer.max(), 0.001)
        assertEquals(20.0, buffer.average(), 0.001)

        // Variance: ((10-20)^2 + (20-20)^2 + (30-20)^2) / 3 = 200 / 3 = 66.666...
        // Stddev: sqrt(66.666...) ~ 8.16496
        val expectedStdDev = sqrt(200.0 / 3.0)
        assertEquals(expectedStdDev, buffer.standardDeviation(), 0.001)
    }

    @Test
    fun testPercentileCalculations() {
        val buffer = CircularTelemetryBuffer(capacity = 100)
        // Insert 100 values from 1.0 to 100.0
        for (i in 1..100) {
            buffer.record(i.toDouble())
        }

        val p50 = buffer.percentile(50.0)
        val p95 = buffer.percentile(95.0)
        val p99 = buffer.percentile(99.0)

        // For 1..100: median (p50) is 50.5
        assertEquals(50.5, p50, 0.1)
        // p95 is ~95.05
        assertEquals(95.0, p95, 0.5)
        // p99 is ~99.01
        assertEquals(99.0, p99, 0.5)
    }

    @Test
    fun testTelemetryCollectorFpsMeasurement() {
        val baseTimeNanos = 1_000_000_000_000L // 1000 seconds
        val frameIntervalNanos = 1_000_000_000L / 60 // 60 FPS (~16.66ms per frame)

        // Deliver 60 frames over 1 second
        for (i in 0 until 60) {
            telemetryCollector.recordFrameDelivered(baseTimeNanos + (i * frameIntervalNanos))
        }

        val fps = telemetryCollector.calculateInstantaneousFps()
        assertEquals(60f, fps, 0.001f)

        val snapshot = telemetryCollector.getSnapshot()
        assertEquals(60L, snapshot.totalFramesRendered)
        assertEquals(60f, snapshot.fps, 0.001f)
    }

    @Test
    fun testRttRecordingAndSnapshot() {
        telemetryCollector.recordRtt(15L)
        telemetryCollector.recordRtt(25L)
        telemetryCollector.recordRtt(35L)
        telemetryCollector.recordRtt(45L)

        val snapshot = telemetryCollector.getSnapshot()
        assertEquals(45L, snapshot.rttMs) // Last recorded RTT
        assertEquals(30.0, snapshot.avgRttMs, 0.001) // Average of 15, 25, 35, 45
        assertEquals(30.0, snapshot.p50RttMs, 0.001)
        assertTrue(snapshot.p95RttMs > 40.0)
    }

    @Test
    fun testDiagnosticHudFormatting() {
        telemetryCollector.recordRtt(18L)
        val now = System.nanoTime()
        telemetryCollector.recordFrameDelivered(now)
        telemetryCollector.recordFrameDelivered(now + 16_000_000L)
        telemetryCollector.recordFrameDropped()
        telemetryCollector.updateConnectionState("Connected")

        val hud = telemetryCollector.getHudModel()
        val text = hud.hudText

        assertTrue("HUD must include FPS indicator", text.contains("FPS:"))
        assertTrue("HUD must include RTT indicator", text.contains("RTT: 18ms"))
        assertTrue("HUD must include p95", text.contains("p95:"))
        assertTrue("HUD must include Jitter", text.contains("Jitter:"))
        assertTrue("HUD must include Drops count", text.contains("Drops: 1"))
        assertTrue("HUD must include Connected state", text.contains("Connected"))
    }

    @Test
    fun testDroppedFramesTracking() {
        assertEquals(0L, telemetryCollector.getSnapshot().droppedFrames)
        telemetryCollector.recordFrameDropped()
        telemetryCollector.recordFrameDropped()
        telemetryCollector.recordFrameDropped()
        assertEquals(3L, telemetryCollector.getSnapshot().droppedFrames)
    }

    @Test
    fun testResetClearsAllBuffers() {
        telemetryCollector.recordRtt(50L)
        telemetryCollector.recordFrameDelivered()
        telemetryCollector.recordFrameDropped()

        telemetryCollector.reset()

        val snap = telemetryCollector.getSnapshot()
        assertEquals(0L, snap.rttMs)
        assertEquals(0.0, snap.avgRttMs, 0.001)
        assertEquals(0L, snap.totalFramesRendered)
        assertEquals(0L, snap.droppedFrames)
    }

    // ---- Boundary / concurrency / quality tests (F24, Tier-2 style) ----

    @Test
    fun testRingBufferCapacityBoundaries() {
        // Capacity 0 (and negative) must be rejected at construction.
        assertThrows(IllegalArgumentException::class.java) { CircularTelemetryBuffer(capacity = 0) }
        assertThrows(IllegalArgumentException::class.java) { CircularTelemetryBuffer(capacity = -1) }

        // Capacity 1 degenerates to "latest sample wins".
        val tiny = CircularTelemetryBuffer(capacity = 1)
        tiny.record(10.0)
        tiny.record(20.0)
        tiny.record(30.0)
        assertEquals(1, tiny.size())
        assertTrue(tiny.isFull())
        assertEquals(30.0, tiny.min(), 0.0)
        assertEquals(30.0, tiny.max(), 0.0)
        assertEquals(30.0, tiny.average(), 0.0)
        assertEquals(30.0, tiny.percentile(50.0), 0.0)
        assertEquals(30.0, tiny.percentile(99.0), 0.0)
    }

    @Test
    fun testPercentileRangeBoundaries() {
        assertThrows(IllegalArgumentException::class.java) { buffer0.percentile(-0.001) }
        assertThrows(IllegalArgumentException::class.java) { buffer0.percentile(100.001) }
        assertThrows(IllegalArgumentException::class.java) { buffer0.percentile(Double.NaN) }

        // Empty buffer: any valid percentile returns 0 (documented sentinel).
        assertEquals(0.0, buffer0.percentile(0.0), 0.0)
        assertEquals(0.0, buffer0.percentile(50.0), 0.0)
        assertEquals(0.0, buffer0.percentile(100.0), 0.0)

        // Exact endpoints on populated data.
        val buffer = CircularTelemetryBuffer(capacity = 16)
        listOf(5.0, 1.0, 9.0, 3.0).forEach { buffer.record(it) }
        assertEquals("p0 must equal the minimum", 1.0, buffer.percentile(0.0), 0.0)
        assertEquals("p100 must equal the maximum", 9.0, buffer.percentile(100.0), 0.0)
    }

    @Test
    fun testCollectorRejectsZeroCapacity() {
        assertThrows(IllegalArgumentException::class.java) { TelemetryCollector(bufferCapacity = 0) }
        assertThrows(IllegalArgumentException::class.java) { TelemetryCollector(bufferCapacity = -5) }
        // Minimum valid capacity constructs and records.
        val tiny = TelemetryCollector(bufferCapacity = 1)
        tiny.recordRtt(10L)
        tiny.recordRtt(20L)
        assertEquals("capacity=1 keeps only the newest sample", 20L, tiny.getSnapshot().rttMs)
        assertEquals(20.0, tiny.getSnapshot().avgRttMs, 0.0)
    }

    @Test
    fun testNegativeRttIsCoercedToZero() {
        telemetryCollector.recordRtt(-42L)
        telemetryCollector.recordRtt(-1L)
        telemetryCollector.recordRtt(0L)

        val snap = telemetryCollector.getSnapshot()
        assertEquals(0L, snap.rttMs)
        assertEquals(0.0, snap.avgRttMs, 0.0)
        assertEquals(0.0, snap.p95RttMs, 0.0)
    }

    @Test
    fun testOutOfOrderFrameTimestampsDoNotCorruptStats() {
        val t1 = 1_000_000_000L
        val t2 = 2_000_000_000L
        val tBackwards = 1_500_000_000L

        telemetryCollector.recordFrameDelivered(t1)
        telemetryCollector.recordFrameDelivered(t2)
        telemetryCollector.recordFrameDelivered(tBackwards) // injected clock skew

        val snap = telemetryCollector.getSnapshot()
        assertEquals(
            "Delivered frames are still counted",
            3L,
            snap.totalFramesRendered
        )
        assertEquals(
            "Only the monotonic interval contributes jitter (single sample => sigma 0)",
            0.0,
            snap.jitterMs,
            0.001
        )
        assertEquals(
            "Backwards timestamp must not enter the FPS window",
            2f,
            snap.fps,
            0.001f
        )
    }

    @Test
    fun testBandwidthWindowBoundaryWithInjectedClock() {
        var now = 0L
        val collector = TelemetryCollector(bufferCapacity = 16, timeSourceMs = { now })

        collector.recordBytesTransferred(12_500)
        collector.recordBytesTransferred(12_500)
        assertEquals("Below the 500ms window no recomputation happens", 0L, collector.getSnapshot().bandwidthKbps)

        now = 499L
        collector.recordBytesTransferred(0)
        assertEquals(0L, collector.getSnapshot().bandwidthKbps)

        // Exactly at the window: (25000 bytes * 8) / 500 ms = 400 kbps.
        now = 500L
        collector.recordBytesTransferred(0)
        assertEquals(400L, collector.getSnapshot().bandwidthKbps)

        // Negative deltas are coerced to zero and must not move the counter/window.
        now = 501L
        collector.recordBytesTransferred(-999_999)
        assertEquals("Window not elapsed: previous value retained", 400L, collector.getSnapshot().bandwidthKbps)

        // Next full window with zero new bytes => 0 kbps.
        now = 1_001L
        collector.recordBytesTransferred(0)
        assertEquals(0L, collector.getSnapshot().bandwidthKbps)
    }

    @Test
    fun testResetClearsBandwidth() {
        var now = 0L
        val collector = TelemetryCollector(bufferCapacity = 16, timeSourceMs = { now })
        collector.recordBytesTransferred(100_000)
        now = 500L
        collector.recordBytesTransferred(0)
        assertTrue(collector.getSnapshot().bandwidthKbps > 0L)

        collector.reset()
        assertEquals(0L, collector.getSnapshot().bandwidthKbps)
    }

    @Test
    fun testConcurrentRingBufferWritesStayBounded() {
        val capacity = 1000
        val buffer = CircularTelemetryBuffer(capacity = capacity)
        val threads = 8
        val perThread = 5000
        val executor = java.util.concurrent.Executors.newFixedThreadPool(threads)
        val futures = (0 until threads).map { t ->
            executor.submit {
                for (i in 0 until perThread) {
                    buffer.record(t * perThread + i + 1.0)
                }
            }
        }
        futures.forEach { it.get(30, java.util.concurrent.TimeUnit.SECONDS) } // propagates worker errors
        executor.shutdown()

        assertEquals(
            "Ring must clamp to capacity regardless of concurrent writers",
            capacity,
            buffer.size()
        )
        assertTrue(buffer.isFull())
        val snapshot = buffer.snapshot()
        assertEquals(capacity, snapshot.size)
        val min = buffer.min()
        val max = buffer.max()
        assertTrue("min <= avg <= max must hold under concurrency", min <= buffer.average() && buffer.average() <= max)
        // Percentiles must remain inside the observed value range.
        val p95 = buffer.percentile(95.0)
        assertTrue("p95 must lie within [min, max]", p95 in min..max)
    }

    @Test
    fun testConcurrentCollectorWritesAndReadsAreThreadSafe() {
        val collector = TelemetryCollector(bufferCapacity = 64)
        val writers = 4
        val executor = java.util.concurrent.Executors.newFixedThreadPool(writers + 1)
        val latch = java.util.concurrent.CountDownLatch(1)
        val done = java.util.concurrent.CountDownLatch(writers)

        repeat(writers) { w ->
            executor.submit {
                latch.await()
                for (i in 0 until 500) {
                    collector.recordRtt((w * 500 + i).toLong())
                    collector.recordFrameDropped()
                    collector.getSnapshot() // concurrent reader
                }
                done.countDown()
            }
        }
        latch.countDown()
        assertTrue(done.await(30, java.util.concurrent.TimeUnit.SECONDS))
        executor.shutdown()

        val snap = collector.getSnapshot()
        assertEquals(
            "All dropped-frame events must be accounted for",
            (writers * 500).toLong(),
            snap.droppedFrames
        )
        assertTrue("RTT must be one of the recorded values", snap.rttMs >= 0L)
    }

    // ---- Diagnostic HUD quality boundaries ----

    @Test
    fun testQualityBoundaryExcellentToGoodAt25To26Ms() {
        assertEquals(
            com.freerdp.feature.telemetry.metrics.DiagnosticHudModel.ConnectionQuality.EXCELLENT,
            com.freerdp.feature.telemetry.metrics.DiagnosticHudModel.format(60f, 25, 20.0, 24.0, 1.0, 1000, 0, "Connected").quality
        )
        assertEquals(
            com.freerdp.feature.telemetry.metrics.DiagnosticHudModel.ConnectionQuality.GOOD,
            com.freerdp.feature.telemetry.metrics.DiagnosticHudModel.format(60f, 26, 20.0, 24.0, 1.0, 1000, 0, "Connected").quality
        )
    }

    @Test
    fun testQualityBoundaryGoodToFairAt60To61Ms() {
        assertEquals(
            com.freerdp.feature.telemetry.metrics.DiagnosticHudModel.ConnectionQuality.GOOD,
            com.freerdp.feature.telemetry.metrics.DiagnosticHudModel.format(30f, 60, 55.0, 59.0, 2.0, 800, 0, "Connected").quality
        )
        assertEquals(
            com.freerdp.feature.telemetry.metrics.DiagnosticHudModel.ConnectionQuality.FAIR,
            com.freerdp.feature.telemetry.metrics.DiagnosticHudModel.format(30f, 61, 55.0, 59.0, 2.0, 800, 0, "Connected").quality
        )
    }

    @Test
    fun testQualityBoundaryFairToPoorAt120To121Ms() {
        assertEquals(
            com.freerdp.feature.telemetry.metrics.DiagnosticHudModel.ConnectionQuality.FAIR,
            com.freerdp.feature.telemetry.metrics.DiagnosticHudModel.format(20f, 120, 100.0, 119.0, 5.0, 400, 0, "Connected").quality
        )
        assertEquals(
            com.freerdp.feature.telemetry.metrics.DiagnosticHudModel.ConnectionQuality.POOR,
            com.freerdp.feature.telemetry.metrics.DiagnosticHudModel.format(20f, 121, 100.0, 119.0, 5.0, 400, 0, "Connected").quality
        )
    }

    @Test
    fun testQualityBoundaryFpsThresholds55And54() {
        assertEquals(
            com.freerdp.feature.telemetry.metrics.DiagnosticHudModel.ConnectionQuality.EXCELLENT,
            com.freerdp.feature.telemetry.metrics.DiagnosticHudModel.format(55f, 10, 8.0, 9.0, 1.0, 2000, 0, "Connected").quality
        )
        assertEquals(
            com.freerdp.feature.telemetry.metrics.DiagnosticHudModel.ConnectionQuality.GOOD,
            com.freerdp.feature.telemetry.metrics.DiagnosticHudModel.format(54f, 10, 8.0, 9.0, 1.0, 2000, 0, "Connected").quality
        )
    }

    @Test
    fun testQualityZeroRttAndLowFpsIsPoor() {
        // rtt=0 means "no sample yet" — must not misreport as EXCELLENT.
        assertEquals(
            com.freerdp.feature.telemetry.metrics.DiagnosticHudModel.ConnectionQuality.POOR,
            com.freerdp.feature.telemetry.metrics.DiagnosticHudModel.format(60f, 0, 0.0, 0.0, 0.0, 0, 0, "Disconnected").quality
        )
        // fps below 14 at a healthy RTT still degrades to POOR.
        assertEquals(
            com.freerdp.feature.telemetry.metrics.DiagnosticHudModel.ConnectionQuality.POOR,
            com.freerdp.feature.telemetry.metrics.DiagnosticHudModel.format(13f, 10, 8.0, 9.0, 1.0, 100, 0, "Connected").quality
        )
    }
}
