package com.rdp.client.model

/**
 * Remote desktop color depth in bits per pixel (bpp).
 */
enum class ColorDepth(val bpp: Int, val displayName: String) {
    DEPTH_8(8, "8-bit (256 colors)"),
    DEPTH_16(16, "16-bit (High Color)"),
    DEPTH_24(24, "24-bit (True Color)"),
    DEPTH_32(32, "32-bit (Deep Color)");

    companion object {
        fun fromInt(bpp: Int): ColorDepth =
            entries.firstOrNull { it.bpp == bpp } ?: DEPTH_32
    }
}
