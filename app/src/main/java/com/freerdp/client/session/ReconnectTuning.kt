package com.freerdp.client.session

import kotlin.random.Random

/**
 * Tuning knobs for the auto-reconnect backoff state machine.
 * Tests inject deterministic values (fixed jitter, short delays); production uses defaults.
 */
data class ReconnectTuning(
    val maxAttempts: Int = 5,
    val baseDelayMs: Long = 1000L,
    val maxDelayMs: Long = 30000L,
    val jitter: (min: Long, max: Long) -> Long = { min, max ->
        if (max <= min) min else Random.nextLong(min, max + 1)
    }
)
