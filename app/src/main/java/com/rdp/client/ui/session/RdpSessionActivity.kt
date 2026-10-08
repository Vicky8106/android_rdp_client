package com.rdp.client.ui.session

import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rdp.client.databinding.ActivityRdpSessionBinding
import com.rdp.client.freerdp.*
import com.rdp.client.model.AppDatabase
import com.rdp.client.model.GestureStyle
import com.rdp.client.model.ServerProfile
import com.rdp.client.model.ViewMode
import com.rdp.client.repository.ProfileRepository
import com.rdp.client.ui.session.compose.VirtualKeysOverlay
import com.rdp.client.ui.session.compose.VirtualMouseOverlay
import com.rdp.client.ui.session.input.TouchDispatcher
import com.rdp.client.ui.session.viewport.DisplayResizeManager
import com.rdp.client.utils.KeyPacer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Production Remote Desktop Session Activity (Milestone 4).
 * Coordinates Viewport FrameView, Jetpack Compose floating overlays,
 * Zero-Scrim Collapsible Toolbar Drawer, CLIPRDR clipboard synchronization,
 * and guaranteed modifier cleanup.
 */
class RdpSessionActivity : AppCompatActivity(), ToolbarDrawerController.Callbacks, RdpSessionListener {

    companion object {
        const val EXTRA_PROFILE_ID = RdpSessionContract.EXTRA_PROFILE_ID
        const val EXTRA_QUICK_CONNECT = RdpSessionContract.EXTRA_QUICK_CONNECT
        const val EXTRA_TRANSIENT_PROFILE = RdpSessionContract.EXTRA_TRANSIENT_PROFILE
    }

    private lateinit var binding: ActivityRdpSessionBinding
    private lateinit var drawerController: ToolbarDrawerController
    private lateinit var clipboardSyncManager: RdpClipboardSyncManager
    private var displayResizeManager: DisplayResizeManager? = null
    lateinit var touchDispatcher: TouchDispatcher
        private set

    // FreeRDP Session & Input Subsystems
    var rdpSession: RdpSession? = null
        private set
    lateinit var modifierState: ModifierState
        private set
    lateinit var keyPacer: KeyPacer
        private set

    // Active Profile & Session Parameters
    var activeProfile: ServerProfile? = null
        private set
    private var isQuickConnect: Boolean = false

    // Reactive UI States
    private val _isVirtualKeysVisible = MutableStateFlow(false)
    val isVirtualKeysVisible: StateFlow<Boolean> = _isVirtualKeysVisible.asStateFlow()

    private val _isVirtualMouseVisible = MutableStateFlow(false)
    val isVirtualMouseVisible: StateFlow<Boolean> = _isVirtualMouseVisible.asStateFlow()

    private var currentViewMode = ViewMode.NORMAL
    private var currentGestureStyle = GestureStyle.AUTO
    private var hasConnectedOnce = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRdpSessionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupImmersiveWindow()
        initializeInputEngine()
        setupDrawer()
        setupClipboardSync()
        setupStatusOverlay()
        setupBackPressHandler()

