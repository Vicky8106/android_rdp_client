package com.freerdp.feature.mouse

import android.content.SharedPreferences
import android.graphics.PointF
import kotlin.math.max
import kotlin.math.min

/**
 * Safe area insets (display cutout, status bar, navigation bar).
 */
data class SafeInsets(
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0
)

/**
 * Normalized overlay position coordinates in the range [0.0, 1.0].
 * Provides normalized persistence math across orientation changes and safe window inset clamping.
 */
data class OverlayCoordinates(
    val normalizedX: Float = DEFAULT_NORM_X,
    val normalizedY: Float = DEFAULT_NORM_Y
) {
    init {
        require(normalizedX in 0f..1f) { "normalizedX must be within [0.0, 1.0], was $normalizedX" }
        require(normalizedY in 0f..1f) { "normalizedY must be within [0.0, 1.0], was $normalizedY" }
    }

    /**
     * Converts normalized coordinates to screen pixel coordinates clamped within safe WindowInsets.
     * Formula:
     *   ox' = insets.left + normX * (screenWidth - overlayWidth - insets.right - insets.left)
     *   oy' = insets.top + normY * (screenHeight - overlayHeight - insets.bottom - insets.top)
     */
    fun toScreenCoordinates(
        screenWidth: Int,
        screenHeight: Int,
        overlayWidth: Int,
        overlayHeight: Int,
        insets: SafeInsets = SafeInsets()
    ): PointF {
        val minX = insets.left.toFloat()
        val maxX = max(minX, (screenWidth - overlayWidth - insets.right).toFloat())
        val minY = insets.top.toFloat()
        val maxY = max(minY, (screenHeight - overlayHeight - insets.bottom).toFloat())

        val spanX = max(0f, maxX - minX)
        val spanY = max(0f, maxY - minY)

        val x = (minX + normalizedX * spanX).coerceIn(minX, maxX)
        val y = (minY + normalizedY * spanY).coerceIn(minY, maxY)

        return PointF(x, y)
    }

    /**
     * Persists normalized coordinates to SharedPreferences.
     */
    fun saveToPreferences(prefs: SharedPreferences, keyPrefix: String = PREFS_KEY_PREFIX) {
        prefs.edit()
            .putFloat("${keyPrefix}_norm_x", normalizedX)
            .putFloat("${keyPrefix}_norm_y", normalizedY)
            .apply()
    }

    companion object {
        const val DEFAULT_NORM_X = 0.9f
        const val DEFAULT_NORM_Y = 0.5f
        const val PREFS_KEY_PREFIX = "floating_mouse_overlay"

        /**
         * Calculates normalized coordinates from screen coordinates, clamping within safe WindowInsets.
         * Formula:
         *   normX = (ox_clamped - insets.left) / max(1, screenWidth - overlayWidth - insets.right - insets.left)
         *   normY = (oy_clamped - insets.top) / max(1, screenHeight - overlayHeight - insets.bottom - insets.top)
         */
        fun fromScreenCoordinates(
            screenX: Float,
            screenY: Float,
            screenWidth: Int,
            screenHeight: Int,
            overlayWidth: Int,
            overlayHeight: Int,
            insets: SafeInsets = SafeInsets()
        ): OverlayCoordinates {
            val minX = insets.left.toFloat()
            val maxX = max(minX, (screenWidth - overlayWidth - insets.right).toFloat())
            val minY = insets.top.toFloat()
            val maxY = max(minY, (screenHeight - overlayHeight - insets.bottom).toFloat())

            val spanX = maxX - minX
            val spanY = maxY - minY

            val clampedX = screenX.coerceIn(minX, maxX)
            val clampedY = screenY.coerceIn(minY, maxY)

            val normX = if (spanX <= 0f) 0f else ((clampedX - minX) / spanX).coerceIn(0f, 1f)
            val normY = if (spanY <= 0f) 0f else ((clampedY - minY) / spanY).coerceIn(0f, 1f)

            return OverlayCoordinates(normX, normY)
        }

        /**
         * Clamps raw screen coordinates within safe window insets.
         */
        fun clamp(
            screenX: Float,
            screenY: Float,
            screenWidth: Int,
            screenHeight: Int,
            overlayWidth: Int,
            overlayHeight: Int,
            insets: SafeInsets = SafeInsets()
        ): PointF {
            val minX = insets.left.toFloat()
            val maxX = max(minX, (screenWidth - overlayWidth - insets.right).toFloat())
            val minY = insets.top.toFloat()
            val maxY = max(minY, (screenHeight - overlayHeight - insets.bottom).toFloat())

            return PointF(screenX.coerceIn(minX, maxX), screenY.coerceIn(minY, maxY))
        }

        /**
         * Snaps the overlay to the nearest safe horizontal edge (left or right).
         */
        fun snapToNearestEdge(
            currentX: Float,
            currentY: Float,
            screenWidth: Int,
            screenHeight: Int,
            overlayWidth: Int,
            overlayHeight: Int,
            insets: SafeInsets = SafeInsets()
        ): PointF {
            val minX = insets.left.toFloat()
            val maxX = max(minX, (screenWidth - overlayWidth - insets.right).toFloat())
            val minY = insets.top.toFloat()
            val maxY = max(minY, (screenHeight - overlayHeight - insets.bottom).toFloat())

            val clampedY = currentY.coerceIn(minY, maxY)
            val centerX = currentX + overlayWidth / 2f
            val screenCenterX = screenWidth / 2f

            val snappedX = if (centerX < screenCenterX) minX else maxX
            return PointF(snappedX, clampedY)
        }

        /**
         * Loads normalized coordinates from SharedPreferences.
         */
        fun loadFromPreferences(
            prefs: SharedPreferences,
            keyPrefix: String = PREFS_KEY_PREFIX
        ): OverlayCoordinates {
            val normX = prefs.getFloat("${keyPrefix}_norm_x", DEFAULT_NORM_X).coerceIn(0f, 1f)
            val normY = prefs.getFloat("${keyPrefix}_norm_y", DEFAULT_NORM_Y).coerceIn(0f, 1f)
            return OverlayCoordinates(normX, normY)
        }
    }
}
