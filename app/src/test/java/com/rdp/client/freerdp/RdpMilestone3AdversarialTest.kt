package com.rdp.client.freerdp

import com.rdp.client.model.AudioMode
import com.rdp.client.model.ColorDepth
import com.rdp.client.model.ResolutionMode
import com.rdp.client.model.SecurityType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Adversarial stress test suite for Milestone 3:
 * 1. RdpSessionStateMachine invalid state transition storms and concurrency races.
 * 2. MockRdpNativeBridge rapid connect/disconnect cycling (churn) & lifecycle leakage.
 * 3. RdpConnectionParameters extreme values (empty, IPv6, boundaries, masking).
 * 4. RdpPointerFlags bitmask fuzzing, multi-button composition, wheel deltas.
 */
class RdpMilestone3AdversarialTest {

    private lateinit var mockBridge: MockRdpNativeBridge

    @Before
    fun setUp() {
        mockBridge = MockRdpNativeBridge(autoConnectSuccess = true, connectionDelayMs = 5L)
        LibFreeRDP.setNativeBridgeForTesting(mockBridge)
    }

    @After
    fun tearDown() {
        LibFreeRDP.resetNativeBridge()
    }

    // =========================================================================
    // SECTION 1: RdpSessionStateMachine Adversarial & Storm Testing
    // =========================================================================

    @Test
    fun testExhaustiveInvalidStateTransitionMatrix() {
        val states = RdpSessionState.values()

        for (fromState in states) {
            for (toState in states) {
                val sm = RdpSessionStateMachine(fromState)
                val isAllowed = fromState.canTransitionTo(toState)

                if (!isAllowed) {
                    val result = sm.transitionTo(toState)
                    assertFalse(
                        "Transition from $fromState to $toState should be illegal",
                        result.isSuccess
                    )
                    assertTrue(
                        "Failure must contain IllegalStateException",
                        result.exceptionOrNull() is IllegalStateException
                    )
                    assertEquals(
                        "State must remain unchanged after illegal transition",
                        fromState,
                        sm.currentState
                    )
                    assertEquals(
                        "StateFlow value must remain unchanged after illegal transition",
                        fromState,
                        sm.stateFlow.value
                    )
                } else {
                    val result = sm.transitionTo(toState)
                    assertTrue(
                        "Transition from $fromState to $toState should be legal",
                        result.isSuccess
                    )
                    assertEquals(toState, sm.currentState)
                    assertEquals(toState, sm.stateFlow.value)
                }
            }
        }
    }

    @Test
    fun testConcurrentStateTransitionStorm() = runBlocking {
        val sm = RdpSessionStateMachine(RdpSessionState.DISCONNECTED)
        val threadCount = 16
        val iterationsPerThread = 250
        val dispatcher = Executors.newFixedThreadPool(threadCount).asCoroutineDispatcher()

        val illegalAttemptCount = AtomicInteger(0)
        val legalAttemptCount = AtomicInteger(0)
        val allStates = RdpSessionState.values()

        coroutineScope {
            val jobs = (1..threadCount).map { threadIdx ->
                async(dispatcher) {
                    for (i in 0 until iterationsPerThread) {
                        // Pick random target state
                        val target = allStates[(threadIdx * 31 + i) % allStates.size]
                        val res = sm.transitionTo(target)
                        if (res.isSuccess) {
                            legalAttemptCount.incrementAndGet()
                        } else {
                            illegalAttemptCount.incrementAndGet()
                        }
                    }
                }
            }
            jobs.awaitAll()
        }

        // Quiescence assertion: state machine state must be valid
        assertNotNull(sm.currentState)
        assertTrue(sm.currentState in allStates)
        assertEquals(
            "StateRef and StateFlow must be in sync at quiescence",
            sm.currentState,
            sm.stateFlow.value
        )
        assertTrue("Illegal transitions were rejected", illegalAttemptCount.get() > 0)
        assertTrue("Legal transitions occurred", legalAttemptCount.get() > 0)
    }

