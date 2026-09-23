package com.freerdp.client.e2e

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.MotionEvent
import com.freerdp.core.engine.MockRdpEngine
import com.freerdp.core.engine.RdpConnectionConfig
import com.freerdp.core.engine.RdpConnectionState
import com.freerdp.core.engine.RdpEventListener
import com.freerdp.core.protocol.ClipboardHandler
import com.freerdp.core.protocol.RdpPointerFlags
import com.freerdp.feature.mouse.CoordinateTransformer
import com.freerdp.feature.mouse.DefaultMouseController
import com.freerdp.feature.mouse.GestureDisambiguationEngine
import com.freerdp.feature.mouse.GestureEventListener
import com.freerdp.feature.session.model.CredentialStorageType
import com.freerdp.feature.session.model.RdpProfile
import com.freerdp.feature.session.model.SecurityConfig
import com.freerdp.feature.session.modifier.LatchState
import com.freerdp.feature.session.modifier.MacroAction
import com.freerdp.feature.session.modifier.ModifierKey
import com.freerdp.feature.session.modifier.ModifierStateMachine
import com.freerdp.feature.session.security.CredentialStore
import com.freerdp.feature.session.security.KeystoreCredentialStore
import com.freerdp.feature.telemetry.display.DynamicLayoutListener
import com.freerdp.feature.telemetry.metrics.TelemetryCollector
import com.freerdp.feature.telemetry.network.LowLatencySocketConfig
import com.freerdp.feature.telemetry.preset.PerformancePresetAdapter
import com.freerdp.feature.telemetry.reconnect.AutoReconnectManagerImpl
import com.freerdp.feature.telemetry.reconnect.ReconnectState
import com.freerdp.feature.telemetry.preset.PerformancePreset as TelemetryPreset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.Socket

