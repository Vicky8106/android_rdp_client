package com.rdp.client.model

/**
 * Framebuffer resolution calculation mode.
 */
enum class ResolutionMode(val code: Int, val displayName: String) {
    FIT_TO_SCREEN(0, "Fit to Screen"),
    NATIVE(1, "Device Native"),
    CUSTOM(2, "Custom (WxH)"),
    DYNAMIC(3, "Dynamic (MS-RDPEDISP)");

    companion object {
        fun fromInt(code: Int): ResolutionMode =
            entries.firstOrNull { it.code == code } ?: FIT_TO_SCREEN
    }
}
