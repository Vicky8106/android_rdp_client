package com.rdp.client.ui.session.input

import android.content.Context
import android.graphics.PointF
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.GestureDetector
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.rdp.client.freerdp.LibFreeRDP
import com.rdp.client.freerdp.RdpPointerFlags
import com.rdp.client.model.GestureStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Unified Touch and Mouse Event Dispatcher for Milestone 4.
 * Integrates Direct Touchscreen, Relative Trackpad, Two-Finger Scroll,
 * Hardware Mouse Passthrough, and LibFreeRDP Cursor Event transmission.
 */
class TouchDispatcher(
    private val context: Context,
    private val instanceProvider: () -> Long,
    private val transformer: IFrameCoordinateTransformer,
    var gestureStyle: GestureStyle = GestureStyle.AUTO,
    var invertVerticalScroll: Boolean = false,
    var buttonUpDelayEnabled: Boolean = true,
    var isInputEnabledProvider: () -> Boolean = { true },
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
) : View.OnTouchListener, View.OnGenericMotionListener, ScaleGestureDetector.OnScaleGestureListener {

    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    val accelerator = PointerAcceleration()

    // Virtual cursor position for Relative Touchpad mode
    val virtualCursor = PointF(0f, 0f)

    // Drag-lock state (engaged via Virtual Mouse or double-tap hold)
    var isDragLocked = false

    // Held button tracking for guaranteed cleanup
    private val heldButtons = mutableSetOf<RdpPointerFlags.Button>()

    // Two-finger scroll accumulator
    private var accumulatedScrollDx = 0f
    private var accumulatedScrollDy = 0f
    private val deltaPerScroll = 24f * context.resources.displayMetrics.density

    // Gesture Detectors
    private val scaleGestureDetector = ScaleGestureDetector(context, this).apply {
        isQuickScaleEnabled = false
    }
    private val gestureListener = InternalGestureListener()
    private val gestureDetector = GestureDetector(context, gestureListener)

    init {
        // Initialize virtual cursor to center of desktop
        if (transformer.fbWidth > 0 && transformer.fbHeight > 0) {
            virtualCursor.set(transformer.fbWidth / 2f, transformer.fbHeight / 2f)
        }
    }

    /**
     * Resolves effective gesture style (Auto defaults to Touchscreen on phones).
     */
    val effectiveGestureStyle: GestureStyle
        get() = when (gestureStyle) {
            GestureStyle.TOUCHSCREEN -> GestureStyle.TOUCHSCREEN
            GestureStyle.TOUCHPAD -> GestureStyle.TOUCHPAD
            GestureStyle.AUTO -> GestureStyle.TOUCHSCREEN
        }

    // -------------------------------------------------------------------------
    // View.OnTouchListener & View.OnGenericMotionListener
    // -------------------------------------------------------------------------

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        if (!isInputEnabledProvider()) {
            return false
        }
        // Feed movement to pointer accelerator
        accelerator.addMovement(event)

        // 1. Check for hardware mouse or stylus events first
        if (handleHardwareMouseEvent(event) || handleStylusEvent(event)) {
            return true
        }

        // 2. Multi-touch scale gesture
        scaleGestureDetector.onTouchEvent(event)

        // 3. Track touch count for multi-finger gestures & scrolls
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                accumulatedScrollDx = 0f
                accumulatedScrollDy = 0f
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 2 && !scaleGestureDetector.isInProgress) {
                    handleTwoFingerScroll(event)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                accelerator.reset()
                if (!isDragLocked) {
                    releaseAllButtons()
                }
            }
        }

        // 4. Dispatch standard gestures (tap, double tap, scroll, fling)
        return gestureDetector.onTouchEvent(event)
    }

    override fun onGenericMotion(v: View, event: MotionEvent): Boolean {
        if (!isInputEnabledProvider()) {
            return false
        }
        if (event.actionMasked == MotionEvent.ACTION_HOVER_MOVE) {
            val fbPoint = transformer.toFb(event.x, event.y) ?: return false
            val safeX = if (fbPoint.x.isFinite()) fbPoint.x.roundToInt() else 0
            val safeY = if (fbPoint.y.isFinite()) fbPoint.y.roundToInt() else 0
            sendCursorEvent(safeX, safeY, RdpPointerFlags.encodeMove())
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_SCROLL) {
            val hScroll = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
            val vScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            val fbPoint = transformer.toFb(event.x, event.y) ?: virtualCursor
            val safeX = if (fbPoint.x.isFinite()) fbPoint.x.roundToInt() else 0
            val safeY = if (fbPoint.y.isFinite()) fbPoint.y.roundToInt() else 0

            if (vScroll != 0f && vScroll.isFinite()) {
                val dir = if (vScroll > 0) RdpPointerFlags.ScrollDirection.UP else RdpPointerFlags.ScrollDirection.DOWN
                sendCursorEvent(
                    safeX,
                    safeY,
                    RdpPointerFlags.encodeVerticalScroll(dir, abs(vScroll).roundToInt().coerceAtLeast(1))
                )
            }
            if (hScroll != 0f && hScroll.isFinite()) {
                val dir = if (hScroll > 0) RdpPointerFlags.ScrollDirection.RIGHT else RdpPointerFlags.ScrollDirection.LEFT
                sendCursorEvent(
                    safeX,
                    safeY,
                    RdpPointerFlags.encodeHorizontalScroll(dir, abs(hScroll).roundToInt().coerceAtLeast(1))
                )
            }
            return true
        }
        return false
    }

    // -------------------------------------------------------------------------
    // Gesture Implementations
    // -------------------------------------------------------------------------

    private inner class InternalGestureListener : GestureDetector.SimpleOnGestureListener() {

        override fun onDown(e: MotionEvent): Boolean {
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            performSingleClick(e.x, e.y)
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            performDoubleClick(e.x, e.y)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            triggerHapticFeedback()
            performRightClick(e.x, e.y)
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (e2.pointerCount > 1) {
                // Handled via two-finger scroll
                return true
            }

            val dx = -distanceX
            val dy = -distanceY

            when (effectiveGestureStyle) {
                GestureStyle.TOUCHSCREEN -> {
                    // Direct drag: Left button down + move
                    val fbPoint = transformer.toFb(e2.x, e2.y) ?: coerceToFbBoundary(e2.x, e2.y)
                    val safeX = if (fbPoint.x.isFinite()) fbPoint.x.roundToInt() else 0
                    val safeY = if (fbPoint.y.isFinite()) fbPoint.y.roundToInt() else 0
                    val flags = if (isDragLocked || heldButtons.contains(RdpPointerFlags.Button.LEFT)) {
                        RdpPointerFlags.encodeDragMove(RdpPointerFlags.Button.LEFT)
                    } else {
                        RdpPointerFlags.encodeMove()
                    }
                    sendCursorEvent(safeX, safeY, flags)
                }

                GestureStyle.TOUCHPAD -> {
                    // Relative trackpad motion with acceleration
                    val (adx, ady) = accelerator.updateDelta(dx, dy)
                    val safeAdx = if (adx.isFinite()) adx else 0f
                    val safeAdy = if (ady.isFinite()) ady else 0f
                    val xLimit = (transformer.fbWidth - 1).coerceAtLeast(0)
                    val yLimit = (transformer.fbHeight - 1).coerceAtLeast(0)

                    virtualCursor.x = (virtualCursor.x + safeAdx).coerceIn(0f, xLimit.toFloat())
                    virtualCursor.y = (virtualCursor.y + safeAdy).coerceIn(0f, yLimit.toFloat())
                    if (!virtualCursor.x.isFinite()) virtualCursor.x = 0f
                    if (!virtualCursor.y.isFinite()) virtualCursor.y = 0f

                    val flags = if (isDragLocked || heldButtons.contains(RdpPointerFlags.Button.LEFT)) {
                        RdpPointerFlags.encodeDragMove(RdpPointerFlags.Button.LEFT)
                    } else {
                        RdpPointerFlags.encodeMove()
                    }
                    val curX = if (virtualCursor.x.isFinite()) virtualCursor.x.roundToInt() else 0
                    val curY = if (virtualCursor.y.isFinite()) virtualCursor.y.roundToInt() else 0
                    sendCursorEvent(curX, curY, flags)

                    // Auto-centering viewport tracking
                    val vp = transformer.toVp(virtualCursor.x, virtualCursor.y)
                    val centerDiffX = transformer.safeAreaCenterX - vp.x
                    val centerDiffY = transformer.safeAreaCenterY - vp.y
                    if (abs(centerDiffX) > 100f || abs(centerDiffY) > 100f) {
                        transformer.panFrame(centerDiffX * 0.1f, centerDiffY * 0.1f)
                    }
                }
                else -> {}
            }
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            return false
        }
    }

    // -------------------------------------------------------------------------
    // Two-Finger Scrolling Math
    // -------------------------------------------------------------------------

    private fun handleTwoFingerScroll(event: MotionEvent) {
        val yDirection = if (invertVerticalScroll) -1f else 1f
        val historySize = event.historySize
        var totalDx = 0f
        var totalDy = 0f

        if (historySize > 0) {
            val hx = event.getHistoricalX(0, historySize - 1)
            val hy = event.getHistoricalY(0, historySize - 1)
            val rawDx = event.x - hx
            val rawDy = (event.y - hy) * yDirection
            totalDx = if (rawDx.isFinite()) rawDx else 0f
            totalDy = if (rawDy.isFinite()) rawDy else 0f
        }

        if (totalDx.isFinite()) accumulatedScrollDx += totalDx
        if (totalDy.isFinite()) accumulatedScrollDy += totalDy

        if (!accumulatedScrollDx.isFinite()) accumulatedScrollDx = 0f
        if (!accumulatedScrollDy.isFinite()) accumulatedScrollDy = 0f

        val targetPoint = if (effectiveGestureStyle == GestureStyle.TOUCHSCREEN) {
            transformer.toFb(event.x, event.y) ?: virtualCursor
        } else {
            virtualCursor
        }
        val safeX = if (targetPoint.x.isFinite()) targetPoint.x.roundToInt() else 0
        val safeY = if (targetPoint.y.isFinite()) targetPoint.y.roundToInt() else 0

        // Drain vertical scrolls
        while (accumulatedScrollDy.isFinite() && abs(accumulatedScrollDy) >= deltaPerScroll && deltaPerScroll > 0f) {
            val dir = if (accumulatedScrollDy > 0) RdpPointerFlags.ScrollDirection.UP else RdpPointerFlags.ScrollDirection.DOWN
            sendCursorEvent(safeX, safeY, RdpPointerFlags.encodeVerticalScroll(dir, 1))
            if (accumulatedScrollDy > 0) accumulatedScrollDy -= deltaPerScroll else accumulatedScrollDy += deltaPerScroll
        }

        // Drain horizontal scrolls
        while (accumulatedScrollDx.isFinite() && abs(accumulatedScrollDx) >= deltaPerScroll && deltaPerScroll > 0f) {
            val dir = if (accumulatedScrollDx > 0) RdpPointerFlags.ScrollDirection.RIGHT else RdpPointerFlags.ScrollDirection.LEFT
            sendCursorEvent(safeX, safeY, RdpPointerFlags.encodeHorizontalScroll(dir, 1))
            if (accumulatedScrollDx > 0) accumulatedScrollDx -= deltaPerScroll else accumulatedScrollDx += deltaPerScroll
        }
    }

    // -------------------------------------------------------------------------
    // Hardware Mouse & Stylus Handlers
    // -------------------------------------------------------------------------

    private fun handleHardwareMouseEvent(e: MotionEvent): Boolean {
        if (Build.VERSION.SDK_INT < 23 || !e.isFromSource(InputDevice.SOURCE_MOUSE)) return false

        val fbPoint = transformer.toFb(e.x, e.y) ?: virtualCursor
        val safeX = if (fbPoint.x.isFinite()) fbPoint.x.roundToInt() else 0
        val safeY = if (fbPoint.y.isFinite()) fbPoint.y.roundToInt() else 0

        when (e.actionMasked) {
            MotionEvent.ACTION_BUTTON_PRESS -> {
                val button = convertMouseButton(e.actionButton)
                if (button != null) {
                    sendButtonDown(button, safeX, safeY)
                    return true
                }
            }
            MotionEvent.ACTION_BUTTON_RELEASE -> {
                val button = convertMouseButton(e.actionButton)
                if (button != null) {
                    sendButtonUp(button, safeX, safeY)
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                sendCursorEvent(safeX, safeY, RdpPointerFlags.encodeMove())
                return true
            }
        }
        return false
    }

    private fun handleStylusEvent(e: MotionEvent): Boolean {
        if (!e.isFromSource(InputDevice.SOURCE_STYLUS) && e.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS) return false

        val fbPoint = transformer.toFb(e.x, e.y) ?: virtualCursor
        val safeX = if (fbPoint.x.isFinite()) fbPoint.x.roundToInt() else 0
        val safeY = if (fbPoint.y.isFinite()) fbPoint.y.roundToInt() else 0
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                sendButtonDown(RdpPointerFlags.Button.LEFT, safeX, safeY)
                return true
            }
            MotionEvent.ACTION_UP -> {
                sendButtonUp(RdpPointerFlags.Button.LEFT, safeX, safeY)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                sendCursorEvent(safeX, safeY, RdpPointerFlags.encodeDragMove(RdpPointerFlags.Button.LEFT))
                return true
            }
        }
        return false
    }

    private fun convertMouseButton(actionButton: Int): RdpPointerFlags.Button? = when (actionButton) {
        MotionEvent.BUTTON_PRIMARY -> RdpPointerFlags.Button.LEFT
        MotionEvent.BUTTON_SECONDARY -> RdpPointerFlags.Button.RIGHT
        MotionEvent.BUTTON_TERTIARY -> RdpPointerFlags.Button.MIDDLE
        else -> null
    }

    // -------------------------------------------------------------------------
    // Core Actions & Click Dispatchers
    // -------------------------------------------------------------------------

    fun performSingleClick(screenX: Float, screenY: Float) {
        if (!isInputEnabledProvider()) return
        val pt = resolveTargetPoint(screenX, screenY)
        val x = if (pt.x.isFinite()) pt.x.roundToInt() else 0
        val y = if (pt.y.isFinite()) pt.y.roundToInt() else 0
        dispatchClick(RdpPointerFlags.Button.LEFT, x, y)
    }

    fun performDoubleClick(screenX: Float, screenY: Float) {
        if (!isInputEnabledProvider()) return
        val pt = resolveTargetPoint(screenX, screenY)
        val x = if (pt.x.isFinite()) pt.x.roundToInt() else 0
        val y = if (pt.y.isFinite()) pt.y.roundToInt() else 0
        dispatchClick(RdpPointerFlags.Button.LEFT, x, y)
        scope.launch {
            delay(50)
            dispatchClick(RdpPointerFlags.Button.LEFT, x, y)
        }
    }

    fun performRightClick(screenX: Float, screenY: Float) {
        if (!isInputEnabledProvider()) return
        val pt = resolveTargetPoint(screenX, screenY)
        val x = if (pt.x.isFinite()) pt.x.roundToInt() else 0
        val y = if (pt.y.isFinite()) pt.y.roundToInt() else 0
        dispatchClick(RdpPointerFlags.Button.RIGHT, x, y)
    }

    fun performMiddleClick(screenX: Float, screenY: Float) {
        if (!isInputEnabledProvider()) return
        val pt = resolveTargetPoint(screenX, screenY)
        val x = if (pt.x.isFinite()) pt.x.roundToInt() else 0
        val y = if (pt.y.isFinite()) pt.y.roundToInt() else 0
        dispatchClick(RdpPointerFlags.Button.MIDDLE, x, y)
    }

    fun sendButtonDown(button: RdpPointerFlags.Button, x: Int, y: Int) {
        heldButtons.add(button)
        sendCursorEvent(x, y, RdpPointerFlags.encodeButtonDown(button))
    }

    fun sendButtonUp(button: RdpPointerFlags.Button, x: Int, y: Int) {
        heldButtons.remove(button)
        sendCursorEvent(x, y, RdpPointerFlags.encodeButtonUp(button))
    }

    private fun dispatchClick(button: RdpPointerFlags.Button, x: Int, y: Int) {
        sendCursorEvent(x, y, RdpPointerFlags.encodeButtonDown(button))
        if (buttonUpDelayEnabled) {
            scope.launch {
                delay(20) // Calibrated 20ms click down duration
                sendCursorEvent(x, y, RdpPointerFlags.encodeButtonUp(button))
            }
        } else {
            sendCursorEvent(x, y, RdpPointerFlags.encodeButtonUp(button))
        }
    }

    private fun resolveTargetPoint(screenX: Float, screenY: Float): PointF {
        return when (effectiveGestureStyle) {
            GestureStyle.TOUCHSCREEN -> transformer.toFb(screenX, screenY) ?: coerceToFbBoundary(screenX, screenY)
            GestureStyle.TOUCHPAD -> virtualCursor
            else -> virtualCursor
        }
    }

    private fun coerceToFbBoundary(screenX: Float, screenY: Float): PointF {
        val xLimit = (transformer.fbWidth - 1).coerceAtLeast(0)
        val yLimit = (transformer.fbHeight - 1).coerceAtLeast(0)
        val safeX = when {
            screenX.isNaN() -> 0f
            screenX == Float.POSITIVE_INFINITY -> xLimit.toFloat()
            screenX == Float.NEGATIVE_INFINITY -> 0f
            else -> screenX
        }
        val safeY = when {
            screenY.isNaN() -> 0f
            screenY == Float.POSITIVE_INFINITY -> yLimit.toFloat()
            screenY == Float.NEGATIVE_INFINITY -> 0f
            else -> screenY
        }
        val rawPoint = transformer.toFb(safeX, safeY) ?: PointF(safeX, safeY)
        val finalX = if (!rawPoint.x.isFinite()) 0f else rawPoint.x.coerceIn(0f, xLimit.toFloat())
        val finalY = if (!rawPoint.y.isFinite()) 0f else rawPoint.y.coerceIn(0f, yLimit.toFloat())
        return PointF(finalX, finalY)
    }

    /**
     * Sends cursor event to native FreeRDP via LibFreeRDP facade.
     */
    fun sendCursorEvent(x: Int, y: Int, flags: Int): Boolean {
        val instance = instanceProvider()
        if (instance == 0L) return false
        return LibFreeRDP.sendCursorEvent(instance, x, y, flags)
    }

    /**
     * Guaranteed release of all held mouse buttons.
     * MUST be invoked on onPause, disconnect, or focus loss.
     */
    fun releaseAllButtons() {
        if (heldButtons.isEmpty()) return
        val x = if (virtualCursor.x.isFinite()) virtualCursor.x.roundToInt() else 0
        val y = if (virtualCursor.y.isFinite()) virtualCursor.y.roundToInt() else 0
        val buttonsToRelease = heldButtons.toList()
        for (btn in buttonsToRelease) {
            sendButtonUp(btn, x, y)
        }
        heldButtons.clear()
        isDragLocked = false
    }

    private fun triggerHapticFeedback() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(40)
            }
        } catch (ignored: Throwable) {}
    }

    // -------------------------------------------------------------------------
    // ScaleGestureDetector.OnScaleGestureListener
    // -------------------------------------------------------------------------

    override fun onScale(detector: ScaleGestureDetector): Boolean {
        transformer.zoomFrame(detector.scaleFactor, detector.focusX, detector.focusY)
        return true
    }

    override fun onScaleBegin(detector: ScaleGestureDetector): Boolean = true
    override fun onScaleEnd(detector: ScaleGestureDetector) {}
}