        loadProfileAndConnect()
    }

    private fun setupImmersiveWindow() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun initializeInputEngine() {
        // Calibrated modifier state machine
        modifierState = ModifierState { scancode, isDown ->
            val inst = rdpSession?.instanceId ?: 0L
            if (inst != 0L && (currentViewMode != ViewMode.VIEW_ONLY || !isDown)) {
                LibFreeRDP.sendKeyEvent(inst, scancode.code, scancode.isExtended, isDown)
            }
        }.apply {
            isInputEnabledProvider = { currentViewMode != ViewMode.VIEW_ONLY }
        }

        // Calibrated 18ms keydown / 22ms inter-key pacer
        keyPacer = KeyPacer(instanceProvider = {
            if (currentViewMode == ViewMode.VIEW_ONLY) 0L else (rdpSession?.instanceId ?: 0L)
        })

        // Initialize touch dispatcher bound to FrameView
        touchDispatcher = TouchDispatcher(
            context = this,
            instanceProvider = { rdpSession?.instanceId ?: 0L },
            transformer = binding.frameView,
            gestureStyle = GestureStyle.AUTO,
            buttonUpDelayEnabled = false
        ).apply {
            isInputEnabledProvider = { currentViewMode != ViewMode.VIEW_ONLY }
        }
        binding.frameView.setOnTouchListener(touchDispatcher)
        binding.frameView.setOnGenericMotionListener(touchDispatcher)

        setupComposeOverlays()
    }

    private fun setupComposeOverlays() {
        binding.composeOverlayContainer.setContent {
            val isKeysVisible by isVirtualKeysVisible.collectAsState()
            val isMouseVisible by isVirtualMouseVisible.collectAsState()

            Box(modifier = Modifier.fillMaxSize()) {
                VirtualMouseOverlay(
                    isVisible = isMouseVisible,
                    onButtonClick = { btn ->
                        if (currentViewMode == ViewMode.VIEW_ONLY) return@VirtualMouseOverlay
                        val x = touchDispatcher.virtualCursor.x.toInt()
                        val y = touchDispatcher.virtualCursor.y.toInt()
                        touchDispatcher.sendCursorEvent(x, y, RdpPointerFlags.encodeButtonDown(btn))
                        lifecycleScope.launch {
                            delay(20)
                            touchDispatcher.sendCursorEvent(x, y, RdpPointerFlags.encodeButtonUp(btn))
                        }
                    },
                    onButtonDown = { btn ->
                        if (currentViewMode == ViewMode.VIEW_ONLY) return@VirtualMouseOverlay
                        val x = touchDispatcher.virtualCursor.x.toInt()
                        val y = touchDispatcher.virtualCursor.y.toInt()
                        touchDispatcher.sendButtonDown(btn, x, y)
                    },
                    onButtonUp = { btn ->
                        if (currentViewMode == ViewMode.VIEW_ONLY) return@VirtualMouseOverlay
                        val x = touchDispatcher.virtualCursor.x.toInt()
                        val y = touchDispatcher.virtualCursor.y.toInt()
                        touchDispatcher.sendButtonUp(btn, x, y)
                    },
                    onScroll = { dir ->
                        if (currentViewMode == ViewMode.VIEW_ONLY) return@VirtualMouseOverlay
                        val x = touchDispatcher.virtualCursor.x.toInt()
                        val y = touchDispatcher.virtualCursor.y.toInt()
                        touchDispatcher.sendCursorEvent(x, y, RdpPointerFlags.encodeVerticalScroll(dir, 1))
                    }
                )

                VirtualKeysOverlay(
                    isVisible = isKeysVisible,
                    keyPacer = keyPacer,
                    modifierState = modifierState,
                    onScroll = { dir ->
                        val x = touchDispatcher.virtualCursor.x.toInt()
                        val y = touchDispatcher.virtualCursor.y.toInt()
                        touchDispatcher.sendCursorEvent(x, y, RdpPointerFlags.encodeVerticalScroll(dir, 1))
                    },
                    onToggleKeyboard = { onToggleIme() },
                    onToggleVirtualMouse = { _isVirtualMouseVisible.value = !_isVirtualMouseVisible.value },
                    onDismiss = { _isVirtualKeysVisible.value = false }
                )
            }
        }
    }

    private fun setupDrawer() {
        drawerController = ToolbarDrawerController(
            binding = binding.toolbarDrawerLayout,
            callbacks = this
        )
    }

    private fun setupClipboardSync() {
        clipboardSyncManager = RdpClipboardSyncManager(
            context = this,
            instanceProvider = { rdpSession?.instanceId ?: 0L },
            isSyncEnabledProvider = { activeProfile?.clipboardSync == true }
        )
    }

    private fun setupStatusOverlay() {
        binding.statusOverlayLayout.btnStatusCancel.setOnClickListener {
            rdpSession?.disconnect()
            finish()
        }

        binding.statusOverlayLayout.btnStatusRetry.setOnClickListener {
            binding.statusOverlayLayout.statusErrorIcon.visibility = View.GONE
            binding.statusOverlayLayout.statusSpinner.visibility = View.VISIBLE
            binding.statusOverlayLayout.btnStatusRetry.visibility = View.GONE
            binding.statusOverlayLayout.statusTitle.text = "Reconnecting..."
            rdpSession?.connect()
        }
    }

    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    drawerController.isExpanded -> drawerController.collapse()
                    _isVirtualKeysVisible.value -> _isVirtualKeysVisible.value = false
                    _isVirtualMouseVisible.value -> _isVirtualMouseVisible.value = false
                    else -> showDisconnectConfirmationDialog()
                }
            }
        })
    }

    private fun loadProfileAndConnect() {
        val profileId = intent.getLongExtra(EXTRA_PROFILE_ID, 0L)
        isQuickConnect = intent.getBooleanExtra(EXTRA_QUICK_CONNECT, false)

        lifecycleScope.launch {
            val profile = if (isQuickConnect) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_TRANSIENT_PROFILE, ServerProfile::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_TRANSIENT_PROFILE)
                }
            } else if (profileId > 0L) {
                withContext(Dispatchers.IO) {
                    val dao = AppDatabase.getInstance(this@RdpSessionActivity).serverProfileDao()
                    ProfileRepository(dao).getProfileById(profileId)
                }
            } else null

            if (profile == null) {
                binding.statusOverlayLayout.statusTitle.text = "Session Error"
                binding.statusOverlayLayout.statusSubtitle.text = "Invalid or missing server profile parameters"
                binding.statusOverlayLayout.statusSpinner.visibility = View.GONE
                binding.statusOverlayLayout.statusErrorIcon.visibility = View.VISIBLE
                return@launch
            }

            activeProfile = profile
            touchDispatcher.gestureStyle = profile.gestureStyle
            touchDispatcher.buttonUpDelayEnabled = profile.buttonUpDelay
            displayResizeManager = DisplayResizeManager(binding.frameView, profile)

            drawerController.setSessionDetails(profile.name, "${profile.customWidth}x${profile.customHeight}")
            startSession(profile)
        }
    }

    private fun startSession(profile: ServerProfile) {
        val params = RdpConnectionParameters.fromProfile(profile)
        val session = RdpSession(context = this, parameters = params)
        rdpSession = session

        binding.frameView.bindSession(session)

        // Observe session state transitions
        lifecycleScope.launch {
            session.state.collect { state ->
                onSessionStateChanged(state)
            }
        }

        binding.statusOverlayLayout.statusTitle.text = "Connecting to ${profile.host}:${profile.port}"
        binding.statusOverlayLayout.statusSubtitle.text = "Negotiating security handshake..."
        session.connect()
    }

    private fun onSessionStateChanged(state: RdpSessionState) {
        when (state) {
            RdpSessionState.CONNECTING -> {
                binding.statusOverlayLayout.statusOverlayRoot.visibility = View.VISIBLE
                binding.statusOverlayLayout.statusSpinner.visibility = View.VISIBLE
                binding.statusOverlayLayout.statusErrorIcon.visibility = View.GONE
                binding.statusOverlayLayout.btnStatusRetry.visibility = View.GONE
            }
            RdpSessionState.CONNECTED -> {
                hasConnectedOnce = true
                binding.statusOverlayLayout.statusOverlayRoot.visibility = View.GONE
                activeProfile?.let { prof ->
                    binding.frameView.setDesktopResolution(prof.customWidth, prof.customHeight)
                }
            }
            RdpSessionState.ERROR -> {
                binding.statusOverlayLayout.statusOverlayRoot.visibility = View.VISIBLE
                binding.statusOverlayLayout.statusSpinner.visibility = View.GONE
                binding.statusOverlayLayout.statusErrorIcon.visibility = View.VISIBLE
                binding.statusOverlayLayout.statusTitle.text = "Connection Failed"
                binding.statusOverlayLayout.statusSubtitle.text =
                    rdpSession?.lastErrorMessage ?: "An unexpected protocol error occurred."
                binding.statusOverlayLayout.btnStatusRetry.visibility = View.VISIBLE
            }
            RdpSessionState.DISCONNECTED -> {
                if (hasConnectedOnce && !isFinishing) finish()
            }
            else -> {}
        }
    }

    // -------------------------------------------------------------------------
    // ToolbarDrawerController.Callbacks
    // -------------------------------------------------------------------------

    override fun onToggleIme() {
        val insetsController = WindowInsetsControllerCompat(window, binding.frameView)
        val isKeyboardVisible = ViewCompat.getRootWindowInsets(binding.frameView)
            ?.isVisible(WindowInsetsCompat.Type.ime()) == true

        if (isKeyboardVisible) {
            insetsController.hide(WindowInsetsCompat.Type.ime())
        } else {
            binding.frameView.requestFocus()
            insetsController.show(WindowInsetsCompat.Type.ime())
        }
    }

    override fun onToggleVirtualKeys() {
        _isVirtualKeysVisible.value = !_isVirtualKeysVisible.value
    }

    override fun onToggleVirtualMouse() {
        _isVirtualMouseVisible.value = !_isVirtualMouseVisible.value
    }

    override fun onZoomLockToggled(isLocked: Boolean) {
        binding.frameView.isZoomLocked = isLocked
    }

    override fun onZoomReset() {
        binding.frameView.resetZoom()
    }

    override fun onZoom100() {
        binding.frameView.setZoom100()
    }

    override fun onMacroCtrlAltDel() {
        val inst = rdpSession?.instanceId ?: return
        if (inst == 0L || currentViewMode == ViewMode.VIEW_ONLY) return

        lifecycleScope.launch(Dispatchers.Default) {
            // Sequence down
            LibFreeRDP.sendKeyEvent(inst, 0x1D, extended = false, down = true) // Ctrl
            delay(KeyPacer.KEYDOWN_DURATION_MS)
            LibFreeRDP.sendKeyEvent(inst, 0x38, extended = false, down = true) // Alt
            delay(KeyPacer.KEYDOWN_DURATION_MS)
            LibFreeRDP.sendKeyEvent(inst, 0x53, extended = true, down = true)  // Del
            delay(KeyPacer.KEYDOWN_DURATION_MS)

            // Sequence up (reverse)
            LibFreeRDP.sendKeyEvent(inst, 0x53, extended = true, down = false)
            delay(KeyPacer.INTER_KEY_PACING_MS)
            LibFreeRDP.sendKeyEvent(inst, 0x38, extended = false, down = false)
            delay(KeyPacer.INTER_KEY_PACING_MS)
            LibFreeRDP.sendKeyEvent(inst, 0x1D, extended = false, down = false)
        }
    }

    override fun onMacroWinKey() {
        val inst = rdpSession?.instanceId ?: return
        if (inst == 0L || currentViewMode == ViewMode.VIEW_ONLY) return

        lifecycleScope.launch(Dispatchers.Default) {
            LibFreeRDP.sendKeyEvent(inst, 0x5B, extended = true, down = true)
            delay(KeyPacer.KEYDOWN_DURATION_MS)
            LibFreeRDP.sendKeyEvent(inst, 0x5B, extended = true, down = false)
        }
    }

    override fun onDisconnectRequested() {
        showDisconnectConfirmationDialog()
    }

    override fun onViewModeChanged(mode: ViewMode) {
        currentViewMode = mode
        if (mode == ViewMode.VIEW_ONLY && ::modifierState.isInitialized) {
            modifierState.releaseAllModifiers()
        }
        when (mode) {
            ViewMode.NORMAL, ViewMode.VIEW_ONLY -> {
                binding.frameView.isRenderingPaused = false
            }
            ViewMode.BACKGROUND -> {
                binding.frameView.isRenderingPaused = true
            }
        }
    }

    override fun onGestureStyleChanged(style: GestureStyle) {
        currentGestureStyle = style
        touchDispatcher.gestureStyle = style
        binding.frameView.gestureStyle = style
    }

    private fun showDisconnectConfirmationDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Disconnect Session")
            .setMessage("Are you sure you want to disconnect from ${activeProfile?.name ?: "this host"}?")
            .setPositiveButton("Disconnect") { _, _ ->
                rdpSession?.disconnect()
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // -------------------------------------------------------------------------
    // RdpSessionListener Reverse Callbacks
    // -------------------------------------------------------------------------

    override fun onConnectionSuccess(instance: Long) {}
    override fun onConnectionFailure(instance: Long, errorCode: Int, message: String) {}
    override fun onDisconnected(instance: Long) {}
    override fun onGraphicsUpdate(instance: Long, x: Int, y: Int, width: Int, height: Int) {}

    override fun onRemoteClipboardChanged(instance: Long, text: String) {
        clipboardSyncManager.onRemoteClipboardReceived(text)
    }

    // -------------------------------------------------------------------------
    // Android Activity Lifecycle & Guaranteed Modifier Safety
    // -------------------------------------------------------------------------

    override fun onResume() {
        super.onResume()
        setupImmersiveWindow()
        clipboardSyncManager.start()
    }

    override fun onPause() {
        super.onPause()
        // CRITICAL MODIFIER SAFETY: Release all latched/held modifiers immediately
        if (::modifierState.isInitialized) {
            modifierState.releaseAllModifiers()
        }
        if (::keyPacer.isInitialized) {
            keyPacer.cancelAndReleaseHeld()
        }
        if (::touchDispatcher.isInitialized) {
            touchDispatcher.releaseAllButtons()
        }

        // Release any held mouse pointers
        val inst = rdpSession?.instanceId ?: 0L
        if (inst != 0L) {
            LibFreeRDP.sendCursorEvent(inst, 0, 0, RdpPointerFlags.PTRFLAGS_BUTTON1) // Button up
        }

        if (::clipboardSyncManager.isInitialized) {
            clipboardSyncManager.stop()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        binding.frameView.onConfigurationChanged(newConfig)
        displayResizeManager?.onConfigurationChanged(newConfig, rdpSession)
        if (::drawerController.isInitialized) {
            drawerController.collapse(animated = false)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::modifierState.isInitialized) {
            modifierState.releaseAllModifiers()
        }
        if (::keyPacer.isInitialized) {
            keyPacer.cancelAndReleaseHeld()
        }
        if (::touchDispatcher.isInitialized) {
            touchDispatcher.releaseAllButtons()
        }
        if (::clipboardSyncManager.isInitialized) {
            clipboardSyncManager.stop()
        }
        if (::drawerController.isInitialized) {
            drawerController.destroy()
        }
        rdpSession?.disconnect()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (::drawerController.isInitialized && drawerController.onInterceptRootTouchEvent(ev)) {
            return true
        }
        return super.dispatchTouchEvent(ev)
    }
}
