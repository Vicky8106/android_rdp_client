package com.freerdp.core.engine

sealed interface RdpConnectionState {
    data object Disconnected : RdpConnectionState
    data object Connecting : RdpConnectionState
    data object Connected : RdpConnectionState
    data class Reconnecting(val attempt: Int, val maxAttempts: Int = 5) : RdpConnectionState

    /**
     * Typed failure state.
     *
     * @param errorCode machine-readable failure code (1001 = native library not loaded,
     *   1002 = native instance allocation failed, 1003 = argument parsing failed,
     *   1004 = native connection failed, 1005 = connection timed out; engine-specific
     *   codes may be added by other engines such as MockRdpEngine).
     * @param message human-readable description, including the native load error when
     *   [nativeLibraryAvailable] is false.
     * @param nativeLibraryAvailable false when the failure was caused by the FreeRDP
     *   native libraries being absent/unloadable on this platform (surfaces
     *   [LibFreeRDP.isNativeLoaded] semantics through the state itself).
     * @param loadError stringified [LibFreeRDP.getLoadError] for the native-load failure,
     *   null for all other failure kinds.
     */
    data class Failed(
        val errorCode: Int,
        val message: String,
        val nativeLibraryAvailable: Boolean = true,
        val loadError: String? = null
    ) : RdpConnectionState

    val isConnected: Boolean get() = this is Connected
    val isConnecting: Boolean get() = this is Connecting
    val isDisconnected: Boolean get() = this is Disconnected
}