    @Test
    fun testConcurrentConnectContentionRace() = runBlocking {
        val sm = RdpSessionStateMachine(RdpSessionState.DISCONNECTED)
        val racers = 20
        val dispatcher = Executors.newFixedThreadPool(racers).asCoroutineDispatcher()
        val successCount = AtomicInteger(0)

        coroutineScope {
            val jobs = (1..racers).map {
                async(dispatcher) {
                    val res = sm.transitionTo(RdpSessionState.CONNECTING)
                    if (res.isSuccess) {
                        successCount.incrementAndGet()
                    }
                }
            }
            jobs.awaitAll()
        }

        assertEquals(RdpSessionState.CONNECTING, sm.currentState)
        assertEquals(RdpSessionState.CONNECTING, sm.stateFlow.value)
        // Since self-transition is legal, all racers succeed either as first transition or idempotent self-transition
        assertEquals(racers, successCount.get())
    }

    @Test
    fun testForceStateOverridesDuringActiveState() {
        val sm = RdpSessionStateMachine(RdpSessionState.CONNECTED)
        assertEquals(RdpSessionState.CONNECTED, sm.currentState)

        // Force to DISCONNECTED (ordinarily illegal directly from CONNECTED)
        assertFalse(RdpSessionState.CONNECTED.canTransitionTo(RdpSessionState.DISCONNECTED))
        sm.forceState(RdpSessionState.DISCONNECTED)

        assertEquals(RdpSessionState.DISCONNECTED, sm.currentState)
        assertEquals(RdpSessionState.DISCONNECTED, sm.stateFlow.value)
    }

    // =========================================================================
    // SECTION 2: MockRdpNativeBridge Rapid Churn & Lifecycle Leakage
    // =========================================================================

    @Test
    fun testRapidSequentialConnectDisconnectChurn() {
        val cycles = 60
        for (i in 0 until cycles) {
            val instance = LibFreeRDP.newInstance(null)
            assertTrue(instance > 0)

            val latch = CountDownLatch(1)
            val listener = object : RdpSessionListener {
                override fun onConnectionSuccess(instance: Long) {
                    latch.countDown()
                }
                override fun onConnectionFailure(instance: Long, errorCode: Int, message: String) {}
                override fun onDisconnected(instance: Long) {}
                override fun onGraphicsUpdate(instance: Long, x: Int, y: Int, width: Int, height: Int) {}
            }

            LibFreeRDP.registerSessionListener(instance, listener)
            assertEquals(listener, LibFreeRDP.getSessionListener(instance))

            val connected = LibFreeRDP.connect(instance)
            assertTrue("Connect should succeed on cycle $i", connected)
            assertTrue("Callback should fire on cycle $i", latch.await(1, TimeUnit.SECONDS))

            // Disconnect and free
            assertTrue(LibFreeRDP.disconnect(instance))
            LibFreeRDP.freeInstance(instance)

            // Crucial: ensure no dangling listener handle remains
            assertNull("Listener must be removed after freeInstance", LibFreeRDP.getSessionListener(instance))
        }
    }

    @Test
    fun testConcurrentMockNativeBridgeLifecycleChurn() = runBlocking {
        val threads = 12
        val opsPerThread = 50
        val dispatcher = Executors.newFixedThreadPool(threads).asCoroutineDispatcher()
        val allAllocatedIds = ConcurrentHashMap.newKeySet<Long>()
        val duplicates = AtomicInteger(0)

        coroutineScope {
            val jobs = (1..threads).map {
                async(dispatcher) {
                    for (i in 0 until opsPerThread) {
                        val id = mockBridge.newInstance(null)
                        if (!allAllocatedIds.add(id)) {
                            duplicates.incrementAndGet()
                        }
                        mockBridge.connect(id)
                        mockBridge.disconnect(id)
                        mockBridge.freeInstance(id)
                    }
                }
            }
            jobs.awaitAll()
        }

        assertEquals("No duplicate instance IDs should be allocated even under concurrent storms", 0, duplicates.get())
    }

