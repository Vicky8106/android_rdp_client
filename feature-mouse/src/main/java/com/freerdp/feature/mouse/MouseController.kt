package com.freerdp.feature.mouse

import android.graphics.PointF
import com.freerdp.core.engine.IRdpEngine
import com.freerdp.core.protocol.RdpPointerFlags
import kotlin.math.max

/**
 * Interface contract for mouse interactions as defined in PROJECT.md.
 */
interface MouseController {
    fun handleLeftClick(screenX: Float, screenY: Float)
    fun handleRightClick(screenX: Float, screenY: Float)
    fun handleDoubleClick(screenX: Float, screenY: Float)
    fun handleDragStart(screenX: Float, screenY: Float)
    fun handleDragMove(screenX: Float, screenY: Float)
    fun handleDragEnd(screenX: Float, screenY: Float)
    fun handleScroll(screenX: Float, screenY: Float, deltaY: Float)
    fun setTouchpadMode(enabled: Boolean)
    fun setCursorVisible(visible: Boolean)
}

/**
 * Production implementation of MouseController dispatching MS-RDPBCGR pointer events
 * to IRdpEngine. Supports Direct Touch and Relative Touchpad modes.
 */
class DefaultMouseController(
    private val rdpEngine: IRdpEngine,
    private val transformer: CoordinateTransformer
) : MouseController {

    var isTouchpadMode: Boolean = false
        private set

    var isCursorVisible: Boolean = false
        private set

    var isDragging: Boolean = false
        private set

    var touchpadSensitivity: Float = 1.0f

    private var cursorX: Float = (transformer.remoteWidth / 2).toFloat()
    private var cursorY: Float = (transformer.remoteHeight / 2).toFloat()

    val virtualCursorPosition: PointF
        get() = PointF(cursorX, cursorY)

    private fun resolveTargetCoordinates(screenX: Float, screenY: Float): Pair<Int, Int> {
        return if (isTouchpadMode) {
            Pair(
                cursorX.toInt().coerceIn(0, max(0, transformer.remoteWidth - 1)),
                cursorY.toInt().coerceIn(0, max(0, transformer.remoteHeight - 1))
            )
        } else {
            val (tx, ty) = transformer.screenToDesktopInt(screenX, screenY)
            cursorX = tx.toFloat()
            cursorY = ty.toFloat()
            Pair(tx, ty)
        }
    }

    override fun handleLeftClick(screenX: Float, screenY: Float) {
        val (tx, ty) = resolveTargetCoordinates(screenX, screenY)
        rdpEngine.sendPointerEvent(RdpPointerFlags.LEFT_BUTTON_DOWN, tx, ty)
        rdpEngine.sendPointerEvent(RdpPointerFlags.LEFT_BUTTON_UP, tx, ty)
    }

    override fun handleRightClick(screenX: Float, screenY: Float) {
        val (tx, ty) = resolveTargetCoordinates(screenX, screenY)
        rdpEngine.sendPointerEvent(RdpPointerFlags.RIGHT_BUTTON_DOWN, tx, ty)
        rdpEngine.sendPointerEvent(RdpPointerFlags.RIGHT_BUTTON_UP, tx, ty)
    }

    override fun handleDoubleClick(screenX: Float, screenY: Float) {
        val (tx, ty) = resolveTargetCoordinates(screenX, screenY)
        // First click
        rdpEngine.sendPointerEvent(RdpPointerFlags.LEFT_BUTTON_DOWN, tx, ty)
        rdpEngine.sendPointerEvent(RdpPointerFlags.LEFT_BUTTON_UP, tx, ty)
        // Second click
        rdpEngine.sendPointerEvent(RdpPointerFlags.LEFT_BUTTON_DOWN, tx, ty)
        rdpEngine.sendPointerEvent(RdpPointerFlags.LEFT_BUTTON_UP, tx, ty)
    }

    override fun handleDragStart(screenX: Float, screenY: Float) {
        val (tx, ty) = resolveTargetCoordinates(screenX, screenY)
        isDragging = true
        rdpEngine.sendPointerEvent(RdpPointerFlags.LEFT_BUTTON_DOWN, tx, ty)
    }

    override fun handleDragMove(screenX: Float, screenY: Float) {
        val (tx, ty) = resolveTargetCoordinates(screenX, screenY)
        val flags = if (isDragging) {
            RdpPointerFlags.MOVE or RdpPointerFlags.PTR_FLAGS_BUTTON1 or RdpPointerFlags.PTR_FLAGS_DOWN
        } else {
            RdpPointerFlags.MOVE
        }
        rdpEngine.sendPointerEvent(flags, tx, ty)
    }

    override fun handleDragEnd(screenX: Float, screenY: Float) {
        val (tx, ty) = resolveTargetCoordinates(screenX, screenY)
        isDragging = false
        rdpEngine.sendPointerEvent(RdpPointerFlags.LEFT_BUTTON_UP, tx, ty)
    }

    override fun handleScroll(screenX: Float, screenY: Float, deltaY: Float) {
        if (deltaY == 0f) return
        val (tx, ty) = resolveTargetCoordinates(screenX, screenY)
        val flags = if (deltaY > 0f) {
            RdpPointerFlags.SCROLL_UP
        } else {
            RdpPointerFlags.SCROLL_DOWN
        }
        rdpEngine.sendPointerEvent(flags, tx, ty)
    }

    /**
     * Handles horizontal scroll wheel event.
     */
    fun handleHorizontalScroll(screenX: Float, screenY: Float, deltaX: Float) {
        if (deltaX == 0f) return
        val (tx, ty) = resolveTargetCoordinates(screenX, screenY)
        val flags = if (deltaX > 0f) {
            RdpPointerFlags.SCROLL_RIGHT
        } else {
            RdpPointerFlags.SCROLL_LEFT
        }
        rdpEngine.sendPointerEvent(flags, tx, ty)
    }

    /**
     * Handles relative touchpad movement.
     */
    fun handleTouchpadMove(deltaX: Float, deltaY: Float) {
        val maxW = max(0, transformer.remoteWidth - 1).toFloat()
        val maxH = max(0, transformer.remoteHeight - 1).toFloat()

        cursorX = (cursorX + deltaX * touchpadSensitivity).coerceIn(0f, maxW)
        cursorY = (cursorY + deltaY * touchpadSensitivity).coerceIn(0f, maxH)

        val tx = cursorX.toInt()
        val ty = cursorY.toInt()

        val flags = if (isDragging) {
            RdpPointerFlags.MOVE or RdpPointerFlags.PTR_FLAGS_BUTTON1 or RdpPointerFlags.PTR_FLAGS_DOWN
        } else {
            RdpPointerFlags.MOVE
        }
        rdpEngine.sendPointerEvent(flags, tx, ty)
    }

    override fun setTouchpadMode(enabled: Boolean) {
        if (isTouchpadMode == enabled) return
        isTouchpadMode = enabled
        if (enabled) {
            isCursorVisible = true
        } else if (isDragging) {
            // Safety release when exiting touchpad mode mid-drag. Button-down went out in
            // the touchpad basis (virtual-cursor DESKTOP coordinates), so the button-up
            // must use the same basis: cursorX/Y are already desktop coordinates, and
            // routing them through handleDragEnd()/resolveTargetCoordinates() now that
            // isTouchpadMode is false would apply screen→desktop a SECOND time.
            isDragging = false
            rdpEngine.sendPointerEvent(RdpPointerFlags.LEFT_BUTTON_UP, cursorX.toInt(), cursorY.toInt())
        }
    }

    override fun setCursorVisible(visible: Boolean) {
        isCursorVisible = visible
    }

    /**
     * Safety cancellation: releases mouse buttons if currently down.
     */
    fun releaseButtons() {
        if (isDragging) {
            isDragging = false
            rdpEngine.sendPointerEvent(RdpPointerFlags.LEFT_BUTTON_UP, cursorX.toInt(), cursorY.toInt())
        }
    }

    /**
     * Sets virtual cursor position directly in remote desktop coordinates.
     */
    fun setVirtualCursorPosition(x: Float, y: Float) {
        cursorX = x.coerceIn(0f, max(0, transformer.remoteWidth - 1).toFloat())
        cursorY = y.coerceIn(0f, max(0, transformer.remoteHeight - 1).toFloat())
    }
}
