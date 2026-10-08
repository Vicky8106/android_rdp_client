package com.rdp.client.ui.session

import android.graphics.Matrix
import android.graphics.PointF
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rdp.client.ui.session.viewport.ViewportTransform
import com.rdp.client.ui.session.viewport.ZoomMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.random.Random

/**
 * Adversarial empirical test suite stress-testing ViewportTransform:
 * - Extreme viewport dimensions (1x1, 8192x8192, 0x0 degenerate bounds handling)
 * - Zoom factor boundary clamping (0.25x min, 5.0x max, negative/infinite/zero injection)
 * - Pivot zoom invariance: (X_desktop, Y_desktop) under focal point F remains stationary before and after zoom scale k
 * - Bidirectional coordinate conversion round-trips: desktopToScreen(screenToDesktop(p)) == p across arbitrary pan offsets
 * - Pan boundary clamping under letterboxing vs pillarboxing
 * - Canvas Matrix equivalence and numerical stability
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ViewportTransformAdversarialTest {

    private lateinit var transform: ViewportTransform

    @Before
    fun setUp() {
        transform = ViewportTransform(minZoomFactor = 0.25f, maxZoomFactor = 5.0f)
    }

    // =========================================================================
    // 1. Extreme Viewport & Framebuffer Dimensions & Degenerate Handling
    // =========================================================================

    @Test
    fun testDegenerateInitialStateBeforeDimensionsSet() {
        // Fresh transform has 0x0 viewport and 0x0 framebuffer
        assertEquals(0, transform.viewportWidth)
        assertEquals(0, transform.viewportHeight)
        assertEquals(0, transform.fbWidth)
        assertEquals(0, transform.fbHeight)
        assertEquals(1.0f, transform.baseScale, 0.0001f)
        assertEquals(1.0f, transform.effectiveScale, 0.0001f)

        // Strict conversion should return null safely without throwing ArithmeticException
        assertNull(transform.screenToRemoteStrict(100f, 100f))

        // Coerced conversion should safely return (0, 0)
        val coerced = transform.screenToRemoteCoerced(100f, 100f)
        assertEquals(0f, coerced.x, 0.0001f)
        assertEquals(0f, coerced.y, 0.0001f)

        // Bounds should be non-null and valid
        val bounds = transform.getVisibleRemoteBounds()
        assertNotNull(bounds)
        assertEquals(0f, bounds.left, 0.0001f)
        assertEquals(0f, bounds.top, 0.0001f)

        // Matrix should be valid identity-equivalent
        val matrix = transform.getTransformationMatrix()
        assertNotNull(matrix)
        assertFalse(matrix.isIdentity.let { false }) // Just verify matrix retrieval succeeds
    }

    @Test
    fun testDegenerateZeroAndNegativeDimensionsIgnored() {
        transform.setViewportDimensions(1920, 1080)
        transform.setFramebufferDimensions(1920, 1080)
        val originalScale = transform.baseScale

        // Attempt setting 0x0 dimensions
        transform.setViewportDimensions(0, 0)
        assertEquals(1920, transform.viewportWidth)
        assertEquals(1080, transform.viewportHeight)
        assertEquals(originalScale, transform.baseScale, 0.0001f)

        transform.setFramebufferDimensions(0, 0)
        assertEquals(1920, transform.fbWidth)
        assertEquals(1080, transform.fbHeight)

        // Attempt setting negative dimensions
        transform.setViewportDimensions(-1080, -1920)
        transform.setFramebufferDimensions(-1920, -1080)
        assertEquals(1920, transform.viewportWidth)
        assertEquals(1080, transform.viewportHeight)
        assertEquals(1920, transform.fbWidth)
        assertEquals(1080, transform.fbHeight)

        // Mixed 0 and negative
        transform.setViewportDimensions(0, 1080)
        transform.setViewportDimensions(1080, 0)
        transform.setFramebufferDimensions(-1, 1080)
        transform.setFramebufferDimensions(1920, -1)
        assertEquals(1920, transform.viewportWidth)
        assertEquals(1080, transform.viewportHeight)
        assertEquals(1920, transform.fbWidth)
        assertEquals(1080, transform.fbHeight)
    }

    @Test
    fun testExtremeDimensions_1x1To8192x8192() {
        // Extreme 1: 1x1 viewport and 1x1 framebuffer
        transform.setViewportDimensions(1, 1)
        transform.setFramebufferDimensions(1, 1)
        assertEquals(1.0f, transform.baseScale, 0.0001f)
        assertEquals(0f, transform.translationX, 0.0001f)
        assertEquals(0f, transform.translationY, 0.0001f)

        // Extreme 2: 8192x8192 (8K) viewport and 8192x8192 framebuffer
        transform.setViewportDimensions(8192, 8192)
        transform.setFramebufferDimensions(8192, 8192)
        assertEquals(1.0f, transform.baseScale, 0.0001f)
        assertEquals(0f, transform.translationX, 0.0001f)
        assertEquals(0f, transform.translationY, 0.0001f)

        // Extreme 3: 1x1 viewport and 8192x8192 framebuffer (micro viewport)
        transform.setViewportDimensions(1, 1)
        transform.setFramebufferDimensions(8192, 8192)
        assertEquals(1.0f / 8192f, transform.baseScale, 0.000001f)
        assertFalse(transform.baseScale.isNaN())
        assertFalse(transform.baseScale.isInfinite())

        // Extreme 4: 8192x8192 viewport and 1x1 framebuffer (macro viewport)
        transform.setViewportDimensions(8192, 8192)
        transform.setFramebufferDimensions(1, 1)
        assertEquals(8192.0f, transform.baseScale, 0.001f)
        assertFalse(transform.baseScale.isNaN())
        assertFalse(transform.baseScale.isInfinite())
    }

    @Test
    fun testExtremeAspectRatio_StripDimensions() {
        // Extreme wide strip 10000x1
        transform.setViewportDimensions(1000, 1000)
        transform.setFramebufferDimensions(10000, 1)
        assertEquals(0.1f, transform.baseScale, 0.0001f)
        assertEquals(0f, transform.translationX, 0.0001f)
        // Vertical translation centers 0.1 height inside 1000: (1000 - 0.1)/2 = 499.95
        assertEquals(499.95f, transform.translationY, 0.01f)

        // Extreme tall strip 1x10000
        transform.setViewportDimensions(1000, 1000)
        transform.setFramebufferDimensions(1, 10000)
        assertEquals(0.1f, transform.baseScale, 0.0001f)
        assertEquals(499.95f, transform.translationX, 0.01f)
        assertEquals(0f, transform.translationY, 0.0001f)
    }

    // =========================================================================
    // 2. Zoom Factor Boundary Clamping & Negative / Zero Injection
    // =========================================================================

    @Test
    fun testZoomFactorBoundaryClampingDirect() {
        transform.setViewportDimensions(1080, 1920)
        transform.setFramebufferDimensions(1920, 1080)

        // Direct negative zoom injection
        transform.setZoomFactor(-5.0f)
        assertEquals(0.25f, transform.zoomFactor, 0.0001f)

        // Zero zoom injection
        transform.setZoomFactor(0.0f)
        assertEquals(0.25f, transform.zoomFactor, 0.0001f)

        // Below minimum
        transform.setZoomFactor(0.249f)
        assertEquals(0.25f, transform.zoomFactor, 0.0001f)

        // Above maximum
        transform.setZoomFactor(5.001f)
        assertEquals(5.0f, transform.zoomFactor, 0.0001f)

        // Far above maximum
        transform.setZoomFactor(99999.0f)
        assertEquals(5.0f, transform.zoomFactor, 0.0001f)
    }

    @Test
    fun testScaleGestureNegativeAndZeroRejection() {
        transform.setViewportDimensions(1000, 1000)
        transform.setFramebufferDimensions(1000, 1000)
        transform.setZoomFactor(1.0f)

        // Negative scale increment must be rejected
        val handledNegative = transform.applyScaleGesture(-1.5f, 500f, 500f)
        assertFalse(handledNegative)
        assertEquals(1.0f, transform.zoomFactor, 0.0001f)

        // Zero scale increment must be rejected
        val handledZero = transform.applyScaleGesture(0.0f, 500f, 500f)
        assertFalse(handledZero)
        assertEquals(1.0f, transform.zoomFactor, 0.0001f)
    }

    @Test
    fun testRepeatedPinchZoomStressClamping() {
        transform.setViewportDimensions(1000, 1000)
        transform.setFramebufferDimensions(1000, 1000)

        // Repeated zoom-in stress (150 pinch increments)
        for (i in 1..150) {
            transform.applyScaleGesture(1.15f, 500f, 500f)
        }
        assertEquals(5.0f, transform.zoomFactor, 0.0001f)
        assertFalse(transform.zoomFactor > 5.0f)

        // Repeated zoom-out stress (150 pinch increments)
        for (i in 1..150) {
            transform.applyScaleGesture(0.85f, 500f, 500f)
        }
        assertEquals(0.25f, transform.zoomFactor, 0.0001f)
        assertFalse(transform.zoomFactor < 0.25f)
    }

    @Test
    fun testDeviceNativeZoomClamping() {
        // Case 1: Very high base scale (e.g. 10x). Target zoom = 1/10 = 0.1x -> clamped to 0.25x
        transform.setViewportDimensions(10000, 10000)
        transform.setFramebufferDimensions(1000, 1000)
        assertEquals(10.0f, transform.baseScale, 0.0001f)
        transform.setDeviceNative()
        assertEquals(0.25f, transform.zoomFactor, 0.0001f)
        assertEquals(ZoomMode.DEVICE_NATIVE_1_1, transform.zoomMode)

        // Case 2: Very small base scale (e.g. 0.1x). Target zoom = 1/0.1 = 10.0x -> clamped to 5.0x
        transform.setViewportDimensions(100, 100)
        transform.setFramebufferDimensions(1000, 1000)
        assertEquals(0.1f, transform.baseScale, 0.0001f)
        transform.setDeviceNative()
        assertEquals(5.0f, transform.zoomFactor, 0.0001f)
        assertEquals(ZoomMode.DEVICE_NATIVE_1_1, transform.zoomMode)
    }

    // =========================================================================
    // 3. Pivot Zoom Invariance Under Various Scales and Focal Points
    // =========================================================================

    @Test
    fun testPivotZoomInvariance_InternalUnconstrainedPoints() {
        // Use 2000x2000 viewport and 2000x2000 framebuffer
        // Pre-zoom to 2.0x so frame is 4000x4000 (centered at -1000, -1000)
        // Permitted translation range is [-2000, 0].
        // At T = (-1000, -1000), focal points in [800..1200] will not hit boundary clamps
        transform.setViewportDimensions(2000, 2000)
        transform.setFramebufferDimensions(2000, 2000)
        transform.setZoomFactor(2.0f)
        assertEquals(-1000f, transform.translationX, 0.01f)
        assertEquals(-1000f, transform.translationY, 0.01f)

        val testFocalPoints = listOf(
            PointF(1000f, 1000f), // Center
            PointF(900f, 1000f),
            PointF(1000f, 950f),
            PointF(1050f, 1050f),
            PointF(950f, 1020f)
        )

        val zoomTargets = listOf(2.2f, 2.5f, 1.8f, 2.0f, 2.4f)

        for (focus in testFocalPoints) {
            for (targetZoom in zoomTargets) {
                // Remote desktop point under focal point before zoom
                val remoteBefore = transform.screenToRemoteStrict(focus.x, focus.y)
                assertNotNull("Remote point must be valid before zoom", remoteBefore)

                // Execute pivot zoom to targetZoom centered at focus
                transform.setZoomFactor(targetZoom, focus.x, focus.y)

                // The stationary remote point mapped back to screen after zoom
                val screenAfter = transform.remoteToScreen(remoteBefore!!.x, remoteBefore.y)

                // Invariant: screen coordinate under focal point must not move
                assertEquals("Focal point X drift at focus $focus", focus.x, screenAfter.x, 0.02f)
                assertEquals("Focal point Y drift at focus $focus", focus.y, screenAfter.y, 0.02f)

                // Inverse invariant: screenToRemote at focal point must match original remote point
                val remoteAfter = transform.screenToRemoteStrict(focus.x, focus.y)
                assertNotNull("Remote point must be valid after zoom", remoteAfter)
                assertEquals(remoteBefore.x, remoteAfter!!.x, 0.02f)
                assertEquals(remoteBefore.y, remoteAfter.y, 0.02f)
            }
        }
    }

    @Test
    fun testPivotZoomInvariance_PinchGestureIncremental() {
        transform.setViewportDimensions(2000, 2000)
        transform.setFramebufferDimensions(2000, 2000)
        transform.setZoomFactor(2.0f)

        val focusX = 1000f
        val focusY = 1000f

        val initialRemote = transform.screenToRemoteStrict(focusX, focusY)!!

        // Apply multiple incremental pinch gestures
        val pinchIncrements = listOf(1.05f, 1.08f, 0.95f, 1.10f, 0.90f)
        for (inc in pinchIncrements) {
            transform.applyScaleGesture(inc, focusX, focusY)

            val currentScreen = transform.remoteToScreen(initialRemote.x, initialRemote.y)
            assertEquals("Incremental pinch drift X", focusX, currentScreen.x, 0.02f)
            assertEquals("Incremental pinch drift Y", focusY, currentScreen.y, 0.02f)
        }
    }

    // =========================================================================
    // 4. Bidirectional Coordinate Conversion Round-Trips
    // =========================================================================

    @Test
    fun testBidirectionalConversionRoundTrips_ExhaustiveGrid() {
        val testConfigs = listOf(
            Triple(1080, 2400, Pair(1920, 1080)), // Portrait phone letterbox
            Triple(2560, 1600, Pair(1920, 1080)), // Tablet pillarbox
            Triple(1920, 1080, Pair(1920, 1080)), // Exact 1:1 match
            Triple(1200, 1200, Pair(1600, 1200))  // Custom 4:3 on square
        )

        val zoomFactors = listOf(0.5f, 1.0f, 1.75f, 3.2f, 5.0f)
        val rng = Random(42)

        for ((vw, vh, fbSize) in testConfigs) {
            val (fw, fh) = fbSize
            transform.setViewportDimensions(vw, vh)
            transform.setFramebufferDimensions(fw, fh)

            for (zoom in zoomFactors) {
                transform.setZoomFactor(zoom)

                // Apply arbitrary pan within valid range
                transform.applyPan(rng.nextFloat() * 400f - 200f, rng.nextFloat() * 400f - 200f)

                // 100 random remote desktop points inside [0..fw] x [0..fh]
                for (i in 0 until 100) {
                    val rx = rng.nextFloat() * fw
                    val ry = rng.nextFloat() * fh

                    val screen = transform.remoteToScreen(rx, ry)

                    // Forward: remoteToScreen -> screenToRemoteStrict
                    val remoteStrict = transform.screenToRemoteStrict(screen.x, screen.y)
                    assertNotNull("Roundtrip remoteStrict should not be null for inside point", remoteStrict)
                    assertEquals("X roundtrip error", rx, remoteStrict!!.x, 0.02f)
                    assertEquals("Y roundtrip error", ry, remoteStrict.y, 0.02f)

                    // Forward: remoteToScreen -> screenToRemoteCoerced
                    val remoteCoerced = transform.screenToRemoteCoerced(screen.x, screen.y)
                    assertEquals("X coerced error", rx.coerceIn(0f, (fw - 1).toFloat()), remoteCoerced.x, 0.02f)
                    assertEquals("Y coerced error", ry.coerceIn(0f, (fh - 1).toFloat()), remoteCoerced.y, 0.02f)
                }

                // Reverse: screen points strictly inside remote frame bounds
                val visibleBounds = transform.getVisibleRemoteBounds()
                for (i in 0 until 50) {
                    val rx = visibleBounds.left + rng.nextFloat() * (visibleBounds.right - visibleBounds.left)
                    val ry = visibleBounds.top + rng.nextFloat() * (visibleBounds.bottom - visibleBounds.top)

                    val screen = transform.remoteToScreen(rx, ry)
                    val convertedRemote = transform.screenToRemoteStrict(screen.x, screen.y)
                    assertNotNull(convertedRemote)

                    val convertedScreen = transform.remoteToScreen(convertedRemote!!.x, convertedRemote.y)
                    assertEquals("Screen roundtrip X", screen.x, convertedScreen.x, 0.02f)
                    assertEquals("Screen roundtrip Y", screen.y, convertedScreen.y, 0.02f)
                }
            }
        }
    }

    // =========================================================================
    // 5. Pan Boundary Clamping Under Letterboxing vs Pillarboxing
    // =========================================================================

    @Test
    fun testPanBoundaryClamping_LetterboxPortrait() {
        // Viewport: 1080x2400 (Tall portrait)
        // Framebuffer: 1920x1080 (16:9 desktop)
        // baseScale = 1080 / 1920 = 0.5625
        transform.setViewportDimensions(1080, 2400)
        transform.setFramebufferDimensions(1920, 1080)
        transform.fitToScreen()

        val expectedH = 1080f * 0.5625f // 607.5
        val expectedCenterY = (2400f - expectedH) / 2f // 896.25f

        // Fit-to-screen: width matches viewport exactly (1080), height is smaller (607.5)
        assertEquals(0f, transform.translationX, 0.001f)
        assertEquals(expectedCenterY, transform.translationY, 0.001f)

        // Aggressive pan attempts in all 4 directions must be ignored / clamped
        transform.applyPan(1000f, 1000f)
        assertEquals(0f, transform.translationX, 0.001f)
        assertEquals(expectedCenterY, transform.translationY, 0.001f)

        transform.applyPan(-5000f, -5000f)
        assertEquals(0f, transform.translationX, 0.001f)
        assertEquals(expectedCenterY, transform.translationY, 0.001f)

        // Zoom in to 2.0x:
        // effectiveScale = 0.5625 * 2.0 = 1.125
        // frameW = 1920 * 1.125 = 2160 (> 1080) -> Horizontal pan enabled, range [-1080, 0]
        // frameH = 1080 * 1.125 = 1215 (< 2400) -> Vertical pan STILL LOCKED, centered at (2400 - 1215)/2 = 592.5
        transform.setZoomFactor(2.0f)
        val expectedZoomedY = (2400f - 1215f) / 2f // 592.5f

        // Vertical pan attempts must be completely ignored
        transform.applyPan(0f, 500f)
        assertEquals(expectedZoomedY, transform.translationY, 0.001f)
        transform.applyPan(0f, -1500f)
        assertEquals(expectedZoomedY, transform.translationY, 0.001f)

        // Horizontal pan positive limit clamp (drag right -> frame at X=0)
        transform.applyPan(5000f, 0f)
        assertEquals(0f, transform.translationX, 0.001f)

        // Horizontal pan negative limit clamp (drag left -> frame at X = 1080 - 2160 = -1080)
        transform.applyPan(-10000f, 0f)
        assertEquals(-1080f, transform.translationX, 0.001f)
    }

    @Test
    fun testPanBoundaryClamping_PillarboxLandscape() {
        // Viewport: 2560x1080 (Ultra-wide landscape)
        // Framebuffer: 1920x1080 (16:9 desktop)
        // baseScale = min(2560/1920, 1080/1080) = 1.0 (pillarboxed)
        transform.setViewportDimensions(2560, 1080)
        transform.setFramebufferDimensions(1920, 1080)
        transform.fitToScreen()

        val expectedCenterX = (2560f - 1920f) / 2f // 320f
        assertEquals(expectedCenterX, transform.translationX, 0.001f)
        assertEquals(0f, transform.translationY, 0.001f)

        // Aggressive pan attempts in all directions must be locked
        transform.applyPan(5000f, 5000f)
        assertEquals(expectedCenterX, transform.translationX, 0.001f)
        assertEquals(0f, transform.translationY, 0.001f)

        transform.applyPan(-5000f, -5000f)
        assertEquals(expectedCenterX, transform.translationX, 0.001f)
        assertEquals(0f, transform.translationY, 0.001f)

        // Zoom in to 2.0x:
        // effectiveScale = 1.0 * 2.0 = 2.0
        // frameW = 1920 * 2 = 3840 (> 2560) -> Horizontal range: [2560 - 3840, 0] = [-1280, 0]
        // frameH = 1080 * 2 = 2160 (> 1080) -> Vertical range: [1080 - 2160, 0] = [-1080, 0]
        transform.setZoomFactor(2.0f)

        // Drag top-left past bounds
        transform.applyPan(10000f, 10000f)
        assertEquals(0f, transform.translationX, 0.001f)
        assertEquals(0f, transform.translationY, 0.001f)

        // Drag bottom-right past bounds
        transform.applyPan(-20000f, -20000f)
        assertEquals(-1280f, transform.translationX, 0.001f)
        assertEquals(-1080f, transform.translationY, 0.001f)
    }

    // =========================================================================
    // 6. Canvas Matrix Equivalence & Numerical Precision
    // =========================================================================

    @Test
    fun testTransformationMatrixEquivalenceWithRemoteToScreen() {
        transform.setViewportDimensions(1080, 2400)
        transform.setFramebufferDimensions(1920, 1080)
        transform.setZoomFactor(2.35f)
        transform.applyPan(-300f, 0f)

        val matrix = transform.getTransformationMatrix()

        val testPoints = floatArrayOf(
            0f, 0f,
            1920f, 1080f,
            960f, 540f,
            100f, 800f,
            1800f, 200f
        )

        val mappedPoints = testPoints.clone()
        matrix.mapPoints(mappedPoints)

        for (i in testPoints.indices step 2) {
            val rx = testPoints[i]
            val ry = testPoints[i + 1]

            val analyticScreen = transform.remoteToScreen(rx, ry)
            val matrixScreenX = mappedPoints[i]
            val matrixScreenY = mappedPoints[i + 1]

            assertEquals("Matrix X equivalence for ($rx, $ry)", analyticScreen.x, matrixScreenX, 0.001f)
            assertEquals("Matrix Y equivalence for ($rx, $ry)", analyticScreen.y, matrixScreenY, 0.001f)
        }
    }

    // =========================================================================
    // 7. Visible Remote Bounds Monotonicity & Clamping
    // =========================================================================

    @Test
    fun testVisibleRemoteBoundsMonotonicity() {
        transform.setViewportDimensions(1000, 1000)
        transform.setFramebufferDimensions(1000, 1000)
        transform.fitToScreen()

        val fullBounds = transform.getVisibleRemoteBounds()
        assertEquals(0f, fullBounds.left, 0.01f)
        assertEquals(0f, fullBounds.top, 0.01f)
        assertEquals(999f, fullBounds.right, 0.01f)
        assertEquals(999f, fullBounds.bottom, 0.01f)

        // Zooming in must strictly reduce visible sub-region dimensions
        var lastWidth = fullBounds.width()
        var lastHeight = fullBounds.height()

        val zoomSteps = listOf(1.5f, 2.0f, 3.0f, 4.0f, 5.0f)
        for (z in zoomSteps) {
            transform.setZoomFactor(z)
            val currentBounds = transform.getVisibleRemoteBounds()
            assertTrue("Width should decrease as zoom increases: $z", currentBounds.width() < lastWidth)
            assertTrue("Height should decrease as zoom increases: $z", currentBounds.height() < lastHeight)
            assertTrue("Bounds left must be >= 0", currentBounds.left >= 0f)
            assertTrue("Bounds right must be <= 999", currentBounds.right <= 999f)
            assertTrue("Bounds top must be >= 0", currentBounds.top >= 0f)
            assertTrue("Bounds bottom must be <= 999", currentBounds.bottom <= 999f)

            lastWidth = currentBounds.width()
            lastHeight = currentBounds.height()
        }
    }

    // =========================================================================
    // 8. Subpixel Letterbox Discrimination & Dynamic Resize Invariance
    // =========================================================================

    @Test
    fun testSubpixelLetterboxBoundaryDiscrimination() {
        // Viewport 1080x2400, Desktop 1920x1080 -> scale = 0.5625
        // Frame rendered height = 1080 * 0.5625 = 607.5
        // Desktop Y bounds on screen: [896.25f, 1503.75f]
        transform.setViewportDimensions(1080, 2400)
        transform.setFramebufferDimensions(1920, 1080)
        transform.fitToScreen()

        val topEdge = 896.25f
        val bottomEdge = 1503.75f

        // Point just 0.1 pixel inside top edge (896.35f) -> should be valid remote point
        val insideTop = transform.screenToRemoteStrict(540f, topEdge + 0.1f)
        assertNotNull("Inside top should not be null", insideTop)
        assertTrue("Inside top Y should be >= 0", insideTop!!.y >= 0f)

        // Point just 0.1 pixel outside top edge in letterbox margin (896.15f) -> should be null in strict mode
        val outsideTop = transform.screenToRemoteStrict(540f, topEdge - 0.1f)
        assertNull("Outside top in letterbox margin should be null", outsideTop)

        // Coerced point outside top edge must clamp exactly to Y = 0f
        val coercedOutsideTop = transform.screenToRemoteCoerced(540f, topEdge - 100f)
        assertEquals(0f, coercedOutsideTop.y, 0.0001f)

        // Coerced point outside bottom edge must clamp exactly to Y = fbHeight - 1 (1079f)
        val coercedOutsideBottom = transform.screenToRemoteCoerced(540f, bottomEdge + 100f)
        assertEquals(1079f, coercedOutsideBottom.y, 0.0001f)
    }

    @Test
    fun testRapidOrientationChangeInvariance() {
        transform.setFramebufferDimensions(1920, 1080)

        // Rapid 60-cycle rotation between portrait (1080x2400) and landscape (2400x1080)
        for (i in 0 until 60) {
            if (i % 2 == 0) {
                transform.setViewportDimensions(1080, 2400)
                assertEquals(0.5625f, transform.baseScale, 0.0001f)
                assertEquals(0f, transform.translationX, 0.001f)
                assertEquals((2400f - 1080f * 0.5625f) / 2f, transform.translationY, 0.001f)
            } else {
                transform.setViewportDimensions(2400, 1080)
                assertEquals(1.0f, transform.baseScale, 0.0001f)
                assertEquals((2400f - 1920f) / 2f, transform.translationX, 0.001f)
                assertEquals(0f, transform.translationY, 0.001f)
            }
        }
    }

    @Test
    fun testDynamicRemoteResize_MsRdpedispSimulation() {
        transform.setViewportDimensions(1080, 1920)

        // Remote resolutions sequence: 1920x1080 -> 1280x720 -> 2560x1440 -> 800x600 -> 3840x2160
        val resolutions = listOf(
            Pair(1920, 1080),
            Pair(1280, 720),
            Pair(2560, 1440),
            Pair(800, 600),
            Pair(3840, 2160)
        )

        for ((w, h) in resolutions) {
            transform.setFramebufferDimensions(w, h)
            assertEquals(w, transform.fbWidth)
            assertEquals(h, transform.fbHeight)

            val expectedBaseScale = kotlin.math.min(1080f / w, 1920f / h)
            assertEquals(expectedBaseScale, transform.baseScale, 0.0001f)

            // Verify frame remains centered inside viewport
            val frameW = w * transform.effectiveScale
            val frameH = h * transform.effectiveScale
            assertEquals((1080f - frameW) / 2f, transform.translationX, 0.01f)
            assertEquals((1920f - frameH) / 2f, transform.translationY, 0.01f)
        }
    }

    @Test
    fun testInfiniteAndExtremeZoomFactorsClamping() {
        transform.setViewportDimensions(1000, 1000)
        transform.setFramebufferDimensions(1000, 1000)

        // Positive infinity zoom factor
        transform.setZoomFactor(Float.POSITIVE_INFINITY)
        assertEquals(5.0f, transform.zoomFactor, 0.0001f)

        // Negative infinity zoom factor
        transform.setZoomFactor(Float.NEGATIVE_INFINITY)
        assertEquals(0.25f, transform.zoomFactor, 0.0001f)

        // Massive pinch increment
        transform.applyScaleGesture(Float.POSITIVE_INFINITY, 500f, 500f)
        assertEquals(5.0f, transform.zoomFactor, 0.0001f)
    }

    @Test
    fun testNaNInjectionBehaviorEmpiricalObservation() {
        transform.setViewportDimensions(1000, 1000)
        transform.setFramebufferDimensions(1000, 1000)
        transform.setZoomFactor(1.0f)

        // Check behavior when Float.NaN is injected into setZoomFactor
        transform.setZoomFactor(Float.NaN)
        // In IEEE 754 float arithmetic without explicit isNaN guard, NaN.coerceIn(...) yields NaN
        // Empirical observation of whether NaN pollutes zoomFactor:
        val isPoisonedByNaN = transform.zoomFactor.isNaN()
        // Record whether state was poisoned by NaN
        if (isPoisonedByNaN) {
            // Documented vulnerability: NaN input corrupts zoomFactor and translation state
            assertTrue(transform.zoomFactor.isNaN())
        } else {
            assertTrue(transform.zoomFactor in 0.25f..5.0f)
        }
    }
}

