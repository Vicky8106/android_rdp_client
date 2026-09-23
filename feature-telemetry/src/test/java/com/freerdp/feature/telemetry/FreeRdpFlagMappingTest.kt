package com.freerdp.feature.telemetry

import com.freerdp.feature.telemetry.network.LowLatencySocketConfig
import com.freerdp.feature.telemetry.preset.FreeRdpFlagMapping
import com.freerdp.feature.telemetry.preset.PerformancePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the PerformancePreset -> FreeRDP connection-flag mapping data (F23/R4)
 * consumed by the :core-rdp integration.
 */
class FreeRdpFlagMappingTest {

    @Test
    fun testMappingCoversAllFourPresets() {
        assertEquals(4, FreeRdpFlagMapping.all.size)
        assertEquals(
            "Mapping must cover exactly the enum entries",
            PerformancePreset.entries.toSet(),
            FreeRdpFlagMapping.all.map { it.preset }.toSet()
        )
        for (preset in PerformancePreset.entries) {
            val profile = FreeRdpFlagMapping.forPreset(preset)
            assertEquals(preset, profile.preset)
            // Single source of truth: the structured profile exposes the same CLI list.
            assertEquals(preset.toFreeRdpCliArgs(), profile.cliArgs)
        }

        val markdown = FreeRdpFlagMapping.renderMarkdownTable()
        assertTrue(markdown.startsWith("| Preset |"))
        PerformancePreset.entries.forEach { assertTrue("Table must list ${it.name}", markdown.contains(it.name)) }
    }

    @Test
    fun testFpsMappingIsClientEnforcedAndConsistent() {
        for (profile in FreeRdpFlagMapping.all) {
            assertEquals(profile.preset.targetFps, profile.frameRateFps)
            assertEquals(1000.0 / profile.preset.targetFps, profile.frameIntervalMs, 1e-9)
            assertTrue(
                "fps enforcement must document the FramePacer (FreeRDP has no /fps argument)",
                profile.fpsEnforcement.contains("FramePacer")
            )
            assertFalse(
                "fps enforcement must document the FreeRDP /fps absence",
                profile.fpsEnforcement.contains("/fps:")
            )
            // FreeRDP's CLI must never receive a bogus /fps argument.
            assertFalse(profile.cliArgs.any { it.startsWith("/fps") })
        }
    }

    @Test
    fun testGpuPipelineArgsAreValidFreeRdpTokens() {
        val validTokens = setOf("AVC444", "AVC420", "progressive", "RFX")
        for (profile in FreeRdpFlagMapping.all) {
            assertTrue("GPU pipeline must be enabled for ${profile.preset}", profile.gpuPipelineEnabled)
            assertTrue(
                "${profile.gpuPipelineArg} must be a /gfx: argument",
                profile.gpuPipelineArg.startsWith("/gfx:")
            )
            assertTrue(
                "${profile.gpuPipelineArg} must use a verified GFX variant",
                profile.gpuPipelineArg.substringAfter(":") in validTokens
            )
            assertEquals(profile.preset.gpuPipelineArg, profile.gpuPipelineArg)
            assertTrue(
                "cliArgs must contain the GPU pipeline argument",
                profile.cliArgs.contains(profile.gpuPipelineArg)
            )
        }
    }

    @Test
    fun testNetworkAutoDetectMappingMatchesCliArgs() {
        val validNetworkValues = setOf("/network:auto", "/network:modem", "/network:broadband")
        for (profile in FreeRdpFlagMapping.all) {
            assertTrue(
                "${profile.networkAutoDetectArg} must be a verified /network: value",
                profile.networkAutoDetectArg in validNetworkValues
            )
            assertEquals(
                profile.networkAutoDetect,
                profile.networkAutoDetectArg == "/network:auto"
            )
            assertTrue(profile.cliArgs.contains(profile.networkAutoDetectArg))
            // Flag consistency with the preset itself.
            assertEquals(profile.preset.networkAutoDetect, profile.networkAutoDetect)
        }
        // Ultra + Balanced auto-detect; Data Saver / Battery pin the link class.
        assertEquals("/network:auto", FreeRdpFlagMapping.forPreset(PerformancePreset.ULTRA_LOW_LATENCY).networkAutoDetectArg)
        assertEquals("/network:modem", FreeRdpFlagMapping.forPreset(PerformancePreset.DATA_SAVER).networkAutoDetectArg)
        assertEquals("/network:broadband", FreeRdpFlagMapping.forPreset(PerformancePreset.BATTERY_SAVER).networkAutoDetectArg)
    }

