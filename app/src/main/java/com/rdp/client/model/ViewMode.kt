package com.rdp.client.model

/**
 * In-session remote desktop viewport mode.
 */
enum class ViewMode(val code: Int, val displayName: String) {
    NORMAL(0, "Normal (Interactive)"),
    VIEW_ONLY(1, "View Only (No Input)"),
    BACKGROUND(2, "Background (No Video)");

    companion object {
        fun fromInt(code: Int): ViewMode =
            entries.firstOrNull { it.code == code } ?: NORMAL
    }
}
