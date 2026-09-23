package com.freerdp.feature.telemetry.metrics

import com.freerdp.core.engine.RdpSessionMetrics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * Real-time diagnostic telemetry collector.
 *
 * Employs a 1,000-sample circular ring buffer to record RTT, frame delivery intervals,
 * and calculate rolling statistics (FPS, inter-frame jitter, p95/p99 latency, bandwidth).
 */
class TelemetryCollector(
    private val bufferCapacity: Int = 1000,
    private val timeSourceMs: () -> Long = System::currentTimeMillis
) {

    init {
        require(bufferCapacity > 0) { "bufferCapacity must be > 0, got $bufferCapacity" }
    }

    data class TelemetrySnapshot(
        val rttMs: Long = 0L,
        val avgRttMs: Double = 0.0,
        val p50RttMs: Double = 0.0,
        val p95RttMs: Double = 0.0,
        val p99RttMs: Double = 0.0,
        val fps: Float = 0f,
        val jitterMs: Double = 0.0,
        val bandwidthKbps: Long = 0L,
        val totalFramesRendered: Long = 0L,
        val droppedFrames: Long = 0L,
        val connectionState: String = "Disconnected",
        val timestampMs: Long = System.currentTimeMillis()
    )

    private val rttRingBuffer = CircularTelemetryBuffer(bufferCapacity)
    private val jitterRingBuffer = CircularTelemetryBuffer(bufferCapacity)

    private val totalFramesRendered = AtomicLong(0L)
    private val totalFramesDropped = AtomicLong(0L)
    private val totalBytesTransferred = AtomicLong(0L)

    private var lastFrameTimeNanos: Long = 0L
    private val frameTimestamps = ArrayDeque<Long>(120)
    private val frameLock = Any()

    private var lastBandwidthCalculationTimeMs: Long = timeSourceMs()
    private var lastBandwidthBytes: Long = 0L

    @Volatile
    private var currentBandwidthKbps: Long = 0L
    private val bandwidthLock = Any()

    private val _telemetryState = MutableStateFlow(TelemetrySnapshot())
    val telemetryState: StateFlow<TelemetrySnapshot> = _telemetryState.asStateFlow()

    private val _hudState = MutableStateFlow(
        DiagnosticHudModel.format(0f, 0L, 0.0, 0.0, 0.0, 0L, 0L, "Disconnected")
    )
    val hudState: StateFlow<DiagnosticHudModel> = _hudState.asStateFlow()

    private var currentConnectionState: String = "Disconnected"

    /**
     * Records a round-trip time (ping) measurement in milliseconds.
     */
    fun recordRtt(rttMs: Long) {
        val nonNegative = rttMs.coerceAtLeast(0L)
        rttRingBuffer.record(nonNegative)
        updateSnapshots()
    }

    /**
     * Records the delivery / blit of a completed frame.
     * Computes inter-frame jitter ($\Delta t$) and updates sliding 1-second FPS.
     *
     * Non-monotonic timestamps (equal or backwards) are accepted for accounting but do
     * NOT contribute jitter samples or window entries — a backwards sample would break
     * the window's ordering invariant and produce negative-jitter artifacts.
     */
    fun recordFrameDelivered(timestampNanos: Long = System.nanoTime()) {
        totalFramesRendered.incrementAndGet()

        synchronized(frameLock) {
            val isMonotonic = lastFrameTimeNanos == 0L || timestampNanos > lastFrameTimeNanos

            if (isMonotonic) {
                if (lastFrameTimeNanos > 0L) {
                    val intervalNanos = timestampNanos - lastFrameTimeNanos
                    val intervalMs = intervalNanos / 1_000_000.0
                    jitterRingBuffer.record(intervalMs)
                }
                lastFrameTimeNanos = timestampNanos

                // 1-second sliding window for instantaneous FPS
                frameTimestamps.addLast(timestampNanos)
                val oneSecAgo = timestampNanos - 1_000_000_000L
                while (!frameTimestamps.isEmpty() && frameTimestamps.first < oneSecAgo) {
                    frameTimestamps.removeFirst()
                }
            }
        }
        updateSnapshots()
    }

    /**
     * Records a dropped frame event (e.g. from the single-slot frame pacer).
     */
    fun recordFrameDropped() {
        totalFramesDropped.incrementAndGet()
        updateSnapshots()
    }

    /**
     * Records network payload bytes transferred (rx + tx) and recalculates bandwidth throughput.
     * Negative deltas are coerced to 0 (counter is monotonic).
     *
     * The window is [BANDWIDTH_WINDOW_MS] wide and evaluated against [timeSourceMs]
     * (injectable for deterministic tests).
     */
    fun recordBytesTransferred(bytesDelta: Long) {
        totalBytesTransferred.addAndGet(bytesDelta.coerceAtLeast(0L))
        synchronized(bandwidthLock) {
            val now = timeSourceMs()
            val elapsedMs = now - lastBandwidthCalculationTimeMs
            if (elapsedMs >= BANDWIDTH_WINDOW_MS) {
                val total = totalBytesTransferred.get()
                val delta = total - lastBandwidthBytes
                // (delta bytes * 8 bits / 1000) / (elapsedMs / 1000) = (delta * 8) / elapsedMs
                currentBandwidthKbps = if (elapsedMs > 0) (delta * 8) / elapsedMs else 0L
                lastBandwidthBytes = total
                lastBandwidthCalculationTimeMs = now
                updateSnapshots()
            }
        }
    }

    /**
     * Updates current connection lifecycle state.
     */
    fun updateConnectionState(state: String) {
        currentConnectionState = state
        updateSnapshots()
    }

    /**
     * Calculates instantaneous FPS based on frames recorded in the last 1.0 second.
     */
    fun calculateInstantaneousFps(): Float {
        synchronized(frameLock) {
            return frameTimestamps.size.toFloat()
        }
    }

    /**
     * Captures an immutable telemetry snapshot.
     */
    fun getSnapshot(): TelemetrySnapshot {
        val lastRtt = if (rttRingBuffer.isEmpty()) 0L else rttRingBuffer.snapshot().last().toLong()
        val avgRtt = rttRingBuffer.average()
        val p50 = rttRingBuffer.percentile(50.0)
        val p95 = rttRingBuffer.percentile(95.0)
        val p99 = rttRingBuffer.percentile(99.0)
        val jitter = jitterRingBuffer.standardDeviation()
        val fps = calculateInstantaneousFps()

        return TelemetrySnapshot(
            rttMs = lastRtt,
            avgRttMs = avgRtt,
            p50RttMs = p50,
            p95RttMs = p95,
            p99RttMs = p99,
            fps = fps,
            jitterMs = jitter,
            bandwidthKbps = currentBandwidthKbps,
            totalFramesRendered = totalFramesRendered.get(),
            droppedFrames = totalFramesDropped.get(),
            connectionState = currentConnectionState,
            timestampMs = System.currentTimeMillis()
        )
    }

    /**
     * Generates the diagnostic HUD model.
     */
    fun getHudModel(): DiagnosticHudModel {
        val snap = getSnapshot()
        return DiagnosticHudModel.format(
            fps = snap.fps,
            rttMs = snap.rttMs,
            p95RttMs = snap.p95RttMs,
            p99RttMs = snap.p99RttMs,
            jitterMs = snap.jitterMs,
            bandwidthKbps = snap.bandwidthKbps,
            droppedFrames = snap.droppedFrames,
            connectionState = snap.connectionState
        )
    }

    /**
     * Converts to core-rdp [RdpSessionMetrics] for engine listeners.
     */
    fun toRdpSessionMetrics(): RdpSessionMetrics {
        val snap = getSnapshot()
        return RdpSessionMetrics(
            rttMs = snap.rttMs,
            fps = snap.fps,
            bandwidthKbps = snap.bandwidthKbps,
            frameCount = snap.totalFramesRendered,
            droppedFrames = snap.droppedFrames,
            jitterMs = snap.jitterMs.toLong()
        )
    }

    /**
     * Resets all metric buffers and counters.
     */
    fun reset() {
        rttRingBuffer.clear()
        jitterRingBuffer.clear()
        totalFramesRendered.set(0L)
        totalFramesDropped.set(0L)
        totalBytesTransferred.set(0L)
        synchronized(frameLock) {
            frameTimestamps.clear()
            lastFrameTimeNanos = 0L
        }
        lastBandwidthBytes = 0L
        currentBandwidthKbps = 0L
        synchronized(bandwidthLock) {
            lastBandwidthCalculationTimeMs = timeSourceMs()
        }
        updateSnapshots()
    }

    private fun updateSnapshots() {
        val snap = getSnapshot()
        _telemetryState.value = snap
        _hudState.value = DiagnosticHudModel.format(
            fps = snap.fps,
            rttMs = snap.rttMs,
            p95RttMs = snap.p95RttMs,
            p99RttMs = snap.p99RttMs,
            jitterMs = snap.jitterMs,
            bandwidthKbps = snap.bandwidthKbps,
            droppedFrames = snap.droppedFrames,
            connectionState = snap.connectionState
        )
    }

    companion object {
        /** Bandwidth recomputation window (ms) — coarse enough to bound HUD churn. */
        const val BANDWIDTH_WINDOW_MS: Long = 500L
    }
}
