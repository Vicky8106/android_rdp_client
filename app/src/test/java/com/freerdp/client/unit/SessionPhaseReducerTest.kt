package com.freerdp.client.unit

import com.freerdp.client.session.SessionErrorCodes
import com.freerdp.client.session.SessionEvent
import com.freerdp.client.session.SessionPhase
import com.freerdp.client.session.friendlyHintFor
import com.freerdp.client.session.reduce
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exhaustive coverage of the pure session lifecycle reducer — every legal transition
 * and the illegal-event guards.
 */
class SessionPhaseReducerTest {

    @Test
    fun idlePlusConnectRequestedMovesToConnecting() {
        val next = SessionPhase.Idle.reduce(SessionEvent.ConnectRequested("host1"))
        assertEquals(SessionPhase.Connecting("host1"), next)
    }

    @Test
    fun connectingPlusSuccessBecomesConnectedAndKeepsDemoFlag() {
        val connecting = SessionPhase.Idle.reduce(SessionEvent.ConnectRequested("host1"))
        val connected = connecting.reduce(SessionEvent.ConnectionSuccess(demo = true))
        assertEquals(SessionPhase.Connected("host1", demo = true), connected)
    }

    @Test
    fun successIsIgnoredWhenNotConnecting() {
        // A stray success callback must not corrupt a terminal state.
        val failed = SessionPhase.Failed(1001, "boom")
        assertEquals(failed, failed.reduce(SessionEvent.ConnectionSuccess()))
        assertEquals(SessionPhase.Idle, SessionPhase.Idle.reduce(SessionEvent.ConnectionSuccess()))
    }

    @Test
    fun failureCarriesCodeMessageAndFriendlyHint() {
        val connecting = SessionPhase.Connecting("host1")
        val failed = connecting.reduce(SessionEvent.ConnectionFailed(1001, "native missing"))
            as SessionPhase.Failed
        assertEquals(1001, failed.code)
        assertEquals("native missing", failed.message)
        assertNotNull("1001 must map to actionable product copy", failed.friendlyHint)
        assertTrue(failed.friendlyHint!!.contains("Demo"))
    }

    @Test
    fun blankFailureMessageFallsBackToGenericText() {
        val failed = SessionPhase.Connecting("h")
            .reduce(SessionEvent.ConnectionFailed(999, "")) as SessionPhase.Failed
        assertEquals("Connection failed", failed.message)
        assertNull("unknown codes have no specialized hint", failed.friendlyHint)
    }

    @Test
    fun failureWhileIdleIsIgnored() {
        // e.g. an engine failure arriving after the user already exited.
        assertEquals(SessionPhase.Idle, SessionPhase.Idle.reduce(SessionEvent.ConnectionFailed(500, "late")))
    }

    @Test
    fun retryFromFailedGoesBackToConnecting() {
        val failed = SessionPhase.Failed(500, "boom")
        val retrying = failed.reduce(SessionEvent.ConnectRequested("host1"))
        assertEquals(SessionPhase.Connecting("host1"), retrying)
    }

    @Test
    fun anyActivePhaseExitsToIdle() {
        assertEquals(SessionPhase.Idle, SessionPhase.Connecting("h").reduce(SessionEvent.UserExit))
        assertEquals(
            SessionPhase.Idle,
            SessionPhase.Connected("h", false).reduce(SessionEvent.UserExit)
        )
        assertEquals(SessionPhase.Idle, SessionPhase.Failed(1, "x").reduce(SessionEvent.UserExit))
        assertEquals(SessionPhase.Idle, SessionPhase.Idle.reduce(SessionEvent.UserExit))
    }

    @Test
    fun profileMissingFlowEndsWithActionableHint() {
        // SessionViewModel requests connect with a placeholder host, then fails 404.
        val state = SessionPhase.Idle
            .reduce(SessionEvent.ConnectRequested("profile"))
            .reduce(SessionEvent.ConnectionFailed(SessionErrorCodes.PROFILE_MISSING, "Connection profile not found"))
        assertTrue(state is SessionPhase.Failed)
        assertEquals(SessionErrorCodes.PROFILE_MISSING, (state as SessionPhase.Failed).code)
        assertNotNull(state.friendlyHint)
    }

    @Test
    fun knownErrorCodesMapToProductCopy() {
        assertNotNull(friendlyHintFor(SessionErrorCodes.NATIVE_UNAVAILABLE))
        assertNotNull(friendlyHintFor(SessionErrorCodes.PASSWORD_REQUIRED))
        assertNotNull(friendlyHintFor(SessionErrorCodes.REMOTE_CLOSED))
        assertNotNull(friendlyHintFor(SessionErrorCodes.CERT_REJECTED))
        assertNull(friendlyHintFor(4242))
    }
}
