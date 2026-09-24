package com.freerdp.feature.mouse

import android.graphics.PointF
import com.freerdp.core.engine.IRdpEngine
import com.freerdp.core.protocol.RdpPointerFlags
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max

/**
 * Standard mouse buttons supported by the pointer subsystem.
 */
enum class PointerButton {
    None,
    Left,
    Middle,
    Right,
    WheelUp,
    WheelDown,
    WheelLeft,
    WheelRight
}

/**
 * Base abstraction for pointer modes (Direct Touch and Relative Touchpad).
 * Coordinates event transformation, button down/up cycles, and scrolling.
 */
abstract class BasePointerMode(
    val rdpEngine: IRdpEngine,
    val transformer: CoordinateTransformer
) {
    // Used for remote scrolling accumulation
    private var accumulatedDx = 0f
    private var accumulatedDy = 0f
    private val deltaPerScroll = 20f

    abstract fun transformPoint(p: PointF): PointF?
    abstract fun doMovePointer(p: PointF, dx: Float = 0f, dy: Float = 0f)
    abstract fun doRemoteDrag(button: PointerButton, p: PointF, dx: Float = 0f, dy: Float = 0f)

    open fun onGestureStart() {}
    open fun onGestureStop(p: PointF) = doButtonRelease(p)

    fun doButtonDown(button: PointerButton, p: PointF) {
        transformPoint(p)?.let { pt ->
            val flags = buttonToDownFlags(button)
            if (flags != 0) {
                rdpEngine.sendPointerEvent(flags, pt.x.toInt(), pt.y.toInt())
            }
        }
    }

    fun doButtonUp(button: PointerButton, p: PointF) {
        transformPoint(p)?.let { pt ->
            val flags = buttonToUpFlags(button)
            if (flags != 0) {
                rdpEngine.sendPointerEvent(flags, pt.x.toInt(), pt.y.toInt())
            }
        }
    }

    fun doButtonRelease(p: PointF) {
        transformPoint(p)?.let { pt ->
            rdpEngine.sendPointerEvent(RdpPointerFlags.LEFT_BUTTON_UP, pt.x.toInt(), pt.y.toInt())
        }
    }

    open fun doClick(button: PointerButton, p: PointF) {
        doButtonDown(button, p)
        doButtonUp(button, p)
    }

    fun doDoubleClick(button: PointerButton, p: PointF) {
        doClick(button, p)
        doClick(button, p)
    }

    fun doRemoteScroll(focus: PointF, dx: Float, dy: Float) {
        accumulatedDx += dx
        accumulatedDy += dy

        val pt = transformPoint(focus) ?: PointF(0f, 0f)
        val tx = pt.x.toInt()
        val ty = pt.y.toInt()

        while (abs(accumulatedDx) >= deltaPerScroll) {
            if (accumulatedDx > 0) {
                rdpEngine.sendPointerEvent(RdpPointerFlags.SCROLL_LEFT, tx, ty)
                accumulatedDx -= deltaPerScroll
            } else {
                rdpEngine.sendPointerEvent(RdpPointerFlags.SCROLL_RIGHT, tx, ty)
                accumulatedDx += deltaPerScroll
            }
        }

        while (abs(accumulatedDy) >= deltaPerScroll) {
            if (accumulatedDy > 0) {
                rdpEngine.sendPointerEvent(RdpPointerFlags.SCROLL_UP, tx, ty)
                accumulatedDy -= deltaPerScroll
            } else {
                rdpEngine.sendPointerEvent(RdpPointerFlags.SCROLL_DOWN, tx, ty)
                accumulatedDy += deltaPerScroll
            }
        }
    }

    fun doRemoteScrollFromMouse(p: PointF, hs: Float, vs: Float) {
        doRemoteScroll(p, -1f * hs * deltaPerScroll, vs * deltaPerScroll)
    }

    private fun buttonToDownFlags(button: PointerButton): Int = when (button) {
        PointerButton.Left -> RdpPointerFlags.LEFT_BUTTON_DOWN
        PointerButton.Right -> RdpPointerFlags.RIGHT_BUTTON_DOWN
        PointerButton.Middle -> RdpPointerFlags.MIDDLE_BUTTON_DOWN
        PointerButton.WheelUp -> RdpPointerFlags.SCROLL_UP
        PointerButton.WheelDown -> RdpPointerFlags.SCROLL_DOWN
        PointerButton.WheelLeft -> RdpPointerFlags.SCROLL_LEFT
        PointerButton.WheelRight -> RdpPointerFlags.SCROLL_RIGHT
        PointerButton.None -> RdpPointerFlags.MOVE
    }

    private fun buttonToUpFlags(button: PointerButton): Int = when (button) {
        PointerButton.Left -> RdpPointerFlags.LEFT_BUTTON_UP
        PointerButton.Right -> RdpPointerFlags.RIGHT_BUTTON_UP
        PointerButton.Middle -> RdpPointerFlags.MIDDLE_BUTTON_UP
        else -> 0
    }
}

