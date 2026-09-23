package com.freerdp.core

import android.content.Context
import android.graphics.Bitmap
import com.freerdp.core.engine.FreeRdpNative
import com.freerdp.core.engine.NativeFreeRdpEngine
import com.freerdp.core.engine.RdpConnectionConfig
import com.freerdp.core.engine.RdpConnectionState
import com.freerdp.core.engine.RdpEventListener
import com.freerdp.core.engine.RdpSessionMetrics
import com.freerdp.core.protocol.ClipboardHandler
import com.freerdp.freerdpcore.services.LibFreeRDP
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/**
 * Lifecycle tests for [NativeFreeRdpEngine] driven through a scripted [FreeRdpNative]
 * fake: full async connect (upstream freerdp_connect only starts a worker thread —
 * the real result arrives via OnConnectionSuccess/OnConnectionFailure), the 5-step
 * leak-free teardown, stale-callback filtering, and the MS-RDPEDISP / MS-RDPECLIP /
 * certificate round-trips — all without packaged .so files.
 */
class NativeFreeRdpEngineLifecycleTest {

    /** Scripted native seam; records every call and can emit callbacks like the real glue. */
    private class FakeNative : FreeRdpNative {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        var loaded: Boolean = true
        var loadError: Throwable? = null
        var nextInstance: Long = 100L
        var newInstanceResult: Long? = null
        var parseResult: Boolean = true
        var startResult: Boolean = true
        var connectSignal: ((LibFreeRDP.NativeCallbacks?, Long) -> Unit)? = null
        var lastErrorMessage: String? = "native last error"
        var callbacks: LibFreeRDP.NativeCallbacks? = null
        val sentClipboard: MutableList<Pair<Long, String>> = Collections.synchronizedList(mutableListOf())
        val sentMonitorLayouts: MutableList<Triple<Long, Int, Int>> = Collections.synchronizedList(mutableListOf())
        val updatedGraphics: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val freedInstances: MutableList<Long> = Collections.synchronizedList(mutableListOf())

        override fun isNativeLibraryLoaded(): Boolean = loaded
        override fun getNativeLoadError(): Throwable? = loadError

        override fun newInstance(context: Context?): Long {
            calls += "new"
            return newInstanceResult ?: nextInstance++
        }

        override fun freeInstance(inst: Long) {
            calls += "free:$inst"
            freedInstances += inst
        }

        override fun disconnectSession(inst: Long): Boolean {
            calls += "disconnect:$inst"
            // Model a healthy glue: the abort makes the worker thread exit promptly and
            // fire OnDisconnected (completes the engine's sessionEnded latch so teardown
            // step 3 does not have to wait for sessionEndWaitMs).
            callbacks?.onDisconnected(inst)
            return true
        }

        override fun startConnection(inst: Long): Boolean {
            calls += "start:$inst"
            // Synchronous-glue simulation: the real glue starts a worker thread whose
            // OnConnectionSuccess/OnConnectionFailure later complete the connect wait.
            connectSignal?.invoke(callbacks, inst)
            return startResult
        }

        override fun parseArguments(inst: Long, args: Array<String>): Boolean {
            calls += "parse"
            return parseResult
        }

        override fun sendCursorEvent(inst: Long, x: Int, y: Int, flags: Int): Boolean = true
        override fun sendKeyEvent(inst: Long, keycode: Int, down: Boolean): Boolean = true
        override fun sendUnicodeEvent(inst: Long, unicode: Int, down: Boolean): Boolean = true

        override fun sendClipboardText(inst: Long, text: String): Boolean {
            sentClipboard += inst to text
            return true
        }

        override fun sendMonitorLayout(inst: Long, width: Int, height: Int): Boolean {
            sentMonitorLayouts += Triple(inst, width, height)
            return true
        }

        override fun updateGraphics(
            inst: Long, bitmap: Bitmap, x: Int, y: Int, width: Int, height: Int
        ): Boolean {
            updatedGraphics += "$inst:$x,$y,${width}x$height"
            return true
        }

        override fun getLastErrorMessage(inst: Long): String? = lastErrorMessage
        override fun setNativeCallbacks(callbacks: LibFreeRDP.NativeCallbacks?) {
            this.callbacks = callbacks
        }
        override fun getNativeCallbacks(): LibFreeRDP.NativeCallbacks? = callbacks
    }

