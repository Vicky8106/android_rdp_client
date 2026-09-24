package com.freerdp.feature.mouse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PointerAccelerationTest {

    private val acceleration = DefaultPointerAcceleration()

    @Test
    fun testPixelToMmConversion() {
        // At 160 DPI, 160 pixels is exactly 1 inch = 25.4 mm
        val mm160 = DefaultPointerAcceleration.pixelToMm(160f, 160f)
        assertEquals(25.4f, mm160, 0.001f)

        // At 320 DPI, 320 pixels is 1 inch = 25.4 mm
        val mm320 = DefaultPointerAcceleration.pixelToMm(320f, 320f)
        assertEquals(25.4f, mm320, 0.001f)

        // Negative values should convert absolute distance
        val mmNeg = DefaultPointerAcceleration.pixelToMm(-160f, 160f)
        assertEquals(25.4f, mmNeg, 0.001f)
    }

    @Test
    fun testTier1PrecisionDeceleration() {
        // Tier 1: v < 10 mm/s -> slope 0.07 * v + 0.3
        // At v = 0 mm/s: 0.07 * 0 + 0.3 = 0.3
        val f0 = DefaultPointerAcceleration.calculateBaseAccelerationFactor(0f)
        assertEquals(0.3f, f0, 0.001f)

        // At v = 5 mm/s: 0.07 * 5 + 0.3 = 0.65
        val f5 = DefaultPointerAcceleration.calculateBaseAccelerationFactor(5f)
        assertEquals(0.65f, f5, 0.001f)

        // At v = 9 mm/s: 0.07 * 9 + 0.3 = 0.93
        val f9 = DefaultPointerAcceleration.calculateBaseAccelerationFactor(9f)
        assertEquals(0.93f, f9, 0.001f)
    }

    @Test
    fun testTier2LinearBaseline() {
        // Tier 2: 10 <= v < 80 mm/s -> constant 1.0
        val f10 = DefaultPointerAcceleration.calculateBaseAccelerationFactor(10f)
        assertEquals(1.0f, f10, 0.001f)

        val f40 = DefaultPointerAcceleration.calculateBaseAccelerationFactor(40f)
        assertEquals(1.0f, f40, 0.001f)

        val f79 = DefaultPointerAcceleration.calculateBaseAccelerationFactor(79.9f)
        assertEquals(1.0f, f79, 0.001f)
    }

    @Test
    fun testTier3QuadraticSpeedupAndClamping() {
        // Tier 3: v >= 80 mm/s -> 0.0005 * (v^2 / 80) + 1.0, clamped [0.3, 3.5]
        // At v = 80: 0.0005 * (6400 / 80) + 1.0 = 0.0005 * 80 + 1.0 = 1.04
        val f80 = DefaultPointerAcceleration.calculateBaseAccelerationFactor(80f)
        assertEquals(1.04f, f80, 0.001f)

        // At v = 200: 0.0005 * (40000 / 80) + 1.0 = 0.0005 * 500 + 1.0 = 1.25
        val f200 = DefaultPointerAcceleration.calculateBaseAccelerationFactor(200f)
        assertEquals(1.25f, f200, 0.001f)

        // At v = 1000: quadratic speedup exceeds 3.5 -> clamped to 3.5
        val f1000 = DefaultPointerAcceleration.calculateBaseAccelerationFactor(1000f)
        assertEquals(3.5f, f1000, 0.001f)
    }

    @Test
    fun testZoomAwareDampening() {
        // When zoomScale <= 1.0, no dampening occurs
        val fZoom1 = DefaultPointerAcceleration.calculateAccelerationFactor(40f, zoomScale = 1.0f)
        assertEquals(1.0f, fZoom1, 0.001f)

        val fZoomSub1 = DefaultPointerAcceleration.calculateAccelerationFactor(40f, zoomScale = 0.75f)
        assertEquals(1.0f, fZoomSub1, 0.001f)

        // When zoomScale > 1.0, dampenerSlope = (zoomScale - 1) * 0.07
        // At zoomScale = 2.0: dampenerSlope = 0.07. Base factor at 40 mm/s is 1.0.
        // dampener = 0.07 * 1.0 = 0.07 -> final factor = 1.0 - 0.07 = 0.93
        val fZoom2 = DefaultPointerAcceleration.calculateAccelerationFactor(40f, zoomScale = 2.0f)
        assertEquals(0.93f, fZoom2, 0.001f)

        // At zoomScale = 3.0: dampenerSlope = 0.14. Dampener = 0.14.
        // Final factor = 1.0 - 0.14 = 0.86
        val fZoom3 = DefaultPointerAcceleration.calculateAccelerationFactor(40f, zoomScale = 3.0f)
        assertEquals(0.86f, fZoom3, 0.001f)

        // Factor never drops below MIN_FACTOR (0.3)
        val fExtreme = DefaultPointerAcceleration.calculateAccelerationFactor(1f, zoomScale = 5.0f)
        assertTrue(fExtreme >= 0.3f)
    }

    @Test
    fun testComputeDelta() {
        // Zero delta produces zero output
        val (zDx, zDy) = acceleration.computeDelta(0f, 0f, 160f, 1.0f)
        assertEquals(0f, zDx, 0.001f)
        assertEquals(0f, zDy, 0.001f)

        // Non-zero delta computes accelerated output
        val (dx, dy) = acceleration.computeDelta(10f, 0f, 160f, 1.0f)
        assertTrue(dx > 0f)
        assertEquals(0f, dy, 0.001f)

        // computeDeltaFromVelocity with explicit velocity
        // 40 mm/s in Tier 2 -> factor 1.0
        // At 160 DPI, 40 mm/s = 40 / 25.4 * 160 = 251.97 px/s
        val (vDx, vDy) = acceleration.computeDeltaFromVelocity(
            rawDeltaX = 15f,
            rawDeltaY = 25f,
            velocityPxX = 251.97f,
            velocityPxY = 0f,
            dpi = 160f,
            zoomScale = 1.0f
        )
        assertEquals(15f, vDx, 0.01f)
        assertEquals(25f, vDy, 0.01f)
    }
}
