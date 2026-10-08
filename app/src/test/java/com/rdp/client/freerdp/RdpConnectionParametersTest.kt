package com.rdp.client.freerdp

import com.rdp.client.model.AudioMode
import com.rdp.client.model.ColorDepth
import com.rdp.client.model.ResolutionMode
import com.rdp.client.model.SecurityType
import com.rdp.client.model.ServerProfile
import org.junit.Assert.*
import org.junit.Test

class RdpConnectionParametersTest {

    @Test
    fun testFactoryFromServerProfile() {
        val profile = ServerProfile(
            id = 10L,
            name = "Office Workstation",
            host = "192.168.1.100",
            port = 3389,
            username = "admin",
            password = "secretPassword123",
            domain = "CORP",
            securityType = SecurityType.NLA,
            resolutionMode = ResolutionMode.FIT_TO_SCREEN,
            customWidth = 1920,
            customHeight = 1080,
            colorDepth = ColorDepth.DEPTH_32,
            enableGateway = true,
            gatewayHost = "gw.corp.com",
            gatewayPort = 443,
            gatewayUsername = "gwuser",
            gatewayPassword = "gwpass",
            audioMode = AudioMode.LOCAL,
            microphoneEnabled = true,
            useRemoteFX = true,
            useGFX = true,
            clipboardSync = true,
            ignoreCertificate = true
        )

        val params = RdpConnectionParameters.fromServerProfile(profile, targetWidth = 2560, targetHeight = 1440)

        assertEquals("192.168.1.100", params.host)
        assertEquals(3389, params.port)
        assertEquals("admin", params.username)
        assertEquals("secretPassword123", params.password)
        assertEquals("CORP", params.domain)
        assertEquals(SecurityType.NLA, params.securityType)
        // With FIT_TO_SCREEN, target dimensions take precedence
        assertEquals(2560, params.width)
        assertEquals(1440, params.height)
        assertEquals(ColorDepth.DEPTH_32, params.colorDepth)
        assertEquals(32, params.colorDepthBpp)
        assertEquals("NLA", params.securityTypeName)
        assertTrue(params.enableGateway)
        assertEquals("gw.corp.com", params.gatewayHost)
        assertTrue(params.microphoneEnabled)
        assertTrue(params.ignoreCertificate)
    }

    @Test
    fun testToNativeArgsGeneration() {
        val params = RdpConnectionParameters(
            host = "rdp.example.com",
            port = 3390,
            username = "alice",
            password = "secretPassword",
            domain = "DOMAIN",
            securityType = SecurityType.TLS,
            width = 1920,
            height = 1080,
            colorDepth = ColorDepth.DEPTH_24,
            resolutionMode = ResolutionMode.DYNAMIC,
            enableGateway = true,
            gatewayHost = "gateway.example.com",
            gatewayPort = 8443,
            gatewayUsername = "gwUser",
            gatewayPassword = "gwPass",
            audioMode = AudioMode.REMOTE,
            microphoneEnabled = true,
            useRemoteFX = true,
            useGFX = true,
            useH264 = true,
            clipboardSync = true,
            ignoreCertificate = true
        )

        val args = params.toNativeArgs()

        assertEquals("freerdp-android", args[0])
        assertTrue(args.contains("/v:rdp.example.com:3390"))
        assertTrue(args.contains("/u:alice"))
        assertTrue(args.contains("/p:secretPassword"))
        assertTrue(args.contains("/d:DOMAIN"))
        assertTrue(args.contains("/sec:tls"))
        assertTrue(args.contains("/size:1920x1080"))
        assertTrue(args.contains("/disp"))
        assertTrue(args.contains("/bpp:24"))
        assertTrue(args.contains("/g:gateway.example.com:8443"))
        assertTrue(args.contains("/gu:gwUser"))
        assertTrue(args.contains("/gp:gwPass"))
        assertTrue(args.contains("/audio-mode:1"))
        assertTrue(args.contains("/microphone:sys:opensles"))
        assertTrue(args.contains("+rfx"))
        assertTrue(args.contains("+gfx"))
        assertTrue(args.contains("+gfx:AVC444"))
        assertTrue(args.contains("+clipboard"))
        assertTrue(args.contains("/cert:ignore"))
        assertTrue(args.contains("/gdi:sw"))
        assertTrue(args.contains("/kbd:unicode:on"))
    }

    @Test
    fun testValidationScenarios() {
        // Valid parameters
        val valid = RdpConnectionParameters(host = "10.0.0.1", port = 3389)
        assertTrue(valid.validate().isSuccess)

        // Empty host
        val invalidHost = RdpConnectionParameters(host = "   ", port = 3389)
        val resHost = invalidHost.validate()
        assertFalse(resHost.isSuccess)
        assertTrue((resHost as ConnectionValidationResult.Error).message.contains("Host address"))

        // Invalid port
        val invalidPort = RdpConnectionParameters(host = "10.0.0.1", port = 70000)
        assertFalse(invalidPort.validate().isSuccess)

        // Invalid dimensions
        val invalidRes = RdpConnectionParameters(host = "10.0.0.1", width = 0, height = -100)
        assertFalse(invalidRes.validate().isSuccess)

        // Gateway enabled without gateway host
        val invalidGw = RdpConnectionParameters(
            host = "10.0.0.1",
            enableGateway = true,
            gatewayHost = ""
        )
        assertFalse(invalidGw.validate().isSuccess)
    }

    @Test
    fun testToSafeStringMasksCredentials() {
        val params = RdpConnectionParameters(
            host = "10.0.0.1",
            username = "secretUser",
            password = "superSecretPassword!",
            enableGateway = true,
            gatewayHost = "gw.corp.com",
            gatewayPassword = "gatewaySecretPass!"
        )

        val safe = params.toSafeString()
        assertFalse("Raw password must not appear in safe log string", safe.contains("superSecretPassword!"))
        assertFalse("Raw gateway password must not appear in safe log string", safe.contains("gatewaySecretPass!"))
        assertTrue(safe.contains("******"))
        assertTrue(safe.contains("10.0.0.1"))
    }
}
