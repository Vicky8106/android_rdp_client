package com.freerdp.core.engine

import android.graphics.Bitmap
import com.freerdp.core.protocol.ClipboardHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Deterministic in-memory test double for [IRdpEngine].
 *
 * Parity notes with [NativeFreeRdpEngine]:
 * - Clipboard text flows through a real [ClipboardHandler] so SHA-256 echo-loop
 *   suppression behaves exactly like the native engine: an echo of the last locally
 *   sent text is neither recorded nor dispatched, and a genuinely new remote text is
 *   dispatched to the listener as UTF-16LE (CF_UNICODETEXT) payload.
 * - `simulateCertVerification` fails closed (no listener → reject), matching the
 *   native engine's default of returning 0 (reject) when no listener is registered.
 * - `triggerGraphicsResize` mirrors the native `OnGraphicsResize` callback: records
 *   the event and dispatches `RdpEventListener.onResolutionChanged`.
 */
class MockRdpEngine : IRdpEngine {

    data class PointerEvent(val flags: Int, val x: Int, val y: Int)
    data class KeyEvent(val keyCode: Int, val down: Boolean)
    data class UnicodeEvent(val unicodeChar: Char, val down: Boolean)
    data class ResolutionEvent(
        val width: Int,
        val height: Int,
        val physicalWidthMm: Int,
        val physicalHeightMm: Int,
        val orientation: Int
    )

    /** Mirrors native OnGraphicsUpdate region callbacks. */
    data class GraphicsRegion(val x: Int, val y: Int, val width: Int, val height: Int)

    /** Mirrors native OnGraphicsResize callbacks (server-driven desktop resize). */
    data class GraphicsResize(val width: Int, val height: Int, val bpp: Int)

    private val _connectionState = MutableStateFlow<RdpConnectionState>(RdpConnectionState.Disconnected)
    override val connectionState: StateFlow<RdpConnectionState> = _connectionState.asStateFlow()

    private val _sessionMetrics = MutableStateFlow(
        RdpSessionMetrics(rttMs = 15, fps = 60f, bandwidthKbps = 2500)
    )
    override val sessionMetrics: StateFlow<RdpSessionMetrics> = _sessionMetrics.asStateFlow()

    @Volatile
    private var eventListener: RdpEventListener? = null