    private class RecordingListener : RdpEventListener {
        val successes = mutableListOf<Unit>()
        val failures = mutableListOf<Pair<Int, String>>()
        val disconnects = mutableListOf<Unit>()
        val resolutions = mutableListOf<Pair<Int, Int>>()
        val graphics = mutableListOf<Triple<Int, Int, Int>>() // x,y,area w*h via first/second
        val graphicsWidths = mutableListOf<Int>()
        val clipboard = mutableListOf<Pair<Int, String>>()
        var lastCertFingerprint: String? = null
        var lastCertHost: String? = null
        var acceptCert = true

        override fun onConnectionSuccess() { successes += Unit }
        override fun onConnectionFailure(errorCode: Int, message: String) {
            failures += errorCode to message
        }
        override fun onDisconnected() { disconnects += Unit }
        override fun onGraphicsUpdate(bitmap: Bitmap, x: Int, y: Int, width: Int, height: Int) {
            graphics += Triple(x, y, width)
            graphicsWidths += width
        }
        override fun onResolutionChanged(width: Int, height: Int) {
            resolutions += width to height
        }
        override fun onClipboardDataReceived(format: Int, data: ByteArray) {
            clipboard += format to ClipboardHandler.decodeUnicodeText(data)
        }
        override fun onCertificateVerification(fingerprint: String, host: String): Boolean {
            lastCertFingerprint = fingerprint
            lastCertHost = host
            return acceptCert
        }
    }

    private val config = RdpConnectionConfig(
        serverAddress = "10.0.0.5",
        port = 3389,
        username = "user",
        domain = "CORP",
        password = "pw"
    )

    @After
    fun tearDown() {
        LibFreeRDP.setNativeCallbacks(null)
    }

    private fun engineWith(
        native: FakeNative,
        timeoutMs: Long = 5_000L,
        endWaitMs: Long = 2_000L
    ) = NativeFreeRdpEngine(
        ioDispatcher = Dispatchers.IO,
        connectTimeoutMs = timeoutMs,
        sessionEndWaitMs = endWaitMs,
        native = native
    )

    // ------------------------------------------------------------------
    // Async connect
    // ------------------------------------------------------------------

    @Test
    fun connectSucceedsWhenNativeSignalsSuccessFromWorkerCallback() = runBlocking {
        val fake = FakeNative()
        // Worker fires OnConnectionSuccess asynchronously after start returns.
        fake.connectSignal = { cbs, inst ->
            Thread { cbs?.onConnectionSuccess(inst) }.apply { isDaemon = true }.start()
        }
        val engine = engineWith(fake)
        val listener = RecordingListener()
        engine.setEventListener(listener)

        val ok = withTimeout(4_000) { engine.connect(config) }

        assertTrue(ok)
        assertEquals(RdpConnectionState.Connected, engine.connectionState.value)
        assertEquals(1, listener.successes.size)
        assertEquals(config, engine.activeConfig)
        // Callbacks registered before start so no event can be missed:
        // argument parsing completes before the worker thread is spawned.
        val startIdx = fake.calls.indexOfFirst { it.startsWith("start") }
        val parseIdx = fake.calls.indexOf("parse")
        assertTrue("start=$startIdx parse=$parseIdx in ${fake.calls}", startIdx > parseIdx && startIdx >= 0)
        assertSameCallbacks(engine, fake)
    }

    @Test
    fun connectFailsWithNativeErrorStringOnConnectionFailureCallback() = runBlocking {
        val fake = FakeNative()
        fake.connectSignal = { cbs, inst -> cbs?.onConnectionFailure(inst) }
        val engine = engineWith(fake)
        val listener = RecordingListener()
        engine.setEventListener(listener)

        val ok = engine.connect(config)

        assertFalse(ok)
        val state = engine.connectionState.value
        assertTrue(state is RdpConnectionState.Failed)
        val failed = state as RdpConnectionState.Failed
        assertEquals(NativeFreeRdpEngine.ERROR_CONNECT_FAILED, failed.errorCode)
        assertEquals("native last error", failed.message)
        assertEquals(1, listener.failures.size)
        // Teardown released the instance and unregistered the static callbacks (leak-free).
        assertEquals(1, fake.freedInstances.size)
        assertNull(fake.callbacks)
    }

