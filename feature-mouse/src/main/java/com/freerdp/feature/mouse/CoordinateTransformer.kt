package com.freerdp.feature.mouse

import android.graphics.Matrix
import android.graphics.PointF
import kotlin.math.max
import kotlin.math.min

/**
 * Handles 2D affine coordinate transformation between Android screen touch viewport
 * and remote RDP desktop surface.
 *
 * Supports zoom scaling, focal point invariance, pan translation offsets,
 * viewport boundary clamping, and bidirectional screen <-> desktop coordinate mapping.
 */
class CoordinateTransformer(
    remoteWidth: Int = 1920,
    remoteHeight: Int = 1080,
    viewWidth: Int = 1080,
    viewHeight: Int = 2400,
    val minScale: Float = 0.25f,
    val maxScale: Float = 5.0f
) {
    var remoteWidth: Int = max(1, remoteWidth)
        private set
    var remoteHeight: Int = max(1, remoteHeight)
        private set
    var viewWidth: Int = max(1, viewWidth)
        private set
    var viewHeight: Int = max(1, viewHeight)
        private set

    var scale: Float = 1.0f
        private set
    var translationX: Float = 0.0f
        private set
    var translationY: Float = 0.0f
        private set

    init {
        clampAndCenterViewport()
    }

    /**
     * Updates the remote RDP desktop dimensions.
     */
    fun setRemoteResolution(width: Int, height: Int) {
        remoteWidth = max(1, width)
        remoteHeight = max(1, height)
        clampAndCenterViewport()
    }

    /**
     * Updates the local Android viewport dimensions.
     */
    fun setViewportSize(width: Int, height: Int) {
        viewWidth = max(1, width)
        viewHeight = max(1, height)
        clampAndCenterViewport()
    }

    /**
     * Sets absolute zoom scale around a given focal point on screen.
     */
    fun setScale(newScale: Float, focusX: Float = viewWidth / 2f, focusY: Float = viewHeight / 2f) {
        val clampedScale = newScale.coerceIn(minScale, maxScale)
        if (scale == clampedScale) return

        val k = clampedScale / scale
        translationX = focusX - (focusX - translationX) * k
        translationY = focusY - (focusY - translationY) * k
        scale = clampedScale

        clampAndCenterViewport()
    }

    /**
     * Applies relative zoom factor around a given focal point.
     */
    fun applyZoom(scaleFactor: Float, focusX: Float = viewWidth / 2f, focusY: Float = viewHeight / 2f) {
        setScale(scale * scaleFactor, focusX, focusY)
    }

    /**
     * Applies pan delta to translation with viewport boundary clamping.
     */
    fun applyPan(deltaX: Float, deltaY: Float) {
        val contentW = remoteWidth * scale
        val contentH = remoteHeight * scale

        // Only allow pan in a dimension if content exceeds viewport
        if (contentW > viewWidth) {
            translationX += deltaX
        }
        if (contentH > viewHeight) {
            translationY += deltaY
        }
        clampAndCenterViewport()
    }

    /**
     * Resets scale and centers the remote desktop to fit entirely within the viewport.
     */
    fun resetToFit() {
        val fitScale = min(
            viewWidth.toFloat() / remoteWidth,
            viewHeight.toFloat() / remoteHeight
        )
        scale = fitScale.coerceIn(minScale, maxScale)
        centerViewport()
    }

    /**
     * Clamps translation to prevent panning into void space, or centers if content fits viewport.
     */
    private fun clampAndCenterViewport() {
        val contentW = remoteWidth * scale
        val contentH = remoteHeight * scale

        if (contentW <= viewWidth) {
            translationX = (viewWidth - contentW) / 2f
        } else {
            val minTransX = viewWidth - contentW
            val maxTransX = 0f
            translationX = translationX.coerceIn(minTransX, maxTransX)
        }

        if (contentH <= viewHeight) {
            translationY = (viewHeight - contentH) / 2f
        } else {
            val minTransY = viewHeight - contentH
            val maxTransY = 0f
            translationY = translationY.coerceIn(minTransY, maxTransY)
        }
    }

    private fun centerViewport() {
        val contentW = remoteWidth * scale
        val contentH = remoteHeight * scale
        translationX = (viewWidth - contentW) / 2f
        translationY = (viewHeight - contentH) / 2f
    }

    /**
     * Converts Android screen touch coordinates to remote RDP desktop coordinates (float).
     * Automatically clamps to [0, remoteWidth - 1] and [0, remoteHeight - 1].
     */
    fun screenToDesktop(screenX: Float, screenY: Float): PointF {
        val rawDx = (screenX - translationX) / scale
        val rawDy = (screenY - translationY) / scale
        val clampedDx = rawDx.coerceIn(0f, max(0f, (remoteWidth - 1).toFloat()))
        val clampedDy = rawDy.coerceIn(0f, max(0f, (remoteHeight - 1).toFloat()))
        return PointF(clampedDx, clampedDy)
    }

    /**
     * Converts Android screen touch coordinates to remote RDP desktop integer coordinates.
     */
    fun screenToDesktopInt(screenX: Float, screenY: Float): Pair<Int, Int> {
        val pt = screenToDesktop(screenX, screenY)
        val x = pt.x.toInt().coerceIn(0, max(0, remoteWidth - 1))
        val y = pt.y.toInt().coerceIn(0, max(0, remoteHeight - 1))
        return Pair(x, y)
    }

    /**
     * Converts remote RDP desktop coordinates to Android screen pixel coordinates.
     */
    fun desktopToScreen(desktopX: Float, desktopY: Float): PointF {
        val sx = desktopX * scale + translationX
        val sy = desktopY * scale + translationY
        return PointF(sx, sy)
    }

    /**
     * Populates an Android Matrix with current scale and translation.
     */
    fun toMatrix(matrix: Matrix = Matrix()): Matrix {
        matrix.reset()
        matrix.postScale(scale, scale)
        matrix.postTranslate(translationX, translationY)
        return matrix
    }

    /**
     * Direct setter for test scenarios or explicit state restoration.
     */
    fun setTransform(newScale: Float, transX: Float, transY: Float, clamp: Boolean = false) {
        scale = newScale.coerceIn(minScale, maxScale)
        translationX = transX
        translationY = transY
        if (clamp) {
            clampAndCenterViewport()
        }
    }

    /**
     * Converts given viewport touch point to corresponding framebuffer point.
     * Returns null if point lies outside of the active framebuffer (e.g. In letterbox margin).
     */
    fun toFb(vpPoint: PointF): PointF? {
        val fbX = (vpPoint.x - translationX) / scale
        val fbY = (vpPoint.y - translationY) / scale
        if (fbX < 0f || fbY < 0f || fbX >= remoteWidth || fbY >= remoteHeight) {
            return null
        }
        return PointF(fbX, fbY)
    }

    /**
     * Converts given viewport touch point to framebuffer point without boundary checks.
     */
    fun toFbUnchecked(vpPoint: PointF): PointF {
        val fbX = (vpPoint.x - translationX) / scale
        val fbY = (vpPoint.y - translationY) / scale
        return PointF(fbX, fbY)
    }

    /**
     * Converts framebuffer desktop coordinates to Android screen pixel coordinates.
     */
    fun toVP(fbPoint: PointF): PointF {
        return desktopToScreen(fbPoint.x, fbPoint.y)
    }

    /**
     * When user taps outside the frame (e.g. In letterbox black bars),
     * coerces the point to the nearest framebuffer edge pixel in screen coordinates.
     * This allows triggering auto-hiding remote taskbars and panels even with letterboxing.
     */
    fun coerceToFbEdge(vpPoint: PointF): PointF? {
        if (remoteWidth < 1 || remoteHeight < 1) return null
        val fb = toFbUnchecked(vpPoint)
        val clampedFb = PointF(
            fb.x.coerceIn(0f, max(0, remoteWidth - 1).toFloat()),
            fb.y.coerceIn(0f, max(0, remoteHeight - 1).toFloat())
        )
        return toVP(clampedFb)
    }

    /**
     * Returns the coerced desktop/framebuffer coordinate for points outside the frame.
     */
    fun coerceToFbEdgeDesktop(vpPoint: PointF): PointF? {
        if (remoteWidth < 1 || remoteHeight < 1) return null
        val fb = toFbUnchecked(vpPoint)
        return PointF(
            fb.x.coerceIn(0f, max(0, remoteWidth - 1).toFloat()),
            fb.y.coerceIn(0f, max(0, remoteHeight - 1).toFloat())
        )
    }

    /**
     * Pans the viewport to keep the given desktop coordinate centered on screen
     * if the remote frame is larger than the viewport.
     */
    fun autoCenterOn(desktopX: Float, desktopY: Float) {
        val vp = desktopToScreen(desktopX, desktopY)
        val centerDiffX = (viewWidth / 2f) - vp.x
        val centerDiffY = (viewHeight / 2f) - vp.y
        applyPan(centerDiffX, centerDiffY)
    }
}
