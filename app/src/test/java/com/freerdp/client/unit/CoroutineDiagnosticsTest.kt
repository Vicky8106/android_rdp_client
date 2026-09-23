package com.freerdp.client.unit

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Environment probes documenting how coroutine work is pumped in this project under
 * kotlinx-coroutines-test 1.9.0. Verified against the library source
 * (TestCoroutineScheduler):
 *
 *  - `advanceUntilIdle()` = `advanceUntilIdleOr { events.none(TestDispatchEvent<*>::isForeground) }`
 *    — it stops as soon as ONLY background-tagged events remain. Everything launched in
 *    `TestScope.backgroundScope` carries the `BackgroundWork` marker, so backgroundScope
 *    work is NOT executed by a plain `advanceUntilIdle()`;
 *  - `runCurrent()` / `advanceTimeBy(...)` have no such filter and DO drain background
 *    work (that is what [pumpAll] builds on);
 *  - foreground work (children of the test body) runs on `advanceUntilIdle()` as usual.
 *
 * Earlier versions of this file asserted that `advanceUntilIdle()` alone runs
 * backgroundScope launches — that expectation modelled the pre-1.9 documentation rather
 * than the shipped behaviour, which is why those probes failed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CoroutineDiagnosticsTest {

    @Test
    fun backgroundScopeLaunchIsSkippedByAdvanceUntilIdleThenRunsOnPumpAll() = runTest {
        var ran = false
        backgroundScope.launch { ran = true }

        advanceUntilIdle()
        assertFalse(
            "1.9.0 contract: advanceUntilIdle() stops at background-only queues",
            ran
        )

        pumpAll()
        assertTrue("pumpAll() must execute the backgroundScope launch", ran)
    }

    @Test
    fun runBlockingSeedDoesNotPoisonLaterPumps() = runTest {
        runBlocking {
            withContext(Dispatchers.Unconfined) { /* simulate repository seeding */ }
        }
        var ran = false
        backgroundScope.launch { ran = true }
        pumpAll()
        assertTrue("launch after runBlocking must run", ran)
    }

    @Test
    fun stateFlowCollectorSeesUpstreamChanges() = runTest {
        val flow = MutableStateFlow(0)
        var last = -1
        backgroundScope.launch { flow.collect { last = it } }
        pumpAll()
        flow.value = 7
        pumpAll()
        assertTrue("collector should observe 7, saw $last", last == 7)
    }

    @Test
    fun nestedLaunchChainRuns() = runTest {
        var stage = 0
        backgroundScope.launch {
            stage = 1
            launch { stage = 2 }
        }
        pumpAll()
        assertTrue("nested launch should reach stage 2, was $stage", stage == 2)
    }

    @Test
    fun plainTestScopeReceiverLaunchRuns() = runTest {
        var ran = false
        launch { ran = true } // 'this' TestScope instead of backgroundScope
        advanceUntilIdle()
        assertTrue("TestScope (foreground) launch must run on advanceUntilIdle", ran)
    }
}
