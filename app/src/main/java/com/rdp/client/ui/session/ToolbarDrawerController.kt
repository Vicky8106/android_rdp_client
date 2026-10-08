package com.rdp.client.ui.session

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.VelocityTracker
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.rdp.client.R
import com.rdp.client.databinding.LayoutToolbarDrawerBinding
import com.rdp.client.model.GestureStyle
import com.rdp.client.model.ViewMode

/**
 * Controller managing the in-session top collapsible toolbar drawer.
 * Guarantees zero-scrim transparency, kinetic pull-tab dragging, top-edge swipe opening,
 * and calibrated auto-collapse behavior.
 */
class ToolbarDrawerController(
    private val binding: LayoutToolbarDrawerBinding,
    private val callbacks: Callbacks
) {

    interface Callbacks {
        fun onToggleIme()
        fun onToggleVirtualKeys()
        fun onToggleVirtualMouse()
        fun onZoomLockToggled(isLocked: Boolean)
        fun onZoomReset()
        fun onZoom100()
        fun onMacroCtrlAltDel()
        fun onMacroWinKey()
        fun onDisconnectRequested()
        fun onViewModeChanged(mode: ViewMode)
        fun onGestureStyleChanged(style: GestureStyle)
    }

    enum class DrawerState {
        COLLAPSED,
        DRAGGING,
        ANIMATING,
        EXPANDED
    }

    companion object {
        const val AUTO_COLLAPSE_TIMEOUT_MS = 5000L
        const val ANIMATION_DURATION_MS = 250L
        const val FLING_VELOCITY_THRESHOLD = 1000f // px/s
        const val TOP_EDGE_SWIPE_THRESHOLD_DP = 48f
    }

    var state: DrawerState = DrawerState.COLLAPSED
        private set

    val isExpanded: Boolean get() = state == DrawerState.EXPANDED

    private var contentHeight: Int = 0
    private var initialTranslationY: Float = 0f
    private var touchStartY: Float = 0f
    private var velocityTracker: VelocityTracker? = null
    private var currentAnimator: ValueAnimator? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val autoCollapseRunnable = Runnable { collapse() }

    private var isZoomLocked = false

    init {
        setupLayoutMeasurements()
        setupListeners()
        setupTouchHandling()
    }

    private fun setupLayoutMeasurements() {
        binding.drawerCard.post {
            contentHeight = binding.drawerCard.height
            if (contentHeight > 0) {
                // Initialize to collapsed
                applyTranslation(-contentHeight.toFloat())
                state = DrawerState.COLLAPSED
            }
        }
    }

    private fun setupListeners() {
        // Close button
        binding.btnCloseDrawer.setOnClickListener {
            collapse()
        }

        // Toggles
        binding.btnToggleIme.setOnClickListener {
            resetAutoCollapseTimer()
            callbacks.onToggleIme()
        }

        binding.btnToggleVirtualKeys.setOnClickListener {
            resetAutoCollapseTimer()
            callbacks.onToggleVirtualKeys()
        }

        binding.btnToggleVirtualMouse.setOnClickListener {
            resetAutoCollapseTimer()
            callbacks.onToggleVirtualMouse()
        }

        // Zoom Controls
        binding.btnZoomLock.setOnClickListener {
            resetAutoCollapseTimer()
            isZoomLocked = !isZoomLocked
            updateZoomLockUi()
            callbacks.onZoomLockToggled(isZoomLocked)
        }

        binding.btnZoomReset.setOnClickListener {
            resetAutoCollapseTimer()
            callbacks.onZoomReset()
        }

        binding.btnZoom100.setOnClickListener {
            resetAutoCollapseTimer()
            callbacks.onZoom100()
        }

        // Macros
        binding.btnMacroCad.setOnClickListener {
            callbacks.onMacroCtrlAltDel()
            scheduleQuickCollapse()
        }

        binding.btnMacroWin.setOnClickListener {
            callbacks.onMacroWinKey()
            scheduleQuickCollapse()
        }

        // Disconnect
        binding.btnDisconnect.setOnClickListener {
            callbacks.onDisconnectRequested()
        }

        // View Mode Segmented Buttons
        binding.toggleGroupViewMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            resetAutoCollapseTimer()
            val mode = when (checkedId) {
                binding.btnModeNormal.id -> ViewMode.NORMAL
                binding.btnModeNoInput.id -> ViewMode.VIEW_ONLY
                binding.btnModeNoVideo.id -> ViewMode.BACKGROUND
                else -> ViewMode.NORMAL
            }
            callbacks.onViewModeChanged(mode)
        }
        binding.toggleGroupViewMode.check(binding.btnModeNormal.id)

        // Gesture Style Segmented Buttons
        binding.toggleGroupGestureStyle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            resetAutoCollapseTimer()
            val style = when (checkedId) {
                binding.btnGestureAuto.id -> GestureStyle.AUTO
                binding.btnGestureTouchscreen.id -> GestureStyle.TOUCHSCREEN
                binding.btnGestureTouchpad.id -> GestureStyle.TOUCHPAD
                else -> GestureStyle.AUTO
            }
            callbacks.onGestureStyleChanged(style)
        }
        binding.toggleGroupGestureStyle.check(binding.btnGestureAuto.id)
    }

    private fun updateZoomLockUi() {
        binding.btnZoomLock.text = if (isZoomLocked) "Unlock Zoom" else "Lock Zoom"
        binding.btnZoomLock.setIconResource(
            if (isZoomLocked) R.drawable.ic_lock
            else R.drawable.ic_lock
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchHandling() {
        // Tab handle dragging
        binding.drawerTabHandle.setOnTouchListener { _, event ->
            handleDragTouchEvent(event)
        }

        // Card touches reset auto-collapse
        binding.drawerCard.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                resetAutoCollapseTimer()
            }
            false
        }
    }

    /**
     * Intercepts touch events from the session root to detect top-edge pull-downs.
     */
    fun onInterceptRootTouchEvent(event: MotionEvent): Boolean {
        if (state == DrawerState.EXPANDED) return false
        val density = binding.root.resources.displayMetrics.density
        val topThresholdPx = TOP_EDGE_SWIPE_THRESHOLD_DP * density

        return if (event.action == MotionEvent.ACTION_DOWN && event.rawY <= topThresholdPx) {
            handleDragTouchEvent(event)
            true
        } else false
    }

    private fun handleDragTouchEvent(event: MotionEvent): Boolean {
        if (contentHeight <= 0) {
            contentHeight = binding.drawerCard.height
            if (contentHeight <= 0) return false
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelAutoCollapseTimer()
                touchStartY = event.rawY
                initialTranslationY = binding.drawerCard.translationY
                velocityTracker = VelocityTracker.obtain()
                velocityTracker?.addMovement(event)
                state = DrawerState.DRAGGING
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                val dy = event.rawY - touchStartY
                val newTranslation = (initialTranslationY + dy).coerceIn(-contentHeight.toFloat(), 0f)
                applyTranslation(newTranslation)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velocityTracker?.addMovement(event)
                velocityTracker?.computeCurrentVelocity(1000)
                val vy = velocityTracker?.yVelocity ?: 0f
                velocityTracker?.recycle()
                velocityTracker = null

                if (vy > FLING_VELOCITY_THRESHOLD) {
                    expand()
                } else if (vy < -FLING_VELOCITY_THRESHOLD) {
                    collapse()
                } else {
                    val currentY = binding.drawerCard.translationY
                    if (currentY > -contentHeight / 2f) {
                        expand()
                    } else {
                        collapse()
                    }
                }
                return true
            }
        }
        return false
    }

    private fun applyTranslation(translationY: Float) {
        binding.drawerCard.translationY = translationY
        // Anchor the handle tab right beneath the bottom of the card
        binding.drawerTabHandle.translationY = (contentHeight + translationY).coerceAtLeast(0f)
    }

    fun expand(animated: Boolean = true) {
        currentAnimator?.cancel()
        currentAnimator = null
        if (contentHeight <= 0) contentHeight = binding.drawerCard.height
        cancelAutoCollapseTimer()

        if (!animated || contentHeight <= 0) {
            applyTranslation(0f)
            state = DrawerState.EXPANDED
            resetAutoCollapseTimer()
            return
        }

        animateTo(0f) {
            state = DrawerState.EXPANDED
            resetAutoCollapseTimer()
        }
    }

    fun collapse(animated: Boolean = true) {
        currentAnimator?.cancel()
        currentAnimator = null
        if (contentHeight <= 0) contentHeight = binding.drawerCard.height
        cancelAutoCollapseTimer()

        val targetY = -contentHeight.toFloat()
        if (!animated || contentHeight <= 0) {
            applyTranslation(targetY)
            state = DrawerState.COLLAPSED
            return
        }

        animateTo(targetY) {
            state = DrawerState.COLLAPSED
        }
    }

    fun toggle() {
        if (isExpanded) collapse() else expand()
    }

    private fun animateTo(targetY: Float, onEnd: () -> Unit) {
        currentAnimator?.cancel()
        state = DrawerState.ANIMATING
        val currentY = binding.drawerCard.translationY
        currentAnimator = ValueAnimator.ofFloat(currentY, targetY).apply {
            duration = ANIMATION_DURATION_MS
            interpolator = FastOutSlowInInterpolator()
            addUpdateListener { animator ->
                applyTranslation(animator.animatedValue as Float)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    currentAnimator = null
                    onEnd()
                }
            })
            start()
        }
    }

    fun setSessionDetails(title: String, resolution: String) {
        binding.tvSessionTitle.text = title
        binding.tvSessionResolution.text = resolution
    }

    fun resetAutoCollapseTimer() {
        cancelAutoCollapseTimer()
        if (state == DrawerState.EXPANDED) {
            mainHandler.postDelayed(autoCollapseRunnable, AUTO_COLLAPSE_TIMEOUT_MS)
        }
    }

    private fun scheduleQuickCollapse() {
        cancelAutoCollapseTimer()
        mainHandler.postDelayed(autoCollapseRunnable, 400L)
    }

    fun cancelAutoCollapseTimer() {
        mainHandler.removeCallbacks(autoCollapseRunnable)
    }

    fun destroy() {
        currentAnimator?.cancel()
        currentAnimator = null
        cancelAutoCollapseTimer()
        velocityTracker?.recycle()
        velocityTracker = null
    }
}
