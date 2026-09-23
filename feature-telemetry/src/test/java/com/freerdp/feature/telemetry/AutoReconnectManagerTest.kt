package com.freerdp.feature.telemetry

import com.freerdp.core.engine.MockRdpEngine
import com.freerdp.core.engine.RdpConnectionConfig
import com.freerdp.feature.telemetry.reconnect.AutoReconnectManagerImpl
import com.freerdp.feature.telemetry.reconnect.ReconnectState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.min

@OptIn(ExperimentalCoroutinesApi::class)
class AutoReconnectManagerTest {

    private lateinit var mockEngine: MockRdpEngine
    private lateinit var testConfig: RdpConnectionConfig

    @Before
    fun setUp() {
        mockEngine = MockRdpEngine()
        testConfig = RdpConnectionConfig(
            serverAddress = "192.168.1.100",
            port = 3389,
            username = "testuser",
            password = "testpassword"
        )
    }

    @Test
    fun testExponentialBackoffFormulaWithJitter() {
        // Test backoff boundaries across 10 attempts
        for (attempt in 0..10) {
            val maxCap = min(30000L, 1000L * (1L shl min(attempt, 30)))
            for (sample in 0..50) {
                val backoff = AutoReconnectManagerImpl.calculateBackoffWithJitter(attempt)
                assertTrue(
                    "Backoff $backoff must be >= 0 for attempt $attempt",
                    backoff >= 0L
                )
                assertTrue(
                    "Backoff $backoff must be <= $maxCap for attempt $attempt",
                    backoff <= maxCap
                )
            }
        }

        // Test with deterministic provider returning maximum
        val maxProvider: (Long, Long) -> Long = { _, max -> max }
        assertEquals(1000L, AutoReconnectManagerImpl.calculateBackoffWithJitter(0, randomProvider = maxProvider))
        assertEquals(2000L, AutoReconnectManagerImpl.calculateBackoffWithJitter(1, randomProvider = maxProvider))
        assertEquals(4000L, AutoReconnectManagerImpl.calculateBackoffWithJitter(2, randomProvider = maxProvider))
        assertEquals(8000L, AutoReconnectManagerImpl.calculateBackoffWithJitter(3, randomProvider = maxProvider))
        assertEquals(16000L, AutoReconnectManagerImpl.calculateBackoffWithJitter(4, randomProvider = maxProvider))
        assertEquals(30000L, AutoReconnectManagerImpl.calculateBackoffWithJitter(5, randomProvider = maxProvider))
        assertEquals(30000L, AutoReconnectManagerImpl.calculateBackoffWithJitter(6, randomProvider = maxProvider))
    }

    @Test
    fun testFiveStepTeardownExecutedInOrder() = runTest {
        // Behavior-based rewrite (reviewer_w2 MAJOR-1): the test no longer checks only
        // history STRINGS — it proves the step-2 abort action and the step-3 render-loop
        // drain action are each INVOKED EXACTLY ONCE and strictly interleaved with the
        // recorded step order. The 5-step ordering contract is kept intact.
        val trace = mutableListOf<String>()
        val stepsExecuted = mutableListOf<Int>()
        var abortInvocations = 0
        var renderLoopDrained = false
        val testDispatcher = StandardTestDispatcher(testScheduler)

        val manager = AutoReconnectManagerImpl(
            engine = mockEngine,
            configProvider = { testConfig },
            coroutineScope = this,
            ioDispatcher = testDispatcher,
            abortInFlightConnect = { abortInvocations++; trace += "abort" },
            renderLoopDrainAction = { renderLoopDrained = true; trace += "drain" },
            onTeardownStepListener = { step, _ -> stepsExecuted.add(step); trace += "step$step" }
        )

        manager.execute5StepTeardown(ReconnectState.Idle)
        advanceUntilIdle()

        assertEquals(
            "All 5 steps of leak-free teardown must execute in strict sequence",
            listOf(1, 2, 3, 4, 5),
            stepsExecuted
        )
        assertEquals(
            "Actions must run exactly once each and between their own step records: " +
                "step1 -> ABORT -> step2 -> DRAIN -> step3 -> step4 -> step5",
            listOf("step1", "abort", "step2", "drain", "step3", "step4", "step5"),
            trace
        )
        assertEquals("abort action must run exactly once", 1, abortInvocations)
        assertTrue("Render loop drain action must be invoked", renderLoopDrained)
        assertEquals(
            "History itself must carry steps 1-5 in order (prefixes; descriptions owned by impl)",
            listOf("Step 1", "Step 2", "Step 3", "Step 4", "Step 5"),
            manager.executedTeardownHistory.map { entry -> entry.takeWhile { c -> c != ':' } }
        )
        assertTrue("Reconnect state must transition to Idle", manager.reconnectState.value.isIdle)
    }

