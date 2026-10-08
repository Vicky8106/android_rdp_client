package com.rdp.client.freerdp

import com.rdp.client.utils.KeyPacer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KeyPacerTest {

    private val sentEvents = mutableListOf<Triple<Long, Int, Boolean>>()
    private val testInstance = 1234L

    @Before
    fun setUp() {
        sentEvents.clear()
        // Install mock native bridge to capture LibFreeRDP calls
        LibFreeRDP.setNativeBridgeForTesting(object : IRdpNativeBridge {
            override fun newInstance(context: android.content.Context?): Long = testInstance
            override fun freeInstance(instance: Long) {}
            override fun connect(instance: Long, params: RdpConnectionParameters?): Boolean = true
            override fun disconnect(instance: Long): Boolean = true
            override fun updateGraphics(instance: Long, bitmap: android.graphics.Bitmap, x: Int, y: Int, w: Int, h: Int): Boolean = true
            override fun sendCursorEvent(instance: Long, x: Int, y: Int, flags: Int): Boolean = true
            override fun sendKeyEvent(instance: Long, scancode: Int, extended: Boolean, down: Boolean): Boolean {
                sentEvents.add(Triple(instance, scancode, down))
                return true
            }
            override fun sendUnicodeKeyEvent(instance: Long, codePoint: Int): Boolean {
                sentEvents.add(Triple(instance, codePoint, true))
                return true
            }
            override fun getVersion(): String = "test"
            override fun getLastError(instance: Long): String? = null
        })
    }

    @Test
    fun testPacerTimingConstants() {
        assertEquals(18L, KeyPacer.KEYDOWN_DURATION_MS)
        assertEquals(22L, KeyPacer.INTER_KEY_PACING_MS)
    }

    @Test
    fun testPacedKeyDelivery() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val pacerScope = TestScope(testDispatcher)
        val pacer = KeyPacer(instanceProvider = { testInstance }, scope = pacerScope)

        val scancodeA = RdpScancode(0x1E, isExtended = false)
        pacer.enqueueKey(scancodeA)

        // Advance to trigger keydown (emitted at t=0)
        pacerScope.testScheduler.advanceTimeBy(1)
        assertEquals(1, sentEvents.size)
        assertEquals(testInstance, sentEvents[0].first)
        assertEquals(0x1E, sentEvents[0].second)
        assertTrue(sentEvents[0].third) // Down

        // Advance past keydown hold duration (18ms) -> key up emitted
        pacerScope.testScheduler.advanceTimeBy(KeyPacer.KEYDOWN_DURATION_MS)
        assertEquals(2, sentEvents.size)
        assertEquals(0x1E, sentEvents[1].second)
        assertFalse(sentEvents[1].third) // Up

        // Advance past inter-key pacing interval (22ms) -> queue cycle complete
        pacerScope.testScheduler.advanceTimeBy(KeyPacer.INTER_KEY_PACING_MS)
        assertEquals(2, sentEvents.size)
    }

    @Test
    fun testPacedTextQueue() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val pacerScope = TestScope(testDispatcher)
        val pacer = KeyPacer(instanceProvider = { testInstance }, scope = pacerScope)

        pacer.enqueueText("AB")

        // 2 characters: each has 18ms down + 22ms inter-key = 40ms per char
        pacerScope.testScheduler.advanceTimeBy(80)
        assertEquals(2, pacer.dispatchedCount)
    }

    @Test
    fun testCancelAndReleaseHeldKey() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val pacerScope = TestScope(testDispatcher)
        val pacer = KeyPacer(instanceProvider = { testInstance }, scope = pacerScope)

        val scancodeB = RdpScancode(0x30, isExtended = false)
        pacer.enqueueKey(scancodeB)

        // Advance partially into keydown (5ms into 18ms hold)
        pacerScope.testScheduler.advanceTimeBy(5)
        assertEquals(1, sentEvents.size)
        assertTrue(sentEvents[0].third) // Down

        // Abruptly cancel and flush
        pacer.cancelAndReleaseHeld()

        // Should have immediately released the held key
        assertEquals(2, sentEvents.size)
        assertEquals(0x30, sentEvents[1].second)
        assertFalse(sentEvents[1].third) // Released up
    }
}
