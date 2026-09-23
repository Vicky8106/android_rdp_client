package com.freerdp.client.ui.components

import com.freerdp.core.engine.PerformancePreset as CorePreset
import com.freerdp.feature.telemetry.preset.PerformancePreset as TelemetryPreset

/** Human-readable descriptions for the performance preset pickers (Settings + editor). */
object PresetDescription {

    fun forPreset(core: CorePreset): String {
        val preset = TelemetryPreset.fromCorePreset(core)
        val blurb = when (preset) {
            TelemetryPreset.ULTRA_LOW_LATENCY -> "snappiest input — best on Wi-Fi/LAN"
            TelemetryPreset.BALANCED_MOBILE -> "rich and smooth on cellular"
            TelemetryPreset.DATA_SAVER -> "least bandwidth on metered links"
            TelemetryPreset.BATTERY_SAVER -> "longest battery life"
        }
        return "${preset.targetFps} FPS · ${preset.colorDepth}-bit color · $blurb"
    }
}