    @Test
    fun testLateAsyncCallbackRaceDoesNotCrashOrLeak() {
        // Mock with higher delay
        val slowBridge = MockRdpNativeBridge(autoConnectSuccess = true, connectionDelayMs = 50L)
        LibFreeRDP.setNativeBridgeForTesting(slowBridge)

        val instance = LibFreeRDP.newInstance(null)
        val callbackFiredAfterFree = AtomicBoolean(false)

        val listener = object : RdpSessionListener {
            override fun onConnectionSuccess(instance: Long) {
                callbackFiredAfterFree.set(true)
            }
            override fun onConnectionFailure(instance: Long, errorCode: Int, message: String) {}
            override fun onDisconnected(instance: Long) {}
            override fun onGraphicsUpdate(instance: Long, x: Int, y: Int, width: Int, height: Int) {}
        }

        LibFreeRDP.registerSessionListener(instance, listener)
        LibFreeRDP.connect(instance)

        // Prematurely free before delay expires (after 5ms < 50ms)
        Thread.sleep(5)
        LibFreeRDP.freeInstance(instance)
        assertNull(LibFreeRDP.getSessionListener(instance))

        // Wait for the background thread in MockRdpNativeBridge to finish sleeping (100ms)
        Thread.sleep(100)

        // The listener was unregistered, so callback should NOT have fired on our listener
        assertFalse("Late callback must not hit unregistered listener", callbackFiredAfterFree.get())
    }

    @Test
    fun testUnregisteredInstanceOperationsGracefulHandling() {
        val nonExistentInstance = 99999999L
        // Operations on non-existent instance must not crash
        assertNull(LibFreeRDP.getSessionListener(nonExistentInstance))
        assertFalse(mockBridge.connect(nonExistentInstance))
        assertTrue(mockBridge.disconnect(nonExistentInstance))
        assertTrue(mockBridge.sendCursorEvent(nonExistentInstance, 0, 0, 0))
        assertTrue(mockBridge.sendKeyEvent(nonExistentInstance, 0x1E, false, true))
        assertTrue(mockBridge.sendUnicodeKeyEvent(nonExistentInstance, 'A'.code))
        LibFreeRDP.freeInstance(nonExistentInstance)
    }

    // =========================================================================
    // SECTION 3: RdpConnectionParameters Extreme Values & Edge Cases
    // =========================================================================

    @Test
    fun testEmptyAndWhitespaceHostValidation() {
        val emptyHost = RdpConnectionParameters(host = "")
        val resEmpty = emptyHost.validate()
        assertFalse(resEmpty.isSuccess)
        assertTrue((resEmpty as ConnectionValidationResult.Error).message.contains("Host address"))

        val spaceHost = RdpConnectionParameters(host = "   \t  \n ")
        val resSpace = spaceHost.validate()
        assertFalse(resSpace.isSuccess)
        assertTrue((resSpace as ConnectionValidationResult.Error).message.contains("Host address"))
    }

    @Test
    fun testIPv6AddressesParsingAndArguments() {
        val ipv6Hosts = listOf(
            "::1",
            "[::1]",
            "2001:0db8:85a3:0000:0000:8a2e:0370:7334",
            "[2001:db8::1]",
            "fe80::1ff:fe23:4567:890a"
        )

        for (ipv6 in ipv6Hosts) {
            val params = RdpConnectionParameters(host = ipv6, port = 3389)
            val validation = params.validate()
            assertTrue("IPv6 host '$ipv6' should be valid", validation.isSuccess)

            val args = params.toNativeArgs()
            assertTrue(
                "CLI arguments must contain target /v argument with IPv6 host",
                args.any { it.startsWith("/v:") && it.contains(ipv6) }
            )
        }
    }

    @Test
    fun testPortBoundaryValues() {
        // Valid boundary ports
        assertTrue(RdpConnectionParameters(host = "10.0.0.1", port = 1).validate().isSuccess)
        assertTrue(RdpConnectionParameters(host = "10.0.0.1", port = 3389).validate().isSuccess)
        assertTrue(RdpConnectionParameters(host = "10.0.0.1", port = 65535).validate().isSuccess)

        // Invalid boundary ports
        assertFalse(RdpConnectionParameters(host = "10.0.0.1", port = 0).validate().isSuccess)
        assertFalse(RdpConnectionParameters(host = "10.0.0.1", port = -1).validate().isSuccess)
        assertFalse(RdpConnectionParameters(host = "10.0.0.1", port = 65536).validate().isSuccess)
        assertFalse(RdpConnectionParameters(host = "10.0.0.1", port = Int.MAX_VALUE).validate().isSuccess)
        assertFalse(RdpConnectionParameters(host = "10.0.0.1", port = Int.MIN_VALUE).validate().isSuccess)
    }

