package com.freerdp.feature.session.keyboard

import com.freerdp.core.engine.MockRdpEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KeyboardTimingManagerTest {

    private lateinit var mockEngine: MockRdpEngine

    @Before
    fun setUp() {
        mockEngine = MockRdpEngine()
    }

    @Test
    fun testSendKeyPressWithHoldExecutesHoldDurationAndEmitsDownAndUp() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)
        val timingManager = DefaultKeyboardTimingManager(
            rdpEngine = mockEngine,
            coroutineScope = testScope,
            keyHoldDurationMs = 50L
        )

        // 1. Send Enter key with 50ms hold
        timingManager.sendKeyPressWithHold(ScancodeTranslator.VK_RETURN, isExtended = false, holdDurationMs = 50L)

        // Before any time passes, actor starts and dispatches KeyDown
        testScope.runCurrent()
        assertEquals(1, mockEngine.recordedKeyEvents.size)
        assertEquals(ScancodeTranslator.VK_RETURN, mockEngine.recordedKeyEvents[0].keyCode)
        assertTrue(mockEngine.recordedKeyEvents[0].down)

        // Advance 49ms: Key should still be held down (BMC polling window active)
        testScope.advanceTimeBy(49L)
        testScope.runCurrent()
        assertEquals("Key must remain held down at 49ms", 1, mockEngine.recordedKeyEvents.size)

        // Advance 1ms (total 50ms): KeyUp must be dispatched
        testScope.advanceTimeBy(1L)
        testScope.runCurrent()
        assertEquals("KeyUp must be dispatched at 50ms hold completion", 2, mockEngine.recordedKeyEvents.size)
        assertEquals(ScancodeTranslator.VK_RETURN, mockEngine.recordedKeyEvents[1].keyCode)
        assertFalse("Second event must be key release", mockEngine.recordedKeyEvents[1].down)

        timingManager.shutdown()
    }

    @Test
    fun testSendKeyPressPreservesExtendedBit() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)
        val timingManager = DefaultKeyboardTimingManager(
            rdpEngine = mockEngine,
            coroutineScope = testScope
        )

        // Delete key (VK_DELETE = 0x2E, extended = true)
        timingManager.sendKeyPressWithHold(ScancodeTranslator.VK_DELETE, isExtended = true, holdDurationMs = 50L)

        testScope.advanceTimeBy(50L)
        testScope.runCurrent()

        assertEquals(2, mockEngine.recordedKeyEvents.size)
        val expectedExtendedCode = ScancodeTranslator.VK_DELETE or ScancodeTranslator.EXTENDED_KEY_FLAG
        assertEquals(expectedExtendedCode, mockEngine.recordedKeyEvents[0].keyCode)
        assertTrue(mockEngine.recordedKeyEvents[0].down)
        assertEquals(expectedExtendedCode, mockEngine.recordedKeyEvents[1].keyCode)
        assertFalse(mockEngine.recordedKeyEvents[1].down)

        timingManager.shutdown()
    }

    @Test
    fun testSendTextWithPacingAndHoldDuration() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)
        val timingManager = DefaultKeyboardTimingManager(
            rdpEngine = mockEngine,
            coroutineScope = testScope,
            keyHoldDurationMs = 50L,
            interKeyPacingMs = 25L
        )

        // Stream "AB"
        timingManager.sendTextWithPacing("AB", pacingDelayMs = 25L)

        // t = 0: 'A' down
        testScope.runCurrent()
        assertEquals(1, mockEngine.recordedUnicodeEvents.size)
        assertEquals('A', mockEngine.recordedUnicodeEvents[0].unicodeChar)
        assertTrue(mockEngine.recordedUnicodeEvents[0].down)

        // t = 50ms: 'A' up
        testScope.advanceTimeBy(50L)
        testScope.runCurrent()
        assertEquals(2, mockEngine.recordedUnicodeEvents.size)
        assertEquals('A', mockEngine.recordedUnicodeEvents[1].unicodeChar)
        assertFalse(mockEngine.recordedUnicodeEvents[1].down)

        // t = 74ms (before 25ms pacing finishes): 'B' should NOT have been sent yet
        testScope.advanceTimeBy(24L)
        testScope.runCurrent()
        assertEquals(2, mockEngine.recordedUnicodeEvents.size)

        // t = 75ms (25ms pacing completed): 'B' down
        testScope.advanceTimeBy(1L)
        testScope.runCurrent()
        assertEquals(3, mockEngine.recordedUnicodeEvents.size)
        assertEquals('B', mockEngine.recordedUnicodeEvents[2].unicodeChar)
        assertTrue(mockEngine.recordedUnicodeEvents[2].down)

        // t = 125ms (50ms hold for 'B'): 'B' up
        testScope.advanceTimeBy(50L)
        testScope.runCurrent()
        assertEquals(4, mockEngine.recordedUnicodeEvents.size)
        assertEquals('B', mockEngine.recordedUnicodeEvents[3].unicodeChar)
        assertFalse(mockEngine.recordedUnicodeEvents[3].down)

        timingManager.shutdown()
    }

    @Test
    fun testTextSanitizationConvertsCrlfToSpaces() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)
        val timingManager = DefaultKeyboardTimingManager(
            rdpEngine = mockEngine,
            coroutineScope = testScope,
            keyHoldDurationMs = 50L,
            interKeyPacingMs = 25L
        )

        // CRLF text: "A\r\nB\nC\rD" -> should replace newlines with spaces: "A B C D"
        timingManager.sendTextWithPacing("A\r\nB\nC\rD", pacingDelayMs = 25L)

        // Total chars: 7 ("A B C D")
        // Time needed: 7 * 50ms (holds) + 6 * 25ms (pacing) = 350 + 150 = 500ms
        testScope.advanceTimeBy(600L)
        testScope.runCurrent()

        assertEquals(14, mockEngine.recordedUnicodeEvents.size) // 7 pairs of down/up
        val downChars = mockEngine.recordedUnicodeEvents.filter { it.down }.map { it.unicodeChar }
        assertEquals(listOf('A', ' ', 'B', ' ', 'C', ' ', 'D'), downChars)

        timingManager.shutdown()
    }

    @Test
    fun testConvenienceBmcHelpers() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)
        val timingManager = DefaultKeyboardTimingManager(
            rdpEngine = mockEngine,
            coroutineScope = testScope,
            keyHoldDurationMs = 50L
        )

        timingManager.sendEnterKey(holdDurationMs = 50L)
        testScope.advanceTimeBy(50L)
        testScope.runCurrent()
        assertEquals(2, mockEngine.recordedKeyEvents.size)
        assertEquals(ScancodeTranslator.VK_RETURN, mockEngine.recordedKeyEvents[0].keyCode)

        timingManager.sendBackspaceKey(holdDurationMs = 50L)
        testScope.advanceTimeBy(50L)
        testScope.runCurrent()
        assertEquals(4, mockEngine.recordedKeyEvents.size)
        assertEquals(ScancodeTranslator.VK_BACK, mockEngine.recordedKeyEvents[2].keyCode)

        timingManager.sendSpaceKey(holdDurationMs = 50L)
        testScope.advanceTimeBy(50L)
        testScope.runCurrent()
        assertEquals(6, mockEngine.recordedKeyEvents.size)
        assertEquals(ScancodeTranslator.VK_SPACE, mockEngine.recordedKeyEvents[4].keyCode)

        timingManager.sendTabKey(holdDurationMs = 50L)
        testScope.advanceTimeBy(50L)
        testScope.runCurrent()
        assertEquals(8, mockEngine.recordedKeyEvents.size)
        assertEquals(ScancodeTranslator.VK_TAB, mockEngine.recordedKeyEvents[6].keyCode)

        timingManager.shutdown()
    }

    @Test
    fun testReleaseAllModifiers() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)
        val timingManager = DefaultKeyboardTimingManager(
            rdpEngine = mockEngine,
            coroutineScope = testScope
        )

        timingManager.releaseAllModifiers()
        testScope.runCurrent()

        // 8 modifiers released: LShift, RShift, LCtrl, RCtrl (ext), LAlt, RAlt (ext), LWin (ext), RWin (ext)
        assertEquals(8, mockEngine.recordedKeyEvents.size)
        assertTrue(mockEngine.recordedKeyEvents.all { !it.down })

        val releasedCodes = mockEngine.recordedKeyEvents.map { it.keyCode }
        assertTrue(releasedCodes.contains(ScancodeTranslator.VK_LSHIFT))
        assertTrue(releasedCodes.contains(ScancodeTranslator.VK_RSHIFT))
        assertTrue(releasedCodes.contains(ScancodeTranslator.VK_LCONTROL))
        assertTrue(releasedCodes.contains(ScancodeTranslator.VK_RCONTROL or ScancodeTranslator.EXTENDED_KEY_FLAG))
        assertTrue(releasedCodes.contains(ScancodeTranslator.VK_LMENU))
        assertTrue(releasedCodes.contains(ScancodeTranslator.VK_RMENU or ScancodeTranslator.EXTENDED_KEY_FLAG))
        assertTrue(releasedCodes.contains(ScancodeTranslator.VK_LWIN or ScancodeTranslator.EXTENDED_KEY_FLAG))
        assertTrue(releasedCodes.contains(ScancodeTranslator.VK_RWIN or ScancodeTranslator.EXTENDED_KEY_FLAG))

        timingManager.shutdown()
    }

    @Test
    fun testFifoActorQueuePreservesOrdering() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)
        val timingManager = DefaultKeyboardTimingManager(
            rdpEngine = mockEngine,
            coroutineScope = testScope,
            keyHoldDurationMs = 50L
        )

        // Queue: Key1 (Enter) hold 50ms, Key2 (Tab) hold 50ms, Key3 (Space) hold 50ms
        timingManager.sendEnterKey(50L)
        timingManager.sendTabKey(50L)
        timingManager.sendSpaceKey(50L)

        // Advance 150ms total
        testScope.advanceTimeBy(150L)
        testScope.runCurrent()

        assertEquals(6, mockEngine.recordedKeyEvents.size)
        // Enter Down, Up
        assertEquals(ScancodeTranslator.VK_RETURN, mockEngine.recordedKeyEvents[0].keyCode)
        assertTrue(mockEngine.recordedKeyEvents[0].down)
        assertEquals(ScancodeTranslator.VK_RETURN, mockEngine.recordedKeyEvents[1].keyCode)
        assertFalse(mockEngine.recordedKeyEvents[1].down)

        // Tab Down, Up
        assertEquals(ScancodeTranslator.VK_TAB, mockEngine.recordedKeyEvents[2].keyCode)
        assertTrue(mockEngine.recordedKeyEvents[2].down)
        assertEquals(ScancodeTranslator.VK_TAB, mockEngine.recordedKeyEvents[3].keyCode)
        assertFalse(mockEngine.recordedKeyEvents[3].down)

        // Space Down, Up
        assertEquals(ScancodeTranslator.VK_SPACE, mockEngine.recordedKeyEvents[4].keyCode)
        assertTrue(mockEngine.recordedKeyEvents[4].down)
        assertEquals(ScancodeTranslator.VK_SPACE, mockEngine.recordedKeyEvents[5].keyCode)
        assertFalse(mockEngine.recordedKeyEvents[5].down)

        timingManager.shutdown()
    }
}
