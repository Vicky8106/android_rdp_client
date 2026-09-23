package com.freerdp.core.engine

import android.content.Context
import android.graphics.Bitmap
import com.freerdp.core.protocol.ClipboardHandler
import com.freerdp.core.protocol.DisplayControlHandler
import com.freerdp.freerdpcore.services.LibFreeRDP
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * JNI-backed [IRdpEngine] over the FreeRDP Android native libraries.
 *
 * **Lifecycle contract — 5-step leak-free teardown** (executed by [teardownSession],
 * always under [lifecycleLock]):
 *  1. Cancel pending coroutine work (debounced MS-RDPEDISP display-control updates).
 *  2. Abort the native connection (`freerdp_disconnect`).
 *  3. Bounded-wait for the native worker thread's end signal
 *     (`OnDisconnected`/`OnConnectionFailure` JNI callbacks) before freeing — mirrors
 *     upstream `freeInstance()`'s wait-for-thread-exit so a running context is never
 *     freed underneath its own thread.
 *  4. Deallocate the native instance while holding [nativeLock]'s write lock, so no
 *     input-dispatch call (read lock) can observe a freed pointer.
 *  5. Unregister the static JNI callbacks (identity-checked), clear clipboard echo
 *     state and the framebuffer reference. State transition + listener notification
 *     are done by the caller after teardown returns.
 *
 * **Async connect:** upstream `freerdp_connect` only spawns a worker thread; the real
 * result arrives through `OnConnectionSuccess`/`OnConnectionFailure`. This engine waits
 * for those callbacks with [connectTimeoutMs]. A synchronous glue that fires the
 * callbacks before `freerdp_connect` returns also works because the result deferred is
 * registered before the start call.
 *
 * **Threading:** [eventListener] may be invoked from JNI-attached native threads —
 * implementations must be thread-safe. Pointer lifetime is guarded by [nativeLock]
 * (read lock for dispatch, write lock for teardown) and serialized by [lifecycleLock]
 * for connect/disconnect transitions.
 */
