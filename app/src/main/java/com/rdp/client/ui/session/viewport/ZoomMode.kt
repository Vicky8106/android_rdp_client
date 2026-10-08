package com.rdp.client.ui.session.viewport

/**
 * Viewport zoom and presentation modes.
 */
enum class ZoomMode {
    /**
     * Scale the remote desktop to completely fit within the viewport bounds
     * with letterboxing or pillarboxing (Zoom factor = 1.0).
     */
    FIT_TO_SCREEN,

    /**
     * Map remote desktop pixels 1:1 with physical device pixels (Effective scale = 1.0).
     */
    DEVICE_NATIVE_1_1,

    /**
     * Custom user zoom scale controlled via pinch gestures or manual zoom adjustments.
     */
    CUSTOM;

    companion object {
        @JvmField
        val DEVICE_NATIVE = DEVICE_NATIVE_1_1
    }
}
