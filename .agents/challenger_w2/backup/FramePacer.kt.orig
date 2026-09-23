package com.freerdp.feature.telemetry.pacer

import android.graphics.Bitmap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Atomic Single-Slot Frame Pacer for interactive remote desktop streaming.
 *
 * Implements a lock-free, single-slot atomic buffer (`AtomicReference<PacedFrame?>`).
 * When graphic tiles are decoded faster than the Android display blitter can draw to
 * the SurfaceView (e.g. during rapid scroll bursts or blitter stalls), intermediate
 * stale frames are atomically dropped in place. There is deliberately NO queue:
 * an unbounded frame queue is the classic source of ever-growing input-to-display
 * lag, so the queue depth of this component is provably <= 1.
 *
 * Guarantees:
 * - Latest-frame-wins: the renderer always consumes the freshest available frame on
 *   display VSYNC, eliminating perceived touch and cursor lag.
 * - Stale-frame drop: a frame older than [maxFrameAgeNanos] at acquisition time is
 *   dropped instead of blitted (drawing outdated pixels delays the next fresh blit).
 * - VSYNC-aligned blitting: [acquireFrameForVsync] allows at most one blit per
 *   [vsyncPeriodNanos] window and requires strictly monotonic VSYNC timestamps;
 *   coalesced callbacks leave the frame pending for the next period.
 * - Conservation invariant (verified by tests): decoded == rendered + dropped + pending.
 */
