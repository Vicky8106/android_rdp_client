package com.rdp.client.ui.session.viewport

import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.min

/**
 * Pure mathematical engine managing viewport transformation, scaling, and coordinate mapping.
 * Independent of Android rendering APIs, enabling comprehensive JVM / Robolectric testing.
 */
class ViewportTransform(
    var minZoomFactor: Float = 0.25f,
    var maxZoomFactor: Float = 5.0f
) {
    // Viewport dimensions in physical device pixels
    var viewportWidth: Int = 0
        private set
    var viewportHeight: Int = 0
        private set

    // Remote desktop framebuffer dimensions in pixels
    var fbWidth: Int = 0
        private set
    var fbHeight: Int = 0
        private set

    // Base scale required to fit framebuffer inside viewport while preserving aspect ratio
    var baseScale: Float = 1.0f
        private set

    // User zoom factor relative to base scale: [minZoomFactor, maxZoomFactor]
    var zoomFactor: Float = 1.0f
        private set

    // Effective scale = baseScale * zoomFactor
    val effectiveScale: Float
        get() = baseScale * zoomFactor

    // Pan translation offsets in screen pixels
    var translationX: Float = 0f
        private set
    var translationY: Float = 0f
        private set

    // Active zoom mode
    var zoomMode: ZoomMode = ZoomMode.FIT_TO_SCREEN
        private set

    // Whether pinch-to-zoom is locked against accidental scale gestures
    var isZoomLocked: Boolean = false

    // Cached Android graphics matrices
    private val forwardMatrix = Matrix()
    private val inverseMatrix = Matrix()
    private var isMatrixDirty = true

    /**
     * Updates viewport dimensions (called on view size change or orientation change).
     */
    fun setViewportDimensions(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        this.viewportWidth = width
        this.viewportHeight = height
        recalculateBaseScale()
    }

    /**
     * Updates remote desktop framebuffer dimensions (called on session connect or MS-RDPEDISP resize).
     */
    fun setFramebufferDimensions(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        this.fbWidth = width
        this.fbHeight = height
        recalculateBaseScale()
    }

    private fun recalculateBaseScale() {
        if (viewportWidth <= 0 || viewportHeight <= 0 || fbWidth <= 0 || fbHeight <= 0) {
            baseScale = 1.0f
            return
        }

        val scaleX = viewportWidth.toFloat() / fbWidth.toFloat()
        val scaleY = viewportHeight.toFloat() / fbHeight.toFloat()
        baseScale = min(scaleX, scaleY)

        when (zoomMode) {
            ZoomMode.FIT_TO_SCREEN -> fitToScreen()
            ZoomMode.DEVICE_NATIVE_1_1 -> setDeviceNative()
            ZoomMode.CUSTOM -> clampAndCenter()
        }
    }

    /**
     * Configures the viewport to Fit to Screen (100% visible, centered with letterboxing/pillarboxing).
     */
    fun fitToScreen() {
        zoomMode = ZoomMode.FIT_TO_SCREEN
        zoomFactor = 1.0f
        centerFrame()
    }

    /**
     * Configures the viewport to Device Native (1:1 remote pixel to device pixel).
     */
    fun setDeviceNative() {
        if (baseScale <= 0f) return
        zoomMode = ZoomMode.DEVICE_NATIVE_1_1
        val targetZoom = (1.0f / baseScale).coerceIn(minZoomFactor, maxZoomFactor)
        zoomFactor = targetZoom
        clampAndCenter()
    }

    /**
     * Sets explicit zoom factor centered at the given screen focal point.
     */
    fun setZoomFactor(
        targetZoom: Float,
        focusX: Float = viewportWidth / 2f,
        focusY: Float = viewportHeight / 2f
    ) {
        if (targetZoom.isNaN()) return
        val clampedZoom = targetZoom.coerceIn(minZoomFactor, maxZoomFactor)
        if (clampedZoom == zoomFactor) return

        zoomMode = ZoomMode.CUSTOM
        val k = clampedZoom / zoomFactor
        zoomFactor = clampedZoom

        // Pivot zoom formula: T' = F * (1 - k) + T * k
        translationX = focusX * (1f - k) + translationX * k
        translationY = focusY * (1f - k) + translationY * k

        clampAndCenter()
    }

    /**
     * Incremental pinch-to-zoom applied around gesture focal point.
     * Returns true if scale was applied, false if zoom is locked or at boundary.
     */
    fun applyScaleGesture(scaleFactorIncrement: Float, focusX: Float, focusY: Float): Boolean {
        if (isZoomLocked || scaleFactorIncrement.isNaN() || scaleFactorIncrement <= 0f) return false

        val currentZoom = zoomFactor
        val candidateZoom = (currentZoom * scaleFactorIncrement).coerceIn(minZoomFactor, maxZoomFactor)
        if (candidateZoom == currentZoom) return false

        zoomMode = ZoomMode.CUSTOM
        val k = candidateZoom / currentZoom
        zoomFactor = candidateZoom

        translationX = focusX * (1f - k) + translationX * k
        translationY = focusY * (1f - k) + translationY * k

        clampAndCenter()
        return true
    }

    /**
     * Applies pan translation delta (dx, dy).
     */
    fun applyPan(dx: Float, dy: Float) {
        val frameW = fbWidth * effectiveScale
        val frameH = fbHeight * effectiveScale

        // Only allow horizontal pan if frame is wider than viewport
        if (frameW > viewportWidth) {
            translationX += dx
        }
        // Only allow vertical pan if frame is taller than viewport
        if (frameH > viewportHeight) {
            translationY += dy
        }

        clampAndCenter()
    }

    /**
     * Centers the frame within the viewport.
     */
    private fun centerFrame() {
        val frameW = fbWidth * effectiveScale
        val frameH = fbHeight * effectiveScale
        translationX = (viewportWidth - frameW) / 2f
        translationY = (viewportHeight - frameH) / 2f
        isMatrixDirty = true
    }

    /**
     * Clamps translation within safe boundaries, centering axes that fit entirely inside the viewport.
     */
    fun clampAndCenter() {
        val frameW = fbWidth * effectiveScale
        val frameH = fbHeight * effectiveScale

        // Horizontal axis
        translationX = if (frameW <= viewportWidth) {
            (viewportWidth - frameW) / 2f
        } else {
            val minX = viewportWidth - frameW
            val maxX = 0f
            translationX.coerceIn(minX, maxX)
        }

        // Vertical axis
        translationY = if (frameH <= viewportHeight) {
            (viewportHeight - frameH) / 2f
        } else {
            val minY = viewportHeight - frameH
            val maxY = 0f
            translationY.coerceIn(minY, maxY)
        }

        isMatrixDirty = true
    }

    /**
     * Recomputes forward and inverse transformation matrices.
     */
    private fun ensureMatrices() {
        if (!isMatrixDirty) return
        forwardMatrix.reset()
        forwardMatrix.postScale(effectiveScale, effectiveScale)
        forwardMatrix.postTranslate(translationX, translationY)

        forwardMatrix.invert(inverseMatrix)
        isMatrixDirty = false
    }

    /**
     * Converts a screen coordinate to a remote desktop framebuffer coordinate.
     * Returns null if the point falls outside the remote frame (e.g. in letterbox padding).
     */
    fun screenToRemoteStrict(screenX: Float, screenY: Float): PointF? {
        if (effectiveScale <= 0f || fbWidth <= 0 || fbHeight <= 0) return null

        val remoteX = (screenX - translationX) / effectiveScale
        val remoteY = (screenY - translationY) / effectiveScale

        return if (remoteX in 0f..fbWidth.toFloat() && remoteY in 0f..fbHeight.toFloat()) {
            PointF(remoteX, remoteY)
        } else {
            null
        }
    }

    /**
     * Converts a screen coordinate to a remote desktop framebuffer coordinate,
     * coercing the result to the nearest desktop edge (AVNC parity for taskbars).
     */
    fun screenToRemoteCoerced(screenX: Float, screenY: Float): PointF {
        if (screenX.isNaN() || screenY.isNaN() || effectiveScale <= 0f || fbWidth <= 0 || fbHeight <= 0) {
            return PointF(0f, 0f)
        }

        val remoteX = ((screenX - translationX) / effectiveScale).coerceIn(0f, (fbWidth - 1).toFloat())
        val remoteY = ((screenY - translationY) / effectiveScale).coerceIn(0f, (fbHeight - 1).toFloat())

        return PointF(remoteX, remoteY)
    }

    /**
     * Converts a remote desktop framebuffer coordinate to a screen coordinate.
     */
    fun remoteToScreen(remoteX: Float, remoteY: Float): PointF {
        val screenX = remoteX * effectiveScale + translationX
        val screenY = remoteY * effectiveScale + translationY
        return PointF(screenX, screenY)
    }

    /**
     * Calculates the visible rectangular sub-region of the remote desktop in framebuffer coordinates.
     */
    fun getVisibleRemoteBounds(): RectF {
        val p0 = screenToRemoteCoerced(0f, 0f)
        val p1 = screenToRemoteCoerced(viewportWidth.toFloat(), viewportHeight.toFloat())
        return RectF(p0.x, p0.y, p1.x, p1.y)
    }

    /**
     * Returns the forward transformation matrix for Canvas drawing.
     */
    fun getTransformationMatrix(): Matrix {
        ensureMatrices()
        return Matrix(forwardMatrix)
    }
}
