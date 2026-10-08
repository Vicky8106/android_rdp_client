package com.rdp.client.freerdp

import android.view.KeyEvent
import com.rdp.client.utils.KeyPacer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Adversarial Stress and Edge Case Test Suite for Milestone 3 Keyboard Subsystem:
 * - ScancodeMapper: Android KeyEvents 0-300, 0xE0 extended flags, collision detection, unmapped keys
 * - Unicode Fastpath: High code points (> 0xFFFF), surrogate pairs, emoji, empty text, dead keys
 * - KeyPacer: Queue flooding (100+ events), calibrated 18ms/22ms timing, cancellation mid-stroke, dangling instance race
 * - ModifierState: Latching permutations (Ctrl+Alt+Del, Win+R, Alt+Tab), locking, guaranteed release
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KeyboardSubsystemAdversarialTest {

    private val capturedKeyEvents = mutableListOf<CapturedKeyEvent>()
    private val capturedUnicodeEvents = mutableListOf<CapturedUnicodeEvent>()
    private val testInstanceId = 8888L

    data class CapturedKeyEvent(
        val instance: Long,
        val scancode: Int,
        val extended: Boolean,
        val down: Boolean
    )

    data class CapturedUnicodeEvent(
        val instance: Long,
        val codePoint: Int
    )

    @Before
    fun setUp() {
        capturedKeyEvents.clear()
        capturedUnicodeEvents.clear()

        LibFreeRDP.setNativeBridgeForTesting(object : IRdpNativeBridge {
            override fun newInstance(context: android.content.Context?): Long = testInstanceId
            override fun freeInstance(instance: Long) {}
            override fun connect(instance: Long, params: RdpConnectionParameters?): Boolean = true
            override fun disconnect(instance: Long): Boolean = true
            override fun updateGraphics(
                instance: Long,
                bitmap: android.graphics.Bitmap,
                x: Int,
                y: Int,
                w: Int,
                h: Int
            ): Boolean = true
            override fun sendCursorEvent(instance: Long, x: Int, y: Int, flags: Int): Boolean = true
            override fun sendKeyEvent(
                instance: Long,
                scancode: Int,
                extended: Boolean,
                down: Boolean
            ): Boolean {
                capturedKeyEvents.add(CapturedKeyEvent(instance, scancode, extended, down))
                return true
            }
            override fun sendUnicodeKeyEvent(instance: Long, codePoint: Int): Boolean {
                capturedUnicodeEvents.add(CapturedUnicodeEvent(instance, codePoint))
                return true
            }
            override fun getVersion(): String = "adversarial-mock"
            override fun getLastError(instance: Long): String? = null
        })
    }

    // =========================================================================
    // 1. ScancodeMapper Adversarial Coverage (0 through 300 + Edge Keys)
    // =========================================================================

    @Test
    fun testAllAndroidKeyEvents0Through300ZeroCrashesAndBijectiveMapping() {
        val seenScancodes = mutableMapOf<Pair<Int, Boolean>, Int>()

        // Stress all Android keycodes from 0 through 300, plus edge negative and high bounds
        val testRange = (-10..320).toList()

        for (keyCode in testRange) {
            // Must NEVER crash or throw an unhandled exception
            val scancode = try {
                ScancodeMapper.toScancode(keyCode)
            } catch (t: Throwable) {
                fail("ScancodeMapper.toScancode threw exception for keyCode $keyCode: ${t.message}")
                null
            }

            if (scancode != null) {
                // Scancode value must be within valid PC AT 8042 single-byte scancode bounds (1..127)
                assertTrue(
                    "Scancode ${scancode.code} out of valid hardware bounds for keyCode $keyCode",
                    scancode.code in 0x01..0x7F
                )

                // Verify MS-RDPBCGR flag encoding
                val flagsDown = scancode.toRdpFlags(isDown = true)
                val flagsUp = scancode.toRdpFlags(isDown = false)

                if (scancode.isExtended) {
                    assertEquals(
                        "Extended key must have KBD_FLAGS_EXTENDED set",
                        RdpScancode.KBD_FLAGS_EXTENDED,
                        flagsDown and RdpScancode.KBD_FLAGS_EXTENDED
                    )
                    assertEquals(
                        "Extended release key must have both EXTENDED and RELEASE flags set",
                        RdpScancode.KBD_FLAGS_EXTENDED or RdpScancode.KBD_FLAGS_RELEASE,
                        flagsUp
                    )
                } else {
                    assertEquals(
                        "Non-extended down key must have 0x0000 flags",
                        RdpScancode.KBD_FLAGS_DOWN,
                        flagsDown
                    )
                    assertEquals(
                        "Non-extended up key must have KBD_FLAGS_RELEASE flag",
                        RdpScancode.KBD_FLAGS_RELEASE,
                        flagsUp
                    )
                }

                // Reverse lookup must successfully recover the original keycode
                val reverseKey = ScancodeMapper.toAndroidKeyCode(scancode.code, scancode.isExtended)
                assertNotNull("Reverse lookup must not be null for mapped scancode $scancode", reverseKey)
                assertEquals(
                    "Reverse lookup failed to return original keyCode for $scancode",
                    keyCode,
                    reverseKey
                )

                // Detect incorrect collisions: two distinct Android keycodes mapping to same (code, isExtended)
                val keyPair = Pair(scancode.code, scancode.isExtended)
                if (seenScancodes.containsKey(keyPair)) {
                    val priorKeyCode = seenScancodes[keyPair]
                    fail("Collision detected! KeyCodes $priorKeyCode and $keyCode both map to scancode $keyPair")
                }
                seenScancodes[keyPair] = keyCode
            } else {
                // For unmapped codes, isModifierKey must be false
                assertFalse(
                    "Unmapped keyCode $keyCode must not be reported as a modifier",
                    ScancodeMapper.isModifierKey(keyCode)
                )
            }
        }

        // Verify that a substantial set of standard keys were actually mapped (> 60 keys)
        assertTrue("Expected at least 60 mapped keys, found ${seenScancodes.size}", seenScancodes.size >= 60)
    }

    @Test
    fun testExtendedScancodeFlagsDualKeyDifferentiations() {
        // Differentiate arrow keys and navigation cluster (must be 0xE0 extended)
        val navKeys = listOf(
            KeyEvent.KEYCODE_DPAD_UP to 0x48,
            KeyEvent.KEYCODE_DPAD_DOWN to 0x50,
            KeyEvent.KEYCODE_DPAD_LEFT to 0x4B,
            KeyEvent.KEYCODE_DPAD_RIGHT to 0x4D,
            KeyEvent.KEYCODE_MOVE_HOME to 0x47,
            KeyEvent.KEYCODE_MOVE_END to 0x4F,
            KeyEvent.KEYCODE_PAGE_UP to 0x49,
            KeyEvent.KEYCODE_PAGE_DOWN to 0x51,
            KeyEvent.KEYCODE_INSERT to 0x52,
            KeyEvent.KEYCODE_FORWARD_DEL to 0x53
        )
        for ((keyCode, expectedCode) in navKeys) {
            val sc = ScancodeMapper.toScancode(keyCode)
            assertNotNull("Navigation key $keyCode must be mapped", sc)
            assertEquals("Code mismatch for $keyCode", expectedCode, sc!!.code)
            assertTrue("Navigation key $keyCode must have isExtended=true (0xE0)", sc.isExtended)
        }

        // Differentiate numeric keypad duplicate scancodes (must be isExtended=false)
        val numpadDuals = listOf(
            KeyEvent.KEYCODE_NUMPAD_0 to 0x52, // Collides with INSERT (0x52 extended)
            KeyEvent.KEYCODE_NUMPAD_DOT to 0x53, // Collides with FORWARD_DEL (0x53 extended)
            KeyEvent.KEYCODE_NUMPAD_7 to 0x47, // Collides with HOME (0x47 extended)
            KeyEvent.KEYCODE_NUMPAD_1 to 0x4F, // Collides with END (0x4F extended)
            KeyEvent.KEYCODE_NUMPAD_9 to 0x49, // Collides with PAGE_UP (0x49 extended)
            KeyEvent.KEYCODE_NUMPAD_3 to 0x51, // Collides with PAGE_DOWN (0x51 extended)
            KeyEvent.KEYCODE_NUMPAD_8 to 0x48, // Collides with DPAD_UP (0x48 extended)
            KeyEvent.KEYCODE_NUMPAD_2 to 0x50, // Collides with DPAD_DOWN (0x50 extended)
            KeyEvent.KEYCODE_NUMPAD_4 to 0x4B, // Collides with DPAD_LEFT (0x4B extended)
            KeyEvent.KEYCODE_NUMPAD_6 to 0x4D  // Collides with DPAD_RIGHT (0x4D extended)
        )
        for ((keyCode, expectedCode) in numpadDuals) {
            val sc = ScancodeMapper.toScancode(keyCode)
            assertNotNull("Numpad key $keyCode must be mapped", sc)
            assertEquals("Code mismatch for $keyCode", expectedCode, sc!!.code)
            assertFalse("Numpad key $keyCode must have isExtended=false", sc.isExtended)
        }

        // Enter (0x1C non-extended) vs Numpad Enter (0x1C extended)
        val mainEnter = ScancodeMapper.toScancode(KeyEvent.KEYCODE_ENTER)!!
        val numpadEnter = ScancodeMapper.toScancode(KeyEvent.KEYCODE_NUMPAD_ENTER)!!
        assertEquals(0x1C, mainEnter.code)
        assertFalse(mainEnter.isExtended)
        assertEquals(0x1C, numpadEnter.code)
        assertTrue(numpadEnter.isExtended)

        // Slash (0x35 non-extended) vs Numpad Divide (0x35 extended)
        val mainSlash = ScancodeMapper.toScancode(KeyEvent.KEYCODE_SLASH)!!
        val numpadDivide = ScancodeMapper.toScancode(KeyEvent.KEYCODE_NUMPAD_DIVIDE)!!
        assertEquals(0x35, mainSlash.code)
        assertFalse(mainSlash.isExtended)
        assertEquals(0x35, numpadDivide.code)
        assertTrue(numpadDivide.isExtended)

        // Windows / Super Keys (both must be extended)
        val winLeft = ScancodeMapper.toScancode(KeyEvent.KEYCODE_META_LEFT)!!
        val winRight = ScancodeMapper.toScancode(KeyEvent.KEYCODE_META_RIGHT)!!
        assertEquals(0x5B, winLeft.code)
        assertTrue(winLeft.isExtended)
        assertEquals(0x5C, winRight.code)
        assertTrue(winRight.isExtended)
    }

    // =========================================================================
    // 2. Unicode Fastpath Handling (High Code Points, Emojis, Surrogates, Dead Keys)
    // =========================================================================

    @Test
    fun testUnicodeFastpathHighCodePointsAndEmojiSurrogates() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val pacerScope = TestScope(testDispatcher)
        val pacer = KeyPacer(instanceProvider = { testInstanceId }, scope = pacerScope)

        // 1. Single supplementary code point: Grinning Face U+1F600
        val grinningCodePoint = 0x1F600
        assertTrue("0x1F600 must be supplementary", Character.isSupplementaryCodePoint(grinningCodePoint))
        val expectedHigh = Character.highSurrogate(grinningCodePoint).code // 0xD83D
        val expectedLow = Character.lowSurrogate(grinningCodePoint).code  // 0xDE00

        pacer.enqueueUnicode(grinningCodePoint)
        pacerScope.testScheduler.advanceTimeBy(KeyPacer.KEYDOWN_DURATION_MS + KeyPacer.INTER_KEY_PACING_MS)

        // Should have emitted both surrogate halves to FreeRDP Unicode channel
        assertEquals(2, capturedUnicodeEvents.size)
        assertEquals(expectedHigh, capturedUnicodeEvents[0].codePoint)
        assertEquals(expectedLow, capturedUnicodeEvents[1].codePoint)
        assertEquals(1, pacer.dispatchedCount)

        // 2. Maximum possible Unicode code point (0x10FFFF)
        val maxCodePoint = 0x10FFFF
        pacer.enqueueUnicode(maxCodePoint)
        pacerScope.testScheduler.advanceTimeBy(KeyPacer.KEYDOWN_DURATION_MS + KeyPacer.INTER_KEY_PACING_MS)
        assertEquals(4, capturedUnicodeEvents.size)
        assertEquals(Character.highSurrogate(maxCodePoint).code, capturedUnicodeEvents[2].codePoint)
        assertEquals(Character.lowSurrogate(maxCodePoint).code, capturedUnicodeEvents[3].codePoint)
        assertEquals(2, pacer.dispatchedCount)

        // 3. BMP code point (no surrogates): Cyrillic 'Ж' (U+0416)
        val cyrillicZh = 0x0416
        assertFalse(Character.isSupplementaryCodePoint(cyrillicZh))
        pacer.enqueueUnicode(cyrillicZh)
        pacerScope.testScheduler.advanceTimeBy(KeyPacer.KEYDOWN_DURATION_MS + KeyPacer.INTER_KEY_PACING_MS)
        assertEquals(5, capturedUnicodeEvents.size)
        assertEquals(cyrillicZh, capturedUnicodeEvents[4].codePoint)
        assertEquals(3, pacer.dispatchedCount)
    }

    @Test
    fun testUnicodeFastpathEmptyTextAndDeadKeys() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val pacerScope = TestScope(testDispatcher)
        val pacer = KeyPacer(instanceProvider = { testInstanceId }, scope = pacerScope)

        // 1. Empty string paste: must do nothing and not crash
        pacer.enqueueText("")
        pacerScope.testScheduler.advanceTimeBy(100)
        assertEquals(0, pacer.dispatchedCount)
        assertEquals(0, capturedUnicodeEvents.size)

        // 2. Combining dead keys sequence: 'e' + combining acute accent (U+0301)
        val combiningText = "e\u0301"
        pacer.enqueueText(combiningText)
        pacerScope.testScheduler.advanceTimeBy(2 * (KeyPacer.KEYDOWN_DURATION_MS + KeyPacer.INTER_KEY_PACING_MS))

        assertEquals(2, pacer.dispatchedCount)
        assertEquals(2, capturedUnicodeEvents.size)
        assertEquals('e'.code, capturedUnicodeEvents[0].codePoint)
        assertEquals(0x0301, capturedUnicodeEvents[1].codePoint)
    }

    // =========================================================================
    // 3. KeyPacer Queue Flooding (100+ Rapid Events, Timing, Bounds & Cancellation)
    // =========================================================================

    @Test
    fun testKeyPacerQueueFlooding120RapidEventsFIFOOrder() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val pacerScope = TestScope(testDispatcher)
        val pacer = KeyPacer(instanceProvider = { testInstanceId }, scope = pacerScope)

        val totalEvents = 120
        // Flood 120 events simultaneously into the pacer channel
        for (i in 0 until totalEvents) {
            val scancode = RdpScancode(code = 0x1E + (i % 20), isExtended = (i % 3 == 0))
            pacer.enqueueKey(scancode)
        }

        // Verify events are paced sequentially:
        // After 1 full cycle (40ms: 18ms hold + 22ms inter-key), exactly 1 event dispatched
        pacerScope.testScheduler.advanceTimeBy(KeyPacer.KEYDOWN_DURATION_MS + KeyPacer.INTER_KEY_PACING_MS)
        assertEquals(1, pacer.dispatchedCount)
        assertEquals(2, capturedKeyEvents.size) // 1 down + 1 up
        assertTrue(capturedKeyEvents[0].down)
        assertFalse(capturedKeyEvents[1].down)

        // Advance remainder of the 120 events: total 120 * 40ms = 4800ms
        pacerScope.testScheduler.advanceTimeBy(119 * (KeyPacer.KEYDOWN_DURATION_MS + KeyPacer.INTER_KEY_PACING_MS))
        assertEquals(totalEvents, pacer.dispatchedCount)
        assertEquals(totalEvents * 2, capturedKeyEvents.size)

        // Confirm all 120 events arrived strictly down-then-up in pairs
        for (i in 0 until totalEvents) {
            val downEv = capturedKeyEvents[i * 2]
            val upEv = capturedKeyEvents[i * 2 + 1]
            assertTrue("Event $i must be down", downEv.down)
            assertFalse("Event $i must be up", upEv.down)
            assertEquals("Scancode mismatch at index $i", downEv.scancode, upEv.scancode)
            assertEquals("Extended flag mismatch at index $i", downEv.extended, upEv.extended)
        }
    }

    @Test
    fun testKeyPacerCancellationBetweenEventsCleanFlush() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val pacerScope = TestScope(testDispatcher)
        val pacer = KeyPacer(instanceProvider = { testInstanceId }, scope = pacerScope)

        // Queue 100 events
        for (i in 0 until 100) {
            pacer.enqueueKey(RdpScancode(code = 0x20 + (i % 10), isExtended = false))
        }

        // Let 2 events complete cleanly (80ms: 2 * 40ms)
        pacerScope.testScheduler.advanceTimeBy(80)
        assertEquals(2, pacer.dispatchedCount)
        assertEquals(4, capturedKeyEvents.size) // 2 down + 2 up

        // Cancel between events (during inter-key pacing delay or before 3rd event starts)
        pacer.cancelAndReleaseHeld()

        // Advance virtual time substantially
        pacerScope.testScheduler.advanceTimeBy(10_000)

        // Exactly the 2 completed events should exist, all 98 remaining events dropped
        assertEquals(4, capturedKeyEvents.size)
        assertEquals(2, pacer.dispatchedCount)

        // Subsequent enqueue attempts must be dropped
        pacer.enqueueKey(RdpScancode(0x1E))
        pacerScope.testScheduler.advanceTimeBy(100)
        assertEquals(4, capturedKeyEvents.size)
    }

    @Test
    fun testKeyPacerCancellationMidStrokeCleanlyReleasesHeldKeyWithoutDuplicates() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val pacerScope = TestScope(testDispatcher)
        val pacer = KeyPacer(instanceProvider = { testInstanceId }, scope = pacerScope)

        // Queue 100 events
        for (i in 0 until 100) {
            pacer.enqueueKey(RdpScancode(code = 0x20 + (i % 10), isExtended = false))
        }

        // Let 2 events complete cleanly (80ms)
        pacerScope.testScheduler.advanceTimeBy(80)
        assertEquals(2, pacer.dispatchedCount)
        assertEquals(4, capturedKeyEvents.size) // 2 down + 2 up

        // Advance 1ms into 3rd event (keydown emitted, currently held in 18ms delay)
        pacerScope.testScheduler.advanceTimeBy(1)
        assertEquals(3, pacer.dispatchedCount)
        assertEquals(5, capturedKeyEvents.size) // 3rd event down emitted
        assertTrue(capturedKeyEvents[4].down)
        val heldScancode = capturedKeyEvents[4].scancode

        // Abruptly cancel and release mid-stroke!
        pacer.cancelAndReleaseHeld()

        // Cancellation immediately emitted key up for held key (Event 6)
        assertEquals(6, capturedKeyEvents.size)
        assertFalse(capturedKeyEvents[5].down) // Up emitted
        assertEquals(heldScancode, capturedKeyEvents[5].scancode)

        // Advance virtual time past the remainder of KEYDOWN_DURATION_MS (18ms)
        pacerScope.testScheduler.advanceTimeBy(10_000)

        // Verification:
        // KeyPacer cleanly cancelled the processing Job and re-checked isRunning/held state,
        // so exactly 6 events are emitted without redundant duplicate key-up release.
        val totalDispatchedEvents = capturedKeyEvents.size
        assertEquals(
            "KeyPacer must cleanly emit exactly 6 events (2 complete pairs + 1 cancelled pair) without duplicate release",
            6,
            totalDispatchedEvents
        )
    }

    // =========================================================================
    // 4. ModifierState Latching Permutations & Guaranteed Cleanup
    // =========================================================================

    @Test
    fun testModifierStateCtrlAltDelPermutation() {
        val sentEvents = mutableListOf<Pair<RdpScancode, Boolean>>()
        val modState = ModifierState { sc, down -> sentEvents.add(sc to down) }

        // 1. Toggle Ctrl -> LATCHED (sends Ctrl down)
        modState.toggleModifier(ModifierKey.CTRL)
        assertEquals(ModifierState.State.LATCHED, modState.ctrlState)
        assertTrue(modState.isCtrlActive)
        assertEquals(1, sentEvents.size)
        assertEquals(0x1D, sentEvents[0].first.code)
        assertFalse(sentEvents[0].first.isExtended)
        assertTrue(sentEvents[0].second)

        // 2. Toggle Alt -> LATCHED (sends Alt down)
        modState.toggleModifier(ModifierKey.ALT)
        assertEquals(ModifierState.State.LATCHED, modState.altState)
        assertTrue(modState.isAltActive)
        assertEquals(2, sentEvents.size)
        assertEquals(0x38, sentEvents[1].first.code)
        assertFalse(sentEvents[1].first.isExtended)
        assertTrue(sentEvents[1].second)

        // 3. User taps DEL (non-modifier) -> triggers onNonModifierKeyDispatched()
        modState.onNonModifierKeyDispatched()

        // Both Ctrl and Alt must auto-release to OFF
        assertEquals(ModifierState.State.OFF, modState.ctrlState)
        assertEquals(ModifierState.State.OFF, modState.altState)
        assertFalse(modState.isCtrlActive)
        assertFalse(modState.isAltActive)

        // Exactly 4 events total (Ctrl down, Alt down, Ctrl up, Alt up)
        assertEquals(4, sentEvents.size)
        assertEquals(0x1D, sentEvents[2].first.code)
        assertFalse(sentEvents[2].second) // Ctrl up
        assertEquals(0x38, sentEvents[3].first.code)
        assertFalse(sentEvents[3].second) // Alt up
    }

    @Test
    fun testModifierStateWinRPermutationWithExtendedFlag() {
        val sentEvents = mutableListOf<Pair<RdpScancode, Boolean>>()
        val modState = ModifierState { sc, down -> sentEvents.add(sc to down) }

        // 1. Toggle Super/Win -> LATCHED (sends 0x5B with extended flag)
        modState.toggleModifier(ModifierKey.SUPER)
        assertEquals(ModifierState.State.LATCHED, modState.superState)
        assertTrue(modState.isSuperActive)
        assertEquals(1, sentEvents.size)
        assertEquals(0x5B, sentEvents[0].first.code)
        assertTrue(sentEvents[0].first.isExtended)
        assertTrue(sentEvents[0].second) // Down

        // 2. Dispatch 'R' -> triggers onNonModifierKeyDispatched()
        modState.onNonModifierKeyDispatched()
        assertEquals(ModifierState.State.OFF, modState.superState)
        assertFalse(modState.isSuperActive)

        // Release event must preserve extended flag
        assertEquals(2, sentEvents.size)
        assertEquals(0x5B, sentEvents[1].first.code)
        assertTrue(sentEvents[1].first.isExtended)
        assertFalse(sentEvents[1].second) // Up
    }

    @Test
    fun testModifierStateAltTabPermutation() {
        val sentEvents = mutableListOf<Pair<RdpScancode, Boolean>>()
        val modState = ModifierState { sc, down -> sentEvents.add(sc to down) }

        // 1. Toggle Alt -> LATCHED
        modState.toggleModifier(ModifierKey.ALT)
        assertTrue(modState.isAltActive)

        // 2. Dispatch TAB -> releases Alt
        modState.onNonModifierKeyDispatched()
        assertEquals(ModifierState.State.OFF, modState.altState)
        assertEquals(2, sentEvents.size)
        assertFalse(sentEvents[1].second) // Alt up
    }

    @Test
    fun testModifierStateStickyLockingAndMixedLatchingPermutations() {
        val sentEvents = mutableListOf<Pair<RdpScancode, Boolean>>()
        val modState = ModifierState { sc, down -> sentEvents.add(sc to down) }

        // Double-tap Ctrl: OFF -> LATCHED -> LOCKED
        modState.toggleModifier(ModifierKey.CTRL)
        modState.toggleModifier(ModifierKey.CTRL)
        assertEquals(ModifierState.State.LOCKED, modState.ctrlState)

        // Single-tap Shift: OFF -> LATCHED
        modState.toggleModifier(ModifierKey.SHIFT)
        assertEquals(ModifierState.State.LATCHED, modState.shiftState)

        // 1st keystroke: Shift auto-releases, Ctrl persists LOCKED
        modState.onNonModifierKeyDispatched()
        assertEquals(ModifierState.State.LOCKED, modState.ctrlState)
        assertEquals(ModifierState.State.OFF, modState.shiftState)

        // 2nd keystroke: Ctrl still persists LOCKED
        modState.onNonModifierKeyDispatched()
        assertEquals(ModifierState.State.LOCKED, modState.ctrlState)

        // Guaranteed releaseAllModifiers() clears even LOCKED modifiers!
        modState.releaseAllModifiers()
        assertEquals(ModifierState.State.OFF, modState.ctrlState)
        assertFalse(modState.isCtrlActive)
    }

    @Test
    fun testModifierStateGuaranteedReleaseAllModifiersIdempotence() {
        val sentEvents = mutableListOf<Pair<RdpScancode, Boolean>>()
        val modState = ModifierState { sc, down -> sentEvents.add(sc to down) }

        // Engage all 4 modifiers
        modState.toggleModifier(ModifierKey.CTRL)
        modState.toggleModifier(ModifierKey.ALT)
        modState.toggleModifier(ModifierKey.SHIFT)
        modState.toggleModifier(ModifierKey.SUPER)

        val eventsBeforeRelease = sentEvents.size
        assertEquals(4, eventsBeforeRelease)

        // Call releaseAllModifiers
        modState.releaseAllModifiers()
        val eventsAfterFirstRelease = sentEvents.size
        assertEquals(8, eventsAfterFirstRelease) // 4 downs + 4 ups

        // Calling releaseAllModifiers again when already OFF must be idempotent (no duplicate events)
        modState.releaseAllModifiers()
        assertEquals(eventsAfterFirstRelease, sentEvents.size)
    }
}