    @Test
    fun testBlankAndExtremePasswordsHandling() {
        // 1. Blank password: omitted from args
        val blankParams = RdpConnectionParameters(host = "10.0.0.1", password = "")
        val blankArgs = blankParams.toNativeArgs()
        assertFalse("Blank password should not generate /p argument", blankArgs.any { it.startsWith("/p:") })
        assertEquals("(none)", blankParams.toSafeString().let { str ->
            val match = Regex("pass='([^']+)'").find(str)
            match?.groupValues?.get(1)
        })

        // 2. Whitespace-only password
        val whitespaceParams = RdpConnectionParameters(host = "10.0.0.1", password = "   ")
        val whitespaceArgs = whitespaceParams.toNativeArgs()
        // Note: isNotBlank() treats whitespace as blank, omitting /p:
        assertFalse("Whitespace password is treated as blank", whitespaceArgs.any { it.startsWith("/p:") })

        // 3. Password with complex special characters & unicode
        val complexPass = "P@\$\$w0rd!#%^&*()_+-=[]{}|;':\",.<>/?~`\u00A9\u00AE"
        val complexParams = RdpConnectionParameters(host = "10.0.0.1", password = complexPass)
        val complexArgs = complexParams.toNativeArgs()
        assertTrue("Complex password should generate /p argument", complexArgs.contains("/p:$complexPass"))

        // Credential masking in toSafeString
        val safeStr = complexParams.toSafeString()
        assertFalse("Raw password must NEVER appear in safe log string", safeStr.contains(complexPass))
        assertTrue("Password must be masked with asterisks", safeStr.contains("pass='******'"))
    }

    @Test
    fun testGatewayExtremesAndLongNames() {
        // Gateway enabled with empty gateway host
        val invalidGw = RdpConnectionParameters(
            host = "10.0.0.1",
            enableGateway = true,
            gatewayHost = ""
        )
        assertFalse(invalidGw.validate().isSuccess)

        // Gateway enabled with invalid port
        val invalidGwPort = RdpConnectionParameters(
            host = "10.0.0.1",
            enableGateway = true,
            gatewayHost = "gw.example.com",
            gatewayPort = 70000
        )
        assertFalse(invalidGwPort.validate().isSuccess)

        // Very long gateway host name (e.g. 500 characters) exceeding 255 buffer limit
        val oversizedGwHost = "subdomain." + "a".repeat(480) + ".example.com"
        val oversizedGwParams = RdpConnectionParameters(
            host = "10.0.0.1",
            enableGateway = true,
            gatewayHost = oversizedGwHost,
            gatewayPort = 443,
            gatewayPassword = "gwPassword123"
        )
        assertFalse(oversizedGwParams.validate().isSuccess)

        // Valid long gateway host name within limit (e.g. 200 characters)
        val validLongGwHost = "subdomain." + "a".repeat(180) + ".example.com"
        val validLongGwParams = RdpConnectionParameters(
            host = "10.0.0.1",
            enableGateway = true,
            gatewayHost = validLongGwHost,
            gatewayPort = 443,
            gatewayPassword = "gwPassword123"
        )
        assertTrue(validLongGwParams.validate().isSuccess)
        val args = validLongGwParams.toNativeArgs()
        assertTrue(args.contains("/g:$validLongGwHost:443"))

        // Ensure gateway password masked
        val safe = validLongGwParams.toSafeString()
        assertFalse(safe.contains("gwPassword123"))
        assertTrue(safe.contains("gwPass='******'"))
    }

