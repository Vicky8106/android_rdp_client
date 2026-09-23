package com.freerdp.feature.mouse

import android.graphics.Matrix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CoordinateTransformerTest {

    private lateinit var transformer: CoordinateTransformer

    @Before
    fun setUp() {
        // Desktop: 1920x1080, Viewport: 1080x2400
        transformer = CoordinateTransformer(
            remoteWidth = 1920,
            remoteHeight = 1080,
            viewWidth = 1080,
            viewHeight = 2400,
            minScale = 0.25f,
            maxScale = 5.0f
        )
    }

    @Test
    fun testDefaultCenteringWhenContentFits() {
        // Remote width 1920 with scale 1.0 > 1080 (contentW = 1920)
        // Remote height 1080 with scale 1.0 < 2400 (contentH = 1080 <= 2400)
        // Vertical translation must be centered: (2400 - 1080) / 2 = 660
        assertEquals(660f, transformer.translationY, 0.01f)
        // Horizontal translation must be clamped between (1080 - 1920 = -840) and 0
        assertTrue(transformer.translationX <= 0f && transformer.translationX >= -840f)
    }

    @Test
    fun testScreenToDesktopAndDesktopToScreenUnderZoomAndTranslation() {
        // Specification R2.6: Zoom = 1.5, translation = (100, 200), touch = (250, 500) -> desktop (100, 200)
        val customTransformer = CoordinateTransformer(
            remoteWidth = 1000,
            remoteHeight = 1000,
            viewWidth = 2000,
            viewHeight = 2000
        )
        customTransformer.setTransform(newScale = 1.5f, transX = 100f, transY = 200f)

        val desktopPt = customTransformer.screenToDesktop(250f, 500f)
        assertEquals(100f, desktopPt.x, 0.01f)
        assertEquals(200f, desktopPt.y, 0.01f)

        val (intX, intY) = customTransformer.screenToDesktopInt(250f, 500f)
        assertEquals(100, intX)
        assertEquals(200, intY)

        val screenPt = customTransformer.desktopToScreen(100f, 200f)
        assertEquals(250f, screenPt.x, 0.01f)
        assertEquals(500f, screenPt.y, 0.01f)
    }

    @Test
    fun testBoundaryClampingExtremeCoordinates() {
        transformer.setTransform(newScale = 1.0f, transX = 0f, transY = 0f)

        // Negative touch coordinates
        val clampedMin = transformer.screenToDesktop(-500f, -300f)
        assertEquals(0f, clampedMin.x, 0.001f)
        assertEquals(0f, clampedMin.y, 0.001f)

        val (minIntX, minIntY) = transformer.screenToDesktopInt(-500f, -300f)
        assertEquals(0, minIntX)
        assertEquals(0, minIntY)

        // Beyond remote desktop bounds (1920x1080 -> max index 1919 x 1079)
        val clampedMax = transformer.screenToDesktop(10000f, 8000f)
        assertEquals(1919f, clampedMax.x, 0.001f)
        assertEquals(1079f, clampedMax.y, 0.001f)

        val (maxIntX, maxIntY) = transformer.screenToDesktopInt(10000f, 8000f)
        assertEquals(1919, maxIntX)
        assertEquals(1079, maxIntY)
    }

    @Test
    fun testFocalPointInvarianceDuringZoom() {
        // Zooming at focus point (540, 1200)
        val focusX = 540f
        val focusY = 1200f

        val desktopBefore = transformer.screenToDesktop(focusX, focusY)
        transformer.applyZoom(scaleFactor = 1.5f, focusX = focusX, focusY = focusY)
        val desktopAfter = transformer.screenToDesktop(focusX, focusY)

        // The desktop coordinate under the focal point must remain identical
        assertEquals(desktopBefore.x, desktopAfter.x, 0.5f)
        assertEquals(desktopBefore.y, desktopAfter.y, 0.5f)
    }

    @Test
    fun testPanTranslationClamping() {
        // Set scale high so content exceeds viewport in both dimensions
        transformer.setScale(3.0f)
        // contentW = 1920 * 3 = 5760 (> 1080), contentH = 1080 * 3 = 3240 (> 2400)
        val minTransX = 1080f - 5760f // -4680
        val minTransY = 2400f - 3240f // -840

        // Pan to extreme positive (should clamp to 0)
        transformer.applyPan(10000f, 10000f)
        assertEquals(0f, transformer.translationX, 0.01f)
        assertEquals(0f, transformer.translationY, 0.01f)

        // Pan to extreme negative (should clamp to minTransX, minTransY)
        transformer.applyPan(-20000f, -20000f)
        assertEquals(minTransX, transformer.translationX, 0.01f)
        assertEquals(minTransY, transformer.translationY, 0.01f)
    }

    @Test
    fun testScaleBoundsClamping() {
        transformer.setScale(0.01f)
        assertEquals(transformer.minScale, transformer.scale, 0.001f)

        transformer.setScale(100f)
        assertEquals(transformer.maxScale, transformer.scale, 0.001f)
    }

    @Test
    fun testResetToFit() {
        transformer.resetToFit()
        // Fit 1920x1080 inside 1080x2400:
        // scaleX = 1080 / 1920 = 0.5625
        // scaleY = 2400 / 1080 = 2.222
        // fitScale should be 0.5625
        val expectedScale = 1080f / 1920f
        assertEquals(expectedScale, transformer.scale, 0.001f)

        // With fitScale, content width is 1080, centered at 0
        assertEquals(0f, transformer.translationX, 0.1f)
        // Content height is 1080 * 0.5625 = 607.5, centered vertically in 2400
        val expectedTransY = (2400f - 607.5f) / 2f
        assertEquals(expectedTransY, transformer.translationY, 0.1f)
    }

    @Test
    fun testToMatrix() {
        transformer.setTransform(newScale = 2.0f, transX = -100f, transY = 50f)
        val matrix = transformer.toMatrix()

        val values = FloatArray(9)
        matrix.getValues(values)
        assertEquals(2.0f, values[Matrix.MSCALE_X], 0.001f)
        assertEquals(2.0f, values[Matrix.MSCALE_Y], 0.001f)
        assertEquals(-100f, values[Matrix.MTRANS_X], 0.001f)
        assertEquals(50f, values[Matrix.MTRANS_Y], 0.001f)
    }
}
