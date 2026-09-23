package com.freerdp.feature.mouse

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PointF
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.hypot
import kotlin.math.max

enum class OverlayState {
    COLLAPSED,
    EXPANDED,
    DRAGGING
}

/**
 * Floating Mouse Overlay View component.
 * Supports Collapsed (floating bubble), Expanded (control dock/palette), and Dragging states.
 * Adapts across screen orientations using normalized coordinates and clamps within SafeInsets.
 */
class FloatingMouseOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    var state: OverlayState = OverlayState.COLLAPSED
        private set

    var currentCoordinates: OverlayCoordinates = OverlayCoordinates()
        private set

    var safeInsets: SafeInsets = SafeInsets()
        private set

    var mouseController: MouseController? = null

    // Dimensions in pixels (default 48dp bubble)
    private val density = context.resources.displayMetrics.density
    val bubbleSizePx: Int = (48 * density).toInt()

    // Overlay Views
    val bubbleView: FrameLayout = FrameLayout(context)
    val paletteView: LinearLayout = LinearLayout(context)

    // Interactive buttons
    val btnLeftClick: Button = Button(context)
    val btnRightClick: Button = Button(context)
    val btnDragToggle: Button = Button(context)
    val btnScrollUp: Button = Button(context)
    val btnScrollDown: Button = Button(context)
    val btnTouchpadToggle: Button = Button(context)
    val btnCursorToggle: Button = Button(context)
    val btnCollapse: Button = Button(context)

    var isDragLocked: Boolean = false
        private set
    var isTouchpadActive: Boolean = false
        private set
    var isCursorActive: Boolean = false
        private set

    // Touch tracking for bubble dragging
    private var touchDownX: Float = 0f
    private var touchDownY: Float = 0f
    private var initialOverlayX: Float = 0f
    private var initialOverlayY: Float = 0f
    private var isDraggingOverlay: Boolean = false
    private val touchSlop = 8 * density

    // Current position in screen pixels
    var currentScreenX: Float = 0f
        private set
    var currentScreenY: Float = 0f
        private set

    init {
        setupBubbleView()
        setupPaletteView()

        addView(bubbleView)
        // Explicit wrap-content: FrameLayout's default child params are MATCH_PARENT,
        // which made the expanded palette a full-screen panel covering the remote
        // desktop instead of a compact button cluster at the saved position.
        addView(paletteView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        setState(OverlayState.COLLAPSED)
    }

    private fun setupBubbleView() {
        val params = LayoutParams(bubbleSizePx, bubbleSizePx)
        bubbleView.layoutParams = params
        bubbleView.setBackgroundColor(Color.argb(200, 33, 150, 243)) // Semi-transparent blue

        val label = TextView(context).apply {
            text = "🖱"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        }
        bubbleView.addView(label, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        setupBubbleTouch()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupBubbleTouch() {
        bubbleView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchDownX = event.rawX
                    touchDownY = event.rawY
                    initialOverlayX = currentScreenX
                    initialOverlayY = currentScreenY
                    isDraggingOverlay = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchDownX
                    val dy = event.rawY - touchDownY
                    if (!isDraggingOverlay && hypot(dx, dy) > touchSlop) {
                        isDraggingOverlay = true
                        setState(OverlayState.DRAGGING)
                    }

                    if (isDraggingOverlay) {
                        val targetX = initialOverlayX + dx
                        val targetY = initialOverlayY + dy
                        updatePosition(targetX, targetY, clampOnly = true)
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (isDraggingOverlay) {
                        // Snap to nearest safe edge on release
                        snapToEdgeAndSettle()
                        setState(OverlayState.COLLAPSED)
                    } else {
                        // Stationary tap on bubble: expand palette
                        setState(OverlayState.EXPANDED)
                    }
                    isDraggingOverlay = false
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    if (isDraggingOverlay) {
                        snapToEdgeAndSettle()
                    }
                    setState(OverlayState.COLLAPSED)
                    isDraggingOverlay = false
                    true
                }

                else -> false
            }
        }
    }

    private fun setupPaletteView() {
        paletteView.orientation = LinearLayout.VERTICAL
        paletteView.setBackgroundColor(Color.argb(230, 30, 30, 30)) // Dark semi-transparent
        paletteView.setPadding(8, 8, 8, 8)

        btnLeftClick.text = "LMB"
        btnRightClick.text = "RMB"
        btnDragToggle.text = "Drag: Off"
        btnScrollUp.text = "▲ Scroll"
        btnScrollDown.text = "▼ Scroll"
        btnTouchpadToggle.text = "Mode: Touch"
        btnCursorToggle.text = "Cursor: Off"
        btnCollapse.text = "✕"

        val buttons = listOf(
            btnLeftClick,
            btnRightClick,
            btnDragToggle,
            btnScrollUp,
            btnScrollDown,
            btnTouchpadToggle,
            btnCursorToggle,
            btnCollapse
        )

        buttons.forEach { btn ->
            btn.setTextColor(Color.WHITE)
            paletteView.addView(btn)
        }

        setupButtonListeners()
    }

    private fun getTargetScreenPosition(): Pair<Float, Float> {
        val pt = mouseController?.cursorScreenPosition
        return if (pt != null) {
            pt.x to pt.y
        } else {
            currentScreenX to currentScreenY
        }
    }

    private fun setupButtonListeners() {
        btnLeftClick.setOnClickListener {
            val (x, y) = getTargetScreenPosition()
            mouseController?.handleLeftClick(x, y)
        }

        btnRightClick.setOnClickListener {
            val (x, y) = getTargetScreenPosition()
            mouseController?.handleRightClick(x, y)
        }

        btnDragToggle.setOnClickListener {
            isDragLocked = !isDragLocked
            btnDragToggle.text = if (isDragLocked) "Drag: ON" else "Drag: Off"
            val (x, y) = getTargetScreenPosition()
            if (isDragLocked) {
                mouseController?.handleDragStart(x, y)
            } else {
                mouseController?.handleDragEnd(x, y)
            }
        }

        btnScrollUp.setOnClickListener {
            val (x, y) = getTargetScreenPosition()
            mouseController?.handleScroll(x, y, 1.0f)
        }

        btnScrollDown.setOnClickListener {
            val (x, y) = getTargetScreenPosition()
            mouseController?.handleScroll(x, y, -1.0f)
        }

        btnTouchpadToggle.setOnClickListener {
            isTouchpadActive = !isTouchpadActive
            btnTouchpadToggle.text = if (isTouchpadActive) "Mode: Pad" else "Mode: Touch"
            mouseController?.setTouchpadMode(isTouchpadActive)
            if (isTouchpadActive) {
                isCursorActive = true
                btnCursorToggle.text = "Cursor: ON"
            }
        }

        btnCursorToggle.setOnClickListener {
            isCursorActive = !isCursorActive
            btnCursorToggle.text = if (isCursorActive) "Cursor: ON" else "Cursor: Off"
            mouseController?.setCursorVisible(isCursorActive)
        }

        btnCollapse.setOnClickListener {
            setState(OverlayState.COLLAPSED)
        }
    }

    fun setState(newState: OverlayState) {
        state = newState
        when (newState) {
            OverlayState.COLLAPSED -> {
                bubbleView.visibility = View.VISIBLE
                paletteView.visibility = View.GONE
            }

            OverlayState.EXPANDED -> {
                bubbleView.visibility = View.GONE
                paletteView.visibility = View.VISIBLE
            }

            OverlayState.DRAGGING -> {
                bubbleView.visibility = View.VISIBLE
                paletteView.visibility = View.GONE
            }
        }
        applyCurrentPosition()
    }

    fun expand() {
        setState(OverlayState.EXPANDED)
    }

    fun collapse() {
        setState(OverlayState.COLLAPSED)
    }

    fun setSafeInsets(insets: SafeInsets) {
        safeInsets = insets
        recalculatePositionFromNormalized()
    }

    fun setOverlayCoordinates(coordinates: OverlayCoordinates) {
        currentCoordinates = coordinates
        recalculatePositionFromNormalized()
    }

    /**
     * Updates screen coordinates and recalculates normalized persistence coordinates.
     */
    fun updatePosition(targetX: Float, targetY: Float, clampOnly: Boolean = false) {
        val overlayW = getActiveWidth()
        val overlayH = getActiveHeight()
        val screenW = width.takeIf { it > 0 } ?: 1080
        val screenH = height.takeIf { it > 0 } ?: 2400

        val clamped = OverlayCoordinates.clamp(
            targetX, targetY, screenW, screenH, overlayW, overlayH, safeInsets
        )
        currentScreenX = clamped.x
        currentScreenY = clamped.y

        if (!clampOnly) {
            currentCoordinates = OverlayCoordinates.fromScreenCoordinates(
                currentScreenX, currentScreenY, screenW, screenH, overlayW, overlayH, safeInsets
            )
        }
        applyCurrentPosition()
    }

    private fun snapToEdgeAndSettle() {
        val overlayW = getActiveWidth()
        val overlayH = getActiveHeight()
        val screenW = width.takeIf { it > 0 } ?: 1080
        val screenH = height.takeIf { it > 0 } ?: 2400

        val snapped = OverlayCoordinates.snapToNearestEdge(
            currentScreenX, currentScreenY, screenW, screenH, overlayW, overlayH, safeInsets
        )
        currentScreenX = snapped.x
        currentScreenY = snapped.y

        currentCoordinates = OverlayCoordinates.fromScreenCoordinates(
            currentScreenX, currentScreenY, screenW, screenH, overlayW, overlayH, safeInsets
        )
        applyCurrentPosition()
    }

    private fun recalculatePositionFromNormalized() {
        val overlayW = getActiveWidth()
        val overlayH = getActiveHeight()
        val screenW = width.takeIf { it > 0 } ?: 1080
        val screenH = height.takeIf { it > 0 } ?: 2400

        val pt = currentCoordinates.toScreenCoordinates(screenW, screenH, overlayW, overlayH, safeInsets)
        currentScreenX = pt.x
        currentScreenY = pt.y
        applyCurrentPosition()
    }

    private fun applyCurrentPosition() {
        bubbleView.translationX = currentScreenX
        bubbleView.translationY = currentScreenY

        // Align expanded palette with clamped position
        paletteView.translationX = currentScreenX
        paletteView.translationY = currentScreenY
    }

    private fun getActiveWidth(): Int {
        return if (state == OverlayState.EXPANDED) {
            paletteView.width.takeIf { it > 0 } ?: (140 * density).toInt()
        } else {
            bubbleSizePx
        }
    }

    private fun getActiveHeight(): Int {
        return if (state == OverlayState.EXPANDED) {
            paletteView.height.takeIf { it > 0 } ?: (300 * density).toInt()
        } else {
            bubbleSizePx
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            // Orientation or screen size switch: recalculate from normalized coordinates without resetting
            recalculatePositionFromNormalized()
        }
    }

    /**
     * Updates display bounds and recalculates overlay position from normalized coordinates.
     */
    fun updateDisplayBounds(width: Int, height: Int) {
        layout(0, 0, width, height)
        if (width > 0 && height > 0) {
            recalculatePositionFromNormalized()
        }
    }

    fun savePosition(prefs: SharedPreferences, keyPrefix: String = OverlayCoordinates.PREFS_KEY_PREFIX) {
        currentCoordinates.saveToPreferences(prefs, keyPrefix)
    }

    fun restorePosition(prefs: SharedPreferences, keyPrefix: String = OverlayCoordinates.PREFS_KEY_PREFIX) {
        currentCoordinates = OverlayCoordinates.loadFromPreferences(prefs, keyPrefix)
        recalculatePositionFromNormalized()
    }
}