    @Test
    fun connectTimesOutWhenWorkerNeverSignals() = runBlocking {
        val fake = FakeNative() // startResult=true but no callback ever fires
        val engine = engineWith(fake, timeoutMs = 200L, endWaitMs = 100L)
        val listener = RecordingListener()
        engine.setEventListener(listener)

        val ok = engine.connect(config)

        assertFalse(ok)
        val failed = engine.connectionState.value as RdpConnectionState.Failed
        assertEquals(NativeFreeRdpEngine.ERROR_CONNECT_TIMEOUT, failed.errorCode)
        assertTrue(failed.message.contains("timed out"))
        assertEquals(1, listener.failures.size)
        // Timeout still runs the full teardown: abort + free + unregister.
        assertTrue(fake.calls.contains("disconnect:100"))
        assertEquals(1, fake.freedInstances.size)
        assertNull(fake.callbacks)
    }

    @Test
    fun nativeNotLoadedSurfacesTypedFailureWithLoadError() = runBlocking {
        val fake = FakeNative()
        fake.loaded = false
        fake.loadError = UnsatisfiedLinkError("no winpr3 in java.library.path")
        val engine = engineWith(fake)
        val listener = RecordingListener()
        engine.setEventListener(listener)

        assertFalse(engine.connect(config))

        val failed = engine.connectionState.value as RdpConnectionState.Failed
        assertEquals(NativeFreeRdpEngine.ERROR_NATIVE_NOT_LOADED, failed.errorCode)
        assertFalse(failed.nativeLibraryAvailable)
        assertTrue(
            "loadError must carry the UnsatisfiedLinkError, got: ${failed.loadError}",
            failed.loadError!!.toString().contains("UnsatisfiedLinkError")
        )
        assertTrue(failed.message.contains("winpr3"))
        assertEquals(1, listener.failures.size)
        // No native call may happen when not loaded.
        assertTrue(fake.calls.isEmpty())
        assertFalse(engine.isNativeLoaded)
    }

    @Test
    fun parseFailureFreesInstanceWithoutStartingWorker() = runBlocking {
        val fake = FakeNative()
        fake.parseResult = false
        val engine = engineWith(fake)
        val listener = RecordingListener()
        engine.setEventListener(listener)

        assertFalse(engine.connect(config))

        val failed = engine.connectionState.value as RdpConnectionState.Failed
        assertEquals(NativeFreeRdpEngine.ERROR_ARGUMENTS_INVALID, failed.errorCode)
        // Worker never started → no disconnect/abort, but the instance IS freed.
        assertFalse(fake.calls.contains("start:100"))
        assertFalse(fake.calls.contains("disconnect:100"))
        assertEquals(1, fake.freedInstances.size)
    }

    @Test
    fun concurrentConnectSerializedByLifecycleMutex() = runBlocking {
        // Two concurrent connects must not interleave instance allocation with teardown
        // (the original code could leak the first native instance).
        val fake = FakeNative()
        fake.connectSignal = { cbs, inst -> cbs?.onConnectionSuccess(inst) }
        val engine = engineWith(fake)

        val results = (1..4).map {
            async(Dispatchers.IO) { engine.connect(config) }
        }
        val oks = results.map { withTimeout(8_000) { it.await() } }

        // The final connect attempt always wins finalization (supersession guard) and
        // reports success when its worker signalled success.
        assertTrue("at least one connect must succeed, got $oks", oks.any { it })
        assertEquals(RdpConnectionState.Connected, engine.connectionState.value)

        // Every allocated instance beyond the single live one was reclaimed by the
        // next attempt's pre-connect cleanup — no native leak under concurrency.
        val news = fake.calls.count { it == "new" }
        val frees = fake.freedInstances.size
        assertEquals("new=$news frees=$frees", news - 1, frees)

        engine.disconnect()
        assertEquals("all instances freed after disconnect", news, fake.freedInstances.size)
    }

    // ------------------------------------------------------------------
    // 5-step teardown / disconnect
    // ------------------------------------------------------------------

