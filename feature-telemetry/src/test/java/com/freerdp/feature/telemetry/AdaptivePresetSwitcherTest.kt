package com.freerdp.feature.telemetry

import com.freerdp.feature.telemetry.preset.AdaptivePresetSwitcher
import com.freerdp.feature.telemetry.preset.PerformancePreset
import com.freerdp.feature.telemetry.preset.PerformancePresetAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hysteresis / anti-flapping boundary tests for adaptive preset switching (F23).
 * All evaluations use an explicit deterministic timestamp.
 */
class AdaptivePresetSwitcherTest {

    private val wifi = PerformancePresetAdapter.NetworkQuality.WIFI
    private val cell4g = PerformancePresetAdapter.NetworkQuality.CELLULAR_4G
    private val metered = PerformancePresetAdapter.NetworkQuality.METERED
    private val goodBattery = PerformancePresetAdapter.BatteryInfo(levelPercent = 90)
    private val criticalBattery = PerformancePresetAdapter.BatteryInfo(levelPercent = 10)

    @Test
    fun testPureFlappingNeverSwitches() {
        val switcher = AdaptivePresetSwitcher(
            initialPreset = PerformancePreset.ULTRA_LOW_LATENCY,
            confirmSamples = 3,
            minDwellMs = 3_000L,
            clockMs = { 0L }
        )

        // 20 alternating evaluations: candidates change target every sample,
        // so the confirmation streak can never reach 3.
        repeat(20) { i ->
            val network = if (i % 2 == 0) cell4g else wifi
            switcher.evaluate(network, goodBattery, nowMs = i * 100L)
        }

        assertEquals(
            "Preset must be rock-solid under pure A/B flapping",
            PerformancePreset.ULTRA_LOW_LATENCY,
            switcher.currentPreset
        )
        assertEquals(0, switcher.switchCount)
    }

    @Test
    fun testBatteryFlappingNeverSwitches() {
        val switcher = AdaptivePresetSwitcher(
            initialPreset = PerformancePreset.ULTRA_LOW_LATENCY,
            confirmSamples = 3,
            clockMs = { 0L }
        )

        val healthy = PerformancePresetAdapter.BatteryInfo(levelPercent = 90)
        val critical = PerformancePresetAdapter.BatteryInfo(levelPercent = 10, isPowerSaveMode = false)

        // ULTRA <-> BATTERY_SAVER oscillation every sample.
        repeat(12) { i ->
            val battery = if (i % 2 == 0) healthy else critical
            switcher.evaluate(wifi, battery, nowMs = i * 500L)
        }

        assertEquals(PerformancePreset.ULTRA_LOW_LATENCY, switcher.currentPreset)
        assertEquals(0, switcher.switchCount)
    }

    @Test
    fun testSustainedConditionSwitchesAfterConfirmations() {
        val switcher = AdaptivePresetSwitcher(
            initialPreset = PerformancePreset.ULTRA_LOW_LATENCY,
            confirmSamples = 3,
            minDwellMs = 0L,
            clockMs = { 0L }
        )

        // Samples 1 & 2: only a candidate — the active preset must not move.
        switcher.evaluate(wifi, criticalBattery, nowMs = 100L)
        switcher.evaluate(wifi, criticalBattery, nowMs = 200L)
        assertEquals(
            "Two confirmations must not switch yet",
            PerformancePreset.ULTRA_LOW_LATENCY,
            switcher.currentPreset
        )

        // Sample 3: third consecutive agreement => switch.
        switcher.evaluate(wifi, criticalBattery, nowMs = 300L)
        assertEquals(PerformancePreset.BATTERY_SAVER, switcher.currentPreset)
        assertEquals(1, switcher.switchCount)

        // Continued agreement is a no-op.
        repeat(5) { switcher.evaluate(wifi, criticalBattery, nowMs = 1000L + it) }
        assertEquals(1, switcher.switchCount)
    }

    @Test
    fun testDwellWindowGatesReswitching() {
        var now = 1_000L
        val switcher = AdaptivePresetSwitcher(
            initialPreset = PerformancePreset.ULTRA_LOW_LATENCY,
            confirmSamples = 1,
            minDwellMs = 3_000L,
            clockMs = { now }
        )

        // First switch is exempt from dwell (no previous switch).
        switcher.evaluate(wifi, criticalBattery, nowMs = now)
        assertEquals(PerformancePreset.BATTERY_SAVER, switcher.currentPreset)

        // Candidate within the dwell window: confirmed but blocked.
        now = 1_500L // only 500ms since last switch
        switcher.evaluate(metered, goodBattery, nowMs = now)
        assertEquals(
            "Dwell window must block an immediate reswitch",
            PerformancePreset.BATTERY_SAVER,
            switcher.currentPreset
        )

        // Same candidate once dwell elapses: switches.
        now = 4_001L // 3001ms since last switch >= 3000
        switcher.evaluate(metered, goodBattery, nowMs = now)
        assertEquals(PerformancePreset.DATA_SAVER, switcher.currentPreset)
        assertEquals(2, switcher.switchCount)
    }