class FramePacer(
    private val onFrameDroppedCallback: ((Bitmap) -> Unit)? = null,
    val maxFrameAgeNanos: Long = DEFAULT_MAX_FRAME_AGE_NANOS,
    val vsyncPeriodNanos: Long = DEFAULT_VSYNC_PERIOD_NANOS
) {

    init {
        require(maxFrameAgeNanos > 0L) { "maxFrameAgeNanos must be > 0, got $maxFrameAgeNanos" }
        require(vsyncPeriodNanos > 0L) { "vsyncPeriodNanos must be > 0, got $vsyncPeriodNanos" }
    }

    data class PacedFrame(
        val bitmap: Bitmap,
        val sequenceId: Long,
        val timestampNanos: Long = System.nanoTime()
    )

    private val frameSlot = AtomicReference<PacedFrame?>(null)

    private val _totalDecoded = AtomicLong(0L)
    private val _totalRendered = AtomicLong(0L)
    private val _totalDropped = AtomicLong(0L)
    private val _staleDropped = AtomicLong(0L)
    private val _totalVsyncCallbacks = AtomicLong(0L)
    private val _totalVsyncBlits = AtomicLong(0L)
    private val _seqGenerator = AtomicLong(0L)

    // VSYNC bookkeeping: single-threaded (choreographer) but guarded for test/consumer safety.
    private val vsyncLock = Any()
    private var lastVsyncTimestampNanos: Long? = null
    private var lastBlitTimestampNanos: Long? = null

    val totalDecoded: Long get() = _totalDecoded.get()
    val totalRendered: Long get() = _totalRendered.get()
    val totalDropped: Long get() = _totalDropped.get()

    /** Frames discarded because they were older than [maxFrameAgeNanos] when acquired. */
    val totalStaleDropped: Long get() = _staleDropped.get()

    /** Number of VSYNC callbacks observed by [acquireFrameForVsync]. */
    val totalVsyncCallbacks: Long get() = _totalVsyncCallbacks.get()

    /** Number of actual blits performed on a VSYNC callback. */
    val totalVsyncBlits: Long get() = _totalVsyncBlits.get()

    val dropRatio: Float
        get() {
            val decoded = _totalDecoded.get()
            return if (decoded > 0) _totalDropped.get().toFloat() / decoded.toFloat() else 0f
        }

    /**
     * Called by the decoder thread when a new video frame / surface update is ready.
     * Atomically replaces whatever frame is currently pending in the slot (latest wins).
     *
     * @param bitmap The decoded frame bitmap.
     * @return true if a previously unconsumed frame was dropped, false otherwise.
     */
    fun onFrameDecoded(bitmap: Bitmap): Boolean = onFrameDecoded(bitmap, System.nanoTime())

    /**
     * Timestamp-explicit variant for deterministic stale-frame testing.
     */
    fun onFrameDecoded(bitmap: Bitmap, timestampNanos: Long): Boolean {
        val seq = _seqGenerator.incrementAndGet()
        val newFrame = PacedFrame(bitmap = bitmap, sequenceId = seq, timestampNanos = timestampNanos)
        _totalDecoded.incrementAndGet()

        val staleFrame = frameSlot.getAndSet(newFrame)
        return if (staleFrame != null) {
            _totalDropped.incrementAndGet()
            onFrameDroppedCallback?.invoke(staleFrame.bitmap)
            true
        } else {
            false
        }
    }

    /**
     * Called by the display blitter thread (e.g. Choreographer VSYNC callback).
     * Atomically extracts and clears the latest frame from the slot.
     *
     * Frames older than [maxFrameAgeNanos] relative to [nowNanos] are counted as stale
     * drops (and reported through [onFrameDroppedCallback]) instead of being returned.
     *
     * @return The fresh Bitmap to render, or null if no new frame arrived or the pending
     *         frame was stale.
     */
    fun acquireFrameForRendering(): Bitmap? = acquireFrameForRendering(System.nanoTime())

    fun acquireFrameForRendering(nowNanos: Long): Bitmap? {
        val frame = frameSlot.getAndSet(null) ?: return null
        return if (isStale(frame, nowNanos)) {
            _staleDropped.incrementAndGet()
            _totalDropped.incrementAndGet()
            onFrameDroppedCallback?.invoke(frame.bitmap)
            null
        } else {
            _totalRendered.incrementAndGet()
            frame.bitmap
        }
    }

    /**
     * VSYNC-aligned acquisition used by the choreographer/blitter thread.
     *
     * Rules:
     * - VSYNC timestamps must strictly increase ([IllegalArgumentException] otherwise),
     *   guarding against out-of-order display callbacks.
     * - At most ONE blit per [vsyncPeriodNanos]: a second callback inside the same period
     *   returns null while the frame stays pending for the next period (coalescing).
     * - Stale frames (age > [maxFrameAgeNanos] measured at the VSYNC timestamp) are
     *   dropped, not blitted.
     *
     * @return the frame to blit at this VSYNC, or null (nothing new / same-period repeat).
     */
    fun acquireFrameForVsync(vsyncTimestampNanos: Long): Bitmap? {
        synchronized(vsyncLock) {
            val previousVsync = lastVsyncTimestampNanos
            if (previousVsync != null) {
                require(vsyncTimestampNanos > previousVsync) {
                    "VSYNC timestamps must strictly increase (previous=$previousVsync, got=$vsyncTimestampNanos)"
                }
            }
            _totalVsyncCallbacks.incrementAndGet()

            val previousBlit = lastBlitTimestampNanos
            if (previousBlit != null && vsyncTimestampNanos - previousBlit < vsyncPeriodNanos) {
                // Duplicate callback inside the same vsync period: coalesce, keep frame pending.
                lastVsyncTimestampNanos = vsyncTimestampNanos
                return null
            }

            lastVsyncTimestampNanos = vsyncTimestampNanos

            val frame = frameSlot.getAndSet(null) ?: return null
            if (isStale(frame, vsyncTimestampNanos)) {
                _staleDropped.incrementAndGet()
                _totalDropped.incrementAndGet()
                onFrameDroppedCallback?.invoke(frame.bitmap)
                return null
            }

            lastBlitTimestampNanos = vsyncTimestampNanos
            _totalVsyncBlits.incrementAndGet()
            _totalRendered.incrementAndGet()
            return frame.bitmap
        }
    }

    private fun isStale(frame: PacedFrame, nowNanos: Long): Boolean =
        nowNanos - frame.timestampNanos > maxFrameAgeNanos

    /**
     * Inspects the currently pending frame without clearing the slot.
     */
    fun peekPendingFrame(): Bitmap? = frameSlot.get()?.bitmap

    /**
     * Checks if a new unconsumed frame is waiting for rendering.
     */
    val hasPendingFrame: Boolean get() = frameSlot.get() != null

    /**
     * Clears any pending frame and resets drop/render statistics.
     */
    fun reset() {
        val stale = frameSlot.getAndSet(null)
        if (stale != null) {
            onFrameDroppedCallback?.invoke(stale.bitmap)
        }
        synchronized(vsyncLock) {
            lastVsyncTimestampNanos = null
            lastBlitTimestampNanos = null
        }
        _totalDecoded.set(0L)
        _totalRendered.set(0L)
        _totalDropped.set(0L)
        _staleDropped.set(0L)
        _totalVsyncCallbacks.set(0L)
        _totalVsyncBlits.set(0L)
        _seqGenerator.set(0L)
    }

    companion object {
        /** Default stale threshold: ~4 frames at 60 Hz (250 ms). */
        const val DEFAULT_MAX_FRAME_AGE_NANOS: Long = 250_000_000L

        /** Default VSYNC period: 60 Hz display. */
        const val DEFAULT_VSYNC_PERIOD_NANOS: Long = 1_666_667L

        /**
         * Computes the first VSYNC deadline strictly after [nowNanos], aligned to
         * `phaseOffsetNanos (mod vsyncPeriodNanos)`.
         *
         * Used to schedule the first blit of a newly established surface so rendering
         * starts on a display refresh boundary instead of mid-scanout (screen tearing /
         * jitter source).
         *
         * @throws IllegalArgumentException when [vsyncPeriodNanos] <= 0 (zero-period guard).
         */
        fun nextVsyncDeadline(
            nowNanos: Long,
            vsyncPeriodNanos: Long,
            phaseOffsetNanos: Long = 0L
        ): Long {
            require(vsyncPeriodNanos > 0L) { "vsyncPeriodNanos must be > 0, got $vsyncPeriodNanos" }
            val phase = Math.floorMod(phaseOffsetNanos, vsyncPeriodNanos)
            val k = Math.floorDiv(nowNanos - phase, vsyncPeriodNanos)
            var deadline = phase + (k + 1) * vsyncPeriodNanos
            if (deadline <= nowNanos) {
                deadline += vsyncPeriodNanos
            }
            return deadline
        }
    }
}
