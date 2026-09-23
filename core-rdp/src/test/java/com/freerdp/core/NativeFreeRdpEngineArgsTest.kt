package com.freerdp.core

import com.freerdp.core.engine.NativeFreeRdpEngine
import com.freerdp.core.engine.PerformancePreset
import com.freerdp.core.engine.RdpConnectionConfig
import com.freerdp.core.engine.RdpConnectionState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeFreeRdpEngineArgsTest {

    private val engine = NativeFreeRdpEngine()

    @Test
    fun testBuildArgsDefaultConfig() {
        val config = RdpConnectionConfig(
            serverAddress = "192.168.1.50",
            port = 3389,
            username = "admin",
            password = "pwd",
            width = 1920,
            height = 1080
        )
        val args = engine.buildFreeRdpArgs(config)

        assertTrue(args.contains("/v:192.168.1.50:3389"))
        assertTrue(args.contains("/u:admin"))
        assertTrue(args.contains("/p:pwd"))
        assertTrue(args.contains("/size:1920x1080"))
        assertTrue(args.contains("/bpp:32"))
        assertTrue(args.contains("/sec:nla"))
        assertTrue(args.contains("+clipboard"))
        assertTrue(args.contains("+disp"))
        assertTrue(args.contains("/network:auto"))
        assertTrue(args.contains("+async-channels"))
        assertTrue(args.contains("+async-update"))
    }

    @Test
    fun testBuildArgsPerformancePresets() {
        val ultraLowConfig = RdpConnectionConfig(
            serverAddress = "localhost",
            performancePreset = PerformancePreset.ULTRA_LOW_LATENCY
        )
        val ultraArgs = engine.buildFreeRdpArgs(ultraLowConfig)
        assertTrue(ultraArgs.contains("/network:auto"))
        assertTrue(ultraArgs.contains("+async-channels"))
        assertTrue(ultraArgs.contains("+async-update"))
        assertTrue(ultraArgs.contains("-wallpaper"))
        assertTrue(ultraArgs.contains("-themes"))
        assertTrue(ultraArgs.contains("-menu-anims"))
        assertTrue(ultraArgs.contains("-window-drag"))

        val dataSaverConfig = RdpConnectionConfig(
            serverAddress = "localhost",
            performancePreset = PerformancePreset.DATA_SAVER
        )
        val dataArgs = engine.buildFreeRdpArgs(dataSaverConfig)
        assertTrue(dataArgs.contains("/network:modem"))
        assertTrue(dataArgs.contains("+compression"))

        val batterySaverConfig = RdpConnectionConfig(
            serverAddress = "localhost",
            performancePreset = PerformancePreset.BATTERY_SAVER
        )
        val batteryArgs = engine.buildFreeRdpArgs(batterySaverConfig)
        assertTrue(batteryArgs.contains("/network:broadband"))
    }

    @Test
    fun testBuildArgsSecurityOptions() {
        val tlsConfig = RdpConnectionConfig(
            serverAddress = "secure.host",
            enableNla = false,
            enableTls = true,
            ignoreCertificate = true
        )
        val args = engine.buildFreeRdpArgs(tlsConfig)
        assertTrue(args.contains("/sec:tls"))
        // FreeRDP 3 canonical form: /cert-ignore is deprecated and may be compiled
        // out (DEFINE_NO_DEPRECATED) while freerdp_parse_arguments runs with
        // allowUnknown=FALSE — the deprecated spelling could fail the whole parse.
        assertTrue(args.contains("/cert:ignore"))
        assertFalse(args.contains("/cert-ignore"))
    }

    @Test
    fun testBuildArgsAlwaysIncludesSoftwareGdiForAndroidGlue() {
        // The Android GFX pipeline only initializes with SoftwareGdi
        // (android_freerdp.c warns "add /gdi:sw" otherwise).
        val args = engine.buildFreeRdpArgs(RdpConnectionConfig(serverAddress = "h"))
        assertTrue(args.contains("/gdi:sw"))
    }

    @Test
    fun testBuildArgsTogglesAreExplicitInBothDirections() {
        val enabled = engine.buildFreeRdpArgs(
            RdpConnectionConfig(
                serverAddress = "h",
                enableClipboard = true,
                enableDynamicResolution = true
            )
        )
        assertTrue(enabled.contains("+clipboard"))
        assertTrue(enabled.contains("+disp"))
        assertTrue(enabled.contains("+dynamic-resolution"))

        val disabled = engine.buildFreeRdpArgs(
            RdpConnectionConfig(
                serverAddress = "h",
                enableClipboard = false,
                enableDynamicResolution = false
            )
        )
        // Disabled configs must be honored regardless of library defaults.
        assertTrue(disabled.contains("-clipboard"))
        assertTrue(disabled.contains("-disp"))
        assertFalse(disabled.contains("+clipboard"))
        assertFalse(disabled.contains("+disp"))
    }

    @Test
    fun testBuildArgsBracketsIpv6ServerAddress() {
        // FreeRDP's /v: parser splits IPv4 at the FIRST colon; a bare IPv6 literal
        // must be bracketed or parse_arguments fails.
        val args = engine.buildFreeRdpArgs(
            RdpConnectionConfig(serverAddress = "::1", port = 65535)
        )
        assertTrue(args.contains("/v:[::1]:65535"))
    }

    @Test
    fun testBuildArgsNlaCredSspFlags() {
        val args = engine.buildFreeRdpArgs(
            RdpConnectionConfig(
                serverAddress = "corp.example",
                username = "alice",
                domain = "CORP",
                password = "pw",
                enableNla = true,
                enableTls = true
            )
        )
        // CredSSP/NLA (MS-CSSP) requires /sec:nla plus full credential triple.
        assertTrue(args.contains("/sec:nla"))
        assertTrue(args.contains("/u:alice"))
        assertTrue(args.contains("/d:CORP"))
        assertTrue(args.contains("/p:pw"))
        // NLA implies TLS underneath; a TLS-only config must not request NLA.
        val tlsOnly = engine.buildFreeRdpArgs(
            RdpConnectionConfig(serverAddress = "h", enableNla = false, enableTls = true)
        )
        assertFalse(tlsOnly.contains("/sec:nla"))
        assertTrue(tlsOnly.contains("/sec:tls"))
        // Legacy RDP security last.
        val rdpOnly = engine.buildFreeRdpArgs(
            RdpConnectionConfig(serverAddress = "h", enableNla = false, enableTls = false)
        )
        assertTrue(rdpOnly.contains("/sec:rdp"))
    }

    @Test
    fun testSafeFailureWhenNativeLibraryNotLoaded() = runBlocking {
        // In JVM test runner without native .so, connect() should fail safely without SIGSEGV
        val config = RdpConnectionConfig("test.host")
        val success = engine.connect(config)

        assertFalse(success)
        val state = engine.connectionState.value
        assertTrue(state is RdpConnectionState.Failed)
        val failed = state as RdpConnectionState.Failed
        // Graceful degradation: typed surfacing of isNativeLoaded() + getLoadError().
        assertEquals(NativeFreeRdpEngine.ERROR_NATIVE_NOT_LOADED, failed.errorCode)
        assertFalse(failed.nativeLibraryAvailable)
        assertNotNull("load error must be surfaced on the Failed state", failed.loadError)
        assertTrue(failed.message.contains("not loaded"))
        assertFalse(engine.isNativeLoaded)
        // Parity with MockRdpEngine: the attempted config is retained until disconnect().
        assertEquals("test.host", engine.activeConfig?.serverAddress)
    }
}