    @Test
    fun testStreakResetsWhenConditionsRecoverToActive() {
        val switcher = AdaptivePresetSwitcher(
            initialPreset = PerformancePreset.ULTRA_LOW_LATENCY,
            confirmSamples = 3,
            minDwellMs = 0L,
            clockMs = { 0L }
        )

        // Two WIFI confirmations toward... (already active, so streak is empty).
        switcher.evaluate(wifi, goodBattery, nowMs = 0)
        switcher.evaluate(wifi, goodBattery, nowMs = 100)
        assertEquals(PerformancePreset.ULTRA_LOW_LATENCY, switcher.currentPreset)

        // Two CELLULAR confirmations (candidate BALANCED_MOBILE building).
        switcher.evaluate(cell4g, goodBattery, nowMs = 200)
        switcher.evaluate(cell4g, goodBattery, nowMs = 300)
        assertEquals("Still only 2 confirmations", PerformancePreset.ULTRA_LOW_LATENCY, switcher.currentPreset)

        // Recovery to active (WIFI) resets the candidate streak.
        switcher.evaluate(wifi, goodBattery, nowMs = 400)

        // Two more cellular samples: without a reset this would be 4 -> switch.
        switcher.evaluate(cell4g, goodBattery, nowMs = 500)
        switcher.evaluate(cell4g, goodBattery, nowMs = 600)
        assertEquals(
            "Streak must have been reset by the recovery sample",
            PerformancePreset.ULTRA_LOW_LATENCY,
            switcher.currentPreset
        )

        // Third consecutive post-reset sample finally switches.
        switcher.evaluate(cell4g, goodBattery, nowMs = 700)
        assertEquals(PerformancePreset.BALANCED_MOBILE, switcher.currentPreset)
        assertEquals(1, switcher.switchCount)
    }

    @Test
    fun testInvalidHysteresisParametersRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            AdaptivePresetSwitcher(confirmSamples = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AdaptivePresetSwitcher(confirmSamples = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AdaptivePresetSwitcher(minDwellMs = -1L)
        }
        // Minimal valid boundary constructs fine.
        val switcher = AdaptivePresetSwitcher(confirmSamples = 1, minDwellMs = 0L, clockMs = { 0L })
        assertEquals(PerformancePreset.BALANCED_MOBILE, switcher.currentPreset)
    }

    @Test
    fun testForcePresetBypassesHysteresisAndResetsState() {
        val switcher = AdaptivePresetSwitcher(
            initialPreset = PerformancePreset.ULTRA_LOW_LATENCY,
            confirmSamples = 3,
            minDwellMs = 10_000L,
            clockMs = { 0L }
        )

        // Build a candidate streak, then force-switch (explicit user selection).
        switcher.evaluate(cell4g, goodBattery, nowMs = 0)
        switcher.evaluate(cell4g, goodBattery, nowMs = 100)
        switcher.forcePreset(PerformancePreset.DATA_SAVER, nowMs = 200)
        assertEquals(PerformancePreset.DATA_SAVER, switcher.currentPreset)
        assertEquals(1, switcher.switchCount)

        // The half-built candidate streak must be discarded.
        switcher.evaluate(cell4g, goodBattery, nowMs = 300)
        switcher.evaluate(cell4g, goodBattery, nowMs = 400)
        assertEquals(PerformancePreset.DATA_SAVER, switcher.currentPreset)

        // Forcing the already-active preset is a no-op.
        switcher.forcePreset(PerformancePreset.DATA_SAVER, nowMs = 500)
        assertEquals(1, switcher.switchCount)
    }

    @Test
    fun testLongFlappingStormStaysOnInitialPreset() {
        val switcher = AdaptivePresetSwitcher(
            initialPreset = PerformancePreset.BALANCED_MOBILE,
            confirmSamples = 3,
            minDwellMs = 3_000L,
            clockMs = { 0L }
        )

        var now = 0L
        // Deterministic adversarial storm: 2x WIFI then 1x metered, repeating —
        // the repeated single metered sample always breaks the WIFI streak and
        // the metered streak never exceeds 1.
        repeat(50) {
            switcher.evaluate(wifi, goodBattery, nowMs = now); now += 40
            switcher.evaluate(wifi, goodBattery, nowMs = now); now += 40
            switcher.evaluate(metered, goodBattery, nowMs = now); now += 40
        }

        assertEquals(
            "2-1 storm pattern must never satisfy 3-sample confirmation for either target",
            PerformancePreset.BALANCED_MOBILE,
            switcher.currentPreset
        )
        assertEquals(0, switcher.switchCount)
        assertTrue(switcher.switchCount == 0)
    }
}
