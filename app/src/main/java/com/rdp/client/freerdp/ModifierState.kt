package com.rdp.client.freerdp

/**
 * Tracks modifier keys state and provides guaranteed release logic.
 */
class ModifierState(
    private val sendKeyAction: (scancode: RdpScancode, isDown: Boolean) -> Unit
) {
    enum class State {
        OFF,
        LATCHED,
        LOCKED
    }

    var ctrlState: State = State.OFF
        private set
    var altState: State = State.OFF
        private set
    var shiftState: State = State.OFF
        private set
    var superState: State = State.OFF
        private set

    // Hardware scancodes for canonical modifiers
    private val scancodeCtrlLeft = RdpScancode(0x1D, isExtended = false)
    private val scancodeAltLeft = RdpScancode(0x38, isExtended = false)
    private val scancodeShiftLeft = RdpScancode(0x2A, isExtended = false)
    private val scancodeSuperLeft = RdpScancode(0x5B, isExtended = true)

    val isCtrlActive: Boolean get() = ctrlState != State.OFF
    val isAltActive: Boolean get() = altState != State.OFF
    val isShiftActive: Boolean get() = shiftState != State.OFF
    val isSuperActive: Boolean get() = superState != State.OFF

    var isInputEnabledProvider: () -> Boolean = { true }

    /**
     * Toggles modifier state (OFF -> LATCHED -> LOCKED -> OFF).
     */
    fun toggleModifier(modifier: ModifierKey) {
        if (!isInputEnabledProvider()) return
        when (modifier) {
            ModifierKey.CTRL -> ctrlState = advanceState(ctrlState, scancodeCtrlLeft)
            ModifierKey.ALT -> altState = advanceState(altState, scancodeAltLeft)
            ModifierKey.SHIFT -> shiftState = advanceState(shiftState, scancodeShiftLeft)
            ModifierKey.SUPER -> superState = advanceState(superState, scancodeSuperLeft)
        }
    }

    private fun advanceState(current: State, scancode: RdpScancode): State {
        return when (current) {
            State.OFF -> {
                sendKeyAction(scancode, true)
                State.LATCHED
            }
            State.LATCHED -> State.LOCKED
            State.LOCKED -> {
                sendKeyAction(scancode, false)
                State.OFF
            }
        }
    }

    /**
     * Called after a non-modifier key (e.g. 'C', 'V', 'R') has been pressed.
     * Releases any latched modifiers while preserving locked modifiers.
     */
    fun onNonModifierKeyDispatched() {
        if (ctrlState == State.LATCHED) {
            sendKeyAction(scancodeCtrlLeft, false)
            ctrlState = State.OFF
        }
        if (altState == State.LATCHED) {
            sendKeyAction(scancodeAltLeft, false)
            altState = State.OFF
        }
        if (shiftState == State.LATCHED) {
            sendKeyAction(scancodeShiftLeft, false)
            shiftState = State.OFF
        }
        if (superState == State.LATCHED) {
            sendKeyAction(scancodeSuperLeft, false)
            superState = State.OFF
        }
    }

    /**
     * Guaranteed release of ALL held modifiers.
     * MUST be invoked on onPause, disconnect, or focus loss.
     */
    fun releaseAllModifiers() {
        if (isCtrlActive) {
            sendKeyAction(scancodeCtrlLeft, false)
            ctrlState = State.OFF
        }
        if (isAltActive) {
            sendKeyAction(scancodeAltLeft, false)
            altState = State.OFF
        }
        if (isShiftActive) {
            sendKeyAction(scancodeShiftLeft, false)
            shiftState = State.OFF
        }
        if (isSuperActive) {
            sendKeyAction(scancodeSuperLeft, false)
            superState = State.OFF
        }
    }
}

enum class ModifierKey {
    CTRL,
    ALT,
    SHIFT,
    SUPER
}
