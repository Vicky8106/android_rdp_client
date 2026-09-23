package com.freerdp.client.ui.session

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.window.Dialog
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.freerdp.client.session.CertificateRequest
import com.freerdp.client.session.SessionErrorCodes
import com.freerdp.client.session.SessionViewModel
import com.freerdp.client.session.SessionPhase
import com.freerdp.client.ui.components.touchTarget
import com.freerdp.feature.mouse.FloatingMouseOverlayView
import com.freerdp.feature.mouse.SafeInsets
import com.freerdp.feature.session.LatchState
import com.freerdp.feature.session.MacroAction
import com.freerdp.feature.session.ModifierKey
import com.freerdp.feature.session.ToolbarAction
import com.freerdp.feature.session.ToolbarState
import com.freerdp.feature.telemetry.metrics.DiagnosticHudModel
import com.freerdp.feature.telemetry.reconnect.ReconnectState

/**
 * The product's core screen: remote canvas, floating mouse overlay, collapsible
 * quick-action toolbar (4s auto-collapse), modifier bar, telemetry HUD,
 * auto-reconnect chip with cancel, certificate/password dialogs, connecting and
 * failure states with retry, confirm-exit — all bottom-anchored for one-handed use.
 */
@Composable
fun SessionScreen(
    vm: SessionViewModel,
    profileId: String,
    onExitConfirmed: () -> Unit,
    onEnableDemoEngineAndRetry: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainView = LocalView.current
    val snackbarHostState = remember { SnackbarHostState() }

    val phase by vm.phase.collectAsState()
    val certRequest by vm.certificateRequest.collectAsState()
    val passwordRequired by vm.passwordRequired.collectAsState()
    val reconnect by vm.reconnectState.collectAsState()
    val hudVisible by vm.hudVisible.collectAsState()
    val hud by vm.telemetry.hudState.collectAsState()
    val keyboardVisible by vm.keyboardVisible.collectAsState()
    val overlayVisible by vm.overlayVisible.collectAsState()
    val modifierBarVisible by vm.modifierBarVisible.collectAsState()
    val toolbarState by vm.toolbarState.collectAsState()
    val toolbarPinned by vm.toolbarPinned.collectAsState()
    val exitConfirmVisible by vm.exitConfirmVisible.collectAsState()
    val exitCount by vm.exitCount.collectAsState()
    val userMessage by vm.userMessage.collectAsState()
    val zoomRequests by vm.zoomRequests.collectAsState()
    val remoteResolution by vm.remoteResolution.collectAsState()
    val modifierStates by vm.modifierStates.collectAsState()

    var canvasView by remember { mutableStateOf<RemoteCanvasView?>(null) }
    var overlayView by remember { mutableStateOf<FloatingMouseOverlayView?>(null) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var zoomPercent by remember { mutableIntStateOf(100) }

    val overlayPrefs = remember {
        context.getSharedPreferences("floating_mouse_overlay_prefs", Context.MODE_PRIVATE)
    }

    val isConnected = phase is SessionPhase.Connected
    val isConnecting = phase is SessionPhase.Connecting
    val isFailed = phase is SessionPhase.Failed
    val recoveryActive = reconnect is ReconnectState.Reconnecting ||
        reconnect is ReconnectState.WaitingForNetwork ||
        reconnect is ReconnectState.Suspended
    val showControls = isConnected || recoveryActive

    // --- start / stop the session -----------------------------------------
    LaunchedEffect(Unit) { vm.ensureStarted(profileId) }

    // --- rotation & split-screen -> MS-RDPEDISP (250ms debounce in the VM) ---
    val orientation = configuration.orientation
    val densityDpi = remember(configuration) { context.resources.displayMetrics.densityDpi }
    LaunchedEffect(viewportSize, orientation) {
        if (viewportSize.width > 0 && viewportSize.height > 0) {
            vm.onViewportSizeChanged(viewportSize.width, viewportSize.height, densityDpi, orientation)
        }
    }

    // --- toolbar fit/1:1 command -------------------------------------------
    LaunchedEffect(zoomRequests) {
        if (zoomRequests > 0) canvasView?.toggleFitOrActualPixels()
    }

    // --- transient snackbar messages ---------------------------------------
    LaunchedEffect(userMessage) {
        userMessage?.let {
            snackbarHostState.showSnackbar(it)
            vm.clearUserMessage()
        }
    }

    // --- exit signal --------------------------------------------------------
    LaunchedEffect(exitCount) {
        if (exitCount > 0) onExitConfirmed()
    }

    // --- app sleep/wake feeds the auto-reconnect state machine --------------
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE ->
                    if (vm.phase.value is SessionPhase.Connected) vm.reconnectManager.onUserPause()
                Lifecycle.Event.ON_RESUME -> vm.reconnectManager.onUserResume()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // --- persist the floating overlay position across rotation -------------
    DisposableEffect(Unit) {
        onDispose { overlayView?.savePosition(overlayPrefs) }
    }

    // --- system back = confirm exit ----------------------------------------
    BackHandler(enabled = phase !is SessionPhase.Idle) { vm.requestExit() }

    // System bar insets for the floating overlay's safe-area clamping.
    val barsPadding = WindowInsets.systemBars.asPaddingValues()
    val safeInsets = SafeInsets(
        left = with(density) { barsPadding.calculateLeftPadding(LayoutDirection.Ltr).roundToPx() },
        top = with(density) { barsPadding.calculateTopPadding().roundToPx() },
        right = with(density) { barsPadding.calculateRightPadding(LayoutDirection.Ltr).roundToPx() },
        bottom = with(density) { barsPadding.calculateBottomPadding().roundToPx() }
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { size -> viewportSize = size }
    ) {
        // ------------------------------------------------ remote canvas -----
        AndroidView(
            factory = { ctx ->
                RemoteCanvasView(ctx).also { view ->
                    view.bindPacer(vm.framePacer)
                    view.frameProvider = { vm.currentFrame() }
                    view.frameLock = vm.frameSourceLock
                    view.onFrameBlitted = { vm.onFrameBlitted() }
                    view.gestureEngine = vm.createGestureEngine(view.gestureListener)
                    view.mouseController = vm.createMouseController(view.transformer, view)
                    view.setZoomCallback { scale -> zoomPercent = (scale * 100).toInt() }
                    canvasView = view
                }
            },
            update = { view ->
                val res = remoteResolution
                if (res != null && (view.transformer.remoteWidth != res.first || view.transformer.remoteHeight != res.second)) {
                    view.transformer.setRemoteResolution(res.first, res.second)
                    view.resetViewportToFit()
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // ---------------------------------------- floating mouse overlay ----
        if (overlayVisible) {
            AndroidView(
                factory = { ctx ->
                    FloatingMouseOverlayView(ctx).also { overlay ->
                        overlay.restorePosition(overlayPrefs)
                        overlay.mouseController = vm.mouseController ?: canvasView?.let {
                            vm.createMouseController(it.transformer, it)
                        }
                        overlay.setSafeInsets(safeInsets)
                        overlay.bubbleView.contentDescription =
                            "Floating mouse controls — drag to reposition, tap to expand"
                        overlay.btnLeftClick.contentDescription = "Left mouse button"
                        overlay.btnRightClick.contentDescription = "Right mouse button"
                        overlay.btnDragToggle.contentDescription = "Toggle click-and-drag mode"
                        overlay.btnScrollUp.contentDescription = "Scroll up"
                        overlay.btnScrollDown.contentDescription = "Scroll down"
                        overlay.btnTouchpadToggle.contentDescription = "Toggle touchpad mode"
                        overlay.btnCursorToggle.contentDescription = "Toggle on-screen cursor"
                        overlay.btnCollapse.contentDescription = "Collapse mouse controls"
                        if (vm.touchpadDefault() && !overlay.isTouchpadActive) {
                            // Aligns the overlay's own toggle state with the Settings default.
                            overlay.btnTouchpadToggle.performClick()
                        }
                        overlayView = overlay
                    }
                },
                update = { overlay ->
                    if (overlay.safeInsets != safeInsets) {
                        overlay.setSafeInsets(safeInsets)
                    }
                    if (overlay.mouseController == null) {
                        overlay.mouseController = vm.mouseController ?: canvasView?.let {
                            vm.createMouseController(it.transformer, it)
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // ------------------------------------------------- top status -------
        Column(
            Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ConnectionChip(phase = phase, demo = vm.isDemo)
                Spacer(Modifier.weight(1f, fill = true))
                if (isConnected) {
                    Surface(
                        onClick = { canvasView?.resetViewportToFit() },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                        modifier = Modifier.semantics {
                            contentDescription = "Zoom $zoomPercent percent, tap to fit to screen"
                        }
                    ) {
                        Text(
                            "$zoomPercent%",
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }
            }

            if (hudVisible && isConnected) {
                HudCard(hud = hud)
            }

            if (recoveryActive) {
                ReconnectChip(
                    state = reconnect,
                    maxAttempts = 5,
                    onCancel = vm::cancelReconnect
                )
            }
        }

        // -------------------------------------- connected placeholders ------
        if (isConnected && remoteResolution == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (vm.isDemo) {
                        "Demo session established.\n\nThe mock engine streams no desktop image — " +
                            "packaged native builds render the real remote desktop here."
                    } else {
                        "Connected — waiting for the first desktop image…"
                    },
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(32.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }

        // ------------------------------------------- connecting scrim -------
        if (isConnecting) {
            Surface(color = Color.Black.copy(alpha = 0.88f), modifier = Modifier.fillMaxSize()) {
                Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(24.dp))
                    Text(
                        "Connecting to ${phaseHost(phase)}…",
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White
                    )
                    Text(
                        "Negotiating secure session",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    Spacer(Modifier.height(32.dp))
                    OutlinedButton(
                        onClick = vm::confirmExit,
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text("Cancel connection", color = Color.White)
                    }
                }
            }
        }

        // ---------------------------------------------- failure card --------
        if (isFailed && !recoveryActive) {
            val failure = phase as SessionPhase.Failed
            Surface(color = Color.Black.copy(alpha = 0.88f), modifier = Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Column(
                        Modifier.widthIn(max = 420.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            Icons.Default.Error,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(56.dp)
                        )
                        Text("Connection failed", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                        Text(
                            "Error ${failure.code} — ${failure.message}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            color = Color.White.copy(alpha = 0.85f)
                        )
                        failure.friendlyHint?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                        if (failure.code == SessionErrorCodes.NATIVE_UNAVAILABLE &&
                            !vm.isDemo && onEnableDemoEngineAndRetry != null
                        ) {
                            // First-run dead-end fix: error 1001 means the native FreeRDP
                            // .so is not packaged in this build. One tap flips the Demo
                            // engine switch (persisted) and retries the connect on the
                            // engine that works here — the hint above stays as guidance.
                            Button(
                                onClick = onEnableDemoEngineAndRetry,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .touchTarget()
                            ) {
                                Text("Enable demo engine & retry")
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = vm::retry,
                            modifier = Modifier
                                .fillMaxWidth()
                                .touchTarget()
                        ) {
                            Text("Retry")
                        }
                        OutlinedButton(
                            onClick = vm::confirmExit,
                            modifier = Modifier
                                .fillMaxWidth()
                                .touchTarget()
                        ) {
                            Text("Back to profiles", color = Color.White)
                        }
                    }
                }
            }
        }

        // ------------------------------------------ bottom controls ---------
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (showControls) {
                if (keyboardVisible) {
                    RemoteKeyboardField(vm = vm)
                }
                if (modifierBarVisible) {
                    ModifierBar(
                        states = modifierStates,
                        onModifierTapped = { key ->
                            if (mainView.isHapticFeedbackEnabled) {
                                mainView.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                            }
                            vm.onModifierTapped(key)
                        },
                        onMacro = vm::onMacro
                    )
                }
                QuickToolbar(
                    state = toolbarState,
                    pinned = toolbarPinned,
                    onToggle = vm::toggleToolbar,
                    onTogglePin = vm::toggleToolbarPin,
                    onAction = vm::toolbarAction,
                    onTouch = vm::onToolbarTouched
                )
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(
                    bottom = if (showControls) 200.dp else 24.dp,
                    start = 16.dp,
                    end = 16.dp
                )
        )

        // --------------------------------------------- dialogs --------------
        if (passwordRequired) {
            PasswordDialog(
                onConfirm = { text -> vm.respondPassword(text) },
                onCancel = { vm.respondPassword(null) }
            )
        }

        certRequest?.let { request ->
            CertificateDialog(
                request = request,
                onTrust = { vm.respondCertificate(trust = true) },
                onReject = { vm.respondCertificate(trust = false) }
            )
        }

        if (exitConfirmVisible) {
            ExitDialog(
                host = phaseHost(phase),
                onConfirm = vm::confirmExit,
                onDismiss = vm::dismissExitConfirm
            )
        }
    }
}

private fun phaseHost(phase: SessionPhase): String = when (phase) {
    is SessionPhase.Connecting -> phase.host
    is SessionPhase.Connected -> phase.host
    else -> "remote desktop"
}

// --------------------------------------------------------------- status chip

@Composable
private fun ConnectionChip(phase: SessionPhase, demo: Boolean) {
    val (containerColor, label) = when {
        phase is SessionPhase.Connected ->
            MaterialTheme.colorScheme.secondaryContainer to (phase.host + if (demo) " · DEMO" else "")
        phase is SessionPhase.Connecting ->
            MaterialTheme.colorScheme.surfaceVariant to "Connecting…"
        phase is SessionPhase.Failed ->
            MaterialTheme.colorScheme.errorContainer to "Disconnected"
        else -> MaterialTheme.colorScheme.surfaceVariant to "Session"
    }
    Surface(shape = CircleShape, color = containerColor) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (phase is SessionPhase.Connecting) {
                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
            }
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun ReconnectChip(
    state: ReconnectState,
    maxAttempts: Int,
    onCancel: () -> Unit
) {
    val (text, spinning) = when (state) {
        is ReconnectState.Reconnecting ->
            "Reconnecting (attempt ${state.attempt} of $maxAttempts)…" to true
        is ReconnectState.WaitingForNetwork -> "Waiting for network…" to true
        is ReconnectState.Suspended -> "Reconnect paused" to false
        else -> "" to false
    }
    if (text.isEmpty()) return
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.tertiaryContainer) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
        ) {
            if (spinning) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp))
            }
            Text(
                text,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.padding(end = 4.dp)
            )
            IconButton(
                onClick = onCancel,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Cancel automatic reconnection",
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

// ------------------------------------------------------------------- HUD ----

@Composable
private fun HudCard(hud: DiagnosticHudModel) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        modifier = Modifier.semantics { contentDescription = "Telemetry: ${hud.hudText}" }
    ) {
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HudValue("FPS", "%.0f".format(hud.fps))
                HudValue("RTT", "${hud.rttMs} ms")
                HudValue("JIT", "%.1f ms".format(hud.jitterMs))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HudValue("NET", "${hud.bandwidthKbps} kbps")
                HudValue("DROP", "${hud.droppedFrames}")
                HudValue("Q", hud.quality.name)
            }
        }
    }
}

@Composable
private fun HudValue(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            fontFamily = FontFamily.Monospace
        )
        Text(
            value,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace
        )
    }
}

// ------------------------------------------------------------- modifier bar -

@Composable
private fun ModifierBar(
    states: Map<ModifierKey, LatchState>,
    onModifierTapped: (ModifierKey) -> Unit,
    onMacro: (MacroAction) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        val latchable = listOf(
            ModifierKey.CTRL to "Ctrl",
            ModifierKey.ALT to "Alt",
            ModifierKey.WIN to "Win",
            ModifierKey.ESC to "Esc"
        )
        latchable.forEach { (key, label) ->
            ModifierKeyButton(label = label, state = states[key] ?: LatchState.INACTIVE) {
                onModifierTapped(key)
            }
        }
        Spacer(Modifier.size(8.dp))
        val functionKeys = listOf(
            ModifierKey.F1, ModifierKey.F2, ModifierKey.F3, ModifierKey.F4,
            ModifierKey.F5, ModifierKey.F6, ModifierKey.F7, ModifierKey.F8,
            ModifierKey.F9, ModifierKey.F10, ModifierKey.F11, ModifierKey.F12
        )
        functionKeys.forEachIndexed { index, key ->
            ModifierKeyButton(label = "F${index + 1}", state = states[key] ?: LatchState.INACTIVE) {
                onModifierTapped(key)
            }
        }
        Spacer(Modifier.size(8.dp))
        MacroButton(label = "Ctrl+Alt+Del") { onMacro(MacroAction.CTRL_ALT_DEL) }
        MacroButton(label = "Alt+Tab") { onMacro(MacroAction.ALT_TAB) }
        MacroButton(label = "Ctrl+C") { onMacro(MacroAction.CTRL_C) }
        MacroButton(label = "Ctrl+V") { onMacro(MacroAction.CTRL_V) }
    }
}

@Composable
private fun ModifierKeyButton(label: String, state: LatchState, onClick: () -> Unit) {
    val (color, contentColor) = when (state) {
        LatchState.LOCKED -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.onPrimary
        LatchState.LATCHED -> MaterialTheme.colorScheme.tertiaryContainer to
            MaterialTheme.colorScheme.onTertiaryContainer
        LatchState.INACTIVE -> MaterialTheme.colorScheme.surfaceVariant to
            MaterialTheme.colorScheme.onSurfaceVariant
    }
    val stateWord = when (state) {
        LatchState.LOCKED -> "locked"
        LatchState.LATCHED -> "latched"
        LatchState.INACTIVE -> "off"
    }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = color,
        contentColor = contentColor,
        modifier = Modifier
            .height(48.dp)
            .semantics { contentDescription = "$label key, $stateWord" }
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            if (state == LatchState.LOCKED) {
                Text(" ▪", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun MacroButton(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier
            .height(48.dp)
            .semantics { contentDescription = "Send $label shortcut" }
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

// ------------------------------------------------------------ quick toolbar -

@Composable
private fun QuickToolbar(
    state: ToolbarState,
    pinned: Boolean,
    onToggle: () -> Unit,
    onTogglePin: () -> Unit,
    onAction: (ToolbarAction) -> Unit,
    onTouch: () -> Unit
) {
    when (state) {
        ToolbarState.COLLAPSED -> {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                FilledIconButton(
                    onClick = onToggle,
                    modifier = Modifier
                        .size(56.dp)
                        .semantics { contentDescription = "Show session controls" }
                ) {
                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = null)
                }
            }
        }

        ToolbarState.EXPANDED -> {
            Row(
                Modifier
                    .fillMaxWidth()
                    .pointerInput(state) {
                        detectTapGestures(onPress = { onTouch() })
                    }
                    .padding(4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ToolbarIconButton(Icons.Default.PowerSettingsNew, "Disconnect") {
                    onAction(ToolbarAction.DISCONNECT)
                }
                ToolbarIconButton(Icons.Default.Keyboard, "Toggle remote keyboard") {
                    onAction(ToolbarAction.TOGGLE_KEYBOARD)
                }
                ToolbarIconButton(Icons.Default.Mouse, "Toggle mouse overlay") {
                    onAction(ToolbarAction.TOGGLE_MOUSE_OVERLAY)
                }
                ToolbarIconButton(Icons.Default.FitScreen, "Toggle fit / actual pixels") {
                    onAction(ToolbarAction.SWITCH_RESOLUTION)
                }
                ToolbarIconButton(Icons.Default.Speed, "Toggle telemetry HUD") {
                    onAction(ToolbarAction.TOGGLE_TELEMETRY_HUD)
                }
                FilledTonalIconButton(
                    onClick = { onAction(ToolbarAction.TOGGLE_MODIFIER_BAR) },
                    modifier = Modifier
                        .size(56.dp)
                        .semantics { contentDescription = "Toggle modifier key bar" }
                ) {
                    Text("Ctrl", style = MaterialTheme.typography.labelMedium)
                }
                FilledTonalIconButton(
                    onClick = onTogglePin,
                    modifier = Modifier
                        .size(56.dp)
                        .semantics {
                            contentDescription = if (pinned) {
                                "Stop keeping session controls open"
                            } else {
                                "Keep session controls open"
                            }
                        }
                ) {
                    Icon(
                        Icons.Default.PushPin,
                        contentDescription = null,
                        modifier = Modifier.size(26.dp),
                        tint = if (pinned) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                FilledIconButton(
                    onClick = onToggle,
                    modifier = Modifier
                        .size(56.dp)
                        .semantics { contentDescription = "Hide session controls" }
                ) {
                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = null)
                }
            }
        }
    }
}

@Composable
private fun ToolbarIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit
) {
    FilledTonalIconButton(
        onClick = onClick,
        modifier = Modifier
            .size(56.dp)
            .semantics { contentDescription = description }
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(26.dp))
    }
}

// ---------------------------------------------------------------- keyboard --

@Composable
private fun RemoteKeyboardField(vm: SessionViewModel) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = "",
                onValueChange = { text -> vm.onKeyboardText(text) },
                placeholder = { Text("Type on the remote desktop…") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .weight(1f)
                    .onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyUp &&
                            (event.key == Key.Backspace || event.key == Key.Delete)
                        ) {
                            vm.onKeyboardBackspace()
                            true
                        } else {
                            false
                        }
                    }
            )
            IconButton(
                onClick = { vm.toolbarAction(ToolbarAction.TOGGLE_KEYBOARD) },
                modifier = Modifier.size(56.dp)
            ) {
                Icon(Icons.Default.Close, contentDescription = "Hide remote keyboard")
            }
        }
    }
}