    @Test
    fun testDefaultAbortActionReallyDisconnectsEngineDuringStepTwo() = runTest {
        // reviewer_w2 MAJOR-1: step 2 must perform a REAL abort even when no custom
        // action is injected. The default abort drives engine.disconnect() — asserted
        // here by counting the engine's onDisconnected callback around the step records.
        val trace = mutableListOf<String>()
        var disconnects = 0
        mockEngine.setEventListener(object : com.freerdp.core.engine.RdpEventListener {
            override fun onConnectionSuccess() {}
            override fun onConnectionFailure(errorCode: Int, message: String) {}
            override fun onDisconnected() {
                disconnects++
                trace += "engineDisconnected"
            }
            override fun onGraphicsUpdate(bitmap: android.graphics.Bitmap, x: Int, y: Int, width: Int, height: Int) {}
            override fun onResolutionChanged(width: Int, height: Int) {}
            override fun onClipboardDataReceived(format: Int, data: ByteArray) {}
            override fun onCertificateVerification(fingerprint: String, host: String): Boolean = false
        })
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val manager = AutoReconnectManagerImpl(
            engine = mockEngine,
            configProvider = { testConfig },
            coroutineScope = this,
            ioDispatcher = testDispatcher,
            onTeardownStepListener = { step, _ -> trace += "step$step" }
        )

        manager.execute5StepTeardown(ReconnectState.Idle)
        advanceUntilIdle()

        assertEquals(
            "step-2 default abort (disconnect) + step-4 deallocate (disconnect) = 2 real " +
                "disconnects — a fake step 2 would produce only 1",
            2,
            disconnects
        )
        assertEquals(
            "The abort must happen DURING step 2 (before its record) and step 4's " +
                "deallocate disconnect during step 4; step 3 must not be claimed without a drain action",
            listOf("step1", "engineDisconnected", "step2", "engineDisconnected", "step4", "step5"),
            trace
        )
        assertTrue(
            "No render-loop action registered → no 'Step 3' claim may exist",
            manager.executedTeardownHistory.none { it.startsWith("Step 3") }
        )
        assertTrue("Teardown must still complete its state transition", manager.reconnectState.value.isIdle)
    }

    @Test
    fun testStepThreeNotRecordedWhenRenderLoopJoinNeverCompletes() = runTest {
        // reviewer_w2 MAJOR-1: a registered drain that does NOT complete (1.5s budget
        // elapses) must leave no "render loop joined" claim, while teardown continues.
        val stepsExecuted = mutableListOf<Int>()
        var drainStarted = false
        var teardownFinished = false
        val testDispatcher = StandardTestDispatcher(testScheduler)

        val manager = AutoReconnectManagerImpl(
            engine = mockEngine,
            configProvider = { testConfig },
            coroutineScope = this,
            ioDispatcher = testDispatcher,
            renderLoopDrainAction = {
                drainStarted = true
                kotlinx.coroutines.CompletableDeferred<Unit>().await() // never completes
            },
            onTeardownStepListener = { step, _ -> stepsExecuted.add(step) }
        )

        launch {
            manager.execute5StepTeardown(ReconnectState.Idle)
            teardownFinished = true
        }
        advanceUntilIdle() // drives the 1500ms join budget to its timeout

        assertTrue("the drain action must at least have started", drainStarted)
        assertTrue("teardown must finish despite the stalled drain", teardownFinished)
        assertEquals(
            "Step 3 must be omitted (drain did not complete); 1, 2, 4, 5 still run in order",
            listOf(1, 2, 4, 5),
            stepsExecuted
        )
        assertTrue(
            "No 'Step 3' claim after the timed-out join",
            manager.executedTeardownHistory.none { it.startsWith("Step 3") }
        )
        assertTrue(manager.reconnectState.value.isIdle)
    }

