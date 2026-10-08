package com.rdp.client.ui.session.viewport

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.os.Build
import android.util.AttributeSet
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.OverScroller
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.rdp.client.freerdp.RdpSession
import com.rdp.client.model.GestureStyle
import com.rdp.client.ui.session.input.IFrameCoordinateTransformer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * High-performance hardware-accelerated remote desktop viewport.
 * Extends SurfaceView, integrating pinch-to-zoom, inertial panning,
 * double-buffered framebuffer updates, and strict coordinate conversions.
 */
open class FrameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr),
    SurfaceHolder.Callback,
    ScaleGestureDetector.OnScaleGestureListener,
    GestureDetector.OnGestureListener,
    GestureDetector.OnDoubleTapListener,
    IFrameCoordinateTransformer {

    private val TAG = "FrameView"

    /** Viewport mathematical transformation engine */
    val transform = ViewportTransform()

    /** Framebuffer double-buffering cache */
    private val framebufferManager = FramebufferManager { dirtyRect ->
        requestRender(dirtyRect)
    }

    /** Inertial fling scroller */
    private val scroller = OverScroller(context)
    private var isFlinging = false

    /** Paint with bilinear filtering enabled for smooth magnification */
    private val framePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    /** Gesture detectors */
    private val scaleDetector = ScaleGestureDetector(context, this).apply {
        isQuickScaleEnabled = false
    }
    private val gestureDetector = GestureDetector(context, this)

    /** External zoom and viewport listeners */
    private var zoomChangedListener: ((Float) -> Unit)? = null
    private var viewportChangedListener: ((ViewportTransform) -> Unit)? = null

    /** Flag indicating whether the underlying surface is valid */
    private var isSurfaceReady = false

    /** Pause rendering in BACKGROUND view mode */
    var isRenderingPaused: Boolean = false
        set(value) {
            field = value
            if (!value) renderFrame()
        }

    /** Current gesture style */
    var gestureStyle: GestureStyle = GestureStyle.AUTO

    init {
        holder.addCallback(this)
        isFocusable = true
        isFocusableInTouchMode = true
    }

    // -------------------------------------------------------------------------
    // Surface Lifecycle & Rendering
    // -------------------------------------------------------------------------

    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.i(TAG, "Surface created")
        isSurfaceReady = true
        renderFrame()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Log.i(TAG, "Surface changed: ${width}x${height}")
        transform.setViewportDimensions(width, height)
        isSurfaceReady = true
        renderFrame()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.i(TAG, "Surface destroyed")
        isSurfaceReady = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        transform.setViewportDimensions(w, h)
        viewportChangedListener?.invoke(transform)
    }

    /**
     * Triggers a redraw pass on the surface.
     */
    fun requestRender(dirtyRect: Rect? = null) {
        if (!isSurfaceReady || isRenderingPaused) return

        val canvas = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                holder.lockHardwareCanvas()
            } else {
                holder.lockCanvas()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to lock surface canvas: ${e.message}")
            null
        } ?: return

        try {
            // Draw background letterbox/pillarbox color
            canvas.drawColor(Color.BLACK)

            framebufferManager.withFrontBitmap { bitmap ->
                val matrix = transform.getTransformationMatrix()
                canvas.drawBitmap(bitmap, matrix, framePaint)
            }
        } finally {
            try {
                holder.unlockCanvasAndPost(canvas)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unlock surface canvas: ${e.message}")
            }
        }
    }

    fun renderFrame() {
        requestRender(null)
    }

    // -------------------------------------------------------------------------
    // Session Binding & Framebuffer Controls
    // -------------------------------------------------------------------------

    fun bindSession(session: RdpSession) {
        val lifecycleScope = findViewTreeLifecycleOwner()?.lifecycleScope
            ?: CoroutineScope(Dispatchers.Main)

        transform.setFramebufferDimensions(session.parameters.width, session.parameters.height)
        framebufferManager.bindSession(session, lifecycleScope)
        renderFrame()
    }

    fun unbindSession() {
        framebufferManager.unbindSession()
    }

    fun setRemoteResolution(width: Int, height: Int) {
        transform.setFramebufferDimensions(width, height)
        framebufferManager.allocateBuffers(width, height)
        renderFrame()
    }

    fun setDesktopResolution(width: Int, height: Int) {
        setRemoteResolution(width, height)
    }

    // -------------------------------------------------------------------------
    // Viewport & Zoom Mode Public API
    // -------------------------------------------------------------------------

    fun setZoomMode(mode: ZoomMode) {
        when (mode) {
            ZoomMode.FIT_TO_SCREEN -> transform.fitToScreen()
            ZoomMode.DEVICE_NATIVE_1_1 -> transform.setDeviceNative()
            ZoomMode.CUSTOM -> { /* preserve current zoom */ }
        }
        notifyZoomChanged()
        renderFrame()
    }

    fun resetZoom() {
        transform.fitToScreen()
        notifyZoomChanged()
        renderFrame()
    }

    fun setZoom100() {
        transform.setDeviceNative()
        notifyZoomChanged()
        renderFrame()
    }

    var isZoomLocked: Boolean
        get() = transform.isZoomLocked
        set(value) {
            transform.isZoomLocked = value
        }

    fun getZoomScale(): Float = transform.zoomFactor

    fun setZoomScale(scale: Float) {
        transform.setZoomFactor(scale)
        notifyZoomChanged()
        renderFrame()
    }

    fun zoomIn(delta: Float = 0.25f) {
        setZoomScale(transform.zoomFactor + delta)
    }

    fun zoomOut(delta: Float = 0.25f) {
        setZoomScale(transform.zoomFactor - delta)
    }

    fun setOnZoomChangedListener(listener: (Float) -> Unit) {
        this.zoomChangedListener = listener
    }

    fun setOnViewportChangedListener(listener: (ViewportTransform) -> Unit) {
        this.viewportChangedListener = listener
    }

    private fun notifyZoomChanged() {
        zoomChangedListener?.invoke(transform.zoomFactor)
        viewportChangedListener?.invoke(transform)
    }


    public override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Will be called when screen orientation or layout shifts
        transform.setViewportDimensions(width, height)
        renderFrame()
    }

    // -------------------------------------------------------------------------
    // Coordinate Conversions
    // -------------------------------------------------------------------------

    fun screenToRemote(screenX: Float, screenY: Float): PointF? =
        transform.screenToRemoteStrict(screenX, screenY)

    fun screenToRemoteCoerced(screenX: Float, screenY: Float): PointF =
        transform.screenToRemoteCoerced(screenX, screenY)

    fun remoteToScreen(remoteX: Float, remoteY: Float): PointF =
        transform.remoteToScreen(remoteX, remoteY)

    // -------------------------------------------------------------------------
    // IFrameCoordinateTransformer Implementation
    // -------------------------------------------------------------------------

    override val fbWidth: Int
        get() = transform.fbWidth

    override val fbHeight: Int
        get() = transform.fbHeight

    override fun toFb(vpX: Float, vpY: Float): PointF? =
        transform.screenToRemoteStrict(vpX, vpY)

    override fun toVp(fbX: Float, fbY: Float): PointF =
        transform.remoteToScreen(fbX, fbY)

    override fun panFrame(dx: Float, dy: Float) {
        transform.applyPan(dx, dy)
        renderFrame()
    }

    override fun zoomFrame(scaleFactor: Float, focusX: Float, focusY: Float) {
        transform.applyScaleGesture(scaleFactor, focusX, focusY)
        renderFrame()
    }

    override val safeAreaCenterX: Float
        get() = if (width > 0) width / 2f else transform.viewportWidth / 2f

    override val safeAreaCenterY: Float
        get() = if (height > 0) height / 2f else transform.viewportHeight / 2f

    // -------------------------------------------------------------------------
    // Touch & Gesture Event Handling
    // -------------------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Stop active kinetic fling on touch down
        if (event.actionMasked == MotionEvent.ACTION_DOWN && isFlinging) {
            scroller.forceFinished(true)
            isFlinging = false
        }

        val scaleHandled = scaleDetector.onTouchEvent(event)
        val gestureHandled = gestureDetector.onTouchEvent(event)

        return scaleHandled || gestureHandled || super.onTouchEvent(event)
    }

    // --- ScaleGestureDetector.OnScaleGestureListener ---

    override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
        return !transform.isZoomLocked
    }

    override fun onScale(detector: ScaleGestureDetector): Boolean {
        val applied = transform.applyScaleGesture(detector.scaleFactor, detector.focusX, detector.focusY)
        if (applied) {
            renderFrame()
            notifyZoomChanged()
        }
        return true
    }

    override fun onScaleEnd(detector: ScaleGestureDetector) {
        notifyZoomChanged()
    }

    // --- GestureDetector.OnGestureListener ---

    override fun onDown(e: MotionEvent): Boolean = true

    override fun onShowPress(e: MotionEvent) {}

    override fun onSingleTapUp(e: MotionEvent): Boolean = false

    override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
        transform.applyPan(-distanceX, -distanceY)
        renderFrame()
        viewportChangedListener?.invoke(transform)
        return true
    }

    override fun onLongPress(e: MotionEvent) {}

    override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
        val frameW = (transform.fbWidth * transform.effectiveScale).toInt()
        val frameH = (transform.fbHeight * transform.effectiveScale).toInt()

        val minX = if (frameW > width) width - frameW else 0
        val minY = if (frameH > height) height - frameH else 0

        scroller.fling(
            transform.translationX.toInt(),
            transform.translationY.toInt(),
            velocityX.toInt(),
            velocityY.toInt(),
            minX, 0,
            minY, 0
        )

        isFlinging = true
        postInvalidateOnAnimation()
        return true
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            val currX = scroller.currX.toFloat()
            val currY = scroller.currY.toFloat()

            transform.applyPan(currX - transform.translationX, currY - transform.translationY)
            renderFrame()
            postInvalidateOnAnimation()
        } else {
            isFlinging = false
        }
    }

    // --- GestureDetector.OnDoubleTapListener ---

    override fun onSingleTapConfirmed(e: MotionEvent): Boolean = false

    override fun onDoubleTap(e: MotionEvent): Boolean {
        if (transform.isZoomLocked) return false

        if (transform.zoomFactor > 1.05f) {
            // If already zoomed in, reset to Fit to Screen
            resetZoom()
        } else {
            // Zoom in to 2.0x centered at double-tap focus
            transform.setZoomFactor(2.0f, e.x, e.y)
            notifyZoomChanged()
            renderFrame()
        }
        return true
    }

    override fun onDoubleTapEvent(e: MotionEvent): Boolean = false
}
