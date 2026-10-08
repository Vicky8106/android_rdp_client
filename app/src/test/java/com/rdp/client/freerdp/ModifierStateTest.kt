package com.rdp.client.freerdp

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ModifierStateTest {

    private val sentEvents = mutableListOf<Pair<RdpScancode, Boolean>>()
    private lateinit var modifierState: ModifierState

    @Before
    fun setUp() {
        sentEvents.clear()
        modifierState = ModifierState { scancode, isDown ->
            sentEvents.add(scancode to isDown)
        }
    }

    @Test
    fun testInitialState() {
        assertEquals(ModifierState.State.OFF, modifierState.ctrlState)
        assertEquals(ModifierState.State.OFF, modifierState.altState)
        assertEquals(ModifierState.State.OFF, modifierState.shiftState)
        assertEquals(ModifierState.State.OFF, modifierState.superState)
        assertFalse(modifierState.isCtrlActive)
        assertFalse(modifierState.isAltActive)
        assertFalse(modifierState.isShiftActive)
        assertFalse(modifierState.isSuperActive)
    }

    @Test
    fun testLatchingAutoReleaseOnNonModifierKey() {
        // Toggle Ctrl: OFF -> LATCHED
        modifierState.toggleModifier(ModifierKey.CTRL)
        assertEquals(ModifierState.State.LATCHED, modifierState.ctrlState)
        assertTrue(modifierState.isCtrlActive)
        assertEquals(1, sentEvents.size)
        assertEquals(0x1D, sentEvents[0].first.code)
        assertTrue(sentEvents[0].second) // isDown = true

        // Dispatch a non-modifier key (e.g. 'C')
        modifierState.onNonModifierKeyDispatched()
        assertEquals(ModifierState.State.OFF, modifierState.ctrlState)
        assertFalse(modifierState.isCtrlActive)
        assertEquals(2, sentEvents.size)
        assertEquals(0x1D, sentEvents[1].first.code)
        assertFalse(sentEvents[1].second) // isDown = false
    }

    @Test
    fun testLockingPersistsAcrossNonModifierKey() {
        // Double-tap Ctrl: OFF -> LATCHED -> LOCKED
        modifierState.toggleModifier(ModifierKey.CTRL)
        modifierState.toggleModifier(ModifierKey.CTRL)
        assertEquals(ModifierState.State.LOCKED, modifierState.ctrlState)
        assertTrue(modifierState.isCtrlActive)

        // Non-modifier key dispatch should NOT release locked modifier
        modifierState.onNonModifierKeyDispatched()
        assertEquals(ModifierState.State.LOCKED, modifierState.ctrlState)
        assertTrue(modifierState.isCtrlActive)
        assertEquals(1, sentEvents.size) // Only initial down event sent

        // Third toggle returns to OFF and sends release
        modifierState.toggleModifier(ModifierKey.CTRL)
        assertEquals(ModifierState.State.OFF, modifierState.ctrlState)
        assertFalse(modifierState.isCtrlActive)
        assertEquals(2, sentEvents.size)
        assertFalse(sentEvents[1].second) // isDown = false
    }

    @Test
    fun testAllModifiersToggleCycle() {
        val modifiers = listOf(
            ModifierKey.CTRL to 0x1D,
            ModifierKey.ALT to 0x38,
            ModifierKey.SHIFT to 0x2A,
            ModifierKey.SUPER to 0x5B
        )

        for ((mod, expectedScancode) in modifiers) {
            sentEvents.clear()
            // 1. OFF -> LATCHED
            modifierState.toggleModifier(mod)
            assertEquals(1, sentEvents.size)
            assertEquals(expectedScancode, sentEvents[0].first.code)
            assertTrue(sentEvents[0].second)

            // 2. LATCHED -> LOCKED
            modifierState.toggleModifier(mod)
            assertEquals(1, sentEvents.size) // No extra event

            // 3. LOCKED -> OFF
            modifierState.toggleModifier(mod)
            assertEquals(2, sentEvents.size)
            assertEquals(expectedScancode, sentEvents[1].first.code)
            assertFalse(sentEvents[1].second) // Key up
        }
    }

    @Test
    fun testReleaseAllModifiersGuaranteedCleanup() {
        // Engage all 4 modifiers in various states
        modifierState.toggleModifier(ModifierKey.CTRL) // Latched
        modifierState.toggleModifier(ModifierKey.ALT)  // Latched
        modifierState.toggleModifier(ModifierKey.ALT)  // Locked
        modifierState.toggleModifier(ModifierKey.SHIFT) // Latched
        modifierState.toggleModifier(ModifierKey.SUPER) // Latched
        modifierState.toggleModifier(ModifierKey.SUPER) // Locked

        assertTrue(modifierState.isCtrlActive)
        assertTrue(modifierState.isAltActive)
        assertTrue(modifierState.isShiftActive)
        assertTrue(modifierState.isSuperActive)

        val eventsBeforeRelease = sentEvents.size
        // Unconditional release
        modifierState.releaseAllModifiers()

        assertEquals(ModifierState.State.OFF, modifierState.ctrlState)
        assertEquals(ModifierState.State.OFF, modifierState.altState)
        assertEquals(ModifierState.State.OFF, modifierState.shiftState)
        assertEquals(ModifierState.State.OFF, modifierState.superState)

        // 4 release events should have been dispatched
        val newEvents = sentEvents.subList(eventsBeforeRelease, sentEvents.size)
        assertEquals(4, newEvents.size)
        assertTrue(newEvents.all { !it.second }) // All must be key up (isDown = false)
    }
}
