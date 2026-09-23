package com.freerdp.client.session

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.freerdp.client.settings.AppSettingsRepository
import com.freerdp.core.engine.IRdpEngine
import com.freerdp.core.engine.RdpConnectionConfig
import com.freerdp.core.engine.RdpConnectionState
import com.freerdp.core.engine.RdpEventListener
import com.freerdp.core.protocol.ClipboardHandler
import com.freerdp.feature.mouse.CoordinateTransformer
import com.freerdp.feature.mouse.GestureDisambiguationEngine
import com.freerdp.feature.mouse.GestureEventListener
import com.freerdp.feature.session.CredentialStore
import com.freerdp.feature.session.CredentialStorageType
import com.freerdp.feature.session.KeystoreCredentialStore
import com.freerdp.feature.session.LatchState
import com.freerdp.feature.session.MacroAction
import com.freerdp.feature.session.ModifierKey
import com.freerdp.feature.session.ModifierStateMachine
import com.freerdp.feature.session.ProfileRepository
import com.freerdp.feature.session.QuickActionToolbarFSM
import com.freerdp.feature.session.RdpProfile
import com.freerdp.feature.session.ToolbarAction
import com.freerdp.feature.telemetry.display.DynamicLayoutListener
import com.freerdp.feature.telemetry.metrics.TelemetryCollector
import com.freerdp.feature.telemetry.pacer.FramePacer
import com.freerdp.feature.telemetry.reconnect.AutoReconnectManager
import com.freerdp.feature.telemetry.reconnect.AutoReconnectManagerImpl
import com.freerdp.feature.telemetry.reconnect.ReconnectState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/** A pending trust-on-first-use certificate decision surfaced to the UI. */
data class CertificateRequest(
    val host: String,
    val fingerprint: String,
    internal val decision: CompletableDeferred<Boolean> = CompletableDeferred()
)

/** Session-level observable counters (drives snackbars and is asserted by tests). */
data class SessionStats(
    val clipboardLocalToRemote: Int = 0,
    val clipboardRemoteToLocal: Int = 0
)

/**
 * Application-scoped ViewModel driving the whole remote session product surface:
 * connection lifecycle, certificate TOFU gate, password prompt, auto-reconnect chip,
 * quick-action toolbar (4s auto-collapse), modifier key latching, dynamic resolution
 * (250ms debounce through [DynamicLayoutListener]), single-slot frame pacing and
 * bidirectional clipboard synchronization with echo-loop suppression.
 *
 * It is intentionally constructed with plain dependencies (no Android singletons) so
 * unit tests can drive it with the deterministic MockRdpEngine, real repositories over
 * temp files and virtual coroutine schedulers.
 */
