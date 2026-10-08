package com.rdp.client.freerdp

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class LibFreeRDPTest {

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

    @Test
    fun testNativeLoadStatusDiagnostics() {
        assertNotNull(LibFreeRDP.loadStatus)
        // In JVM test environment, loadStatus should be JVM_TEST_ENVIRONMENT or LOADED
        val status = LibFreeRDP.loadStatus
        assertTrue(
            status == NativeLoadStatus.JVM_TEST_ENVIRONMENT ||
            status == NativeLoadStatus.LOADED ||
            status == NativeLoadStatus.LINKAGE_ERROR
        )
    }

    @Test
    fun testMockNativeBridgeLifecycleAndCallbacks() {
        val instance = LibFreeRDP.newInstance(null)
        assertTrue(instance > 0)

        val latch = CountDownLatch(1)
        var successFired = false
        var graphicsFired = false

        val listener = object : RdpSessionListener {
            override fun onConnectionSuccess(instance: Long) {
                successFired = true
            }

            override fun onConnectionFailure(instance: Long, errorCode: Int, message: String) {}

            override fun onDisconnected(instance: Long) {}

            override fun onGraphicsUpdate(instance: Long, x: Int, y: Int, width: Int, height: Int) {
                graphicsFired = true
                latch.countDown()
            }
        }

        LibFreeRDP.registerSessionListener(instance, listener)
        assertEquals(listener, LibFreeRDP.getSessionListener(instance))

        val connectSuccess = LibFreeRDP.connect(instance)
        assertTrue(connectSuccess)

        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertTrue(successFired)
        assertTrue(graphicsFired)

        // Event dispatches
        assertTrue(LibFreeRDP.sendCursorEvent(instance, 100, 200, RdpPointerFlags.PTRFLAGS_MOVE))
        assertTrue(LibFreeRDP.sendKeyEvent(instance, 0x1E, extended = false, down = true))
        assertTrue(LibFreeRDP.sendUnicodeKeyEvent(instance, 'A'.code))

        // Disconnect and free
        assertTrue(LibFreeRDP.disconnect(instance))
        LibFreeRDP.freeInstance(instance)
        assertNull(LibFreeRDP.getSessionListener(instance))
    }

    @Test
    fun testRdpSessionControllerLifecycle() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(testDispatcher)

        val params = RdpConnectionParameters(host = "127.0.0.1", port = 3389)
        val session = RdpSession(context = null, parameters = params, externalScope = scope)

        assertEquals(RdpSessionState.DISCONNECTED, session.state.value)

        session.connect()
        scope.testScheduler.advanceUntilIdle()

        assertEquals(RdpSessionState.CONNECTING, session.state.value)
        assertTrue(session.instanceId > 0)

        // Fire connection success callback
        LibFreeRDP.onConnectionSuccess(session.instanceId)
        assertEquals(RdpSessionState.CONNECTED, session.state.value)

        // Test sending input events in connected state
        assertTrue(session.sendCursorEvent(50, 50, RdpPointerFlags.PTRFLAGS_MOVE))
        assertTrue(session.sendKeyEvent(0x1E, extended = false, down = true))
        assertTrue(session.sendUnicodeKeyEvent('B'.code))

        // Disconnect
        session.disconnect()
        scope.testScheduler.advanceUntilIdle()
        assertEquals(RdpSessionState.DISCONNECTED, session.state.value)
        assertEquals(0L, session.instanceId)
    }

    @Test
    fun testConvenienceInterfaceContractOverloads() {
        val params = RdpConnectionParameters(host = "10.0.0.1", port = 3389)
        val instance = LibFreeRDP.connect(params)
        assertTrue(instance > 0)

        // Down event with extended flag (0x0100)
        assertTrue(LibFreeRDP.sendKeyEvent(instance, 0x48, 0x0100))
        // Up event with extended and release flags (0x8100)
        assertTrue(LibFreeRDP.sendKeyEvent(instance, 0x48, 0x8100))

        assertTrue(LibFreeRDP.disconnect(instance))
        LibFreeRDP.freeInstance(instance)
    }
}