// ---------------------------------------------------------------- dialogs ---

@Composable
private fun PasswordDialog(
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit
) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Password required") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Enter the password for this profile to continue connecting.")
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Password
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(password) }, modifier = Modifier.touchTarget()) {
                Text("Connect")
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, modifier = Modifier.touchTarget()) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun CertificateDialog(
    request: CertificateRequest,
    onTrust: () -> Unit,
    onReject: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onReject,
        title = { Text("Verify server certificate") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("First connection to ${request.host}. Check the fingerprint with your " +
                    "administrator, then choose whether to trust it.")
                Text(
                    request.fingerprint,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Trusting stores this fingerprint on the device (trust on first use); " +
                        "you can revoke it later in Settings → Trusted certificates.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            Button(onClick = onTrust, modifier = Modifier.touchTarget()) {
                Text("Trust & connect")
            }
        },
        dismissButton = {
            TextButton(onClick = onReject, modifier = Modifier.touchTarget()) {
                Text("Reject", color = MaterialTheme.colorScheme.error)
            }
        }
    )
}

@Composable
private fun ExitDialog(
    host: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Disconnect?") },
        text = { Text("The session with $host will end and you'll return to your profiles.") },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.touchTarget()) {
                Text("Disconnect", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.touchTarget()) {
                Text("Stay connected")
            }
        }
    )
}
