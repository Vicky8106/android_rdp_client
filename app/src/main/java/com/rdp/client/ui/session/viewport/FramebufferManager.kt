package com.rdp.client.ui.session.viewport

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.rdp.client.freerdp.RdpSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Manages double-buffered bitmap caches for the remote desktop framebuffer.
 * Thread-safe: coordinates between FreeRDP worker threads and the render thread.
 */
class FramebufferManager(
    private val onFrameInvalidated: (dirtyRect: Rect) -> Unit
) {
    private val TAG = "FramebufferManager"
    private val bufferLock = ReentrantLock()

    var width: Int = 0
        private set
    var height: Int = 0
        private set

    /** Active bitmap presented to the surface/renderer */
    private var frontBitmap: Bitmap? = null

    /** Back buffer receiving pixel blits from native memory */
    private var backBitmap: Bitmap? = null

    private var activeSession: RdpSession? = null
    private var dirtyCollectorJob: Job? = null

    /**
     * Allocates or reallocates front and back buffers matching remote dimensions.
     */
    fun allocateBuffers(newWidth: Int, newHeight: Int) {
        if (newWidth <= 0 || newHeight <= 0) return

        bufferLock.withLock {
            if (width == newWidth && height == newHeight && frontBitmap != null && backBitmap != null) {
                return
            }

            Log.i(TAG, "Allocating double framebuffer: ${newWidth}x${newHeight} (ARGB_8888)")
            width = newWidth
            height = newHeight

            // Recycle existing bitmaps
            frontBitmap?.recycle()
            backBitmap?.recycle()

            frontBitmap = Bitmap.createBitmap(newWidth, newHeight, Bitmap.Config.ARGB_8888)
            backBitmap = Bitmap.createBitmap(newWidth, newHeight, Bitmap.Config.ARGB_8888)

            // Fill default dark background (#1E1E2E)
            frontBitmap?.eraseColor(0xFF1E1E2E.toInt())
            backBitmap?.eraseColor(0xFF1E1E2E.toInt())
        }

        onFrameInvalidated(Rect(0, 0, newWidth, newHeight))
    }

    /**
     * Binds an active RdpSession and begins collecting dirty rectangle updates.
     */
    fun bindSession(session: RdpSession, scope: CoroutineScope) {
        dirtyCollectorJob?.cancel()
        activeSession = session

        allocateBuffers(session.parameters.width, session.parameters.height)

        dirtyCollectorJob = session.dirtyRectFlow
            .onEach { dirtyRect ->
                handleDirtyRectUpdate(session, dirtyRect)
            }
            .launchIn(scope)
    }

    /**
     * Unbinds session and stops dirty rect updates.
     */
    fun unbindSession() {
        dirtyCollectorJob?.cancel()
        dirtyCollectorJob = null
        activeSession = null
    }

    /**
     * Blits the dirty rectangle from native memory into the back buffer,
     * synchronizes the front buffer, and requests surface invalidation.
     */
    private fun handleDirtyRectUpdate(session: RdpSession, dirtyRect: Rect) {
        if (width <= 0 || height <= 0) return

        // Clip rectangle against framebuffer boundaries
        val clipped = Rect(
            dirtyRect.left.coerceIn(0, width),
            dirtyRect.top.coerceIn(0, height),
            dirtyRect.right.coerceIn(0, width),
            dirtyRect.bottom.coerceIn(0, height)
        )

        if (clipped.isEmpty) return

        bufferLock.withLock {
            val back = backBitmap ?: return
            val front = frontBitmap ?: return

            // 1. Invoke native updateGraphics to blit pixels into backBitmap
            val updated = session.updateGraphics(back, clipped)
            if (updated) {
                // 2. Safely synchronize front buffer with updated dirty region
                val w = clipped.width()
                val h = clipped.height()
                if (w > 0 && h > 0) {
                    val pixels = IntArray(w * h)
                    back.getPixels(pixels, 0, w, clipped.left, clipped.top, w, h)
                    front.setPixels(pixels, 0, w, clipped.left, clipped.top, w, h)
                }
            }
        }

        onFrameInvalidated(clipped)
    }

    /**
     * Safely reads the front bitmap under lock for presentation.
     */
    fun withFrontBitmap(block: (Bitmap) -> Unit) {
        bufferLock.withLock {
            frontBitmap?.let { bmp ->
                if (!bmp.isRecycled) {
                    block(bmp)
                }
            }
        }
    }

    /**
     * Releases memory and recycles bitmaps.
     */
    fun release() {
        unbindSession()
        bufferLock.withLock {
            frontBitmap?.recycle()
            backBitmap?.recycle()
            frontBitmap = null
            backBitmap = null
            width = 0
            height = 0
        }
    }
}
