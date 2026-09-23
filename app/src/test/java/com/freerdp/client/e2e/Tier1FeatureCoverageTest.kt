package com.freerdp.client.e2e

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import com.freerdp.core.engine.MockRdpEngine
import com.freerdp.core.engine.RdpConnectionConfig
import com.freerdp.core.engine.RdpConnectionState
import com.freerdp.core.engine.RdpEventListener
import com.freerdp.core.engine.RdpSessionMetrics
import com.freerdp.core.protocol.ClipboardHandler
import com.freerdp.core.protocol.RdpPointerFlags
import com.freerdp.feature.mouse.CoordinateTransformer
import com.freerdp.feature.mouse.DefaultMouseController
import com.freerdp.feature.mouse.GestureDisambiguationEngine
import com.freerdp.feature.mouse.GestureEventListener
import com.freerdp.feature.session.data.AtomicFileProfileRepository
import com.freerdp.feature.session.keyboard.ScancodeTranslator
import com.freerdp.feature.session.model.RdpProfile
import com.freerdp.feature.session.modifier.LatchState
import com.freerdp.feature.session.modifier.ModifierKey
import com.freerdp.feature.session.modifier.ModifierStateMachine
import com.freerdp.feature.session.security.CredentialStore
import com.freerdp.feature.session.security.KeystoreCredentialStore
import com.freerdp.feature.session.toolbar.QuickActionToolbarFSM
import com.freerdp.feature.session.toolbar.ToolbarAction
import com.freerdp.feature.session.toolbar.ToolbarState
import com.freerdp.feature.telemetry.metrics.DiagnosticHudModel
import com.freerdp.feature.telemetry.metrics.TelemetryCollector
import com.freerdp.feature.telemetry.network.FastPathPduPrioritizer
import com.freerdp.feature.telemetry.network.LowLatencySocketConfig
import com.freerdp.feature.telemetry.pacer.FramePacer
import com.freerdp.feature.telemetry.preset.PerformancePresetAdapter
import com.freerdp.feature.telemetry.preset.PerformancePreset as TelemetryPreset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.net.Socket
import java.nio.file.Files