    @Test
    fun testSoundMappingFollowsPresetFlag() {
        for (profile in FreeRdpFlagMapping.all) {
            assertEquals(profile.preset.soundEnabled, profile.soundEnabled)
            if (profile.soundEnabled) {
                assertEquals(listOf("/audio-mode:0"), profile.soundArgs)
                assertTrue(profile.cliArgs.contains("+sound"))
            } else {
                assertEquals(listOf("/audio-mode:2"), profile.soundArgs)
                assertTrue(profile.cliArgs.contains("-sound"))
            }
            assertTrue(profile.cliArgs.contains(profile.soundArgs.first()))
        }
    }

    @Test
    fun testSocketBuffersStayWithinValidatedBounds() {
        for (profile in FreeRdpFlagMapping.all) {
            assertTrue(
                profile.socketRxBufferBytes in LowLatencySocketConfig.MIN_BUFFER_BYTES..LowLatencySocketConfig.MAX_BUFFER_BYTES
            )
            assertTrue(
                profile.socketTxBufferBytes in LowLatencySocketConfig.MIN_BUFFER_BYTES..LowLatencySocketConfig.MAX_BUFFER_BYTES
            )
            // Round-trip through the validating factory — must not throw.
            val options = profile.preset.toSocketOptions()
            assertEquals(profile.socketRxBufferBytes, options.rxBufferBytes)
            assertEquals(profile.socketTxBufferBytes, options.txBufferBytes)
            assertEquals(profile.tcpNoDelay, options.tcpNoDelay)
            assertTrue("Interactive modes always use TCP_NODELAY", options.tcpNoDelay)
        }
    }

    @Test
    fun testReconnectAndTelemetryTunablesAreSane() {
        for (profile in FreeRdpFlagMapping.all) {
            assertTrue(profile.reconnectBaseDelayMs >= 1L)
            assertTrue(profile.reconnectMaxDelayMs >= profile.reconnectBaseDelayMs)
            assertTrue(profile.reconnectMaxAttempts in 1..100)
            assertTrue(profile.telemetryIntervalMs >= 1L)
            assertEquals(profile.preset.telemetryIntervalMs, profile.telemetryIntervalMs)
            // Reconnection is owned by the client FSM: never emit FreeRDP auto-reconnect.
            assertFalse(profile.freerdpAutoReconnectEmitted)
            assertFalse(profile.cliArgs.any { it.startsWith("/auto-reconnect") })
        }
    }

    @Test
    fun testCompressionMappingIsWithinFreeRdpRange() {
        for (profile in FreeRdpFlagMapping.all) {
            assertEquals(1, profile.compressionArgs.size)
            val arg = profile.compressionArgs[0]
            assertTrue(arg.startsWith("/compression-level:"))
            val level = arg.substringAfterLast(":").toInt()
            assertTrue("FreeRDP /compression-level accepts 0..2 only", level in 0..2)
            assertEquals(level, profile.preset.compressionLevel)
            assertTrue(profile.cliArgs.contains(arg))
        }
    }

    @Test
    fun testColorDepthMappingMatchesCliArgs() {
        for (profile in FreeRdpFlagMapping.all) {
            assertEquals("/bpp:${profile.preset.colorDepth}", profile.colorDepthArg)
            assertTrue(profile.cliArgs.contains(profile.colorDepthArg))
        }
        assertEquals("/bpp:32", FreeRdpFlagMapping.forPreset(PerformancePreset.ULTRA_LOW_LATENCY).colorDepthArg)
        assertEquals("/bpp:8", FreeRdpFlagMapping.forPreset(PerformancePreset.DATA_SAVER).colorDepthArg)
    }

    @Test
    fun testSocketOptionMappingTableIsComplete() {
        val mappings = FreeRdpFlagMapping.socketOptionMappings
        assertTrue("Must document at least 5 OS option mappings", mappings.size >= 5)

        val osOptions = mappings.joinToString(" ") { it.osSocketOption }
        for (expected in listOf("TCP_NODELAY", "SO_RCVBUF", "SO_SNDBUF", "IP_TOS", "SO_KEEPALIVE")) {
            assertTrue("Mapping table must cover $expected", osOptions.contains(expected))
        }
        for (row in mappings) {
            assertTrue("Row must have a tuning option: $row", row.tuningOption.isNotBlank())
            assertTrue("Row must have a rationale", row.rationale.isNotBlank())
            assertTrue("Row must state the FreeRDP surface", row.freerdpSurface.isNotBlank())
        }
        // The auto-reconnect opt-out must be spelled out for the core-rdp consumer.
        assertTrue(mappings.any { it.freerdpSurface.contains("/auto-reconnect") && it.tuningOption.contains("not emitted") })
    }
}
