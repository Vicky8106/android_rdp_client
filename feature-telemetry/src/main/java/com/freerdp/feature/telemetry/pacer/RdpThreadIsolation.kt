package com.freerdp.feature.telemetry.pacer

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

/**
 * 5-Thread Isolation Architecture for FreeRDP streaming on Android.
 *
 * Dedicated isolated thread pools prevent network blocking, heavy decompression,
 * and input handling from contending with the Android Main UI thread or each other:
 * 1. [socketIoDispatcher]: Native epoll network I/O and packet demuxing ("RdpIoThread").
 * 2. [decoderDispatcher]: RemoteFX / H.264 / Planar RLE decompression ("RdpDecoderThread").
 * 3. [renderDispatcher]: VSYNC display blitter loop ("RdpRenderThread").
 * 4. [inputDispatcher]: Touch gesture and key event serialization ("RdpInputThread").
 * 5. [mainDispatcher]: Android UI thread for views, HUD, and overlays
 *    (provided externally — the Android main looper thread; not owned/closed here).
 *
 * Lifecycle contract:
 * - The four owned dispatchers are guarded: dispatching work after [close] throws
 *   [RejectedExecutionException] (fail-fast) instead of silently swallowing frames.
 * - [close] is idempotent; [awaitTermination] supports deterministic clean shutdown.
 * - [mainDispatcher] is a pass-through to the injected dispatcher and is never rejected
 *   (Android main-thread work must not fail fast during teardown).
 */
class RdpThreadIsolation(
    val mainDispatcher: CoroutineDispatcher = Dispatchers.Main
) : Closeable {

    private class NamedThreadFactory(private val prefix: String) : ThreadFactory {
        private val count = AtomicInteger(1)
        override fun newThread(r: Runnable): Thread {
            val t = Thread(r, "$prefix-${count.getAndIncrement()}")
            t.isDaemon = true
            return t
        }
    }

    /**
     * Wraps an owned dispatcher so post-close dispatch fails fast with
     * [RejectedExecutionException] (rejected-execution behavior) before reaching the
     * executor's own rejection path.
     */
    private class GuardedDispatcher(
        private val delegate: CoroutineDispatcher,
        private val threadName: String,
        private val closed: AtomicBoolean
    ) : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (closed.get()) {
                throw RejectedExecutionException("$threadName dispatcher has been shut down")
            }
            delegate.dispatch(context, block)
        }
    }

    private val closed = AtomicBoolean(false)

    private val ioExecutor = Executors.newSingleThreadExecutor(NamedThreadFactory("RdpIoThread"))
    val socketIoDispatcher: CoroutineDispatcher =
        GuardedDispatcher(ioExecutor.asCoroutineDispatcher(), "RdpIoThread", closed)

    private val decoderExecutor = Executors.newFixedThreadPool(
        Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
        NamedThreadFactory("RdpDecoderThread")
    )
    val decoderDispatcher: CoroutineDispatcher =
        GuardedDispatcher(decoderExecutor.asCoroutineDispatcher(), "RdpDecoderThread", closed)

    private val renderExecutor = Executors.newSingleThreadExecutor(NamedThreadFactory("RdpRenderThread"))
    val renderDispatcher: CoroutineDispatcher =
        GuardedDispatcher(renderExecutor.asCoroutineDispatcher(), "RdpRenderThread", closed)

    private val inputExecutor = Executors.newSingleThreadExecutor(NamedThreadFactory("RdpInputThread"))
    val inputDispatcher: CoroutineDispatcher =
        GuardedDispatcher(inputExecutor.asCoroutineDispatcher(), "RdpInputThread", closed)

    /**
     * Non-blocking bounded input channel that drops oldest events on overflow
     * to eliminate input backlog under extreme lag (capacity 64).
     */
    val inputChannel = Channel<Any>(
        capacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** True once [close] has run. */
    val isClosed: Boolean get() = closed.get()

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            inputChannel.close()
            ioExecutor.shutdown()
            decoderExecutor.shutdown()
            renderExecutor.shutdown()
            inputExecutor.shutdown()
        }
    }

    /**
     * Blocks (up to [timeoutMs]) until every owned executor terminates after [close].
     * Returns true when all four pools fully terminated — the clean-shutdown assertion
     * used by tests and by session teardown.
     */
    fun awaitTermination(timeoutMs: Long): Boolean {
        require(timeoutMs >= 0L) { "timeoutMs must be >= 0" }
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        var allTerminated = true
        for (executor in arrayOf(ioExecutor, decoderExecutor, renderExecutor, inputExecutor)) {
            val remainingNanos = deadline - System.nanoTime()
            val waitMs = if (remainingNanos > 0) remainingNanos / 1_000_000L else 0L
            allTerminated = executor.awaitTermination(waitMs, TimeUnit.MILLISECONDS) && allTerminated
        }
        return allTerminated
    }

    /** True once every owned executor has been shut down (incl. the input pool). */
    val isShutdown: Boolean
        get() = ioExecutor.isShutdown && decoderExecutor.isShutdown &&
                renderExecutor.isShutdown && inputExecutor.isShutdown
}
