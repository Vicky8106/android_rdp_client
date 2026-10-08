package com.rdp.client.model

/**
 * Screen orientation lock modes.
 */
enum class ScreenOrientation(val id: String, val displayName: String) {
    AUTO("auto", "Auto Sensor"),
    PORTRAIT("portrait", "Portrait"),
    LANDSCAPE("landscape", "Landscape");

    companion object {
        fun fromString(id: String?): ScreenOrientation =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: AUTO
    }
}
