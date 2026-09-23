package com.freerdp.feature.session

import com.freerdp.core.engine.MockRdpEngine
import com.freerdp.feature.session.keyboard.ScancodeTranslator
import com.freerdp.feature.session.modifier.LatchState
import com.freerdp.feature.session.modifier.MacroAction
import com.freerdp.feature.session.modifier.ModifierKey
import com.freerdp.feature.session.modifier.ModifierStateMachine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ModifierStateMachineTest {

    private lateinit var mockEngine: MockRdpEngine
    private lateinit var modifierFsm: ModifierStateMachine

    @Before
    fun setUp() {
        mockEngine = MockRdpEngine()
        modifierFsm = ModifierStateMachine(mockEngine)
    }

    @Test
    fun testInitialStatesAreInactive() {
        assertEquals(LatchState.INACTIVE, modifierFsm.getModifierState(ModifierKey.CTRL))
        assertEquals(LatchState.INACTIVE, modifierFsm.getModifierState(ModifierKey.ALT))
        assertEquals(LatchState.INACTIVE, modifierFsm.getModifierState(ModifierKey.SHIFT))
        assertEquals(LatchState.INACTIVE, modifierFsm.getModifierState(ModifierKey.WIN))
        assertFalse(modifierFsm.isKeyActive(ModifierKey.CTRL))
    }

    @Test
    fun testCtrlKeySingleTapLatchesAndAutoReleasesOnNonModifierKey() {
        // Step 1: Tap Ctrl once -> LATCHED
        modifierFsm.onModifierKeyTapped(ModifierKey.CTRL)
        assertEquals(LatchState.LATCHED, modifierFsm.getModifierState(ModifierKey.CTRL))
        assertTrue(modifierFsm.isLatched(ModifierKey.CTRL))
        assertFalse(modifierFsm.isLocked(ModifierKey.CTRL))
        assertTrue(modifierFsm.isKeyActive(ModifierKey.CTRL))

        // Engine should have received KeyDown for Left Ctrl (0x1D)
        val lCtrlScancode = ScancodeTranslator.SCANCODE_LEFT_CTRL
        assertTrue(mockEngine.recordedKeyEvents.any { it.keyCode == lCtrlScancode && it.down })

        // Step 2: User presses non-modifier key 'C' (Scancode 0x2E)
        val cScancode = ScancodeTranslator.SCANCODE_C
        modifierFsm.onNonModifierKeyPressed(cScancode)

        // Step 3: Ctrl must automatically release to INACTIVE
        assertEquals(LatchState.INACTIVE, modifierFsm.getModifierState(ModifierKey.CTRL))
        assertFalse(modifierFsm.isLatched(ModifierKey.CTRL))
        assertFalse(modifierFsm.isKeyActive(ModifierKey.CTRL))

        // Engine should have received KeyUp for Left Ctrl (0x1D)
        assertTrue(mockEngine.recordedKeyEvents.any { it.keyCode == lCtrlScancode && !it.down })
    }

    @Test
    fun testCtrlKeyDoubleTapLocksAndPersistsAcrossKeysUntilTappedAgain() {
        // Step 1: Tap Ctrl twice -> LOCKED
        modifierFsm.onModifierKeyTapped(ModifierKey.CTRL)
        assertEquals(LatchState.LATCHED, modifierFsm.getModifierState(ModifierKey.CTRL))

        modifierFsm.onModifierKeyTapped(ModifierKey.CTRL)
        assertEquals(LatchState.LOCKED, modifierFsm.getModifierState(ModifierKey.CTRL))
        assertTrue(modifierFsm.isLocked(ModifierKey.CTRL))
        assertFalse(modifierFsm.isLatched(ModifierKey.CTRL))
        assertTrue(modifierFsm.isKeyActive(ModifierKey.CTRL))

        // Step 2: Press multiple non-modifier keys ('A' and 'C')
        modifierFsm.onNonModifierKeyPressed(ScancodeTranslator.SCANCODE_A)
        assertEquals(LatchState.LOCKED, modifierFsm.getModifierState(ModifierKey.CTRL))

        modifierFsm.onNonModifierKeyPressed(ScancodeTranslator.SCANCODE_C)
        assertEquals(LatchState.LOCKED, modifierFsm.getModifierState(ModifierKey.CTRL))

        // Step 3: Third tap unlocks and transitions to INACTIVE
        modifierFsm.onModifierKeyTapped(ModifierKey.CTRL)
        assertEquals(LatchState.INACTIVE, modifierFsm.getModifierState(ModifierKey.CTRL))
        assertFalse(modifierFsm.isKeyActive(ModifierKey.CTRL))

        val lCtrlScancode = ScancodeTranslator.SCANCODE_LEFT_CTRL
        val lastEvent = mockEngine.recordedKeyEvents.last()
        assertEquals(lCtrlScancode, lastEvent.keyCode)
        assertFalse("Final event must be key release", lastEvent.down)
    }

    @Test
    fun testMultipleModifiersLatchedSimultaneously() {
        // Tap Ctrl -> Latched, Tap Shift -> Latched
        modifierFsm.onModifierKeyTapped(ModifierKey.CTRL)
        modifierFsm.onModifierKeyTapped(ModifierKey.SHIFT)

        assertTrue(modifierFsm.isLatched(ModifierKey.CTRL))
        assertTrue(modifierFsm.isLatched(ModifierKey.SHIFT))

        // Press 'Z'
        modifierFsm.onNonModifierKeyPressed(ScancodeTranslator.SCANCODE_Z)

        // Both should auto-release
        assertEquals(LatchState.INACTIVE, modifierFsm.getModifierState(ModifierKey.CTRL))
        assertEquals(LatchState.INACTIVE, modifierFsm.getModifierState(ModifierKey.SHIFT))
    }

    @Test
    fun testSpecialKeysDispatchEventsAndReleaseLatchedModifiers() {
        modifierFsm.onModifierKeyTapped(ModifierKey.ALT)
        assertTrue(modifierFsm.isLatched(ModifierKey.ALT))

        // Tap ESC
        modifierFsm.onSpecialKeyTapped(ModifierKey.ESC)

        // Alt should be released
        assertEquals(LatchState.INACTIVE, modifierFsm.getModifierState(ModifierKey.ALT))

        // Check ESC events: down then up
        val escScancode = ScancodeTranslator.SCANCODE_ESCAPE
        val escEvents = mockEngine.recordedKeyEvents.filter { it.keyCode == escScancode }
        assertEquals(2, escEvents.size)
        assertTrue(escEvents[0].down)
        assertFalse(escEvents[1].down)
    }

    @Test
    fun testFunctionKeysDispatch() {
        modifierFsm.onSpecialKeyTapped(ModifierKey.F5)
        val f5Scancode = ScancodeTranslator.SCANCODE_F5
        val f5Events = mockEngine.recordedKeyEvents.filter { it.keyCode == f5Scancode }
        assertEquals(2, f5Events.size)
        assertTrue(f5Events[0].down)
        assertFalse(f5Events[1].down)
    }

    @Test
    fun testCtrlAltDelMacroSequence() {
        modifierFsm.triggerMacro(MacroAction.CTRL_ALT_DEL)

        val keys = mockEngine.recordedKeyEvents
        // Must contain Down(Ctrl), Down(Alt), Down(Del), Up(Del), Up(Alt), Up(Ctrl)
        val expectedScancodes = listOf(
            ScancodeTranslator.SCANCODE_LEFT_CTRL,
            ScancodeTranslator.SCANCODE_LEFT_ALT,
            ScancodeTranslator.SCANCODE_DELETE,
            ScancodeTranslator.SCANCODE_DELETE,
            ScancodeTranslator.SCANCODE_LEFT_ALT,
            ScancodeTranslator.SCANCODE_LEFT_CTRL
        )
        val actualScancodes = keys.takeLast(6).map { it.keyCode }
        assertEquals(expectedScancodes, actualScancodes)

        val downFlags = keys.takeLast(6).map { it.down }
        assertEquals(listOf(true, true, true, false, false, false), downFlags)
    }

    @Test
    fun testAltTabMacroSequence() {
        modifierFsm.triggerMacro(MacroAction.ALT_TAB)

        val keys = mockEngine.recordedKeyEvents.takeLast(4)
        val expectedScancodes = listOf(
            ScancodeTranslator.SCANCODE_LEFT_ALT,
            ScancodeTranslator.SCANCODE_TAB,
            ScancodeTranslator.SCANCODE_TAB,
            ScancodeTranslator.SCANCODE_LEFT_ALT
        )
        assertEquals(expectedScancodes, keys.map { it.keyCode })
        assertEquals(listOf(true, true, false, false), keys.map { it.down })
    }

    @Test
    fun testAltF4MacroSequence() {
        modifierFsm.triggerMacro(MacroAction.ALT_F4)

        val keys = mockEngine.recordedKeyEvents.takeLast(4)
        val expectedScancodes = listOf(
            ScancodeTranslator.SCANCODE_LEFT_ALT,
            ScancodeTranslator.SCANCODE_F4,
            ScancodeTranslator.SCANCODE_F4,
            ScancodeTranslator.SCANCODE_LEFT_ALT
        )
        assertEquals(expectedScancodes, keys.map { it.keyCode })
        assertEquals(listOf(true, true, false, false), keys.map { it.down })
    }

    @Test
    fun testWinDMacroSequence() {
        modifierFsm.triggerMacro(MacroAction.WIN_D)

        val keys = mockEngine.recordedKeyEvents.takeLast(4)
        val expectedScancodes = listOf(
            ScancodeTranslator.SCANCODE_LEFT_WIN,
            ScancodeTranslator.SCANCODE_D,
            ScancodeTranslator.SCANCODE_D,
            ScancodeTranslator.SCANCODE_LEFT_WIN
        )
        assertEquals(expectedScancodes, keys.map { it.keyCode })
        assertEquals(listOf(true, true, false, false), keys.map { it.down })
    }

    @Test
    fun testResetAllClearsAllActiveModifiers() {
        modifierFsm.onModifierKeyTapped(ModifierKey.CTRL) // Latched
        modifierFsm.onModifierKeyTapped(ModifierKey.ALT)
        modifierFsm.onModifierKeyTapped(ModifierKey.ALT)  // Locked

        assertTrue(modifierFsm.isLatched(ModifierKey.CTRL))
        assertTrue(modifierFsm.isLocked(ModifierKey.ALT))

        modifierFsm.resetAll()

        assertEquals(LatchState.INACTIVE, modifierFsm.getModifierState(ModifierKey.CTRL))
        assertEquals(LatchState.INACTIVE, modifierFsm.getModifierState(ModifierKey.ALT))

        val ctrlScancode = ScancodeTranslator.SCANCODE_LEFT_CTRL
        val altScancode = ScancodeTranslator.SCANCODE_LEFT_ALT

        assertTrue(mockEngine.recordedKeyEvents.any { it.keyCode == ctrlScancode && !it.down })
        assertTrue(mockEngine.recordedKeyEvents.any { it.keyCode == altScancode && !it.down })
    }

    // ---------------------------------------------------------------------
    // Boundary & corner cases (invalid transitions, rapid flapping, unmapped
    // scancodes, unicode fallback, down/up pairing, engineless operation)
    // ---------------------------------------------------------------------

    @Test
    fun testInvalidDirectLockTransitionIsRejectedWithoutEngineEvents() {
        assertFalse(
            "INACTIVE -> LOCKED bypasses LATCHED and must be rejected",
            modifierFsm.tryTransition(ModifierKey.CTRL, LatchState.LOCKED)
        )
        assertEquals(LatchState.INACTIVE, modifierFsm.getModifierState(ModifierKey.CTRL))
        assertTrue("Rejected transition must emit no engine events", mockEngine.recordedKeyEvents.isEmpty())
    }

    @Test
    fun testSelfTransitionsAndInvalidUnlockAreRejected() {
        assertFalse(
            "Release from INACTIVE must be rejected (no spurious key-up)",
            modifierFsm.tryTransition(ModifierKey.CTRL, LatchState.INACTIVE)
        )
        assertTrue(mockEngine.recordedKeyEvents.isEmpty())

        modifierFsm.onModifierKeyTapped(ModifierKey.CTRL) // -> LATCHED, one key-down
        assertEquals(1, mockEngine.recordedKeyEvents.size)

        assertFalse(
            "Self-loop LATCHED -> LATCHED must be rejected",
            modifierFsm.tryTransition(ModifierKey.CTRL, LatchState.LATCHED)
        )
        assertEquals(LatchState.LATCHED, modifierFsm.getModifierState(ModifierKey.CTRL))
        assertEquals("No duplicate key-down may be emitted", 1, mockEngine.recordedKeyEvents.size)

        modifierFsm.onModifierKeyTapped(ModifierKey.CTRL) // -> LOCKED
        assertEquals(LatchState.LOCKED, modifierFsm.getModifierState(ModifierKey.CTRL))
        val eventsBefore = mockEngine.recordedKeyEvents.size

        assertFalse(
            "LOCKED -> LATCHED must be rejected",
            modifierFsm.tryTransition(ModifierKey.CTRL, LatchState.LATCHED)
        )
        assertEquals(LatchState.LOCKED, modifierFsm.getModifierState(ModifierKey.CTRL))
        assertEquals("Rejected transition must emit nothing", eventsBefore, mockEngine.recordedKeyEvents.size)
    }

    @Test
    fun testNonLatchableKeysCannotBeTransitioned() {
        assertFalse(modifierFsm.tryTransition(ModifierKey.ESC, LatchState.LATCHED))
        assertFalse(modifierFsm.tryTransition(ModifierKey.F5, LatchState.LOCKED))
        assertFalse(modifierFsm.tryTransition(ModifierKey.ARROW_UP, LatchState.INACTIVE))
        assertTrue("Rejected transitions must not reach the engine", mockEngine.recordedKeyEvents.isEmpty())

        // The dispatch path for a non-latchable key remains functional: down + up
        modifierFsm.onModifierKeyTapped(ModifierKey.ESC)
        assertEquals(2, mockEngine.recordedKeyEvents.size)
        assertTrue(mockEngine.recordedKeyEvents[0].down)
        assertFalse(mockEngine.recordedKeyEvents[1].down)
    }

    @Test
    fun testRapidModifierFlappingKeepsDownUpEventsBalanced() {
        val ctrl = ScancodeTranslator.SCANCODE_LEFT_CTRL

        // 30 rapid taps = 10 full INACTIVE -> LATCHED -> LOCKED -> INACTIVE cycles
        repeat(30) { modifierFsm.onModifierKeyTapped(ModifierKey.CTRL) }
        assertEquals(LatchState.INACTIVE, modifierFsm.getModifierState(ModifierKey.CTRL))

        var downs = mockEngine.recordedKeyEvents.count { it.keyCode == ctrl && it.down }
        var ups = mockEngine.recordedKeyEvents.count { it.keyCode == ctrl && !it.down }
        assertEquals("Exactly one key-down per full tap cycle", 10, downs)
        assertEquals("Exactly one key-up per full tap cycle", 10, ups)
        assertEquals("No other engine events may appear during flapping", 20, mockEngine.recordedKeyEvents.size)

        // One extra tap re-latches: downs must equal ups + 1
        modifierFsm.onModifierKeyTapped(ModifierKey.CTRL)
        assertEquals(LatchState.LATCHED, modifierFsm.getModifierState(ModifierKey.CTRL))
        downs = mockEngine.recordedKeyEvents.count { it.keyCode == ctrl && it.down }
        ups = mockEngine.recordedKeyEvents.count { it.keyCode == ctrl && !it.down }
        assertEquals(11, downs)
        assertEquals(10, ups)
        assertEquals(21, mockEngine.recordedKeyEvents.size)

        // Flapping with two keys interleaved must remain balanced per scancode.
        // Trace from state (CTRL=LATCHED, ALT=INACTIVE) over 10 alternating taps:
        // CTRL cycles LOCKED->INACTIVE->LATCHED->LOCKED->INACTIVE (ends INACTIVE),
        // ALT cycles LATCHED->LOCKED->INACTIVE->LATCHED->LOCKED (ends LOCKED).
        val alt = ScancodeTranslator.SCANCODE_LEFT_ALT
        repeat(10) { i ->
            modifierFsm.onModifierKeyTapped(if (i % 2 == 0) ModifierKey.CTRL else ModifierKey.ALT)
        }
        assertEquals(LatchState.INACTIVE, modifierFsm.getModifierState(ModifierKey.CTRL))
        assertEquals(LatchState.LOCKED, modifierFsm.getModifierState(ModifierKey.ALT))

        val ctrlDowns = mockEngine.recordedKeyEvents.count { it.keyCode == ctrl && it.down }
        val ctrlUps = mockEngine.recordedKeyEvents.count { it.keyCode == ctrl && !it.down }
        val altDowns = mockEngine.recordedKeyEvents.count { it.keyCode == alt && it.down }
        val altUps = mockEngine.recordedKeyEvents.count { it.keyCode == alt && !it.down }
        assertEquals("Ctrl ended inactive: must be perfectly balanced", ctrlDowns, ctrlUps)
        assertEquals("Alt ended locked: downs must exceed ups by exactly one", altDowns - 1, altUps)
        assertTrue("Alt must have been exercised", altDowns > 0)
    }

    @Test
    fun testUnmappedScancodeIsDroppedAndPreservesLock() {
        modifierFsm.onModifierKeyTapped(ModifierKey.CTRL)
        modifierFsm.onModifierKeyTapped(ModifierKey.CTRL) // LOCKED
        val eventsBefore = mockEngine.recordedKeyEvents.size

        assertFalse(ScancodeTranslator.isKnownScancode(0x00))
        assertFalse(ScancodeTranslator.isKnownScancode(-1))
        assertFalse(ScancodeTranslator.isKnownScancode(0x200))

        modifierFsm.onNonModifierKeyPressed(0x200)
        modifierFsm.onNonModifierKeyPressed(0)
        modifierFsm.onNonModifierKeyPressed(-1)

        assertEquals(
            "Unmapped scancodes must never reach the engine",
            eventsBefore,
            mockEngine.recordedKeyEvents.size
        )
        assertEquals(
            "Nothing was consumed remotely, so the lock must survive",
            LatchState.LOCKED,
            modifierFsm.getModifierState(ModifierKey.CTRL)
        )
    }

    @Test
    fun testUnmappedScancodePreservesLatchUntilRealKeyConsumed() {
        modifierFsm.onModifierKeyTapped(ModifierKey.SHIFT) // LATCHED

        modifierFsm.onNonModifierKeyPressed(0x200) // rejected: not consumed
        assertEquals(
            "Rejected key must not auto-clear the latch",
            LatchState.LATCHED,
            modifierFsm.getModifierState(ModifierKey.SHIFT)
        )

        modifierFsm.onNonModifierKeyPressed(ScancodeTranslator.SCANCODE_A) // real key
        assertEquals(
            "Consumed key event must auto-clear the latch",
            LatchState.INACTIVE,
            modifierFsm.getModifierState(ModifierKey.SHIFT)
        )
    }

    @Test
    fun testUnknownCharDispatchesThroughUnicodeFallbackAndReleasesLatch() {
        modifierFsm.onModifierKeyTapped(ModifierKey.CTRL)
        assertTrue(modifierFsm.isLatched(ModifierKey.CTRL))

        modifierFsm.onNonModifierKeyPressed('§')

        val unicodeEvents = mockEngine.recordedUnicodeEvents
        assertEquals("Fallback must emit unicode down + up", 2, unicodeEvents.size)
        assertEquals('§', unicodeEvents[0].unicodeChar)
        assertTrue(unicodeEvents[0].down)
        assertEquals('§', unicodeEvents[1].unicodeChar)
        assertFalse(unicodeEvents[1].down)

        assertEquals(
            "Unicode dispatch counts as consumed: latch auto-clears",
            LatchState.INACTIVE,
            modifierFsm.getModifierState(ModifierKey.CTRL)
        )

        val ctrl = ScancodeTranslator.SCANCODE_LEFT_CTRL
        val ctrlDowns = mockEngine.recordedKeyEvents.count { it.keyCode == ctrl && it.down }
        val ctrlUps = mockEngine.recordedKeyEvents.count { it.keyCode == ctrl && !it.down }
        assertEquals(1, ctrlDowns)
        assertEquals(1, ctrlUps)
    }

    @Test
    fun testDownUpPairingHoldsAcrossMixedSequence() {
        modifierFsm.onModifierKeyTapped(ModifierKey.CTRL)      // latched
        modifierFsm.onSpecialKeyTapped(ModifierKey.F5)         // consumes + releases Ctrl
        modifierFsm.onModifierKeyTapped(ModifierKey.ALT)       // latched
        modifierFsm.onModifierKeyTapped(ModifierKey.SHIFT)     // latched
        modifierFsm.onNonModifierKeyPressed('a')               // consumes + releases Alt/Shift
        modifierFsm.onModifierKeyTapped(ModifierKey.WIN)       // latched
        modifierFsm.onModifierKeyTapped(ModifierKey.WIN)       // locked
        assertEquals(LatchState.LOCKED, modifierFsm.getModifierState(ModifierKey.WIN))
        modifierFsm.resetAll()

        assertEquals(LatchState.INACTIVE, modifierFsm.getModifierState(ModifierKey.CTRL))
        assertEquals(LatchState.INACTIVE, modifierFsm.getModifierState(ModifierKey.ALT))
        assertEquals(LatchState.INACTIVE, modifierFsm.getModifierState(ModifierKey.SHIFT))
        assertEquals(LatchState.INACTIVE, modifierFsm.getModifierState(ModifierKey.WIN))

        assertTrue("Sequence must have produced events", mockEngine.recordedKeyEvents.isNotEmpty())
        val byScancode = mockEngine.recordedKeyEvents.groupBy { it.keyCode }
        for ((scancode, events) in byScancode) {
            val downs = events.count { it.down }
            val ups = events.count { !it.down }
            assertEquals(
                "Scancode 0x${scancode.toString(16)} must have equal downs and ups",
                downs,
                ups
            )
        }
        // Specific known pairs
        val ctrl = ScancodeTranslator.SCANCODE_LEFT_CTRL
        val win = ScancodeTranslator.SCANCODE_LEFT_WIN
        assertEquals(1, mockEngine.recordedKeyEvents.count { it.keyCode == ctrl && it.down })
        assertEquals(1, mockEngine.recordedKeyEvents.count { it.keyCode == ctrl && !it.down })
        assertEquals(1, mockEngine.recordedKeyEvents.count { it.keyCode == win && it.down })
        assertEquals(1, mockEngine.recordedKeyEvents.count { it.keyCode == win && !it.down })
    }

    @Test
    fun testStateMachineWorksWithoutEngineAndStillUpdatesStateFlow() {
        val engineless = ModifierStateMachine(rdpEngine = null)

        engineless.onModifierKeyTapped(ModifierKey.CTRL)
        assertEquals(LatchState.LATCHED, engineless.getModifierState(ModifierKey.CTRL))
        engineless.onModifierKeyTapped(ModifierKey.CTRL)
        assertEquals(LatchState.LOCKED, engineless.getModifierState(ModifierKey.CTRL))
        engineless.onNonModifierKeyPressed(ScancodeTranslator.SCANCODE_B)
        assertEquals(
            "LOCKED survives a consumed key even without an engine",
            LatchState.LOCKED,
            engineless.getModifierState(ModifierKey.CTRL)
        )

        engineless.triggerMacro(MacroAction.CTRL_ALT_DEL) // must not throw
        engineless.onSpecialKeyTapped(ModifierKey.ESC)    // must not throw
        engineless.resetAll()
        assertEquals(LatchState.INACTIVE, engineless.getModifierState(ModifierKey.CTRL))

        // StateFlow must mirror the initial and updated states
        assertEquals(LatchState.INACTIVE, engineless.statesFlow.value[ModifierKey.CTRL])
        engineless.onModifierKeyTapped(ModifierKey.ALT)
        assertEquals(LatchState.LATCHED, engineless.statesFlow.value[ModifierKey.ALT])
        assertEquals(LatchState.INACTIVE, engineless.statesFlow.value[ModifierKey.CTRL])
    }
}
