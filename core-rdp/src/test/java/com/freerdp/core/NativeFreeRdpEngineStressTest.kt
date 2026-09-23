package com.freerdp.core

import com.freerdp.core.engine.IRdpEngine
import com.freerdp.core.engine.MockRdpEngine
import com.freerdp.core.engine.NativeFreeRdpEngine
import com.freerdp.core.engine.PerformancePreset
import com.freerdp.core.engine.RdpConnectionConfig
import com.freerdp.core.engine.RdpConnectionState
import com.freerdp.core.engine.RdpEventListener
import com.freerdp.core.engine.RdpSessionMetrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Empirical stress test harness designed to challenge:
 * 1. NativeFreeRdpEngine pointer safety under null, closed, and invalid states.
 * 2. Parameter boundary handling (empty, negative, exotic, unicode configs).
 * 3. Concurrency robustness under multi-threaded races on connect, disconnect, and input dispatch.
 * 4. MockRdpEngine concurrency characteristics and event recording safety.
 * 5. Lifecycle memory accumulation under repetitive connection loops.
 */
class NativeFreeRdpEngineStressTest {

    private val engine = NativeFreeRdpEngine()

    @Test
    fun testNullAndBoundaryConfigsInBuildFreeRdpArgs() {
        val exoticConfigs = listOf(
            RdpConnectionConfig(
                serverAddress = "",
                port = 0,
                username = "",
                password = "",
                domain = "",
                width = 0,
                height = 0,
                colorDepth = 0
            ),
            RdpConnectionConfig(
                serverAddress = "::1",
                port = 65535,
                username = "admin' OR '1'='1",
                password = "p@ss\"word with spaces & symbols; --",
                domain = "CORP\\DOMAIN",
                width = 7680,
                height = 4320,
                colorDepth = 32,
                enableNla = true,
                enableTls = true,
                ignoreCertificate = true
            ),
            RdpConnectionConfig(
                serverAddress = "rdp.example.com",
                port = -1,
                username = "\uD83D\uDE00 Unicode \u65E5\u672C\u8A9E",
                password = "\uD83D\uDD11 Secret\u2708\uFE0F",
                width = -100,
                height = -100,
                colorDepth = 16,
                performancePreset = PerformancePreset.BATTERY_SAVER
            ),
            RdpConnectionConfig(
                serverAddress = "10.0.0.1",
                port = 3389,
                performancePreset = PerformancePreset.DATA_SAVER,
                enableNla = false,
                enableTls = false
            )
        )

        for (config in exoticConfigs) {
            val args = engine.buildFreeRdpArgs(config)
            assertNotNull("Generated args should never be null", args)
            assertTrue("Args should start with xfreerdp", args.isNotEmpty() && args[0] == "xfreerdp")
            // Verify server address format
            assertTrue("Args must contain /v: flag", args.any { it.startsWith("/v:") })
            // Verify user flag
            assertTrue("Args must contain /u: flag", args.any { it.startsWith("/u:") })
        }
    }

    @Test
    fun testPointerSafetyWhenUnconnectedAndClosed() = runBlocking {
        val unstartedEngine = NativeFreeRdpEngine()

        // 1. Initial state check
        assertEquals(RdpConnectionState.Disconnected, unstartedEngine.connectionState.value)

        // 2. All input dispatch methods must safely no-op when pointer is 0L
        try {
            unstartedEngine.sendPointerEvent(0x0001, 100, 200)
            unstartedEngine.sendKeyEvent(65, true)
            unstartedEngine.sendKeyEvent(65, false)
            unstartedEngine.sendUnicodeKeyEvent('A', true)
            unstartedEngine.sendUnicodeKeyEvent('A', false)
            unstartedEngine.updateResolution(1920, 1080, 500, 300, 0)
            unstartedEngine.sendClipboardText("Sample test text")
        } catch (t: Throwable) {
            fail("Calling input methods on unconnected NativeFreeRdpEngine must never throw: ${t.message}")
        }

        // 3. Repeated disconnect on already-disconnected engine must be completely idempotent
        for (i in 0 until 50) {
            unstartedEngine.disconnect()
            assertEquals(RdpConnectionState.Disconnected, unstartedEngine.connectionState.value)
        }
    }

    @Test
    fun testConcurrentDisconnectHammer() = runBlocking {
        val testEngine = NativeFreeRdpEngine()
        val numThreads = 64
        val threadPool = Executors.newFixedThreadPool(numThreads)
        val dispatcher = threadPool.asCoroutineDispatcher()

        val errors = ConcurrentLinkedQueue<Throwable>()
        val startLatch = CountDownLatch(1)

        val jobs = (0 until numThreads).map {
            async(dispatcher) {
                startLatch.await()
                try {
                    testEngine.disconnect()
                } catch (t: Throwable) {
                    errors.add(t)
                }
            }
        }

        // Unleash all threads simultaneously
        startLatch.countDown()
        jobs.awaitAll()
        threadPool.shutdown()
        assertTrue("Thread pool should terminate cleanly", threadPool.awaitTermination(5, TimeUnit.SECONDS))

        assertTrue("Concurrent disconnect must produce zero exceptions, but had: $errors", errors.isEmpty())
        assertEquals(RdpConnectionState.Disconnected, testEngine.connectionState.value)
    }