    @Test
    fun disconnectExecutesFiveStepTeardownInOrderAndNotifiesOnce() = runBlocking {
        val fake = FakeNative()
        fake.connectSignal = { cbs, inst -> cbs?.onConnectionSuccess(inst) }
        val engine = engineWith(fake)
        val listener = RecordingListener()
        engine.setEventListener(listener)
        assertTrue(engine.connect(config))
        fake.calls.clear()

        engine.disconnect()

        assertEquals(RdpConnectionState.Disconnected, engine.connectionState.value)
        assertEquals(1, listener.disconnects.size)
        assertNull(engine.activeConfig)
        // Step order: abort before free; callbacks unregistered last.
        val abort = fake.calls.indexOf("disconnect:100")
        val free = fake.calls.indexOf("free:100")
        assertTrue("abort=$abort free=$free in ${fake.calls}", abort in 0 until free)
        assertNull("static JNI callbacks must not outlive the session", fake.callbacks)
        // Stale input after teardown is a no-op (pointer claimed before free).
        engine.sendPointerEvent(0x1000, 1, 2)
        engine.sendClipboardText("late")
        assertEquals(0, fake.sentClipboard.size)
    }

    @Test
    fun disconnectIsIdempotentAndFreesExactlyOnce() = runBlocking {
        val fake = FakeNative()
        fake.connectSignal = { cbs, inst -> cbs?.onConnectionSuccess(inst) }
        val engine = engineWith(fake)
        val listener = RecordingListener()
        engine.setEventListener(listener)
        assertTrue(engine.connect(config))

        engine.disconnect()
        engine.disconnect()

        assertEquals(1, fake.freedInstances.size)
        assertEquals(RdpConnectionState.Disconnected, engine.connectionState.value)
        assertEquals(2, listener.disconnects.size) // public API notifies per call (Mock parity)
    }

    @Test
    fun connectDoesNotEmitPhantomDisconnectEvent() = runBlocking {
        // Regression: connect() used to call the public disconnect() first, emitting a
        // spurious onDisconnected before Connecting (MockRdpEngine.connect never does).
        val fake = FakeNative()
        fake.connectSignal = { cbs, inst -> cbs?.onConnectionSuccess(inst) }
        val engine = engineWith(fake)
        val listener = RecordingListener()
        engine.setEventListener(listener)

        assertTrue(engine.connect(config))
        assertEquals("no phantom disconnect on connect", 0, listener.disconnects.size)

        engine.disconnect()
        assertEquals(1, listener.disconnects.size)
    }

    @Test
    fun reconnectAfterServerDropReclaimsTheDeadInstance() = runBlocking {
        val fake = FakeNative()
        fake.connectSignal = { cbs, inst -> cbs?.onConnectionSuccess(inst) }
        val engine = engineWith(fake)
        val listener = RecordingListener()
        engine.setEventListener(listener)
        assertTrue(engine.connect(config))
        val firstInstance =100L

        // Server initiates a clean close while we are Connected.
        engine.onDisconnected(firstInstance)
        assertEquals(RdpConnectionState.Disconnected, engine.connectionState.value)
        assertEquals(1, listener.disconnects.size)
        // The instance is NOT freed inside the JNI callback (android_thread_func is
        // still unwinding); it is reclaimed by the next lifecycle call:
        assertEquals(0, fake.freedInstances.size)

        assertTrue(engine.connect(config)) // pre-connect cleanup reclaims instance100
        assertTrue(fake.freedInstances.contains(firstInstance))
        engine.disconnect()
    }

    @Test
    fun midSessionFailureFailsStateForAutoReconnectAndReclaimsLater() = runBlocking {
        val fake = FakeNative()
        fake.connectSignal = { cbs, inst -> cbs?.onConnectionSuccess(inst) }
        val engine = engineWith(fake)
        val listener = RecordingListener()
        engine.setEventListener(listener)
        assertTrue(engine.connect(config))
        val inst =100L

        // Network-level drop: OnConnectionFailure arrives mid-session.
        engine.onConnectionFailure(inst)

        val failed = engine.connectionState.value as RdpConnectionState.Failed
        assertEquals(NativeFreeRdpEngine.ERROR_CONNECT_FAILED, failed.errorCode)
        assertEquals("native last error", failed.message)
        assertEquals(1, listener.failures.size)
        // No free inside the callback; reclaim on the next lifecycle operation.
        assertEquals(0, fake.freedInstances.size)

        engine.disconnect()
        assertEquals(1, fake.freedInstances.size)
    }