class SessionViewModel(
    private val engine: IRdpEngine,
    private val demoMode: Boolean,
    private val profileRepository: ProfileRepository,
    private val credentialStore: CredentialStore,
    private val settings: AppSettingsRepository,
    val telemetry: TelemetryCollector,
    val framePacer: FramePacer,
    private val clipboard: ClipboardBridge,
    private val networkMonitorFactory: (AutoReconnectManager) -> NetworkMonitor,
    private val gestureEngineFactory: (GestureEventListener) -> GestureDisambiguationEngine =
        { listener -> GestureDisambiguationEngine(listener = listener) },
    private val mouseControllerFactory: (IRdpEngine, CoordinateTransformer, View?) -> HapticMouseController =
        { e, t, v -> HapticMouseController.create(e, t, v) },
    private val scopeOverride: CoroutineScope? = null,
    private val connectDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val reconnectTuning: ReconnectTuning = ReconnectTuning(),
    private val toolbarTimeoutMs: Long = 4000L,
    private val toolbarScopeOverride: CoroutineScope? = null,
    private val toolbarDispatcher: CoroutineDispatcher? = null,
    private val layoutDebounceMs: Long = 250L,
    private val dropSettleDelayMs: Long = 250L,
    private val certificateDecisionTimeoutMs: Long = 25_000L,
    private val initialProfileId: String? = null
) : ViewModel() {

    // ------------------------------------------------------------------ scopes

    private val baseScope: CoroutineScope = scopeOverride ?: viewModelScope
    private val rootJob = SupervisorJob(baseScope.coroutineContext[Job])
    private val sessionScope = CoroutineScope(baseScope.coroutineContext + rootJob)
    private val reconnectScope = CoroutineScope(baseScope.coroutineContext + SupervisorJob(rootJob))

    // ------------------------------------------------------------------- state

    private val _phase = MutableStateFlow<SessionPhase>(SessionPhase.Idle)
    val phase: StateFlow<SessionPhase> = _phase.asStateFlow()

    private val _certificateRequest = MutableStateFlow<CertificateRequest?>(null)
    val certificateRequest: StateFlow<CertificateRequest?> = _certificateRequest.asStateFlow()

    private val _passwordRequired = MutableStateFlow(false)
    val passwordRequired: StateFlow<Boolean> = _passwordRequired.asStateFlow()

    private val _reconnectState = MutableStateFlow<ReconnectState>(ReconnectState.Idle)
    val reconnectState: StateFlow<ReconnectState> = _reconnectState.asStateFlow()

    private val _hudVisible = MutableStateFlow(true)
    val hudVisible: StateFlow<Boolean> = _hudVisible.asStateFlow()

    private val _keyboardVisible = MutableStateFlow(false)
    val keyboardVisible: StateFlow<Boolean> = _keyboardVisible.asStateFlow()

    private val _overlayVisible = MutableStateFlow(true)
    val overlayVisible: StateFlow<Boolean> = _overlayVisible.asStateFlow()

    private val _modifierBarVisible = MutableStateFlow(true)
    val modifierBarVisible: StateFlow<Boolean> = _modifierBarVisible.asStateFlow()

    private val _exitConfirmVisible = MutableStateFlow(false)
    val exitConfirmVisible: StateFlow<Boolean> = _exitConfirmVisible.asStateFlow()

    private val _exitCount = MutableStateFlow(0)
    val exitCount: StateFlow<Int> = _exitCount.asStateFlow()

    private val _userMessage = MutableStateFlow<String?>(null)
    val userMessage: StateFlow<String?> = _userMessage.asStateFlow()

    private val _stats = MutableStateFlow(SessionStats())
    val stats: StateFlow<SessionStats> = _stats.asStateFlow()

    private val _remoteResolution = MutableStateFlow<Pair<Int, Int>?>(null)
    val remoteResolution: StateFlow<Pair<Int, Int>?> = _remoteResolution.asStateFlow()

    private val _zoomRequests = MutableStateFlow(0)
    val zoomRequests: StateFlow<Int> = _zoomRequests.asStateFlow()

    private val _modifierStates = MutableStateFlow<Map<ModifierKey, LatchState>>(emptyMap())
    val modifierStates: StateFlow<Map<ModifierKey, LatchState>> = _modifierStates.asStateFlow()

    val toolbarState: StateFlow<com.freerdp.feature.session.ToolbarState> get() = toolbar.state

    // ------------------------------------------------------------- frame slot

    private val frameLock = Any()
    private var backingBitmap: Bitmap? = null

    /** Latest composited remote frame (single-slot semantics live in [framePacer]). */
    fun currentFrame(): Bitmap? = synchronized(frameLock) { backingBitmap }

    val frameSourceLock: Any get() = frameLock

    // ------------------------------------------------------------ mutable ops

    private var currentProfile: RdpProfile? = null
    private var startedProfileId: String? = initialProfileId
    private var lastConfig: RdpConnectionConfig? = null
    private var hasBeenConnected = false
    private var userExitRequested = false
    private var exiting = false
    private var recoveryCancelled = false
    private var pendingFailure: Pair<Int, String>? = null
    private var modifierJob: Job? = null
    private var lastRecordedRtt = Long.MIN_VALUE

    /** Incremented on every successful connect; used to discard stale disconnect jobs. */
    private var connectionGeneration = 0

    private val phaseLock = Any()

    val isDemo: Boolean get() = demoMode

    var mouseController: HapticMouseController? = null
        private set

    val modifierMachine: ModifierStateMachine
    val layoutListener: DynamicLayoutListener

    // -------------------------------------------------------- toolbar and FSM

    val toolbar = QuickActionToolbarFSM(
        inactivityTimeoutMs = toolbarTimeoutMs,
        coroutineScope = toolbarScopeOverride
            ?: CoroutineScope(SupervisorJob() + Dispatchers.Default),
        dispatcher = toolbarDispatcher ?: Dispatchers.Default
    )

    // ------------------------------------------------- reconnect + network (declared
    // before init so the collectors started there capture initialized instances)

    val reconnectManager = AutoReconnectManagerImpl(
        engine = engine,
        configProvider = { lastConfig },
        coroutineScope = reconnectScope,
        ioDispatcher = connectDispatcher,
        maxAttempts = reconnectTuning.maxAttempts,
        baseDelayMs = reconnectTuning.baseDelayMs,
        maxDelayMs = reconnectTuning.maxDelayMs,
        randomProvider = reconnectTuning.jitter,
        renderLoopDrainAction = { framePacer.reset() }
    )

    val networkMonitor: NetworkMonitor = networkMonitorFactory(reconnectManager)

    // ----------------------------------------------------------- clipboard sync

    private val clipHandler = ClipboardHandler(
        onSendRemoteClipboard = { _, data ->
            engine.sendClipboardText(ClipboardHandler.decodeUnicodeText(data))
        },
        onLocalClipboardUpdate = { text ->
            clipboard.write(text)
        }
    )

    /** Local system clipboard changed: push to remote unless this is our own echo. */
    fun onLocalClipboardChanged(text: String) {
        if (text.isEmpty()) return
        if (clipHandler.onLocalClipboardChanged(text)) {
            _stats.value = _stats.value.copy(clipboardLocalToRemote = _stats.value.clipboardLocalToRemote + 1)
            _userMessage.value = "Clipboard sent to remote desktop"
        }
    }

    // ------------------------------------------------------- engine listener

    private val engineListener = object : RdpEventListener {
        override fun onConnectionSuccess() {
            pendingFailure = null
            if (!hasBeenConnected) hasBeenConnected = true
            // Bumped on every successful (re)connect: pending disconnect-settle jobs from
            // the previous connection generation become stale and must not fire.
            connectionGeneration++
            val phaseNow = _phase.value
            if (phaseNow is SessionPhase.Failed || phaseNow is SessionPhase.Idle) {
                // Reconnects driven by the AutoReconnectManager skip doConnect(), so the
                // phase has to re-enter Connecting before it can become Connected again.
                val host = currentProfile?.hostname ?: lastConfig?.serverAddress ?: "remote"
                postPhase(SessionEvent.ConnectRequested(host))
            }
            postPhase(SessionEvent.ConnectionSuccess(demoMode))
            currentProfile?.let { profile ->
                sessionScope.launch {
                    runCatching {
                        profileRepository.saveProfile(
                            profile.copy(lastConnectedTimestamp = System.currentTimeMillis())
                        )
                    }
                }
            }
        }

        override fun onConnectionFailure(errorCode: Int, message: String) {
            cancelPendingCertificateRequest()
            pendingFailure = errorCode to message
            postPhase(SessionEvent.ConnectionFailed(errorCode, message))
        }

        override fun onDisconnected() {
            cancelPendingCertificateRequest()
            val engineState = engine.connectionState.value
            if (engineState is RdpConnectionState.Failed) {
                pendingFailure = engineState.errorCode to engineState.message
            }
            if (userExitRequested) return
            val generationAtSchedule = connectionGeneration
            // Defer evaluation so an auto-reconnect teardown (whose state transition
            // lands right after the disconnect callback) can be told apart from a
            // genuine server-initiated close.
            sessionScope.launch {
                delay(dropSettleDelayMs)
                processSettledDisconnect(generationAtSchedule)
            }
        }

        override fun onGraphicsUpdate(bitmap: Bitmap, x: Int, y: Int, width: Int, height: Int) {
            compositeFrame(bitmap, x, y, width, height)
            framePacer.onFrameDecoded(currentFrame() ?: return)
        }

        override fun onResolutionChanged(width: Int, height: Int) {
            if (width > 0 && height > 0) {
                _remoteResolution.value = width to height
                synchronized(frameLock) { resizeBackingLocked(width, height) }
            }
        }

        override fun onClipboardDataReceived(format: Int, data: ByteArray) {
            if (clipHandler.onRemoteClipboardReceived(format, data)) {
                _stats.value = _stats.value.copy(clipboardRemoteToLocal = _stats.value.clipboardRemoteToLocal + 1)
                _userMessage.value = "Clipboard received from remote desktop"
            }
        }

        override fun onCertificateVerification(fingerprint: String, host: String): Boolean {
            if (settings.isCertificateTrusted(host, fingerprint)) return true
            val request = CertificateRequest(host, fingerprint)
            _certificateRequest.value = request
            val decision = try {
                runBlocking {
                    withTimeoutOrNull(certificateDecisionTimeoutMs) { request.decision.await() } ?: false
                }
            } catch (t: Throwable) {
                false
            }
            _certificateRequest.value = null
            if (decision) settings.trustCertificate(host, fingerprint)
            return decision
        }
    }

    /** Completes the pending TOFU decision with false to unblock any blocked native thread. */
    fun cancelPendingCertificateRequest() {
        val request = _certificateRequest.value ?: return
        request.decision.complete(false)
        _certificateRequest.value = null
    }

    /** Explicit disconnect request; ensures pending certificate requests are cancelled. */
    fun disconnect() {
        cancelPendingCertificateRequest()
        confirmExit()
    }

    /** Completes the pending TOFU decision; called by the certificate dialog. */
    fun respondCertificate(trust: Boolean) {
        val request = _certificateRequest.value ?: return
        request.decision.complete(trust)
        if (!trust) {
            _userMessage.value = "Certificate rejected"
        }
    }

    // ------------------------------------------------------------ phase helper

    private fun postPhase(event: SessionEvent) {
        synchronized(phaseLock) {
            _phase.value = _phase.value.reduce(event)
        }
        telemetry.updateConnectionState(phaseLabel(_phase.value))
    }

    private fun phaseLabel(p: SessionPhase): String = when (p) {
        SessionPhase.Idle -> "Disconnected"
        is SessionPhase.Connecting -> "Connecting"
        is SessionPhase.Connected -> if (demoMode) "Connected (demo)" else "Connected"
        is SessionPhase.Failed -> "Failed (${p.code})"
    }

    private val autoReconnectEnabled: Boolean
        get() = currentProfile?.networkConfig?.autoReconnect ?: true

    private fun processSettledDisconnect(generationAtSchedule: Int) {
        if (userExitRequested) return
        if (generationAtSchedule != connectionGeneration) {
            // A newer connection was established while this job was waiting out the
            // settle delay — the disconnect it observed belongs to the past.
            return
        }
        // Session_logic contract: collapse the quick-action toolbar deterministically
        // once a disconnect has settled (pin does not block it; pin preference kept).
        toolbar.onSessionLost()
        val engineState = engine.connectionState.value
        if (engineState is RdpConnectionState.Failed) {
            pendingFailure = engineState.errorCode to engineState.message
        }
        val rec = _reconnectState.value

        // --- phase -------------------------------------------------------
        when {
            pendingFailure != null -> {
                val (code, message) = pendingFailure!!
                postPhase(SessionEvent.ConnectionFailed(code, message))
            }
            _phase.value is SessionPhase.Connected -> {
                postPhase(
                    SessionEvent.ConnectionFailed(
                        SessionErrorCodes.REMOTE_CLOSED,
                        "Remote host closed the connection"
                    )
                )
            }
            rec is ReconnectState.Failed && _phase.value !is SessionPhase.Failed -> {
                postPhase(
                    SessionEvent.ConnectionFailed(
                        SessionErrorCodes.SESSION_DROPPED,
                        rec.reason.ifBlank { "Unable to reconnect" }
                    )
                )
            }
        }

        // --- recovery ------------------------------------------------------
        // The AutoReconnectManagerImpl observes engine failures itself; calling it a
        // second time would double-count attempts. We only cover the "server closed
        // cleanly while connected" case that the manager cannot observe.
        val recoveryEngaged = rec !is ReconnectState.Connected && rec !is ReconnectState.Idle
        val managerOwnsFailure = engineState is RdpConnectionState.Failed
        val shouldAutoReconnect =
            autoReconnectEnabled &&
                hasBeenConnected &&
                !userExitRequested &&
                !recoveryCancelled &&
                !recoveryEngaged &&
                !managerOwnsFailure &&
                _phase.value is SessionPhase.Failed

        if (shouldAutoReconnect) {
            reconnectManager.onSessionDropped(pendingFailure?.second ?: "Connection closed")
        }
    }

    // ------------------------------------------------------------- reconnect --

    private fun onReconnectStateChange(state: ReconnectState) {
        _reconnectState.value = state
        when (state) {
            is ReconnectState.Reconnecting, is ReconnectState.WaitingForNetwork ->
                telemetry.updateConnectionState("Reconnecting")
            is ReconnectState.Failed ->
                if (state.exhausted) _userMessage.value = "Automatic reconnection failed"
            else -> Unit
        }
        val recoveryPending = state is ReconnectState.Reconnecting || state is ReconnectState.WaitingForNetwork
        if (recoveryPending && !autoReconnectEnabled) {
            // The profile opted out: cancel the machine's built-in retry loop once.
            recoveryCancelled = true
            reconnectManager.cancelReconnect()
            _userMessage.value = "Auto-reconnect is disabled for this profile"
        }
    }

    // -------------------------------------------------------------- modifiers

    init {
        modifierMachine = ModifierStateMachine(engine)
        _hudVisible.value = settings.current.hudEnabled

        engine.setEventListener(engineListener)

        layoutListener = DynamicLayoutListener(
            engine = engine,
            coroutineScope = sessionScope,
            debounceDelayMs = layoutDebounceMs
        )

        modifierJob = sessionScope.launch {
            modifierMachine.statesFlow.collect { _modifierStates.value = it }
        }
        sessionScope.launch {
            reconnectManager.reconnectState.collect { onReconnectStateChange(it) }
        }
        sessionScope.launch {
            toolbar.actionEvents.collect { onToolbarAction(it) }
        }
        sessionScope.launch {
            engine.sessionMetrics.collect { metrics ->
                if (metrics.rttMs != lastRecordedRtt) {
                    lastRecordedRtt = metrics.rttMs
                    telemetry.recordRtt(metrics.rttMs)
                }
            }
        }
    }

    // ------------------------------------------------------------- entrypoint

    private var phaseKnown = false

    /** Idempotent: loads the profile, possibly prompts for a password, then connects. */
    fun ensureStarted(profileId: String? = startedProfileId) {
        if (userExitRequested) return
        if (phaseKnown && startedProfileId == profileId) return
        startedProfileId = profileId
        phaseKnown = true
        recoveryCancelled = false
        pendingFailure = null
        telemetry.reset()
        lastRecordedRtt = Long.MIN_VALUE
        telemetry.recordRtt(engine.sessionMetrics.value.rttMs)
        sessionScope.launch { loadAndConnect(profileId) }
    }

    private suspend fun loadAndConnect(profileId: String?) {
        val profile = profileId?.let {
            runCatching { profileRepository.getProfile(it) }.getOrNull()
        }
        if (profile == null) {
            postPhase(SessionEvent.ConnectRequested("profile"))
            postPhase(
                SessionEvent.ConnectionFailed(
                    SessionErrorCodes.PROFILE_MISSING,
                    "Connection profile not found"
                )
            )
            return
        }
        currentProfile = profile
        startClipboardListening()
        if (profile.networkConfig.autoReconnect) {
            networkMonitor.start()
        }

        val needsPasswordPrompt =
            profile.credentialStorageType != CredentialStorageType.NONE &&
                credentialStore.getSecret(profile.id) == null

        postPhase(SessionEvent.ConnectRequested(profile.hostname))
        if (needsPasswordPrompt) {
            _passwordRequired.value = true
            return
        }
        val stored = credentialStore.getSecret(profile.id)
        val password = stored?.concatToString() ?: ""
        stored?.let { KeystoreCredentialStore.wipeSecret(it) }
        doConnect(profile, password)
    }

    /** User answered the password prompt ([null] cancels and surfaces a retryable failure). */
    fun respondPassword(password: String?) {
        _passwordRequired.value = false
        val profile = currentProfile
        if (password == null) {
            postPhase(
                SessionEvent.ConnectionFailed(
                    SessionErrorCodes.PASSWORD_REQUIRED,
                    "Password required to connect"
                )
            )
            return
        }
        if (profile == null) return
        if (profile.credentialStorageType == CredentialStorageType.KEYSTORE_ENCRYPTED && password.isNotEmpty()) {
            val chars = password.toCharArray()
            runCatching { credentialStore.saveSecret(profile.id, chars) }
            KeystoreCredentialStore.wipeSecret(chars)
        }
        doConnect(profile, password)
    }

    private fun doConnect(profile: RdpProfile, password: String) {
        lastConfig = buildConnectionConfig(profile, password)
        postPhase(SessionEvent.ConnectRequested(profile.hostname))
        sessionScope.launch(connectDispatcher) {
            runCatching { engine.connect(lastConfig!!) }
        }
    }

    fun buildConnectionConfig(profile: RdpProfile, password: String): RdpConnectionConfig {
        val base = profile.toConnectionConfig(password)
        val preset = if (settings.current.presetOverridesProfiles) {
            settings.current.defaultPerformancePreset
        } else {
            profile.performancePreset
        }
        return base.copy(performancePreset = preset)
    }

    /** Manual retry from the failure screen: full reload of profile + password flow. */
    fun retry() {
        cancelPendingCertificateRequest()
        recoveryCancelled = false
        pendingFailure = null
        phaseKnown = false
        ensureStarted(startedProfileId)
    }

    // ---------------------------------------------------------------- exit ---

    fun requestExit() {
        _exitConfirmVisible.value = true
    }

    /**
     * User pressed Cancel on the reconnect chip: stop the machine AND arm the settle
     * guard so the disconnect caused by the cancellation cannot re-trigger a retry.
     */
    fun cancelReconnect() {
        cancelPendingCertificateRequest()
        recoveryCancelled = true
        reconnectManager.cancelReconnect()
        _userMessage.value = "Automatic reconnection cancelled"
    }

    fun dismissExitConfirm() {
        _exitConfirmVisible.value = false
    }

    fun confirmExit() {
        if (exiting) return
        exiting = true
        userExitRequested = true
        recoveryCancelled = true
        _exitConfirmVisible.value = false
        cancelPendingCertificateRequest()
        toolbar.onSessionLost() // the session is over — collapse controls deterministically
        sessionScope.launch {
            runCatching { reconnectManager.cancelReconnect() }
            networkMonitor.stop()
            clipboard.stopListening()
            runCatching { modifierMachine.resetAll() }
            mouseController?.releaseButtons()
            runCatching { engine.disconnect() }
            postPhase(SessionEvent.UserExit)
            _exitCount.value = _exitCount.value + 1
        }
    }

    /** Called by the container when the session UI is permanently dismissed. */
    fun terminate() {
        cancelPendingCertificateRequest()
        networkMonitor.stop()
        clipboard.stopListening()
        engine.setEventListener(null)
        toolbar.onSessionLost()
        // Leak guard (latency_perf handoff §4): the reconnect manager's engine-state
        // observer runs in a component-owned scope that rootJob.cancel() cannot reach —
        // shutdown() is the only way to end it. Idempotent.
        reconnectManager.shutdown()
        rootJob.cancel()
    }

    override fun onCleared() {
        terminate()
        super.onCleared()
    }

    // ------------------------------------------------------------ start/stop clipboard

    fun startClipboardListening() {
        clipboard.startListening { text -> onLocalClipboardChanged(text) }
    }

    // -------------------------------------------------------------- toolbar ---

    fun toggleToolbar() = toolbar.toggle()

    fun onToolbarTouched() = toolbar.onTouch()

    fun toolbarAction(action: ToolbarAction) = toolbar.triggerAction(action)

    /** Toolbar "keep open" pin state (QuickActionToolbarFSM.pin()/unpin()). */
    val toolbarPinned: StateFlow<Boolean> get() = toolbar.pinned

    /** Toggles the keep-open pin: pinned toolbars never auto-collapse. */
    fun toggleToolbarPin() {
        if (toolbar.isPinned) toolbar.unpin() else toolbar.pin()
    }

    /** Session-default touchpad mode from Settings (applied by the floating overlay). */
    fun touchpadDefault(): Boolean = settings.current.touchpadDefault

    private fun onToolbarAction(action: ToolbarAction) {
        when (action) {
            ToolbarAction.DISCONNECT -> requestExit()
            ToolbarAction.TOGGLE_KEYBOARD -> _keyboardVisible.value = !_keyboardVisible.value
            ToolbarAction.TOGGLE_MOUSE_OVERLAY -> _overlayVisible.value = !_overlayVisible.value
            ToolbarAction.SWITCH_RESOLUTION -> _zoomRequests.value = _zoomRequests.value + 1
            ToolbarAction.TOGGLE_TELEMETRY_HUD -> {
                _hudVisible.value = !_hudVisible.value
                settings.setHudEnabled(_hudVisible.value)
            }
            ToolbarAction.TOGGLE_MODIFIER_BAR -> _modifierBarVisible.value = !_modifierBarVisible.value
        }
    }

    // ------------------------------------------------------------- modifiers ---

    fun onModifierTapped(key: ModifierKey) {
        modifierMachine.onModifierKeyTapped(key)
    }

    fun onMacro(macro: MacroAction) {
        modifierMachine.triggerMacro(macro)
    }

    fun onKeyboardText(text: String) {
        text.forEach { modifierMachine.onNonModifierKeyPressed(it) }
    }

    fun onKeyboardBackspace() {
        modifierMachine.onSpecialKeyTapped(ModifierKey.BACKSPACE)
    }

    // --------------------------------------------------------- layout / res ---

    fun onViewportSizeChanged(widthPx: Int, heightPx: Int, densityDpi: Int, orientation: Int) {
        layoutListener.onWindowSizeChanged(
            widthPx = widthPx,
            heightPx = heightPx,
            densityDpi = densityDpi,
            orientation = orientation
        )
    }

    // ------------------------------------------------------------- factories ---

    fun createGestureEngine(listener: GestureEventListener): GestureDisambiguationEngine =
        gestureEngineFactory(listener)

    fun createMouseController(transformer: CoordinateTransformer, hapticTarget: View?): HapticMouseController {
        return mouseControllerFactory(engine, transformer, hapticTarget).also { mouseController = it }
    }

    // --------------------------------------------------------------- messages ---

    fun clearUserMessage() {
        _userMessage.value = null
    }

    fun requestZoomToggle() {
        _zoomRequests.value = _zoomRequests.value + 1
    }

    /** Called by the canvas after it blits a paced frame (feeds the FPS HUD). */
    fun onFrameBlitted() {
        telemetry.recordFrameDelivered()
    }

    // -------------------------------------------------------- frame compositing

    private fun compositeFrame(tile: Bitmap, x: Int, y: Int, width: Int, height: Int) {
        synchronized(frameLock) {
            ensureBackingLocked(x + width, y + height)
            val backing = backingBitmap ?: return
            val canvas = Canvas(backing)
            val srcX = if (tile.width > width || tile.height > height) x else 0
            val srcY = if (tile.height > height || tile.width > width) y else 0
            val srcW = minOf(width, tile.width - srcX)
            val srcH = minOf(height, tile.height - srcY)
            if (srcW <= 0 || srcH <= 0) return
            canvas.drawBitmap(
                tile,
                Rect(srcX, srcY, srcX + srcW, srcY + srcH),
                Rect(x, y, x + srcW, y + srcH),
                null
            )
        }
    }

    private fun ensureBackingLocked(requiredWidth: Int, requiredHeight: Int) {
        val current = backingBitmap
        if (current != null && current.width >= requiredWidth && current.height >= requiredHeight) return
        val (rw, rh) = _remoteResolution.value ?: (0 to 0)
        val newW = maxOf(requiredWidth, current?.width ?: 0, rw)
        val newH = maxOf(requiredHeight, current?.height ?: 0, rh)
        resizeBackingLocked(newW, newH)
    }

    private fun resizeBackingLocked(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val current = backingBitmap
        if (current != null && current.width == width && current.height == height) return
        val next = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        next.eraseColor(Color.BLACK)
        if (current != null) {
            val canvas = Canvas(next)
            val copyW = minOf(current.width, width)
            val copyH = minOf(current.height, height)
            canvas.drawBitmap(
                current,
                Rect(0, 0, copyW, copyH),
                RectF(0f, 0f, copyW.toFloat(), copyH.toFloat()),
                null
            )
        }
        backingBitmap = next
    }
}
