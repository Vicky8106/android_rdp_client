package com.freerdp.feature.telemetry

import com.freerdp.feature.telemetry.pacer.RdpThreadIsolation
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.EmptyCoroutineContext

/**
 * 5-thread isolation architecture tests (PROJECT.md feature #21 / F20-F24 suite):
 * dispatcher existence & naming, rejected-execution behavior, bounded input channel,
 * and clean shutdown.
 */
class RdpThreadIsolationTest {

    private var isolation: RdpThreadIsolation? = null

    @After
    fun tearDown() {
        isolation?.close()
        isolation = null
    }

    private fun newIsolation(): RdpThreadIsolation =
        RdpThreadIsolation(mainDispatcher = Dispatchers.Unconfined).also { isolation = it }

    /** Synchronously executes [unit] on [dispatcher] and returns the worker thread name. */
    private fun threadNameOn(dispatcher: kotlinx.coroutines.CoroutineDispatcher): String {
        val nameRef = AtomicReference<String>("")
        val ran = AtomicBoolean(false)
        dispatcher.dispatch(EmptyCoroutineContext, Runnable {
            nameRef.set(Thread.currentThread().name)
            ran.set(true)
        })
        val deadline = System.currentTimeMillis() + 5_000L
        while (!ran.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(1L)
        }
        assertTrue("Task must execute on the dispatcher", ran.get())
        return nameRef.get()
    }

    @Test
    fun testFiveDispatchersExistAndAreDistinct() {
        val iso = newIsolation()

        // 5 dispatchers: socket IO, decoder, render/blitter, input, main.
        val dispatchers = listOf(
            iso.socketIoDispatcher,
            iso.decoderDispatcher,
            iso.renderDispatcher,
            iso.inputDispatcher,
            iso.mainDispatcher
        )
        assertEquals(5, dispatchers.size)
        // All five must be distinct instances (no accidental aliasing between roles).
        for (i in dispatchers.indices) {
            for (j in i + 1 until dispatchers.size) {
                assertNotSame(
                    "Dispatchers $i and $j must be distinct",
                    dispatchers[i],
                    dispatchers[j]
                )
            }
        }
        // Main dispatcher is the injected one (Android main looper in production).
        assertEquals(Dispatchers.Unconfined, iso.mainDispatcher)
    }

    @Test
    fun testOwnedThreadsUseDedicatedNames() {
        val iso = newIsolation()

        val ioName = threadNameOn(iso.socketIoDispatcher)
        val decoderName = threadNameOn(iso.decoderDispatcher)
        val renderName = threadNameOn(iso.renderDispatcher)
        val inputName = threadNameOn(iso.inputDispatcher)

        assertTrue("I/O thread misnamed: $ioName", ioName.startsWith("RdpIoThread-"))
        assertTrue("Decoder thread misnamed: $decoderName", decoderName.startsWith("RdpDecoderThread-"))
        assertTrue("Render thread misnamed: $renderName", renderName.startsWith("RdpRenderThread-"))
        assertTrue("Input thread misnamed: $inputName", inputName.startsWith("RdpInputThread-"))

        // Dedicated: no two roles may share a thread.
        val names = setOf(ioName, decoderName, renderName, inputName)
        assertEquals("Each role must own a distinct thread", 4, names.size)
    }

    @Test
    fun testDispatchAfterCloseThrowsRejectedExecution() {
        val iso = newIsolation()
        iso.close()

        for (dispatcher in listOf(iso.socketIoDispatcher, iso.decoderDispatcher, iso.renderDispatcher, iso.inputDispatcher)) {
            assertThrows(RejectedExecutionException::class.java) {
                dispatcher.dispatch(EmptyCoroutineContext, Runnable { })
            }
        }
    }

    @Test
    fun testCloseIsIdempotentAndCoversInputPool() {
        val iso = newIsolation()

        assertFalse(iso.isClosed)
        assertFalse(iso.isShutdown)

        iso.close()
        iso.close() // idempotent — must not throw

        assertTrue(iso.isClosed)
        assertTrue(
            "isShutdown must cover ALL FOUR owned pools, including the input pool",
            iso.isShutdown
        )
        assertTrue("Input channel must be closed", iso.inputChannel.isClosedForSend)
    }

    @Test
    fun testCleanShutdownTerminatesAllExecutors() {
        val iso = newIsolation()

        // Put one short task in flight on each pool and wait for completion.
        val latch = java.util.concurrent.CountDownLatch(4)
        for (dispatcher in listOf(iso.socketIoDispatcher, iso.decoderDispatcher, iso.renderDispatcher, iso.inputDispatcher)) {
            dispatcher.dispatch(EmptyCoroutineContext, Runnable {
                Thread.sleep(20L)
                latch.countDown()
            })
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS))

        iso.close()
        assertTrue(
            "All executors must terminate within the timeout after close",
            iso.awaitTermination(5_000L)
        )
        assertTrue("isShutdown must hold after termination", iso.isShutdown)
    }

    @Test
    fun testInputChannelDropsOldestAtExactCapacityBoundary() {
        val iso = newIsolation()

        // Exactly 64 enqueued: nothing dropped yet (capacity = 64).
        for (i in 0 until 64) {
            assertTrue(iso.inputChannel.trySend(i).isSuccess)
        }
        assertEquals(0, iso.inputChannel.tryReceive().getOrThrow())

        iso.close()
    }

    @Test
    fun testInputChannelDropsOldestBeyondCapacity() {
        val iso = newIsolation()

        // 65 sends into a capacity-64 DROP_OLDEST channel: element 0 is evicted.
        for (i in 0 until 65) {
            assertTrue(iso.inputChannel.trySend(i).isSuccess)
        }

        val received = mutableListOf<Any>()
        while (true) {
            val result = iso.inputChannel.tryReceive()
            if (result.isFailure) break
            received.add(result.getOrThrow())
        }

        assertEquals("Channel must clamp to capacity", 64, received.size)
        assertEquals("Oldest event (0) must be dropped first", 1, received.first())
        assertEquals(64, received.last())
        assertFalse(received.contains(0))

        iso.close()
    }

    @Test
    fun testAwaitTerminationRejectsNegativeTimeout() {
        val iso = newIsolation()
        assertThrows(IllegalArgumentException::class.java) {
            iso.awaitTermination(-1L)
        }
    }
}
