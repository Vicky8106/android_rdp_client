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
import com.freerdp.feature.session.data.AtomicFileProfileRepository
import com.freerdp.feature.session.keyboard.ScancodeTranslator
import com.freerdp.feature.session.model.CredentialStorageType
import com.freerdp.feature.session.model.RdpProfile
import com.freerdp.feature.session.model.SecurityConfig
import com.freerdp.feature.session.modifier.MacroAction
import com.freerdp.feature.session.modifier.ModifierKey
import com.freerdp.feature.session.modifier.ModifierStateMachine
import com.freerdp.feature.session.security.CredentialStore
import com.freerdp.feature.session.security.KeystoreCredentialStore
import com.freerdp.feature.session.toolbar.QuickActionToolbarFSM
import com.freerdp.feature.session.toolbar.ToolbarAction
import com.freerdp.feature.session.toolbar.ToolbarState
import com.freerdp.feature.telemetry.display.DynamicLayoutListener
import com.freerdp.feature.telemetry.metrics.TelemetryCollector
import com.freerdp.feature.telemetry.pacer.FramePacer
import com.freerdp.feature.telemetry.reconnect.AutoReconnectManagerImpl
import com.freerdp.feature.telemetry.reconnect.ReconnectState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/**
 * TIER 4 — Real-World Scenarios.
 *
 * The five SCENARIO workflows from TEST_INFRA.md § Tier 4, each executed as a
 * single multi-step deterministic test. Scenario steps are composed exclusively
 * from feature-module public APIs plus MockRdpEngine (app/src/main is owned by
 * a concurrent agent and is deliberately not imported).
 *
 * No Thread.sleep, no real network, no wall-clock timing: Robolectric for
 * Android SDK surfaces and StandardTestDispatcher virtual time everywhere.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class Tier4RealWorldScenariosTest {

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

    private class ScenarioGestureListener : GestureEventListener {
        var singleTapCount = 0
        var pinchCount = 0
        var panCount = 0
        var lastPinchScale = 1.0f
        var lastFocusX = 0f
        var lastFocusY = 0f

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

    private fun dual(action: Int, x0: Float, y0: Float, x1: Float, y1: Float, downTime: Long, eventTime: Long): MotionEvent {
        val pp0 = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
        val pp1 = MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER }
        val pc0 = MotionEvent.PointerCoords().apply { x = x0; y = y0 }
        val pc1 = MotionEvent.PointerCoords().apply { x = x1; y = y1 }
        return MotionEvent.obtain(downTime, eventTime, action, 2, arrayOf(pp0, pp1), arrayOf(pc0, pc1), 0, 0, 1.0f, 1.0f, 0, 0, 0, 0)
    }

    private fun identityTransformer(): CoordinateTransformer {
        val t = CoordinateTransformer(remoteWidth = 1920, remoteHeight = 1080, viewWidth = 1080, viewHeight = 2400)
        t.setTransform(newScale = 1f, transX = 0f, transY = 0f, clamp = false)
        return t
    }

    // ==================================================================
    // SCENARIO-1: Enterprise Workstation Login & Task Execution
    // NLA handshake -> desktop canvas ready -> quick-action toolbar toggle
    // -> modifier shortcut -> mouse click
    // ==================================================================
    @Test
    fun scenario1_enterpriseLoginToolbarModifierAndMouseClick() = runTest {
        val context = RuntimeEnvironment.getApplication()

        // --- Step 1: one-tap profile with keystore-encrypted credentials ---
        val profileDir = Files.createTempDirectory("e2e_s1_profiles").toFile()
        val repo = AtomicFileProfileRepository(File(profileDir, "profiles.json"), StandardTestDispatcher(testScheduler))
        val vault: CredentialStore = KeystoreCredentialStore(context, prefFileName = "e2e_s1_vault")
        vault.clearAll()

        val created = RdpProfile(
            label = "Enterprise WS",
            hostname = "ws.corp.example",
            username = "j.doe",
            domain = "CORP",
            credentialStorageType = CredentialStorageType.KEYSTORE_ENCRYPTED,
            securityConfig = SecurityConfig(nlaEnabled = true, tlsEnabled = true)
        )
        vault.saveSecret(created.id, "Enterprise#Pass1".toCharArray())
        repo.saveProfile(created)

        // "One tap": profile reloaded from disk, password pulled from the vault
        val loaded = repo.getAllProfiles().first().single()
        val secret = vault.getSecret(loaded.id)!!
        val config = loaded.toConnectionConfig(password = String(secret))
        assertTrue(config.enableNla)
        assertTrue(config.enableTls)
        assertTrue(config.enableClipboard)

        // --- Step 2: NLA handshake with certificate check, canvas ready ---
        val engine = MockRdpEngine()
        val listener = RecordingListener()
        engine.setEventListener(listener)
        engine.simulateCertVerification = true
        // Verification callback reports the workstation identity being validated
        engine.certVerificationHost = "ws.corp.example"
        engine.certVerificationFingerprint = "SHA256:WS_LEAF_01"

        assertTrue("NLA login must succeed", engine.connect(config))
        assertEquals(RdpConnectionState.Connected, engine.connectionState.value)
        assertEquals(1, listener.successCount)
        assertEquals("ws.corp.example", listener.certHost)
        engine.updateResolution(1920, 1080, 304, 171, orientation = 0)
        assertEquals(listOf(1920 to 1080), listener.resolutionChanges)
        assertEquals("SHA256:WS_LEAF_01", listener.certFingerprint)

        // --- Step 3: quick-action toolbar toggle ---
        val toolbarActions = mutableListOf<ToolbarAction>()
        val toolbar = QuickActionToolbarFSM(
            inactivityTimeoutMs = 4000L,
            coroutineScope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
            onActionCallback = { toolbarActions.add(it) }
        )
        toolbar.expand()
        assertTrue(ToolbarState.EXPANDED == toolbar.state.value)
        toolbar.triggerAction(ToolbarAction.TOGGLE_MODIFIER_BAR)
        assertEquals(listOf(ToolbarAction.TOGGLE_MODIFIER_BAR), toolbarActions)

        // --- Step 4: modifier shortcut (Ctrl+S saves in the remote session) ---
        val modifier = ModifierStateMachine(engine)
        modifier.onModifierKeyTapped(ModifierKey.CTRL)
        modifier.onNonModifierKeyPressed('s')

        // --- Step 5: mouse click on a desktop icon ---
        val controller = DefaultMouseController(engine, identityTransformer())
        controller.handleLeftClick(320f, 480f)

        // Keyboard channel: Ctrl down, S down/up, Ctrl up (Set-1 0x1D / 0x1F)
        assertEquals(
            listOf(
                MockRdpEngine.KeyEvent(0x1D, true),
                MockRdpEngine.KeyEvent(0x1F, true),
                MockRdpEngine.KeyEvent(0x1F, false),
                MockRdpEngine.KeyEvent(0x1D, false)
            ),
            engine.recordedKeyEvents
        )
        // Pointer channel: down/up pair at the icon's desktop coordinates
        assertEquals(2, engine.recordedPointerEvents.size)
        assertEquals(RdpPointerFlags.LEFT_BUTTON_DOWN, engine.recordedPointerEvents[0].flags)
        assertEquals(320, engine.recordedPointerEvents[0].x)
        assertEquals(480, engine.recordedPointerEvents[0].y)
        assertEquals(RdpPointerFlags.LEFT_BUTTON_UP, engine.recordedPointerEvents[1].flags)

        // Toolbar auto-collapses after 4s of inactivity (virtual time)
        advanceTimeBy(3999)
        runCurrent()
        assertTrue(toolbar.isExpanded)
        advanceTimeBy(1)
        runCurrent()
        assertTrue("4s inactivity collapse", toolbar.isCollapsed)

        // --- Teardown: wipe the session's secrets ---
        vault.clearAll()
        assertNull(vault.getSecret(loaded.id))
    }

    // ==================================================================
    // SCENARIO-2: Multi-Slide Presentation Navigation
    // Fullscreen session -> 2-finger pinch-zoom -> viewport pan
    // -> right-click context menu -> wheel scroll through slides
    // ==================================================================
    @Test
    fun scenario2_presentationZoomPanRightClickAndWheel() {
        val engine = MockRdpEngine()
        val transformer = CoordinateTransformer(remoteWidth = 1920, remoteHeight = 1080, viewWidth = 1080, viewHeight = 2400)
        val controller = DefaultMouseController(engine, transformer)
        val probe = ScenarioGestureListener()
        val gestures = GestureDisambiguationEngine(handler = null, listener = probe)
        val now = SystemClock.uptimeMillis()

        // --- Step 1: fullscreen = fit the whole slide deck to the phone ---
        transformer.resetToFit()
        assertEquals(0.5625f, transformer.scale, 0.0001f) // min(1080/1920, 2400/1080)
        assertEquals(0f, transformer.translationX, 0.001f) // horizontally centred exactly

        // --- Step 2: 2-finger pinch zooms onto a chart ---
        gestures.onTouchEvent(single(MotionEvent.ACTION_DOWN, 400f, 1200f, now, now))
        gestures.onTouchEvent(dual(MotionEvent.ACTION_POINTER_DOWN, 400f, 1200f, 500f, 1300f, now, now + 10))
        gestures.onTouchEvent(dual(MotionEvent.ACTION_MOVE, 350f, 1150f, 550f, 1350f, now, now + 40))
        assertEquals(1, probe.pinchCount)
        transformer.applyZoom(probe.lastPinchScale, probe.lastFocusX, probe.lastFocusY)
        assertEquals("fit 0.5625 x pinch 2.0", 1.125f, transformer.scale, 0.001f)

        // --- Step 3: pan the zoomed viewport; latch absorbs the finger lift ---
        val minTx = 1080f - 1920f * transformer.scale
        // Moderate pan back toward the content centre stays inside the bounds
        transformer.applyPan(+300f, 0f)
        assertTrue(
            "pan moved within bounds",
            transformer.translationX > minTx && transformer.translationX <= 0f
        )
        // Massive overshoot clamps exactly at the content edge — no void space
        transformer.applyPan(-1_000_000f, -1_000_000f)
        assertEquals("pan stops at the content edge", minTx, transformer.translationX, 0.001f)

        gestures.onTouchEvent(dual(MotionEvent.ACTION_POINTER_UP, 350f, 1150f, 550f, 1350f, now, now + 70))
        gestures.onTouchEvent(single(MotionEvent.ACTION_UP, 350f, 1150f, now, now + 95))
        assertEquals("no phantom tap after zoom/pan lift-off", 0, probe.singleTapCount)

        // --- Step 4: right-click opens the context menu ---
        controller.handleRightClick(700f, 1500f)

        // --- Step 5: wheel down through the remaining slides ---
        controller.handleScroll(700f, 1500f, -1f)
        controller.handleScroll(700f, 1500f, -1f)
        controller.handleScroll(700f, 1500f, -1f)

        // The whole navigation produced exactly: right-down, right-up, 3 wheel
        // events — gestures contributed zero clicks.
        val flags = engine.recordedPointerEvents.map { it.flags }
        assertEquals(
            listOf(
                RdpPointerFlags.RIGHT_BUTTON_DOWN,
                RdpPointerFlags.RIGHT_BUTTON_UP,
                RdpPointerFlags.SCROLL_DOWN,
                RdpPointerFlags.SCROLL_DOWN,
                RdpPointerFlags.SCROLL_DOWN
            ),
            flags
        )
        assertEquals(0x0378, flags[2]) // WHEEL | WHEEL_NEGATIVE | 120
    }

    // ==================================================================
    // SCENARIO-3: Remote IDE & Terminal Coding Session
    // Latching Ctrl -> terminal scancodes -> text clipboard sync
    // -> drag-lock code selection
    // ==================================================================
    @Test
    fun scenario3_remoteIdeTerminalClipboardAndSelection() {
        val engine = MockRdpEngine()
        val modifier = ModifierStateMachine(engine)
        val controller = DefaultMouseController(engine, identityTransformer())
        val localUpdates = mutableListOf<String>()
        val clipboard = ClipboardHandler(
            onSendRemoteClipboard = { _, data ->
                engine.sendClipboardText(ClipboardHandler.decodeUnicodeText(data))
            },
            onLocalClipboardUpdate = { localUpdates.add(it) }
        )

        // --- Step 1: latch Ctrl, tap 'c' -> terminal SIGINT shortcut ---
        modifier.onModifierKeyTapped(ModifierKey.CTRL)
        modifier.onNonModifierKeyPressed('c')
        assertEquals(
            listOf(
                MockRdpEngine.KeyEvent(0x1D, true),
                MockRdpEngine.KeyEvent(0x2E, true),
                MockRdpEngine.KeyEvent(0x2E, false),
                MockRdpEngine.KeyEvent(0x1D, false)
            ),
            engine.recordedKeyEvents
        )

        // --- Step 2: type a command using Windows Set-1 scancodes ---
        // 'l' 0x26, 's' 0x1F, Enter 0x1C
        modifier.onNonModifierKeyPressed('l')
        modifier.onNonModifierKeyPressed('s')
        modifier.onSpecialKeyTapped(ModifierKey.ENTER)
        val keys = engine.recordedKeyEvents
        assertEquals(MockRdpEngine.KeyEvent(0x26, true), keys[4])
        assertEquals(MockRdpEngine.KeyEvent(0x26, false), keys[5])
        assertEquals(MockRdpEngine.KeyEvent(0x1F, true), keys[6])
        assertEquals(MockRdpEngine.KeyEvent(0x1F, false), keys[7])
        assertEquals(MockRdpEngine.KeyEvent(0x1C, true), keys[8])
        assertEquals(MockRdpEngine.KeyEvent(0x1C, false), keys[9])

        // --- Step 3: synchronize selected text via MS-RDPECLIP ---
        val snippet = "SELECTED_SECRET = hunter2"
        assertTrue(clipboard.onLocalClipboardChanged(snippet))
        assertEquals(listOf(snippet), engine.recordedClipboardTexts)
        // Remote echoes the same payload back -> suppressed, no loop
        assertFalse(
            clipboard.onRemoteClipboardReceived(
                ClipboardHandler.CF_UNICODETEXT,
                ClipboardHandler.encodeUnicodeText(snippet)
            )
        )
        assertEquals(0, localUpdates.size)

        // --- Step 4: drag-lock selection over the code block ---
        controller.handleDragStart(200f, 600f)
        controller.handleDragMove(350f, 600f)
        controller.handleDragMove(500f, 640f)
        controller.handleDragEnd(500f, 640f)

        val pointer = engine.recordedPointerEvents
        assertEquals(4, pointer.size)
        assertEquals(RdpPointerFlags.LEFT_BUTTON_DOWN, pointer[0].flags)
        val dragFlags = RdpPointerFlags.MOVE or RdpPointerFlags.PTR_FLAGS_BUTTON1 or RdpPointerFlags.PTR_FLAGS_DOWN
        assertEquals(dragFlags, pointer[1].flags)
        assertEquals(dragFlags, pointer[2].flags)
        assertEquals(RdpPointerFlags.LEFT_BUTTON_UP, pointer[3].flags)
        assertEquals(500, pointer[3].x)
        assertEquals(640, pointer[3].y)

        // --- Step 5: latch Shift to extend the selection, then Win+R macro ---
        modifier.onModifierKeyTapped(ModifierKey.SHIFT)
        assertTrue(modifier.isLatched(ModifierKey.SHIFT))
        modifier.onNonModifierKeyPressed('x') // Shift+X extends the code selection
        assertEquals(
            listOf(
                MockRdpEngine.KeyEvent(0x2A, true),  // LEFT_SHIFT down
                MockRdpEngine.KeyEvent(0x2D, true),  // X down
                MockRdpEngine.KeyEvent(0x2D, false), // X up
                MockRdpEngine.KeyEvent(0x2A, false)  // latch auto-released
            ),
            engine.recordedKeyEvents.takeLast(4)
        )
        assertFalse("keystroke consumed the latch", modifier.isKeyActive(ModifierKey.SHIFT))

        // Win+R Run dialog macro emits exactly its four Set-1 steps
        modifier.triggerMacro(MacroAction.WIN_R)
        assertEquals(
            listOf(
                MockRdpEngine.KeyEvent(0x5B, true),  // LEFT_WIN (extended) down
                MockRdpEngine.KeyEvent(0x13, true),  // R down
                MockRdpEngine.KeyEvent(0x13, false), // R up
                MockRdpEngine.KeyEvent(0x5B, false)  // LEFT_WIN up
            ),
            engine.recordedKeyEvents.takeLast(4)
        )
        assertEquals(ScancodeTranslator.SCANCODE_R, 0x13)
    }

    // ==================================================================
    // SCENARIO-4: Intermittent Cellular Connection & Seamless Recovery
    // Active streaming -> drop -> exponential backoff w/ jitter -> fast-path
    // reconnect -> zero session leak
    // ==================================================================
    @Test
    fun scenario4_cellularDropBackoffReconnectWithoutSessionLeak() = runTest {
        val engine = MockRdpEngine()
        val listener = RecordingListener()
        engine.setEventListener(listener)
        val config = RdpConnectionConfig(serverAddress = "cell-tower.example", username = "mobile")

        var renderLoopDrains = 0
        val dispatcher = StandardTestDispatcher(testScheduler)
        val manager = AutoReconnectManagerImpl(
            engine = engine,
            configProvider = { config },
            coroutineScope = this,
            ioDispatcher = dispatcher,
            maxAttempts = 5,
            baseDelayMs = 1000L,
            maxDelayMs = 30_000L,
            // Deterministic jitter: always take the top of the window
            randomProvider = { _, max -> max },
            renderLoopDrainAction = { renderLoopDrains++ },
            onTeardownStepListener = { _, _ -> }
        )
        runCurrent()

        val telemetry = TelemetryCollector()

        // --- Step 1: healthy streaming session ---
        assertTrue(engine.connect(config))
        runCurrent()
        assertEquals(ReconnectState.Connected, manager.reconnectState.value)
        telemetry.updateConnectionState("Connected")
        repeat(3) { telemetry.recordRtt(30) }

        // --- Step 2: cellular handoff drops the socket ---
        engine.triggerSessionDrop("cellular handoff")
        runCurrent()

        val recovering = manager.reconnectState.value
        assertTrue("expected Reconnecting, was $recovering", recovering is ReconnectState.Reconnecting)
        assertEquals(1, (recovering as ReconnectState.Reconnecting).attempt)
        assertEquals("attempt 1 full-jitter bound = 1000ms", 1000L, recovering.nextDelayMs)
        assertEquals(
            "5-step leak-free teardown ran exactly once, in order",
            listOf(1, 2, 3, 4, 5),
            manager.executedTeardownHistory.map { it.substringAfter("Step ").first() }.map { it - '0' }.take(5)
        )
        assertEquals("render loop drained during teardown", 1, renderLoopDrains)
        assertEquals("native context deallocated while backing off", RdpConnectionState.Disconnected, engine.connectionState.value)
        telemetry.updateConnectionState("Reconnecting")

        // --- Step 3: backoff elapses at exactly 1000ms (deterministic jitter) ---
        advanceTimeBy(999)
        runCurrent()
        assertTrue(
            "still backing off 1ms before deadline",
            manager.reconnectState.value is ReconnectState.Reconnecting
        )
        advanceTimeBy(2)
        runCurrent()
        advanceUntilIdle()

        // --- Step 4: fast-path reconnect established ---
        assertEquals(ReconnectState.Connected, manager.reconnectState.value)
        assertEquals(RdpConnectionState.Connected, engine.connectionState.value)
        assertEquals(config, engine.activeConfig)
        // Handshake callback fired for the original connect AND the recovery connect
        assertEquals(2, listener.successCount)
        telemetry.updateConnectionState("Connected")

        // --- Step 5: zero session leak — telemetry history survives, and the
        // attempt counter reset so the next drop starts a fresh cycle ---
        val snap = telemetry.getSnapshot()
        assertEquals("Connected", snap.connectionState)
        assertEquals("telemetry must not reset across the drop", 30.0, snap.avgRttMs, 1e-6)

        engine.triggerSessionDrop("second handoff")
        runCurrent()
        val secondCycle = manager.reconnectState.value
        assertTrue(secondCycle is ReconnectState.Reconnecting)
        assertEquals("attempt counter must reset after recovery", 1, (secondCycle as ReconnectState.Reconnecting).attempt)
        assertEquals(
            "two full teardown cycles completed",
            listOf(1, 2, 3, 4, 5, 1, 2, 3, 4, 5),
            manager.executedTeardownHistory.map { it.substringAfter("Step ").first() }.map { it - '0' }
        )

        // Clean shutdown of the second recovery cycle
        manager.cancelReconnect()
        advanceUntilIdle()
        assertEquals(ReconnectState.Idle, manager.reconnectState.value)
        assertEquals(RdpConnectionState.Disconnected, engine.connectionState.value)
    }

    // ==================================================================
    // SCENARIO-5: Foldable / Multi-Window Resizing & High-Load Frame Pacing
    // Device unfolded -> MS-RDPEDISP layout update -> rapid 60fps frame burst
    // -> atomic single-slot frame dropper eliminating lag
    // ==================================================================
    @Test
    fun scenario5_foldableUnfoldResizeThen60fpsBurstThroughSingleSlotPacer() = runTest {
        val engine = MockRdpEngine()
        val listener = RecordingListener()
        engine.setEventListener(listener)
        assertTrue(engine.connect(RdpConnectionConfig(serverAddress = "foldable.example")))
        runCurrent()

        // --- Step 1: foldable unfolds -> MS-RDPEDISP monitor layout update ---
        val layout = DynamicLayoutListener(engine = engine, coroutineScope = backgroundScope, debounceDelayMs = 200L)
        layout.onWindowSizeChanged(widthPx = 2208, heightPx = 1668, densityDpi = 320, orientation = 0, immediate = true)

        assertEquals(1, layout.resizeEventsCount)
        assertEquals(1, engine.recordedResolutions.size)
        val pdu = engine.recordedResolutions[0]
        assertEquals(2208, pdu.width)
        assertEquals(1668, pdu.height)
        // Physical size: (2208px * 25.4mm) / 320dpi = 175.26mm -> truncated to 175
        assertEquals(175, pdu.physicalWidthMm)
        assertEquals(132, pdu.physicalHeightMm)
        assertEquals(0, pdu.orientation)
        assertEquals(listOf(2208 to 1668), listener.resolutionChanges)

        // User folds back to the outer portrait display
        layout.onWindowSizeChanged(widthPx = 1668, heightPx = 2208, densityDpi = 320, orientation = 1, immediate = true)
        assertEquals(2, layout.resizeEventsCount)
        assertEquals(2, engine.recordedResolutions.size)
        assertEquals(1, engine.recordedResolutions[1].orientation)

        // --- Step 2: decoder races ahead: 60fps burst into the single-slot pacer ---
        val dropped = mutableListOf<Bitmap>()
        val pacer = FramePacer(onFrameDroppedCallback = { dropped.add(it) })
        val frames = (1..60).map { Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888) }
        val burstBase = 1_000_000_000L

        assertFalse(
            "first frame occupies the empty slot",
            pacer.onFrameDecoded(frames[0], timestampNanos = burstBase)
        )
        for (i in 1 until 60) {
            assertTrue(
                "every overwrite of a stale frame is an atomic drop",
                pacer.onFrameDecoded(frames[i], timestampNanos = burstBase + i * 16_666_667L)
            )
        }
        assertEquals(60L, pacer.totalDecoded)
        assertEquals(59L, pacer.totalDropped)
        assertEquals("every dropped frame reported to the diagnostic sink", 59, dropped.size)

        // --- Step 3: VSYNC blit consumes exactly the freshest frame (1ms after
        // the last decode on the injected clock — never stale) ---
        val blitTime = burstBase + 59 * 16_666_667L + 1_000_000L
        assertTrue(pacer.hasPendingFrame)
        assertSame("must render frame #60, never a stale one", frames[59], pacer.peekPendingFrame())
        assertSame(frames[59], pacer.acquireFrameForRendering(nowNanos = blitTime))
        assertNull("slot empty until the next decode", pacer.acquireFrameForRendering(nowNanos = blitTime))
        assertEquals(1L, pacer.totalRendered)
        assertEquals("drop ratio = 59/60", 59f / 60f, pacer.dropRatio, 1e-6f)

        // --- Step 4: telemetry mirrors the burst for the HUD ---
        val telemetry = TelemetryCollector()
        val base = 1_000_000_000L
        repeat(60) { i -> telemetry.recordFrameDelivered(base + i * 16_666_667L) }
        repeat(59) { telemetry.recordFrameDropped() }

        val snap = telemetry.getSnapshot()
        assertEquals(60L, snap.totalFramesRendered)
        assertEquals(59L, snap.droppedFrames)
        assertEquals(60f, snap.fps, 0.001f)
        assertEquals("regular 60fps cadence has no jitter", 0.0, snap.jitterMs, 1e-9)

        // --- Step 5: a second micro-burst keeps the invariant (injected clock) ---
        val f61 = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val f62 = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val f63 = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        assertFalse(pacer.onFrameDecoded(f61, timestampNanos = burstBase + 60 * 16_666_667L))
        assertTrue(pacer.onFrameDecoded(f62, timestampNanos = burstBase + 61 * 16_666_667L))
        assertTrue(pacer.onFrameDecoded(f63, timestampNanos = burstBase + 62 * 16_666_667L))
        assertSame(
            f63,
            pacer.acquireFrameForRendering(nowNanos = burstBase + 62 * 16_666_667L + 1_000_000L)
        )
        assertEquals(63L, pacer.totalDecoded)
        assertEquals(61L, pacer.totalDropped)
        assertEquals(2L, pacer.totalRendered)

        // Session never dropped during the resize+burst storm
        assertEquals(RdpConnectionState.Connected, engine.connectionState.value)
    }
}
