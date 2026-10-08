package com.rdp.client

import android.content.Context
import android.os.Parcel
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.rdp.client.model.AudioMode
import com.rdp.client.model.ColorDepth
import com.rdp.client.model.GestureStyle
import com.rdp.client.model.ResolutionMode
import com.rdp.client.model.ScreenOrientation
import com.rdp.client.model.SecurityType
import com.rdp.client.model.ServerProfile
import com.rdp.client.model.ViewMode
import com.rdp.client.ui.session.RdpSessionContract
import com.rdp.client.utils.WolHelper
import com.rdp.client.validator.FormField
import com.rdp.client.validator.ProfileValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AdversarialM2StressTest {

    // =========================================================================
    // 1. ProfileValidator: Boundary Values & Fuzzing
    // =========================================================================

    @Test
    fun fuzzPort_boundariesAndEdgeValues() {
        // Boundary: 0, 1, 65535, 65536, negative, extremes
        assertThat(ProfileValidator.validatePort(0).isValid).isFalse()
        assertThat(ProfileValidator.validatePort(1).isValid).isTrue()
        assertThat(ProfileValidator.validatePort(65535).isValid).isTrue()
        assertThat(ProfileValidator.validatePort(65536).isValid).isFalse()
        assertThat(ProfileValidator.validatePort(-1).isValid).isFalse()
        assertThat(ProfileValidator.validatePort(-3389).isValid).isFalse()
        assertThat(ProfileValidator.validatePort(Int.MIN_VALUE).isValid).isFalse()
        assertThat(ProfileValidator.validatePort(Int.MAX_VALUE).isValid).isFalse()
    }

    @Test
    fun fuzzPort_formStringParsing() {
        val testCases = listOf(
            "0" to false,
            "1" to true,
            "3389" to true,
            "65535" to true,
            "65536" to false,
            "-1" to false,
            "abc" to false,
            "" to false,
            "   " to false,
            "3389.0" to false,
            "0x3389" to false,
            "999999999999999999999999999999" to false // Overflow beyond Long/Int
        )

        for ((input, expectedValid) in testCases) {
            val form = ProfileValidator.ProfileFormState(
                host = "10.0.0.1",
                portStr = input
            )
            val result = ProfileValidator.validateProfile(form)
            val portValid = !result.errors.containsKey(FormField.PORT)
            assertThat(portValid).isEqualTo(expectedValid)
        }
    }

    @Test
    fun fuzzBroadcastIp_ipv4Boundaries() {
        // Valid IPv4 extremes
        assertThat(ProfileValidator.validateBroadcastIp("0.0.0.0").isValid).isTrue()
        assertThat(ProfileValidator.validateBroadcastIp("255.255.255.255").isValid).isTrue()
        assertThat(ProfileValidator.validateBroadcastIp("127.0.0.1").isValid).isTrue()
        assertThat(ProfileValidator.validateBroadcastIp("10.0.0.255").isValid).isTrue()
        assertThat(ProfileValidator.validateBroadcastIp(" 255.255.255.255 ").isValid).isTrue()

        // Invalid IPv4 values
        assertThat(ProfileValidator.validateBroadcastIp("256.0.0.1").isValid).isFalse()
        assertThat(ProfileValidator.validateBroadcastIp("192.168.1.256").isValid).isFalse()
        assertThat(ProfileValidator.validateBroadcastIp("999.999.999.999").isValid).isFalse()
        assertThat(ProfileValidator.validateBroadcastIp("-1.0.0.1").isValid).isFalse()
        assertThat(ProfileValidator.validateBroadcastIp("192.168.1.1.1").isValid).isFalse()
        assertThat(ProfileValidator.validateBroadcastIp("192.168.1").isValid).isFalse()
        assertThat(ProfileValidator.validateBroadcastIp("...").isValid).isFalse()
        assertThat(ProfileValidator.validateBroadcastIp("").isValid).isFalse()
        assertThat(ProfileValidator.validateBroadcastIp("   ").isValid).isFalse()
        assertThat(ProfileValidator.validateBroadcastIp("::1").isValid).isFalse() // IPv6 not valid broadcast IPv4
    }

    @Test
    fun fuzzHost_ipv6Addresses() {
        // ProfileValidator validateHost accepts non-empty, non-space hostnames including IPv6
        assertThat(ProfileValidator.validateHost("::1").isValid).isTrue()
        assertThat(ProfileValidator.validateHost("fe80::1").isValid).isTrue()
        assertThat(ProfileValidator.validateHost("[2001:db8::1]").isValid).isTrue()
        assertThat(ProfileValidator.validateHost("2001:0db8:85a3:0000:0000:8a2e:0370:7334").isValid).isTrue()
    }

    @Test
    fun fuzzHost_domainNamesWithWhitespace() {
        // Interior spaces must fail
        assertThat(ProfileValidator.validateHost("corp .com").isValid).isFalse()
        assertThat(ProfileValidator.validateHost("rdp server.internal").isValid).isFalse()
        assertThat(ProfileValidator.validateHost("192.168. 1.1").isValid).isFalse()

        // Surrounding spaces are trimmed
        assertThat(ProfileValidator.validateHost("  corp.internal  ").isValid).isTrue()
    }

    @Test
    fun fuzzHost_unicodeAndInternationalHostnames() {
        // IDN / Internationalized domain names
        assertThat(ProfileValidator.validateHost("münchen.de").isValid).isTrue()
        assertThat(ProfileValidator.validateHost("сервер.рф").isValid).isTrue()
        assertThat(ProfileValidator.validateHost("主机.cn").isValid).isTrue()
        assertThat(ProfileValidator.validateHost("café.example.org").isValid).isTrue()
    }

    @Test
    fun fuzzMacAddress_formattingAndInvalidHex() {
        // Valid forms: colon, hyphen, lower, upper, mixed-case
        assertThat(ProfileValidator.validateMacAddress("00:11:22:33:44:55").isValid).isTrue()
        assertThat(ProfileValidator.validateMacAddress("aa:bb:cc:dd:ee:ff").isValid).isTrue()
        assertThat(ProfileValidator.validateMacAddress("AA:BB:CC:DD:EE:FF").isValid).isTrue()
        assertThat(ProfileValidator.validateMacAddress("aA:bB:cC:dD:eE:fF").isValid).isTrue()
        assertThat(ProfileValidator.validateMacAddress("00-11-22-33-44-55").isValid).isTrue()
        assertThat(ProfileValidator.validateMacAddress("AA-BB-CC-DD-EE-FF").isValid).isTrue()
        assertThat(ProfileValidator.validateMacAddress("00:11-22:33-44:55").isValid).isTrue() // mixed delimiters allowed by regex

        // Invalid hex characters
        assertThat(ProfileValidator.validateMacAddress("00:11:22:33:44:GG").isValid).isFalse()
        assertThat(ProfileValidator.validateMacAddress("00:11:22:33:44:ZZ").isValid).isFalse()
        assertThat(ProfileValidator.validateMacAddress("XX:YY:ZZ:11:22:33").isValid).isFalse()

        // Invalid octet lengths
        assertThat(ProfileValidator.validateMacAddress("00:11:22:33:44:5").isValid).isFalse() // 1 digit in last octet
        assertThat(ProfileValidator.validateMacAddress("00:11:22:33:44:555").isValid).isFalse() // 3 digits
        assertThat(ProfileValidator.validateMacAddress("00:11:22:33:44").isValid).isFalse() // 5 octets
        assertThat(ProfileValidator.validateMacAddress("00:11:22:33:44:55:66").isValid).isFalse() // 7 octets
        assertThat(ProfileValidator.validateMacAddress("").isValid).isFalse()
        assertThat(ProfileValidator.validateMacAddress("00 11 22 33 44 55").isValid).isFalse()
        assertThat(ProfileValidator.validateMacAddress("00.11.22.33.44.55").isValid).isFalse()
        assertThat(ProfileValidator.validateMacAddress("001122334455").isValid).isFalse()
    }

    // =========================================================================
    // 2. WolHelper: Magic Packet Payload Integrity & Length (102 Bytes)
    // =========================================================================

    @Test
    fun wolHelper_magicPacketPayloadLengthAndComposition() = runBlocking {
        // Bind a local receiver socket on an ephemeral port
        val receiverSocket = DatagramSocket(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        val port = receiverSocket.localPort

        try {
            val testMac = "01:23:45:67:89:AB"
            val expectedMacBytes = byteArrayOf(
                0x01.toByte(), 0x23.toByte(), 0x45.toByte(),
                0x67.toByte(), 0x89.toByte(), 0xAB.toByte()
            )

            val receiveDeferred = async(Dispatchers.IO) {
                val buffer = ByteArray(256)
                val packet = DatagramPacket(buffer, buffer.size)
                receiverSocket.receive(packet)
                packet
            }

            // Send packet via WolHelper
            val sendSuccess = WolHelper.sendMagicPacket(
                macAddress = testMac,
                broadcastIp = "127.0.0.1",
                port = port
            )
            assertThat(sendSuccess).isTrue()

            val receivedPacket = receiveDeferred.await()

            // 1. Length MUST BE EXACTLY 102 bytes: 6x 0xFF + 16x MAC (6*16 = 96) = 102
            assertThat(receivedPacket.length).isEqualTo(102)

            val data = receivedPacket.data

            // 2. First 6 bytes must be 0xFF
            for (i in 0 until 6) {
                assertThat(data[i]).isEqualTo(0xFF.toByte())
            }

            // 3. Next 96 bytes must be 16 repetitions of the 6 MAC bytes
            for (rep in 0 until 16) {
                val offset = 6 + rep * 6
                for (b in 0 until 6) {
                    assertThat(data[offset + b]).isEqualTo(expectedMacBytes[b])
                }
            }
        } finally {
            receiverSocket.close()
        }
    }

    @Test
    fun wolHelper_magicPacketWithHyphenAndMixedCase() = runBlocking {
        val receiverSocket = DatagramSocket(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        val port = receiverSocket.localPort

        try {
            val testMac = "de-ad-be-ef-ca-fe"
            val expectedMacBytes = byteArrayOf(
                0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(),
                0xEF.toByte(), 0xCA.toByte(), 0xFE.toByte()
            )

            val receiveDeferred = async(Dispatchers.IO) {
                val buffer = ByteArray(256)
                val packet = DatagramPacket(buffer, buffer.size)
                receiverSocket.receive(packet)
                packet
            }

            val sendSuccess = WolHelper.sendMagicPacket(
                macAddress = testMac,
                broadcastIp = "127.0.0.1",
                port = port
            )
            assertThat(sendSuccess).isTrue()

            val received = receiveDeferred.await()
            assertThat(received.length).isEqualTo(102)

            for (rep in 0 until 16) {
                val offset = 6 + rep * 6
                for (b in 0 until 6) {
                    assertThat(received.data[offset + b]).isEqualTo(expectedMacBytes[b])
                }
            }
        } finally {
            receiverSocket.close()
        }
    }

    @Test
    fun wolHelper_malformedMacRejection() = runBlocking {
        // Send with invalid MAC should return false gracefully without exception
        assertThat(WolHelper.sendMagicPacket("not-a-mac", "127.0.0.1", 9)).isFalse()
        assertThat(WolHelper.sendMagicPacket("", "127.0.0.1", 9)).isFalse()
        assertThat(WolHelper.sendMagicPacket("00:11:22:33:44", "127.0.0.1", 9)).isFalse()
        assertThat(WolHelper.sendMagicPacket("00:11:22:33:44:GG", "127.0.0.1", 9)).isFalse()
    }

    // =========================================================================
    // 3. ServerProfile.toFreeRdpArguments(): Spaces, Quotes & Special Characters
    // =========================================================================

    @Test
    fun toFreeRdpArguments_spacesInCredentials() {
        val profile = ServerProfile(
            host = "rdp.corp.com",
            port = 3389,
            username = "john doe",
            password = "Secret Password With Spaces",
            domain = "CORP DOMAIN"
        )
        val args = profile.toFreeRdpArguments()

        assertThat(args).contains("/u:john doe")
        assertThat(args).contains("/p:Secret Password With Spaces")
        assertThat(args).contains("/d:CORP DOMAIN")
    }

    @Test
    fun toFreeRdpArguments_quotesAndSpecialCharacters() {
        val specialPass = "p@\$\"w'o;r&d|!#%^*()<>"
        val domainUser = "CORP\\admin"
        val profile = ServerProfile(
            host = "10.0.0.1",
            port = 3389,
            username = domainUser,
            password = specialPass,
            domain = "CORP",
            enableGateway = true,
            gatewayHost = "gw.corp.com",
            gatewayPort = 443,
            gatewayUsername = "gw\\user",
            gatewayPassword = "gw\"pass"
        )
        val args = profile.toFreeRdpArguments()

        assertThat(args).contains("/u:CORP\\admin")
        assertThat(args).contains("/p:$specialPass")
        assertThat(args).contains("/gu:gw\\user")
        assertThat(args).contains("/gp:gw\"pass")
    }

    @Test
    fun toFreeRdpArguments_ipv6Host() {
        val profile = ServerProfile(
            host = "fe80::1",
            port = 3389
        )
        val args = profile.toFreeRdpArguments()
        // Check argument format
        assertThat(args).contains("/v:fe80::1:3389")
    }

    @Test
    fun toFreeRdpArguments_blankCredentialsIgnored() {
        val profile = ServerProfile(
            host = "10.0.0.1",
            port = 3389,
            username = "",
            password = "",
            domain = ""
        )
        val args = profile.toFreeRdpArguments()

        // Arguments list should not include empty flags
        val hasUser = args.any { it.startsWith("/u:") }
        val hasPass = args.any { it.startsWith("/p:") }
        val hasDomain = args.any { it.startsWith("/d:") }

        assertThat(hasUser).isFalse()
        assertThat(hasPass).isFalse()
        assertThat(hasDomain).isFalse()
    }

    // =========================================================================
    // 4. RdpSessionContract & QuickConnect Contracts
    // =========================================================================

    @Test
    fun rdpSessionContract_persistedIntentIntegrity() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = RdpSessionContract.createSessionIntent(context, 42L)

        assertThat(intent.getLongExtra(RdpSessionContract.EXTRA_PROFILE_ID, -1L)).isEqualTo(42L)
        assertThat(intent.getBooleanExtra(RdpSessionContract.EXTRA_QUICK_CONNECT, true)).isFalse()
        assertThat(intent.getParcelableExtra<ServerProfile>(RdpSessionContract.EXTRA_TRANSIENT_PROFILE)).isNull()
    }

    @Test
    fun rdpSessionContract_transientIntentIntegrity() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val transientProfile = ServerProfile(
            name = "Temp Ad-hoc Session",
            host = "10.10.10.10",
            port = 3390,
            username = "guest",
            isQuickConnect = true
        )
        val intent = RdpSessionContract.createTransientSessionIntent(context, transientProfile)

        assertThat(intent.getLongExtra(RdpSessionContract.EXTRA_PROFILE_ID, -1L)).isEqualTo(0L)
        assertThat(intent.getBooleanExtra(RdpSessionContract.EXTRA_QUICK_CONNECT, false)).isTrue()

        val restored = intent.getParcelableExtra<ServerProfile>(RdpSessionContract.EXTRA_TRANSIENT_PROFILE)
        assertThat(restored).isNotNull()
        assertThat(restored?.host).isEqualTo("10.10.10.10")
        assertThat(restored?.port).isEqualTo(3390)
        assertThat(restored?.username).isEqualTo("guest")
        assertThat(restored?.isQuickConnect).isTrue()
    }

    @Test
    fun serverProfile_parcelableRoundTripAllFields() {
        // Test Android IPC parcelization with all 28 fields populated
        val original = ServerProfile(
            id = 999L,
            name = "Full Test Profile",
            host = "rdp.fulltest.com",
            port = 3391,
            domain = "DOMAIN_TEST",
            username = "user_test",
            password = "password_test",
            securityType = SecurityType.TLS,
            enableGateway = true,
            gatewayHost = "gw.fulltest.com",
            gatewayPort = 8443,
            gatewayDomain = "GW_DOMAIN",
            gatewayUsername = "gw_user",
            gatewayPassword = "gw_pass",
            resolutionMode = ResolutionMode.CUSTOM,
            customWidth = 2560,
            customHeight = 1440,
            desktopScale = 150,
            colorDepth = ColorDepth.DEPTH_16,
            useRemoteFX = false,
            useGFX = false,
            useH264 = false,
            audioMode = AudioMode.REMOTE,
            microphoneEnabled = true,
            enableWol = true,
            wolMacAddress = "11:22:33:44:55:66",
            wolBroadcastIp = "192.168.1.255",
            wolPort = 7,
            zoom1 = 1.25f,
            zoom2 = 1.50f,
            viewMode = ViewMode.BACKGROUND,
            gestureStyle = GestureStyle.TOUCHPAD,
            screenOrientation = ScreenOrientation.LANDSCAPE,
            ignoreCertificate = true,
            clipboardSync = false,
            connectOnAppStart = true,
            buttonUpDelay = true,
            performanceFlags = 42,
            lastConnectedTimestamp = 123456789L,
            connectionCount = 10,
            isQuickConnect = true,
            sortOrder = 5,
            createdTimestamp = 987654321L
        )

        val bundle = android.os.Bundle()
        bundle.putParcelable("profile", original)
        bundle.classLoader = ServerProfile::class.java.classLoader
        @Suppress("DEPRECATION")
        val unparceled = bundle.getParcelable<ServerProfile>("profile")

        assertThat(unparceled as Any?).isEqualTo(original)
    }
}