/**
 * Direct Touch Mode:
 * Direct tap-to-click at touch coordinates (toFb(p)), two-finger scroll/pan,
 * pinch-to-zoom, and edge coercion (coerceToFbEdge) so users can tap 1-pixel
 * remote taskbars even with letterboxing.
 */
class DirectPointerMode(
    rdpEngine: IRdpEngine,
    transformer: CoordinateTransformer
) : BasePointerMode(rdpEngine, transformer) {

    override fun transformPoint(p: PointF): PointF? = transformer.toFb(p)

    override fun doMovePointer(p: PointF, dx: Float, dy: Float) {
        val pt = transformPoint(p) ?: coerceToFbEdge(p)
        pt?.let {
            rdpEngine.sendPointerEvent(RdpPointerFlags.MOVE, it.x.toInt(), it.y.toInt())
        }
    }

    override fun doRemoteDrag(button: PointerButton, p: PointF, dx: Float, dy: Float) {
        val pt = transformPoint(p) ?: coerceToFbEdge(p)
        pt?.let {
            val flags = RdpPointerFlags.MOVE or RdpPointerFlags.PTR_FLAGS_BUTTON1 or RdpPointerFlags.PTR_FLAGS_DOWN
            rdpEngine.sendPointerEvent(flags, it.x.toInt(), it.y.toInt())
        }
    }

    override fun doClick(button: PointerButton, p: PointF) {
        if (transformPoint(p) != null) {
            super.doClick(button, p)
        } else if (button == PointerButton.Left) {
            // When user taps outside the frame (letterboxed black bars), coerce to frame edge
            coerceToFbEdge(p)?.let { edgePt ->
                rdpEngine.sendPointerEvent(RdpPointerFlags.LEFT_BUTTON_DOWN, edgePt.x.toInt(), edgePt.y.toInt())
                rdpEngine.sendPointerEvent(RdpPointerFlags.LEFT_BUTTON_UP, edgePt.x.toInt(), edgePt.y.toInt())
            }
        }
    }

    /**
     * Coerces viewport screen coordinate to the nearest framebuffer edge pixel.
     */
    fun coerceToFbEdge(p: PointF): PointF? = transformer.coerceToFbEdgeDesktop(p)

    /**
     * Handles two-finger pan gesture on the remote desktop viewport.
     */
    fun handleTwoFingerPan(deltaX: Float, deltaY: Float) {
        transformer.applyPan(deltaX, deltaY)
    }

    /**
     * Handles two-finger pinch-to-zoom around a focal point.
     */
    fun handlePinchZoom(scaleFactor: Float, focusX: Float, focusY: Float) {
        transformer.applyZoom(scaleFactor, focusX, focusY)
    }
}

/**
 * Touchpad / Mouse Pointer Mode:
 * Relative cursor tracking, auto-centering viewport, and libinput 3-tier
 * physical pointer acceleration.
 */
