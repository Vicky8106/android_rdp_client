package com.freerdp.client.e2e

import com.freerdp.core.engine.MockRdpEngine
import com.freerdp.core.engine.RdpConnectionConfig
import com.freerdp.core.engine.RdpConnectionState
import com.freerdp.core.protocol.ClipboardHandler
import com.freerdp.core.protocol.DisplayControlHandler
import com.freerdp.feature.mouse.CoordinateTransformer
import com.freerdp.feature.mouse.OverlayCoordinates
import com.freerdp.feature.mouse.SafeInsets
import com.freerdp.feature.session.data.AtomicFileProfileRepository
import com.freerdp.feature.session.model.RdpProfile
import com.freerdp.feature.session.security.CredentialStore
import com.freerdp.feature.session.security.KeystoreCredentialStore
import com.freerdp.feature.telemetry.display.DynamicLayoutListener
import com.freerdp.feature.telemetry.reconnect.AutoReconnectManagerImpl
import com.freerdp.feature.telemetry.reconnect.ReconnectState
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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/**
 * TIER 2 — Boundary & Corner Cases.
 *
 * Four boundary categories from TEST_INFRA.md § Tier 2, each with >= 5 tests:
 *   A. Connection endpoints & clipboard payload limits (empty host, port 0/65535, 1MB clipboard)
 *   B. Coordinate / viewport / scale boundaries (zero & negative coords, sub-pixel scale, clamping)
 *   C. Orientation flips, 250ms debounce coalescing, `& ~3` odd-dimension alignment
 *   D. Reconnect exhaustion, zero backoff delay, corrupt credentials / storage
 *
 * Determinism: MockRdpEngine double, Robolectric SDK, virtual-time coroutines only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class Tier2BoundaryCornerTest {

    // ==================================================================
    // Category A — endpoints, ports, clipboard payload limits
    // ==================================================================

    @Test
    fun t2_emptyHostnameIsPreservedVerbatimThroughProfileToEngine() = runTest {
        // Boundary: the config pipeline must not crash, substitute, or trim an
        // empty hostname; server-side validation happens at the native engine.
        val profile = RdpProfile(label = "NoHost", hostname = "")
        val config = profile.toConnectionConfig(password = "pw")

        assertEquals("", config.serverAddress)
        assertEquals("", config.host)

        val engine = MockRdpEngine()
        assertTrue(engine.connect(config))
        assertEquals("", engine.activeConfig?.serverAddress)

        val direct = RdpConnectionConfig(serverAddress = "")
        assertEquals("", direct.serverAddress)
    }

    @Test
    fun t2_portZeroIsPreservedEndToEnd() = runTest {
        // Port 0 is the OS "ephemeral" boundary; the pipeline must carry it
        // unmodified rather than silently defaulting to 3389.
        val profile = RdpProfile(label = "PortZero", hostname = "host.local", port = 0)
        val config = profile.toConnectionConfig()
        assertEquals(0, config.port)

        val engine = MockRdpEngine()
        assertTrue(engine.connect(config))
        assertEquals(0, engine.activeConfig?.port)

        // Sanity: omitted port still defaults to the RDP standard port
        assertEquals(3389, RdpProfile(label = "Default", hostname = "h").port)
        assertEquals(3389, RdpConnectionConfig(serverAddress = "h").port)
    }

    @Test
    fun t2_port65535MaximumIsValidAndPreserved() = runTest {
        // TCP port range upper bound (MS-RDPBCGR permits any TCP port)
        val profile = RdpProfile(label = "MaxPort", hostname = "host.local", port = 65535)
        val config = profile.toConnectionConfig()
        assertEquals(65535, config.port)

        val engine = MockRdpEngine()
        assertTrue(engine.connect(config))
        assertEquals(65535, engine.activeConfig?.port)
        assertEquals(RdpConnectionState.Connected, engine.connectionState.value)
    }

    @Test
    fun t2_oneMegabyteClipboardEncodesToUtf16LeWithNullTerminator() {
        // 500,000 UTF-16 code units = exactly 1,000,000 bytes of text payload
        // plus the 2-byte terminator required by MS-RDPECLIP CF_UNICODETEXT.
        val text = "R".repeat(500_000)
        val encoded = ClipboardHandler.encodeUnicodeText(text)

        assertEquals(1_000_002, encoded.size)
        assertEquals(0, encoded[encoded.size - 2].toInt())
        assertEquals(0, encoded[encoded.size - 1].toInt())

        val decoded = ClipboardHandler.decodeUnicodeText(encoded)
        assertEquals(text.length, decoded.length)
        assertEquals("1MB payload must round trip losslessly", text, decoded)

        val hash = ClipboardHandler.sha256(text)
        assertEquals("SHA-256 hex digest length", 64, hash.length)
        assertEquals(hash, ClipboardHandler.sha256(text))
    }

    @Test
    fun t2_oneMegabyteClipboardTravelsThroughEngineAndEchoIsSuppressed() {
        val text = "P".repeat(500_000)
        var sentFormat = -1
        var sentBytes = 0
        val localUpdates = mutableListOf<String>()

        val handler = ClipboardHandler(
            onSendRemoteClipboard = { format, data ->
                sentFormat = format
                sentBytes = data.size
            },
            onLocalClipboardUpdate = { localUpdates.add(it) }
        )

        assertTrue("1MB local change must be pushed to remote", handler.onLocalClipboardChanged(text))
        assertEquals(ClipboardHandler.CF_UNICODETEXT, sentFormat)
        assertEquals(1_000_002, sentBytes)

        // The remote echoes the identical payload back: loop must be suppressed
        val encoded = ClipboardHandler.encodeUnicodeText(text)
        assertFalse("echo of a local copy must be suppressed", handler.onRemoteClipboardReceived(ClipboardHandler.CF_UNICODETEXT, encoded))
        assertEquals(0, localUpdates.size)

        // And the engine records the outbound payload intact
        val engine = MockRdpEngine()
        engine.sendClipboardText(text)
        assertEquals(1, engine.recordedClipboardTexts.size)
        assertEquals(500_000, engine.recordedClipboardTexts[0].length)
    }

    @Test
    fun t2_emptyClipboardAndForeignFormatsAreRejected() {
        var sendCount = 0
        var localCount = 0
        val handler = ClipboardHandler(
            onSendRemoteClipboard = { _, _ -> sendCount++ },
            onLocalClipboardUpdate = { localCount++ }
        )

        assertFalse("empty local clipboard must not be transmitted", handler.onLocalClipboardChanged(""))
        assertEquals(0, sendCount)

        assertFalse("empty remote payload is ignored", handler.onRemoteClipboardReceived(ClipboardHandler.CF_UNICODETEXT, ByteArray(0)))
        assertEquals(0, localCount)

        // CF_DIB bitmaps are not text and must be dropped by the text-only path
        assertFalse(
            "non-unicode format must be rejected",
            handler.onRemoteClipboardReceived(ClipboardHandler.CF_DIB, byteArrayOf(0x42, 0x4D))
        )
        assertEquals(0, localCount)

        // Unicode payload that decodes to nothing (only NULs) is also ignored
        assertFalse(
            "NUL-only payload decodes to empty text and is ignored",
            handler.onRemoteClipboardReceived(ClipboardHandler.CF_UNICODETEXT, byteArrayOf(0, 0))
        )
        assertEquals(0, localCount)
    }

    // ==================================================================
    // Category B — coordinate, viewport and scale boundaries
    // ==================================================================

    private fun identity(remoteW: Int = 1920, remoteH: Int = 1080): CoordinateTransformer {
        val t = CoordinateTransformer(remoteWidth = remoteW, remoteHeight = remoteH, viewWidth = 1080, viewHeight = 2400)
        t.setTransform(newScale = 1f, transX = 0f, transY = 0f, clamp = false)
        return t
    }

    @Test
    fun t2_zeroScreenCoordinatesMapToDesktopOrigin() {
        val t = identity()
        assertEquals(0 to 0, t.screenToDesktopInt(0f, 0f))
        val pt = t.screenToDesktop(0f, 0f)
        assertEquals(0f, pt.x, 0.0001f)
        assertEquals(0f, pt.y, 0.0001f)
    }

    @Test
    fun t2_negativeScreenCoordinatesClampToOrigin() {
        val t = identity()
        assertEquals(0 to 0, t.screenToDesktopInt(-1f, -1f))
        assertEquals(0 to 0, t.screenToDesktopInt(-100_000f, -50_000f))
        val pt = t.screenToDesktop(-250.75f, -80.5f)
        assertEquals(0f, pt.x, 0.0001f)
        assertEquals(0f, pt.y, 0.0001f)
    }

    @Test
    fun t2_coordinatesBeyondDesktopClampToLastPixel() {
        val t = identity()
        assertEquals(1919 to 1079, t.screenToDesktopInt(100_000f, 100_000f))
        assertEquals(1919 to 1079, t.screenToDesktopInt(1920f, 1080f))
        val pt = t.screenToDesktop(9_999_999f, 9_999_999f)
        assertEquals(1919f, pt.x, 0.0001f)
        assertEquals(1079f, pt.y, 0.0001f)
    }

    @Test
    fun t2_subPixelScaleStaysWithinOnePixelAndClampsToLimits() {
        val t = CoordinateTransformer(remoteWidth = 1920, remoteHeight = 1080, viewWidth = 1080, viewHeight = 2400,
            minScale = 0.25f, maxScale = 5.0f)

        // Sub-pixel zoom levels must still round-trip within a whole pixel
        t.setTransform(newScale = 0.37f, transX = -13.7f, transY = 5.13f, clamp = false)
        // Pick a screen point whose desktop image (640, 540) lies inside the
        // remote bounds, so the clamped screenToDesktopInt cannot mask precision
        val sx = 640f * 0.37f - 13.7f
        val sy = 540f * 0.37f + 5.13f
        val (rdx, rdy) = t.screenToDesktopInt(sx, sy)
        val back = t.desktopToScreen(rdx.toFloat(), rdy.toFloat())
        // Round-trip error is bounded by one floor step scaled by s (< 0.37px)
        assertTrue("x round trip within 1px", Math.abs(back.x - sx) <= 0.38f)
        assertTrue("y round trip within 1px", Math.abs(back.y - sy) <= 0.38f)
        assertTrue("x lands at intended desktop column", Math.abs(rdx - 640) <= 1)
        assertTrue("y lands at intended desktop row", Math.abs(rdy - 540) <= 1)

        // A microscopic zoom delta must not push scale below the floor
        t.setTransform(newScale = 0.25f, transX = 0f, transY = 0f, clamp = false)
        t.applyZoom(0.0001f, 100f, 100f)
        assertEquals("scale clamped at minScale", 0.25f, t.scale, 0.0001f)

        // An enormous zoom delta must not exceed maxScale
        t.applyZoom(1_000_000f, 100f, 100f)
        assertEquals("scale clamped at maxScale", 5.0f, t.scale, 0.0001f)

        t.setScale(-42f)
        assertEquals(0.25f, t.scale, 0.0001f)
    }

    @Test
    fun t2_panIsClampedToViewportEdges() {
        // View smaller than the desktop in BOTH axes so panning is possible on X and Y
        val t = CoordinateTransformer(remoteWidth = 3840, remoteHeight = 2160, viewWidth = 1080, viewHeight = 1080)
        t.setTransform(newScale = 1f, transX = -1000f, transY = -500f, clamp = true)

        val minTx = 1080f - 3840f // view - content = -2760
        val minTy = 1080f - 2160f // view - content = -1080

        // Shove far past both edges: must clamp, never land in void space
        t.applyPan(-1_000_000f, -1_000_000f)
        assertEquals(minTx, t.translationX, 0.001f)
        assertEquals(minTy, t.translationY, 0.001f)

        t.applyPan(+1_000_000f, +1_000_000f)
        assertEquals(0f, t.translationX, 0.001f)
        assertEquals(0f, t.translationY, 0.001f)

        // A moderate pan from the far edge lands strictly between the clamps
        t.applyPan(-400f, -120f)
        assertEquals(-400f, t.translationX, 0.001f)
        assertEquals(-120f, t.translationY, 0.001f)
        assertTrue(t.translationX > minTx && t.translationX < 0f)
        assertTrue(t.translationY > minTy && t.translationY < 0f)

        // When content fits entirely it is re-centred, pan attempts are ignored
        val fitting = CoordinateTransformer(remoteWidth = 800, remoteHeight = 600, viewWidth = 1080, viewHeight = 2400)
        fitting.setTransform(newScale = 1f, transX = 0f, transY = 0f, clamp = true)
        val centredTx = (1080f - 800f) / 2f
        assertEquals(centredTx, fitting.translationX, 0.001f)
        fitting.applyPan(-500f, -500f)
        assertEquals("content smaller than view cannot be panned", centredTx, fitting.translationX, 0.001f)
    }

    @Test
    fun t2_zeroRemoteResolutionIsGuardedToOnePixel() {
        val t = CoordinateTransformer(remoteWidth = 0, remoteHeight = 0, viewWidth = 0, viewHeight = 0)
        assertEquals("degenerate dimensions clamp to 1", 1, t.remoteWidth)
        assertEquals(1, t.remoteHeight)
        assertEquals(1, t.viewWidth)
        assertEquals(1, t.viewHeight)

        assertEquals(0 to 0, t.screenToDesktopInt(500f, 500f))
        t.setRemoteResolution(-100, -100)
        assertEquals(1, t.remoteWidth)
        assertEquals(1, t.remoteHeight)
    }

    @Test
    fun t2_overlayNormalizedCoordinatesValidateAndClampAtBounds() {
        // Inclusive boundaries are valid...
        OverlayCoordinates(0f, 0f)
        OverlayCoordinates(1f, 1f)

        // ...out-of-range normalised values are rejected eagerly
        assertThrows(IllegalArgumentException::class.java) { OverlayCoordinates(1.01f, 0.5f) }
        assertThrows(IllegalArgumentException::class.java) { OverlayCoordinates(0.5f, -0.001f) }

        // Out-of-screen drags normalise into the legal [0,1] range
        val clamped = OverlayCoordinates.fromScreenCoordinates(
            screenX = -500f, screenY = 9_999f,
            screenWidth = 1080, screenHeight = 2400,
            overlayWidth = 200, overlayHeight = 200
        )
        assertEquals(0f, clamped.normalizedX, 0.0001f)
        assertEquals(1f, clamped.normalizedY, 0.0001f)

        // Overlay larger than the usable area collapses inside the safe insets
        val insets = SafeInsets(left = 30, top = 60, right = 20, bottom = 90)
        val pos = OverlayCoordinates(1f, 1f).toScreenCoordinates(
            screenWidth = 300, screenHeight = 400,
            overlayWidth = 400, overlayHeight = 500,
            insets = insets
        )
        assertTrue(pos.x >= insets.left && pos.x <= 300 - 20)
        assertTrue(pos.y >= insets.top && pos.y <= 400 - 90)
    }

    // ==================================================================
    // Category C — orientation flips, debounce, odd-dimension alignment
    // ==================================================================

    @Test
    fun t2_rapidOrientationFlipsCoalesceIntoSingleDebouncedDispatch() = runTest {
        val layouts = mutableListOf<DisplayControlHandler.MonitorLayout>()
        val handler = DisplayControlHandler(debounceDelayMs = 250L, scope = backgroundScope) { layouts.add(it) }

        // Six flips inside 60ms — portrait/landscape ping-pong
        handler.requestLayoutUpdate(width = 1080, height = 1920, dpi = 160f)
        advanceTimeBy(10)
        handler.requestLayoutUpdate(width = 1920, height = 1080, dpi = 160f)
        advanceTimeBy(10)
        handler.requestLayoutUpdate(width = 1080, height = 1920, dpi = 160f)
        advanceTimeBy(10)
        handler.requestLayoutUpdate(width = 1920, height = 1080, dpi = 160f)
        advanceTimeBy(10)
        handler.requestLayoutUpdate(width = 1080, height = 1920, dpi = 160f)
        advanceTimeBy(10)
        handler.requestLayoutUpdate(width = 1920, height = 1080, dpi = 160f)

        advanceTimeBy(1000)
        runCurrent()

        assertEquals("six flips must coalesce into one layout PDU", 1, layouts.size)
        assertEquals("last flip wins", 1920, layouts[0].width)
        assertEquals(1080, layouts[0].height)
        assertEquals(DisplayControlHandler.MonitorLayout.ORIENTATION_LANDSCAPE, layouts[0].orientation)
        assertEquals(1920, handler.getLastDispatchedLayout()?.width)
    }

    @Test
    fun t2_debounceBoundaryNothingAt249msDispatchAt250ms() = runTest {
        val layouts = mutableListOf<DisplayControlHandler.MonitorLayout>()
        val handler = DisplayControlHandler(debounceDelayMs = 250L, scope = backgroundScope) { layouts.add(it) }

        handler.requestLayoutUpdate(width = 1440, height = 3200, dpi = 160f)
        advanceTimeBy(249)
        runCurrent()
        assertEquals("nothing may dispatch before the 250ms window closes", 0, layouts.size)
        assertNull(handler.getLastDispatchedLayout())

        advanceTimeBy(1)
        runCurrent()
        assertEquals("dispatch exactly at 250ms", 1, layouts.size)
        assertEquals(1440, layouts[0].width)
    }

    @Test
    fun t2_oddPixelDimensionsAlignDownToMultipleOfFour() = runTest {
        // `& ~3` alignment required by the display control channel
        assertEquals(1920, DisplayControlHandler.alignDimension(1921))
        assertEquals(1920, DisplayControlHandler.alignDimension(1923))
        assertEquals(1080, DisplayControlHandler.alignDimension(1083))
        assertEquals(644, DisplayControlHandler.alignDimension(645))
        assertEquals(1076, DisplayControlHandler.alignDimension(1079))
        assertEquals(640, DisplayControlHandler.alignDimension(639))
        assertEquals(640, DisplayControlHandler.alignDimension(640))

        // End-to-end through the handler (immediate dispatch)
        var layout: DisplayControlHandler.MonitorLayout? = null
        val handler = DisplayControlHandler(debounceDelayMs = 250L, scope = backgroundScope) { layout = it }
        handler.requestLayoutUpdate(width = 1025, height = 771, dpi = 160f, immediate = true)
        assertEquals(1024, layout?.width)
        assertEquals(768, layout?.height)
        assertTrue("aligned width divisible by 4", layout!!.width % 4 == 0)
        assertTrue("aligned height divisible by 4", layout!!.height % 4 == 0)
    }

    @Test
    fun t2_subMinimumDimensionsClampToSafeFloor() = runTest {
        assertEquals(640, DisplayControlHandler.alignDimension(100, min = 640))
        assertEquals(640, DisplayControlHandler.alignDimension(0, min = 640))
        assertEquals(480, DisplayControlHandler.alignDimension(1, min = 480))

        var layout: DisplayControlHandler.MonitorLayout? = null
        val handler = DisplayControlHandler(debounceDelayMs = 250L, scope = backgroundScope) { layout = it }
        handler.requestLayoutUpdate(width = 320, height = 200, dpi = 160f, immediate = true)

        assertEquals("width floor is 640", 640, layout?.width)
        assertEquals("height floor is 480", 480, layout?.height)
    }

    @Test
    fun t2_nonPositiveDpiProducesZeroPhysicalMillimeters() = runTest {
        assertEquals(0, DisplayControlHandler.calculatePhysicalDimensionMm(1920, 0f))
        assertEquals(0, DisplayControlHandler.calculatePhysicalDimensionMm(1920, -96f))
        // Reference values at positive dpi
        assertEquals(304, DisplayControlHandler.calculatePhysicalDimensionMm(1920, 160f))
        assertEquals(171, DisplayControlHandler.calculatePhysicalDimensionMm(1080, 160f))

        var layout: DisplayControlHandler.MonitorLayout? = null
        val handler = DisplayControlHandler(debounceDelayMs = 0L, scope = backgroundScope) { layout = it }
        handler.requestLayoutUpdate(width = 1920, height = 1080, dpi = 0f, immediate = true)
        assertEquals(0, layout?.physicalWidthMm)
        assertEquals(0, layout?.physicalHeightMm)
    }

    @Test
    fun t2_nonPositiveAndRedundantLayoutEventsAreIgnored() = runTest {
        val engine = MockRdpEngine()
        val listener = DynamicLayoutListener(engine = engine, coroutineScope = backgroundScope, debounceDelayMs = 200L)

        listener.onLayoutChanged(0, 0, immediate = true)
        listener.onLayoutChanged(-1920, 1080, immediate = true)
        listener.onLayoutChanged(1920, -1080, immediate = true)
        assertEquals("non-positive dimensions must never reach the engine", 0, listener.resizeEventsCount)
        assertEquals(0, engine.recordedResolutions.size)

        listener.onLayoutChanged(1920, 1080, physicalWidthMm = 304, physicalHeightMm = 171, orientation = 0, immediate = true)
        assertEquals(1, listener.resizeEventsCount)

        // Exact repeat is deduplicated
        listener.onLayoutChanged(1920, 1080, physicalWidthMm = 304, physicalHeightMm = 171, orientation = 0, immediate = true)
        assertEquals(1, listener.resizeEventsCount)

        // Same pixels but rotated orientation is a real change and must fire
        listener.onLayoutChanged(1920, 1080, physicalWidthMm = 304, physicalHeightMm = 171, orientation = 1, immediate = true)
        assertEquals(2, listener.resizeEventsCount)
        assertEquals(2, engine.recordedResolutions.size)
    }

    @Test
    fun t2_pendingDebounceCanBeCancelledDuringFlipStorm() = runTest {
        val engine = MockRdpEngine()
        val listener = DynamicLayoutListener(engine = engine, coroutineScope = backgroundScope, debounceDelayMs = 200L)

        listener.onLayoutChanged(1080, 1920, orientation = 1)
        advanceTimeBy(100)
        listener.cancelPending()
        advanceTimeBy(500)
        runCurrent()

        assertEquals("cancelled debounce must not fire", 0, listener.resizeEventsCount)
        assertEquals(0, engine.recordedResolutions.size)
    }

    // ==================================================================
    // Category D — reconnect exhaustion, zero backoff, corrupt credentials
    // ==================================================================

    @Test
    fun t2_reconnectExhaustionAfterMaxAttemptsFailsPermanently() = runTest {
        val engine = MockRdpEngine()
        engine.shouldFailConnection = true
        engine.failureErrorCode = 1006
        engine.failureErrorMessage = "forced failure"

        val dispatcher = StandardTestDispatcher(testScheduler)
        val manager = AutoReconnectManagerImpl(
            engine = engine,
            configProvider = { RdpConnectionConfig(serverAddress = "unreachable.example") },
            coroutineScope = this,
            ioDispatcher = dispatcher,
            maxAttempts = 5,
            baseDelayMs = 100L,
            randomProvider = { _, max -> max }
        )
        runCurrent()

        manager.onSessionDropped("initial drop")
        // Drains every scheduled backoff + failing connect until the FSM halts
        advanceUntilIdle()

        val state = manager.reconnectState.value
        assertTrue("expected permanent Failed, was $state", state is ReconnectState.Failed)
        assertTrue("must be flagged as exhausted", (state as ReconnectState.Failed).exhausted)
        assertFalse("exhausted FSM is not connected", state.isConnected)
        assertTrue("every teardown must have executed 5 steps", manager.executedTeardownHistory.size >= 5)
    }

    @Test
    fun t2_zeroBackoffDelayReconnectsImmediately() = runTest {
        // Pure function: zero base delay collapses the whole curve to zero
        val zeroBase: (Long, Long) -> Long = { _, max -> max }
        assertEquals(0L, AutoReconnectManagerImpl.calculateBackoffWithJitter(0, baseDelayMs = 0L, randomProvider = zeroBase))
        assertEquals(0L, AutoReconnectManagerImpl.calculateBackoffWithJitter(9, baseDelayMs = 0L, randomProvider = zeroBase))
        assertEquals("full jitter can sample the floor", 0L, AutoReconnectManagerImpl.calculateBackoffWithJitter(0, randomProvider = { min, _ -> min }))

        val engine = MockRdpEngine()
        val config = RdpConnectionConfig(serverAddress = "fast.example")
        assertTrue(engine.connect(config))

        val dispatcher = StandardTestDispatcher(testScheduler)
        val manager = AutoReconnectManagerImpl(
            engine = engine,
            configProvider = { config },
            coroutineScope = this,
            ioDispatcher = dispatcher,
            baseDelayMs = 0L,
            randomProvider = { min, _ -> min }
        )
        runCurrent()
        assertEquals(ReconnectState.Connected, manager.reconnectState.value)

        manager.onSessionDropped("blip")
        advanceUntilIdle()

        assertEquals("zero-delay path must finish connected", ReconnectState.Connected, manager.reconnectState.value)
        assertEquals(RdpConnectionState.Connected, engine.connectionState.value)
    }

    @Test
    fun t2_networkLossDuringPendingBackoffHoldsUntilNetworkReturns() = runTest {
        val engine = MockRdpEngine()
        val config = RdpConnectionConfig(serverAddress = "cell.example")
        assertTrue(engine.connect(config))

        val dispatcher = StandardTestDispatcher(testScheduler)
        val manager = AutoReconnectManagerImpl(
            engine = engine,
            configProvider = { config },
            coroutineScope = this,
            ioDispatcher = dispatcher,
            baseDelayMs = 1000L,
            randomProvider = { _, max -> max }
        )
        runCurrent()

        manager.onSessionDropped("signal fading")
        runCurrent()
        assertTrue(manager.reconnectState.value is ReconnectState.Reconnecting)

        // Radio goes away mid-backoff: FSM must park in WaitingForNetwork
        manager.onNetworkLost()
        advanceUntilIdle()
        assertTrue(manager.reconnectState.value is ReconnectState.WaitingForNetwork)

        // Virtual minutes pass with no network: no reconnect attempts may fire
        advanceTimeBy(600_000)
        runCurrent()
        assertTrue(
            "must remain WaitingForNetwork without connectivity",
            manager.reconnectState.value is ReconnectState.WaitingForNetwork
        )
        assertEquals(RdpConnectionState.Disconnected, engine.connectionState.value)

        // Fast-path recovery the moment the network returns
        manager.onNetworkAvailable()
        advanceUntilIdle()
        assertEquals(ReconnectState.Connected, manager.reconnectState.value)
        assertEquals(RdpConnectionState.Connected, engine.connectionState.value)
    }

    @Test
    fun t2_corruptProfileFileIsIsolatedOrRecoveredFromBackup() = runTest {
        val dir = Files.createTempDirectory("e2e_t2_corrupt").toFile()
        val file = File(dir, "profiles.json")
        val dispatcher = StandardTestDispatcher(testScheduler)

        // Case 1: garbage file, no backup -> isolated as .corrupt.bak, empty state
        file.writeText("{ this is definitely not json [", Charsets.UTF_8)
        val repoA = AtomicFileProfileRepository(file, dispatcher)
        assertEquals("corrupt storage must yield empty list, not throw", 0, repoA.getAllProfiles().first().size)
        val quarantine = File(dir, "profiles.json.corrupt.bak")
        assertTrue("corrupt payload must be quarantined for diagnosis", quarantine.exists())
        assertEquals("{ this is definitely not json [", quarantine.readText(Charsets.UTF_8))

        // Case 2: valid previous-generation backup restores last good state
        val file2 = File(dir, "profiles2.json")
        val repoB = AtomicFileProfileRepository(file2, dispatcher)
        val p1 = RdpProfile(label = "Gen1", hostname = "one.example")
        val p2 = RdpProfile(label = "Gen2", hostname = "two.example")
        repoB.saveProfile(p1)
        repoB.saveProfile(p2)
        // After two writes the .bak holds the previous good generation [p1]
        val backup = File(dir, "profiles2.json.bak")
        assertTrue(backup.exists())
        file2.writeText("### corrupted mid-write ###", Charsets.UTF_8)

        val repoC = AtomicFileProfileRepository(file2, dispatcher)
        val restored = repoC.getAllProfiles().first()
        assertTrue("last good generation must be restored", restored.any { it.id == p1.id })
        assertFalse("only the backed-up generation returns", restored.any { it.id == p2.id })
    }

    @Test
    fun t2_tamperedVaultCiphertextFailsClosedWithoutReturningPlaintext() {
        val context = RuntimeEnvironment.getApplication()
        val prefsName = "e2e_t2_tamper_vault"
        val store: CredentialStore = KeystoreCredentialStore(context, prefFileName = prefsName)
        store.clearAll()

        val profileId = "victim-profile"
        store.saveSecret(profileId, "TopSecret#1".toCharArray())
        assertArrayEquals("TopSecret#1".toCharArray(), store.getSecret(profileId))

        // Corrupt the stored vault entry with structurally invalid Base64
        val prefs = context.getSharedPreferences(prefsName, android.content.Context.MODE_PRIVATE)
        prefs.edit().putString("secret_$profileId", "!@#\$not-base64%^&*").commit()

        // Hardened contract: the vault never throws — tampered entries resolve
        // to null (fail-closed), so neither plaintext nor exceptions leak out.
        val read = store.getCredential(profileId)
        assertTrue("read must not throw", read.isSuccess)
        assertNull("corrupt entry must resolve to null, never plaintext", read.getOrNull())
        assertNull("raw API also fails closed to null", store.getSecret(profileId))

        // GCM tamper detection: flipping one ciphertext byte must also null out
        store.clearAll()
        store.saveSecret(profileId, "Recoverable#2".toCharArray())
        val raw = prefs.getString("secret_$profileId", null)
        assertNotNull(raw)
        val bytes = android.util.Base64.decode(raw, android.util.Base64.NO_WRAP)
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x01).toByte()
        prefs.edit().putString("secret_$profileId", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)).commit()

        val tampered = store.getCredential(profileId)
        assertTrue("flipped bits must not throw", tampered.isSuccess)
        assertNull("GCM authentication failure must resolve to null", tampered.getOrNull())
        store.clearAll()
    }

    @Test
    fun t2_missingCredentialReturnsNullNotError() {
        val context = RuntimeEnvironment.getApplication()
        val store: CredentialStore = KeystoreCredentialStore(context, prefFileName = "e2e_t2_missing_vault")
        store.clearAll()

        assertNull(store.getSecret("does-not-exist"))
        val viaResult = store.getCredential("does-not-exist")
        assertTrue("missing key is a successful null read, not an error", viaResult.isSuccess)
        assertNull(viaResult.getOrNull())
        // Deleting a missing entry is a no-op, not an exception
        store.deleteSecret("does-not-exist")
        assertTrue(store.deleteCredential("does-not-exist").isSuccess)
    }
}
