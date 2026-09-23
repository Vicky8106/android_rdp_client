package com.freerdp.client.ui.session

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas as GCanvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import com.freerdp.client.session.HapticMouseController
import com.freerdp.feature.mouse.CoordinateTransformer
import com.freerdp.feature.mouse.GestureDisambiguationEngine
import com.freerdp.feature.mouse.GestureEventListener

/**
 * Remote desktop surface:
 *  - draws the latest composited frame through the [CoordinateTransformer] viewport
 *    (pan / pinch-zoom aware) on every display VSYNC pulled from the single-slot
 *    FramePacer (blit-latest semantics — never queues stale frames);
 *  - feeds disambiguated touch gestures to the session's mouse controller:
 *    tap = left click, double-tap = double click, long-press = right click,
 *    one-finger pan (direct mode) pans the viewport *without* clicking thanks to the
 *    anti-spurious multi-touch latch, pinch zooms, two-finger drag pans (direct mode)
 *    or drives the scroll wheel (touchpad mode);
 *  - renders the optional virtual cursor when touchpad/cursor mode is active.
 */
class RemoteCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    val transformer = CoordinateTransformer(
        remoteWidth = 1920,
        remoteHeight = 1080,
        viewWidth = 1,
        viewHeight = 1
    )

    /** Injected by the Session screen — created through the AppContainer factory. */
    var gestureEngine: GestureDisambiguationEngine? = null

    var mouseController: HapticMouseController? = null

    /** Provides the freshest backing frame; ownership and locking stay with the session VM. */
    var frameProvider: (() -> Bitmap?)? = null
    var frameLock: Any? = null
    var onFrameBlitted: (() -> Unit)? = null

    var userHasAdjustedZoom = false
        private set

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val cursorFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 33, 150, 243)
        style = Paint.Style.FILL
    }

    private var zoomCallback: ((Float) -> Unit)? = null
    private var scrollAccumulator = 0f
    private var framePacerRef: com.freerdp.feature.telemetry.pacer.FramePacer? = null

    /** The disambiguated gesture surface handed to [GestureDisambiguationEngine]. */
    val gestureListener: GestureEventListener = object : GestureEventListener {
        override fun onSingleTap(screenX: Float, screenY: Float) {
            mouseController?.handleLeftClick(screenX, screenY)
        }

        override fun onDoubleTap(screenX: Float, screenY: Float) {
            mouseController?.handleDoubleClick(screenX, screenY)
        }

        override fun onLongPress(screenX: Float, screenY: Float) {
            mouseController?.handleRightClick(screenX, screenY)
        }

        override fun onPan(deltaX: Float, deltaY: Float) {
            val controller = mouseController
            if (controller != null && controller.isTouchpadMode) {
                controller.handleTouchpadMove(deltaX, deltaY)
            } else if (controller != null && controller.isDragging) {
                val cursor = controller.inner.virtualCursorPosition
                val newDesktopX = cursor.x + deltaX / transformer.scale
                val newDesktopY = cursor.y + deltaY / transformer.scale
                val screenPt = transformer.desktopToScreen(newDesktopX, newDesktopY)
                controller.handleDragMove(screenPt.x, screenPt.y)
                invalidate()
            } else {
                transformer.applyPan(deltaX, deltaY)
                userHasAdjustedZoom = true
                invalidate()
            }
        }

        override fun onPinchZoom(focusX: Float, focusY: Float, scaleFactor: Float) {
            transformer.applyZoom(scaleFactor, focusX, focusY)
            userHasAdjustedZoom = true
            notifyZoom()
            invalidate()
        }

        override fun onTwoFingerScroll(deltaX: Float, deltaY: Float) {
            val controller = mouseController
            if (controller != null && controller.isTouchpadMode) {
                scrollAccumulator += deltaY
                while (scrollAccumulator >= SCROLL_THRESHOLD) {
                    controller.handleScroll(0f, 0f, 1f)
                    scrollAccumulator -= SCROLL_THRESHOLD
                }
                while (scrollAccumulator <= -SCROLL_THRESHOLD) {
                    controller.handleScroll(0f, 0f, -1f)
                    scrollAccumulator += SCROLL_THRESHOLD
                }
            } else {
                // Direct mode: two-finger drag pans the viewport; the multi-touch latch
                // guarantees no spurious click fires on lift-off.
                transformer.applyPan(deltaX, deltaY)
                userHasAdjustedZoom = true
                invalidate()
            }
        }
    }

    private val vsyncCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isAttachedToWindow) return
            val pacer = framePacerRef
            if (pacer != null) {
                // VSYNC-aligned single-slot acquisition (latency_perf §4): the pacer
                // enforces ≤1 blit per vsync period, monotonic timestamps and
                // stale-frame dropping itself — always go through acquireFrameForVsync
                // with the display's frame timestamp.
                val blitted = pacer.acquireFrameForVsync(frameTimeNanos)
                if (blitted != null) {
                    onFrameBlitted?.invoke()
                    invalidate()
                }
            }
            // Re-arm DELAYED by one frame interval, never immediately: an instant
            // re-post makes this callback permanently "due" on a paused test looper,
            // so the message queue can never quiesce (Robolectric waitForIdle livelock).
            // On a device a +16ms delayed frame callback still executes at the very next
            // vsync (Choreographer runs delayed callbacks at the first frame past the
            // deadline), so the blit cadence — and input-to-display latency — are unchanged.
            choreographer.postFrameCallbackDelayed(this, VSYNC_INTERVAL_MS)
        }
    }

    private val choreographer: Choreographer
        get() = Choreographer.getInstance()

    fun bindPacer(pacer: com.freerdp.feature.telemetry.pacer.FramePacer) {
        framePacerRef = pacer
    }

    fun setZoomCallback(callback: (Float) -> Unit) {
        zoomCallback = callback
        notifyZoom()
    }

    fun notifyZoom() {
        zoomCallback?.invoke(transformer.scale)
    }

    /** Fit-to-screen <-> actual pixels toggle driven by the quick-action toolbar. */
    fun toggleFitOrActualPixels() {
        val contentW = transformer.remoteWidth * transformer.scale
        val contentH = transformer.remoteHeight * transformer.scale
        val fits = contentW <= transformer.viewWidth + 0.5f && contentH <= transformer.viewHeight + 0.5f
        if (fits) {
            transformer.setScale(1f)
        } else {
            transformer.resetToFit()
        }
        userHasAdjustedZoom = false
        notifyZoom()
        invalidate()
    }

    fun resetViewportToFit() {
        transformer.resetToFit()
        userHasAdjustedZoom = false
        notifyZoom()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            val firstLayout = oldw == 0 && oldh == 0
            transformer.setViewportSize(w, h)
            if (firstLayout || !userHasAdjustedZoom) {
                transformer.resetToFit()
            }
            notifyZoom()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        try {
            Choreographer.getInstance().postFrameCallback(vsyncCallback)
        } catch (t: Throwable) {
            // Headless/test environments without a choreographer draw on invalidate() only.
        }
    }

    override fun onDetachedFromWindow() {
        try {
            Choreographer.getInstance().removeFrameCallback(vsyncCallback)
        } catch (t: Throwable) {
            // Ignore — no choreographer available.
        }
        super.onDetachedFromWindow()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handled = gestureEngine?.onTouchEvent(event) ?: false
        return handled || super.onTouchEvent(event)
    }

    override fun onDraw(canvas: GCanvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        val lock = frameLock
        val bitmap = frameProvider?.invoke()
        if (bitmap != null && !bitmap.isRecycled) {
            if (lock != null) {
                synchronized(lock) { drawFrame(canvas, bitmap) }
            } else {
                drawFrame(canvas, bitmap)
            }
        }

        val controller = mouseController
        if (controller != null && controller.isCursorVisible) {
            val cursor = controller.inner.virtualCursorPosition
            val point = transformer.desktopToScreen(cursor.x, cursor.y)
            canvas.drawCircle(point.x, point.y, 18f, cursorFillPaint)
            canvas.drawCircle(point.x, point.y, 18f, cursorPaint)
            canvas.drawLine(point.x - 26f, point.y, point.x + 26f, point.y, cursorPaint)
            canvas.drawLine(point.x, point.y - 26f, point.x, point.y + 26f, cursorPaint)
        }
    }

    private fun drawFrame(canvas: GCanvas, bitmap: Bitmap) {
        // Only the active remote region is blitted (backing may be larger after a shrink).
        val srcW = transformer.remoteWidth.toFloat().coerceAtMost(bitmap.width.toFloat())
        val srcH = transformer.remoteHeight.toFloat().coerceAtMost(bitmap.height.toFloat())
        val s = transformer.scale
        val src = android.graphics.Rect(0, 0, srcW.toInt().coerceAtLeast(0), srcH.toInt().coerceAtLeast(0))
        val dst = RectF(
            transformer.translationX,
            transformer.translationY,
            transformer.translationX + srcW * s,
            transformer.translationY + srcH * s
        )
        canvas.drawBitmap(bitmap, src, dst, bitmapPaint)
    }

    /** Reports the current zoom for the HUD chip. */
    fun currentScalePercent(): Int = (transformer.scale * 100).toInt().coerceAtLeast(1)

    companion object {
        /** Pixels of two-finger travel per scroll-wheel notch (touchpad mode). */
        private const val SCROLL_THRESHOLD = 36f

        /** Frame interval used to re-arm the vsync heartbeat (≈60 Hz cadence). */
        private const val VSYNC_INTERVAL_MS = 16L
    }
}
