package com.freerdp.core

import android.graphics.Bitmap
import com.freerdp.core.engine.MockRdpEngine
import com.freerdp.core.engine.RdpConnectionConfig
import com.freerdp.core.engine.RdpConnectionState
import com.freerdp.core.engine.RdpEventListener
import com.freerdp.core.protocol.ClipboardHandler
import com.freerdp.core.protocol.DisplayControlHandler
import com.freerdp.core.protocol.RdpPointerFlags
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class ProtocolStressTest {

    // =========================================================================
    // 1. MockRdpEngine Challenges
    // =========================================================================

    @Test
    fun testMockRdpEngine_HighFrequencyPointerEvents10000() {
        val engine = MockRdpEngine()
        val eventCount = 10_000

        val startTime = System.nanoTime()
        for (i in 0 until eventCount) {
            val flags = if (i % 2 == 0) RdpPointerFlags.PTR_FLAGS_MOVE else RdpPointerFlags.LEFT_BUTTON_DOWN
            engine.sendPointerEvent(flags, x = i % 1920, y = (i * 2) % 1080)
        }
        val durationMs = (System.nanoTime() - startTime) / 1_000_000

        // Verify all 10,000 events recorded in exact sequence
        assertEquals(eventCount, engine.recordedPointerEvents.size)
        assertEquals(RdpPointerFlags.PTR_FLAGS_MOVE, engine.recordedPointerEvents[0].flags)
        assertEquals(0, engine.recordedPointerEvents[0].x)
        assertEquals(0, engine.recordedPointerEvents[0].y)

        val lastIndex = eventCount - 1
        assertEquals(RdpPointerFlags.LEFT_BUTTON_DOWN, engine.recordedPointerEvents[lastIndex].flags)
        assertEquals(lastIndex % 1920, engine.recordedPointerEvents[lastIndex].x)
        assertEquals((lastIndex * 2) % 1080, engine.recordedPointerEvents[lastIndex].y)

        // Throughput check: 10,000 events should complete in well under 1000ms
        assertTrue("Expected 10,000 events in < 1000ms, took ${durationMs}ms", durationMs < 1000)

        // Clear recorded events to verify memory release
        engine.clearRecordedEvents()
        assertEquals(0, engine.recordedPointerEvents.size)
    }

    @Test
    fun testMockRdpEngine_RapidConnectDisconnectCycles() = runBlocking {
        val engine = MockRdpEngine()
        val cycles = 500
        val config = RdpConnectionConfig(serverAddress = "stress.test.server")

        val connectSuccessCount = AtomicInteger(0)
        val disconnectCount = AtomicInteger(0)

        engine.setEventListener(object : RdpEventListener {
            override fun onConnectionSuccess() {
                connectSuccessCount.incrementAndGet()
            }
            override fun onConnectionFailure(errorCode: Int, message: String) {}
            override fun onDisconnected() {
                disconnectCount.incrementAndGet()
            }
            override fun onGraphicsUpdate(bitmap: Bitmap, x: Int, y: Int, width: Int, height: Int) {}
            override fun onResolutionChanged(width: Int, height: Int) {}
            override fun onClipboardDataReceived(format: Int, data: ByteArray) {}
            override fun onCertificateVerification(fingerprint: String, host: String): Boolean = true
        })

        for (i in 0 until cycles) {
            val connected = engine.connect(config)
            assertTrue(connected)
            assertTrue(engine.connectionState.value.isConnected)
            assertEquals(config, engine.activeConfig)

            engine.disconnect()
            assertTrue(engine.connectionState.value.isDisconnected)
            assertNull(engine.activeConfig)
        }

        assertEquals(cycles, connectSuccessCount.get())
        assertEquals(cycles, disconnectCount.get())
        assertEquals(RdpConnectionState.Disconnected, engine.connectionState.value)
    }

    @Test
    fun testMockRdpEngine_SimulatedConnectionFailures() = runBlocking {
        val engine = MockRdpEngine()
        val config = RdpConnectionConfig(serverAddress = "fail.test.server")

        var receivedErrorCode = 0
        var receivedErrorMessage = ""
        var certPrompted = false

        engine.setEventListener(object : RdpEventListener {
            override fun onConnectionSuccess() {}
            override fun onConnectionFailure(errorCode: Int, message: String) {
                receivedErrorCode = errorCode
                receivedErrorMessage = message
            }
            override fun onDisconnected() {}
            override fun onGraphicsUpdate(bitmap: Bitmap, x: Int, y: Int, width: Int, height: Int) {}
            override fun onResolutionChanged(width: Int, height: Int) {}
            override fun onClipboardDataReceived(format: Int, data: ByteArray) {}
            override fun onCertificateVerification(fingerprint: String, host: String): Boolean {
                certPrompted = true
                return false // Reject cert
            }
        })

        // Test 1: shouldFailConnection
        engine.shouldFailConnection = true
        engine.failureErrorCode = 0x00020008
        engine.failureErrorMessage = "RDP server out of memory"
        val failResult = engine.connect(config)
        assertFalse(failResult)
        assertEquals(0x00020008, receivedErrorCode)
        assertEquals("RDP server out of memory", receivedErrorMessage)
        assertTrue(engine.connectionState.value is RdpConnectionState.Failed)

        // Test 2: simulateCertVerification reject
        engine.shouldFailConnection = false
        engine.simulateCertVerification = true
        val certResult = engine.connect(config)
        assertFalse(certResult)
        assertTrue(certPrompted)
        assertEquals(403, (engine.connectionState.value as RdpConnectionState.Failed).errorCode)

        // Test 3: triggerSessionDrop
        engine.triggerSessionDrop("Network timeout dropped session")
        assertEquals(503, (engine.connectionState.value as RdpConnectionState.Failed).errorCode)
        assertEquals("Network timeout dropped session", (engine.connectionState.value as RdpConnectionState.Failed).message)

        // Test 4: triggerConnectionFailure
        engine.triggerConnectionFailure(1005, "Protocol negotiation failed")
        assertEquals(1005, (engine.connectionState.value as RdpConnectionState.Failed).errorCode)
        assertEquals("Protocol negotiation failed", (engine.connectionState.value as RdpConnectionState.Failed).message)
    }

    @Test
    fun testMockRdpEngine_ConcurrentEventListeners() {
        val engine = MockRdpEngine()
        val threadCount = 4
        val iterationsPerThread = 500
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val callbackCount = AtomicInteger(0)

        val dummyListener = object : RdpEventListener {
            override fun onConnectionSuccess() { callbackCount.incrementAndGet() }
            override fun onConnectionFailure(errorCode: Int, message: String) { callbackCount.incrementAndGet() }
            override fun onDisconnected() { callbackCount.incrementAndGet() }
            override fun onGraphicsUpdate(bitmap: Bitmap, x: Int, y: Int, width: Int, height: Int) { callbackCount.incrementAndGet() }
            override fun onResolutionChanged(width: Int, height: Int) { callbackCount.incrementAndGet() }
            override fun onClipboardDataReceived(format: Int, data: ByteArray) { callbackCount.incrementAndGet() }
            override fun onCertificateVerification(fingerprint: String, host: String): Boolean = true
        }

        // Thread 0 toggles listener registration
        executor.submit {
            try {
                for (i in 0 until iterationsPerThread) {
                    engine.setEventListener(if (i % 2 == 0) dummyListener else null)
                }
            } finally {
                latch.countDown()
            }
        }

        // Threads 1-3 invoke triggers concurrently
        for (t in 1 until threadCount) {
            executor.submit {
                try {
                    for (i in 0 until iterationsPerThread) {
                        engine.triggerClipboardReceived(13, byteArrayOf(1, 2, 3))
                        engine.triggerConnectionFailure(500, "Concurrent error")
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        val completed = latch.await(5, TimeUnit.SECONDS)
        executor.shutdown()
        assertTrue("Concurrent listener test timed out", completed)
    }

    // =========================================================================
    // 2. RdpPointerFlags Challenges
    // =========================================================================

    @Test
    fun testRdpPointerFlags_BitwiseIntegrityAndNonOverlap() {
        val baseFlags = listOf(
            RdpPointerFlags.PTR_FLAGS_HWHEEL,         // 0x0400
            RdpPointerFlags.PTR_FLAGS_WHEEL,          // 0x0200
            RdpPointerFlags.PTR_FLAGS_WHEEL_NEGATIVE, // 0x0100
            RdpPointerFlags.PTR_FLAGS_MOVE,           // 0x0800
            RdpPointerFlags.PTR_FLAGS_DOWN,           // 0x8000
            RdpPointerFlags.PTR_FLAGS_BUTTON1,        // 0x1000
            RdpPointerFlags.PTR_FLAGS_BUTTON2,        // 0x2000
            RdpPointerFlags.PTR_FLAGS_BUTTON3         // 0x4000
        )

        // 1. Verify all base flags are single-bit powers of 2
        for (flag in baseFlags) {
            assertTrue("Flag $flag is not power of 2", (flag > 0) && ((flag and (flag - 1)) == 0))
        }

        // 2. Verify all pairs have zero bitwise overlap
        for (i in baseFlags.indices) {
            for (j in i + 1 until baseFlags.size) {
                val f1 = baseFlags[i]
                val f2 = baseFlags[j]
                assertEquals("Collision between flags 0x${f1.toString(16)} and 0x${f2.toString(16)}", 0, f1 and f2)
            }
        }
    }

    @Test
    fun testRdpPointerFlags_MultiButtonSimultaneousDownStates() {
        // Multi-button simultaneous state 1: Left + Right button down
        val lmbRmbDown = RdpPointerFlags.PTR_FLAGS_BUTTON1 or
                RdpPointerFlags.PTR_FLAGS_BUTTON2 or
                RdpPointerFlags.PTR_FLAGS_DOWN
        assertEquals(0xB000, lmbRmbDown)
        assertTrue(RdpPointerFlags.isButtonDown(lmbRmbDown))
        assertTrue(RdpPointerFlags.isButton1(lmbRmbDown))
        assertTrue(RdpPointerFlags.isButton2(lmbRmbDown))
        assertFalse(RdpPointerFlags.isButton3(lmbRmbDown))
        assertFalse(RdpPointerFlags.isMove(lmbRmbDown))

        // Multi-button simultaneous state 2: Left + Middle button down
        val lmbMmbDown = RdpPointerFlags.PTR_FLAGS_BUTTON1 or
                RdpPointerFlags.PTR_FLAGS_BUTTON3 or
                RdpPointerFlags.PTR_FLAGS_DOWN
        assertEquals(0xD000, lmbMmbDown)
        assertTrue(RdpPointerFlags.isButtonDown(lmbMmbDown))
        assertTrue(RdpPointerFlags.isButton1(lmbMmbDown))
        assertFalse(RdpPointerFlags.isButton2(lmbMmbDown))
        assertTrue(RdpPointerFlags.isButton3(lmbMmbDown))

        // Multi-button simultaneous state 3: All three buttons down + Move
        val allButtonsDownMove = RdpPointerFlags.PTR_FLAGS_BUTTON1 or
                RdpPointerFlags.PTR_FLAGS_BUTTON2 or
                RdpPointerFlags.PTR_FLAGS_BUTTON3 or
                RdpPointerFlags.PTR_FLAGS_DOWN or
                RdpPointerFlags.PTR_FLAGS_MOVE
        assertEquals(0xF800, allButtonsDownMove)
        assertTrue(RdpPointerFlags.isButtonDown(allButtonsDownMove))
        assertTrue(RdpPointerFlags.isButton1(allButtonsDownMove))
        assertTrue(RdpPointerFlags.isButton2(allButtonsDownMove))
        assertTrue(RdpPointerFlags.isButton3(allButtonsDownMove))
        assertTrue(RdpPointerFlags.isMove(allButtonsDownMove))
    }

    @Test
    fun testRdpPointerFlags_WheelRotationDeltaMasking() {
        // Default scroll down: WHEEL (0x0200) | NEGATIVE (0x0100) | 120 (0x0078) = 0x0378
        val scrollDown = RdpPointerFlags.SCROLL_DOWN
        assertTrue(RdpPointerFlags.isWheel(scrollDown))
        assertTrue(RdpPointerFlags.isWheelNegative(scrollDown))

        // Extract wheel step without negative flag
        val wheelDelta = scrollDown and RdpPointerFlags.WHEEL_STEP_DEFAULT
        assertEquals(120, wheelDelta)

        // Custom wheel delta: 240 units (double step)
        val customStep = 240
        val customScrollUp = RdpPointerFlags.PTR_FLAGS_WHEEL or (customStep and RdpPointerFlags.WHEEL_ROTATION_MASK)
        assertTrue(RdpPointerFlags.isWheel(customScrollUp))
        assertFalse(RdpPointerFlags.isWheelNegative(customScrollUp))
        assertEquals(240, customScrollUp and RdpPointerFlags.WHEEL_ROTATION_MASK)
    }

    @Test
    fun testRdpPointer_NegativeAndExtremeCoordinates() {
        val engine = MockRdpEngine()

        // Negative coordinates (off-screen gestures, dual-screen relative offsets)
        engine.sendPointerEvent(RdpPointerFlags.PTR_FLAGS_MOVE, -1, -1)
        engine.sendPointerEvent(RdpPointerFlags.PTR_FLAGS_MOVE, -32768, -32768)
        engine.sendPointerEvent(RdpPointerFlags.PTR_FLAGS_MOVE, Int.MIN_VALUE, Int.MIN_VALUE)

        // Extreme positive coordinates (high-DPI 8K or multi-monitor configurations)
        engine.sendPointerEvent(RdpPointerFlags.PTR_FLAGS_MOVE, 65535, 65535)
        engine.sendPointerEvent(RdpPointerFlags.PTR_FLAGS_MOVE, 100000, 200000)
        engine.sendPointerEvent(RdpPointerFlags.PTR_FLAGS_MOVE, Int.MAX_VALUE, Int.MAX_VALUE)

        assertEquals(6, engine.recordedPointerEvents.size)
        assertEquals(-1, engine.recordedPointerEvents[0].x)
        assertEquals(-1, engine.recordedPointerEvents[0].y)
        assertEquals(-32768, engine.recordedPointerEvents[1].x)
        assertEquals(-32768, engine.recordedPointerEvents[1].y)
        assertEquals(Int.MIN_VALUE, engine.recordedPointerEvents[2].x)
        assertEquals(Int.MIN_VALUE, engine.recordedPointerEvents[2].y)
        assertEquals(65535, engine.recordedPointerEvents[3].x)
        assertEquals(65535, engine.recordedPointerEvents[3].y)
        assertEquals(100000, engine.recordedPointerEvents[4].x)
        assertEquals(200000, engine.recordedPointerEvents[4].y)
        assertEquals(Int.MAX_VALUE, engine.recordedPointerEvents[5].x)
        assertEquals(Int.MAX_VALUE, engine.recordedPointerEvents[5].y)
    }

    // =========================================================================
    // 3. DisplayControlHandler Challenges
    // =========================================================================

    @Test
    fun testDisplayControlHandler_RapidOrientationChangesUnder50ms() = runTest {
        val dispatchedLayouts = mutableListOf<DisplayControlHandler.MonitorLayout>()
        val handler = DisplayControlHandler(
            debounceDelayMs = 250L,
            scope = backgroundScope
        ) { layout ->
            dispatchedLayouts.add(layout)
        }

        // Simulate 20 rapid sensor orientation flips arriving every 20ms (< 50ms)
        // Total duration = 20 * 20ms = 400ms > debounce window of 250ms,
        // but each request arrives before debounce timer expires, continuously resetting it.
        for (i in 0 until 20) {
            val isLandscape = (i % 2 == 0)
            val w = if (isLandscape) 1920 else 1080
            val h = if (isLandscape) 1080 else 1920
            handler.requestLayoutUpdate(width = w, height = h, dpi = 160f)
            advanceTimeBy(20)
            runCurrent()
            // Crucial assertion: NO layout should be dispatched yet because interval (20ms) < debounce (250ms)
            assertEquals("Debounce failed at step $i: premature dispatch", 0, dispatchedLayouts.size)
        }

        // Now wait for full debounce window to elapse (250ms) + small margin
        advanceTimeBy(260)
        runCurrent()

        // Exactly ONE layout update should be dispatched, representing the 20th request
        assertEquals("Expected exactly 1 debounced layout", 1, dispatchedLayouts.size)
        val finalLayout = dispatchedLayouts.first()
        // 20th iteration (i = 19, odd): Portrait -> w = 1080, h = 1920
        assertEquals(1080, finalLayout.width)
        assertEquals(1920, finalLayout.height)
        assertEquals(DisplayControlHandler.MonitorLayout.ORIENTATION_PORTRAIT, finalLayout.orientation)
    }

    @Test
    fun testDisplayControlHandler_ImmediateInterruption() = runTest {
        val dispatchedLayouts = mutableListOf<DisplayControlHandler.MonitorLayout>()
        val handler = DisplayControlHandler(
            debounceDelayMs = 250L,
            scope = backgroundScope
        ) { layout ->
            dispatchedLayouts.add(layout)
        }

        // 1. Schedule debounced layout
        handler.requestLayoutUpdate(width = 1200, height = 800, immediate = false)
        advanceTimeBy(100)
        runCurrent()
        assertEquals(0, dispatchedLayouts.size)

        // 2. Immediate request interrupts and cancels pending debounce
        handler.requestLayoutUpdate(width = 1920, height = 1080, immediate = true)
        assertEquals(1, dispatchedLayouts.size)
        assertEquals(1920, dispatchedLayouts.last().width)

        // 3. Advance past original debounce time: no spurious second layout
        advanceTimeBy(300)
        runCurrent()
        assertEquals(1, dispatchedLayouts.size)
    }

    @Test
    fun testDisplayControlHandler_DimensionClampingAndAlignment() {
        // Enforce multiples of 4 alignment
        assertEquals(640, DisplayControlHandler.alignDimension(640, min = 640))
        assertEquals(640, DisplayControlHandler.alignDimension(641, min = 640))
        assertEquals(640, DisplayControlHandler.alignDimension(642, min = 640))
        assertEquals(640, DisplayControlHandler.alignDimension(643, min = 640))
        assertEquals(644, DisplayControlHandler.alignDimension(644, min = 640))

        // Clamping minimums
        assertEquals(640, DisplayControlHandler.alignDimension(0, min = 640))
        assertEquals(640, DisplayControlHandler.alignDimension(-100, min = 640))
        assertEquals(480, DisplayControlHandler.alignDimension(-500, min = 480))
        assertEquals(480, DisplayControlHandler.alignDimension(479, min = 480))

        // High resolution alignment (8K)
        assertEquals(7680, DisplayControlHandler.alignDimension(7683, min = 640))

        // DPI clamping
        assertEquals(0, DisplayControlHandler.calculatePhysicalDimensionMm(1920, 0f))
        assertEquals(0, DisplayControlHandler.calculatePhysicalDimensionMm(1920, -100f))
        assertEquals(304, DisplayControlHandler.calculatePhysicalDimensionMm(1920, 160f))
        assertEquals(152, DisplayControlHandler.calculatePhysicalDimensionMm(3840, 640f))
    }

    // =========================================================================
    // 4. ClipboardHandler Challenges
    // =========================================================================

    @Test
    fun testClipboardHandler_LargeTextBuffer1MB() {
        // Construct 1MB text buffer (1,048,576 chars)
        val sb = StringBuilder(1024 * 1024)
        val chunk = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ!@#$%^&*()-=_+[]"
        while (sb.length < 1024 * 1024) {
            sb.append(chunk)
        }
        val oneMbText = sb.substring(0, 1024 * 1024)
        assertEquals(1024 * 1024, oneMbText.length)

        // 1. Encode to UTF-16LE: each char -> 2 bytes + 2 bytes null termination = 2,097,154 bytes
        val encodeStart = System.nanoTime()
        val encodedBytes = ClipboardHandler.encodeUnicodeText(oneMbText)
        val encodeDurationMs = (System.nanoTime() - encodeStart) / 1_000_000

        assertEquals(2 * 1024 * 1024 + 2, encodedBytes.size)
        assertEquals(0.toByte(), encodedBytes[encodedBytes.size - 1])
        assertEquals(0.toByte(), encodedBytes[encodedBytes.size - 2])
        assertTrue("Encoding 1MB should take < 500ms, took ${encodeDurationMs}ms", encodeDurationMs < 500)

        // 2. Decode back and verify exact round-trip
        val decodeStart = System.nanoTime()
        val decodedText = ClipboardHandler.decodeUnicodeText(encodedBytes)
        val decodeDurationMs = (System.nanoTime() - decodeStart) / 1_000_000

        assertEquals(oneMbText.length, decodedText.length)
        assertEquals(oneMbText, decodedText)
        assertTrue("Decoding 1MB should take < 500ms, took ${decodeDurationMs}ms", decodeDurationMs < 500)

        // 3. Test ClipboardHandler dispatch with 1MB payload
        var remoteData: ByteArray? = null
        val handler = ClipboardHandler(
            onSendRemoteClipboard = { _, data -> remoteData = data },
            onLocalClipboardUpdate = {}
        )

        val handled = handler.onLocalClipboardChanged(oneMbText)
        assertTrue(handled)
        assertNotNull(remoteData)
        assertEquals(encodedBytes.size, remoteData!!.size)

        // Verify SHA-256 echo suppression for 1MB payload
        val echoHandled = handler.onRemoteClipboardReceived(ClipboardHandler.CF_UNICODETEXT, remoteData!!)
        assertFalse("Echo loop was not suppressed for 1MB payload", echoHandled)
    }

    @Test
    fun testClipboardHandler_UnicodeSurrogatePairsAndComplexCharacters() {
        val complexTexts = listOf(
            "Family: 👨‍👩‍👧‍👦 Emoji test with ZWJ",
            "CJK Ext B: 𠜎𠜱𠝹𠱓 rare characters",
            "SMP Musical symbols: 𝄞𝄢𝄡 and Mahjong 🀀🀁🀂🀃",
            "Mathematical Alphanumeric: 𝕳𝖊𝖑𝖑𝖔 𝖂𝖔𝖗𝖑𝖉",
            "Arabic: مرحبا بالعالم - Hebrew: שלום עולם",
            "Combining diacritics: e\u0301 a\u0300 u\u0308",
            "Mixed whitespace: \t\r\n   tabs and newlines \r\n"
        )

        for (text in complexTexts) {
            // Encode
            val encoded = ClipboardHandler.encodeUnicodeText(text)
            // Verify null termination
            assertTrue(encoded.size >= 2)
            assertEquals(0.toByte(), encoded[encoded.size - 1])
            assertEquals(0.toByte(), encoded[encoded.size - 2])

            // Decode
            val decoded = ClipboardHandler.decodeUnicodeText(encoded)
            assertEquals("Roundtrip mismatch for: $text", text, decoded)

            // Echo suppression check
            var localNotified: String? = null
            var remoteNotified: ByteArray? = null
            val handler = ClipboardHandler(
                onSendRemoteClipboard = { _, d -> remoteNotified = d },
                onLocalClipboardUpdate = { t -> localNotified = t }
            )

            // Remote -> Local
            val r1 = handler.onRemoteClipboardReceived(ClipboardHandler.CF_UNICODETEXT, encoded)
            assertTrue("Remote clipboard failed for: $text", r1)
            assertEquals(text, localNotified)

            // Local echo suppression
            val r2 = handler.onLocalClipboardChanged(text)
            assertFalse("Echo suppression failed for: $text", r2)
            assertNull(remoteNotified)
        }
    }

    @Test
    fun testClipboardHandler_BidirectionalEchoLoops1000Cycles() {
        var remoteSendCount = 0
        var localUpdateCount = 0

        val handler = ClipboardHandler(
            onSendRemoteClipboard = { _, _ -> remoteSendCount++ },
            onLocalClipboardUpdate = { localUpdateCount++ }
        )

        val cycles = 1000
        for (i in 0 until cycles) {
            val userText = "User copy text iteration $i"
            val serverText = "Server copy text iteration $i"

            // 1. User copies on Android
            val userSent = handler.onLocalClipboardChanged(userText)
            assertTrue("User copy failed at iteration $i", userSent)
            assertEquals(i + 1, remoteSendCount)

            // 2. Server echoes back the identical text (simulated echo)
            val serverEchoBytes = ClipboardHandler.encodeUnicodeText(userText)
            val serverEchoHandled = handler.onRemoteClipboardReceived(ClipboardHandler.CF_UNICODETEXT, serverEchoBytes)
            assertFalse("Echo loop leak on server echo at iteration $i", serverEchoHandled)
            assertEquals(i, localUpdateCount) // localUpdateCount must NOT have incremented

            // 3. Server sends new text from remote desktop
            val serverBytes = ClipboardHandler.encodeUnicodeText(serverText)
            val serverSent = handler.onRemoteClipboardReceived(ClipboardHandler.CF_UNICODETEXT, serverBytes)
            assertTrue("Server send failed at iteration $i", serverSent)
            assertEquals(i + 1, localUpdateCount)

            // 4. Android system echoes back identical text (simulated echo)
            val androidEchoHandled = handler.onLocalClipboardChanged(serverText)
            assertFalse("Echo loop leak on Android echo at iteration $i", androidEchoHandled)
            assertEquals(i + 1, remoteSendCount) // remoteSendCount must NOT have incremented
        }

        // Final verification: exactly 1000 remote sends and 1000 local updates across 1000 cycles
        assertEquals(cycles, remoteSendCount)
        assertEquals(cycles, localUpdateCount)
    }
}
