package com.freerdp.core.engine

import android.content.Context
import android.graphics.Bitmap
import com.freerdp.freerdpcore.services.LibFreeRDP

/**
 * Abstraction over the [LibFreeRDP] static JNI surface.
 *
 * Two purposes:
 * 1. **JVM testability** — unit tests inject a fake implementation to drive the full
 *    connect/disconnect/callback lifecycle without packaged `.so` files.
 * 2. **Graceful degradation** — the JNI implementation guards every native call with
 *    [isNativeLibraryLoaded] and `UnsatisfiedLinkError` handling, so a partial or
 *    mismatched native build degrades to typed failures instead of crashing.
 *
 * All implementations must be thread-safe: they are called from the engine's IO
 * dispatcher, from input-dispatch threads, and (via registered callbacks) from
 * JNI-attached native worker threads.
 */
interface FreeRdpNative {
    /** True when the four FreeRDP native libraries loaded successfully. */
    fun isNativeLibraryLoaded(): Boolean

    /** The first library load failure, or null when loaded (surfaces LibFreeRDP.getLoadError). */
    fun getNativeLoadError(): Throwable?

    /** Allocates a native freerdp instance; 0 on failure (never throws). */
    fun newInstance(context: Context?): Long

    /** Frees a native instance (never throws). */
    fun freeInstance(inst: Long)

    /** Signals the native session to abort/end (never throws). */
    fun disconnectSession(inst: Long): Boolean

    /**
     * Starts the native connection attempt. Upstream JNI spawns a worker thread and
     * returns immediately — the real result arrives asynchronously through the
     * [LibFreeRDP.NativeCallbacks.onConnectionSuccess]/[LibFreeRDP.NativeCallbacks.onConnectionFailure]
     * callbacks. Returns false only when the worker thread could not be created.
     */
    fun startConnection(inst: Long): Boolean

    fun parseArguments(inst: Long, args: Array<String>): Boolean

    fun sendCursorEvent(inst: Long, x: Int, y: Int, flags: Int): Boolean

    fun sendKeyEvent(inst: Long, keycode: Int, down: Boolean): Boolean

    fun sendUnicodeEvent(inst: Long, unicode: Int, down: Boolean): Boolean

    fun sendClipboardText(inst: Long, text: String): Boolean

    fun sendMonitorLayout(inst: Long, width: Int, height: Int): Boolean

    fun updateGraphics(inst: Long, bitmap: Bitmap, x: Int, y: Int, width: Int, height: Int): Boolean

    /** Last native error message for [inst]; null when unavailable. */
    fun getLastErrorMessage(inst: Long): String?

    fun setNativeCallbacks(callbacks: LibFreeRDP.NativeCallbacks?)

    fun getNativeCallbacks(): LibFreeRDP.NativeCallbacks?
}

/**
 * Production implementation delegating to the real [LibFreeRDP] static JNI bridge.
 * Every call is guarded so JVM execution (or a symbol mismatch against the packaged
 * `.so`) degrades safely instead of throwing.
 */
object JniFreeRdpNative : FreeRdpNative {

    override fun isNativeLibraryLoaded(): Boolean = LibFreeRDP.isNativeLoaded()

    override fun getNativeLoadError(): Throwable? = LibFreeRDP.getLoadError()

    override fun newInstance(context: Context?): Long {
        if (!LibFreeRDP.isNativeLoaded()) return 0L
        return try {
            LibFreeRDP.freerdp_new(context)
        } catch (e: UnsatisfiedLinkError) {
            0L
        }
    }

    override fun freeInstance(inst: Long) {
        if (!LibFreeRDP.isNativeLoaded()) return
        try {
            LibFreeRDP.freerdp_free(inst)
        } catch (e: UnsatisfiedLinkError) {
            // Symbol mismatch — nothing we can do; the pointer is abandoned safely
            // because the engine only ever calls this once per claimed instance.
        }
    }

    override fun disconnectSession(inst: Long): Boolean {
        if (!LibFreeRDP.isNativeLoaded()) return false
        return try {
            LibFreeRDP.freerdp_disconnect(inst)
        } catch (e: UnsatisfiedLinkError) {
            false
        }
    }

    override fun startConnection(inst: Long): Boolean {
        if (!LibFreeRDP.isNativeLoaded()) return false
        return try {
            LibFreeRDP.freerdp_connect(inst)
        } catch (e: UnsatisfiedLinkError) {
            false
        }
    }

    override fun parseArguments(inst: Long, args: Array<String>): Boolean {
        if (!LibFreeRDP.isNativeLoaded()) return false
        return try {
            LibFreeRDP.freerdp_parse_arguments(inst, args)
        } catch (e: UnsatisfiedLinkError) {
            false
        }
    }

    override fun sendCursorEvent(inst: Long, x: Int, y: Int, flags: Int): Boolean {
        if (!LibFreeRDP.isNativeLoaded()) return false
        return try {
            LibFreeRDP.freerdp_send_cursor_event(inst, x, y, flags)
        } catch (e: UnsatisfiedLinkError) {
            false
        }
    }

    override fun sendKeyEvent(inst: Long, keycode: Int, down: Boolean): Boolean {
        if (!LibFreeRDP.isNativeLoaded()) return false
        return try {
            LibFreeRDP.freerdp_send_key_event(inst, keycode, down)
        } catch (e: UnsatisfiedLinkError) {
            false
        }
    }

    override fun sendUnicodeEvent(inst: Long, unicode: Int, down: Boolean): Boolean {
        if (!LibFreeRDP.isNativeLoaded()) return false
        return try {
            LibFreeRDP.freerdp_send_unicodekey_event(inst, unicode, down)
        } catch (e: UnsatisfiedLinkError) {
            false
        }
    }

    override fun sendClipboardText(inst: Long, text: String): Boolean {
        if (!LibFreeRDP.isNativeLoaded()) return false
        return try {
            LibFreeRDP.freerdp_send_clipboard_data(inst, text)
        } catch (e: UnsatisfiedLinkError) {
            false
        }
    }

    override fun sendMonitorLayout(inst: Long, width: Int, height: Int): Boolean {
        if (!LibFreeRDP.isNativeLoaded()) return false
        return try {
            LibFreeRDP.freerdp_send_monitor_layout(inst, width, height)
        } catch (e: UnsatisfiedLinkError) {
            false
        }
    }

    override fun updateGraphics(
        inst: Long,
        bitmap: Bitmap,
        x: Int,
        y: Int,
        width: Int,
        height: Int
    ): Boolean {
        if (!LibFreeRDP.isNativeLoaded()) return false
        return try {
            LibFreeRDP.freerdp_update_graphics(inst, bitmap, x, y, width, height)
        } catch (e: UnsatisfiedLinkError) {
            false
        }
    }

    override fun getLastErrorMessage(inst: Long): String? {
        if (!LibFreeRDP.isNativeLoaded()) return null
        return try {
            LibFreeRDP.freerdp_get_last_error_string(inst)
        } catch (e: UnsatisfiedLinkError) {
            null
        }
    }

    override fun setNativeCallbacks(callbacks: LibFreeRDP.NativeCallbacks?) {
        LibFreeRDP.setNativeCallbacks(callbacks)
    }

    override fun getNativeCallbacks(): LibFreeRDP.NativeCallbacks? = LibFreeRDP.getNativeCallbacks()
}
