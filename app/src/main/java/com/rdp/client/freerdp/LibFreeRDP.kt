package com.rdp.client.freerdp

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * High-level FreeRDP JNI Facade bridging Android UI, ViewModels, and input dispatchers
 * to native libfreerdp-android.so.
 *
 * Provides:
 * 1. Safe multi-ABI dynamic library loading with diagnostic status.
 * 2. Pluggable IRdpNativeBridge enabling headless JVM / Robolectric unit testing.
 * 3. Concurrent instance-to-listener registry for reverse JNI callbacks.
 * 4. JNI external method declarations matching android_freerdp.c.
 */
object LibFreeRDP {
    private const val TAG = "LibFreeRDP"

    /** Current dynamic loading status of native shared libraries */
    val loadStatus: NativeLoadStatus

    /** Human-readable explanation if native loading failed; null on success */
    val loadErrorMessage: String?

    /** Whether the native library was successfully loaded and is ready for JNI calls */
    val isNativeLoaded: Boolean
        get() = loadStatus == NativeLoadStatus.LOADED

    /** Native bridge implementation (real JNI bridge or mock bridge for testing) */
    private var nativeBridge: IRdpNativeBridge

    /** Active session listener registry mapping native instance pointer (jlong) to listener */
    private val sessionListeners = ConcurrentHashMap<Long, RdpSessionListener>()

    init {
        val (status, errorMsg) = loadNativeLibraries()
        loadStatus = status
        loadErrorMessage = errorMsg

        nativeBridge = if (status == NativeLoadStatus.LOADED) {
            RealRdpNativeBridge()
        } else {
            Log.w(TAG, "Native library not loaded ($status: $errorMsg). Initializing fallback mock bridge.")
            MockRdpNativeBridge()
        }
    }