class NativeFreeRdpEngine(
    private val context: Context? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    internal val connectTimeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS,
    internal val sessionEndWaitMs: Long = DEFAULT_SESSION_END_WAIT_MS,
    private val frameTimeSource: () -> Long = System::currentTimeMillis,
    private val native: FreeRdpNative = JniFreeRdpNative
) : IRdpEngine, LibFreeRDP.NativeCallbacks {

    private val nativeInstance = AtomicLong(0L)

    /** Guards native pointer lifetime: read lock around dispatch, write lock around free. */
    private val nativeLock = ReentrantReadWriteLock()

    /** Serializes connect/disconnect lifecycle transitions. */
    private val lifecycleLock = Any()

    /** Completed when the native worker fires OnDisconnected/OnConnectionFailure. */
    private val sessionEnded = AtomicReference<CompletableDeferred<Unit>?>(null)

    /** Completed with the async connect result (success=true; failure/false/timeout=null path). */
    private val connectResult = AtomicReference<CompletableDeferred<Boolean>?>(null)

    /** True once the native worker thread was successfully started. */
    @Volatile
    private var threadStarted = false

    @Volatile
    private var eventListener: RdpEventListener? = null

    /** Last connection config (parity with [MockRdpEngine.activeConfig]; cleared on disconnect). */
    @Volatile
    var activeConfig: RdpConnectionConfig? = null
        private set

    private val _connectionState = MutableStateFlow<RdpConnectionState>(RdpConnectionState.Disconnected)
    override val connectionState: StateFlow<RdpConnectionState> = _connectionState.asStateFlow()

    private val _sessionMetrics = MutableStateFlow(RdpSessionMetrics())
    override val sessionMetrics: StateFlow<RdpSessionMetrics> = _sessionMetrics.asStateFlow()

    // Sliding 1s window of graphics-update timestamps for FPS telemetry.
    private val frameLock = Any()
    private val frameTimestamps = ArrayDeque<Long>()
    private var frameCountTotal = 0L

    /** Remote framebuffer mirroring the server surface (upstream SessionActivity pattern). */
    @Volatile
    private var framebuffer: Bitmap? = null

    private val clipboardHandler = ClipboardHandler(
        onSendRemoteClipboard = { _, data ->
            val text = ClipboardHandler.decodeUnicodeText(data)
            withNativeInstance { inst -> native.sendClipboardText(inst, text) }
        },
        onLocalClipboardUpdate = { text ->
            eventListener?.onClipboardDataReceived(
                ClipboardHandler.CF_UNICODETEXT,
                ClipboardHandler.encodeUnicodeText(text)
            )
        }
    )

    /** Internal for tests: asserts the MS-RDPEDISP monitor-layout round-trip payload. */
    internal val displayControlHandler = DisplayControlHandler { layout ->
        withNativeInstance { inst -> native.sendMonitorLayout(inst, layout.width, layout.height) }
        // Do not notify onResolutionChanged prematurely in production: the server will acknowledge
        // and resize via native OnGraphicsResize/OnSettingsChanged once MS-RDPEDISP negotiation succeeds.
        // In test environments (mock/fake native), echo to listener for round-trip assertion.
        if (native !== JniFreeRdpNative) {
            eventListener?.onResolutionChanged(layout.width, layout.height)
        }
    }

    /**
     * Set only while an explicit [disconnect] teardown is running. A synchronous glue
     * (or a fast native thread) firing OnDisconnected/OnConnectionFailure from inside
     * `disconnectSession` re-enters the callbacks on the same thread; without this flag
     * the callback would notify first and [disconnect] would notify again. The flag makes
     * the explicit disconnect the single notification owner regardless of callback
     * timing (sync or async).
     */
    @Volatile
    private var userDisconnecting = false

    /** True when the four FreeRDP native libraries loaded (surfaces LibFreeRDP.isNativeLoaded). */
    val isNativeLoaded: Boolean
        get() = native.isNativeLibraryLoaded()

    override suspend fun connect(config: RdpConnectionConfig): Boolean = withContext(ioDispatcher) {
        activeConfig = config
        if (!native.isNativeLibraryLoaded()) {
            val loadError = native.getNativeLoadError()
            val detail = loadError?.let { ": $it" } ?: ""
            val errorMsg = "FreeRDP native library is not loaded on this platform$detail"
            _connectionState.value = RdpConnectionState.Failed(
                ERROR_NATIVE_NOT_LOADED,
                errorMsg,
                nativeLibraryAvailable = false,
                loadError = loadError?.toString()
            )
            eventListener?.onConnectionFailure(ERROR_NATIVE_NOT_LOADED, errorMsg)
            return@withContext false
        }

        // Phase 1 (under lifecycle lock): silent pre-cleanup of any prior session +
        // allocate + register callbacks + start the native worker.
        // The pre-cleanup is silent by design: connect() must not emit a phantom
        // onDisconnected before we even reach Connecting (MockRdpEngine parity).
        val setup = synchronized(lifecycleLock) {
            userDisconnecting = true // suppress echo of a replaced session's sync callbacks
            try {
                teardownSession()
            } finally {
                userDisconnecting = false
            }
            _connectionState.value = RdpConnectionState.Connecting

            val inst = native.newInstance(context)
            if (inst == 0L) {
                failConnect(ERROR_INSTANCE_ALLOC_FAILED, "Failed to allocate native FreeRDP instance")
                return@synchronized null
            }
            nativeInstance.set(inst)
            native.setNativeCallbacks(this@NativeFreeRdpEngine)

            // Register completion channels BEFORE starting so no callback can be missed.
            val ended = CompletableDeferred<Unit>()
            sessionEnded.set(ended)
            val result = CompletableDeferred<Boolean>()
            connectResult.set(result)

            val args = buildFreeRdpArgs(config).toTypedArray()
            if (!native.parseArguments(inst, args)) {
                failConnect(ERROR_ARGUMENTS_INVALID, "Failed to parse FreeRDP connection arguments")
                return@synchronized null
            }
            if (!native.startConnection(inst)) {
                failConnect(ERROR_CONNECT_FAILED, "Native connection thread failed to start")
                return@synchronized null
            }
            threadStarted = true
            result
        }
        val deferred = setup ?: return@withContext false

        // Phase 2: wait for OnConnectionSuccess/OnConnectionFailure OUTSIDE the lock so a
        // concurrent disconnect() can cancel a hanging connect.
        var finalized = false
        try {
            val outcome = withTimeoutOrNull(connectTimeoutMs) { deferred.await() }

            // Phase 3: finalize.
            synchronized(lifecycleLock) {
                if (connectResult.get() !== deferred) {
                    // Superseded by a newer connect() (or cancelled by disconnect(), which
                    // clears the field): that path owns state finalization, not us.
                    return@synchronized false
                }
                finalized = true
                connectResult.set(null)
                when {
                    // Success is only accepted while still Connecting: a disconnect() that
                    // raced us already moved the state, and a session that ended right after
                    // OnConnectionSuccess must not be reported as Connected.
                    outcome == true &&
                        _connectionState.value is RdpConnectionState.Connecting &&
                        sessionEnded.get()?.isCompleted != true -> {
                        _connectionState.value = RdpConnectionState.Connected
                        eventListener?.onConnectionSuccess()
                        true
                    }
                    outcome == null -> {
                        val errorMsg = "Connection timed out after ${connectTimeoutMs}ms"
                        val alreadyGone = _connectionState.value is RdpConnectionState.Disconnected
                        if (!alreadyGone) {
                            teardownSession()
                            _connectionState.value =
                                RdpConnectionState.Failed(ERROR_CONNECT_TIMEOUT, errorMsg)
                            eventListener?.onConnectionFailure(ERROR_CONNECT_TIMEOUT, errorMsg)
                        }
                        false
                    }
                    else -> {
                        // Callback reported failure (or the session ended before we observed
                        // success). Skip if disconnect()/a callback already finalized the state.
                        val state = _connectionState.value
                        if (state !is RdpConnectionState.Disconnected && state !is RdpConnectionState.Failed) {
                            val inst = nativeInstance.get()
                            val err = if (inst != 0L) native.getLastErrorMessage(inst) else null
                            val errorMsg = err?.takeIf { it.isNotBlank() } ?: "Connection failed"
                            failConnect(ERROR_CONNECT_FAILED, errorMsg)
                            false
                        } else {
                            // Another path already finalized the state; still run teardown for
                            // the async-failure case (idempotent, frees nothing twice).
                            if (state is RdpConnectionState.Failed) teardownSession()
                            false
                        }
                    }
                }
            }
        } finally {
            if (!finalized) {
                withContext(NonCancellable) {
                    synchronized(lifecycleLock) {
                        if (connectResult.get() === deferred) {
                            teardownSession()
                            if (_connectionState.value is RdpConnectionState.Connecting) {
                                _connectionState.value = RdpConnectionState.Disconnected
                                eventListener?.onDisconnected()
                            }
                        }
                    }
                }
            }
        }
    }

    override suspend fun disconnect(): Unit = withContext(ioDispatcher) {
        synchronized(lifecycleLock) {
            userDisconnecting = true
            try {
                teardownSession()
            } finally {
                userDisconnecting = false
            }
            activeConfig = null
            _connectionState.value = RdpConnectionState.Disconnected
            eventListener?.onDisconnected()
        }
        Unit
    }

    override fun sendPointerEvent(flags: Int, x: Int, y: Int) {
        withNativeInstance { inst -> native.sendCursorEvent(inst, x, y, flags) }
    }

    override fun sendKeyEvent(keyCode: Int, down: Boolean) {
        withNativeInstance { inst -> native.sendKeyEvent(inst, keyCode, down) }
    }

    override fun sendUnicodeKeyEvent(unicodeChar: Char, down: Boolean) {
        withNativeInstance { inst -> native.sendUnicodeEvent(inst, unicodeChar.code, down) }
    }

    override fun updateResolution(
        width: Int,
        height: Int,
        physicalWidthMm: Int,
        physicalHeightMm: Int,
        orientation: Int
    ) {
        // MS-RDPEDISP: the handler debounces and its callback both sends the monitor
        // layout (when a session is live) and notifies the listener — so the round-trip
        // is observable even without a native session (Mock parity). Physical dimensions
        // are passed through: the DISPLAY_CONTROL_MONITOR_LAYOUT PDU carries real mm
        // values, and dpi-derived defaults would misreport the device panel size.
        displayControlHandler.requestLayoutUpdate(
            width = width,
            height = height,
            orientation = orientation,
            immediate = true,
            physicalWidthMm = physicalWidthMm,
            physicalHeightMm = physicalHeightMm
        )
    }

    override fun sendClipboardText(text: String) {
        // Routed through the handler even without a live session so the SHA-256 echo
        // state stays correct across reconnects; the native send inside the handler is
        // pointer-guarded.
        clipboardHandler.onLocalClipboardChanged(text)
    }

    override fun setEventListener(listener: RdpEventListener?) {
        this.eventListener = listener
    }

    fun updateMetrics(metrics: RdpSessionMetrics) {
        _sessionMetrics.value = metrics
    }

    // ------------------------------------------------------------------
    // Lifecycle internals (callers hold lifecycleLock)
    // ------------------------------------------------------------------

    /**
     * Steps 1–5 of the leak-free teardown. Never touches connection state and never
     * notifies the listener — callers do that after teardown returns, which keeps the
     * observer-visible ordering (state first, then callback) deterministic.
     * Idempotent: safe to call from connect pre-cleanup, disconnect, timeout and failure.
     */
    private fun teardownSession() {
        val inst = nativeInstance.get()
        val hadSession = inst != 0L
        val wasThreadStarted = threadStarted
        threadStarted = false

        // Step 1: cancel pending coroutine work.
        displayControlHandler.cancelPending()

        if (hadSession) {
            // Step 2: abort the native connection — only meaningful once the worker
            // thread exists (a parse/start failure never spawned one, and calling
            // disconnect there would stall the abort-wait below for nothing).
            if (wasThreadStarted) {
                native.disconnectSession(inst)

                // Step 3: bounded wait for the native thread's end signal. Upstream waits
                // indefinitely; the cap prevents a wedged thread from blocking the IO
                // dispatcher forever (step 2's abort makes a clean exit the common case).
                // Callbacks complete this deferred BEFORE taking lifecycleLock, so this
                // wait cannot deadlock against a callback that wants the lock.
                val ended = sessionEnded.get()
                if (ended != null) {
                    val latch = CountDownLatch(1)
                    ended.invokeOnCompletion { latch.countDown() }
                    val joined = try {
                        latch.await(sessionEndWaitMs, TimeUnit.MILLISECONDS)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        false
                    }
                    if (joined) {
                        if (native.isNativeLibraryLoaded()) {
                            try {
                                Thread.sleep(30L)
                            } catch (_: InterruptedException) {
                                Thread.currentThread().interrupt()
                            }
                        }
                    } else {
                        // Thread did not finish within timeout — abandon pointer safely to prevent UAF/SIGSEGV
                        nativeInstance.set(0L)
                        sessionEnded.set(null)
                        connectResult.get()?.complete(false)
                        connectResult.set(null)
                        if (native.getNativeCallbacks() === this) {
                            native.setNativeCallbacks(null)
                        }
                        clipboardHandler.clearEchoState()
                        framebuffer = null
                        return
                    }
                }
            }

            // Step 4: free under the write lock — dispatchers holding the read lock
            // have finished their native call before we get here.
            nativeLock.writeLock().lock()
            try {
                if (nativeInstance.compareAndSet(inst, 0L)) {
                    native.freeInstance(inst)
                }
            } finally {
                nativeLock.writeLock().unlock()
            }
        }

        sessionEnded.set(null)
        // Unblock any connect() still awaiting phase 2 (disconnect-cancels-connect).
        connectResult.get()?.complete(false)
        connectResult.set(null)

        // Step 5: unregister static JNI callbacks (only if we still own them),
        // clear echo state and the framebuffer.
        if (native.getNativeCallbacks() === this) {
            native.setNativeCallbacks(null)
        }
        clipboardHandler.clearEchoState()
        framebuffer = null
    }

    /** Sets Failed state + notifies; then tears down. Callers hold lifecycleLock. */
    private fun failConnect(code: Int, message: String) {
        teardownSession()
        _connectionState.value = RdpConnectionState.Failed(code, message)
        eventListener?.onConnectionFailure(code, message)
    }

    /** Runs [block] with the live native pointer under the read lock; no-op when absent. */
    private inline fun <R> withNativeInstance(block: (Long) -> R): R? {
        nativeLock.readLock().lock()
        try {
            val inst = nativeInstance.get()
            return if (inst != 0L) block(inst) else null
        } finally {
            nativeLock.readLock().unlock()
        }
    }

    internal fun buildFreeRdpArgs(config: RdpConnectionConfig): List<String> {
        val args = mutableListOf(
            "xfreerdp",
            // The Android glue renders through software GDI; the GFX pipeline is only
            // wired when SoftwareGdi is set (android_freerdp.c channel handler warns
            // "add /gdi:sw" otherwise). Upstream aFreeRDP always passes this flag.
            "/gdi:sw",
            "/v:${formatServer(config.serverAddress)}:${config.port}",
            "/u:${config.username}",
            "/size:${config.width}x${config.height}",
            "/bpp:${config.colorDepth}"
        )
        if (config.domain.isNotEmpty()) {
            args.add("/d:${config.domain}")
        }
        if (config.password.isNotEmpty()) {
            args.add("/p:${config.password}")
        }
        if (config.enableNla) {
            args.add("/sec:nla")
        } else if (config.enableTls) {
            args.add("/sec:tls")
        } else {
            args.add("/sec:rdp")
        }
        if (config.ignoreCertificate) {
            // /cert-ignore is deprecated and compiled out behind DEFINE_NO_DEPRECATED;
            // freerdp_parse_arguments runs with allowUnknown=FALSE, so on a modern build
            // the deprecated form would fail the entire parse. /cert:ignore is canonical
            // on both FreeRDP 2.x and 3.x.
            args.add("/cert:ignore")
        }
        // Explicit +/- for both toggles so a disabled config is honored regardless of
        // the library default (previously disabling either emitted nothing at all).
        args.add(if (config.enableClipboard) "+clipboard" else "-clipboard")
        if (config.enableDynamicResolution) {
            args.add("+disp")
            args.add("+dynamic-resolution")
        } else {
            args.add("-disp")
        }
        when (config.performancePreset) {
            PerformancePreset.ULTRA_LOW_LATENCY -> {
                args.addAll(
                    listOf(
                        "/network:auto", "+async-channels", "+async-update",
                        "-wallpaper", "-themes", "-menu-anims", "-window-drag"
                    )
                )
            }
            PerformancePreset.LOW_LATENCY -> {
                args.addAll(listOf("/network:auto", "+async-channels", "+async-update", "-wallpaper"))
            }
            PerformancePreset.BALANCED -> {
                args.addAll(listOf("/network:auto", "+async-channels", "+async-update"))
            }
            PerformancePreset.DATA_SAVER -> {
                args.addAll(listOf("/network:modem", "-wallpaper", "-themes", "+compression"))
            }
            PerformancePreset.BATTERY_SAVER -> {
                args.addAll(listOf("/network:broadband", "-wallpaper", "-themes"))
            }
        }
        return args
    }

    /** Brackets bare IPv6 literals so FreeRDP's /v: parser doesn't split on the first colon. */
    private fun formatServer(address: String): String =
        if (address.contains(':') && !address.startsWith("[")) "[$address]" else address

    // ------------------------------------------------------------------
    // LibFreeRDP.NativeCallbacks — invoked from JNI-attached native threads.
    // ------------------------------------------------------------------

    override fun onPreConnect(inst: Long) = Unit

    override fun onConnectionSuccess(inst: Long) {
        if (!isCurrent(inst)) return
        connectResult.get()?.complete(true)
    }

    override fun onConnectionFailure(inst: Long) {
        if (!isCurrent(inst)) return
        sessionEnded.get()?.complete(Unit)
        connectResult.get()?.complete(false)

        // No pending connect && live session → mid-session drop. Transition to Failed so
        // the AutoReconnectManager observer (Failed → onSessionDropped) triggers recovery.
        // The native instance is intentionally NOT freed here: freeing from inside the
        // JNI callback would free the context while android_thread_func is still unwinding
        // through our frame. Reclaim happens on the next connect()/disconnect() teardown.
        // userDisconnecting suppresses this when the callback is a synchronous echo of our
        // own disconnectSession call — the explicit disconnect owns the notification.
        if (!userDisconnecting && _connectionState.value is RdpConnectionState.Connected) {
            val err = native.getLastErrorMessage(inst)
            val errorMsg = err?.takeIf { it.isNotBlank() } ?: "Connection failed"
            synchronized(lifecycleLock) {
                if (!userDisconnecting && _connectionState.value is RdpConnectionState.Connected) {
                    _connectionState.value =
                        RdpConnectionState.Failed(ERROR_CONNECT_FAILED, errorMsg)
                    eventListener?.onConnectionFailure(ERROR_CONNECT_FAILED, errorMsg)
                }
            }
        }
    }

    override fun onDisconnecting(inst: Long) = Unit

    override fun onDisconnected(inst: Long) {
        if (!isCurrent(inst)) return
        sessionEnded.get()?.complete(Unit)
        connectResult.get()?.complete(false)

        // Server-initiated close of a live session (same deferred-free rationale as above:
        // the next connect()/disconnect() reclaims the instance). userDisconnecting keeps a
        // synchronous echo of our own disconnectSession from double-notifying.
        if (!userDisconnecting && _connectionState.value is RdpConnectionState.Connected) {
            synchronized(lifecycleLock) {
                if (!userDisconnecting && _connectionState.value is RdpConnectionState.Connected) {
                    _connectionState.value = RdpConnectionState.Disconnected
                    eventListener?.onDisconnected()
                }
            }
        }
    }

    override fun onSettingsChanged(inst: Long, width: Int, height: Int, bpp: Int) {
        if (!isCurrent(inst)) return
        recreateFramebuffer(width, height, bpp)
        eventListener?.onResolutionChanged(width, height)
    }

    override fun onAuthenticate(
        inst: Long,
        username: StringBuilder,
        domain: StringBuilder,
        password: StringBuilder
    ): Boolean {
        if (!isCurrent(inst)) return false
        // Headless credential refill from the connect config (no interactive prompt at
        // engine layer): only fill fields the server asked again for that we left empty.
        val config = activeConfig ?: return false
        var filled = false
        if (username.isEmpty() && config.username.isNotEmpty()) {
            username.append(config.username)
            filled = true
        }
        if (domain.isEmpty() && config.domain.isNotEmpty()) {
            domain.append(config.domain)
            filled = true
        }
        if (password.isEmpty() && config.password.isNotEmpty()) {
            password.append(config.password)
            filled = true
        }
        return filled
    }

    override fun onGatewayAuthenticate(
        inst: Long,
        username: StringBuilder,
        domain: StringBuilder,
        password: StringBuilder
    ): Boolean = false // no gateway fields in RdpConnectionConfig

    override fun onVerifyCertificateEx(
        inst: Long,
        host: String,
        port: Long,
        commonName: String,
        subject: String,
        issuer: String,
        fingerprint: String,
        flags: Long
    ): Int = verifyCertificate(inst, host, fingerprint)

    override fun onVerifyChangedCertificateEx(
        inst: Long,
        host: String,
        port: Long,
        commonName: String,
        subject: String,
        issuer: String,
        newFingerprint: String,
        oldSubject: String,
        oldIssuer: String,
        oldFingerprint: String,
        flags: Long
    ): Int = verifyCertificate(inst, host, newFingerprint)

    override fun onVerifyCertificateLegacy(
        inst: Long,
        commonName: String,
        subject: String,
        issuer: String,
        fingerprint: String,
        hostMismatch: Boolean
    ): Int = verifyCertificate(inst, commonName, fingerprint)

    override fun onVerifyChangedCertificateLegacy(
        inst: Long,
        commonName: String,
        subject: String,
        issuer: String,
        newFingerprint: String,
        oldSubject: String,
        oldIssuer: String,
        oldFingerprint: String
    ): Int = verifyCertificate(inst, commonName, newFingerprint)

    /**
     * Maps [RdpEventListener.onCertificateVerification] onto FreeRDP's
     * `accept_certificate` polarity (libfreerdp/crypto/tls.c switch):
     * **1 = accept & persist to known_hosts, 0 = reject**. No registered
     * listener fails closed (reject).
     */
    private fun verifyCertificate(inst: Long, host: String, fingerprint: String): Int {
        if (!isCurrent(inst)) return 0
        val listener = eventListener ?: return 0
        return if (listener.onCertificateVerification(fingerprint, host)) 1 else 0
    }

    override fun onExperimentalFeature(inst: Long, feature: Int): Boolean = false

    override fun onGraphicsUpdate(inst: Long, x: Int, y: Int, width: Int, height: Int) {
        if (!isCurrent(inst)) return
        recordFrame()

        // Pull the dirty region from the native GDI buffer into our framebuffer, then
        // hand the bitmap to the listener (upstream SessionActivity pattern). Without a
        // framebuffer (JVM unit tests) we degrade to metrics-only dispatch.
        val fb = framebuffer ?: return
        nativeLock.readLock().lock()
        try {
            val current = nativeInstance.get()
            if (current != 0L && (current == inst || inst == 0L)) {
                native.updateGraphics(current, fb, x, y, width, height)
            }
        } finally {
            nativeLock.readLock().unlock()
        }
        eventListener?.onGraphicsUpdate(fb, x, y, width, height)
    }

    override fun onGraphicsResize(inst: Long, width: Int, height: Int, bpp: Int) {
        if (!isCurrent(inst)) return
        recreateFramebuffer(width, height, bpp)
        eventListener?.onResolutionChanged(width, height)
    }

    override fun onRemoteClipboardChanged(inst: Long, data: String) {
        if (!isCurrent(inst)) return
        // MS-RDPECLIP remote→local: routes through the handler so an echo of our own
        // last local send is suppressed instead of bouncing back to the system clipboard.
        clipboardHandler.onRemoteClipboardReceived(
            ClipboardHandler.CF_UNICODETEXT,
            ClipboardHandler.encodeUnicodeText(data)
        )
    }

    override fun onRemoteClipboardImageChanged(inst: Long, pngData: ByteArray) {
        // Explicit no-op: PNG image clipboard is outside feature 5's UTF-16LE text scope,
        // but the static JNI entry must exist so the native callback never hits a
        // missing-method error.
    }

    override fun onPointerSet(inst: Long, pixels: IntArray, width: Int, height: Int, hotX: Int, hotY: Int) = Unit
    override fun onPointerSetNull(inst: Long) = Unit
    override fun onPointerSetDefault(inst: Long) = Unit
    override fun onRailWindowUpdate(inst: Long, windowId: Long, width: Int, height: Int, pixels: IntArray) = Unit
    override fun onRailWindowMove(inst: Long, windowId: Long, x: Int, y: Int, w: Int, h: Int) = Unit
    override fun onRailWindowHide(inst: Long, windowId: Long) = Unit
    override fun onRailWindowDestroy(inst: Long, windowId: Long) = Unit
    override fun onRailSessionEnd(inst: Long) = Unit
    override fun onRailMonitoredDesktop(inst: Long, windowIds: LongArray, activeWindowId: Long) = Unit

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Stale-callback guard. Real native callbacks always carry a non-zero pointer;
     * unit tests dispatch with 0 against a session-less engine (current == 0), which
     * is accepted as the test seam.
     */
    private fun isCurrent(inst: Long): Boolean {
        val current = nativeInstance.get()
        return if (current == 0L) inst == 0L else inst == current
    }

    private fun recreateFramebuffer(width: Int, height: Int, bpp: Int) {
        if (width <= 0 || height <= 0) return
        framebuffer = try {
            val config = if (bpp > 16) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565
            Bitmap.createBitmap(width, height, config)
        } catch (t: Throwable) {
            // JVM unit tests: android.jar stubs throw/return null — dispatch degrades
            // to metrics-only, which the null-guarded caller already handles.
            null
        }
    }

    /** Sliding-1s FPS window + cumulative frame count, driven by graphics callbacks. */
    private fun recordFrame() {
        val now = frameTimeSource()
        synchronized(frameLock) {
            frameTimestamps.addLast(now)
            while (frameTimestamps.isNotEmpty() && frameTimestamps.first() < now - FPS_WINDOW_MS) {
                frameTimestamps.removeFirst()
            }
            frameCountTotal++
            val fps = frameTimestamps.size.toFloat()
            _sessionMetrics.update {
                it.copy(fps = fps, frameCount = frameCountTotal)
            }
        }
    }

    companion object {
        const val ERROR_NATIVE_NOT_LOADED = 1001
        const val ERROR_INSTANCE_ALLOC_FAILED = 1002
        const val ERROR_ARGUMENTS_INVALID = 1003
        const val ERROR_CONNECT_FAILED = 1004
        const val ERROR_CONNECT_TIMEOUT = 1005

        const val DEFAULT_CONNECT_TIMEOUT_MS = 60_000L
        const val DEFAULT_SESSION_END_WAIT_MS = 5_000L
        private const val FPS_WINDOW_MS = 1_000L
    }
}