    @Test
    fun staleCallbacksForOldInstancesAreDropped() = runBlocking {
        val fake = FakeNative()
        fake.connectSignal = { cbs, inst -> cbs?.onConnectionSuccess(inst) }
        val engine = engineWith(fake)
        val listener = RecordingListener()
        engine.setEventListener(listener)
        assertTrue(engine.connect(config))
        val live =100L
        engine.onConnectionSuccess(live) // duplicate for live instance: no state change
        assertTrue(engine.connectionState.value is RdpConnectionState.Connected)

        // Instance101 was never ours → all callbacks ignored.
        engine.onConnectionFailure(101L)
        engine.onDisconnected(101L)
        engine.onGraphicsResize(101L,1, 1, 32)
        engine.onRemoteClipboardChanged(101L, "stale")

        assertTrue(engine.connectionState.value is RdpConnectionState.Connected)
        assertEquals(0, listener.failures.size)
        assertEquals(0, listener.disconnects.size)
        assertEquals(0, listener.resolutions.size)
        assertEquals(0, listener.clipboard.size)
        engine.disconnect()
    }

    @Test
    fun instanceZeroCallbacksIgnoredWhileSessionAlive() = runBlocking {
        val fake = FakeNative()
        fake.connectSignal = { cbs, inst -> cbs?.onConnectionSuccess(inst) }
        val engine = engineWith(fake)
        engine.setEventListener(RecordingListener())
        assertTrue(engine.connect(config))
        val listener = RecordingListener()
        engine.setEventListener(listener)

        // Real callbacks always carry a non-zero pointer while a session is live.
        engine.onSettingsChanged(0L, 800, 600, 32)
        engine.onRemoteClipboardChanged(0L, "zero")
        assertEquals(0, listener.resolutions.size)
        assertEquals(0, listener.clipboard.size)
        engine.disconnect()
    }

    // ------------------------------------------------------------------
    // MS-RDPEDISP resolution round-trip
    // ------------------------------------------------------------------

    @Test
    fun resolutionRequestRoundTripsThroughMonitorLayoutWhileConnected() = runBlocking {
        val fake = FakeNative()
        fake.connectSignal = { cbs, inst -> cbs?.onConnectionSuccess(inst) }
        val engine = engineWith(fake)
        val listener = RecordingListener()
        engine.setEventListener(listener)
        assertTrue(engine.connect(config))

        engine.updateResolution(
            width = 1920, height = 1080,
            physicalWidthMm = 508, physicalHeightMm = 286,
            orientation = 0
        )

        assertEquals(listOf(Triple(100L, 1920, 1080)), fake.sentMonitorLayouts)
        assertEquals(listOf(1920 to 1080), listener.resolutions)
        // Physical millimetres pass through to the dispatched monitor layout
        // (dpi-derived defaults would misreport the panel size).
        val layout = engine.displayControlHandler.getLastDispatchedLayout()
        assertEquals(508, layout?.physicalWidthMm)
        assertEquals(286, layout?.physicalHeightMm)
        engine.disconnect()
    }

    @Test
    fun resolutionRequestStillNotifiesListenerWithoutNativeSession() = runBlocking {
        // Mock parity: updateResolution echoes to the listener even with no session.
        val engine = engineWith(FakeNative())
        val listener = RecordingListener()
        engine.setEventListener(listener)

        engine.updateResolution(1024, 768, 300, 170, orientation = 1)

        assertEquals(listOf(1024 to 768), listener.resolutions)
        // Immediate dispatch already happened synchronously; physical mm passed through.
        assertEquals(300, engine.displayControlHandler.getLastDispatchedLayout()?.physicalWidthMm)
        assertEquals(170, engine.displayControlHandler.getLastDispatchedLayout()?.physicalHeightMm)
    }

    // ------------------------------------------------------------------
    // MS-RDPECLIP clipboard round-trip + echo suppression
    // ------------------------------------------------------------------