    /**
     * Attempts to dynamically load native libraries with ABI verification.
     */
    private fun loadNativeLibraries(): Pair<NativeLoadStatus, String?> {
        // 1. Detect if running on desktop JVM during tests
        val vmName = System.getProperty("java.vm.name") ?: ""
        val isAndroidRuntime = try {
            Class.forName("android.os.Build")
            Build.VERSION.SDK_INT > 0
        } catch (e: Throwable) {
            false
        }

        if (!isAndroidRuntime && (vmName.contains("OpenJDK", ignoreCase = true) || vmName.contains("HotSpot", ignoreCase = true))) {
            return NativeLoadStatus.JVM_TEST_ENVIRONMENT to "Running on host JVM ($vmName)"
        }

        // 2. Validate ABI compatibility
        val supportedAbis = try {
            Build.SUPPORTED_ABIS ?: emptyArray()
        } catch (e: Throwable) {
            emptyArray()
        }

        val hasCompatibleAbi = supportedAbis.any { it == "arm64-v8a" || it == "x86_64" }
        if (!hasCompatibleAbi && supportedAbis.isNotEmpty()) {
            val abiList = supportedAbis.joinToString(", ")
            return NativeLoadStatus.UNSUPPORTED_ABI to "Device ABIs [$abiList] do not include arm64-v8a or x86_64"
        }

        // 3. Attempt loading dependencies in order
        val optionalDependencies = listOf("c++_shared", "crypto", "ssl", "winpr3", "freerdp3", "freerdp-client3")
        for (lib in optionalDependencies) {
            try {
                System.loadLibrary(lib)
            } catch (ignored: UnsatisfiedLinkError) {
                // Prebuilts may be statically linked into freerdp-android or bundled directly
            } catch (ignored: Throwable) {
                // Ignore non-fatal library load errors
            }
        }

        // 4. Load the primary JNI bridge library
        return try {
            System.loadLibrary("freerdp-android")
            val version = try {
                nativeGetVersion()
            } catch (e: Throwable) {
                "Unknown"
            }
            Log.i(TAG, "libfreerdp-android.so loaded successfully. Core version: $version")
            NativeLoadStatus.LOADED to null
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "UnsatisfiedLinkError loading libfreerdp-android: ${e.message}", e)
            NativeLoadStatus.LINKAGE_ERROR to e.message
        } catch (e: Throwable) {
            Log.e(TAG, "Unexpected error loading libfreerdp-android: ${e.message}", e)
            NativeLoadStatus.LOAD_FAILED to e.message
        }
    }

    /**
     * Overrides the active native bridge. Used exclusively for automated unit and Robolectric tests.
     */
    fun setNativeBridgeForTesting(bridge: IRdpNativeBridge) {
        this.nativeBridge = bridge
    }

    /**
     * Resets native bridge to default based on load status.
     */
    fun resetNativeBridge() {
        this.nativeBridge = if (isNativeLoaded) RealRdpNativeBridge() else MockRdpNativeBridge()
    }

    // -------------------------------------------------------------------------
    // Session Listener Registration & Reverse Callbacks
    // -------------------------------------------------------------------------

    fun registerSessionListener(instance: Long, listener: RdpSessionListener) {
        sessionListeners[instance] = listener
    }

    fun unregisterSessionListener(instance: Long) {
        sessionListeners.remove(instance)
    }

    fun getSessionListener(instance: Long): RdpSessionListener? = sessionListeners[instance]

    // -------------------------------------------------------------------------
    // High-Level Facade API (Delegates to IRdpNativeBridge)
    // -------------------------------------------------------------------------

    fun newInstance(context: Context?): Long = nativeBridge.newInstance(context)

    fun freeInstance(instance: Long) {
        sessionListeners.remove(instance)
        nativeBridge.freeInstance(instance)
    }

    fun connect(instance: Long, params: RdpConnectionParameters? = null): Boolean =
        nativeBridge.connect(instance, params)

    /**
     * Convenience connection overload matching PROJECT.md interface contract:
     * LibFreeRDP.connect(params: RdpConnectionParameters): Long
     * Creates a new native instance and connects with given parameters.
     * Returns the instance ID handle, or 0L if creation/connection fails.
     */
    fun connect(params: RdpConnectionParameters): Long {
        val instance = newInstance(null)
        if (instance != 0L) {
            val success = connect(instance, params)
            if (!success) {
                freeInstance(instance)
                return 0L
            }
        }
        return instance
    }

    fun disconnect(instance: Long): Boolean = nativeBridge.disconnect(instance)

    fun updateGraphics(instance: Long, bitmap: Bitmap, x: Int, y: Int, w: Int, h: Int): Boolean =
        nativeBridge.updateGraphics(instance, bitmap, x, y, w, h)

    fun sendCursorEvent(instance: Long, x: Int, y: Int, flags: Int): Boolean =
        nativeBridge.sendCursorEvent(instance, x, y, flags)

    fun sendKeyEvent(instance: Long, scancode: Int, extended: Boolean, down: Boolean): Boolean =
        nativeBridge.sendKeyEvent(instance, scancode, extended, down)

    fun sendKeyEvent(instance: Long, scancode: Int, down: Boolean): Boolean =
        nativeBridge.sendKeyEvent(instance, scancode, false, down)

    /**
     * Convenience key event overload matching PROJECT.md interface contract:
     * LibFreeRDP.sendKeyEvent(instance: Long, scancode: Int, flags: Int)
     * Bit 8 (0x0100): KBD_FLAGS_EXTENDED
     * Bit 15 (0x8000): KBD_FLAGS_RELEASE (down = false when set)
     */
    fun sendKeyEvent(instance: Long, scancode: Int, flags: Int): Boolean {
        val extended = (flags and 0x0100) != 0
        val down = (flags and 0x8000) == 0
        return sendKeyEvent(instance, scancode, extended, down)
    }

    fun sendUnicodeKeyEvent(instance: Long, codePoint: Int): Boolean =
        nativeBridge.sendUnicodeKeyEvent(instance, codePoint)

    fun getVersion(): String = nativeBridge.getVersion()

    fun getLastError(instance: Long): String? = nativeBridge.getLastError(instance)

    // -------------------------------------------------------------------------
    // Direct Native JNI Declarations (Implemented in android_freerdp.c)
    // -------------------------------------------------------------------------

    @JvmStatic external fun nativeNewInstance(context: Context?): Long
    @JvmStatic external fun nativeFreeInstance(instance: Long)
    @JvmStatic external fun nativeConnect(instance: Long, params: RdpConnectionParameters?): Boolean
    @JvmStatic external fun nativeConnectSimple(instance: Long): Boolean
    @JvmStatic external fun nativeDisconnect(instance: Long): Boolean
    @JvmStatic external fun nativeUpdateGraphics(instance: Long, bitmap: Bitmap, x: Int, y: Int, w: Int, h: Int): Boolean
    @JvmStatic external fun nativeSendCursorEvent(instance: Long, x: Int, y: Int, flags: Int): Boolean
    @JvmStatic external fun nativeSendKeyEvent(instance: Long, scancode: Int, extended: Boolean, down: Boolean): Boolean
    @JvmStatic external fun nativeSendKeyEventWithFlags(instance: Long, scancode: Int, flags: Int): Boolean
    @JvmStatic external fun nativeSendUnicodeKeyEvent(instance: Long, codePoint: Int): Boolean
    @JvmStatic external fun nativeGetVersion(): String

    // -------------------------------------------------------------------------
    // Reverse JNI Callbacks (Invoked from C via JNIEnv CallStaticVoidMethod)
    // -------------------------------------------------------------------------

    @JvmStatic
    fun onConnectionSuccess(instance: Long) {
        Log.i(TAG, "Native callback: onConnectionSuccess (instance: $instance)")
        sessionListeners[instance]?.onConnectionSuccess(instance)
    }

    @JvmStatic
    fun onConnectionFailure(instance: Long, errorCode: Int, message: String) {
        Log.e(TAG, "Native callback: onConnectionFailure (instance: $instance, err: $errorCode, msg: $message)")
        sessionListeners[instance]?.onConnectionFailure(instance, errorCode, message)
    }

    @JvmStatic
    fun onDisconnected(instance: Long) {
        Log.i(TAG, "Native callback: onDisconnected (instance: $instance)")
        sessionListeners[instance]?.onDisconnected(instance)
    }

    @JvmStatic
    fun onGraphicsUpdate(instance: Long, x: Int, y: Int, width: Int, height: Int) {
        sessionListeners[instance]?.onGraphicsUpdate(instance, x, y, width, height)
    }

    @JvmStatic
    fun onAuthenticate(instance: Long): Boolean {
        Log.i(TAG, "Native callback: onAuthenticate (instance: $instance)")
        return sessionListeners[instance]?.onAuthenticate(instance) ?: false
    }

    @JvmStatic
    fun onCursorMoved(instance: Long, x: Int, y: Int) {
        sessionListeners[instance]?.onCursorMoved(instance, x, y)
    }
}
