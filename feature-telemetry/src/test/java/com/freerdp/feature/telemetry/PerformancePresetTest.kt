package com.freerdp.feature.telemetry

import com.freerdp.feature.telemetry.preset.CodecQualityLevel
import com.freerdp.feature.telemetry.preset.PerformancePreset
import com.freerdp.feature.telemetry.preset.PerformancePresetAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformancePresetTest {

    @Test
    fun testPresetConfigurations() {
        // 1. Ultra-Low Latency
        val ultra = PerformancePreset.ULTRA_LOW_LATENCY
        assertEquals(60, ultra.targetFps)
        assertEquals(32, ultra.colorDepth)
        assertFalse("Sound should be disabled in Ultra-Low Latency to avoid audio jitter", ultra.soundEnabled)
        assertEquals("NONE", ultra.compressionCodec)
        assertFalse(ultra.wallpaperEnabled)
        assertFalse(ultra.dynamicThrottling)
        assertEquals(10.0, ultra.telemetryRateHz, 0.001)

        // 2. Balanced Mobile
        val balanced = PerformancePreset.BALANCED_MOBILE
        assertEquals(30, balanced.targetFps)
        assertEquals(16, balanced.colorDepth)
        assertTrue("Sound should be enabled in Balanced Mobile", balanced.soundEnabled)
        assertEquals("RFX", balanced.compressionCodec)
        assertFalse(balanced.wallpaperEnabled)
        assertEquals(2.0, balanced.telemetryRateHz, 0.001)

        // 3. Data Saver
        val dataSaver = PerformancePreset.DATA_SAVER
        assertEquals(15, dataSaver.targetFps)
        assertEquals(8, dataSaver.colorDepth)
        assertFalse(dataSaver.soundEnabled)
        assertEquals("RLE", dataSaver.compressionCodec)
        assertFalse(dataSaver.fontSmoothingEnabled)
        assertTrue("Dynamic throttling should be enabled in Data Saver", dataSaver.dynamicThrottling)
        assertEquals(1.0, dataSaver.telemetryRateHz, 0.001)

        // 4. Battery Saver
        val batterySaver = PerformancePreset.BATTERY_SAVER
        assertEquals(15, batterySaver.targetFps)
        assertEquals(16, batterySaver.colorDepth)
        assertFalse(batterySaver.soundEnabled)
        assertEquals("RLE", batterySaver.compressionCodec)
        assertTrue("Dynamic throttling should be enabled in Battery Saver", batterySaver.dynamicThrottling)
        assertEquals(0.5, batterySaver.telemetryRateHz, 0.001)
    }

    @Test
    fun testCliArgumentsGeneration() {
        val ultraArgs = PerformancePreset.ULTRA_LOW_LATENCY.toFreeRdpCliArgs()
        assertTrue(ultraArgs.contains("/bpp:32"))
        assertTrue(ultraArgs.contains("/network:auto"))
        assertTrue(ultraArgs.contains("+async-channels"))
        assertTrue(ultraArgs.contains("+async-update"))
        assertTrue(ultraArgs.contains("-wallpaper"))
        assertTrue(ultraArgs.contains("-sound"))

        val balancedArgs = PerformancePreset.BALANCED_MOBILE.toFreeRdpCliArgs()
        assertTrue(balancedArgs.contains("/bpp:16"))
        assertTrue(balancedArgs.contains("+rfx"))
        assertTrue(balancedArgs.contains("+sound"))

        val dataSaverArgs = PerformancePreset.DATA_SAVER.toFreeRdpCliArgs()
        assertTrue(dataSaverArgs.contains("/bpp:8"))
        assertTrue(dataSaverArgs.contains("/network:modem"))
        assertTrue(dataSaverArgs.contains("+compression"))
        assertTrue(dataSaverArgs.contains("-fonts"))
        assertTrue(dataSaverArgs.contains("-sound"))

        val batterySaverArgs = PerformancePreset.BATTERY_SAVER.toFreeRdpCliArgs()
        assertTrue(batterySaverArgs.contains("/bpp:16"))
        assertTrue(batterySaverArgs.contains("/network:broadband"))
        assertTrue(batterySaverArgs.contains("+compression"))
        assertTrue(batterySaverArgs.contains("-sound"))
    }

    @Test
    fun testBatteryAndNetworkAdaptationRules() {
        // Rule 1: Power save mode forces BATTERY_SAVER regardless of network
        val powerSaveBattery = PerformancePresetAdapter.BatteryInfo(
            levelPercent = 80,
            isPowerSaveMode = true,
            isCharging = false
        )
        assertEquals(
            PerformancePreset.BATTERY_SAVER,
            PerformancePresetAdapter.determinePreset(PerformancePresetAdapter.NetworkQuality.WIFI, powerSaveBattery)
        )

        // Rule 2: Critically low battery (<= 15%) without charging forces BATTERY_SAVER
        val criticalBattery = PerformancePresetAdapter.BatteryInfo(
            levelPercent = 12,
            isPowerSaveMode = false,
            isCharging = false
        )
        assertEquals(
            PerformancePreset.BATTERY_SAVER,
            PerformancePresetAdapter.determinePreset(PerformancePresetAdapter.NetworkQuality.WIFI, criticalBattery)
        )

        // Charging overrides critical battery
        val chargingBattery = PerformancePresetAdapter.BatteryInfo(
            levelPercent = 12,
            isPowerSaveMode = false,
            isCharging = true
        )
        assertEquals(
            PerformancePreset.ULTRA_LOW_LATENCY,
            PerformancePresetAdapter.determinePreset(PerformancePresetAdapter.NetworkQuality.WIFI, chargingBattery)
        )

        // Rule 3: Low battery (<= 25%) forces DATA_SAVER
        val lowBattery = PerformancePresetAdapter.BatteryInfo(
            levelPercent = 20,
            isPowerSaveMode = false,
            isCharging = false
        )
        assertEquals(
            PerformancePreset.DATA_SAVER,
            PerformancePresetAdapter.determinePreset(PerformancePresetAdapter.NetworkQuality.WIFI, lowBattery)
        )

        // Rule 4: Metered or 3G network forces DATA_SAVER
        val healthyBattery = PerformancePresetAdapter.BatteryInfo(
            levelPercent = 90,
            isPowerSaveMode = false,
            isCharging = false
        )
        assertEquals(
            PerformancePreset.DATA_SAVER,
            PerformancePresetAdapter.determinePreset(PerformancePresetAdapter.NetworkQuality.METERED, healthyBattery)
        )
        assertEquals(
            PerformancePreset.DATA_SAVER,
            PerformancePresetAdapter.determinePreset(PerformancePresetAdapter.NetworkQuality.CELLULAR_3G, healthyBattery)
        )

        // Rule 5: Cellular 4G/5G selects BALANCED_MOBILE
        assertEquals(
            PerformancePreset.BALANCED_MOBILE,
            PerformancePresetAdapter.determinePreset(PerformancePresetAdapter.NetworkQuality.CELLULAR_4G, healthyBattery)
        )
        assertEquals(
            PerformancePreset.BALANCED_MOBILE,
            PerformancePresetAdapter.determinePreset(PerformancePresetAdapter.NetworkQuality.CELLULAR_5G, healthyBattery)
        )

        // Rule 6: High-speed Wi-Fi with good battery selects ULTRA_LOW_LATENCY
        assertEquals(
            PerformancePreset.ULTRA_LOW_LATENCY,
            PerformancePresetAdapter.determinePreset(PerformancePresetAdapter.NetworkQuality.WIFI, healthyBattery)
        )
    }

    @Test
    fun testCorePresetBidirectionalMapping() {
        assertEquals(
            com.freerdp.core.engine.PerformancePreset.ULTRA_LOW_LATENCY,
            PerformancePreset.ULTRA_LOW_LATENCY.toCorePreset()
        )
        assertEquals(
            com.freerdp.core.engine.PerformancePreset.BALANCED,
            PerformancePreset.BALANCED_MOBILE.toCorePreset()
        )
        assertEquals(
            com.freerdp.core.engine.PerformancePreset.DATA_SAVER,
            PerformancePreset.DATA_SAVER.toCorePreset()
        )
        assertEquals(
            com.freerdp.core.engine.PerformancePreset.BATTERY_SAVER,
            PerformancePreset.BATTERY_SAVER.toCorePreset()
        )

        assertEquals(
            PerformancePreset.ULTRA_LOW_LATENCY,
            PerformancePreset.fromCorePreset(com.freerdp.core.engine.PerformancePreset.ULTRA_LOW_LATENCY)
        )
        assertEquals(
            PerformancePreset.ULTRA_LOW_LATENCY,
            PerformancePreset.fromCorePreset(com.freerdp.core.engine.PerformancePreset.LOW_LATENCY)
        )
        assertEquals(
            PerformancePreset.BALANCED_MOBILE,
            PerformancePreset.fromCorePreset(com.freerdp.core.engine.PerformancePreset.BALANCED)
        )
        assertEquals(
            PerformancePreset.DATA_SAVER,
            PerformancePreset.fromCorePreset(com.freerdp.core.engine.PerformancePreset.DATA_SAVER)
        )
        assertEquals(
            PerformancePreset.BATTERY_SAVER,
            PerformancePreset.fromCorePreset(com.freerdp.core.engine.PerformancePreset.BATTERY_SAVER)
        )
    }

    @Test
    fun testConcreteTunablesPerPreset() {
        // Ultra-Latency: 128KB/64KB sockets, aggressive reconnect, 100ms telemetry.
        val ultra = PerformancePreset.ULTRA_LOW_LATENCY
        assertEquals(131072, ultra.socketRxBufferBytes)
        assertEquals(65536, ultra.socketTxBufferBytes)
        assertTrue(ultra.tcpNoDelay)
        assertEquals("/gfx:AVC444", ultra.gpuPipelineArg)
        assertTrue(ultra.gpuPipelineEnabled)
        assertEquals(500L, ultra.reconnectBaseDelayMs)
        assertEquals(8_000L, ultra.reconnectMaxDelayMs)
        assertEquals(8, ultra.reconnectMaxAttempts)
        assertEquals(100L, ultra.telemetryIntervalMs)
        assertEquals(16.67, ultra.frameIntervalMs, 0.01)

        // Data Saver: halved buffers, level-2 compression, fixed network class, 1s telemetry.
        val saver = PerformancePreset.DATA_SAVER
        assertEquals(65536, saver.socketRxBufferBytes)
        assertEquals(32768, saver.socketTxBufferBytes)
        assertEquals(2, saver.compressionLevel)
        assertFalse(saver.networkAutoDetect)
        assertEquals(1000L, saver.telemetryIntervalMs)
        assertEquals(2_000L, saver.reconnectBaseDelayMs)
        assertEquals(60_000L, saver.reconnectMaxDelayMs)
        assertEquals(5, saver.reconnectMaxAttempts)
        assertEquals(CodecQualityLevel.MEDIUM, saver.qualityLevel)

        // Battery Saver: fewest retries (fewest wakeups), LOW quality tier.
        val battery = PerformancePreset.BATTERY_SAVER
        assertEquals(3, battery.reconnectMaxAttempts)
        assertEquals(2_000L, battery.telemetryIntervalMs)
        assertEquals(CodecQualityLevel.LOW, battery.qualityLevel)

        // Every preset materializes VALID socket options (range-checked cross-consistency).
        for (preset in PerformancePreset.entries) {
            val options = preset.toSocketOptions()
            assertEquals(preset.tcpNoDelay, options.tcpNoDelay)
            assertEquals(preset.socketRxBufferBytes, options.rxBufferBytes)
            assertEquals(preset.socketTxBufferBytes, options.txBufferBytes)
            assertTrue("fps * frameInterval must approximate 1000ms", preset.frameIntervalMs * preset.targetFps in 999.0..1001.0)
            assertTrue("reconnect max >= base", preset.reconnectMaxDelayMs >= preset.reconnectBaseDelayMs)
        }
    }

    @Test
    fun testCliArgsIncludeGpuCompressionAndAudioFlags() {
        for (preset in PerformancePreset.entries) {
            val args = preset.toFreeRdpCliArgs()
            // GPU pipeline argument present for every preset.
            assertTrue("${preset.name} must emit ${preset.gpuPipelineArg}", args.contains(preset.gpuPipelineArg))
            // Compression level argument present and within FreeRDP's 0..2 range.
            val level = args.find { it.startsWith("/compression-level:") }
            assertNotNull("${preset.name} must emit a compression level", level)
            val parsed = level!!.substringAfterLast(":").toInt()
            assertTrue(parsed in 0..2)
            // Audio redirection follows soundEnabled.
            if (preset.soundEnabled) {
                assertTrue(args.contains("/audio-mode:0"))
            } else {
                assertTrue(args.contains("/audio-mode:2"))
            }
            // Never emit FreeRDP's own auto-reconnect (client FSM owns reconnection).
            assertFalse(args.any { it.startsWith("/auto-reconnect") })
        }
    }
}