/**
 * TIER 1 — Core Feature Coverage.
 *
 * Opaque-box happy-path tests for the four core requirement groups defined in
 * ORIGINAL_REQUEST.md (R1-R4), written strictly against PROJECT.md interface
 * contracts. The engine is always the deterministic [MockRdpEngine] double,
 * Android SDK surfaces run under Robolectric, and all coroutine timing uses
 * kotlinx-coroutines-test virtual time (no Thread.sleep, no real network).
 *
 * Expected values are derived from protocol standards:
 *  - Pointer flags: MS-RDPBCGR 2.2.8.1.1.3.1.1
 *  - Clipboard format ids / UTF-16LE payloads: MS-RDPECLIP 3.1.5.2
 *  - Windows Scancode Set 1: standard PC/AT keyboard scancodes
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class Tier1FeatureCoverageTest {

    /** Records every RdpEventListener callback for opaque-box assertions. */
    private class RecordingRdpEventListener : RdpEventListener {
        var connectionSuccessCount = 0
        val failures = mutableListOf<Pair<Int, String>>()
        var disconnectCount = 0
        val resolutionChanges = mutableListOf<Pair<Int, Int>>()
        val clipboardReceived = mutableListOf<Pair<Int, String>>()
        var certFingerprint: String? = null
        var certHost: String? = null
        var acceptCertificate = true
        var graphicsUpdates = 0

        override fun onConnectionSuccess() {
            connectionSuccessCount++
        }

        override fun onConnectionFailure(errorCode: Int, message: String) {
            failures.add(errorCode to message)
        }

        override fun onDisconnected() {
            disconnectCount++
        }

        override fun onGraphicsUpdate(bitmap: Bitmap, x: Int, y: Int, width: Int, height: Int) {
            graphicsUpdates++
        }

        override fun onResolutionChanged(width: Int, height: Int) {
            resolutionChanges.add(width to height)
        }

        override fun onClipboardDataReceived(format: Int, data: ByteArray) {
            clipboardReceived.add(format to ClipboardHandler.decodeUnicodeText(data))
        }

        override fun onCertificateVerification(fingerprint: String, host: String): Boolean {
            certFingerprint = fingerprint
            certHost = host
            return acceptCertificate
        }
    }

    /** Gesture listener that counts every disambiguated gesture callback. */
    private class CountingGestureListener : GestureEventListener {
        var singleTapCount = 0
        var doubleTapCount = 0
        var panCount = 0
        var panEndCount = 0
        var pinchCount = 0
        var lastPinchScale = 1.0f
        var lastPanDx = 0f
        var lastPanDy = 0f

        override fun onSingleTap(screenX: Float, screenY: Float) {
            singleTapCount++
        }

        override fun onDoubleTap(screenX: Float, screenY: Float) {
            doubleTapCount++
        }

        override fun onPan(deltaX: Float, deltaY: Float) {
            panCount++
            lastPanDx = deltaX
            lastPanDy = deltaY
        }

        override fun onPanEnd() {
            panEndCount++
        }

        override fun onPinchZoom(focusX: Float, focusY: Float, scaleFactor: Float) {
            pinchCount++
            lastPinchScale = scaleFactor
        }
    }

    private lateinit var engine: MockRdpEngine
    private lateinit var listener: RecordingRdpEventListener

    @Before
    fun setUp() {
        engine = MockRdpEngine()
        listener = RecordingRdpEventListener()
        engine.setEventListener(listener)
    }

    // ------------------------------------------------------------------
    // R1 — Core Remote Desktop Foundation (engine, NLA/TLS, resolution, clipboard)
    // ------------------------------------------------------------------

    @Test
    fun r1_connectHappyPathReachesConnectedAndNotifiesListener() = runTest {
        val config = RdpConnectionConfig(
            serverAddress = "rdp.corp.example",
            port = 3389,
            username = "alice",
            enableNla = true,
            enableTls = true
        )

        assertTrue("connect must succeed on healthy engine", engine.connect(config))

        assertEquals(RdpConnectionState.Connected, engine.connectionState.value)
        assertTrue(engine.connectionState.value.isConnected)
        assertEquals(1, listener.connectionSuccessCount)
        assertEquals("no failure callback expected", 0, listener.failures.size)
        assertEquals(config, engine.activeConfig)
        assertEquals("rdp.corp.example", engine.activeConfig?.serverAddress)
        assertTrue("NLA requested must survive into active config", engine.activeConfig!!.enableNla)
        assertTrue("TLS requested must survive into active config", engine.activeConfig!!.enableTls)
    }

    @Test
    fun r1_disconnectReturnsToDisconnectedAndClearsActiveSession() = runTest {
        val config = RdpConnectionConfig(serverAddress = "10.0.0.8")
        assertTrue(engine.connect(config))
        assertEquals(RdpConnectionState.Connected, engine.connectionState.value)

        engine.disconnect()

        assertEquals(RdpConnectionState.Disconnected, engine.connectionState.value)
        assertTrue(engine.connectionState.value.isDisconnected)
        assertNull("native session reference must be cleared on disconnect", engine.activeConfig)
        assertEquals(1, listener.disconnectCount)
    }

    @Test
    fun r1_certificateVerificationAcceptanceAllowsHandshakeToComplete() = runTest {
        engine.simulateCertVerification = true
        engine.certVerificationFingerprint = "SHA256:AA11BB22CC33"
        engine.certVerificationHost = "rdp.corp.example"
        listener.acceptCertificate = true

        assertTrue(engine.connect(RdpConnectionConfig(serverAddress = "rdp.corp.example")))

        assertEquals("SHA256:AA11BB22CC33", listener.certFingerprint)
        assertEquals("rdp.corp.example", listener.certHost)
        assertEquals(RdpConnectionState.Connected, engine.connectionState.value)
        assertEquals(1, listener.connectionSuccessCount)
    }

    @Test
    fun r1_certificateRejectionAbortsConnectionWith403() = runTest {
        engine.simulateCertVerification = true
        listener.acceptCertificate = false

        assertFalse(
            "untrusted certificate must abort connect",
            engine.connect(RdpConnectionConfig(serverAddress = "evil.example"))
        )

        val state = engine.connectionState.value
        assertTrue("expected Failed state, was $state", state is RdpConnectionState.Failed)
        assertEquals(403, (state as RdpConnectionState.Failed).errorCode)
        assertEquals(1, listener.failures.size)
        assertEquals(403, listener.failures[0].first)
        assertTrue(listener.failures[0].second.contains("rejected"))
        assertEquals("handshake must not succeed after cert rejection", 0, listener.connectionSuccessCount)
    }

    @Test
    fun r1_connectionFailureSurfacesErrorCodeAndMessageToListener() = runTest {
        engine.shouldFailConnection = true
        engine.failureErrorCode = 50051
        engine.failureErrorMessage = "CredSSP encryption oracle remediation"

        assertFalse(engine.connect(RdpConnectionConfig(serverAddress = "locked.example")))

        val state = engine.connectionState.value
        assertTrue(state is RdpConnectionState.Failed)
        assertEquals(50051, (state as RdpConnectionState.Failed).errorCode)
        assertEquals(listOf(50051 to "CredSSP encryption oracle remediation"), listener.failures)
        assertEquals(0, listener.connectionSuccessCount)
    }

    @Test
    fun r1_protocolCallsAreRecordedAndPointerFlagsMatchMsRdpbcgr() = runTest {
        // MS-RDPBCGR 2.2.8.1.1.3.1.1 TS_POINTER_EVENT flag values
        assertEquals(0x8000, RdpPointerFlags.PTR_FLAGS_DOWN)
        assertEquals(0x1000, RdpPointerFlags.PTR_FLAGS_BUTTON1)
        assertEquals(0x2000, RdpPointerFlags.PTR_FLAGS_BUTTON2)
        assertEquals(0x4000, RdpPointerFlags.PTR_FLAGS_BUTTON3)
        assertEquals(0x0800, RdpPointerFlags.PTR_FLAGS_MOVE)
        assertEquals(0x0200, RdpPointerFlags.PTR_FLAGS_WHEEL)
        assertEquals(0x0100, RdpPointerFlags.PTR_FLAGS_WHEEL_NEGATIVE)
        assertEquals(0x0400, RdpPointerFlags.PTR_FLAGS_HWHEEL)
        assertEquals(120, RdpPointerFlags.WHEEL_STEP_DEFAULT)

        engine.sendPointerEvent(RdpPointerFlags.LEFT_BUTTON_DOWN, 10, 20)
        engine.sendKeyEvent(0x1D, true)
        engine.sendKeyEvent(0x1D, false)
        engine.sendUnicodeKeyEvent('é', true)
        engine.sendClipboardText("hello")

        assertEquals(listOf(MockRdpEngine.PointerEvent(0x9000, 10, 20)), engine.recordedPointerEvents)
        assertEquals(
            listOf(
                MockRdpEngine.KeyEvent(0x1D, true),
                MockRdpEngine.KeyEvent(0x1D, false)
            ),
            engine.recordedKeyEvents
        )
        assertEquals(listOf(MockRdpEngine.UnicodeEvent('é', true)), engine.recordedUnicodeEvents)
        assertEquals(listOf("hello"), engine.recordedClipboardTexts)

        engine.clearRecordedEvents()
        assertTrue(engine.recordedPointerEvents.isEmpty())
        assertTrue(engine.recordedKeyEvents.isEmpty())
        assertTrue(engine.recordedUnicodeEvents.isEmpty())
        assertTrue(engine.recordedClipboardTexts.isEmpty())
        assertTrue(engine.recordedResolutions.isEmpty())
    }

    @Test
    fun r1_dynamicResolutionUpdateEchoesToEventListener() = runTest {
        engine.updateResolution(1024, 768, 338, 190, orientation = 0)

        assertEquals(
            listOf(MockRdpEngine.ResolutionEvent(1024, 768, 338, 190, 0)),
            engine.recordedResolutions
        )
        assertEquals(listOf(1024 to 768), listener.resolutionChanges)

        engine.updateResolution(768, 1024, 190, 338, orientation = 1)
        assertEquals(2, engine.recordedResolutions.size)
        assertEquals(listOf(1024 to 768, 768 to 1024), listener.resolutionChanges)
    }

    @Test
    fun r1_clipboardTextRoundTripsThroughUnicodeFormat() = runTest {
        // MS-RDPECLIP: CF_UNICODETEXT format id is 13; payloads are UTF-16LE
        // with a trailing UTF-16 NUL terminator.
        assertEquals(13, ClipboardHandler.CF_UNICODETEXT)
        assertEquals(1, ClipboardHandler.CF_TEXT)

        engine.sendClipboardText("cmd /c dir")
        assertEquals(listOf("cmd /c dir"), engine.recordedClipboardTexts)

        val text = "remote clipboard ✓ texto"
        val encoded = ClipboardHandler.encodeUnicodeText(text)
        assertEquals("UTF-16LE payload plus 2-byte NUL", text.length * 2 + 2, encoded.size)
        assertEquals(0, encoded[encoded.size - 2].toInt())
        assertEquals(0, encoded[encoded.size - 1].toInt())
        assertEquals(text, ClipboardHandler.decodeUnicodeText(encoded))

        engine.triggerClipboardReceived(ClipboardHandler.CF_UNICODETEXT, encoded)
        assertEquals(1, listener.clipboardReceived.size)
        assertEquals(ClipboardHandler.CF_UNICODETEXT, listener.clipboardReceived[0].first)
        assertEquals(text, listener.clipboardReceived[0].second)
    }

    @Test
    fun r1_sessionMetricsFlowReflectsEngineUpdates() = runTest {
        assertEquals(
            RdpSessionMetrics(rttMs = 15, fps = 60f, bandwidthKbps = 2500),
            engine.sessionMetrics.value
        )

        engine.setMetrics(
            RdpSessionMetrics(
                rttMs = 42, fps = 30f, bandwidthKbps = 5000,
                frameCount = 900, droppedFrames = 3, jitterMs = 7
            )
        )

        assertEquals(42L, engine.sessionMetrics.value.rttMs)
        assertEquals(30f, engine.sessionMetrics.value.fps, 0.001f)
        assertEquals(5000L, engine.sessionMetrics.value.bandwidthKbps)
        assertEquals(900L, engine.sessionMetrics.value.frameCount)
        assertEquals(3L, engine.sessionMetrics.value.droppedFrames)
        assertEquals(7L, engine.sessionMetrics.value.jitterMs)
    }

    // ------------------------------------------------------------------
    // R2 — Mobile-First Floating Mouse & Touch System
    // ------------------------------------------------------------------

    /** Identity transform: screen pixels map 1:1 onto remote desktop pixels. */
    private fun identityTransformer(): CoordinateTransformer {
        val transformer = CoordinateTransformer(
            remoteWidth = 1920,
            remoteHeight = 1080,
            viewWidth = 1080,
            viewHeight = 2400
        )
        transformer.setTransform(newScale = 1f, transX = 0f, transY = 0f, clamp = false)
        return transformer
    }

    @Test
    fun r2_leftClickEmitsButtonDownThenButtonUpPair() {
        val controller = DefaultMouseController(engine, identityTransformer())

        controller.handleLeftClick(300f, 400f)

        assertEquals(2, engine.recordedPointerEvents.size)
        val down = engine.recordedPointerEvents[0]
        val up = engine.recordedPointerEvents[1]
        assertEquals(RdpPointerFlags.LEFT_BUTTON_DOWN, down.flags)
        assertEquals(300, down.x)
        assertEquals(400, down.y)
        assertTrue(RdpPointerFlags.isButtonDown(down.flags))
        assertTrue(RdpPointerFlags.isButton1(down.flags))
        assertFalse(RdpPointerFlags.isMove(down.flags))
        assertEquals(RdpPointerFlags.LEFT_BUTTON_UP, up.flags)
        assertFalse("release must clear PTR_FLAGS_DOWN", RdpPointerFlags.isButtonDown(up.flags))
        assertEquals(300, up.x)
        assertEquals(400, up.y)
    }

    @Test
    fun r2_rightClickEmitsButton2Pair() {
        val controller = DefaultMouseController(engine, identityTransformer())

        controller.handleRightClick(640f, 480f)

        assertEquals(2, engine.recordedPointerEvents.size)
        assertEquals(RdpPointerFlags.RIGHT_BUTTON_DOWN, engine.recordedPointerEvents[0].flags)
        assertTrue(RdpPointerFlags.isButton2(engine.recordedPointerEvents[0].flags))
        assertTrue(RdpPointerFlags.isButtonDown(engine.recordedPointerEvents[0].flags))
        assertEquals(RdpPointerFlags.RIGHT_BUTTON_UP, engine.recordedPointerEvents[1].flags)
        assertFalse(RdpPointerFlags.isButtonDown(engine.recordedPointerEvents[1].flags))
        assertFalse(
            "right click must not touch button1",
            RdpPointerFlags.isButton1(engine.recordedPointerEvents[1].flags)
        )
    }

    @Test
    fun r2_doubleClickEmitsFourAlternatingEvents() {
        val controller = DefaultMouseController(engine, identityTransformer())

        controller.handleDoubleClick(111f, 222f)

        val flags = engine.recordedPointerEvents.map { it.flags }
        assertEquals(
            "double click = down/up/down/up on button1",
            listOf(
                RdpPointerFlags.LEFT_BUTTON_DOWN,
                RdpPointerFlags.LEFT_BUTTON_UP,
                RdpPointerFlags.LEFT_BUTTON_DOWN,
                RdpPointerFlags.LEFT_BUTTON_UP
            ),
            flags
        )
        assertTrue(
            "all four events at identical coordinate",
            engine.recordedPointerEvents.all { it.x == 111 && it.y == 222 }
        )
    }

    @Test
    fun r2_clickDragCarriesButton1BetweenDownAndUp() {
        val controller = DefaultMouseController(engine, identityTransformer())

        controller.handleDragStart(100f, 100f)
        assertTrue(controller.isDragging)
        controller.handleDragMove(150f, 120f)
        controller.handleDragMove(200f, 140f)
        controller.handleDragEnd(200f, 140f)
        assertFalse(controller.isDragging)
        // Plain move after release carries no button bits
        controller.handleDragMove(210f, 150f)

        val events = engine.recordedPointerEvents
        assertEquals(5, events.size)
        assertEquals(RdpPointerFlags.LEFT_BUTTON_DOWN, events[0].flags)
        assertEquals(100, events[0].x)

        val dragMoveFlags =
            RdpPointerFlags.MOVE or RdpPointerFlags.PTR_FLAGS_BUTTON1 or RdpPointerFlags.PTR_FLAGS_DOWN
        assertEquals(0x9800, dragMoveFlags)
        assertEquals(dragMoveFlags, events[1].flags)
        assertTrue(RdpPointerFlags.isMove(events[1].flags))
        assertTrue(RdpPointerFlags.isButton1(events[1].flags))
        assertTrue(RdpPointerFlags.isButtonDown(events[1].flags))
        assertEquals(150, events[1].x)
        assertEquals(120, events[1].y)
        assertEquals(dragMoveFlags, events[2].flags)
        assertEquals(200, events[2].x)

        assertEquals(RdpPointerFlags.LEFT_BUTTON_UP, events[3].flags)
        assertEquals(200, events[3].x)
        assertEquals(140, events[3].y)

        assertEquals(RdpPointerFlags.MOVE, events[4].flags)
        assertFalse("released drag must not keep button1", RdpPointerFlags.isButton1(events[4].flags))
    }

    @Test
    fun r2_wheelAndHorizontalScrollEncodeSpecRotationAmounts() {
        val controller = DefaultMouseController(engine, identityTransformer())

        controller.handleScroll(50f, 60f, +1f)
        controller.handleScroll(50f, 60f, -1f)
        controller.handleScroll(50f, 60f, 0f) // zero delta must be dropped
        controller.handleHorizontalScroll(50f, 60f, +3f)
        controller.handleHorizontalScroll(50f, 60f, -3f)

        val flags = engine.recordedPointerEvents.map { it.flags }
        assertEquals(
            listOf(
                RdpPointerFlags.SCROLL_UP,    // WHEEL | +120
                RdpPointerFlags.SCROLL_DOWN,  // WHEEL | NEGATIVE | 120
                RdpPointerFlags.SCROLL_RIGHT, // HWHEEL | +120
                RdpPointerFlags.SCROLL_LEFT   // HWHEEL | NEGATIVE | 120
            ),
            flags
        )
        assertEquals(0x0278, flags[0])
        assertEquals(0x0378, flags[1])
        assertTrue(RdpPointerFlags.isWheel(flags[0]))
        assertFalse(RdpPointerFlags.isWheelNegative(flags[0]))
        assertTrue(RdpPointerFlags.isWheelNegative(flags[1]))
        assertTrue(RdpPointerFlags.isHWheel(flags[2]))
        assertEquals("zero-delta scroll must emit nothing", 4, engine.recordedPointerEvents.size)
    }

    @Test
    fun r2_touchpadModeDrivesRelativeVirtualCursor() {
        val controller = DefaultMouseController(engine, identityTransformer())
        assertFalse(controller.isTouchpadMode)

        controller.setTouchpadMode(true)
        assertTrue(controller.isTouchpadMode)
        assertTrue("touchpad mode must expose the virtual cursor", controller.isCursorVisible)

        // Cursor starts at the remote desktop centre: 1920/2, 1080/2
        assertEquals(960f, controller.virtualCursorPosition.x, 0.01f)
        assertEquals(540f, controller.virtualCursorPosition.y, 0.01f)

        controller.handleTouchpadMove(25.6f, 10.2f)
        val move = engine.recordedPointerEvents[0]
        assertEquals(RdpPointerFlags.MOVE, move.flags)
        assertEquals(985, move.x)
        assertEquals(550, move.y)

        // In touchpad mode screen coordinates are ignored: clicks act on the cursor
        controller.handleLeftClick(0f, 0f)
        val down = engine.recordedPointerEvents[1]
        assertEquals(985, down.x)
        assertEquals(550, down.y)

        // Leaving touchpad mode restores absolute mapping
        controller.setTouchpadMode(false)
        assertFalse(controller.isTouchpadMode)
        controller.handleLeftClick(10f, 20f)
        val absoluteDown = engine.recordedPointerEvents[3]
        assertEquals(10, absoluteDown.x)
        assertEquals(20, absoluteDown.y)
    }

    @Test
    fun r2_panGesturePansWithoutEmittingClicks() {
        val gestureListener = CountingGestureListener()
        val gestures = GestureDisambiguationEngine(handler = null, listener = gestureListener)
        val now = SystemClock.uptimeMillis()

        gestures.onTouchEvent(single(MotionEvent.ACTION_DOWN, 100f, 100f, now, now))
        gestures.onTouchEvent(single(MotionEvent.ACTION_MOVE, 160f, 140f, now, now + 40))
        assertTrue("movement beyond touch slop starts pan", gestures.isPanning)
        gestures.onTouchEvent(single(MotionEvent.ACTION_UP, 160f, 140f, now, now + 90))

        assertEquals(1, gestureListener.panCount)
        assertEquals(60f, gestureListener.lastPanDx, 0.01f)
        assertEquals(40f, gestureListener.lastPanDy, 0.01f)
        assertEquals(1, gestureListener.panEndCount)
        assertEquals("pan must never be reported as a tap", 0, gestureListener.singleTapCount)
        assertEquals(0, gestureListener.doubleTapCount)
    }

    @Test
    fun r2_pinchToZoomReportsSpanRatioAsScaleFactor() {
        val gestureListener = CountingGestureListener()
        val gestures = GestureDisambiguationEngine(handler = null, listener = gestureListener)
        val now = SystemClock.uptimeMillis()

        gestures.onTouchEvent(single(MotionEvent.ACTION_DOWN, 200f, 200f, now, now))
        gestures.onTouchEvent(dual(MotionEvent.ACTION_POINTER_DOWN, 200f, 200f, 300f, 300f, now, now + 10))
        gestures.onTouchEvent(dual(MotionEvent.ACTION_MOVE, 150f, 150f, 350f, 350f, now, now + 30))

        assertTrue(gestures.isMultiTouchLatched)
        assertTrue("pinch must be detected", gestureListener.pinchCount > 0)
        val spanBefore = Math.hypot((300 - 200).toDouble(), (300 - 200).toDouble())
        val spanAfter = Math.hypot((350 - 150).toDouble(), (350 - 150).toDouble())
        val expected = (spanAfter / spanBefore).toFloat()
        assertEquals("scale factor must equal finger-span ratio", expected, gestureListener.lastPinchScale, 0.001f)
        assertEquals(2.0f, gestureListener.lastPinchScale, 0.001f)
    }

    @Test
    fun r2_affineTransformRoundTripsScreenAndDesktopCoordinates() {
        val transformer = CoordinateTransformer(
            remoteWidth = 1920, remoteHeight = 1080, viewWidth = 1080, viewHeight = 2400
        )
        transformer.setTransform(newScale = 1.75f, transX = -120.5f, transY = 33.25f, clamp = false)

        val screen = transformer.desktopToScreen(640f, 360f)
        assertEquals(640f * 1.75f - 120.5f, screen.x, 0.001f)
        assertEquals(360f * 1.75f + 33.25f, screen.y, 0.001f)

        val (dx, dy) = transformer.screenToDesktopInt(screen.x, screen.y)
        assertTrue("round trip within 1px on x", Math.abs(dx - 640) <= 1)
        assertTrue("round trip within 1px on y", Math.abs(dy - 360) <= 1)

        // Identity mapping floors fractional touches exactly
        val identity = identityTransformer()
        assertEquals(640 to 360, identity.screenToDesktopInt(640.9f, 360.9f))
    }

    // ------------------------------------------------------------------
    // R3 — Mobile Productivity & Session Management
    // ------------------------------------------------------------------

    @Test
    fun r3_profileCrudLifecycleThroughAtomicRepository() = runTest {
        val dir = Files.createTempDirectory("e2e_t1_profiles").toFile()
        val repo = AtomicFileProfileRepository(File(dir, "profiles.json"), StandardTestDispatcher(testScheduler))

        assertEquals(0, repo.getAllProfiles().first().size)

        val dev = RdpProfile(label = "Dev Box", hostname = "10.0.0.5", port = 3390)
        val prod = RdpProfile(label = "Prod", hostname = "10.0.0.6")
        repo.saveProfile(dev)
        repo.saveProfile(prod)

        val all = repo.getAllProfiles().first()
        assertEquals(2, all.size)
        val devId = all.first { it.label == "Dev Box" }.id

        // Update
        repo.saveProfile(dev.copy(label = "Dev Box Renamed"))
        val updated = repo.getProfile(devId)
        assertNotNull(updated)
        assertEquals("Dev Box Renamed", updated?.label)
        assertEquals(2, repo.getAllProfiles().first().size)

        // Duplicate
        val prodId = all.first { it.label == "Prod" }.id
        val duplicate = repo.duplicateProfile(prodId)
        assertNotNull(duplicate)
        assertEquals("Prod (Copy)", duplicate?.label)
        assertTrue("duplicate must get a fresh id", duplicate?.id != prodId)

        // Delete (second attempt reports false)
        assertTrue(repo.deleteProfile(devId))
        assertFalse(repo.deleteProfile(devId))
        assertNull(repo.getProfile(devId))
        assertEquals(2, repo.getAllProfiles().first().size)

        repo.clearAll()
        assertEquals(0, repo.getAllProfiles().first().size)
    }

    @Test
    fun r3_profilesPersistAcrossRepositoryInstances() = runTest {
        val dir = Files.createTempDirectory("e2e_t1_persist").toFile()
        val file = File(dir, "profiles.json")
        val dispatcher = StandardTestDispatcher(testScheduler)

        val repo1 = AtomicFileProfileRepository(file, dispatcher)
        val saved = RdpProfile(label = "Persisted", hostname = "persist.example", username = "bob")
        repo1.saveProfile(saved)

        // Fresh repository instance reads the atomic file from disk
        val repo2 = AtomicFileProfileRepository(file, dispatcher)
        val loaded = repo2.getAllProfiles().first()
        assertEquals(1, loaded.size)
        assertEquals(saved.id, loaded[0].id)
        assertEquals("Persisted", loaded[0].label)
        assertEquals("persist.example", loaded[0].hostname)
        assertEquals("bob", loaded[0].username)
    }

    @Test
    fun r3_keystoreCredentialRoundTripDeleteAndWipe() {
        val context = RuntimeEnvironment.getApplication()
        val store: CredentialStore = KeystoreCredentialStore(context, prefFileName = "e2e_t1_vault")
        store.clearAll()

        val secret = "S3cureEnterpr1se!Pass".toCharArray()
        store.saveSecret("profile-1", secret)
        assertArrayEquals(secret, store.getSecret("profile-1"))
        assertNull("unknown id must yield null, not an exception", store.getSecret("missing-profile"))

        // Result-based convenience API from the CredentialStore contract
        assertTrue(store.saveCredential("profile-2", "second".toCharArray()).isSuccess)
        val read = store.getCredential("profile-2")
        assertTrue(read.isSuccess)
        assertArrayEquals("second".toCharArray(), read.getOrNull())

        store.deleteSecret("profile-1")
        assertNull(store.getSecret("profile-1"))
        assertNotNull(store.getSecret("profile-2"))

        store.clearAll()
        assertNull(store.getSecret("profile-2"))

        // CharArray zeroing contract (TEST_INFRA 5.4)
        val wipe = "Sensitive123".toCharArray()
        KeystoreCredentialStore.wipeSecret(wipe)
        assertTrue("password buffer must be zero-filled", wipe.all { it.code == 0 })
    }

    @Test
    fun r3_modifierBarThreeStateLatchCycleEmitsMatchingKeyEvents() {
        val transitions = mutableListOf<Pair<ModifierKey, LatchState>>()
        val modifier = ModifierStateMachine(engine) { key, state -> transitions.add(key to state) }

        assertEquals(LatchState.INACTIVE, modifier.getModifierState(ModifierKey.CTRL))
        assertFalse(modifier.isKeyActive(ModifierKey.CTRL))

        modifier.onModifierKeyTapped(ModifierKey.CTRL)
        assertEquals(LatchState.LATCHED, modifier.getModifierState(ModifierKey.CTRL))
        assertTrue(modifier.isLatched(ModifierKey.CTRL))

        modifier.onModifierKeyTapped(ModifierKey.CTRL)
        assertEquals(LatchState.LOCKED, modifier.getModifierState(ModifierKey.CTRL))
        assertTrue(modifier.isLocked(ModifierKey.CTRL))

        modifier.onModifierKeyTapped(ModifierKey.CTRL)
        assertEquals(LatchState.INACTIVE, modifier.getModifierState(ModifierKey.CTRL))
        assertFalse(modifier.isKeyActive(ModifierKey.CTRL))

        // Ctrl (Set-1 scancode 0x1D) goes down on first tap and up only on unlock
        assertEquals(
            listOf(
                MockRdpEngine.KeyEvent(0x1D, true),
                MockRdpEngine.KeyEvent(0x1D, false)
            ),
            engine.recordedKeyEvents
        )
        assertEquals(
            listOf(
                ModifierKey.CTRL to LatchState.LATCHED,
                ModifierKey.CTRL to LatchState.LOCKED,
                ModifierKey.CTRL to LatchState.INACTIVE
            ),
            transitions
        )
        assertEquals(transitions.last().second, modifier.statesFlow.value[ModifierKey.CTRL])

        // Non-latchable keys are plain taps and never enter the latch map
        modifier.onModifierKeyTapped(ModifierKey.F1)
        assertEquals(LatchState.INACTIVE, modifier.getModifierState(ModifierKey.F1))
        assertEquals(4, engine.recordedKeyEvents.size)
        assertEquals(MockRdpEngine.KeyEvent(0x3B, true), engine.recordedKeyEvents[2])
        assertEquals(MockRdpEngine.KeyEvent(0x3B, false), engine.recordedKeyEvents[3])
    }

    @Test
    fun r3_latchedModifierAppliesToNextKeystrokeThenAutoReleases() {
        val modifier = ModifierStateMachine(engine)

        modifier.onModifierKeyTapped(ModifierKey.CTRL)
        modifier.onNonModifierKeyPressed('c')

        assertEquals(
            "Ctrl down -> c down -> c up -> Ctrl up",
            listOf(
                MockRdpEngine.KeyEvent(0x1D, true), // LEFT_CTRL
                MockRdpEngine.KeyEvent(0x2E, true), // C
                MockRdpEngine.KeyEvent(0x2E, false),
                MockRdpEngine.KeyEvent(0x1D, false)
            ),
            engine.recordedKeyEvents
        )
        assertEquals(
            "latched modifier auto-releases after the keystroke",
            LatchState.INACTIVE,
            modifier.getModifierState(ModifierKey.CTRL)
        )

        // Characters outside Set-1 coverage fall back to Unicode input
        modifier.onModifierKeyTapped(ModifierKey.SHIFT)
        modifier.onNonModifierKeyPressed('é')
        assertEquals(
            listOf(
                MockRdpEngine.UnicodeEvent('é', true),
                MockRdpEngine.UnicodeEvent('é', false)
            ),
            engine.recordedUnicodeEvents
        )
        assertEquals(LatchState.INACTIVE, modifier.getModifierState(ModifierKey.SHIFT))
    }

    @Test
    fun r3_scancodeTranslatorImplementsWindowsScancodeSet1() {
        val ctrl = ScancodeTranslator.getScancodeForModifierKey(ModifierKey.CTRL)
        assertEquals(0x1D, ctrl.scancode)
        assertFalse("Set-1 Ctrl is not extended", ctrl.isExtended)
        assertEquals(0x0000, ctrl.flags)

        val win = ScancodeTranslator.getScancodeForModifierKey(ModifierKey.WIN)
        assertEquals(0x5B, win.scancode)
        assertTrue("Win key uses the E0 extended prefix", win.isExtended)
        assertEquals(0x0100, win.flags)

        assertEquals(0x57, ScancodeTranslator.getScancodeForModifierKey(ModifierKey.F11).scancode)
        assertEquals(0x58, ScancodeTranslator.getScancodeForModifierKey(ModifierKey.F12).scancode)
        assertEquals(0x01, ScancodeTranslator.getScancodeForModifierKey(ModifierKey.ESC).scancode)
        val left = ScancodeTranslator.getScancodeForModifierKey(ModifierKey.ARROW_LEFT)
        assertEquals(0x4B, left.scancode)
        assertTrue(left.isExtended)

        // Character mapping is case-insensitive
        assertEquals(ScancodeTranslator.fromChar('a'), ScancodeTranslator.fromChar('A'))
        assertEquals(0x1E, ScancodeTranslator.fromChar('a')?.scancode)
        assertEquals(0x0B, ScancodeTranslator.fromChar('0')?.scancode)
        assertEquals(0x39, ScancodeTranslator.fromChar(' ')?.scancode)
        assertNull(ScancodeTranslator.fromChar('§'))

        // Android key codes
        assertEquals(0x1E, ScancodeTranslator.fromAndroidKeyCode(KeyEvent.KEYCODE_A)?.scancode)
        assertEquals(0x0E, ScancodeTranslator.fromAndroidKeyCode(KeyEvent.KEYCODE_DEL)?.scancode)
        val ctrlRight = ScancodeTranslator.fromAndroidKeyCode(KeyEvent.KEYCODE_CTRL_RIGHT)!!
        assertEquals(0x1D, ctrlRight.scancode)
        assertTrue("right Ctrl is extended", ctrlRight.isExtended)
        assertNull(ScancodeTranslator.fromAndroidKeyCode(KeyEvent.KEYCODE_UNKNOWN))
    }

    @Test
    fun r3_quickActionToolbarExpandsCollapsesAndAutoTimers() = runTest {
        val actions = mutableListOf<ToolbarAction>()
        val toolbar = QuickActionToolbarFSM(
            inactivityTimeoutMs = 4000L,
            coroutineScope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
            onActionCallback = { actions.add(it) }
        )

        assertEquals(ToolbarState.COLLAPSED, toolbar.state.value)
        assertTrue(toolbar.isCollapsed)

        toolbar.expand()
        assertTrue(toolbar.isExpanded)
        toolbar.triggerAction(ToolbarAction.TOGGLE_TELEMETRY_HUD)
        toolbar.triggerAction(ToolbarAction.DISCONNECT)
        assertEquals(listOf(ToolbarAction.TOGGLE_TELEMETRY_HUD, ToolbarAction.DISCONNECT), actions)

        // 4s inactivity auto-collapse, boundary-checked on virtual time
        advanceTimeBy(3999)
        runCurrent()
        assertTrue("still expanded at 3999ms", toolbar.isExpanded)
        advanceTimeBy(1)
        runCurrent()
        assertTrue("auto-collapse fires at exactly 4000ms", toolbar.isCollapsed)

        // Touch resets the inactivity window
        toolbar.expand()
        advanceTimeBy(3000)
        runCurrent()
        toolbar.onTouch()
        advanceTimeBy(3000)
        runCurrent()
        assertTrue("touch must reset the 4s window", toolbar.isExpanded)
        advanceTimeBy(1001)
        runCurrent()
        assertTrue(toolbar.isCollapsed)

        toolbar.expand()
        toolbar.toggle()
        assertTrue(toolbar.isCollapsed)
        toolbar.toggle()
        assertTrue(toolbar.isExpanded)
        toolbar.collapse()
        assertEquals(ToolbarState.COLLAPSED, toolbar.state.value)
    }

    // ------------------------------------------------------------------
    // R4 — Adaptive Low-Latency Performance & Telemetry
    // ------------------------------------------------------------------

    @Test
    fun r4_sessionDropSchedulesExponentialBackoffThenReconnects() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val config = RdpConnectionConfig(serverAddress = "10.1.1.1")
        val manager = com.freerdp.feature.telemetry.reconnect.AutoReconnectManagerImpl(
            engine = engine,
            configProvider = { config },
            coroutineScope = this,
            ioDispatcher = dispatcher,
            maxAttempts = 5,
            baseDelayMs = 1000L,
            maxDelayMs = 30000L,
            // Deterministic full-jitter: always take the upper bound
            randomProvider = { _, max -> max }
        )
        runCurrent()

        assertTrue(engine.connect(config))
        runCurrent()
        assertEquals(
            com.freerdp.feature.telemetry.reconnect.ReconnectState.Connected,
            manager.reconnectState.value
        )

        manager.onSessionDropped("link loss")
        runCurrent()

        val reconnecting = manager.reconnectState.value
        assertTrue(
            "expected Reconnecting, was $reconnecting",
            reconnecting is com.freerdp.feature.telemetry.reconnect.ReconnectState.Reconnecting
        )
        assertEquals(
            1,
            (reconnecting as com.freerdp.feature.telemetry.reconnect.ReconnectState.Reconnecting).attempt
        )
        assertEquals(
            "attempt 1 jitter bound = random(0, min(30000, 1000*2^0)) = 1000",
            1000L,
            reconnecting.nextDelayMs
        )

        // Still backing off one millisecond before the deadline
        advanceTimeBy(999)
        runCurrent()
        assertTrue(
            manager.reconnectState.value is com.freerdp.feature.telemetry.reconnect.ReconnectState.Reconnecting
        )
        assertEquals(RdpConnectionState.Disconnected, engine.connectionState.value)

        advanceTimeBy(2)
        runCurrent()
        advanceUntilIdle()

        assertEquals(
            com.freerdp.feature.telemetry.reconnect.ReconnectState.Connected,
            manager.reconnectState.value
        )
        assertEquals(RdpConnectionState.Connected, engine.connectionState.value)
        assertEquals(config, engine.activeConfig)
    }

    @Test
    fun r4_backoffJitterStaysWithinExponentialBounds() {
        val maxProvider: (Long, Long) -> Long = { _, max -> max }
        assertEquals(
            1000L,
            com.freerdp.feature.telemetry.reconnect.AutoReconnectManagerImpl
                .calculateBackoffWithJitter(0, randomProvider = maxProvider)
        )
        assertEquals(
            2000L,
            com.freerdp.feature.telemetry.reconnect.AutoReconnectManagerImpl
                .calculateBackoffWithJitter(1, randomProvider = maxProvider)
        )
        assertEquals(
            4000L,
            com.freerdp.feature.telemetry.reconnect.AutoReconnectManagerImpl
                .calculateBackoffWithJitter(2, randomProvider = maxProvider)
        )
        assertEquals(
            30000L,
            com.freerdp.feature.telemetry.reconnect.AutoReconnectManagerImpl
                .calculateBackoffWithJitter(5, randomProvider = maxProvider)
        )
        // Hardened contract: negative attempts are a programming error and
        // are rejected eagerly rather than silently clamped
        assertThrows(IllegalArgumentException::class.java) {
            com.freerdp.feature.telemetry.reconnect.AutoReconnectManagerImpl
                .calculateBackoffWithJitter(-3, randomProvider = maxProvider)
        }
        // attempt > 30 clamps the exponent instead of overflowing the shift
        assertEquals(
            30000L,
            com.freerdp.feature.telemetry.reconnect.AutoReconnectManagerImpl
                .calculateBackoffWithJitter(99, randomProvider = maxProvider)
        )

        // Full jitter invariant: result always within [0, min(maxDelay, base * 2^attempt)]
        for (attempt in 0..10) {
            val cap = minOf(30000L, 1000L * (1L shl attempt))
            repeat(50) {
                val delay = com.freerdp.feature.telemetry.reconnect.AutoReconnectManagerImpl
                    .calculateBackoffWithJitter(attempt)
                assertTrue("delay $delay >= 0", delay >= 0L)
                assertTrue("delay $delay <= cap $cap", delay <= cap)
            }
        }
    }

    @Test
    fun r4_framePacerSingleSlotDropsStaleFramesAtomically() {
        val dropped = mutableListOf<Bitmap>()
        val pacer = FramePacer(onFrameDroppedCallback = { dropped.add(it) })
        val frames = (1..10).map { Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888) }

        // Injected 60fps-spaced decode timestamps: no wall clock anywhere
        frames.forEachIndexed { index, frame ->
            val wasDropped = pacer.onFrameDecoded(
                frame,
                timestampNanos = 1_000_000_000L + index * 16_666_667L
            )
            assertEquals("first frame has no predecessor to drop", index > 0, wasDropped)
        }

        assertEquals(10L, pacer.totalDecoded)
        assertEquals(9L, pacer.totalDropped)
        assertEquals(9, dropped.size)
        assertEquals(0.9f, pacer.dropRatio, 0.0001f)
        assertTrue(pacer.hasPendingFrame)
        assertSame("single slot must hold the freshest frame", frames[9], pacer.peekPendingFrame())

        // Acquire 1ms after the last decode: well within the 250ms freshness bound
        val blitTime = 1_000_000_000L + 9 * 16_666_667L + 1_000_000L
        assertSame(frames[9], pacer.acquireFrameForRendering(nowNanos = blitTime))
        assertNull("slot is consumed exactly once", pacer.acquireFrameForRendering(nowNanos = blitTime))
        assertEquals(1L, pacer.totalRendered)
        assertFalse(pacer.hasPendingFrame)

        pacer.reset()
        assertEquals(0L, pacer.totalDecoded)
        assertEquals(0L, pacer.totalDropped)
    }

    @Test
    fun r4_telemetryCollectorComputesExactRttPercentiles() {
        val telemetry = TelemetryCollector()
        telemetry.recordRtt(100)
        telemetry.recordRtt(200)
        telemetry.recordRtt(300)

        val snap = telemetry.getSnapshot()
        assertEquals(300L, snap.rttMs)
        assertEquals(200.0, snap.avgRttMs, 1e-6)
        assertEquals(200.0, snap.p50RttMs, 1e-6)
        assertEquals(290.0, snap.p95RttMs, 1e-6) // rank = 0.95*(3-1) = 1.9 -> 200 + 0.9*100
        assertEquals(298.0, snap.p99RttMs, 1e-6) // rank = 0.99*(3-1) = 1.98 -> 200 + 0.98*100
        assertTrue(
            "p50 <= p95 <= p99 ordering",
            snap.p50RttMs <= snap.p95RttMs && snap.p95RttMs <= snap.p99RttMs
        )
        assertTrue(snap.p99RttMs <= 300.0)

        // Metrics bridge mirrors the same ring while it still holds 3 samples
        val metrics: RdpSessionMetrics = telemetry.toRdpSessionMetrics()
        assertEquals(300L, metrics.rttMs)
        assertEquals(0L, metrics.frameCount)

        // Negative ping readings are clamped to 0ms and recorded as such:
        // latest reading becomes 0, average becomes (100+200+300+0)/4 = 150
        telemetry.recordRtt(-7)
        assertEquals("clamped sample becomes the latest reading", 0L, telemetry.getSnapshot().rttMs)
        assertEquals(150.0, telemetry.getSnapshot().avgRttMs, 1e-6)
    }

    @Test
    fun r4_telemetryCollectorMeasuresFpsAndFrameJitter() {
        val telemetry = TelemetryCollector()
        val base = 1_000_000_000L

        // Perfectly paced 60 fps burst over ~1s
        repeat(60) { i -> telemetry.recordFrameDelivered(base + i * 16_666_667L) }
        assertEquals(60f, telemetry.calculateInstantaneousFps(), 0.001f)
        assertEquals("uniform pacing implies zero jitter", 0.0, telemetry.getSnapshot().jitterMs, 1e-9)
        assertEquals(60L, telemetry.getSnapshot().totalFramesRendered)

        // Irregular pacing: intervals of 10ms and 20ms -> population stddev = 5ms
        val jittery = TelemetryCollector()
        jittery.recordFrameDelivered(base)
        jittery.recordFrameDelivered(base + 10_000_000L)
        jittery.recordFrameDelivered(base + 30_000_000L)
        assertEquals(5.0, jittery.getSnapshot().jitterMs, 1e-6)
    }

    @Test
    fun r4_diagnosticHudClassifiesConnectionQualityBySpecThresholds() {
        val excellent = DiagnosticHudModel.format(
            fps = 60f, rttMs = 12, p95RttMs = 14.0, p99RttMs = 15.0,
            jitterMs = 1.0, bandwidthKbps = 8000, droppedFrames = 0, connectionState = "Connected"
        )
        assertEquals(DiagnosticHudModel.ConnectionQuality.EXCELLENT, excellent.quality)
        assertTrue(excellent.hudText.contains("FPS"))
        assertTrue(excellent.hudText.contains("Connected"))

        val good = DiagnosticHudModel.format(
            fps = 40f, rttMs = 50, p95RttMs = 55.0, p99RttMs = 58.0,
            jitterMs = 3.0, bandwidthKbps = 3000, droppedFrames = 1, connectionState = "Connected"
        )
        assertEquals(DiagnosticHudModel.ConnectionQuality.GOOD, good.quality)

        val fair = DiagnosticHudModel.format(
            fps = 20f, rttMs = 100, p95RttMs = 110.0, p99RttMs = 115.0,
            jitterMs = 8.0, bandwidthKbps = 1200, droppedFrames = 5, connectionState = "Connected"
        )
        assertEquals(DiagnosticHudModel.ConnectionQuality.FAIR, fair.quality)

        val poor = DiagnosticHudModel.format(
            fps = 10f, rttMs = 250, p95RttMs = 280.0, p99RttMs = 290.0,
            jitterMs = 40.0, bandwidthKbps = 300, droppedFrames = 60, connectionState = "Reconnecting"
        )
        assertEquals(DiagnosticHudModel.ConnectionQuality.POOR, poor.quality)
    }

    @Test
    fun r4_performancePresetsGenerateDistinctFreerdpArguments() {
        val ultra = TelemetryPreset.ULTRA_LOW_LATENCY
        val balanced = TelemetryPreset.BALANCED_MOBILE
        val saver = TelemetryPreset.DATA_SAVER

        assertEquals(60, ultra.targetFps)
        assertEquals(32, ultra.colorDepth)
        assertEquals("NONE", ultra.compressionCodec)
        val ultraArgs = ultra.toFreeRdpCliArgs()
        assertTrue(ultraArgs.contains("/bpp:32"))
        assertTrue(ultraArgs.contains("-sound"))
        assertTrue(ultraArgs.contains("-wallpaper"))
        assertFalse("latency preset must not enable RFX", ultraArgs.contains("+rfx"))

        val balancedArgs = balanced.toFreeRdpCliArgs()
        assertTrue(balancedArgs.contains("+rfx"))
        assertTrue(balancedArgs.contains("+sound"))
        assertTrue(balancedArgs.contains("/bpp:16"))

        assertEquals(15, saver.targetFps)
        assertTrue(saver.dynamicThrottling)
        val saverArgs = saver.toFreeRdpCliArgs()
        assertTrue(saverArgs.contains("/bpp:8"))
        assertTrue(saverArgs.contains("/network:modem"))
        assertTrue(saverArgs.contains("+compression"))
        assertFalse(saverArgs.contains("+rfx"))

        // Round trip to the core engine preset contract
        assertEquals(com.freerdp.core.engine.PerformancePreset.DATA_SAVER, saver.toCorePreset())
        assertEquals(
            ultra,
            TelemetryPreset.fromCorePreset(com.freerdp.core.engine.PerformancePreset.LOW_LATENCY)
        )
        assertEquals(
            balanced,
            TelemetryPreset.fromCorePreset(com.freerdp.core.engine.PerformancePreset.BALANCED)
        )
    }

    @Test
    fun r4_lowLatencySocketConfigTogglesNagleAndSizesBuffers() {
        assertEquals(131072, LowLatencySocketConfig.DEFAULT_RX_BUFFER_BYTES)
        assertEquals(65536, LowLatencySocketConfig.DEFAULT_TX_BUFFER_BYTES)
        assertEquals(0x10, LowLatencySocketConfig.IPTOS_LOWDELAY)
        assertTrue(LowLatencySocketConfig.DEFAULT_TCP_NODELAY)

        // Unconnected socket: purely local option mutation, no network traffic
        val socket = Socket()
        val result = LowLatencySocketConfig.configureSocket(socket)
        assertTrue("configuration must succeed", result.isSuccess)

        val inspected = LowLatencySocketConfig.inspectSocket(socket)
        assertEquals(
            setOf("tcpNoDelay", "receiveBufferSize", "sendBufferSize", "trafficClass", "keepAlive"),
            inspected.keys
        )
        assertEquals(true, inspected["tcpNoDelay"])
        assertEquals(true, inspected["keepAlive"])
        assertTrue("rx buffer >= 128KB", (inspected["receiveBufferSize"] as Int) >= 131072)
        assertTrue("tx buffer >= 64KB", (inspected["sendBufferSize"] as Int) >= 65536)

        // Closed sockets are rejected with a failed Result (never an exception)
        socket.close()
        val closedResult = LowLatencySocketConfig.configureSocket(socket)
        assertTrue("closed socket must fail closed", closedResult.isFailure)
        assertTrue(
            "failure must carry IllegalStateException",
            closedResult.exceptionOrNull() is IllegalStateException
        )
    }

    @Test
    fun r4_fastPathPrioritizerOrdersInteractivePdusBeforeBulk() {
        // MS-RDPBCGR 2.2.9.1.2: FastPath action bits are the low 2 bits of the
        // first header byte; 0b00 = fastpath, slow-path TPKT starts with 0x03.
        assertTrue(FastPathPduPrioritizer.isFastPath(0x00))
        assertTrue(FastPathPduPrioritizer.isFastPath(0x04))
        assertFalse(FastPathPduPrioritizer.isFastPath(0x03))

        // Deterministic injected clock: nothing starves during the first phase
        var now = 1_000_000_000L
        val queue = FastPathPduPrioritizer.PduQueue(nanoClock = { now })
        val bulk = queue.enqueue(byteArrayOf(0xFF.toByte(), 0x01), isOutbound = false)!!
        val input = queue.enqueue(byteArrayOf(0x00, 0x02), isOutbound = true)!!
        val slowPath = queue.enqueue(byteArrayOf(0x03, 0x00), isOutbound = false)!!

        assertEquals(FastPathPduPrioritizer.PduPriority.BULK_DATA, bulk.priority)
        assertEquals(FastPathPduPrioritizer.PduPriority.FASTPATH_INPUT, input.priority)
        assertEquals(FastPathPduPrioritizer.PduPriority.SLOWPATH_INTERACTIVE, slowPath.priority)

        // Within the starvation window strict priority holds:
        // user input wins over control traffic wins over bulk clipboard.
        assertSame(input, queue.poll())
        assertSame(slowPath, queue.poll())
        assertSame(bulk, queue.poll())
        assertTrue(queue.isEmpty)

        // Beyond the 500ms starvation bound a waiting bulk PDU is promoted
        // ahead of fresher input, bounding clipboard latency under load.
        val starvedBulk = queue.enqueue(byteArrayOf(0xFF.toByte(), 0x01), isOutbound = false)!!
        now += FastPathPduPrioritizer.PduQueue.DEFAULT_STARVATION_THRESHOLD_NANOS + 1
        val freshInput = queue.enqueue(byteArrayOf(0x00, 0x02), isOutbound = true)!!
        assertSame("starved bulk outranks fresher input", starvedBulk, queue.poll())
        assertSame(freshInput, queue.poll())
        assertTrue(queue.isEmpty)
    }

    @Test
    fun r4_dynamicLayoutListenerAppliesOrientationResizeAndDeduplicates() = runTest {
        val layoutListener = com.freerdp.feature.telemetry.display.DynamicLayoutListener(
            engine = engine,
            coroutineScope = backgroundScope,
            debounceDelayMs = 200L
        )

        layoutListener.onLayoutChanged(
            1080, 1920,
            physicalWidthMm = 68, physicalHeightMm = 121,
            orientation = 1, immediate = true
        )
        assertEquals(1, layoutListener.resizeEventsCount)
        assertEquals(
            listOf(MockRdpEngine.ResolutionEvent(1080, 1920, 68, 121, 1)),
            engine.recordedResolutions
        )
        assertEquals(listOf(1080 to 1920), listener.resolutionChanges)

        // Identical dims + orientation must not flood the native session
        layoutListener.onLayoutChanged(
            1080, 1920,
            physicalWidthMm = 68, physicalHeightMm = 121,
            orientation = 1, immediate = true
        )
        assertEquals(1, layoutListener.resizeEventsCount)

        // Rotation back to landscape is a genuine change
        layoutListener.onLayoutChanged(
            1920, 1080,
            physicalWidthMm = 121, physicalHeightMm = 68,
            orientation = 0, immediate = true
        )
        assertEquals(2, layoutListener.resizeEventsCount)
        // Deduplicated call 2 never reached the engine: exactly 2 PDUs total
        assertEquals(2, engine.recordedResolutions.size)
        assertEquals(1920, engine.recordedResolutions[1].width)
        assertEquals(0, engine.recordedResolutions[1].orientation)
    }

    // ------------------------------------------------------------------
    // MotionEvent builders (deterministic, Robolectric-shadowed)
    // ------------------------------------------------------------------

    private fun single(action: Int, x: Float, y: Float, downTime: Long, eventTime: Long): MotionEvent =
        MotionEvent.obtain(downTime, eventTime, action, x, y, 0)

    private fun dual(
        action: Int,
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        downTime: Long,
        eventTime: Long
    ): MotionEvent {
        val pp0 = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
        val pp1 = MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER }
        val pc0 = MotionEvent.PointerCoords().apply { x = x0; y = y0 }
        val pc1 = MotionEvent.PointerCoords().apply { x = x1; y = y1 }
        return MotionEvent.obtain(
            downTime, eventTime, action, 2,
            arrayOf(pp0, pp1), arrayOf(pc0, pc1),
            0, 0, 1.0f, 1.0f, 0, 0, 0, 0
        )
    }
}
