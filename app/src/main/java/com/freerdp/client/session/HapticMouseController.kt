package com.freerdp.client.session

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import com.freerdp.feature.mouse.DefaultMouseController
import com.freerdp.feature.mouse.CoordinateTransformer
import com.freerdp.feature.mouse.MouseController

/**
 * Wraps the production [DefaultMouseController] with touch feedback: every mouse click
 * that reaches the remote desktop produces a short haptic tick so users get confirmation
 * without looking at the screen (critical for one-handed, direct-touch operation).
 *
 * Also re-exposes the concrete controller extras (touchpad move, scroll, safety release)
 * that the floating overlay and canvas need beyond the [MouseController] contract.
 */
class HapticMouseController(
    private val delegate: DefaultMouseController,
    private val hapticTarget: View?
) : MouseController {

    val inner: DefaultMouseController get() = delegate

    override val isTouchpadMode: Boolean get() = delegate.isTouchpadMode
    val isCursorVisible: Boolean get() = delegate.isCursorVisible
    override val isDragging: Boolean get() = delegate.isDragging
    override val cursorScreenPosition: android.graphics.PointF get() = delegate.cursorScreenPosition
    override val virtualCursorPosition: android.graphics.PointF get() = delegate.virtualCursorPosition

    private fun tick(style: Int = HapticFeedbackConstants.KEYBOARD_TAP) {
        val target = hapticTarget ?: return
        if (target.isAttachedToWindow) {
            target.performHapticFeedback(style)
        }
    }

    override fun handleLeftClick(screenX: Float, screenY: Float) {
        delegate.handleLeftClick(screenX, screenY)
        tick()
    }

    override fun handleRightClick(screenX: Float, screenY: Float) {
        delegate.handleRightClick(screenX, screenY)
        tick(HapticFeedbackConstants.LONG_PRESS)
    }

    override fun handleDoubleClick(screenX: Float, screenY: Float) {
        delegate.handleDoubleClick(screenX, screenY)
        tick()
    }

    override fun handleDragStart(screenX: Float, screenY: Float) {
        delegate.handleDragStart(screenX, screenY)
        tick(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM
            else HapticFeedbackConstants.LONG_PRESS)
    }

    override fun handleDragMove(screenX: Float, screenY: Float) = delegate.handleDragMove(screenX, screenY)

    override fun handleDragEnd(screenX: Float, screenY: Float) = delegate.handleDragEnd(screenX, screenY)

    override fun handleScroll(screenX: Float, screenY: Float, deltaY: Float) =
        delegate.handleScroll(screenX, screenY, deltaY)

    override fun setTouchpadMode(enabled: Boolean) {
        delegate.setTouchpadMode(enabled)
        tick(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    override fun setCursorVisible(visible: Boolean) {
        delegate.setCursorVisible(visible)
    }

    /** Relative touchpad movement (no haptics — motion is continuous). */
    fun handleTouchpadMove(deltaX: Float, deltaY: Float, accelerate: Boolean = true) =
        delegate.handleTouchpadMove(deltaX, deltaY, accelerate)

    fun handleHorizontalScroll(screenX: Float, screenY: Float, deltaX: Float) =
        delegate.handleHorizontalScroll(screenX, screenY, deltaX)

    fun releaseButtons() = delegate.releaseButtons()

    companion object {
        fun create(
            engine: com.freerdp.core.engine.IRdpEngine,
            transformer: CoordinateTransformer,
            hapticTarget: View?
        ): HapticMouseController = HapticMouseController(DefaultMouseController(engine, transformer), hapticTarget)
    }
}