    @Test
    fun clipboardRoundTripSuppressesEchoAndDispatchesNewText() = runBlocking {
        val fake = FakeNative()
        fake.connectSignal = { cbs, inst -> cbs?.onConnectionSuccess(inst) }
        val engine = engineWith(fake)
        val listener = RecordingListener()
        engine.setEventListener(listener)
        assertTrue(engine.connect(config))

        // local → remote
        engine.sendClipboardText("SharedText")
        assertEquals(listOf(100L to "SharedText"), fake.sentClipboard)

        // remote echoes the exact same text → suppressed (no loop back to listener)
        engine.onRemoteClipboardChanged(100L, "SharedText")
        assertEquals(0, listener.clipboard.size)

        // genuinely new remote text → dispatched as UTF-16LE CF_UNICODETEXT
        engine.onRemoteClipboardChanged(100L, "FromRemote ✓")
        assertEquals(1, listener.clipboard.size)
        assertEquals(ClipboardHandler.CF_UNICODETEXT, listener.clipboard[0].first)
        assertEquals("FromRemote ✓", listener.clipboard[0].second)

        // stale instance does not inject
        engine.onRemoteClipboardChanged(101L, "Stale")
        assertEquals(1, listener.clipboard.size)

        // Echo state is cleared on disconnect: after a reconnect the same text is no
        // longer treated as an echo of our previous local send.
        engine.disconnect()
        engine.connect(config) // fresh instance (nextInstance increments to 101)
        engine.onRemoteClipboardChanged(101L, "SharedText")
        assertEquals(2, listener.clipboard.size)
        assertEquals("SharedText", listener.clipboard[1].second)
    }

    // ------------------------------------------------------------------
    // Certificate verification wiring (FreeRDP accept_certificate polarity)
    // ------------------------------------------------------------------

    @Test
    fun certificateVerificationMapsBooleanToNativePolarity() = runBlocking {
        val fake = FakeNative()
        fake.connectSignal = { cbs, inst -> cbs?.onConnectionSuccess(inst) }
        val engine = engineWith(fake)
        val listener = RecordingListener()
        engine.setEventListener(listener)
        assertTrue(engine.connect(config))
        val inst =100L

        listener.acceptCert = true
        assertEquals(
            "accept must persist (1)",
            1,
            engine.onVerifyCertificateEx(inst, "host.example", 3389, "cn", "sub", "iss", "SHA256:AA", 0L)
        )
        assertEquals("SHA256:AA", listener.lastCertFingerprint)
        assertEquals("host.example", listener.lastCertHost)

        listener.acceptCert = false
        assertEquals(
            "reject must deny (0)",
            0,
            engine.onVerifyCertificateEx(inst, "host.example", 3389, "cn", "sub", "iss", "SHA256:BB", 0L)
        )

        // Stale instance → fail closed without consulting the listener.
        listener.lastCertFingerprint = null
        assertEquals(0, engine.onVerifyCertificateEx(101L, "h", 1, "c", "s", "i", "f", 0L))
        assertNull(listener.lastCertFingerprint)

        // No listener registered → fail closed.
        engine.setEventListener(null)
        assertEquals(
            0,
            engine.onVerifyCertificateEx(inst, "h", 1, "c", "s", "i", "f", 0L)
        )
        engine.disconnect()
    }

    // ------------------------------------------------------------------
    // Session metrics (FPS from graphics cadence)
    // ------------------------------------------------------------------

    @Test
    fun frameMetricsUpdateFromGraphicsCallbackCadence() = runBlocking {
        val fake = FakeNative()
        fake.connectSignal = { cbs, inst -> cbs?.onConnectionSuccess(inst) }
        var nowMs = 0L
        val engine = NativeFreeRdpEngine(
            ioDispatcher = Dispatchers.IO,
            connectTimeoutMs = 5_000L,
            sessionEndWaitMs = 100L,
            frameTimeSource = { nowMs },
            native = fake
        )
        assertTrue(engine.connect(config))
        val inst =100L

        // 10 frames spaced16ms apart inside the1s window → fps == frames in window.
        repeat(10) {
            nowMs += 16L
            engine.onGraphicsUpdate(inst, 0, 0, 10, 10)
        }
        assertEquals(10L, engine.sessionMetrics.value.frameCount)
        assertEquals(10f, engine.sessionMetrics.value.fps)

        // Idle past the1s window → a fresh frame yields fps=1 (window pruned).
        nowMs +=1_500L
        engine.onGraphicsUpdate(inst, 0, 0, 10, 10)
        assertEquals(11L, engine.sessionMetrics.value.frameCount)
        assertEquals(1f, engine.sessionMetrics.value.fps)

        // External RTT injection survives frame updates (copy preserves fields).
        engine.updateMetrics(engine.sessionMetrics.value.copy(rttMs = 42L))
        engine.onGraphicsUpdate(inst, 0, 0, 10, 10)
        assertEquals(42L, engine.sessionMetrics.value.rttMs)
        assertEquals(12L, engine.sessionMetrics.value.frameCount)
        engine.disconnect()
    }