    @Test
    fun testResolutionBoundaries() {
        // Non-positive resolution
        assertFalse(RdpConnectionParameters(host = "10.0.0.1", width = 0, height = 1080).validate().isSuccess)
        assertFalse(RdpConnectionParameters(host = "10.0.0.1", width = 1920, height = -1).validate().isSuccess)
        assertFalse(RdpConnectionParameters(host = "10.0.0.1", width = -1920, height = -1080).validate().isSuccess)

        // Valid extreme resolution
        val ultra4k = RdpConnectionParameters(host = "10.0.0.1", width = 7680, height = 4320)
        assertTrue(ultra4k.validate().isSuccess)
        assertTrue(ultra4k.toNativeArgs().contains("/size:7680x4320"))
    }

    // =========================================================================
    // SECTION 4: RdpPointerFlags Bitmask Fuzzing & Concurrency
    // =========================================================================

    @Test
    fun testPointerFlagsBitmaskOrthogonality() {
        val move = RdpPointerFlags.PTRFLAGS_MOVE
        val down = RdpPointerFlags.PTRFLAGS_DOWN
        val b1 = RdpPointerFlags.PTRFLAGS_BUTTON1
        val b2 = RdpPointerFlags.PTRFLAGS_BUTTON2
        val b3 = RdpPointerFlags.PTRFLAGS_BUTTON3
        val wheel = RdpPointerFlags.PTRFLAGS_WHEEL
        val wheelNeg = RdpPointerFlags.PTRFLAGS_WHEEL_NEGATIVE
        val hwheel = RdpPointerFlags.PTRFLAGS_HWHEEL

        // Button bits must be pairwise disjoint
        assertEquals(0, b1 and b2)
        assertEquals(0, b1 and b3)
        assertEquals(0, b2 and b3)

        // Button bits must be disjoint from DOWN and MOVE
        assertEquals(0, b1 and down)
        assertEquals(0, b2 and down)
        assertEquals(0, b3 and down)
        assertEquals(0, b1 and move)
        assertEquals(0, b2 and move)
        assertEquals(0, b3 and move)

        // MOVE and DOWN must be disjoint
        assertEquals(0, move and down)

        // Wheel flags disjointness from button flags
        assertEquals(0, wheel and b1)
        assertEquals(0, wheel and down)
        assertEquals(0, hwheel and b1)
        assertEquals(0, wheelNeg and b1)
    }

    @Test
    fun testMultiButtonCombinationsAndReleases() {
        val leftDown = RdpPointerFlags.encodeButtonDown(RdpPointerFlags.Button.LEFT)
        val rightDown = RdpPointerFlags.encodeButtonDown(RdpPointerFlags.Button.RIGHT)
        val middleDown = RdpPointerFlags.encodeButtonDown(RdpPointerFlags.Button.MIDDLE)

        // Chorded press: Left + Right buttons held down
        val chordedLR = leftDown or rightDown
        assertEquals(0xB000, chordedLR)
        assertTrue("Must have DOWN bit", (chordedLR and RdpPointerFlags.PTRFLAGS_DOWN) != 0)
        assertTrue("Must have BUTTON1 bit", (chordedLR and RdpPointerFlags.PTRFLAGS_BUTTON1) != 0)
        assertTrue("Must have BUTTON2 bit", (chordedLR and RdpPointerFlags.PTRFLAGS_BUTTON2) != 0)
        assertEquals("Must NOT have BUTTON3 bit", 0, chordedLR and RdpPointerFlags.PTRFLAGS_BUTTON3)

        // Triple chord: Left + Right + Middle down
        val chordedAll = leftDown or rightDown or middleDown
        assertEquals(0xF000, chordedAll)

        // Release buttons (no DOWN bit)
        val leftUp = RdpPointerFlags.encodeButtonUp(RdpPointerFlags.Button.LEFT)
        val rightUp = RdpPointerFlags.encodeButtonUp(RdpPointerFlags.Button.RIGHT)
        assertEquals(0x1000, leftUp)
        assertEquals(0x2000, rightUp)
        assertEquals("Release must not contain DOWN bit", 0, leftUp and RdpPointerFlags.PTRFLAGS_DOWN)
        assertEquals("Release must not contain DOWN bit", 0, rightUp and RdpPointerFlags.PTRFLAGS_DOWN)
    }