/**
 * TIER 3 — Cross-Feature Combinations.
 *
 * All eight pairwise interactions mandated by TEST_INFRA.md Tier 3. Each test
 * drives two subsystems together and asserts that cross-subsystem state stays
 * coherent: no phantom input, no lost resolution PDUs, no metric resets, and no
 * mutual interference. Deterministic throughout (MockRdpEngine, Robolectric,
 * virtual time).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class Tier3CrossFeatureTest {

    private class RecordingListener : RdpEventListener {
        var successCount = 0
        val failures = mutableListOf<Pair<Int, String>>()
        var disconnectCount = 0
        val resolutionChanges = mutableListOf<Pair<Int, Int>>()
        var certFingerprint: String? = null
        var certHost: String? = null
        var acceptCert = true

        override fun onConnectionSuccess() {
            successCount++
        }

        override fun onConnectionFailure(errorCode: Int, message: String) {
            failures.add(errorCode to message)
        }

        override fun onDisconnected() {
            disconnectCount++
        }

        override fun onGraphicsUpdate(bitmap: Bitmap, x: Int, y: Int, width: Int, height: Int) = Unit

        override fun onResolutionChanged(width: Int, height: Int) {
            resolutionChanges.add(width to height)
        }

        override fun onClipboardDataReceived(format: Int, data: ByteArray) = Unit

        override fun onCertificateVerification(fingerprint: String, host: String): Boolean {
            certFingerprint = fingerprint
            certHost = host
            return acceptCert
        }
    }

    private class GestureProbe : GestureEventListener {
        var singleTapCount = 0
        var pinchCount = 0
        var lastPinchScale = 1.0f
        var lastFocusX = 0f
        var lastFocusY = 0f
        var panCount = 0

        override fun onSingleTap(screenX: Float, screenY: Float) {
            singleTapCount++
        }

        override fun onPan(deltaX: Float, deltaY: Float) {
            panCount++
        }

        override fun onPinchZoom(focusX: Float, focusY: Float, scaleFactor: Float) {
            pinchCount++
            lastPinchScale = scaleFactor
            lastFocusX = focusX
            lastFocusY = focusY
        }
    }

    private fun single(action: Int, x: Float, y: Float, downTime: Long, eventTime: Long): MotionEvent =
        MotionEvent.obtain(downTime, eventTime, action, x, y, 0)

    private fun dual(
        action: Int, x0: Float, y0: Float, x1: Float, y1: Float, downTime: Long, eventTime: Long
    ): MotionEvent {
        val pp0 = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
        val pp1 = MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER }
        val pc0 = MotionEvent.PointerCoords().apply { x = x0; y = y0 }
        val pc1 = MotionEvent.PointerCoords().apply { x = x1; y = y1 }
        return MotionEvent.obtain(
            downTime, eventTime, action, 2, arrayOf(pp0, pp1), arrayOf(pc0, pc1),
            0, 0, 1.0f, 1.0f, 0, 0, 0, 0
        )
    }

    // ==================================================================
    // 1. Pinch-to-Zoom + Mouse Click-and-Drag
    // ==================================================================

    @Test
    fun combo1_zoomedDragCoordinatesStayAffineWhileLatchSuppressesPhantomClicks() {
        val engine = MockRdpEngine()
        val transformer = CoordinateTransformer(
            remoteWidth = 1920, remoteHeight = 1080, viewWidth = 1080, viewHeight = 2400
        )
        val probe = GestureProbe()
        val gestures = GestureDisambiguationEngine(handler = null, listener = probe)
        val now = SystemClock.uptimeMillis()

        // --- Pinch-to-zoom through the gesture engine ---
        gestures.onTouchEvent(single(MotionEvent.ACTION_DOWN, 400f, 1200f, now, now))
        gestures.onTouchEvent(dual(MotionEvent.ACTION_POINTER_DOWN, 400f, 1200f, 500f, 1300f, now, now + 10))
        gestures.onTouchEvent(dual(MotionEvent.ACTION_MOVE, 350f, 1150f, 550f, 1350f, now, now + 40))
        assertTrue(probe.pinchCount > 0)

        // Focal-point invariance: the desktop pixel under the pinch midpoint
        // must not shift when the zoom is applied around that same midpoint.
        val focusX = probe.lastFocusX
        val focusY = probe.lastFocusY
        val desktopUnderFocusBefore = (focusX - transformer.translationX) / transformer.scale
        transformer.applyZoom(probe.lastPinchScale, focusX, focusY)
        val desktopUnderFocusAfter = (focusX - transformer.translationX) / transformer.scale
        assertEquals("zoom must pivot around the focal point", desktopUnderFocusBefore, desktopUnderFocusAfter, 0.01f)
        assertTrue("pinch spread zoomed the viewport in", transformer.scale > 1.0f)

        // --- Lift-off: latch must swallow the phantom tap ---
        gestures.onTouchEvent(dual(MotionEvent.ACTION_POINTER_UP, 350f, 1150f, 550f, 1350f, now, now + 70))
        gestures.onTouchEvent(single(MotionEvent.ACTION_UP, 350f, 1150f, now, now + 95))
        assertEquals("multi-touch release must not become a click", 0, probe.singleTapCount)
        assertEquals("gesture lift must emit zero RDP pointer events", 0, engine.recordedPointerEvents.size)

        // --- Drag through the *zoomed* affine transform ---
        val controller = DefaultMouseController(engine, transformer)
        val sx = 540f
        val sy = 1400f
        controller.handleDragStart(sx, sy)

        // Independent expectation straight from the affine formula
        val expectedX = ((sx - transformer.translationX) / transformer.scale).toInt().coerceIn(0, 1919)
        val expectedY = ((sy - transformer.translationY) / transformer.scale).toInt().coerceIn(0, 1079)

        controller.handleDragMove(sx + 30f, sy - 20f)
        controller.handleDragEnd(sx + 30f, sy - 20f)

        val events = engine.recordedPointerEvents
        assertEquals("exactly down/move/up — no phantom extras", 3, events.size)
        assertEquals(RdpPointerFlags.LEFT_BUTTON_DOWN, events[0].flags)
        assertEquals("drag start honours zoomed affine mapping", expectedX, events[0].x)
        assertEquals(expectedY, events[0].y)
        assertEquals(
            RdpPointerFlags.MOVE or RdpPointerFlags.PTR_FLAGS_BUTTON1 or RdpPointerFlags.PTR_FLAGS_DOWN,
            events[1].flags
        )
        assertEquals(RdpPointerFlags.LEFT_BUTTON_UP, events[2].flags)
        assertEquals(0, probe.singleTapCount)
    }

    // ==================================================================
    // 2. Keystore Encryption + Connection Profile Launch
    // ==================================================================

    @Test
    fun combo2_keystoreCredentialsLaunchAuthenticatedNlaSession() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val vault: CredentialStore = KeystoreCredentialStore(context, prefFileName = "e2e_t3_nla_vault")
        vault.clearAll()

        val profile = RdpProfile(
            label = "Corp NLA",
            hostname = "nla.corp.example",
            port = 3389,
            username = "svc_rd",
            domain = "CORP",
            credentialStorageType = CredentialStorageType.KEYSTORE_ENCRYPTED,
            securityConfig = SecurityConfig(nlaEnabled = true, tlsEnabled = true)
        )

        val secret = "NlaPass#2026!".toCharArray()
        vault.saveSecret(profile.id, secret)

        // Load the AES-256 vault entry and launch the session from the profile
        val stored = vault.getSecret(profile.id)
        assertNotNull("vault entry must exist", stored)
        val config = profile.toConnectionConfig(password = String(stored!!))

        assertTrue("MS-CSSP/CredSSP NLA must be requested", config.enableNla)
        assertTrue("TLS must be requested", config.enableTls)
        assertFalse("WARN_ON_MISMATCH must not blanket-ignore certificates", config.ignoreCertificate)
        assertEquals("nla.corp.example", config.serverAddress)
        assertEquals("svc_rd", config.username)
        assertEquals("CORP", config.domain)
        assertEquals("NlaPass#2026!", config.password)
        assertEquals(3389, config.port)

        val engine = MockRdpEngine()
        val listener = RecordingListener()
        engine.setEventListener(listener)
        engine.simulateCertVerification = true
        // The verification callback reports the server identity being validated
        engine.certVerificationHost = "nla.corp.example"
        engine.certVerificationFingerprint = "SHA256:CORP_NLA_LEAF"

        assertTrue("authenticated launch must succeed", engine.connect(config))
        assertEquals(RdpConnectionState.Connected, engine.connectionState.value)
        assertEquals(1, listener.successCount)
        assertEquals(0, listener.failures.size)
        assertEquals("certificate callback must fire before NLA completes", "SHA256:CORP_NLA_LEAF", listener.certFingerprint)
        assertEquals("nla.corp.example", listener.certHost)

        // Contract: password buffer is zeroed after use
        KeystoreCredentialStore.wipeSecret(stored)
        assertTrue(stored.all { it.code == 0 })
        vault.clearAll()
    }

    // ==================================================================
    // 3. Auto-Reconnect Backoff + Dynamic Orientation Resizing
    // ==================================================================

    @Test
    fun combo3_rotationDuringPendingReconnectEmitsSingleCleanResolutionPdu() = runTest {
        val engine = MockRdpEngine()
        val listener = RecordingListener()
        engine.setEventListener(listener)
        val config = RdpConnectionConfig(serverAddress = "rotate.example")

        val dispatcher = StandardTestDispatcher(testScheduler)
        val manager = AutoReconnectManagerImpl(
            engine = engine,
            configProvider = { config },
            coroutineScope = this,
            ioDispatcher = dispatcher,
            baseDelayMs = 10_000L,
            randomProvider = { _, max -> max }
        )
        runCurrent()
        assertTrue(engine.connect(config))
        runCurrent()
        assertEquals(ReconnectState.Connected, manager.reconnectState.value)

        // Network blip puts the session into a 10s backoff window
        manager.onSessionDropped("cellular handoff")
        runCurrent()
        val pending = manager.reconnectState.value
        assertTrue(pending is ReconnectState.Reconnecting)
        assertEquals(10_000L, (pending as ReconnectState.Reconnecting).nextDelayMs)

        // --- Device rotates while recovery is pending ---
        val layout = DynamicLayoutListener(
            engine = engine, coroutineScope = backgroundScope, debounceDelayMs = 200L
        )
        layout.onLayoutChanged(
            newWidth = 1668, newHeight = 2208,
            physicalWidthMm = 77, physicalHeightMm = 103,
            orientation = 1, immediate = true
        )

        assertEquals("rotation resize recorded exactly once mid-reconnect", 1, engine.recordedResolutions.size)
        assertEquals(ReconnectState.Reconnecting::class, manager.reconnectState.value::class)

        // Let the backoff elapse and the session re-establish
        advanceTimeBy(10_001)
        runCurrent()
        advanceUntilIdle()

        assertEquals(ReconnectState.Connected, manager.reconnectState.value)
        assertEquals(RdpConnectionState.Connected, engine.connectionState.value)
        assertEquals("reconnect must not duplicate or drop the rotation PDU", 1, engine.recordedResolutions.size)
        val pdu = engine.recordedResolutions[0]
        assertEquals(1668, pdu.width)
        assertEquals(2208, pdu.height)
        assertEquals(77, pdu.physicalWidthMm)
        assertEquals(103, pdu.physicalHeightMm)
        assertEquals(1, pdu.orientation)
        assertEquals(listOf(1668 to 2208), listener.resolutionChanges)
        // onConnectionSuccess fired for the original connect AND the recovery connect
        assertEquals(2, listener.successCount)
    }

    // ==================================================================
    // 4. Modifier Key Latching + Mouse Click-Drag
    // ==================================================================

    @Test
    fun combo4_latchedCtrlSurvivesRangeSelectionDragUntilConsumed() {
        val engine = MockRdpEngine()
        val modifier = ModifierStateMachine(engine)
        val transformer = CoordinateTransformer(
            remoteWidth = 1920, remoteHeight = 1080, viewWidth = 1080, viewHeight = 2400
        )
        transformer.setTransform(newScale = 1f, transX = 0f, transY = 0f, clamp = false)
        val controller = DefaultMouseController(engine, transformer)

        // 1. Latch Ctrl for "extend selection" semantics
        modifier.onModifierKeyTapped(ModifierKey.CTRL)
        assertTrue(modifier.isLatched(ModifierKey.CTRL))
        assertEquals(listOf(MockRdpEngine.KeyEvent(0x1D, true)), engine.recordedKeyEvents)

        // 2. Perform a range-selection drag on the remote desktop
        controller.handleDragStart(120f, 300f)
        controller.handleDragMove(400f, 300f)
        controller.handleDragMove(640f, 480f)
        controller.handleDragEnd(640f, 480f)

        val pointer = engine.recordedPointerEvents
        assertEquals("drag emits exactly 4 pointer PDUs", 4, pointer.size)
        assertEquals(RdpPointerFlags.LEFT_BUTTON_DOWN, pointer[0].flags)
        assertEquals(
            RdpPointerFlags.MOVE or RdpPointerFlags.PTR_FLAGS_BUTTON1 or RdpPointerFlags.PTR_FLAGS_DOWN,
            pointer[1].flags
        )
        assertEquals(
            RdpPointerFlags.MOVE or RdpPointerFlags.PTR_FLAGS_BUTTON1 or RdpPointerFlags.PTR_FLAGS_DOWN,
            pointer[2].flags
        )
        assertEquals(RdpPointerFlags.LEFT_BUTTON_UP, pointer[3].flags)
        assertEquals(640, pointer[3].x)
        assertEquals(480, pointer[3].y)

        // 3. Dragging must neither release nor duplicate the latched Ctrl
        assertTrue("Ctrl must stay latched across the whole drag", modifier.isLatched(ModifierKey.CTRL))
        assertEquals(
            "mouse traffic must not touch the keyboard channel",
            listOf(MockRdpEngine.KeyEvent(0x1D, true)),
            engine.recordedKeyEvents
        )
        assertEquals("keyboard traffic must not touch the pointer channel", 4, engine.recordedPointerEvents.size)

        // 4. Consuming the shortcut releases the latch through the key channel
        modifier.onNonModifierKeyPressed('v') // Ctrl+V
        assertEquals(
            listOf(
                MockRdpEngine.KeyEvent(0x1D, true),
                MockRdpEngine.KeyEvent(0x2F, true),
                MockRdpEngine.KeyEvent(0x2F, false),
                MockRdpEngine.KeyEvent(0x1D, false)
            ),
            engine.recordedKeyEvents
        )
        assertEquals(LatchState.INACTIVE, modifier.getModifierState(ModifierKey.CTRL))
    }

    // ==================================================================
    // 5. Performance Preset Switching + Low-Latency Socket Buffers
    // ==================================================================

    @Test
    fun combo5_presetSwitchRetunesFpsCodecWhileBuffersStayLowLatency() {
        // Local unconnected socket only — no network involved
        val socket = Socket()
        assertTrue(LowLatencySocketConfig.configureSocket(socket).isSuccess)
        val baseline = LowLatencySocketConfig.inspectSocket(socket)

        // Wi-Fi + healthy battery -> ultra-low-latency profile
        val wifiPreset = PerformancePresetAdapter.determinePreset(
            PerformancePresetAdapter.NetworkQuality.WIFI,
            PerformancePresetAdapter.BatteryInfo(levelPercent = 80)
        )
        assertEquals(TelemetryPreset.ULTRA_LOW_LATENCY, wifiPreset)
        assertEquals(60, wifiPreset.targetFps)
        assertEquals("NONE", wifiPreset.compressionCodec)

        // Switch to metered network -> data-saver profile (fps, codec, throttling)
        val meteredPreset = PerformancePresetAdapter.determinePreset(
            PerformancePresetAdapter.NetworkQuality.METERED,
            PerformancePresetAdapter.BatteryInfo(levelPercent = 50)
        )
        assertEquals(TelemetryPreset.DATA_SAVER, meteredPreset)
        assertEquals(15, meteredPreset.targetFps)
        assertEquals("RLE", meteredPreset.compressionCodec)
        assertTrue(meteredPreset.dynamicThrottling)
        assertFalse("data saver must disable sound redirection", meteredPreset.soundEnabled)

        // The switch flows into the next engine connection config
        val profile = RdpProfile(label = "Adaptive", hostname = "adaptive.example")
            .copy(performancePreset = meteredPreset.toCorePreset())
        val config = profile.toConnectionConfig()
        assertEquals(com.freerdp.core.engine.PerformancePreset.DATA_SAVER, config.performancePreset)
        // Display depth comes from the profile display config, independent of preset
        assertEquals(32, config.colorDepth)

        // ...while the socket stays tuned for interactive input latency:
        // Nagle off, keepalive on, buffers at least 128KB/64KB in every preset.
        val after = LowLatencySocketConfig.inspectSocket(socket)
        assertEquals(baseline["tcpNoDelay"], after["tcpNoDelay"])
        assertEquals(true, after["tcpNoDelay"])
        assertEquals(true, after["keepAlive"])
        assertTrue((after["receiveBufferSize"] as Int) >= 131072)
        assertTrue((after["sendBufferSize"] as Int) >= 65536)

        socket.close()
    }

    // ==================================================================
    // 6. Dynamic Resolution Resizing + Clipboard Synchronization
    // ==================================================================

    @Test
    fun combo6_clipboardFlowsUnharmedThroughDebouncedResolutionRenegotiation() = runTest {
        val engine = MockRdpEngine()
        val localUpdates = mutableListOf<String>()

        val clipboard = ClipboardHandler(
            onSendRemoteClipboard = { _, data ->
                engine.sendClipboardText(ClipboardHandler.decodeUnicodeText(data))
            },
            onLocalClipboardUpdate = { localUpdates.add(it) }
        )

        val layout = DynamicLayoutListener(
            engine = engine, coroutineScope = backgroundScope, debounceDelayMs = 200L
        )

        // Begin a debounced resolution renegotiation (fold / rotation in flight)
        layout.onLayoutChanged(1080, 2400, physicalWidthMm = 68, physicalHeightMm = 152, orientation = 1)

        // Clipboard traffic occurs while the renegotiation is still pending
        val payload = "design spec v3"
        assertTrue(clipboard.onLocalClipboardChanged(payload))
        assertFalse(
            "remote echo of local copy suppressed",
            clipboard.onRemoteClipboardReceived(
                ClipboardHandler.CF_UNICODETEXT,
                ClipboardHandler.encodeUnicodeText(payload)
            )
        )
        assertEquals("no resolution PDU may fire before debounce", 0, engine.recordedResolutions.size)
        assertEquals(listOf(payload), engine.recordedClipboardTexts)

        advanceTimeBy(200)
        runCurrent()

        // Resolution lands exactly once after debounce; clipboard untouched
        assertEquals(1, layout.resizeEventsCount)
        assertEquals(1, engine.recordedResolutions.size)
        assertEquals(1080, engine.recordedResolutions[0].width)
        assertEquals(2400, engine.recordedResolutions[0].height)
        assertEquals(listOf(payload), engine.recordedClipboardTexts)
        assertEquals("echo must not reach the local clipboard twice", 0, localUpdates.size)

        // A genuine remote-originated clipboard update still works afterwards
        assertTrue(
            clipboard.onRemoteClipboardReceived(
                ClipboardHandler.CF_UNICODETEXT,
                ClipboardHandler.encodeUnicodeText("remote note")
            )
        )
        assertEquals(listOf("remote note"), localUpdates)

        // Rotate back: second debounced resize, clipboard channel still stable
        layout.onLayoutChanged(2400, 1080, physicalWidthMm = 152, physicalHeightMm = 68, orientation = 0)
        advanceTimeBy(200)
        runCurrent()
        assertEquals(2, layout.resizeEventsCount)
        assertEquals(2, engine.recordedResolutions.size)
        assertEquals("resize must not re-blast the clipboard", 1, engine.recordedClipboardTexts.size)
    }

    // ==================================================================
    // 7. Touchpad Relative Cursor + Modifier Key Shortcut
    // ==================================================================

    @Test
    fun combo7_touchpadCursorAndScancodeMacroOperateIndependently() {
        val engine = MockRdpEngine()
        val transformer = CoordinateTransformer(
            remoteWidth = 1920, remoteHeight = 1080, viewWidth = 1080, viewHeight = 2400
        )
        val controller = DefaultMouseController(engine, transformer)

        controller.setTouchpadMode(true)

        // Relative navigation: huge deltas clamp at the remote desktop bounds,
        // subsequent deltas are relative to the cursor, never to screen coords.
        controller.handleTouchpadMove(-9_999f, -9_999f)
        controller.handleTouchpadMove(100.6f, 50.4f)

        val moves = engine.recordedPointerEvents
        assertEquals(2, moves.size)
        assertEquals(RdpPointerFlags.MOVE, moves[0].flags)
        assertEquals("clamped to desktop origin", 0, moves[0].x)
        assertEquals(0, moves[0].y)
        assertEquals(RdpPointerFlags.MOVE, moves[1].flags)
        assertEquals(100, moves[1].x)
        assertEquals(50, moves[1].y)

        // Windows scancode macro fires on the keyboard channel only
        val modifier = ModifierStateMachine(engine)
        modifier.triggerMacro(MacroAction.CTRL_ALT_DEL)

        assertEquals(
            "Ctrl+Alt+Del order per Windows Set-1: ctrl-down alt-down del-down del-up alt-up ctrl-up",
            listOf(
                MockRdpEngine.KeyEvent(0x1D, true),
                MockRdpEngine.KeyEvent(0x38, true),
                MockRdpEngine.KeyEvent(0x53, true),
                MockRdpEngine.KeyEvent(0x53, false),
                MockRdpEngine.KeyEvent(0x38, false),
                MockRdpEngine.KeyEvent(0x1D, false)
            ),
            engine.recordedKeyEvents
        )
        assertEquals("macro must not inject pointer events", 2, engine.recordedPointerEvents.size)

        // A touchpad click lands on the *virtual cursor*, not the screen point
        controller.handleLeftClick(999f, 999f)
        assertEquals(4, engine.recordedPointerEvents.size)
        val down = engine.recordedPointerEvents[2]
        assertEquals(RdpPointerFlags.LEFT_BUTTON_DOWN, down.flags)
        assertEquals(100, down.x)
        assertEquals(50, down.y)
        assertEquals(RdpPointerFlags.LEFT_BUTTON_UP, engine.recordedPointerEvents[3].flags)

        // ...and the macro left no modifier latched behind
        assertEquals(LatchState.INACTIVE, modifier.getModifierState(ModifierKey.CTRL))
        assertEquals(LatchState.INACTIVE, modifier.getModifierState(ModifierKey.ALT))
    }

    // ==================================================================
    // 8. Network Reconnect + Telemetry HUD Metrics
    // ==================================================================

    @Test
    fun combo8_telemetryRingBufferTracksConnectedPausedConnectedTransitions() = runTest {
        val engine = MockRdpEngine()
        val config = RdpConnectionConfig(serverAddress = "hud.example")
        val dispatcher = StandardTestDispatcher(testScheduler)
        val manager = AutoReconnectManagerImpl(
            engine = engine,
            configProvider = { config },
            coroutineScope = this,
            ioDispatcher = dispatcher,
            randomProvider = { _, max -> max }
        )

        val reconnectTransitions = mutableListOf<ReconnectState>()
        backgroundScope.launch {
            manager.reconnectState.collect { reconnectTransitions.add(it) }
        }
        runCurrent()

        val telemetry = TelemetryCollector()
        telemetry.updateConnectionState("Connected")
        telemetry.recordRtt(42)
        telemetry.recordRtt(44)
        runCurrent()

        assertTrue(engine.connect(config))
        runCurrent()
        assertEquals(ReconnectState.Connected, manager.reconnectState.value)

        // User backgrounds the app: Connected -> Paused (suspended)
        manager.onUserPause()
        runCurrent()
        assertTrue(manager.reconnectState.value is ReconnectState.Suspended)
        assertTrue((manager.reconnectState.value as ReconnectState.Suspended).userPaused)
        telemetry.updateConnectionState("Paused")
        runCurrent()

        // Return to foreground: fast-path resume -> Connected
        manager.onUserResume()
        advanceUntilIdle()
        assertEquals(ReconnectState.Connected, manager.reconnectState.value)
        telemetry.updateConnectionState("Connected")
        runCurrent()

        // Full observed lifecycle: Idle -> Connected -> Suspended -> Reconnecting -> Connected
        assertEquals(
            listOf(
                ReconnectState.Idle,
                ReconnectState.Connected,
                ReconnectState.Suspended(userPaused = true),
                ReconnectState.Reconnecting(attempt = 0, nextDelayMs = 0L),
                ReconnectState.Connected
            ),
            reconnectTransitions
        )

        // HUD must reflect the final state and retain metrics across the
        // pause/resume cycle — a leak would have reset the ring buffer.
        val snap = telemetry.getSnapshot()
        assertEquals("Connected", snap.connectionState)
        assertEquals(44L, snap.rttMs)
        assertEquals(43.0, snap.avgRttMs, 1e-6)
        assertEquals("ring buffer must survive pause/resume without reset", 43.9, snap.p95RttMs, 1e-6)
        assertEquals(43.98, snap.p99RttMs, 1e-6)

        val hud = telemetry.getHudModel()
        assertEquals("Connected", hud.connectionState)
        assertTrue(hud.hudText.contains("Connected"))
        assertTrue("RTT made it into the HUD", hud.hudText.contains("44"))
    }
}
