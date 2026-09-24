package com.freerdp.feature.mouse

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Libinput-inspired 3-tier pointer acceleration profile with physical DPI velocity conversion
 * and zoom-aware dampening.
 *
 * Implements:
 * - Velocity conversion via display DPI (mm/s).
 * - Tier 1 (< 10 mm/s): deceleration slope 0.07 * v + 0.3.
 * - Tier 2 (10 <= v < 80 mm/s): constant 1.0.
 * - Tier 3 (v >= 80 mm/s): quadratic speedup 0.0005 * (v^2 / 80) + 1.0, clamped to [0.3, 3.5].
 * - Zoom dampening when zoomScale > 1.
 */
interface PointerAcceleration {
    fun computeDelta(rawDeltaX: Float, rawDeltaY: Float, dpi: Float, zoomScale: Float): Pair<Float, Float>
}

/**
 * Default implementation of PointerAcceleration using libinput 3-tier physics curves.
 */
class DefaultPointerAcceleration(
    val assumedSampleIntervalMs: Float = 16.6667f
) : PointerAcceleration {

    override fun computeDelta(
        rawDeltaX: Float,
        rawDeltaY: Float,
        dpi: Float,
        zoomScale: Float
    ): Pair<Float, Float> {
        if (rawDeltaX == 0f && rawDeltaY == 0f) {
            return Pair(0f, 0f)
        }
        val distancePx = sqrt(rawDeltaX * rawDeltaX + rawDeltaY * rawDeltaY)
        val velocityPxPerSec = distancePx / (assumedSampleIntervalMs / 1000f)
        val velocityMmPerSec = pixelToMm(velocityPxPerSec, dpi)
        val factor = calculateAccelerationFactor(velocityMmPerSec, zoomScale)
        return Pair(rawDeltaX * factor, rawDeltaY * factor)
    }

    /**
     * Computes accelerated delta given explicit velocities in pixels/sec.
     */
    fun computeDeltaFromVelocity(
        rawDeltaX: Float,
        rawDeltaY: Float,
        velocityPxX: Float,
        velocityPxY: Float,
        dpi: Float,
        zoomScale: Float
    ): Pair<Float, Float> {
        val speedPxPerSec = sqrt(velocityPxX * velocityPxX + velocityPxY * velocityPxY)
        val velocityMmPerSec = pixelToMm(speedPxPerSec, dpi)
        val factor = calculateAccelerationFactor(velocityMmPerSec, zoomScale)
        return Pair(rawDeltaX * factor, rawDeltaY * factor)
    }

    companion object {
        const val MIN_FACTOR = 0.3f
        const val MAX_FACTOR = 3.5f
        const val BASELINE_FACTOR = 1.0f
        const val THRESHOLD1 = 10f // mm/s
        const val THRESHOLD2 = 80f // mm/s
        const val INITIAL_SLOPE = 0.07f

        /**
         * Converts pixel distance or velocity to physical millimeters given the display DPI.
         */
        fun pixelToMm(pixels: Float, dpi: Float): Float {
            return (abs(pixels) * 25.4f) / max(1f, dpi)
        }

        /**
         * Calculates 3-tier libinput acceleration factor for given physical velocity (mm/s).
         */
        fun calculateBaseAccelerationFactor(velocityMmPerSec: Float): Float {
            val v = abs(velocityMmPerSec)
            val f = when {
                v < THRESHOLD1 -> INITIAL_SLOPE * v + MIN_FACTOR
                v < THRESHOLD2 -> BASELINE_FACTOR
                else -> 0.0005f * ((v * v) / THRESHOLD2) + BASELINE_FACTOR
            }
            return f.coerceIn(MIN_FACTOR, MAX_FACTOR)
        }

        /**
         * Calculates acceleration factor with zoom-aware dampening for zoomScale > 1.
         */
        fun calculateAccelerationFactor(velocityMmPerSec: Float, zoomScale: Float = 1.0f): Float {
            val baseFactor = calculateBaseAccelerationFactor(velocityMmPerSec)
            if (zoomScale <= 1.0f) {
                return baseFactor
            }
            val dampenerSlope = (zoomScale - 1f) * 0.07f
            val dampener = (dampenerSlope * baseFactor).coerceIn(0f, 1f)
            return (baseFactor - dampener).coerceIn(MIN_FACTOR, MAX_FACTOR)
        }

        /**
         * Computes accelerated delta given displacement and elapsed time in seconds.
         */
        fun computeDelta(
            rawDeltaX: Float,
            rawDeltaY: Float,
            dtSeconds: Float,
            dpi: Float = 160f,
            zoomScale: Float = 1.0f
        ): Pair<Float, Float> {
            if (rawDeltaX == 0f && rawDeltaY == 0f) return Pair(0f, 0f)
            val effectiveDpi = if (dpi <= 0f) 160f else dpi
            val effectiveDt = if (dtSeconds <= 0f) 0.016f else dtSeconds

            val distPx = sqrt(rawDeltaX * rawDeltaX + rawDeltaY * rawDeltaY)
            val distMm = distPx * 25.4f / effectiveDpi
            val velocityMmS = distMm / effectiveDt
            val factor = calculateAccelerationFactor(velocityMmS, zoomScale)
            return Pair(rawDeltaX * factor, rawDeltaY * factor)
        }
    }

    /**
     * Computes accelerated delta given displacement and elapsed time in seconds.
     */
    fun computeDelta(
        rawDeltaX: Float,
        rawDeltaY: Float,
        dtSeconds: Float,
        dpi: Float = 160f,
        zoomScale: Float = 1.0f
    ): Pair<Float, Float> = Companion.computeDelta(rawDeltaX, rawDeltaY, dtSeconds, dpi, zoomScale)
}
