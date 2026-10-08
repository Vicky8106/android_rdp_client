package com.rdp.client.freerdp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicReference

/**
 * State machine enum representing the lifecycle states of an RDP connection session.
 */
enum class RdpSessionState {
    /** Initial idle state before connection, or clean post-disconnect terminal state */
    DISCONNECTED,

    /** Protocol handshake in progress (X.224, MCS, CredSSP/TLS, licensing, capabilities) */
    CONNECTING,

    /** Active session established; desktop framebuffer rasterized and receiving input PDUs */
    CONNECTED,

    /** Transient network disruption detected; auto-reconnect backoff in progress */
    RECONNECTING,

    /** Disconnect command issued; draining queues, releasing keys, tearing down context */
    DISCONNECTING,

    /** Terminal failure state encountered (network timeout, auth error, protocol abort) */
    ERROR;

    /** Returns true if this state is a terminal/quiescent state */
    val isTerminal: Boolean
        get() = this == DISCONNECTED || this == ERROR

    /** Returns true if active remote desktop interaction is possible */
    val isConnected: Boolean
        get() = this == CONNECTED

    /** Returns true if the session is currently engaging network or native resources */
    val isActive: Boolean
        get() = this == CONNECTING || this == CONNECTED || this == RECONNECTING || this == DISCONNECTING

    /**
     * Checks if transition to target state is legally valid per the state machine contract.
     */
    fun canTransitionTo(target: RdpSessionState): Boolean {
        if (this == target) return true
        return when (this) {
            DISCONNECTED -> target == CONNECTING
            CONNECTING -> target == CONNECTED || target == DISCONNECTING || target == ERROR || target == DISCONNECTED
            CONNECTED -> target == RECONNECTING || target == DISCONNECTING || target == ERROR
            RECONNECTING -> target == CONNECTED || target == DISCONNECTING || target == ERROR || target == DISCONNECTED
            DISCONNECTING -> target == DISCONNECTED || target == ERROR
            ERROR -> target == DISCONNECTED || target == CONNECTING
        }
    }
}

/**
 * Thread-safe atomic state machine managing transitions and exposing a reactive StateFlow.
 */
class RdpSessionStateMachine(initialState: RdpSessionState = RdpSessionState.DISCONNECTED) {
    private val stateRef = AtomicReference(initialState)
    private val _stateFlow = MutableStateFlow(initialState)

    /** Reactive StateFlow stream observing state transitions */
    val stateFlow: StateFlow<RdpSessionState> = _stateFlow.asStateFlow()

    /** Current instantaneous session state */
    val currentState: RdpSessionState
        get() = stateRef.get()

    /**
     * Attempts to transition to target state.
     * @return Result.success with new state if transition was legal, or Result.failure if illegal.
     */
    fun transitionTo(target: RdpSessionState): Result<RdpSessionState> {
        while (true) {
            val current = stateRef.get()
            if (current == target) {
                return Result.success(target)
            }
            if (!current.canTransitionTo(target)) {
                return Result.failure(
                    IllegalStateException("Illegal state transition from $current to $target")
                )
            }
            if (stateRef.compareAndSet(current, target)) {
                _stateFlow.value = target
                return Result.success(target)
            }
        }
    }

    /**
     * Forces transition to target state regardless of validation matrix.
     * Used for emergency reset during unhandled exceptions or activity teardown.
     */
    fun forceState(target: RdpSessionState) {
        stateRef.set(target)
        _stateFlow.value = target
    }
}
