package com.rdp.client.ui.session

import android.graphics.PointF
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rdp.client.ui.session.viewport.ViewportTransform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Headless unit tests validating Viewport transformation mathematics,
 * aspect ratio preservation, focal point invariance, and boundary clamping.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ViewportTransformTest {

    private lateinit var transform: ViewportTransform

    @Before
    fun setUp() {
        transform = ViewportTransform(minZoomFactor = 0.25f, maxZoomFactor = 5.0f)
    }

    @Test
    fun testBaseScaleLetterbox() {
        // Viewport: 1080x2400 (Portrait mobile)
        // Framebuffer: 1920x1080 (16:9 desktop)
        // scaleX = 1080 / 1920 = 0.5625
        // scaleY = 2400 / 1080 = 2.2222
        // baseScale should be min(scaleX, scaleY) = 0.5625 (letterboxed)
        transform.setViewportDimensions(1080, 2400)
        transform.setFramebufferDimensions(1920, 1080)

        assertEquals(0.5625f, transform.baseScale, 0.0001f)
        assertEquals(1.0f, transform.zoomFactor, 0.0001f)

        // Horizontal translation should be 0 (full width used)
        assertEquals(0f, transform.translationX, 0.0001f)

        // Vertical translation should center the frame: (2400 - 1080 * 0.5625) / 2 = (2400 - 607.5) / 2 = 896.25
        val expectedHeight = 1080f * 0.5625f
        val expectedY = (2400f - expectedHeight) / 2f
        assertEquals(expectedY, transform.translationY, 0.001f)
    }

    @Test
    fun testBaseScalePillarbox() {
        // Viewport: 2400x1080 (Landscape wide)
        // Framebuffer: 1920x1080 (16:9 desktop)
        // scaleX = 2400 / 1920 = 1.25
        // scaleY = 1080 / 1080 = 1.0
        // baseScale should be min(scaleX, scaleY) = 1.0 (pillarboxed)
        transform.setViewportDimensions(2400, 1080)
        transform.setFramebufferDimensions(1920, 1080)

        assertEquals(1.0f, transform.baseScale, 0.0001f)

        // Vertical translation should be 0
        assertEquals(0f, transform.translationY, 0.0001f)

        // Horizontal translation: (2400 - 1920 * 1.0) / 2 = 240.0
        assertEquals(240f, transform.translationX, 0.001f)
    }

    @Test
    fun testFocalPointInvarianceUnderPinchZoom() {
        transform.setViewportDimensions(1000, 1000)
        transform.setFramebufferDimensions(1000, 1000)
        transform.fitToScreen()

        val focusX = 500f
        val focusY = 500f

        // Convert focal point to remote desktop coordinates before zoom
        val remoteBefore = transform.screenToRemoteStrict(focusX, focusY)
        assertNotNull("Focal point should be inside remote desktop", remoteBefore)

        // Apply 2.0x pinch zoom centered at focus
        transform.applyScaleGesture(2.0f, focusX, focusY)

        // Convert remote point back to screen coordinates after zoom
        val screenAfter = transform.remoteToScreen(remoteBefore!!.x, remoteBefore.y)

        // Must remain invariant at (focusX, focusY)
        assertEquals(focusX, screenAfter.x, 0.01f)
        assertEquals(focusY, screenAfter.y, 0.01f)
    }

    @Test
    fun testZoomLimitsClamping() {
        transform.setViewportDimensions(1080, 1920)
        transform.setFramebufferDimensions(1920, 1080)

        // Attempt zooming past maxZoom (5.0f)
        transform.setZoomFactor(10.0f)
        assertEquals(5.0f, transform.zoomFactor, 0.0001f)

        // Attempt zooming below minZoom (0.25f)
        transform.setZoomFactor(0.1f)
        assertEquals(0.25f, transform.zoomFactor, 0.0001f)
    }

    @Test
    fun testZoomLockPreventsPinch() {
        transform.setViewportDimensions(1080, 1920)
        transform.setFramebufferDimensions(1920, 1080)
        transform.setZoomFactor(1.5f)

        transform.isZoomLocked = true
        val handled = transform.applyScaleGesture(2.0f, 500f, 500f)

        assertTrue(!handled)
        assertEquals(1.5f, transform.zoomFactor, 0.0001f)
    }

    @Test
    fun testPanClampingPreventsVoid() {
        transform.setViewportDimensions(1000, 1000)
        transform.setFramebufferDimensions(1000, 1000)
        // Zoom in to 2.0x -> frame dimensions = 2000x2000
        transform.setZoomFactor(2.0f)

        // Permitted translation range: [1000 - 2000, 0] = [-1000, 0]
        transform.applyPan(500f, 500f) // Attempt dragging past top-left
        assertEquals(0f, transform.translationX, 0.001f)
        assertEquals(0f, transform.translationY, 0.001f)

        transform.applyPan(-3000f, -3000f) // Attempt dragging past bottom-right
        assertEquals(-1000f, transform.translationX, 0.001f)
        assertEquals(-1000f, transform.translationY, 0.001f)
    }

    @Test
    fun testCoordinateConversionsBidirectional() {
        transform.setViewportDimensions(1080, 1920)
        transform.setFramebufferDimensions(1920, 1080)
        transform.setZoomFactor(1.75f)
        transform.applyPan(-200f, -150f)

        val originalRemote = PointF(640f, 480f)
        val screen = transform.remoteToScreen(originalRemote.x, originalRemote.y)
        val convertedRemote = transform.screenToRemoteStrict(screen.x, screen.y)

        assertNotNull(convertedRemote)
        assertEquals(originalRemote.x, convertedRemote!!.x, 0.01f)
        assertEquals(originalRemote.y, convertedRemote.y, 0.01f)
    }

    @Test
    fun testEdgeCoercionForTaskbars() {
        transform.setViewportDimensions(1080, 2400)
        transform.setFramebufferDimensions(1920, 1080)
        transform.fitToScreen()

        // Tap into letterbox margin at (500, 100) where desktop starts around Y=896
        val strictPoint = transform.screenToRemoteStrict(500f, 100f)
        assertNull("Strict point in margin should be null", strictPoint)

        val coercedPoint = transform.screenToRemoteCoerced(500f, 100f)
        // Coerced Y should clamp to top edge (0f)
        assertEquals(0f, coercedPoint.y, 0.001f)
        assertTrue(coercedPoint.x >= 0f && coercedPoint.x < 1920f)
    }
}
