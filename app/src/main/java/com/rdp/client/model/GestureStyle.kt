package com.rdp.client.model

/**
 * Touchscreen input gesture translation style.
 */
enum class GestureStyle(val id: String, val displayName: String) {
    AUTO("auto", "Auto"),
    TOUCHSCREEN("touchscreen", "Direct Touchscreen"),
    TOUCHPAD("touchpad", "Relative Touchpad");

    companion object {
        fun fromString(id: String?): GestureStyle =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: AUTO
    }
}
