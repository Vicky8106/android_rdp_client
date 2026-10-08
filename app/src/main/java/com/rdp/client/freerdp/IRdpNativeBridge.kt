package com.rdp.client.freerdp

import android.content.Context
import android.graphics.Bitmap

/**
 * Abstraction decoupling Kotlin facade from direct JNI external invocations.
 * Enables zero-crash testing on JVM / Robolectric.
 */
interface IRdpNativeBridge {
    fun newInstance(context: Context?): Long
    fun freeInstance(instance: Long)
    fun connect(instance: Long, params: RdpConnectionParameters? = null): Boolean
    fun disconnect(instance: Long): Boolean
    fun updateGraphics(instance: Long, bitmap: Bitmap, x: Int, y: Int, w: Int, h: Int): Boolean
    fun sendCursorEvent(instance: Long, x: Int, y: Int, flags: Int): Boolean
    fun sendKeyEvent(instance: Long, scancode: Int, extended: Boolean, down: Boolean): Boolean
    fun sendUnicodeKeyEvent(instance: Long, codePoint: Int): Boolean
    fun getVersion(): String
    fun getLastError(instance: Long): String?
}

/**
 * Production implementation invoking actual JNI C functions in libfreerdp-android.so.
 */
class RealRdpNativeBridge : IRdpNativeBridge {
    override fun newInstance(context: Context?): Long = LibFreeRDP.nativeNewInstance(context)
    override fun freeInstance(instance: Long) = LibFreeRDP.nativeFreeInstance(instance)
    override fun connect(instance: Long, params: RdpConnectionParameters?): Boolean =
        LibFreeRDP.nativeConnect(instance, params)
    override fun disconnect(instance: Long): Boolean = LibFreeRDP.nativeDisconnect(instance)
    override fun updateGraphics(instance: Long, bitmap: Bitmap, x: Int, y: Int, w: Int, h: Int): Boolean =
        LibFreeRDP.nativeUpdateGraphics(instance, bitmap, x, y, w, h)
    override fun sendCursorEvent(instance: Long, x: Int, y: Int, flags: Int): Boolean =
        LibFreeRDP.nativeSendCursorEvent(instance, x, y, flags)
    override fun sendKeyEvent(instance: Long, scancode: Int, extended: Boolean, down: Boolean): Boolean =
        LibFreeRDP.nativeSendKeyEvent(instance, scancode, extended, down)
    override fun sendUnicodeKeyEvent(instance: Long, codePoint: Int): Boolean =
        LibFreeRDP.nativeSendUnicodeKeyEvent(instance, codePoint)
    override fun getVersion(): String = LibFreeRDP.nativeGetVersion()
    override fun getLastError(instance: Long): String? = null
}

/**
 * Headless mock implementation providing simulated session behavior without native libraries.
 */
class MockRdpNativeBridge(
    var autoConnectSuccess: Boolean = true,
    var connectionDelayMs: Long = 20L
) : IRdpNativeBridge {
    private val nextInstanceId = java.util.concurrent.atomic.AtomicLong(2000L)
    private val activeInstances = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

    override fun newInstance(context: Context?): Long {
        val id = nextInstanceId.getAndIncrement()
        activeInstances.add(id)
        return id
    }

    override fun freeInstance(instance: Long) {
        activeInstances.remove(instance)
    }

    override fun connect(instance: Long, params: RdpConnectionParameters?): Boolean {
        if (!activeInstances.contains(instance)) return false
        // Simulate asynchronous connection callback
        Thread {
            try {
                if (connectionDelayMs > 0) {
                    Thread.sleep(connectionDelayMs)
                }
                if (!activeInstances.contains(instance)) return@Thread
                if (autoConnectSuccess) {
                    LibFreeRDP.onConnectionSuccess(instance)
                    LibFreeRDP.onGraphicsUpdate(instance, 0, 0, params?.width ?: 1920, params?.height ?: 1080)
                } else {
                    LibFreeRDP.onConnectionFailure(instance, 1001, "Mock connection failed")
                }
            } catch (e: InterruptedException) {
                // Thread interrupted
            }
        }.start()
        return true
    }

    override fun disconnect(instance: Long): Boolean {
        LibFreeRDP.onDisconnected(instance)
        return true
    }

    override fun updateGraphics(instance: Long, bitmap: Bitmap, x: Int, y: Int, w: Int, h: Int): Boolean = true
    override fun sendCursorEvent(instance: Long, x: Int, y: Int, flags: Int): Boolean = true
    override fun sendKeyEvent(instance: Long, scancode: Int, extended: Boolean, down: Boolean): Boolean = true
    override fun sendUnicodeKeyEvent(instance: Long, codePoint: Int): Boolean = true
    override fun getVersion(): String = "FreeRDP 3.5.1-mock"
    override fun getLastError(instance: Long): String? = null
}
