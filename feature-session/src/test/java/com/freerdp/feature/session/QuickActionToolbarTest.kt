package com.freerdp.feature.session

import com.freerdp.feature.session.toolbar.QuickActionToolbarFSM
import com.freerdp.feature.session.toolbar.ToolbarAction
import com.freerdp.feature.session.toolbar.ToolbarState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QuickActionToolbarTest {

    @Test
    fun testInitialStateIsCollapsed() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val fsm = QuickActionToolbarFSM(
            inactivityTimeoutMs = 4000L,
            coroutineScope = this,
            dispatcher = testDispatcher
        )

        assertEquals(ToolbarState.COLLAPSED, fsm.state.value)
        assertTrue(fsm.isCollapsed)
        assertFalse(fsm.isExpanded)
    }

    @Test
    fun testExpandAndCollapseTransitions() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val fsm = QuickActionToolbarFSM(
            inactivityTimeoutMs = 4000L,
            coroutineScope = this,
            dispatcher = testDispatcher
        )

        fsm.expand()
        assertEquals(ToolbarState.EXPANDED, fsm.state.value)
        assertTrue(fsm.isExpanded)
        assertFalse(fsm.isCollapsed)

        fsm.collapse()
        assertEquals(ToolbarState.COLLAPSED, fsm.state.value)
        assertTrue(fsm.isCollapsed)
    }

    @Test
    fun testToggleFlipsState() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val fsm = QuickActionToolbarFSM(
            inactivityTimeoutMs = 4000L,
            coroutineScope = this,
            dispatcher = testDispatcher
        )

        // Toggle from COLLAPSED -> EXPANDED
        fsm.toggle()
        assertEquals(ToolbarState.EXPANDED, fsm.state.value)

        // Toggle from EXPANDED -> COLLAPSED
        fsm.toggle()
        assertEquals(ToolbarState.COLLAPSED, fsm.state.value)
    }

    @Test
    fun testAutoCollapseAfterFourSeconds() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val fsm = QuickActionToolbarFSM(
            inactivityTimeoutMs = 4000L,
            coroutineScope = this,
            dispatcher = testDispatcher
        )

        fsm.expand()
        assertEquals(ToolbarState.EXPANDED, fsm.state.value)

        // Advance 3900ms - should still be expanded
        advanceTimeBy(3900L)
        runCurrent()
        assertEquals(ToolbarState.EXPANDED, fsm.state.value)

        // Advance remaining 100ms (total 4000ms) - should auto-collapse
        advanceTimeBy(100L)
        runCurrent()
        assertEquals(ToolbarState.COLLAPSED, fsm.state.value)
    }

    @Test
    fun testTouchResetsAutoCollapseTimer() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val fsm = QuickActionToolbarFSM(
            inactivityTimeoutMs = 4000L,
            coroutineScope = this,
            dispatcher = testDispatcher
        )

        fsm.expand()
        assertEquals(ToolbarState.EXPANDED, fsm.state.value)

        // Advance 3000ms
        advanceTimeBy(3000L)
        runCurrent()
        assertEquals(ToolbarState.EXPANDED, fsm.state.value)

        // User touch resets countdown
        fsm.onTouch()

        // Advance 2500ms (5500ms since expand, but only 2500ms since touch)
        advanceTimeBy(2500L)
        runCurrent()
        assertEquals(ToolbarState.EXPANDED, fsm.state.value)

        // Advance remaining 1500ms (total 4000ms since touch)
        advanceTimeBy(1500L)
        runCurrent()
        assertEquals(ToolbarState.COLLAPSED, fsm.state.value)
    }

    @Test
    fun testTriggerActionDispatchesEventAndResetsTimer() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val receivedActions = mutableListOf<ToolbarAction>()

        val fsm = QuickActionToolbarFSM(
            inactivityTimeoutMs = 4000L,
            coroutineScope = this,
            dispatcher = testDispatcher,
            onActionCallback = { receivedActions.add(it) }
        )

        fsm.expand()
        fsm.triggerAction(ToolbarAction.TOGGLE_KEYBOARD)

        assertEquals(1, receivedActions.size)
        assertEquals(ToolbarAction.TOGGLE_KEYBOARD, receivedActions[0])

        fsm.triggerAction(ToolbarAction.SWITCH_RESOLUTION)
        fsm.triggerAction(ToolbarAction.TOGGLE_MOUSE_OVERLAY)
        fsm.triggerAction(ToolbarAction.TOGGLE_TELEMETRY_HUD)
        fsm.triggerAction(ToolbarAction.TOGGLE_MODIFIER_BAR)
        fsm.triggerAction(ToolbarAction.DISCONNECT)

        assertEquals(6, receivedActions.size)
        assertEquals(ToolbarAction.DISCONNECT, receivedActions.last())
    }

    // ---------------------------------------------------------------------
    // Boundary & corner cases (exact 4s expiry, timer extension, pin/unpin,
    // session loss, stale timer races, flow-based action dispatch)
    // ---------------------------------------------------------------------

    @Test
    fun testAutoCollapseFiresExactlyAtFourSecondsNotBefore() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val fsm = QuickActionToolbarFSM(
            inactivityTimeoutMs = 4000L,
            coroutineScope = this,
            dispatcher = testDispatcher
        )

        fsm.expand()

        advanceTimeBy(3999L)
        runCurrent()
        assertEquals(
            "Must stay expanded 1ms before the deadline",
            ToolbarState.EXPANDED,
            fsm.state.value
        )

        advanceTimeBy(1L)
        runCurrent()
        assertEquals(
            "Must collapse exactly at the 4000ms deadline",
            ToolbarState.COLLAPSED,
            fsm.state.value
        )
    }

    @Test
    fun testTouchJustBeforeExpiryExtendsDeadlineWithoutDroppingEarly() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val fsm = QuickActionToolbarFSM(
            inactivityTimeoutMs = 4000L,
            coroutineScope = this,
            dispatcher = testDispatcher
        )

        fsm.expand() // original deadline: t = 4000

        advanceTimeBy(3990L)
        runCurrent()
        fsm.onTouch() // new deadline: t = 3990 + 4000 = 7990

        advanceTimeBy(110L) // t = 4100, past the original 4000ms deadline
        runCurrent()
        assertEquals(
            "Original timer must be superseded by the touch",
            ToolbarState.EXPANDED,
            fsm.state.value
        )

        advanceTimeBy(3890L) // t = 7990, exactly 4000ms after the touch
        runCurrent()
        assertEquals(
            "Must collapse exactly 4000ms after the touch",
            ToolbarState.COLLAPSED,
            fsm.state.value
        )
    }

    @Test
    fun testPinnedToolbarNeverAutoCollapses() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val fsm = QuickActionToolbarFSM(
            inactivityTimeoutMs = 4000L,
            coroutineScope = this,
            dispatcher = testDispatcher
        )

        fsm.expand()
        fsm.pin()
        assertTrue("pin() must mark the toolbar pinned", fsm.isPinned)
        assertEquals(ToolbarState.EXPANDED, fsm.state.value)

        advanceTimeBy(30_000L)
        runCurrent()
        assertEquals(
            "Pinned toolbar must survive far beyond the 4s window",
            ToolbarState.EXPANDED,
            fsm.state.value
        )
        assertTrue(fsm.isPinned)
    }

    @Test
    fun testUnpinRearmsAutoCollapseTimer() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val fsm = QuickActionToolbarFSM(
            inactivityTimeoutMs = 4000L,
            coroutineScope = this,
            dispatcher = testDispatcher
        )

        fsm.expand()
        fsm.pin()
        advanceTimeBy(10_000L)
        runCurrent()
        assertEquals(ToolbarState.EXPANDED, fsm.state.value)

        fsm.unpin() // timer re-armed from t = 10000
        assertFalse("unpin() must clear the pin", fsm.isPinned)

        advanceTimeBy(3999L)
        runCurrent()
        assertEquals(
            "Must stay expanded 1ms before the post-unpin deadline",
            ToolbarState.EXPANDED,
            fsm.state.value
        )

        advanceTimeBy(1L)
        runCurrent()
        assertEquals(
            "Must collapse exactly 4000ms after unpin",
            ToolbarState.COLLAPSED,
            fsm.state.value
        )
    }

    @Test
    fun testSessionLossCollapsesToolbarImmediatelyAndStaysDown() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val fsm = QuickActionToolbarFSM(
            inactivityTimeoutMs = 4000L,
            coroutineScope = this,
            dispatcher = testDispatcher
        )

        fsm.expand()
        fsm.onSessionLost()
        assertEquals(
            "Session loss must collapse the toolbar",
            ToolbarState.COLLAPSED,
            fsm.state.value
        )

        advanceTimeBy(10_000L)
        runCurrent()
        assertEquals(
            "No stale timer may resurrect the toolbar after session loss",
            ToolbarState.COLLAPSED,
            fsm.state.value
        )
    }

    @Test
    fun testSessionLossOverridesPinButKeepsPinPreference() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val fsm = QuickActionToolbarFSM(
            inactivityTimeoutMs = 4000L,
            coroutineScope = this,
            dispatcher = testDispatcher
        )

        fsm.expand()
        fsm.pin()
        fsm.onSessionLost()

        assertEquals(
            "Session loss must collapse even a pinned toolbar",
            ToolbarState.COLLAPSED,
            fsm.state.value
        )
        assertTrue("Pin preference survives for the next session", fsm.isPinned)
    }

    @Test
    fun testStaleTimerCannotCollapseReExpandedToolbar() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val fsm = QuickActionToolbarFSM(
            inactivityTimeoutMs = 4000L,
            coroutineScope = this,
            dispatcher = testDispatcher
        )

        fsm.expand() // timer #1, deadline t = 4000
        advanceTimeBy(1000L)
        runCurrent()

        fsm.collapse() // cancels timer #1
        advanceTimeBy(500L) // t = 1500
        runCurrent()

        fsm.expand() // timer #2, deadline t = 5500
        advanceTimeBy(2600L) // t = 4100, past timer #1's original deadline
        runCurrent()
        assertEquals(
            "Stale first timer must not collapse the re-expanded toolbar",
            ToolbarState.EXPANDED,
            fsm.state.value
        )

        advanceTimeBy(1400L) // t = 5500, exactly 4000ms after re-expand
        runCurrent()
        assertEquals(
            "Second timer must fire exactly 4000ms after re-expand",
            ToolbarState.COLLAPSED,
            fsm.state.value
        )
    }

    /**
     * Challenger_w2 survivor M4: the test above stages staleness via `collapse()`, which
     * CANCELS timer #1 — cancellation, not the generation guard, is what defeats it there.
     * This test forces the guard itself: the stale timer's `delay()` has COMPLETED (it is
     * past its only suspension point, so `cancel()` can no longer stop it) when a
     * collapse+re-expand decision bumps `timerGeneration` before the collapse decision
     * runs. Only `generation == timerGeneration` can now keep the toolbar open — remove
     * that check and the re-expanded toolbar collapses.
     */
    @Test
    fun testGenerationGuardBlocksTimerThatCompletedDelayBeforeReExpand() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        lateinit var fsm: QuickActionToolbarFSM
        var expiredTimers = 0
        fsm = QuickActionToolbarFSM(
            inactivityTimeoutMs = 4000L,
            coroutineScope = this,
            dispatcher = testDispatcher,
            onTimerExpired = {
                expiredTimers++
                if (expiredTimers == 1) {
                    // Timer #1 is past delay() but has NOT made its collapse decision yet:
                    // stage the racing user decision — collapse, then immediately re-expand.
                    fsm.collapse()
                    fsm.expand()
                }
            }
        )

        fsm.expand() // timer #1 armed (generation 1), deadline t = 4000
        advanceTimeBy(4000L) // virtual clock reaches the deadline — delay() completes
        runCurrent() // execute the resumed timer body: expiry hook, then the gen check

        assertEquals("the stale timer must have reached its expiry hook exactly once", 1, expiredTimers)
        assertEquals(
            "A stale armed timer that already completed delay() must NOT collapse the " +
                "re-expanded toolbar — this is the generation guard, not job cancellation",
            ToolbarState.EXPANDED,
            fsm.state.value
        )

        advanceTimeBy(4000L) // the timer armed by the re-expand: deadline t = 8000
        runCurrent()
        assertEquals(
            "The fresh timer must still collapse the toolbar 4000ms after re-expand",
            ToolbarState.COLLAPSED,
            fsm.state.value
        )
    }

    @Test
    fun testActionEventsDeliveredToActiveFlowCollector() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val received = mutableListOf<ToolbarAction>()
        var callbackSeen: ToolbarAction? = null
        val fsm = QuickActionToolbarFSM(
            inactivityTimeoutMs = 4000L,
            coroutineScope = this,
            dispatcher = testDispatcher,
            onActionCallback = { callbackSeen = it }
        )

        val collector = launch { fsm.actionEvents.collect { received.add(it) } }
        runCurrent() // subscription must be active before emitting

        fsm.expand()
        fsm.triggerAction(ToolbarAction.TOGGLE_KEYBOARD)
        fsm.triggerAction(ToolbarAction.DISCONNECT)
        runCurrent()

        assertEquals(
            listOf(ToolbarAction.TOGGLE_KEYBOARD, ToolbarAction.DISCONNECT),
            received
        )
        assertEquals("Callback must observe the last action", ToolbarAction.DISCONNECT, callbackSeen)

        collector.cancel()
        runCurrent()
    }
}