    @Test
    fun testDragMoveBitmaskComposition() {
        val dragLeft = RdpPointerFlags.encodeDragMove(RdpPointerFlags.Button.LEFT)
        val dragRight = RdpPointerFlags.encodeDragMove(RdpPointerFlags.Button.RIGHT)
        val dragMiddle = RdpPointerFlags.encodeDragMove(RdpPointerFlags.Button.MIDDLE)

        // Drag left = MOVE (0x0800) | DOWN (0x8000) | BUTTON1 (0x1000) = 0x9800
        assertEquals(0x9800, dragLeft)
        assertEquals(0xA800, dragRight)
        assertEquals(0xC800, dragMiddle)

        // Combined drag move
        val multiDrag = dragLeft or dragRight
        assertEquals(0xB800, multiDrag)
        assertTrue((multiDrag and RdpPointerFlags.PTRFLAGS_MOVE) != 0)
        assertTrue((multiDrag and RdpPointerFlags.PTRFLAGS_DOWN) != 0)
        assertTrue((multiDrag and RdpPointerFlags.PTRFLAGS_BUTTON1) != 0)
        assertTrue((multiDrag and RdpPointerFlags.PTRFLAGS_BUTTON2) != 0)
    }

    @Test
    fun testWheelScrollFuzzingAndDeltaBoundaries() {
        // Vertical UP / DOWN notches
        val vUp1 = RdpPointerFlags.encodeVerticalScroll(RdpPointerFlags.ScrollDirection.UP, 1)
        val vDown1 = RdpPointerFlags.encodeVerticalScroll(RdpPointerFlags.ScrollDirection.DOWN, 1)
        assertEquals(0x0278, vUp1)
        assertEquals(0x0388, vDown1)

        // Multiple notches: 2 notches = 240 delta
        val vUp2 = RdpPointerFlags.encodeVerticalScroll(RdpPointerFlags.ScrollDirection.UP, 2)
        assertEquals(0x02F0, vUp2) // 0x0200 | 240

        // Zero notches: delta = 0
        val vZero = RdpPointerFlags.encodeVerticalScroll(RdpPointerFlags.ScrollDirection.UP, 0)
        assertEquals(RdpPointerFlags.PTRFLAGS_WHEEL, vZero)

        // Horizontal RIGHT / LEFT notches
        val hRight1 = RdpPointerFlags.encodeHorizontalScroll(RdpPointerFlags.ScrollDirection.RIGHT, 1)
        val hLeft1 = RdpPointerFlags.encodeHorizontalScroll(RdpPointerFlags.ScrollDirection.LEFT, 1)
        assertEquals(0x0478, hRight1)
        assertEquals(0x0588, hLeft1)

        // High notches boundary: 3 notches clamped to max 2 notches (240 delta) to prevent wrap-around
        val vUp3 = RdpPointerFlags.encodeVerticalScroll(RdpPointerFlags.ScrollDirection.UP, 3)
        assertEquals(0x02F0, vUp3)
    }

    @Test
    fun testConcurrentPointerEventDispatch() = runBlocking {
        val instance = LibFreeRDP.newInstance(null)
        val threads = 10
        val eventsPerThread = 100
        val dispatcher = Executors.newFixedThreadPool(threads).asCoroutineDispatcher()

        coroutineScope {
            val jobs = (0 until threads).map { tIdx ->
                async(dispatcher) {
                    for (i in 0 until eventsPerThread) {
                        val flag = when (i % 4) {
                            0 -> RdpPointerFlags.encodeMove()
                            1 -> RdpPointerFlags.encodeDragMove(RdpPointerFlags.Button.LEFT)
                            2 -> RdpPointerFlags.encodeVerticalScroll(RdpPointerFlags.ScrollDirection.UP, 1)
                            else -> RdpPointerFlags.encodeButtonUp(RdpPointerFlags.Button.LEFT)
                        }
                        val res = LibFreeRDP.sendCursorEvent(instance, tIdx * 10 + i, tIdx * 10 + i, flag)
                        assertTrue(res)
                    }
                }
            }
            jobs.awaitAll()
        }

        LibFreeRDP.freeInstance(instance)
    }
}