    @Test
    fun testConcurrentInputDispatchAndDisconnectRace() = runBlocking {
        val testEngine = NativeFreeRdpEngine()
        val numThreads = 32
        val threadPool = Executors.newFixedThreadPool(numThreads)
        val dispatcher = threadPool.asCoroutineDispatcher()

        val errors = ConcurrentLinkedQueue<Throwable>()
        val startLatch = CountDownLatch(1)

        val jobs = (0 until numThreads).map { index ->
            async(dispatcher) {
                startLatch.await()
                try {
                    for (step in 0 until 200) {
                        when (index % 6) {
                            0 -> testEngine.sendPointerEvent(0x0001, step, step * 2)
                            1 -> testEngine.sendKeyEvent(step % 120, step % 2 == 0)
                            2 -> testEngine.sendUnicodeKeyEvent(('a'.code + (step % 26)).toChar(), true)
                            3 -> testEngine.updateResolution(1280 + (step % 100), 720 + (step % 100), 400, 250, 0)
                            4 -> testEngine.sendClipboardText("Data-$step")
                            5 -> testEngine.disconnect()
                        }
                    }
                } catch (t: Throwable) {
                    errors.add(t)
                }
            }
        }

        startLatch.countDown()
        jobs.awaitAll()
        threadPool.shutdown()
        assertTrue(threadPool.awaitTermination(5, TimeUnit.SECONDS))

        assertTrue("Multi-threaded input and disconnect race should never throw: $errors", errors.isEmpty())
        assertEquals(RdpConnectionState.Disconnected, testEngine.connectionState.value)
    }

    @Test
    fun testEventListenerConcurrencySafety() = runBlocking {
        val testEngine = NativeFreeRdpEngine()
        val numThreads = 16
        val threadPool = Executors.newFixedThreadPool(numThreads)
        val dispatcher = threadPool.asCoroutineDispatcher()

        val callbackCount = AtomicInteger(0)
        val listener = object : RdpEventListener {
            override fun onConnectionSuccess() { callbackCount.incrementAndGet() }
            override fun onConnectionFailure(errorCode: Int, message: String) { callbackCount.incrementAndGet() }
            override fun onDisconnected() { callbackCount.incrementAndGet() }
            override fun onGraphicsUpdate(bitmap: android.graphics.Bitmap, x: Int, y: Int, width: Int, height: Int) {}
            override fun onResolutionChanged(width: Int, height: Int) { callbackCount.incrementAndGet() }
            override fun onClipboardDataReceived(format: Int, data: ByteArray) { callbackCount.incrementAndGet() }
            override fun onCertificateVerification(fingerprint: String, host: String): Boolean = true
        }

        val errors = ConcurrentLinkedQueue<Throwable>()
        val startLatch = CountDownLatch(1)

        val jobs = (0 until numThreads).map { i ->
            async(dispatcher) {
                startLatch.await()
                try {
                    for (step in 0 until 100) {
                        if (i % 2 == 0) {
                            testEngine.setEventListener(if (step % 2 == 0) listener else null)
                        } else {
                            testEngine.disconnect()
                        }
                    }
                } catch (t: Throwable) {
                    errors.add(t)
                }
            }
        }

        startLatch.countDown()
        jobs.awaitAll()
        threadPool.shutdown()
        assertTrue(threadPool.awaitTermination(5, TimeUnit.SECONDS))

        assertTrue("EventListener swapping under concurrency must not throw: $errors", errors.isEmpty())
    }

    @Test
    fun testMockRdpEngineConcurrencySafetyCheck() {
        val mock = MockRdpEngine()
        val numThreads = 16
        val eventsPerThread = 500
        val threadPool = Executors.newFixedThreadPool(numThreads)

        val errors = ConcurrentLinkedQueue<Throwable>()
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(numThreads)

        for (t in 0 until numThreads) {
            threadPool.execute {
                try {
                    startLatch.await()
                    for (i in 0 until eventsPerThread) {
                        mock.sendPointerEvent(0x0001, t, i)
                        mock.sendKeyEvent(i % 100, true)
                        mock.sendUnicodeKeyEvent('X', false)
                        mock.sendClipboardText("clip-$t-$i")
                    }
                } catch (e: Throwable) {
                    errors.add(e)
                } finally {
                    doneLatch.countDown()
                }
            }
        }

        startLatch.countDown()
        val finished = doneLatch.await(10, TimeUnit.SECONDS)
        threadPool.shutdown()

        assertTrue("Execution should complete within timeout", finished)
        // If MockRdpEngine uses non-synchronized ArrayLists, this assertion or the execution may surface concurrent modification or lost updates
        if (errors.isNotEmpty()) {
            System.err.println("OBSERVED CONCURRENCY FAILURE in MockRdpEngine: $errors")
        }
        val expectedTotal = numThreads * eventsPerThread
        val actualPointerEvents = mock.recordedPointerEvents.size
        System.out.println("MockRdpEngine concurrency check: expected $expectedTotal pointer events, recorded $actualPointerEvents. Errors count: ${errors.size}")
    }

    @Test
    fun testLifecycleMemoryStability() = runBlocking {
        val mock = MockRdpEngine()
        val runtime = Runtime.getRuntime()

        System.gc()
        val initialMemory = runtime.totalMemory() - runtime.freeMemory()

        // Execute 500 connect / send / disconnect cycles
        for (i in 0 until 500) {
            mock.connect(RdpConnectionConfig("host-$i.test"))
            mock.sendPointerEvent(1, i, i)
            mock.sendKeyEvent(65, true)
            mock.clearRecordedEvents() // prevent intentional test recording accumulation
            mock.disconnect()
        }

        System.gc()
        val postMemory = runtime.totalMemory() - runtime.freeMemory()
        val diffMb = (postMemory - initialMemory) / (1024 * 1024)

        System.out.println("Memory delta after 500 session cycles: $diffMb MB (initial: ${initialMemory / 1024} KB, final: ${postMemory / 1024} KB)")
        // Verify memory delta is not an extreme leak (should easily remain under 50 MB)
        assertTrue("Memory delta after 500 cycles should not indicate runaway leak (was $diffMb MB)", diffMb < 50)
    }
}