    // Event recording for unit & integration assertions (thread-safe)
    val recordedPointerEvents: MutableList<PointerEvent> = java.util.Collections.synchronizedList(mutableListOf<PointerEvent>())
    val recordedKeyEvents: MutableList<KeyEvent> = java.util.Collections.synchronizedList(mutableListOf<KeyEvent>())
    val recordedUnicodeEvents: MutableList<UnicodeEvent> = java.util.Collections.synchronizedList(mutableListOf<UnicodeEvent>())
    val recordedResolutions: MutableList<ResolutionEvent> = java.util.Collections.synchronizedList(mutableListOf<ResolutionEvent>())
    val recordedClipboardTexts: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf<String>())
    val recordedGraphicsUpdates: MutableList<GraphicsRegion> = java.util.Collections.synchronizedList(mutableListOf<GraphicsRegion>())
    val recordedGraphicsResizes: MutableList<GraphicsResize> = java.util.Collections.synchronizedList(mutableListOf<GraphicsResize>())

    // Simulation controls
    var shouldFailConnection: Boolean = false
    var failureErrorCode: Int = 500
    var failureErrorMessage: String = "Simulated connection failure"
    var simulateCertVerification: Boolean = false
    var certVerificationFingerprint: String = "SHA256:MOCK_CERT"
    var certVerificationHost: String = "mock.server.local"
    var activeConfig: RdpConnectionConfig? = null

    /**
     * Real echo-suppression state machine — identical to the native engine's handler.
     * [ClipboardHandler.onSendRemoteClipboard] is a no-op here: "sending to remote" is
     * modelled by recording into [recordedClipboardTexts] when the handler accepts.
     */
    private val clipboardHandler = ClipboardHandler(
        onSendRemoteClipboard = { _, _ -> },
        onLocalClipboardUpdate = { text ->
            eventListener?.onClipboardDataReceived(
                ClipboardHandler.CF_UNICODETEXT,
                ClipboardHandler.encodeUnicodeText(text)
            )
        }
    )

    override suspend fun connect(config: RdpConnectionConfig): Boolean {
        activeConfig = config
        _connectionState.value = RdpConnectionState.Connecting

        if (simulateCertVerification) {
            // Fail closed without a listener — parity with the native engine, which
            // returns 0 (reject) from OnVerifyCertificateEx when nothing is registered.
            val accepted = eventListener?.onCertificateVerification(
                certVerificationFingerprint,
                certVerificationHost
            ) ?: false
            if (!accepted) {
                val error = "Certificate rejected by user"
                _connectionState.value = RdpConnectionState.Failed(403, error)
                eventListener?.onConnectionFailure(403, error)
                return false
            }
        }

        if (shouldFailConnection) {
            _connectionState.value = RdpConnectionState.Failed(failureErrorCode, failureErrorMessage)
            eventListener?.onConnectionFailure(failureErrorCode, failureErrorMessage)
            return false
        }

        _connectionState.value = RdpConnectionState.Connected
        eventListener?.onConnectionSuccess()
        return true
    }

    override suspend fun disconnect() {
        _connectionState.value = RdpConnectionState.Disconnected
        activeConfig = null
        clipboardHandler.clearEchoState()
        eventListener?.onDisconnected()
    }

    override fun sendPointerEvent(flags: Int, x: Int, y: Int) {
        recordedPointerEvents.add(PointerEvent(flags, x, y))
    }

    override fun sendKeyEvent(keyCode: Int, down: Boolean) {
        recordedKeyEvents.add(KeyEvent(keyCode, down))
    }

    override fun sendUnicodeKeyEvent(unicodeChar: Char, down: Boolean) {
        recordedUnicodeEvents.add(UnicodeEvent(unicodeChar, down))
    }

    override fun updateResolution(
        width: Int,
        height: Int,
        physicalWidthMm: Int,
        physicalHeightMm: Int,
        orientation: Int
    ) {
        recordedResolutions.add(ResolutionEvent(width, height, physicalWidthMm, physicalHeightMm, orientation))
        eventListener?.onResolutionChanged(width, height)
    }

    override fun sendClipboardText(text: String) {
        // Routed through the real handler: echo of a previously received remote text
        // is suppressed (not recorded) — native-engine parity.
        if (clipboardHandler.onLocalClipboardChanged(text)) {
            recordedClipboardTexts.add(text)
        }
    }

    override fun setEventListener(listener: RdpEventListener?) {
        this.eventListener = listener
    }

    fun triggerGraphicsUpdate(bitmap: Bitmap, x: Int, y: Int, width: Int, height: Int) {
        recordedGraphicsUpdates.add(GraphicsRegion(x, y, width, height))
        eventListener?.onGraphicsUpdate(bitmap, x, y, width, height)
    }

    /** Mirrors the native OnGraphicsResize callback (server-driven desktop resize). */
    fun triggerGraphicsResize(width: Int, height: Int, bpp: Int) {
        recordedGraphicsResizes.add(GraphicsResize(width, height, bpp))
        eventListener?.onResolutionChanged(width, height)
    }

    fun triggerClipboardReceived(format: Int, data: ByteArray) {
        if (format == ClipboardHandler.CF_UNICODETEXT) {
            // Real handler path: echo of our last local send is suppressed (parity with
            // NativeFreeRdpEngine.onRemoteClipboardChanged); new remote text dispatches
            // as UTF-16LE payload to the listener.
            clipboardHandler.onRemoteClipboardReceived(format, data)
        } else {
            // Non-text formats are forwarded untouched (native text path is CF_UNICODETEXT).
            eventListener?.onClipboardDataReceived(format, data)
        }
    }

    fun triggerConnectionFailure(errorCode: Int, message: String) {
        _connectionState.value = RdpConnectionState.Failed(errorCode, message)
        eventListener?.onConnectionFailure(errorCode, message)
    }

    fun triggerSessionDrop(reason: String = "Network dropped") {
        _connectionState.value = RdpConnectionState.Failed(503, reason)
        eventListener?.onDisconnected()
    }

    fun setMetrics(metrics: RdpSessionMetrics) {
        _sessionMetrics.value = metrics
    }

    fun clearRecordedEvents() {
        recordedPointerEvents.clear()
        recordedKeyEvents.clear()
        recordedUnicodeEvents.clear()
        recordedResolutions.clear()
        recordedClipboardTexts.clear()
        recordedGraphicsUpdates.clear()
        recordedGraphicsResizes.clear()
    }
}
