package com.freerdp.feature.mouse

import android.graphics.PointF

/**
 * Interface contract for mouse interactions as defined in PROJECT.md.
 */
interface MouseController {
    val isTouchpadMode: Boolean get() = false
    val isDragging: Boolean get() = false
    val cursorScreenPosition: PointF? get() = null
    val virtualCursorPosition: PointF? get() = null
    fun handleLeftClick(screenX: Float, screenY: Float)
    fun handleRightClick(screenX: Float, screenY: Float)
    fun handleMiddleClick(screenX: Float, screenY: Float) = Unit
    fun handleDoubleClick(screenX: Float, screenY: Float)
    fun handleDragStart(screenX: Float, screenY: Float)
    fun handleDragMove(screenX: Float, screenY: Float)
    fun handleDragEnd(screenX: Float, screenY: Float)
    fun handleScroll(screenX: Float, screenY: Float, deltaY: Float)
    fun setTouchpadMode(enabled: Boolean)
    fun setCursorVisible(visible: Boolean)
}
