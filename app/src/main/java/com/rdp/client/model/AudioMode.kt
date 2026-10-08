package com.rdp.client.model

/**
 * Audio redirection policy for RDP sound playback.
 */
enum class AudioMode(val code: Int, val displayName: String) {
    LOCAL(0, "Play on this device"),
    REMOTE(1, "Play on remote computer"),
    MUTE(2, "Do not play");

    companion object {
        fun fromInt(code: Int): AudioMode =
            entries.firstOrNull { it.code == code } ?: LOCAL
    }
}
