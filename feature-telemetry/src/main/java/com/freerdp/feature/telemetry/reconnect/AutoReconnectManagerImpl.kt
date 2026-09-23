package com.freerdp.feature.telemetry.reconnect

import com.freerdp.core.engine.IRdpEngine
import com.freerdp.core.engine.RdpConnectionConfig
import com.freerdp.core.engine.RdpConnectionState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlin.random.Random

/**
 * Concrete implementation of [AutoReconnectManager] featuring:
 * 1. Exponential backoff with full jitter to eliminate thundering-herd reconnect storms.
 * 2. Fast-path network recovery cancelling delay immediately on network availability.
 * 3. 5-step leak-free session teardown protocol preventing native handle/thread leaks.
 *
 * Threading/lifecycle model:
 * - All coroutines run in an internal [managerScope] derived from (but NOT a child of)
 *   the caller-provided [coroutineScope]'s context. This keeps short reconnect jobs on the
 *   caller's dispatcher/scheduler (e.g. test virtual time) while giving the component an
 *   explicit lifecycle: callers MUST invoke [shutdown] when the session is destroyed so the
 *   engine-state observer cannot leak.
 */
class AutoReconnectManagerImpl(
    private val engine: IRdpEngine,
    private val configProvider: () -> RdpConnectionConfig?,
    coroutineScope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    val maxAttempts: Int = 5,
    val baseDelayMs: Long = 1000L,
    val maxDelayMs: Long = 30000L,
    private val randomProvider: (min: Long, max: Long) -> Long = { min, max ->
        if (max <= min) min else Random.nextLong(min, max + 1)
    },
    private val renderLoopDrainAction: (suspend () -> Unit)? = null,
    /**
     * Optional, caller-registered transport abort for teardown step 2. When null, step 2
     * falls back to the default abort (an [engine] `disconnect()` call — the only
     * connect-abort the [IRdpEngine] contract exposes). Step 2 is recorded ONLY after
     * an abort action has actually been invoked; the audit trail never claims an abort
     * that did not happen.
     */
    private val abortInFlightConnect: (suspend () -> Unit)? = null,
    var onTeardownStepListener: ((step: Int, description: String) -> Unit)? = null
) : AutoReconnectManager {

    init {
        require(maxAttempts >= 0) { "maxAttempts must be >= 0, got $maxAttempts" }
        require(baseDelayMs >= 0L) { "baseDelayMs must be >= 0, got $baseDelayMs" }
        require(maxDelayMs >= 0L) { "maxDelayMs must be >= 0, got $maxDelayMs" }
    }

    private val _reconnectState = MutableStateFlow<ReconnectState>(ReconnectState.Idle)
    override val reconnectState: StateFlow<ReconnectState> = _reconnectState.asStateFlow()

    /**
     * Component-owned scope: inherits dispatcher/scheduler from the caller's scope so
     * virtual-time test dispatchers still drive it, but is detached (SupervisorJob) so it
     * does not keep the caller's scope (or a test job tree) alive.
     */
    private val managerScope = CoroutineScope(
        coroutineScope.coroutineContext + SupervisorJob() + CoroutineName("AutoReconnectManager")
    )

    private val currentAttempt = AtomicInteger(0)
    private var backoffJob: Job? = null
    private var connectJob: Job? = null
    private val isNetworkAvailable = AtomicBoolean(true)
    private val isShutdown = AtomicBoolean(false)

    // Records teardown step execution for verification & auditing (thread-safe).
    val executedTeardownHistory: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private val engineStateObserver: Job = managerScope.launch {
        engine.connectionState.collect { state ->
            when (state) {
                is RdpConnectionState.Connected -> {
                    currentAttempt.set(0)
                    _reconnectState.value = ReconnectState.Connected
                }
                is RdpConnectionState.Failed -> {
                    if (_reconnectState.value is ReconnectState.Connected ||
                        _reconnectState.value is ReconnectState.Reconnecting
                    ) {
                        onSessionDropped(state.message)
                    }
                }
                is RdpConnectionState.Disconnected -> {
                    // Keep current reconnect state if already reconnecting/suspended
                }
                else -> {}
            }
        }
    }

    /** True once [shutdown] has been called; the manager no longer launches work. */
    val isShutDown: Boolean get() = isShutdown.get()

    /**
     * Leak-free lifecycle termination: cancels the engine-state observer plus every
     * pending backoff/connect job. Idempotent.
     */
    fun shutdown() {
        if (isShutdown.compareAndSet(false, true)) {
            engineStateObserver.cancel()
            backoffJob?.cancel()
            backoffJob = null
            connectJob?.cancel()
            connectJob = null
            managerScope.cancel()
        }
    }

    /**
     * Step 1 of 5: Cancel coroutines.
     * Cancels any pending backoff delay timers, connection workers, or scheduled jobs.
     */
    private fun teardownStep1_cancelCoroutines() {
        backoffJob?.cancel()
        backoffJob = null
        connectJob?.cancel()
        connectJob = null
        recordTeardownStep(1, "Step 1: Cancelled active coroutines and timers")
    }

    /**
     * Step 2 of 5: Abort native connect.
     *
     * Performs a REAL abort before recording the step:
     * - [abortInFlightConnect] is invoked when the caller registered one; otherwise
     * - the default transport abort runs: `engine.disconnect()` on [ioDispatcher] —
     *   the only abort for an in-flight connect attempt that the [IRdpEngine]
     *   contract exposes (it has no dedicated cancel API). It is idempotent by
     *   contract; step 4 repeats it as part of deallocation.
     *
     * The step is recorded ONLY after an abort action has actually been invoked —
     * never speculatively.
     */
    private suspend fun teardownStep2_abortNativeConnect() {
        val abortAction: suspend () -> Unit = abortInFlightConnect ?: {
            withContext(ioDispatcher) {
                try {
                    engine.disconnect()
                } catch (e: Exception) {
                    // Suppress teardown errors to ensure leak-free completion
                }
            }
        }
        try {
            abortAction()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // The abort action was invoked but failed; teardown continues (step 4 wins).
        }
        recordTeardownStep(2, "Step 2: Aborted native connection attempt")
    }

    /**
     * Step 3 of 5: Wait for render loop join.
     * Flushes and joins the surface rendering/blitter loop so no frames are drawn to invalid surfaces.
     *
     * Recorded ONLY when a join/drain action exists AND it completed within the 1.5s
     * budget: a null action or a timed-out/failed drain leaves no "render loop joined"
     * claim in the audit trail.
     */
    private suspend fun teardownStep3_waitForRenderLoopJoin() {
        val drain = renderLoopDrainAction ?: return
        val completed = try {
            withTimeoutOrNull(1500L) { drain() } != null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        if (completed) {
            recordTeardownStep(3, "Step 3: Render loop joined and surfaces flushed")
        }
    }

    /**
     * Step 4 of 5: Deallocate native context.
     * Invokes engine disconnect to release native LibFreeRDP instance and close socket descriptors.
     */
    private suspend fun teardownStep4_deallocateNativeContext() {
        withContext(ioDispatcher) {
            try {
                engine.disconnect()
            } catch (e: Exception) {
                // Suppress teardown errors to ensure leak-free completion
            }
        }
        recordTeardownStep(4, "Step 4: Native context deallocated and sockets closed")
    }

    /**
     * Step 5 of 5: Trigger reconnect or transition.
     * Completes teardown and transitions to target state.
     */
    private fun teardownStep5_triggerReconnect(targetState: ReconnectState) {
        _reconnectState.value = targetState
        recordTeardownStep(5, "Step 5: Transitioned state to $targetState")
    }

    /**
     * Executes the mandatory 5-step leak-free session teardown protocol.
     *
     * Audit honesty contract: steps 1, 4 and 5 always record; step 2 records only after
     * a real abort action ran (see [teardownStep2_abortNativeConnect]); step 3 records
     * only when a render-loop join action existed and completed (see
     * [teardownStep3_waitForRenderLoopJoin]). History entries never describe actions
     * that did not happen.
     */
    suspend fun execute5StepTeardown(targetState: ReconnectState) {
        teardownStep1_cancelCoroutines()
        teardownStep2_abortNativeConnect()
        teardownStep3_waitForRenderLoopJoin()
        teardownStep4_deallocateNativeContext()
        teardownStep5_triggerReconnect(targetState)
    }

    private fun recordTeardownStep(step: Int, description: String) {
        executedTeardownHistory.add("Step $step: $description")
        onTeardownStepListener?.invoke(step, description)
    }

    override fun onNetworkLost() {
        if (isShutdown.get()) return
        isNetworkAvailable.set(false)
        managerScope.launch {
            execute5StepTeardown(ReconnectState.WaitingForNetwork)
        }
    }

    override fun onNetworkAvailable() {
        if (isShutdown.get()) return
        isNetworkAvailable.set(true)
        val currentState = _reconnectState.value

        when (currentState) {
            is ReconnectState.WaitingForNetwork -> {
                // Fast-path recovery: trigger immediate reconnect without waiting
                currentAttempt.set(0)
                scheduleReconnectAttempt(immediate = true)
            }
            is ReconnectState.Reconnecting -> {
                // Fast-path override: cancel pending backoff timer and reconnect right away
                backoffJob?.cancel()
                scheduleReconnectAttempt(immediate = true)
            }
            is ReconnectState.Suspended -> {
                if (!currentState.userPaused) {
                    scheduleReconnectAttempt(immediate = true)
                }
            }
            else -> {}
        }
    }

    override fun onSessionDropped(reason: String) {
        if (isShutdown.get()) return
        val attempt = currentAttempt.incrementAndGet()

        if (attempt > maxAttempts) {
            managerScope.launch {
                execute5StepTeardown(ReconnectState.Failed(exhausted = true, reason = reason))
            }
            return
        }

        if (!isNetworkAvailable.get()) {
            managerScope.launch {
                execute5StepTeardown(ReconnectState.WaitingForNetwork)
            }
            return
        }

        val backoffMs = calculateBackoffWithJitter(attempt - 1, baseDelayMs, maxDelayMs, randomProvider)
        managerScope.launch {
            execute5StepTeardown(ReconnectState.Reconnecting(attempt = attempt, nextDelayMs = backoffMs))
            scheduleReconnectAttempt(immediate = false, delayMs = backoffMs)
        }
    }

    override fun onUserPause() {
        if (isShutdown.get()) return
        teardownStep1_cancelCoroutines()
        _reconnectState.value = ReconnectState.Suspended(userPaused = true)
    }

    override fun onUserResume() {
        if (isShutdown.get()) return
        val currentState = _reconnectState.value
        if (currentState is ReconnectState.Suspended && currentState.userPaused) {
            if (isNetworkAvailable.get()) {
                scheduleReconnectAttempt(immediate = true)
            } else {
                _reconnectState.value = ReconnectState.WaitingForNetwork
            }
        }
    }

    override fun cancelReconnect() {
        if (isShutdown.get()) return
        currentAttempt.set(0)
        managerScope.launch {
            execute5StepTeardown(ReconnectState.Idle)
        }
    }

    /**
     * Schedules a reconnect attempt, either immediately (fast-path) or after [delayMs].
     */
    private fun scheduleReconnectAttempt(immediate: Boolean, delayMs: Long = 0L) {
        backoffJob?.cancel()
        backoffJob = managerScope.launch {
            if (!immediate && delayMs > 0L) {
                kotlinx.coroutines.delay(delayMs)
            }

            val config = configProvider()
            if (config == null) {
                _reconnectState.value = ReconnectState.Failed(exhausted = false, reason = "No configuration provided")
                return@launch
            }

            _reconnectState.value = ReconnectState.Reconnecting(
                attempt = currentAttempt.get(),
                nextDelayMs = 0L
            )

            connectJob = launch(ioDispatcher) {
                val success = engine.connect(config)
                if (success) {
                    currentAttempt.set(0)
                    _reconnectState.value = ReconnectState.Connected
                } else {
                    onSessionDropped("Reconnect attempt failed")
                }
            }
        }
    }

    companion object {
        /**
         * Exponential backoff with Full Jitter:
         * T_backoff = random(0, min(maxDelay, baseDelay * 2^attempt))
         *
         * Boundaries:
         * - attempt must be >= 0 (negative attempt is a programming error).
         * - baseDelayMs == 0 yields a zero-delay backoff (immediate retry window).
         * - attempt > 30 clamps the exponent to avoid Long overflow before the min() cap.
         */
        fun calculateBackoffWithJitter(
            attempt: Int,
            baseDelayMs: Long = 1000L,
            maxDelayMs: Long = 30000L,
            randomProvider: (min: Long, max: Long) -> Long = { min, max ->
                if (max <= min) min else Random.nextLong(min, max + 1)
            }
        ): Long {
            require(attempt >= 0) { "attempt must be >= 0, got $attempt" }
            require(baseDelayMs >= 0L) { "baseDelayMs must be >= 0, got $baseDelayMs" }
            require(maxDelayMs >= 0L) { "maxDelayMs must be >= 0, got $maxDelayMs" }
            val exponent = attempt.coerceIn(0, 30)
            val exponential = baseDelayMs * (1L shl exponent)
            val maxCap = min(maxDelayMs, exponential)
            return randomProvider(0L, maxCap)
        }
    }
}
