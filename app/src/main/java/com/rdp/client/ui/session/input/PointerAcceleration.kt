package com.rdp.client.ui.session.input

import android.view.MotionEvent
import android.view.VelocityTracker
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Velocity-based Non-linear Pointer Accelerator.
 * Delivers calibrated 1.0x to 3.5x superlinear ballistics curve.
 */
class PointerAcceleration(
    val baseGain: Float = 1.0f,
    val maxGain: Float = 3.5f,
    val minVelocity: Float = 300f,  // px/sec
    val maxVelocity: Float = 3200f, // px/sec
    val powerExponent: Float = 1.5f
) {
    private var velocityTracker: VelocityTracker? = null

    fun addMovement(event: MotionEvent) {
        if (velocityTracker == null) {
            velocityTracker = VelocityTracker.obtain()
        }
        velocityTracker?.addMovement(event)
    }

    /**
     * Computes the acceleration multiplier from current tracking velocity.
     */
    fun computeMultiplier(): Float {
        val vt = velocityTracker ?: return baseGain
        vt.computeCurrentVelocity(1000) // Compute pixels per second
        val vx = vt.xVelocity
        val vy = vt.yVelocity
        val speed = sqrt(vx * vx + vy * vy)

        if (speed <= minVelocity) {
            return baseGain
        }
        val normalized = ((speed - minVelocity) / (maxVelocity - minVelocity)).coerceIn(0f, 1f)
        val curve = normalized.pow(powerExponent)
        return (baseGain + (maxGain - baseGain) * curve).coerceIn(baseGain, maxGain)
    }

    /**
     * Accelerates input delta (dx, dy) by computed velocity gain.
     */
    fun updateDelta(dx: Float, dy: Float): Pair<Float, Float> {
        val mult = computeMultiplier()
        return Pair(dx * mult, dy * mult)
    }

    fun reset() {
        velocityTracker?.recycle()
        velocityTracker = null
    }
}
