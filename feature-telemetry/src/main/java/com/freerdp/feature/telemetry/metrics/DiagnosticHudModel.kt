package com.freerdp.feature.telemetry.metrics

import java.util.Locale

/**
 * Diagnostic HUD overlay model for real-time remote desktop streaming diagnostics.
 */
data class DiagnosticHudModel(
    val hudText: String,
    val fps: Float,
    val rttMs: Long,
    val p95RttMs: Double,
    val p99RttMs: Double,
    val jitterMs: Double,
    val bandwidthKbps: Long,
    val droppedFrames: Long,
    val connectionState: String,
    val quality: ConnectionQuality
) {
    enum class ConnectionQuality {
        EXCELLENT, // < 25ms RTT, 60 FPS
        GOOD,      // < 60ms RTT, > 30 FPS
        FAIR,      // < 120ms RTT, > 15 FPS
        POOR       // >= 120ms RTT or heavy drops
    }

    companion object {
        fun format(
            fps: Float,
            rttMs: Long,
            p95RttMs: Double,
            p99RttMs: Double,
            jitterMs: Double,
            bandwidthKbps: Long,
            droppedFrames: Long,
            connectionState: String
        ): DiagnosticHudModel {
            val quality = when {
                rttMs in 1..25 && fps >= 55f -> ConnectionQuality.EXCELLENT
                rttMs in 1..60 && fps >= 28f -> ConnectionQuality.GOOD
                rttMs in 1..120 && fps >= 14f -> ConnectionQuality.FAIR
                else -> ConnectionQuality.POOR
            }

            val text = String.format(
                Locale.US,
                "FPS: %.0f | RTT: %dms (p95: %.1f) | Jitter: %.1fms | %d Kbps | Drops: %d | %s",
                fps,
                rttMs,
                p95RttMs,
                jitterMs,
                bandwidthKbps,
                droppedFrames,
                connectionState
            )

            return DiagnosticHudModel(
                hudText = text,
                fps = fps,
                rttMs = rttMs,
                p95RttMs = p95RttMs,
                p99RttMs = p99RttMs,
                jitterMs = jitterMs,
                bandwidthKbps = bandwidthKbps,
                droppedFrames = droppedFrames,
                connectionState = connectionState,
                quality = quality
            )
        }
    }
}
