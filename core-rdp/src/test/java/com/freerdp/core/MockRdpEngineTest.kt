package com.freerdp.core

import android.graphics.Bitmap
import com.freerdp.core.engine.IRdpEngine
import com.freerdp.core.engine.MockRdpEngine
import com.freerdp.core.engine.RdpConnectionConfig
import com.freerdp.core.engine.RdpConnectionState
import com.freerdp.core.engine.RdpEventListener
import com.freerdp.core.engine.RdpSessionMetrics
import com.freerdp.core.protocol.RdpPointerFlags
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MockRdpEngineTest {

    private lateinit var engine: MockRdpEngine

    @Before
    fun setUp() {
        engine = MockRdpEngine()
    }

    @Test
    fun testInitialState() {
        assertEquals(RdpConnectionState.Disconnected, engine.connectionState.value)
        assertTrue(engine.connectionState.value.isDisconnected)
        assertEquals(0, engine.recordedPointerEvents.size)
        assertEquals(0, engine.recordedKeyEvents.size)
        assertEquals(0, engine.recordedUnicodeEvents.size)
    }

    @Test
    fun testConnectSuccessWorkflow() = runBlocking {
        var successNotified = false
        engine.setEventListener(object : RdpEventListener {
            override fun onConnectionSuccess() {
                successNotified = true
            }
            override fun onConnectionFailure(errorCode: Int, message: String) {}
            override fun onDisconnected() {}
            override fun onGraphicsUpdate(bitmap: Bitmap, x: Int, y: Int, width: Int, height: Int) {}
            override fun onResolutionChanged(width: Int, height: Int) {}
            override fun onClipboardDataReceived(format: Int, data: ByteArray) {}
            override fun onCertificateVerification(fingerprint: String, host: String): Boolean = true
        })

        val config = RdpConnectionConfig(
            serverAddress = "192.168.1.100",
            port = 3389,
            username = "rdpuser",
            password = "secretpassword",
            width = 1920,
            height = 1080
        )

        val connected = engine.connect(config)

        assertTrue(connected)
        assertTrue(successNotified)
        assertEquals(RdpConnectionState.Connected, engine.connectionState.value)
        assertTrue(engine.connectionState.value.isConnected)
        assertEquals(config, engine.activeConfig)
    }

    @Test
    fun testSimulatedConnectionFailure() = runBlocking {
        var failureCode = 0
        var failureMessage = ""

        engine.setEventListener(object : RdpEventListener {
            override fun onConnectionSuccess() {}
            override fun onConnectionFailure(errorCode: Int, message: String) {
                failureCode = errorCode
                failureMessage = message
            }
            override fun onDisconnected() {}
            override fun onGraphicsUpdate(bitmap: Bitmap, x: Int, y: Int, width: Int, height: Int) {}
            override fun onResolutionChanged(width: Int, height: Int) {}
            override fun onClipboardDataReceived(format: Int, data: ByteArray) {}
            override fun onCertificateVerification(fingerprint: String, host: String): Boolean = true
        })

        engine.shouldFailConnection = true
        engine.failureErrorCode = 0x00020009 // FREERDP_ERROR_AUTHENTICATION_FAILED
        engine.failureErrorMessage = "Invalid RDP credentials"

        val config = RdpConnectionConfig(serverAddress = "10.0.0.1")
        val result = engine.connect(config)

        assertFalse(result)
        assertEquals(0x00020009, failureCode)
        assertEquals("Invalid RDP credentials", failureMessage)
        val state = engine.connectionState.value
        assertTrue(state is RdpConnectionState.Failed)
        assertEquals(0x00020009, (state as RdpConnectionState.Failed).errorCode)
    }

    @Test
    fun testSimulatedCertificateVerificationRejection() = runBlocking {
        var certVerified = false
        engine.setEventListener(object : RdpEventListener {
            override fun onConnectionSuccess() {}
            override fun onConnectionFailure(errorCode: Int, message: String) {}
            override fun onDisconnected() {}
            override fun onGraphicsUpdate(bitmap: Bitmap, x: Int, y: Int, width: Int, height: Int) {}
            override fun onResolutionChanged(width: Int, height: Int) {}
            override fun onClipboardDataReceived(format: Int, data: ByteArray) {}
            override fun onCertificateVerification(fingerprint: String, host: String): Boolean {
                certVerified = true
                return false // Reject untrusted certificate
            }
        })

        engine.simulateCertVerification = true
        engine.certVerificationFingerprint = "SHA256:UNTRUSTED"
        engine.certVerificationHost = "untrusted.server.com"

        val connected = engine.connect(RdpConnectionConfig("untrusted.server.com"))

        assertFalse(connected)
        assertTrue(certVerified)
        assertTrue(engine.connectionState.value is RdpConnectionState.Failed)
    }

    @Test
    fun testPointerEventDispatchAndRecording() {
        engine.sendPointerEvent(RdpPointerFlags.PTR_FLAGS_MOVE, 100, 200)
        engine.sendPointerEvent(RdpPointerFlags.LEFT_BUTTON_DOWN, 100, 200)
        engine.sendPointerEvent(RdpPointerFlags.LEFT_BUTTON_UP, 100, 200)
        engine.sendPointerEvent(RdpPointerFlags.SCROLL_DOWN, 100, 200)

        assertEquals(4, engine.recordedPointerEvents.size)
        assertEquals(RdpPointerFlags.PTR_FLAGS_MOVE, engine.recordedPointerEvents[0].flags)
        assertEquals(100, engine.recordedPointerEvents[0].x)
        assertEquals(200, engine.recordedPointerEvents[0].y)

        assertEquals(RdpPointerFlags.LEFT_BUTTON_DOWN, engine.recordedPointerEvents[1].flags)
        assertEquals(RdpPointerFlags.LEFT_BUTTON_UP, engine.recordedPointerEvents[2].flags)
        assertEquals(RdpPointerFlags.SCROLL_DOWN, engine.recordedPointerEvents[3].flags)
    }

    @Test
    fun testKeyboardAndUnicodeEvents() {
        engine.sendKeyEvent(0x1E, true) // Key A down
        engine.sendKeyEvent(0x1E, false) // Key A up
        engine.sendUnicodeKeyEvent('Z', true)

        assertEquals(2, engine.recordedKeyEvents.size)
        assertEquals(0x1E, engine.recordedKeyEvents[0].keyCode)
        assertTrue(engine.recordedKeyEvents[0].down)
        assertFalse(engine.recordedKeyEvents[1].down)

        assertEquals(1, engine.recordedUnicodeEvents.size)
        assertEquals('Z', engine.recordedUnicodeEvents[0].unicodeChar)
        assertTrue(engine.recordedUnicodeEvents[0].down)
    }

    @Test
    fun testResolutionUpdateAndListener() {
        var notifiedWidth = 0
        var notifiedHeight = 0

        engine.setEventListener(object : RdpEventListener {
            override fun onConnectionSuccess() {}
            override fun onConnectionFailure(errorCode: Int, message: String) {}
            override fun onDisconnected() {}
            override fun onGraphicsUpdate(bitmap: Bitmap, x: Int, y: Int, width: Int, height: Int) {}
            override fun onResolutionChanged(width: Int, height: Int) {
                notifiedWidth = width
                notifiedHeight = height
            }
            override fun onClipboardDataReceived(format: Int, data: ByteArray) {}
            override fun onCertificateVerification(fingerprint: String, host: String): Boolean = true
        })

        engine.updateResolution(
            width = 2400,
            height = 1080,
            physicalWidthMm = 300,
            physicalHeightMm = 135,
            orientation = 0
        )

        assertEquals(1, engine.recordedResolutions.size)
        val event = engine.recordedResolutions[0]
        assertEquals(2400, event.width)
        assertEquals(1080, event.height)
        assertEquals(300, event.physicalWidthMm)
        assertEquals(135, event.physicalHeightMm)
        assertEquals(0, event.orientation)

        assertEquals(2400, notifiedWidth)
        assertEquals(1080, notifiedHeight)
    }

    @Test
    fun testClipboardTextRecording() {
        engine.sendClipboardText("Test clipboard message")
        assertEquals(1, engine.recordedClipboardTexts.size)
        assertEquals("Test clipboard message", engine.recordedClipboardTexts[0])
    }

    @Test
    fun testDisconnectWorkflow() = runBlocking {
        var disconnectedNotified = false
        engine.setEventListener(object : RdpEventListener {
            override fun onConnectionSuccess() {}
            override fun onConnectionFailure(errorCode: Int, message: String) {}
            override fun onDisconnected() {
                disconnectedNotified = true
            }
            override fun onGraphicsUpdate(bitmap: Bitmap, x: Int, y: Int, width: Int, height: Int) {}
            override fun onResolutionChanged(width: Int, height: Int) {}
            override fun onClipboardDataReceived(format: Int, data: ByteArray) {}
            override fun onCertificateVerification(fingerprint: String, host: String): Boolean = true
        })

        engine.connect(RdpConnectionConfig("host"))
        assertTrue(engine.connectionState.value.isConnected)

        engine.disconnect()

        assertTrue(engine.connectionState.value.isDisconnected)
        assertTrue(disconnectedNotified)
    }

    @Test
    fun testMetricsUpdating() {
        val newMetrics = RdpSessionMetrics(
            rttMs = 28L,
            fps = 58.5f,
            bandwidthKbps = 3200L,
            frameCount = 1200L,
            droppedFrames = 2L,
            jitterMs = 3L
        )

        engine.setMetrics(newMetrics)

        assertEquals(newMetrics, engine.sessionMetrics.value)
        assertEquals(28L, engine.sessionMetrics.value.rttMs)
        assertEquals(58.5f, engine.sessionMetrics.value.fps)
    }
}
