package com.freerdp.core

import android.graphics.Bitmap
import com.freerdp.core.engine.MockRdpEngine
import com.freerdp.core.engine.RdpConnectionConfig
import com.freerdp.core.engine.RdpConnectionState
import com.freerdp.core.engine.RdpEventListener
import com.freerdp.core.protocol.ClipboardHandler
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MockRdpEngine parity with NativeFreeRdpEngine:
 * graphics update/resize events, MS-RDPECLIP echo suppression through the real
 * [ClipboardHandler], fail-closed certificate verification, and disconnect cleanup.
 */
class MockRdpEngineParityTest {

    private class RecordingListener : RdpEventListener {
        val successes = mutableListOf<Unit>()
        val failures = mutableListOf<Pair<Int, String>>()
        val disconnects = mutableListOf<Unit>()
        val resolutions = mutableListOf<Pair<Int, Int>>()
        val graphicsUpdates = mutableListOf<com.freerdp.core.engine.MockRdpEngine.GraphicsRegion>()
        val clipboard = mutableListOf<Pair<Int, String>>()
        var acceptCert = true
        var certInvocations = 0

        override fun onConnectionSuccess() { successes += Unit }
        override fun onConnectionFailure(errorCode: Int, message: String) {
            failures += errorCode to message
        }
        override fun onDisconnected() { disconnects += Unit }
        override fun onGraphicsUpdate(bitmap: Bitmap, x: Int, y: Int, width: Int, height: Int) {
            graphicsUpdates += com.freerdp.core.engine.MockRdpEngine.GraphicsRegion(x, y, width, height)
        }
        override fun onResolutionChanged(width: Int, height: Int) {
            resolutions += width to height
        }
        override fun onClipboardDataReceived(format: Int, data: ByteArray) {
            clipboard += format to ClipboardHandler.decodeUnicodeText(data)
        }
        override fun onCertificateVerification(fingerprint: String, host: String): Boolean {
            certInvocations++
            return acceptCert
        }
    }

    @Test
    fun graphicsResizeIsRecordedAndDispatchesResolutionChange() {
        val engine = MockRdpEngine()
        val listener = RecordingListener()
        engine.setEventListener(listener)

        // Mirrors the native OnGraphicsResize callback (server-driven desktop resize).
        engine.triggerGraphicsResize(1280, 720, 32)

        assertEquals(
            listOf(MockRdpEngine.GraphicsResize(1280, 720, 32)),
            engine.recordedGraphicsResizes
        )
        assertEquals(listOf(1280 to 720), listener.resolutions)
    }

    @Test
    fun graphicsUpdateRegionsAreRecordedAndDispatched() {
        val engine = MockRdpEngine()
        val listener = RecordingListener()
        engine.setEventListener(listener)
        val bitmap: Bitmap = mockk(relaxed = true)

        engine.triggerGraphicsUpdate(bitmap, 10, 20, 100, 50)
        engine.triggerGraphicsUpdate(bitmap, 0, 0, 640, 480)

        assertEquals(
            listOf(
                MockRdpEngine.GraphicsRegion(10, 20, 100, 50),
                MockRdpEngine.GraphicsRegion(0, 0, 640, 480)
            ),
            engine.recordedGraphicsUpdates
        )
        assertEquals(2, listener.graphicsUpdates.size)

        engine.clearRecordedEvents()
        assertTrue(engine.recordedGraphicsUpdates.isEmpty())
        assertTrue(engine.recordedGraphicsResizes.isEmpty())
    }

    @Test
    fun clipboardEchoSuppressionMatchesNativeEngine() = runBlocking {
        val engine = MockRdpEngine()
        val listener = RecordingListener()
        engine.setEventListener(listener)

        // local → remote: first send is accepted and recorded.
        engine.sendClipboardText("DupText")
        assertEquals(listOf("DupText"), engine.recordedClipboardTexts)

        // remote echoes the exact same text → suppressed (no loop to the listener).
        engine.triggerClipboardReceived(
            ClipboardHandler.CF_UNICODETEXT,
            ClipboardHandler.encodeUnicodeText("DupText")
        )
        assertEquals("echo must not reach the listener", 0, listener.clipboard.size)

        // genuinely new remote text → dispatched as UTF-16LE CF_UNICODETEXT.
        engine.triggerClipboardReceived(
            ClipboardHandler.CF_UNICODETEXT,
            ClipboardHandler.encodeUnicodeText("FreshRemote")
        )
        assertEquals(1, listener.clipboard.size)
        assertEquals(ClipboardHandler.CF_UNICODETEXT, listener.clipboard[0].first)
        assertEquals("FreshRemote", listener.clipboard[0].second)

        // local copy of the text we just received from remote → suppressed, not recorded.
        engine.sendClipboardText("FreshRemote")
        assertEquals(
            "echo of remote text must not be recorded as a local send",
            listOf("DupText"),
            engine.recordedClipboardTexts
        )

        // a genuinely new local send still goes out.
        engine.sendClipboardText("BrandNew")
        assertEquals(listOf("DupText", "BrandNew"), engine.recordedClipboardTexts)
    }

    @Test
    fun disconnectClearsClipboardEchoState() = runBlocking {
        val engine = MockRdpEngine()
        val listener = RecordingListener()
        engine.setEventListener(listener)

        engine.sendClipboardText("Persisted")
        engine.triggerClipboardReceived(
            ClipboardHandler.CF_UNICODETEXT,
            ClipboardHandler.encodeUnicodeText("Persisted")
        )
        assertEquals(0, listener.clipboard.size)

        engine.disconnect()

        // Echo state is session-scoped: after disconnect the same text is delivered again.
        engine.triggerClipboardReceived(
            ClipboardHandler.CF_UNICODETEXT,
            ClipboardHandler.encodeUnicodeText("Persisted")
        )
        assertEquals(1, listener.clipboard.size)
        assertEquals("Persisted", listener.clipboard[0].second)
    }

    @Test
    fun certificateVerificationFailsClosedWithoutListener() = runBlocking {
        // Parity with the native engine, which returns 0 (reject) from
        // OnVerifyCertificateEx when no listener is registered.
        val engine = MockRdpEngine()
        engine.simulateCertVerification = true
        // NOTE: no listener registered.

        val ok = engine.connect(RdpConnectionConfig(serverAddress = "untrusted.example"))

        assertFalse(ok)
        val failed = engine.connectionState.value as RdpConnectionState.Failed
        assertEquals(403, failed.errorCode)
        assertTrue(failed.message.contains("rejected"))
    }

    @Test
    fun certificateVerificationDelegatesToFailingAndAcceptingListener() = runBlocking {
        val engine = MockRdpEngine()

        // Rejecting listener → connect fails with the cert error.
        val rejecting = RecordingListener()
        rejecting.acceptCert = false
        engine.setEventListener(rejecting)
        engine.simulateCertVerification = true
        assertFalse(engine.connect(RdpConnectionConfig(serverAddress = "host")))
        assertEquals(1, rejecting.certInvocations)
        assertEquals(403, (engine.connectionState.value as RdpConnectionState.Failed).errorCode)

        // Accepting listener → handshake proceeds to Connected.
        val accepting = RecordingListener()
        accepting.acceptCert = true
        engine.setEventListener(accepting)
        assertTrue(engine.connect(RdpConnectionConfig(serverAddress = "host")))
        assertEquals(1, accepting.certInvocations)
        assertEquals(RdpConnectionState.Connected, engine.connectionState.value)
        assertEquals(1, accepting.successes.size)
    }
}
