package com.freerdp.feature.telemetry.reconnect

import kotlinx.coroutines.flow.StateFlow

/**
 * Reconnection lifecycle state machine states.
 */
sealed interface ReconnectState {
    /** Inactive, no active connection or reconnection in progress. */
    data object Idle : ReconnectState

    /** Paused waiting for device network connectivity to return. */
    data object WaitingForNetwork : ReconnectState

    /** Backing off or actively attempting reconnection. */
    data class Reconnecting(val attempt: Int, val nextDelayMs: Long) : ReconnectState

    /** Active session established and connected. */
    data object Connected : ReconnectState

    /** Reconnect suspended due to user pause (app backgrounded) or network pause. */
    data class Suspended(val userPaused: Boolean) : ReconnectState

    /** Reconnection failed permanently (e.g. max retries exhausted). */
    data class Failed(val exhausted: Boolean, val reason: String = "") : ReconnectState

    val isIdle: Boolean get() = this is Idle
    val isConnected: Boolean get() = this is Connected
    val isReconnecting: Boolean get() = this is Reconnecting
    val isWaitingForNetwork: Boolean get() = this is WaitingForNetwork
    val isSuspended: Boolean get() = this is Suspended
    val isFailed: Boolean get() = this is Failed
}

/**
 * Auto-Reconnect State Machine Contract (from PROJECT.md).
 */
interface AutoReconnectManager {
    val reconnectState: StateFlow<ReconnectState>

    fun onNetworkLost()
    fun onNetworkAvailable()
    fun onSessionDropped(reason: String)
    fun onUserPause()
    fun onUserResume()
    fun cancelReconnect()
}
