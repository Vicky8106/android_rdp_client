package com.rdp.client.freerdp

import android.view.KeyEvent
import com.rdp.client.utils.KeyPacer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Empirical Adversarial Stress Test Suite for Milestone 3 Keyboard Subsystem:
 * - ScancodeMapper: 0..300 KeyEvent sweep, zero crashes, bijectivity, collision prevention, unmapped handling
 * - Extended scancodes: 0xE0 flags, navigation arrows, numpad vs standard keys, Super/Win
 * - Unicode Fastpath: SMP high code points (> 0xFFFF), UTF-16 surrogate pairs, emojis, dead keys, empty strings
 * - KeyPacer: queue flooding (100+ rapid events), calibrated timing (18ms / 22ms), concurrent stress, cancellation
 * - ModifierState: latching permutations (Ctrl+Alt+Del, Win+R, Alt+Tab), sticky lock states, 81-state fuzzing, guaranteed sweep
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AdversarialKeyboardStressTest {

    private val sentNativeKeyEvents = ConcurrentLinkedQueue<CapturedKeyEvent>()
    private val sentNativeUnicodeEvents = ConcurrentLinkedQueue<CapturedUnicodeEvent>()
    private val testInstance = 998877L

    data class CapturedKeyEvent(
        val instance: Long,
        val scancode: Int,
        val extended: Boolean,
        val down: Boolean,
        val timestampMs: Long = System.currentTimeMillis()
    )

    data class CapturedUnicodeEvent(
        val instance: Long,
        val codePoint: Int,
        val timestampMs: Long = System.currentTimeMillis()
    )

    @Before
    fun setUp() {
        sentNativeKeyEvents.clear()
        sentNativeUnicodeEvents.clear()

        LibFreeRDP.setNativeBridgeForTesting(object : IRdpNativeBridge {
            override fun newInstance(context: android.content.Context?): Long = testInstance
            override fun freeInstance(instance: Long) {}
            override fun connect(instance: Long, params: RdpConnectionParameters?): Boolean = true
            override fun disconnect(instance: Long): Boolean = true
            override fun updateGraphics(instance: Long, bitmap: android.graphics.Bitmap, x: Int, y: Int, w: Int, h: Int): Boolean = true
            override fun sendCursorEvent(instance: Long, x: Int, y: Int, flags: Int): Boolean = true
            override fun sendKeyEvent(instance: Long, scancode: Int, extended: Boolean, down: Boolean): Boolean {
                sentNativeKeyEvents.add(CapturedKeyEvent(instance, scancode, extended, down))
                return true
            }
            override fun sendUnicodeKeyEvent(instance: Long, codePoint: Int): Boolean {
                sentNativeUnicodeEvents.add(CapturedUnicodeEvent(instance, codePoint))
                return true
            }
            override fun getVersion(): String = "Adversarial-FreeRDP-Mock"
            override fun getLastError(instance: Long): String? = null
        })
    }

    // =========================================================================
    // 1. ScancodeMapper: 0..300 Android KeyEvents Exhaustive Fuzzing & Bijectivity
    // =========================================================================

    @Test
    fun fuzzAllAndroidKeyEvents_0to300_zeroCrashesAndBijectiveIntegrity() {
        var mappedCount = 0
        var unmappedCount = 0

        for (keyCode in 0..300) {
            val scancode = try {
                ScancodeMapper.toScancode(keyCode)
            } catch (t: Throwable) {
                fail("ScancodeMapper.toScancode threw exception for keyCode $keyCode: ${t.message}")
                null
            }

            if (scancode != null) {
                mappedCount++
                // Validate scancode byte bounds
                assertTrue("Scancode byte must be in 0x01..0xFF for keyCode $keyCode", scancode.code in 0x01..0xFF)

                // Verify down and release flags packing
                val flagsDown = scancode.toRdpFlags(isDown = true)
                val flagsUp = scancode.toRdpFlags(isDown = false)

                assertEquals(0, flagsDown and RdpScancode.KBD_FLAGS_RELEASE)
                assertNotEquals(0, flagsUp and RdpScancode.KBD_FLAGS_RELEASE)

                if (scancode.isExtended) {
                    assertNotEquals(0, flagsDown and RdpScancode.KBD_FLAGS_EXTENDED)
                    assertNotEquals(0, flagsUp and RdpScancode.KBD_FLAGS_EXTENDED)
                } else {
                    assertEquals(0, flagsDown and RdpScancode.KBD_FLAGS_EXTENDED)
                    assertEquals(0, flagsUp and RdpScancode.KBD_FLAGS_EXTENDED)
                }

                // Verify bijectivity: reverse lookup must map back to exact keyCode
                val reverse = ScancodeMapper.toAndroidKeyCode(scancode.code, scancode.isExtended)
                assertNotNull("Reverse lookup should not be null for mapped keyCode $keyCode", reverse)
                assertEquals("Reverse lookup for ($scancode) must match original keyCode $keyCode", keyCode, reverse)
            } else {
                unmappedCount++
            }
        }

        assertTrue("Expected at least 90 mapped keys in 0..300 range, found $mappedCount", mappedCount >= 90)
        assertTrue("Expected unmapped keys in 0..300 range, found $unmappedCount", unmappedCount > 0)
    }

    @Test
    fun fuzzExtremeAndNegativeKeyCodes_returnsNullSafely() {
        val extremeCodes = listOf(
            Int.MIN_VALUE, -1000, -1, 301, 350, 500, 1000, 65535, Int.MAX_VALUE
        )

        for (code in extremeCodes) {
            val sc = ScancodeMapper.toScancode(code)
            assertNull("Expected null scancode for extreme/negative keyCode $code", sc)

            val isMod = ScancodeMapper.isModifierKey(code)
            assertFalse("Extreme/negative keyCode $code must not be classified as modifier", isMod)
        }

        // Test reverse lookup with extreme scancode values
        val extremeScancodes = listOf(-1, 0x00, 0xFF, 0x100, 9999, Int.MAX_VALUE)
        for (sc in extremeScancodes) {
            assertNull("Expected null for extreme scancode $sc (not extended)", ScancodeMapper.toAndroidKeyCode(sc, false))
            assertNull("Expected null for extreme scancode $sc (extended)", ScancodeMapper.toAndroidKeyCode(sc, true))
        }
    }

    @Test
    fun auditZeroCollisionsInMappingTables() {
        // Collect all forward mappings in 0..300
        val forwardEntries = mutableListOf<Pair<Int, RdpScancode>>()
        for (k in 0..300) {
            val sc = ScancodeMapper.toScancode(k)
            if (sc != null) {
                forwardEntries.add(k to sc)
            }
        }

        val scancodePairs = forwardEntries.map { it.second.code to it.second.isExtended }.toSet()

        // Every forward key must map to a unique (scancode, isExtended) pair.
        assertEquals(
            "Collision detected! Number of mapped keycodes (${forwardEntries.size}) does not match unique (scancode, isExtended) pairs (${scancodePairs.size})",
            forwardEntries.size,
            scancodePairs.size
        )

        // Ensure reverse lookup also contains the exact same size
        for ((k, sc) in forwardEntries) {
            val rev = ScancodeMapper.toAndroidKeyCode(sc.code, sc.isExtended)
            assertEquals("Reverse mapping mismatch for $sc", k, rev)
        }
    }

    @Test
    fun fuzzModifierClassification_strictlyClassifiesOnlyKnownModifiers() {
        val expectedModifiers = setOf(
            KeyEvent.KEYCODE_CTRL_LEFT,
            KeyEvent.KEYCODE_CTRL_RIGHT,
            KeyEvent.KEYCODE_ALT_LEFT,
            KeyEvent.KEYCODE_ALT_RIGHT,
            KeyEvent.KEYCODE_SHIFT_LEFT,
            KeyEvent.KEYCODE_SHIFT_RIGHT,
            KeyEvent.KEYCODE_META_LEFT,
            KeyEvent.KEYCODE_META_RIGHT
        )

        for (k in -100..400) {
            val isMod = ScancodeMapper.isModifierKey(k)
            if (expectedModifiers.contains(k)) {
                assertTrue("Keycode $k must be classified as modifier", isMod)
            } else {
                assertFalse("Keycode $k must NOT be classified as modifier", isMod)
            }
        }
    }

    // =========================================================================
    // 2. Extended Scancode Flags (0xE0) & Keypad vs Standard Key Distinctions
    // =========================================================================

    @Test
    fun testExtendedScancodes_navigationKeysStrictlyExtended() {
        val navKeys = mapOf(
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
            assertEquals("Scancode mismatch for $keyCode", expectedCode, sc!!.code)
            assertTrue("Navigation key $keyCode must be extended (0xE0)", sc.isExtended)

            val downFlags = sc.toRdpFlags(isDown = true)
            assertEquals(RdpScancode.KBD_FLAGS_EXTENDED, downFlags)

            val upFlags = sc.toRdpFlags(isDown = false)
            assertEquals(RdpScancode.KBD_FLAGS_RELEASE or RdpScancode.KBD_FLAGS_EXTENDED, upFlags)
        }
    }

    @Test
    fun testKeypadVsStandardKeys_zeroCollisionsAndExtendedDisambiguation() {
        val pairs = listOf(
            // Enter vs Numpad Enter
            Triple(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, 0x1C),
            // Slash vs Numpad Divide
            Triple(KeyEvent.KEYCODE_SLASH, KeyEvent.KEYCODE_NUMPAD_DIVIDE, 0x35),
            // Keypad 0 vs Insert
            Triple(KeyEvent.KEYCODE_NUMPAD_0, KeyEvent.KEYCODE_INSERT, 0x52),
            // Keypad Dot vs Forward Del
            Triple(KeyEvent.KEYCODE_NUMPAD_DOT, KeyEvent.KEYCODE_FORWARD_DEL, 0x53),
            // Keypad 7 vs Home
            Triple(KeyEvent.KEYCODE_NUMPAD_7, KeyEvent.KEYCODE_MOVE_HOME, 0x47),
            // Keypad 1 vs End
            Triple(KeyEvent.KEYCODE_NUMPAD_1, KeyEvent.KEYCODE_MOVE_END, 0x4F),
            // Keypad 9 vs PgUp
            Triple(KeyEvent.KEYCODE_NUMPAD_9, KeyEvent.KEYCODE_PAGE_UP, 0x49),
            // Keypad 3 vs PgDn
            Triple(KeyEvent.KEYCODE_NUMPAD_3, KeyEvent.KEYCODE_PAGE_DOWN, 0x51),
            // Keypad 8 vs Up
            Triple(KeyEvent.KEYCODE_NUMPAD_8, KeyEvent.KEYCODE_DPAD_UP, 0x48),
            // Keypad 2 vs Down
            Triple(KeyEvent.KEYCODE_NUMPAD_2, KeyEvent.KEYCODE_DPAD_DOWN, 0x50),
            // Keypad 4 vs Left
            Triple(KeyEvent.KEYCODE_NUMPAD_4, KeyEvent.KEYCODE_DPAD_LEFT, 0x4B),
            // Keypad 6 vs Right
            Triple(KeyEvent.KEYCODE_NUMPAD_6, KeyEvent.KEYCODE_DPAD_RIGHT, 0x4D),
            // Left Ctrl vs Right Ctrl
            Triple(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT, 0x1D),
            // Left Alt vs Right Alt
            Triple(KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT, 0x38)
        )

        for ((stdKey, extKey, scancodeByte) in pairs) {
            val scStd = ScancodeMapper.toScancode(stdKey)
            val scExt = ScancodeMapper.toScancode(extKey)

            assertNotNull("Standard key $stdKey must be mapped", scStd)
            assertNotNull("Extended key $extKey must be mapped", scExt)

            assertEquals("Standard key scancode byte mismatch", scancodeByte, scStd!!.code)
            assertEquals("Extended key scancode byte mismatch", scancodeByte, scExt!!.code)

            assertFalse("Standard key $stdKey must NOT be extended", scStd.isExtended)
            assertTrue("Extended key $extKey MUST be extended", scExt.isExtended)

            // Verify reverse lookup disambiguates correctly
            val revStd = ScancodeMapper.toAndroidKeyCode(scancodeByte, isExtended = false)
            val revExt = ScancodeMapper.toAndroidKeyCode(scancodeByte, isExtended = true)

            assertEquals("Reverse lookup for non-extended 0x${Integer.toHexString(scancodeByte)} failed", stdKey, revStd)
            assertEquals("Reverse lookup for extended 0x${Integer.toHexString(scancodeByte)} failed", extKey, revExt)
        }
    }

    @Test
    fun testSuperWinAndSystemKeysExtendedFlags() {
        val winLeft = ScancodeMapper.toScancode(KeyEvent.KEYCODE_META_LEFT)
        val winRight = ScancodeMapper.toScancode(KeyEvent.KEYCODE_META_RIGHT)
        val menuKey = ScancodeMapper.toScancode(KeyEvent.KEYCODE_MENU)
        val numLock = ScancodeMapper.toScancode(KeyEvent.KEYCODE_NUM_LOCK)

        assertNotNull(winLeft)
        assertNotNull(winRight)
        assertNotNull(menuKey)
        assertNotNull(numLock)

        assertEquals(0x5B, winLeft!!.code)
        assertTrue(winLeft.isExtended)

        assertEquals(0x5C, winRight!!.code)
        assertTrue(winRight.isExtended)

        assertEquals(0x5D, menuKey!!.code)
        assertTrue(menuKey.isExtended)

        assertEquals(0x45, numLock!!.code)
        assertTrue(numLock.isExtended)
    }

    // =========================================================================
    // 3. Unicode Fastpath Handling: SMP High Code Points, Emojis, Dead Keys
    // =========================================================================

    @Test
    fun testUnicodeFastpath_supplementaryCodePointsDecomposedIntoSurrogates() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val pacerScope = TestScope(testDispatcher)
        val pacer = KeyPacer(instanceProvider = { testInstance }, scope = pacerScope)

        val smpCases = listOf(
            0x1F600, // 😀 Grinning Face
            0x1F680, // 🚀 Rocket
            0x1F980, // 🦀 Crab
            0x1D11E, // 𝄞 Musical G Clef
            0x10000, // SMP boundary minimum
            0x10FFFF // Unicode maximum code point
        )

        for (cp in smpCases) {
            sentNativeUnicodeEvents.clear()
            pacer.enqueueUnicode(cp)

            // Advance through down duration (18ms) + inter-key pacing (22ms)
            pacerScope.testScheduler.advanceTimeBy(40)

            // Must have emitted exactly 2 surrogate events
            assertEquals("Supplementary code point U+${Integer.toHexString(cp).uppercase()} must emit 2 UTF-16 surrogates", 2, sentNativeUnicodeEvents.size)

            val highEvent = sentNativeUnicodeEvents.poll()!!
            val lowEvent = sentNativeUnicodeEvents.poll()!!

            val expectedHigh = Character.highSurrogate(cp).code
            val expectedLow = Character.lowSurrogate(cp).code

            assertEquals(testInstance, highEvent.instance)
            assertEquals(expectedHigh, highEvent.codePoint)
            assertEquals(testInstance, lowEvent.instance)
            assertEquals(expectedLow, lowEvent.codePoint)

            // Reconstruct code point to verify lossless round-trip
            val reconstructed = Character.toCodePoint(highEvent.codePoint.toChar(), lowEvent.codePoint.toChar())
            assertEquals("Surrogate pair reconstruction must equal original code point", cp, reconstructed)
        }
    }

    @Test
    fun testUnicodeFastpath_bmpCodePointsDirectlyDispatched() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val pacerScope = TestScope(testDispatcher)
        val pacer = KeyPacer(instanceProvider = { testInstance }, scope = pacerScope)

        val bmpCases = listOf(
            0x0041, // 'A'
            0x007A, // 'z'
            0x0030, // '0'
            0x6F22, // '漢' (CJK)
            0x5B57, // '字' (CJK)
            0x03A9, // 'Ω' (Greek Omega)
            0x0000, // NUL control
            0xFFFF  // BMP boundary max
        )

        for (cp in bmpCases) {
            sentNativeUnicodeEvents.clear()
            pacer.enqueueUnicode(cp)

            pacerScope.testScheduler.advanceTimeBy(40)

            assertEquals("BMP code point U+${Integer.toHexString(cp).uppercase()} must emit exactly 1 event", 1, sentNativeUnicodeEvents.size)
            val event = sentNativeUnicodeEvents.poll()!!
            assertEquals(cp, event.codePoint)
        }
    }

    @Test
    fun testUnicodeFastpath_enqueueTextComplexStringsAndEmojis() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val pacerScope = TestScope(testDispatcher)
        val pacer = KeyPacer(instanceProvider = { testInstance }, scope = pacerScope)

        // 1. Empty string test: zero events, zero crashes
        sentNativeUnicodeEvents.clear()
        pacer.enqueueText("")
        pacerScope.testScheduler.advanceTimeBy(100)
        assertEquals(0, sentNativeUnicodeEvents.size)

        // 2. Mixed emoji and text
        val mixedString = "RDP 🚀 2026!"
        sentNativeUnicodeEvents.clear()
        pacer.enqueueText(mixedString)

        // Advance 11 code points * 40ms = 440ms
        pacerScope.testScheduler.advanceTimeBy(440)

        assertEquals(11, pacer.dispatchedCount)
        assertEquals(12, sentNativeUnicodeEvents.size)

        // 3. Combining diacritics & dead keys
        val deadKeyString = "e\u0301" // 'e' + combining acute accent
        sentNativeUnicodeEvents.clear()
        pacer.enqueueText(deadKeyString)
        pacerScope.testScheduler.advanceTimeBy(80)
        assertEquals(2, sentNativeUnicodeEvents.size)
        assertEquals('e'.code, sentNativeUnicodeEvents.poll()!!.codePoint)
        assertEquals(0x0301, sentNativeUnicodeEvents.poll()!!.codePoint)
    }

    // =========================================================================
    // 4. KeyPacer: Queue Flooding (100+ Events), Timing (18/22ms) & Cancellation
    // =========================================================================

    @Test
    fun testKeyPacerFlooding_150RapidEventsProcessedWithCalibratedTiming() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val pacerScope = TestScope(testDispatcher)
        val pacer = KeyPacer(instanceProvider = { testInstance }, scope = pacerScope)

        val totalEvents = 150
        val scancodeA = RdpScancode(0x1E, isExtended = false)

        // Rapidly flood 150 events
        for (i in 1..totalEvents) {
            pacer.enqueueKey(scancodeA)
        }

        assertEquals(0, pacer.dispatchedCount)
        assertEquals(0, sentNativeKeyEvents.size)

        // Advance total calibrated time: 150 keys * (18ms down + 22ms inter-key) = 6000ms
        pacerScope.testScheduler.advanceTimeBy(totalEvents * (KeyPacer.KEYDOWN_DURATION_MS + KeyPacer.INTER_KEY_PACING_MS))

        // Final verification: 150 keys processed, total 300 native calls (150 down + 150 up)
        assertEquals(totalEvents, pacer.dispatchedCount)
        assertEquals(totalEvents * 2, sentNativeKeyEvents.size)

        // Verify strictly alternating DOWN, UP sequence
        val eventList = sentNativeKeyEvents.toList()
        for (i in 0 until eventList.size step 2) {
            assertTrue("Event at index $i must be DOWN", eventList[i].down)
            assertFalse("Event at index ${i + 1} must be UP", eventList[i + 1].down)
            assertEquals("Scancode must match across down/up pair", eventList[i].scancode, eventList[i + 1].scancode)
        }
    }

    @Test
    fun testKeyPacer_exactMillisecondPacingPrecision() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val pacerScope = TestScope(testDispatcher)
        val pacer = KeyPacer(instanceProvider = { testInstance }, scope = pacerScope)

        val scancodeA = RdpScancode(0x1E, isExtended = false)
        val scancodeB = RdpScancode(0x30, isExtended = false)

        pacer.enqueueKey(scancodeA)
        pacer.enqueueKey(scancodeB)

        // Advance 1ms to trigger Key A keydown at t=1
        pacerScope.testScheduler.advanceTimeBy(1)
        assertEquals(1, sentNativeKeyEvents.size)
        assertTrue(sentNativeKeyEvents.peek()!!.down)
        assertEquals(0x1E, sentNativeKeyEvents.peek()!!.scancode)

        // Advance 17ms (t=18, only 17ms since keydown): Key A up NOT yet emitted
        pacerScope.testScheduler.advanceTimeBy(KeyPacer.KEYDOWN_DURATION_MS - 1)
        assertEquals(1, sentNativeKeyEvents.size)

        // Advance 1ms (t=19, exactly 18ms since keydown): Key A up IS emitted
        pacerScope.testScheduler.advanceTimeBy(1)
        assertEquals(2, sentNativeKeyEvents.size)
        val eventList = sentNativeKeyEvents.toList()
        assertFalse(eventList.last().down)
        assertEquals(0x1E, eventList.last().scancode)

        // Advance 21ms (t=40, only 21ms since key up): Key B down NOT yet emitted
        pacerScope.testScheduler.advanceTimeBy(KeyPacer.INTER_KEY_PACING_MS - 1)
        assertEquals(2, sentNativeKeyEvents.size)

        // Advance 1ms (t=41, exactly 22ms since key up): Key B down IS emitted
        pacerScope.testScheduler.advanceTimeBy(1)
        assertEquals(3, sentNativeKeyEvents.size)
        val eventList3 = sentNativeKeyEvents.toList()
        assertTrue(eventList3.last().down)
        assertEquals(0x30, eventList3.last().scancode)

        // Advance 18ms (t=59, exactly 18ms since key B down): Key B up IS emitted
        pacerScope.testScheduler.advanceTimeBy(KeyPacer.KEYDOWN_DURATION_MS)
        assertEquals(4, sentNativeKeyEvents.size)
        val eventList4 = sentNativeKeyEvents.toList()
        assertFalse(eventList4.last().down)
        assertEquals(0x30, eventList4.last().scancode)
    }

    @Test
    fun testKeyPacer_cancellationMidKeydownGuaranteesImmediateRelease() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val pacerScope = TestScope(testDispatcher)
        val pacer = KeyPacer(instanceProvider = { testInstance }, scope = pacerScope)

        val scancodeZ = RdpScancode(0x2C, isExtended = false)

        // Queue 50 events
        for (i in 1..50) {
            pacer.enqueueKey(scancodeZ)
        }

        // Advance 10ms into the first key's 18ms keydown duration
        pacerScope.testScheduler.advanceTimeBy(10)
        assertEquals(1, sentNativeKeyEvents.size)
        assertTrue("First event must be DOWN", sentNativeKeyEvents.peek()!!.down)

        // Abruptly cancel while key is held down
        pacer.cancelAndReleaseHeld()

        // Immediate release must have been sent
        val upEvents = sentNativeKeyEvents.filter { !it.down }
        assertEquals("Cancel must trigger immediate key up release", 1, upEvents.size)
        assertEquals(0x2C, upEvents[0].scancode)

        // Advance virtual time by 20 seconds
        pacerScope.testScheduler.advanceTimeBy(20000)

        // Dispatched count must remain 1; no remaining 49 keys ever processed
        assertEquals(1, pacer.dispatchedCount)

        // Subsequent enqueue after cancel is safely dropped
        pacer.enqueueKey(RdpScancode(0x10, isExtended = false))
        pacer.enqueueText("SHOULD_BE_IGNORED")
        pacerScope.testScheduler.advanceTimeBy(1000)
        assertEquals(1, pacer.dispatchedCount)
    }

    @Test
    fun testKeyPacer_zeroInstanceSkipsSafely() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val pacerScope = TestScope(testDispatcher)
        val pacer = KeyPacer(instanceProvider = { 0L }, scope = pacerScope)

        pacer.enqueueKey(RdpScancode(0x1E, isExtended = false))
        pacerScope.testScheduler.advanceTimeBy(100)

        // Zero native calls dispatched
        assertEquals(0, sentNativeKeyEvents.size)
        assertEquals(0, pacer.dispatchedCount)
    }

    @Test
    fun testKeyPacer_concurrentMultiThreadedFlooding() {
        val realScope = CoroutineScope(Dispatchers.Default)
        val pacer = KeyPacer(instanceProvider = { testInstance }, scope = realScope)

        val threadCount = 10
        val keysPerThread = 15
        val latch = CountDownLatch(threadCount)

        for (t in 0 until threadCount) {
            realScope.launch {
                try {
                    for (k in 0 until keysPerThread) {
                        pacer.enqueueKey(RdpScancode(0x1E, isExtended = false))
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue("Threads must complete enqueueing within 5s", latch.await(5, TimeUnit.SECONDS))
        pacer.cancelAndReleaseHeld()
    }

    // =========================================================================
    // 5. ModifierState: Latching Permutations, Sticky Toggles, 81-State Sweep
    // =========================================================================

    @Test
    fun testModifierPermutation_CtrlAltDel() {
        val actions = mutableListOf<Pair<RdpScancode, Boolean>>()
        val modifierState = ModifierState { scancode, isDown ->
            actions.add(scancode to isDown)
        }

        // 1. Toggle Ctrl: OFF -> LATCHED
        modifierState.toggleModifier(ModifierKey.CTRL)
        assertTrue(modifierState.isCtrlActive)
        assertEquals(ModifierState.State.LATCHED, modifierState.ctrlState)
        assertEquals(1, actions.size)
        assertEquals(0x1D, actions[0].first.code)
        assertTrue(actions[0].second) // Down

        // 2. Toggle Alt: OFF -> LATCHED
        modifierState.toggleModifier(ModifierKey.ALT)
        assertTrue(modifierState.isAltActive)
        assertEquals(ModifierState.State.LATCHED, modifierState.altState)
        assertEquals(2, actions.size)
        assertEquals(0x38, actions[1].first.code)
        assertTrue(actions[1].second) // Down

        // 3. User dispatches Forward Delete (non-modifier key)
        modifierState.onNonModifierKeyDispatched()

        // Both Ctrl and Alt must be released to OFF
        assertFalse(modifierState.isCtrlActive)
        assertFalse(modifierState.isAltActive)
        assertEquals(ModifierState.State.OFF, modifierState.ctrlState)
        assertEquals(ModifierState.State.OFF, modifierState.altState)

        // 4 total events: Ctrl Down, Alt Down, Ctrl Up, Alt Up
        assertEquals(4, actions.size)
        assertFalse("Ctrl must be released", actions[2].second)
        assertEquals(0x1D, actions[2].first.code)
        assertFalse("Alt must be released", actions[3].second)
        assertEquals(0x38, actions[3].first.code)
    }

    @Test
    fun testModifierPermutation_WinR() {
        val actions = mutableListOf<Pair<RdpScancode, Boolean>>()
        val modifierState = ModifierState { scancode, isDown ->
            actions.add(scancode to isDown)
        }

        // 1. Toggle Win/Super: OFF -> LATCHED
        modifierState.toggleModifier(ModifierKey.SUPER)
        assertTrue(modifierState.isSuperActive)
        assertEquals(1, actions.size)
        assertEquals(0x5B, actions[0].first.code)
        assertTrue(actions[0].first.isExtended)
        assertTrue(actions[0].second) // Down

        // 2. User presses 'R'
        modifierState.onNonModifierKeyDispatched()

        // Super must be released
        assertFalse(modifierState.isSuperActive)
        assertEquals(ModifierState.State.OFF, modifierState.superState)
        assertEquals(2, actions.size)
        assertEquals(0x5B, actions[1].first.code)
        assertTrue(actions[1].first.isExtended)
        assertFalse(actions[1].second) // Up
    }

    @Test
    fun testModifierPermutation_AltTab() {
        val actions = mutableListOf<Pair<RdpScancode, Boolean>>()
        val modifierState = ModifierState { scancode, isDown ->
            actions.add(scancode to isDown)
        }

        // 1. Toggle Alt: OFF -> LATCHED
        modifierState.toggleModifier(ModifierKey.ALT)
        assertTrue(modifierState.isAltActive)

        // 2. User presses Tab
        modifierState.onNonModifierKeyDispatched()

        // Alt released
        assertFalse(modifierState.isAltActive)
        assertEquals(2, actions.size)
        assertFalse(actions[1].second)
    }

    @Test
    fun testStickyLockedModifiers_persistAcrossMultipleNonModifierKeys() {
        val actions = mutableListOf<Pair<RdpScancode, Boolean>>()
        val modifierState = ModifierState { scancode, isDown ->
            actions.add(scancode to isDown)
        }

        // Double-tap Shift: OFF -> LATCHED -> LOCKED
        modifierState.toggleModifier(ModifierKey.SHIFT)
        modifierState.toggleModifier(ModifierKey.SHIFT)

        assertEquals(ModifierState.State.LOCKED, modifierState.shiftState)
        assertTrue(modifierState.isShiftActive)
        assertEquals(1, actions.size) // Only initial Down sent

        // Type 10 keys: Shift must persist in LOCKED state
        for (i in 1..10) {
            modifierState.onNonModifierKeyDispatched()
            assertEquals(ModifierState.State.LOCKED, modifierState.shiftState)
            assertTrue(modifierState.isShiftActive)
            assertEquals(1, actions.size) // No Up event sent!
        }

        // Third tap releases Shift: LOCKED -> OFF
        modifierState.toggleModifier(ModifierKey.SHIFT)
        assertEquals(ModifierState.State.OFF, modifierState.shiftState)
        assertFalse(modifierState.isShiftActive)
        assertEquals(2, actions.size)
        assertFalse("Shift must be released on third tap", actions[1].second)
    }

    @Test
    fun testMixedLatchedAndLockedModifiers_onlyLatchedAutoReleases() {
        val actions = mutableListOf<Pair<RdpScancode, Boolean>>()
        val modifierState = ModifierState { scancode, isDown ->
            actions.add(scancode to isDown)
        }

        // Ctrl LOCKED (double tap)
        modifierState.toggleModifier(ModifierKey.CTRL)
        modifierState.toggleModifier(ModifierKey.CTRL)

        // Alt LATCHED (single tap)
        modifierState.toggleModifier(ModifierKey.ALT)

        // Super LOCKED (double tap)
        modifierState.toggleModifier(ModifierKey.SUPER)
        modifierState.toggleModifier(ModifierKey.SUPER)

        // Shift LATCHED (single tap)
        modifierState.toggleModifier(ModifierKey.SHIFT)

        assertEquals(ModifierState.State.LOCKED, modifierState.ctrlState)
        assertEquals(ModifierState.State.LATCHED, modifierState.altState)
        assertEquals(ModifierState.State.LOCKED, modifierState.superState)
        assertEquals(ModifierState.State.LATCHED, modifierState.shiftState)

        val countBeforeDispatch = actions.size

        // Non-modifier key dispatch
        modifierState.onNonModifierKeyDispatched()

        // Latched modifiers (Alt, Shift) must become OFF
        assertEquals(ModifierState.State.OFF, modifierState.altState)
        assertEquals(ModifierState.State.OFF, modifierState.shiftState)

        // Locked modifiers (Ctrl, Super) must remain LOCKED
        assertEquals(ModifierState.State.LOCKED, modifierState.ctrlState)
        assertEquals(ModifierState.State.LOCKED, modifierState.superState)

        // Exactly 2 release events sent (Alt Up and Shift Up)
        val newEvents = actions.subList(countBeforeDispatch, actions.size)
        assertEquals(2, newEvents.size)
        assertTrue(newEvents.all { !it.second })

        // Guaranteed release cleanup sweeps remaining locked modifiers
        modifierState.releaseAllModifiers()
        assertEquals(ModifierState.State.OFF, modifierState.ctrlState)
        assertEquals(ModifierState.State.OFF, modifierState.superState)
        assertFalse(modifierState.isCtrlActive)
        assertFalse(modifierState.isSuperActive)
    }

    @Test
    fun testGuaranteedReleaseAllModifiers_idempotent() {
        val actions = mutableListOf<Pair<RdpScancode, Boolean>>()
        val modifierState = ModifierState { scancode, isDown ->
            actions.add(scancode to isDown)
        }

        // When all OFF, releaseAllModifiers sends 0 events
        modifierState.releaseAllModifiers()
        assertEquals(0, actions.size)

        // Engage all 4
        modifierState.toggleModifier(ModifierKey.CTRL)
        modifierState.toggleModifier(ModifierKey.ALT)
        modifierState.toggleModifier(ModifierKey.SHIFT)
        modifierState.toggleModifier(ModifierKey.SUPER)

        // First release sends 4 release events
        modifierState.releaseAllModifiers()
        val releases = actions.filter { !it.second }
        assertEquals(4, releases.size)

        // Second consecutive release sends 0 additional events
        val countAfterFirst = actions.size
        modifierState.releaseAllModifiers()
        assertEquals(countAfterFirst, actions.size)
    }

    @Test
    fun fuzzAll81ModifierStatePermutations_guaranteedCleanupSweep() {
        val states = listOf(
            ModifierState.State.OFF,
            ModifierState.State.LATCHED,
            ModifierState.State.LOCKED
        )

        var totalPermutationsTested = 0

        for (c in states) {
            for (a in states) {
                for (s in states) {
                    for (u in states) {
                        val actions = mutableListOf<Pair<RdpScancode, Boolean>>()
                        val modifierState = ModifierState { scancode, isDown ->
                            actions.add(scancode to isDown)
                        }

                        // Configure state
                        applyState(modifierState, ModifierKey.CTRL, c)
                        applyState(modifierState, ModifierKey.ALT, a)
                        applyState(modifierState, ModifierKey.SHIFT, s)
                        applyState(modifierState, ModifierKey.SUPER, u)

                        assertEquals(c, modifierState.ctrlState)
                        assertEquals(a, modifierState.altState)
                        assertEquals(s, modifierState.shiftState)
                        assertEquals(u, modifierState.superState)

                        val activeCount = listOf(c, a, s, u).count { it != ModifierState.State.OFF }

                        val eventsBeforeRelease = actions.size
                        // Execute guaranteed release sweep
                        modifierState.releaseAllModifiers()

                        // All states must be strictly OFF
                        assertEquals(ModifierState.State.OFF, modifierState.ctrlState)
                        assertEquals(ModifierState.State.OFF, modifierState.altState)
                        assertEquals(ModifierState.State.OFF, modifierState.shiftState)
                        assertEquals(ModifierState.State.OFF, modifierState.superState)

                        assertFalse(modifierState.isCtrlActive)
                        assertFalse(modifierState.isAltActive)
                        assertFalse(modifierState.isShiftActive)
                        assertFalse(modifierState.isSuperActive)

                        // Exactly activeCount release events must have been dispatched
                        val releaseEvents = actions.subList(eventsBeforeRelease, actions.size)
                        assertEquals("Mismatch in release events for state ($c, $a, $s, $u)", activeCount, releaseEvents.size)
                        assertTrue("All release events must be key-up", releaseEvents.all { !it.second })

                        totalPermutationsTested++
                    }
                }
            }
        }

        assertEquals("Must test all 3^4 = 81 permutations", 81, totalPermutationsTested)
    }

    private fun applyState(modifierState: ModifierState, key: ModifierKey, target: ModifierState.State) {
        when (target) {
            ModifierState.State.OFF -> { /* Already OFF by default */ }
            ModifierState.State.LATCHED -> modifierState.toggleModifier(key) // 1 tap
            ModifierState.State.LOCKED -> {
                modifierState.toggleModifier(key) // 1st tap: LATCHED
                modifierState.toggleModifier(key) // 2nd tap: LOCKED
            }
        }
    }
}