    @Test
    fun updateMetricsReplacesSnapshotLikeMockSetMetrics() {
        val engine = engineWith(FakeNative())
        engine.updateMetrics(RdpSessionMetrics(rttMs = 7L, fps = 30f, bandwidthKbps = 900L))
        assertEquals(7L, engine.sessionMetrics.value.rttMs)
        assertEquals(30f, engine.sessionMetrics.value.fps)
        assertEquals(900L, engine.sessionMetrics.value.bandwidthKbps)
    }

    // ------------------------------------------------------------------
    // Static JNI registration lifecycle (leak-free)
    // ------------------------------------------------------------------

    @Test
    fun staticCallbacksRegisteredForSessionAndUnregisteredOnTeardown() = runBlocking {
        val fake = FakeNative()
        fake.connectSignal = { cbs, inst -> cbs?.onConnectionSuccess(inst) }
        val engine = engineWith(fake)
        engine.setEventListener(RecordingListener())
        assertTrue(engine.connect(config))

        assertSame(engine, fake.callbacks)
        // The registered target is the engine implementing NativeCallbacks — verify a
        // static dispatch reaches it (same path the JNI glue uses).
        val listener = RecordingListener()
        engine.setEventListener(listener)
        fake.callbacks?.onSettingsChanged(100L, 1280, 800, 32)
        assertEquals(listOf(1280 to 800), listener.resolutions)

        engine.disconnect()
        assertNull(fake.callbacks)
    }

    @Test
    fun nativeAuthenticateFillsCredentialsFromActiveConfig() = runBlocking {
        val fake = FakeNative()
        fake.connectSignal = { cbs, inst -> cbs?.onConnectionSuccess(inst) }
        val engine = engineWith(fake)
        engine.setEventListener(RecordingListener())
        assertTrue(engine.connect(config))

        val u = StringBuilder()
        val d = StringBuilder()
        val p = StringBuilder()
        assertTrue(engine.onAuthenticate(100L, u, d, p))
        assertEquals("user", u.toString())
        assertEquals("CORP", d.toString())
        assertEquals("pw", p.toString())

        // Gateway auth unsupported by config contract → declined.
        assertFalse(
            engine.onGatewayAuthenticate(100L, StringBuilder(), StringBuilder(), StringBuilder())
        )
        engine.disconnect()
    }

    @Test
    fun settingsChangedDispatchesResolutionAndCreatesFramebufferWhenBitmapAvailable() = runBlocking {
        val fake = FakeNative()
        fake.connectSignal = { cbs, inst -> cbs?.onConnectionSuccess(inst) }
        val engine = engineWith(fake)
        val listener = RecordingListener()
        engine.setEventListener(listener)
        assertTrue(engine.connect(config))

        engine.onSettingsChanged(100L, 640, 480, 32)
        assertEquals(listOf(640 to 480), listener.resolutions)

        engine.onGraphicsResize(100L, 800, 600, 16)
        assertEquals(listOf(640 to 480, 800 to 600), listener.resolutions)

        // Graphics update: metrics advance; bitmap dispatch degrades without a
        // framebuffer (android.jar stubs cannot allocate one on the JVM).
        engine.onGraphicsUpdate(100L, 0, 0,100, 100)
        assertTrue(engine.sessionMetrics.value.frameCount >= 1L)
        engine.disconnect()
    }

    private suspend fun assertSameCallbacks(engine: NativeFreeRdpEngine, fake: FakeNative) {
        assertSame(
            "engine must be the registered static JNI target while the session is live",
            engine, fake.callbacks
        )
    }
}
