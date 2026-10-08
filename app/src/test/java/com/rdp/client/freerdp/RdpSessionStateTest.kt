package com.rdp.client.freerdp

import org.junit.Assert.*
import org.junit.Test

class RdpSessionStateTest {

    @Test
    fun testStateProperties() {
        // Terminal states
        assertTrue(RdpSessionState.DISCONNECTED.isTerminal)
        assertTrue(RdpSessionState.ERROR.isTerminal)
        assertFalse(RdpSessionState.CONNECTING.isTerminal)
        assertFalse(RdpSessionState.CONNECTED.isTerminal)
        assertFalse(RdpSessionState.RECONNECTING.isTerminal)
        assertFalse(RdpSessionState.DISCONNECTING.isTerminal)

        // Connected
        assertTrue(RdpSessionState.CONNECTED.isConnected)
        assertFalse(RdpSessionState.CONNECTING.isConnected)
        assertFalse(RdpSessionState.DISCONNECTED.isConnected)

        // Active
        assertTrue(RdpSessionState.CONNECTING.isActive)
        assertTrue(RdpSessionState.CONNECTED.isActive)
        assertTrue(RdpSessionState.RECONNECTING.isActive)
        assertTrue(RdpSessionState.DISCONNECTING.isActive)
        assertFalse(RdpSessionState.DISCONNECTED.isActive)
        assertFalse(RdpSessionState.ERROR.isActive)
    }

    @Test
    fun testAllowedStateTransitions() {
        // DISCONNECTED can only transition to CONNECTING
        assertTrue(RdpSessionState.DISCONNECTED.canTransitionTo(RdpSessionState.CONNECTING))
        assertFalse(RdpSessionState.DISCONNECTED.canTransitionTo(RdpSessionState.CONNECTED))
        assertFalse(RdpSessionState.DISCONNECTED.canTransitionTo(RdpSessionState.ERROR))

        // CONNECTING can transition to CONNECTED, DISCONNECTING, ERROR, DISCONNECTED
        assertTrue(RdpSessionState.CONNECTING.canTransitionTo(RdpSessionState.CONNECTED))
        assertTrue(RdpSessionState.CONNECTING.canTransitionTo(RdpSessionState.ERROR))
        assertTrue(RdpSessionState.CONNECTING.canTransitionTo(RdpSessionState.DISCONNECTING))
        assertTrue(RdpSessionState.CONNECTING.canTransitionTo(RdpSessionState.DISCONNECTED))
        assertFalse(RdpSessionState.CONNECTING.canTransitionTo(RdpSessionState.RECONNECTING))

        // CONNECTED can transition to RECONNECTING, DISCONNECTING, ERROR
        assertTrue(RdpSessionState.CONNECTED.canTransitionTo(RdpSessionState.RECONNECTING))
        assertTrue(RdpSessionState.CONNECTED.canTransitionTo(RdpSessionState.DISCONNECTING))
        assertTrue(RdpSessionState.CONNECTED.canTransitionTo(RdpSessionState.ERROR))
        assertFalse(RdpSessionState.CONNECTED.canTransitionTo(RdpSessionState.CONNECTING))
        assertFalse(RdpSessionState.CONNECTED.canTransitionTo(RdpSessionState.DISCONNECTED))

        // DISCONNECTING can transition to DISCONNECTED, ERROR
        assertTrue(RdpSessionState.DISCONNECTING.canTransitionTo(RdpSessionState.DISCONNECTED))
        assertTrue(RdpSessionState.DISCONNECTING.canTransitionTo(RdpSessionState.ERROR))
        assertFalse(RdpSessionState.DISCONNECTING.canTransitionTo(RdpSessionState.CONNECTED))

        // ERROR can transition to DISCONNECTED or retry to CONNECTING
        assertTrue(RdpSessionState.ERROR.canTransitionTo(RdpSessionState.DISCONNECTED))
        assertTrue(RdpSessionState.ERROR.canTransitionTo(RdpSessionState.CONNECTING))
        assertFalse(RdpSessionState.ERROR.canTransitionTo(RdpSessionState.CONNECTED))
    }

    @Test
    fun testRdpSessionStateMachineTransitions() {
        val sm = RdpSessionStateMachine(RdpSessionState.DISCONNECTED)
        assertEquals(RdpSessionState.DISCONNECTED, sm.currentState)
        assertEquals(RdpSessionState.DISCONNECTED, sm.stateFlow.value)

        // Valid transition: DISCONNECTED -> CONNECTING
        val res1 = sm.transitionTo(RdpSessionState.CONNECTING)
        assertTrue(res1.isSuccess)
        assertEquals(RdpSessionState.CONNECTING, sm.currentState)
        assertEquals(RdpSessionState.CONNECTING, sm.stateFlow.value)

        // Valid transition: CONNECTING -> CONNECTED
        val res2 = sm.transitionTo(RdpSessionState.CONNECTED)
        assertTrue(res2.isSuccess)
        assertEquals(RdpSessionState.CONNECTED, sm.currentState)

        // Invalid transition: CONNECTED -> CONNECTING (must throw/fail)
        val resInvalid = sm.transitionTo(RdpSessionState.CONNECTING)
        assertFalse(resInvalid.isSuccess)
        assertTrue(resInvalid.exceptionOrNull() is IllegalStateException)
        assertEquals(RdpSessionState.CONNECTED, sm.currentState) // State remains unchanged

        // Self-transition is a successful no-op
        val resSelf = sm.transitionTo(RdpSessionState.CONNECTED)
        assertTrue(resSelf.isSuccess)
        assertEquals(RdpSessionState.CONNECTED, sm.currentState)

        // Force state bypasses validation
        sm.forceState(RdpSessionState.ERROR)
        assertEquals(RdpSessionState.ERROR, sm.currentState)
        assertEquals(RdpSessionState.ERROR, sm.stateFlow.value)
    }
}