class RelativePointerMode(
    rdpEngine: IRdpEngine,
    transformer: CoordinateTransformer,
    val accelerator: PointerAcceleration = DefaultPointerAcceleration(),
    var dpi: Float = 160f
) : BasePointerMode(rdpEngine, transformer) {

    val pointerPosition = PointF(
        (transformer.remoteWidth / 2).toFloat(),
        (transformer.remoteHeight / 2).toFloat()
    )

    var isDragging: Boolean = false
        private set

    var autoCenterEnabled: Boolean = true

    override fun transformPoint(p: PointF): PointF = pointerPosition

    override fun doMovePointer(p: PointF, dx: Float, dy: Float) {
        doMovePointer(dx, dy, accelerate = true)
    }

    fun doMovePointer(dx: Float, dy: Float, accelerate: Boolean = true) {
        val xLimit = max(0, transformer.remoteWidth - 1).toFloat()
        val yLimit = max(0, transformer.remoteHeight - 1).toFloat()
        if (xLimit < 0f || yLimit < 0f) return

        val (adx, ady) = if (accelerate) {
            accelerator.computeDelta(dx, dy, dpi, transformer.scale)
        } else {
            Pair(dx, dy)
        }

        pointerPosition.x = (pointerPosition.x + adx).coerceIn(0f, xLimit)
        pointerPosition.y = (pointerPosition.y + ady).coerceIn(0f, yLimit)

        val tx = pointerPosition.x.toInt()
        val ty = pointerPosition.y.toInt()

        val flags = if (isDragging) {
            RdpPointerFlags.MOVE or RdpPointerFlags.PTR_FLAGS_BUTTON1 or RdpPointerFlags.PTR_FLAGS_DOWN
        } else {
            RdpPointerFlags.MOVE
        }
        rdpEngine.sendPointerEvent(flags, tx, ty)

        if (autoCenterEnabled) {
            autoCenterViewport()
        }
    }

    override fun doRemoteDrag(button: PointerButton, p: PointF, dx: Float, dy: Float) {
        isDragging = true
        doButtonDown(button, pointerPosition)
        doMovePointer(dx, dy, accelerate = true)
    }

    override fun onGestureStop(p: PointF) {
        if (isDragging) {
            isDragging = false
            doButtonUp(PointerButton.Left, pointerPosition)
        }
        super.onGestureStop(pointerPosition)
    }

    /**
     * Auto-centers the viewport around the current virtual pointer position
     * if the remote frame is zoomed in larger than the viewport.
     */
    fun autoCenterViewport() {
        transformer.autoCenterOn(pointerPosition.x, pointerPosition.y)
    }

    fun setCursorPosition(x: Float, y: Float) {
        val xLimit = max(0, transformer.remoteWidth - 1).toFloat()
        val yLimit = max(0, transformer.remoteHeight - 1).toFloat()
        pointerPosition.x = x.coerceIn(0f, xLimit)
        pointerPosition.y = y.coerceIn(0f, yLimit)
    }
}

/**
 * Disambiguates two-finger gestures between Pinch-to-Zoom and Two-Finger Scroll/Pan
 * based on angular differential of touch paths (AVNC TouchHandler pattern).
 */
class SwipeVsScale(
    val scaleAngleThresholdDeg: Double = 45.0,
    val swipeAngleThresholdDeg: Double = 30.0
) {
    enum class Decision {
        SCALE,
        SWIPE,
        AMBIGUOUS
    }

    fun decide(p1Start: PointF, p1Current: PointF, p2Start: PointF, p2Current: PointF): Decision {
        val t1 = angleTheta(p1Start, p1Current)
        val t2 = angleTheta(p2Start, p2Current)
        val diff = abs(t1 - t2)
        val normalizedDiff = if (diff > 180.0) 360.0 - diff else diff

        return when {
            normalizedDiff > scaleAngleThresholdDeg -> Decision.SCALE
            normalizedDiff < swipeAngleThresholdDeg -> Decision.SWIPE
            else -> Decision.AMBIGUOUS
        }
    }

    private fun angleTheta(p1: PointF, p2: PointF): Double {
        val theta = atan2((p2.y - p1.y).toDouble(), (p2.x - p1.x).toDouble())
        val deg = (theta / PI) * 180.0
        return (deg + 360.0) % 360.0
    }
}
