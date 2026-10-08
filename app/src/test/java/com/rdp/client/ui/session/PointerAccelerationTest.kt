package com.rdp.client.ui.session

import com.rdp.client.ui.session.input.PointerAcceleration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class PointerAccelerationTest {

    private val accelerator = PointerAcceleration(
        baseGain = 1.0f,
        maxGain = 3.5f,
        minVelocity = 300f,
        maxVelocity = 3200f,
        powerExponent = 1.5f
    )

    private fun computeTheoreticalMultiplier(speed: Float): Float {
        if (speed <= accelerator.minVelocity) return accelerator.baseGain
        val normalized = ((speed - accelerator.minVelocity) / (accelerator.maxVelocity - accelerator.minVelocity)).coerceIn(0f, 1f)
        val curve = normalized.pow(accelerator.powerExponent)
        return (accelerator.baseGain + (accelerator.maxGain - accelerator.baseGain) * curve).coerceIn(accelerator.baseGain, accelerator.maxGain)
    }

    @Test
    fun testSubThresholdSpeedMaintainsUnityGain() {
        assertEquals(1.0f, computeTheoreticalMultiplier(0f), 0.001f)
        assertEquals(1.0f, computeTheoreticalMultiplier(150f), 0.001f)
        assertEquals(1.0f, computeTheoreticalMultiplier(300f), 0.001f)
    }

    @Test
    fun testMediumSpeedSuperlinearAcceleration() {
        val midSpeed = 1750f // (300 + 3200) / 2 = normalized 0.5
        val mult = computeTheoreticalMultiplier(midSpeed)
        // 0.5^1.5 = 0.3535; 1.0 + 2.5 * 0.3535 = 1.8839x
        assertEquals(1.884f, mult, 0.05f)
        assertTrue(mult > 1.0f)
        assertTrue(mult < 3.5f)
    }

    @Test
    fun testHighSpeedSaturationClamping() {
        assertEquals(3.5f, computeTheoreticalMultiplier(3200f), 0.001f)
        assertEquals(3.5f, computeTheoreticalMultiplier(5000f), 0.001f)
        assertEquals(3.5f, computeTheoreticalMultiplier(10000f), 0.001f)
    }

    @Test
    fun testDeltaDirectionPreservation() {
        val (dx1, dy1) = accelerator.updateDelta(10f, -20f)
        assertTrue(dx1 > 0f)
        assertTrue(dy1 < 0f)
        assertEquals(2.0f, -dy1 / dx1, 0.01f) // Aspect ratio perfectly preserved
    }
}
