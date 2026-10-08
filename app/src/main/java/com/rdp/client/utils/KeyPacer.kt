package com.rdp.client.utils

import com.rdp.client.freerdp.LibFreeRDP
import com.rdp.client.freerdp.RdpScancode
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

/**
 * Calibrated Keystroke Pacer delivering 18ms keydown duration and 22ms inter-key pacing.
 * Prevents remote input queue drops, stuck auto-repeats, and buffer overruns.
 */
class KeyPacer(
    private val instanceProvider: () -> Long,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {
    companion object {
        const val KEYDOWN_DURATION_MS = 18L
        const val INTER_KEY_PACING_MS = 22L
    }

    sealed class PacedEvent {
        data class ScancodeEvent(val scancode: RdpScancode, val onComplete: (() -> Unit)? = null) : PacedEvent()
        data class UnicodeEvent(val codePoint: Int, val onComplete: (() -> Unit)? = null) : PacedEvent()
    }

    private val eventChannel = Channel<PacedEvent>(256)
    private val isRunning = AtomicBoolean(true)
    private var processingJob: Job? = null
    private var currentlyHeldScancode: RdpScancode? = null
    private var currentlyHeldUnicode: Int? = null

    // For test observation and validation
    var dispatchedCount: Int = 0
        private set

    init {
        processingJob = scope.launch {
            processQueue()
        }
    }

    private suspend fun processQueue() {
        for (event in eventChannel) {
            if (!coroutineContext.isActive || !isRunning.get()) break
            val instance = instanceProvider()
            if (instance == 0L) continue

            when (event) {
                is PacedEvent.ScancodeEvent -> {
                    val sc = event.scancode
                    currentlyHeldScancode = sc
                    // 1. Emit Key Down
                    LibFreeRDP.sendKeyEvent(instance, sc.code, sc.isExtended, down = true)
                    dispatchedCount++
                    try {
                        delay(KEYDOWN_DURATION_MS)
                    } finally {
                        withContext(NonCancellable) {
                            if (currentlyHeldScancode != null) {
                                val currentInstance = instanceProvider()
                                if (currentInstance != 0L) {
                                    LibFreeRDP.sendKeyEvent(currentInstance, sc.code, sc.isExtended, down = false)
                                }
                                currentlyHeldScancode = null
                                event.onComplete?.invoke()
                            }
                        }
                    }

                    if (!coroutineContext.isActive || !isRunning.get()) {
                        return
                    }

                    try {
                        delay(INTER_KEY_PACING_MS)
                    } catch (e: CancellationException) {
                        return
                    }
                }

                is PacedEvent.UnicodeEvent -> {
                    val cp = event.codePoint
                    currentlyHeldUnicode = cp
                    // 1. Emit Unicode Down / Char
                    sendUnicode(instance, cp)
                    dispatchedCount++
                    try {
                        delay(KEYDOWN_DURATION_MS)
                    } finally {
                        withContext(NonCancellable) {
                            currentlyHeldUnicode = null
                            event.onComplete?.invoke()
                        }
                    }

                    if (!coroutineContext.isActive || !isRunning.get()) {
                        return
                    }

                    try {
                        delay(INTER_KEY_PACING_MS)
                    } catch (e: CancellationException) {
                        return
                    }
                }
            }
        }
    }

    private fun sendUnicode(instance: Long, codePoint: Int) {
        if (Character.isSupplementaryCodePoint(codePoint)) {
            val high = Character.highSurrogate(codePoint).code
            val low = Character.lowSurrogate(codePoint).code
            LibFreeRDP.sendUnicodeKeyEvent(instance, high)
            LibFreeRDP.sendUnicodeKeyEvent(instance, low)
        } else {
            LibFreeRDP.sendUnicodeKeyEvent(instance, codePoint)
        }
    }

    /**
     * Enqueues an IBM PC AT scancode for paced transmission.
     */
    fun enqueueKey(scancode: RdpScancode, onComplete: (() -> Unit)? = null) {
        if (isRunning.get()) {
            eventChannel.trySend(PacedEvent.ScancodeEvent(scancode, onComplete))
        }
    }

    /**
     * Enqueues a Unicode code point for paced transmission.
     */
    fun enqueueUnicode(codePoint: Int, onComplete: (() -> Unit)? = null) {
        if (isRunning.get()) {
            eventChannel.trySend(PacedEvent.UnicodeEvent(codePoint, onComplete))
        }
    }

    /**
     * Enqueues a full text string (e.g. from clipboard paste) for calibrated pacing.
     */
    fun enqueueText(text: String) {
        var offset = 0
        while (offset < text.length) {
            val codePoint = text.codePointAt(offset)
            enqueueUnicode(codePoint)
            offset += Character.charCount(codePoint)
        }
    }

    /**
     * Flushes queue and guarantees release of any currently held key.
     */
    fun cancelAndReleaseHeld() {
        if (!isRunning.compareAndSet(true, false)) {
            return
        }
        processingJob?.cancel()
        eventChannel.cancel()

        // Drain any remaining events in the channel
        while (eventChannel.tryReceive().isSuccess) {
            // drained
        }

        val instance = instanceProvider()
        if (instance != 0L) {
            currentlyHeldScancode?.let { sc ->
                LibFreeRDP.sendKeyEvent(instance, sc.code, sc.isExtended, down = false)
                currentlyHeldScancode = null
            }
            currentlyHeldUnicode?.let {
                currentlyHeldUnicode = null
            }
        } else {
            currentlyHeldScancode = null
            currentlyHeldUnicode = null
        }
    }
}
