package com.freerdp.feature.telemetry.preset

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager

/**
 * Automatically adapts [PerformancePreset] based on live network quality and battery constraints.
 */
object PerformancePresetAdapter {

    enum class NetworkQuality {
        WIFI,
        CELLULAR_5G,
        CELLULAR_4G,
        CELLULAR_3G,
        METERED,
        OFFLINE,
        UNKNOWN
    }

    data class BatteryInfo(
        val levelPercent: Int,
        val isPowerSaveMode: Boolean = false,
        val isCharging: Boolean = false
    )

    /**
     * Determines the optimal [PerformancePreset] based on network and battery conditions.
     *
     * Rules:
     * 1. Battery Saver: Triggered if power save mode is enabled, or battery <= 15% and not charging.
     * 2. Data Saver: Triggered if network is metered, 3G, or battery <= 25% and not charging.
     * 3. Balanced Mobile: Triggered on cellular networks (4G/5G).
     * 4. Ultra-Low Latency: Triggered on unmetered Wi-Fi with healthy battery (> 25% or charging).
     */
    fun determinePreset(
        networkQuality: NetworkQuality,
        batteryInfo: BatteryInfo,
        preferQuality: Boolean = false
    ): PerformancePreset {
        // Priority 1: Battery constraints
        if (batteryInfo.isPowerSaveMode || (batteryInfo.levelPercent <= 15 && !batteryInfo.isCharging)) {
            return PerformancePreset.BATTERY_SAVER
        }

        // Priority 2: Data conservation constraints
        if (networkQuality == NetworkQuality.METERED || networkQuality == NetworkQuality.CELLULAR_3G) {
            return PerformancePreset.DATA_SAVER
        }

        if (batteryInfo.levelPercent <= 25 && !batteryInfo.isCharging) {
            return PerformancePreset.DATA_SAVER
        }

        // Priority 3: Cellular mobile connections
        if (networkQuality == NetworkQuality.CELLULAR_4G || networkQuality == NetworkQuality.CELLULAR_5G) {
            return PerformancePreset.BALANCED_MOBILE
        }

        // Priority 4: High-bandwidth unmetered Wi-Fi / Ethernet
        if (networkQuality == NetworkQuality.WIFI) {
            return PerformancePreset.ULTRA_LOW_LATENCY
        }

        return if (preferQuality) PerformancePreset.ULTRA_LOW_LATENCY else PerformancePreset.BALANCED_MOBILE
    }

    /**
     * Reads current battery status from Android system services.
     */
    fun getBatteryInfo(context: Context): BatteryInfo {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val isPowerSave = powerManager?.isPowerSaveMode ?: false

        val batteryIntent = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1

        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        val batteryPct = if (level >= 0 && scale > 0) (level * 100) / scale else 50

        return BatteryInfo(
            levelPercent = batteryPct,
            isPowerSaveMode = isPowerSave,
            isCharging = isCharging
        )
    }

    /**
     * Reads current network quality from Android ConnectivityManager.
     */
    fun getNetworkQuality(context: Context): NetworkQuality {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return NetworkQuality.UNKNOWN
        val activeNetwork = cm.activeNetwork ?: return NetworkQuality.OFFLINE
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return NetworkQuality.UNKNOWN

        val isMetered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)

        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> {
                if (isMetered) NetworkQuality.METERED else NetworkQuality.WIFI
            }
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                if (isMetered) NetworkQuality.METERED else NetworkQuality.CELLULAR_5G
            }
            else -> NetworkQuality.UNKNOWN
        }
    }
}

/**
 * Stateful preset selector with anti-flapping hysteresis.
 *
 * Network/battery signals oscillate at cell-tower boundaries (RSRP hysteresis is the
 * carrier's problem; ours is preset thrash: every switch renegotiates bpp/codec/GFX and
 * causes a visible session hitch). Switching therefore requires BOTH:
 *
 * 1. [confirmSamples] consecutive evaluations agreeing on the same target preset, and
 * 2. at least [minDwellMs] elapsed since the last switch (first switch exempt).
 *
 * Evaluations whose target equals the active preset reset the confirmation streak, so
 * a signal that flaps A/B/A/B can never accumulate confirmations — switching is
 * impossible under pure flapping by construction, not by tuning.
 *
 * All state transitions are guarded by a lock; the clock is injectable for
 * deterministic virtual-time tests.
 */
class AdaptivePresetSwitcher(
    initialPreset: PerformancePreset = PerformancePreset.BALANCED_MOBILE,
    private val confirmSamples: Int = DEFAULT_CONFIRM_SAMPLES,
    private val minDwellMs: Long = DEFAULT_MIN_DWELL_MS,
    private val clockMs: () -> Long = { System.currentTimeMillis() }
) {

    init {
        require(confirmSamples >= 1) { "confirmSamples must be >= 1, got $confirmSamples" }
        require(minDwellMs >= 0L) { "minDwellMs must be >= 0, got $minDwellMs" }
    }

    private val lock = Any()
    private var active: PerformancePreset = initialPreset
    private var pendingTarget: PerformancePreset? = null
    private var pendingCount: Int = 0
    private var lastSwitchAtMs: Long? = null // null = never switched (first switch exempt from dwell)

    /** Currently active preset (never thrashes; only changes under hysteresis rules). */
    val currentPreset: PerformancePreset get() = synchronized(lock) { active }

    /** Number of switches performed since construction (flapping detector for telemetry). */
    var switchCount: Int = 0
        private set

    /**
     * Evaluates conditions and applies the hysteresis state machine.
     *
     * @param nowMs evaluation timestamp in the same timebase as [clockMs] (injectable for tests)
     * @return the active preset after evaluation (may still be the previous one while a
     *         candidate gathers confirmations)
     */
    fun evaluate(
        networkQuality: PerformancePresetAdapter.NetworkQuality,
        batteryInfo: PerformancePresetAdapter.BatteryInfo,
        preferQuality: Boolean = false,
        nowMs: Long = clockMs()
    ): PerformancePreset = synchronized(lock) {
        val desired = PerformancePresetAdapter.determinePreset(networkQuality, batteryInfo, preferQuality)

        if (desired == active) {
            // Conditions recovered or converged: drop any half-built candidate streak.
            pendingTarget = null
            pendingCount = 0
            return active
        }

        if (desired == pendingTarget) {
            pendingCount++
        } else {
            pendingTarget = desired
            pendingCount = 1
        }

        val dwellSatisfied = lastSwitchAtMs?.let { nowMs - it >= minDwellMs } ?: true
        if (pendingCount >= confirmSamples && dwellSatisfied) {
            active = desired
            pendingTarget = null
            pendingCount = 0
            lastSwitchAtMs = nowMs
            switchCount++
        }
        return active
    }

    /** Forces the active preset (explicit user choice), resetting hysteresis state. */
    fun forcePreset(preset: PerformancePreset, nowMs: Long = clockMs()) {
        synchronized(lock) {
            if (preset != active) {
                active = preset
                switchCount++
                lastSwitchAtMs = nowMs
            }
            pendingTarget = null
            pendingCount = 0
        }
    }

    companion object {
        const val DEFAULT_CONFIRM_SAMPLES: Int = 3
        const val DEFAULT_MIN_DWELL_MS: Long = 3_000L
    }
}