    @Test
    fun testNetworkLostTransitionsToWaitingForNetwork() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val manager = AutoReconnectManagerImpl(
            engine = mockEngine,
            configProvider = { testConfig },
            coroutineScope = this,
            ioDispatcher = testDispatcher
        )

        manager.onNetworkLost()
        advanceUntilIdle()

        assertTrue(
            "State must transition to WaitingForNetwork",
            manager.reconnectState.value is ReconnectState.WaitingForNetwork
        )
    }

    @Test
    fun testFastPathNetworkRecoveryBypassesDelay() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val manager = AutoReconnectManagerImpl(
            engine = mockEngine,
            configProvider = { testConfig },
            coroutineScope = this,
            ioDispatcher = testDispatcher,
            baseDelayMs = 10000L, // Large delay to ensure timer is active
            randomProvider = { _, max -> max }
        )

        // Drop network
        manager.onNetworkLost()
        advanceUntilIdle()
        assertTrue(manager.reconnectState.value is ReconnectState.WaitingForNetwork)

        // Network returns: fast-path override should immediately attempt reconnect
        manager.onNetworkAvailable()
        advanceUntilIdle()

        assertTrue(
            "Fast path must immediately transition to Connected after network returns",
            manager.reconnectState.value is ReconnectState.Connected
        )
    }

    @Test
    fun testSessionDroppedTriggersReconnectingState() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val manager = AutoReconnectManagerImpl(
            engine = mockEngine,
            configProvider = { testConfig },
            coroutineScope = this,
            ioDispatcher = testDispatcher,
            baseDelayMs = 500L,
            randomProvider = { _, max -> max }
        )

        manager.onSessionDropped("TCP RST received")
        advanceTimeBy(50L)

        val state = manager.reconnectState.value
        assertTrue("State must be Reconnecting, was $state", state is ReconnectState.Reconnecting)
        assertEquals(1, (state as ReconnectState.Reconnecting).attempt)
        assertEquals(500L, state.nextDelayMs)

        // Advance through backoff delay
        advanceTimeBy(500L)
        advanceUntilIdle()

        // Mock engine should now be connected
        assertTrue(
            "After backoff delay elapses, reconnect should establish Connected state",
            manager.reconnectState.value is ReconnectState.Connected
        )
    }

    @Test
    fun testExhaustedRetriesTransitionsToFailed() = runTest {
        mockEngine.shouldFailConnection = true
        mockEngine.failureErrorMessage = "Server unreachable"

        val testDispatcher = StandardTestDispatcher(testScheduler)
        val manager = AutoReconnectManagerImpl(
            engine = mockEngine,
            configProvider = { testConfig },
            coroutineScope = this,
            ioDispatcher = testDispatcher,
            maxAttempts = 3,
            baseDelayMs = 10L,
            randomProvider = { _, max -> max }
        )

        // Simulate 4 successive session drops exceeding maxAttempts
        manager.onSessionDropped("Drop 1")
        advanceUntilIdle()
        manager.onSessionDropped("Drop 2")
        advanceUntilIdle()
        manager.onSessionDropped("Drop 3")
        advanceUntilIdle()
        manager.onSessionDropped("Drop 4")
        advanceUntilIdle()

        val finalState = manager.reconnectState.value
        assertTrue("State must be Failed when retries exhausted", finalState is ReconnectState.Failed)
        assertTrue("Exhausted flag must be true", (finalState as ReconnectState.Failed).exhausted)
    }

    @Test
    fun testUserPauseAndResumeLifecycle() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val manager = AutoReconnectManagerImpl(
            engine = mockEngine,
            configProvider = { testConfig },
            coroutineScope = this,
            ioDispatcher = testDispatcher
        )

        manager.onUserPause()
        val pausedState = manager.reconnectState.value
        assertTrue("State must be Suspended", pausedState is ReconnectState.Suspended)
        assertTrue("userPaused must be true", (pausedState as ReconnectState.Suspended).userPaused)

        manager.onUserResume()
        advanceUntilIdle()

        assertTrue(
            "State must recover to Connected after resume",
            manager.reconnectState.value is ReconnectState.Connected
        )
    }

    @Test
    fun testCancelReconnectResetsToIdle() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val manager = AutoReconnectManagerImpl(
            engine = mockEngine,
            configProvider = { testConfig },
            coroutineScope = this,
            ioDispatcher = testDispatcher,
            baseDelayMs = 5000L,
            // Pin full-jitter to the max so the backoff can never elapse inside the
            // 100ms advance below (otherwise jitter < 100ms races to Connected).
            randomProvider = { _, max -> max }
        )

        manager.onSessionDropped("Network glitch")
        advanceTimeBy(100L)
        assertTrue(manager.reconnectState.value is ReconnectState.Reconnecting)

        manager.cancelReconnect()
        advanceUntilIdle()

        assertTrue(
            "Canceling reconnect must return to Idle",
            manager.reconnectState.value is ReconnectState.Idle
        )
    }

    // ---- Boundary / hardening tests (F20, Tier-2 style) ----

    @Test
    fun testZeroDelayBackoffExecutesImmediately() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val manager = AutoReconnectManagerImpl(
            engine = mockEngine,
            configProvider = { testConfig },
            coroutineScope = this,
            ioDispatcher = testDispatcher,
            baseDelayMs = 0L,
            maxDelayMs = 0L,
            randomProvider = { _, max -> max }
        )

        // base=0 => full-jitter window collapses to a deterministic zero delay.
        assertEquals(0L, AutoReconnectManagerImpl.calculateBackoffWithJitter(0, baseDelayMs = 0L, maxDelayMs = 0L))

        manager.onSessionDropped("instant retry")
        advanceUntilIdle()

        assertTrue(
            "Zero-delay backoff must reconnect without advancing virtual time",
            manager.reconnectState.value is ReconnectState.Connected
        )
        assertNotNull("Engine must have been invoked", mockEngine.activeConfig)
    }

    @Test
    fun testNegativeBackoffParametersRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            AutoReconnectManagerImpl.calculateBackoffWithJitter(0, baseDelayMs = -1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AutoReconnectManagerImpl.calculateBackoffWithJitter(0, maxDelayMs = -1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AutoReconnectManagerImpl.calculateBackoffWithJitter(-1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AutoReconnectManagerImpl(
                engine = mockEngine,
                configProvider = { testConfig },
                coroutineScope = kotlinx.coroutines.CoroutineScope(
                    kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined
                ),
                maxAttempts = -1
            )
        }
    }

    @Test
    fun testBackoffExponentClampedAt30AndAtZero() {
        val maxProvider: (Long, Long) -> Long = { _, max -> max }
        // attempt=30 is the clamp ceiling: base * 2^30 >> maxDelay => capped to maxDelay.
        assertEquals(
            30_000L,
            AutoReconnectManagerImpl.calculateBackoffWithJitter(30, randomProvider = maxProvider)
        )
        // Attempts beyond the clamp (31, 999) must behave identically — no Long overflow.
        assertEquals(
            30_000L,
            AutoReconnectManagerImpl.calculateBackoffWithJitter(31, randomProvider = maxProvider)
        )
        assertEquals(
            30_000L,
            AutoReconnectManagerImpl.calculateBackoffWithJitter(999, randomProvider = maxProvider)
        )
        // attempt=0 degenerates to the base delay.
        assertEquals(
            1_000L,
            AutoReconnectManagerImpl.calculateBackoffWithJitter(0, randomProvider = maxProvider)
        )
        // Lower-bound provider: full jitter can select 0 (the "random(0, cap)" floor).
        val minProvider: (Long, Long) -> Long = { min, _ -> min }
        assertEquals(0L, AutoReconnectManagerImpl.calculateBackoffWithJitter(4, randomProvider = minProvider))
    }

    @Test
    fun testRapidNetworkFlappingConvergesToConnected() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val manager = AutoReconnectManagerImpl(
            engine = mockEngine,
            configProvider = { testConfig },
            coroutineScope = this,
            ioDispatcher = testDispatcher,
            baseDelayMs = 1000L,
            randomProvider = { _, max -> max }
        )

        repeat(10) {
            manager.onNetworkLost()
            advanceUntilIdle()
            assertTrue(
                "Flap cycle $it must pass through WaitingForNetwork",
                manager.reconnectState.value is ReconnectState.WaitingForNetwork
            )
            manager.onNetworkAvailable()
            advanceUntilIdle()
        }

        assertTrue(
            "After rapid lost/available flapping the manager must converge to Connected",
            manager.reconnectState.value is ReconnectState.Connected
        )
        assertFalse(manager.reconnectState.value is ReconnectState.Failed)
        assertNotNull(mockEngine.activeConfig)
    }

    @Test
    fun testUserPauseDuringBackoffCancelsPendingAttempt() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val manager = AutoReconnectManagerImpl(
            engine = mockEngine,
            configProvider = { testConfig },
            coroutineScope = this,
            ioDispatcher = testDispatcher,
            baseDelayMs = 5000L,
            randomProvider = { _, max -> max }
        )

        manager.onSessionDropped("drop before pause")
        advanceTimeBy(100L)
        assertTrue(manager.reconnectState.value is ReconnectState.Reconnecting)

        manager.onUserPause()
        advanceUntilIdle()
        assertTrue(manager.reconnectState.value is ReconnectState.Suspended)
        assertTrue((manager.reconnectState.value as ReconnectState.Suspended).userPaused)

        // The cancelled backoff must NEVER fire while paused.
        advanceTimeBy(60_000L)
        advanceUntilIdle()
        assertTrue(
            "Suspended state must survive arbitrary time advance",
            manager.reconnectState.value is ReconnectState.Suspended
        )
        assertNull("No connection attempt may occur while user-paused", mockEngine.activeConfig)

        manager.onUserResume()
        advanceUntilIdle()
        assertTrue(
            "Resume must reconnect immediately",
            manager.reconnectState.value is ReconnectState.Connected
        )
        assertNotNull(mockEngine.activeConfig)
    }

    @Test
    fun testNetworkLostDuringReconnectHaltsUntilNetworkReturns() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val manager = AutoReconnectManagerImpl(
            engine = mockEngine,
            configProvider = { testConfig },
            coroutineScope = this,
            ioDispatcher = testDispatcher,
            baseDelayMs = 5000L,
            randomProvider = { _, max -> max }
        )

        manager.onSessionDropped("tcp reset")
        advanceTimeBy(100L)
        assertTrue(manager.reconnectState.value is ReconnectState.Reconnecting)

        // Network drops while a backoff delay is pending: teardown must cancel it.
        manager.onNetworkLost()
        advanceUntilIdle()
        assertTrue(manager.reconnectState.value is ReconnectState.WaitingForNetwork)

        advanceTimeBy(60_000L)
        advanceUntilIdle()
        assertTrue(
            "Manager must keep waiting for the network indefinitely",
            manager.reconnectState.value is ReconnectState.WaitingForNetwork
        )
        assertNull("No reconnect attempt while offline", mockEngine.activeConfig)

        manager.onNetworkAvailable()
        advanceUntilIdle()
        assertTrue(
            "Network return must fast-path reconnect",
            manager.reconnectState.value is ReconnectState.Connected
        )
    }

    @Test
    fun testShutdownIsIdempotentAndBlocksFurtherTransitions() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val manager = AutoReconnectManagerImpl(
            engine = mockEngine,
            configProvider = { testConfig },
            coroutineScope = this,
            ioDispatcher = testDispatcher
        )

        assertFalse(manager.isShutDown)
        manager.shutdown()
        manager.shutdown() // idempotent
        assertTrue(manager.isShutDown)

        // Post-shutdown signals must be ignored (no coroutine launch, no state change).
        manager.onSessionDropped("after shutdown")
        manager.onNetworkLost()
        manager.onUserPause()
        advanceUntilIdle()

        assertTrue(
            "State must remain Idle after shutdown",
            manager.reconnectState.value is ReconnectState.Idle
        )
        assertNull(mockEngine.activeConfig)
    }
}
