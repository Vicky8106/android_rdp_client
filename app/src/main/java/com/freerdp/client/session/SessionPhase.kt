package com.freerdp.client.session

/**
 * Pure state machine for the lifecycle of one RDP session screen.
 *
 * Keeping it as an isolated reducer (state + event -> state) makes every transition
 * unit-testable without Android, engines or coroutines.
 */
sealed interface SessionPhase {

    /** Nothing started yet, or the session was torn down and the user is back at the list. */
    data object Idle : SessionPhase

    /** A connection attempt is in flight (includes waiting for credentials/certificate). */
    data class Connecting(val host: String) : SessionPhase

    /** The remote desktop is streaming. */
    data class Connected(val host: String, val demo: Boolean) : SessionPhase

    /** The attempt or the session failed; [friendlyHint] is an actionable, human explanation. */
    data class Failed(
        val code: Int,
        val message: String,
        val friendlyHint: String? = null
    ) : SessionPhase
}

/** Inputs to the [SessionPhase.reduce] reducer. */
sealed interface SessionEvent {
    data class ConnectRequested(val host: String) : SessionEvent
    data class ConnectionSuccess(val demo: Boolean = false) : SessionEvent
    data class ConnectionFailed(val code: Int, val message: String) : SessionEvent
    data object UserExit : SessionEvent
}

/** Error codes surfaced by the engine that deserve specialized product copy. */
object SessionErrorCodes {
    const val NATIVE_UNAVAILABLE = 1001
    const val NATIVE_ALLOC_FAILED = 1002
    const val NATIVE_PARSE_FAILED = 1003
    const val PASSWORD_REQUIRED = 401
    const val CERT_REJECTED = 403
    const val PROFILE_MISSING = 404
    const val REMOTE_CLOSED = 502
    const val SESSION_DROPPED = 503
}

fun friendlyHintFor(code: Int): String? = when (code) {
    SessionErrorCodes.NATIVE_UNAVAILABLE ->
        "The native FreeRDP libraries are not packaged in this build. " +
            "Enable the Demo engine in Settings to explore the app, or install a build that includes them."
    SessionErrorCodes.NATIVE_ALLOC_FAILED, SessionErrorCodes.NATIVE_PARSE_FAILED ->
        "The RDP engine could not be initialized. Retry, and check the host and port settings."
    SessionErrorCodes.PASSWORD_REQUIRED ->
        "A password is required for this profile. Tap Retry to enter it."
    SessionErrorCodes.CERT_REJECTED ->
        "The server certificate was rejected. Reconnect to review the fingerprint again."
    SessionErrorCodes.PROFILE_MISSING ->
        "This profile no longer exists. It may have been deleted from another screen."
    SessionErrorCodes.REMOTE_CLOSED ->
        "The remote host closed the connection. The server may be restarting — tap Retry."
    SessionErrorCodes.SESSION_DROPPED ->
        "The connection dropped. Auto-reconnect will retry, or tap Retry once the network recovers."
    else -> null
}

/**
 * Single, authoritative transition function for [SessionPhase].
 * Illegal/unknown events leave the state unchanged so a racing callback can never
 * corrupt an already-terminal state.
 */
fun SessionPhase.reduce(event: SessionEvent): SessionPhase = when (event) {
    is SessionEvent.ConnectRequested -> SessionPhase.Connecting(event.host)

    is SessionEvent.ConnectionSuccess -> when (this) {
        is SessionPhase.Connecting -> SessionPhase.Connected(host, event.demo)
        else -> this
    }

    is SessionEvent.ConnectionFailed -> when (this) {
        is SessionPhase.Idle -> this // failure with nothing running stays idle
        else -> SessionPhase.Failed(
            code = event.code,
            message = event.message.ifBlank { "Connection failed" },
            friendlyHint = friendlyHintFor(event.code)
        )
    }

    SessionEvent.UserExit -> when (this) {
        is SessionPhase.Idle -> this
        else -> SessionPhase.Idle
    }
}
