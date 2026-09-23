package com.freerdp.feature.session.modifier

import com.freerdp.core.engine.IRdpEngine
import com.freerdp.feature.session.keyboard.ScancodeTranslator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

enum class ModifierKey {
    CTRL,
    ALT,
    SHIFT,
    WIN,
    ESC,
    TAB,
    DEL,
    INS,
    HOME,
    END,
    PAGE_UP,
    PAGE_DOWN,
    F1,
    F2,
    F3,
    F4,
    F5,
    F6,
    F7,
    F8,
    F9,
    F10,
    F11,
    F12,
    ARROW_LEFT,
    ARROW_UP,
    ARROW_RIGHT,
    ARROW_DOWN,
    ENTER,
    BACKSPACE,
    SPACE
}

enum class LatchState {
    INACTIVE,
    LATCHED,
    LOCKED
}

enum class MacroAction {
    CTRL_ALT_DEL,
    ALT_TAB,
    ALT_F4,
    WIN_D,
    WIN_R,
    CTRL_C,
    CTRL_V
}

class ModifierStateMachine(
    private val rdpEngine: IRdpEngine? = null,
    private val onStateChanged: ((ModifierKey, LatchState) -> Unit)? = null
) {

    private val latchableModifiers = setOf(
        ModifierKey.CTRL,
        ModifierKey.ALT,
        ModifierKey.SHIFT,
        ModifierKey.WIN
    )

    private val modifierStates = ConcurrentHashMap<ModifierKey, LatchState>()
    private val _statesFlow = MutableStateFlow<Map<ModifierKey, LatchState>>(emptyMap())
    val statesFlow: StateFlow<Map<ModifierKey, LatchState>> = _statesFlow.asStateFlow()

    init {
        latchableModifiers.forEach { modifierStates[it] = LatchState.INACTIVE }
        _statesFlow.value = HashMap(modifierStates)
    }

    @Synchronized
    fun onModifierKeyTapped(key: ModifierKey) {
        if (!latchableModifiers.contains(key)) {
            // Non-latching key tapped via this method
            onSpecialKeyTapped(key)
            return
        }

        // Tap semantics of the exact 3-state FSM:
        //   INACTIVE -> LATCHED (single tap, key-down emitted once)
        //   LATCHED  -> LOCKED  (double tap, key stays held)
        //   LOCKED   -> INACTIVE (third tap, key-up emitted)
        val target = when (getModifierState(key)) {
            LatchState.INACTIVE -> LatchState.LATCHED
            LatchState.LATCHED -> LatchState.LOCKED
            LatchState.LOCKED -> LatchState.INACTIVE
        }
        tryTransition(key, target)
    }

    /**
     * Applies a single [LatchState] transition for a latchable modifier
     * ([ModifierKey.CTRL], [ModifierKey.ALT], [ModifierKey.SHIFT], [ModifierKey.WIN]).
     *
     * The only accepted transitions are:
     *  - INACTIVE -> LATCHED  (emits key-down)
     *  - LATCHED  -> LOCKED   (no event; key already held down)
     *  - LATCHED  -> INACTIVE (emits key-up; used by auto-clear after a consumed key)
     *  - LOCKED   -> INACTIVE (emits key-up)
     *
     * Everything else — direct INACTIVE -> LOCKED, LOCKED -> LATCHED, self-loops,
     * and any target for a non-latchable key — is rejected: no state change and
     * no engine event is produced.
     *
     * @return true when the transition was valid and applied, false when rejected.
     */
    @Synchronized
    fun tryTransition(key: ModifierKey, target: LatchState): Boolean {
        if (!latchableModifiers.contains(key)) {
            return false
        }
        val current = getModifierState(key)
        val valid = when (current) {
            LatchState.INACTIVE -> target == LatchState.LATCHED
            LatchState.LATCHED -> target == LatchState.LOCKED || target == LatchState.INACTIVE
            LatchState.LOCKED -> target == LatchState.INACTIVE
        }
        if (!valid) {
            return false
        }

        val scancode = ScancodeTranslator.getScancodeForModifierKey(key).scancode
        when (target) {
            LatchState.LATCHED -> rdpEngine?.sendKeyEvent(scancode, down = true)
            LatchState.INACTIVE -> rdpEngine?.sendKeyEvent(scancode, down = false)
            LatchState.LOCKED -> {
                // Key is already held down in the engine; state-only update.
            }
        }
        modifierStates[key] = target
        notifyStateChange(key, target)
        return true
    }

    @Synchronized
    fun onNonModifierKeyPressed(scancode: Int, isExtended: Boolean = false) {
        if (!ScancodeTranslator.isKnownScancode(scancode)) {
            // Defined fallback for unmapped/invalid Set-1 scancodes: drop the event.
            // Nothing reaches the remote desktop, so no key was consumed and any
            // latched modifiers are intentionally preserved for the next real key.
            return
        }
        rdpEngine?.sendKeyEvent(scancode, down = true)
        rdpEngine?.sendKeyEvent(scancode, down = false)
        releaseLatchedModifiers()
    }

    @Synchronized
    fun onNonModifierKeyPressed(char: Char) {
        val scancodeResult = ScancodeTranslator.fromChar(char)
        if (scancodeResult != null) {
            rdpEngine?.sendKeyEvent(scancodeResult.scancode, down = true)
            rdpEngine?.sendKeyEvent(scancodeResult.scancode, down = false)
        } else {
            rdpEngine?.sendUnicodeKeyEvent(char, down = true)
            rdpEngine?.sendUnicodeKeyEvent(char, down = false)
        }
        releaseLatchedModifiers()
    }

    @Synchronized
    fun onSpecialKeyTapped(key: ModifierKey) {
        val scancodeResult = ScancodeTranslator.getScancodeForModifierKey(key)
        rdpEngine?.sendKeyEvent(scancodeResult.scancode, down = true)
        rdpEngine?.sendKeyEvent(scancodeResult.scancode, down = false)
        releaseLatchedModifiers()
    }

    @Synchronized
    fun triggerMacro(macro: MacroAction) {
        val steps = ScancodeTranslator.getMacroSteps(macro)
        steps.forEach { step ->
            rdpEngine?.sendKeyEvent(step.scancode, down = step.down)
        }
        releaseLatchedModifiers()
    }

    fun getModifierState(key: ModifierKey): LatchState {
        return modifierStates[key] ?: LatchState.INACTIVE
    }

    fun isLatched(key: ModifierKey): Boolean {
        return getModifierState(key) == LatchState.LATCHED
    }

    fun isLocked(key: ModifierKey): Boolean {
        return getModifierState(key) == LatchState.LOCKED
    }

    fun isKeyActive(key: ModifierKey): Boolean {
        val s = getModifierState(key)
        return s == LatchState.LATCHED || s == LatchState.LOCKED
    }

    @Synchronized
    fun resetAll() {
        latchableModifiers.forEach { key ->
            if (getModifierState(key) != LatchState.INACTIVE) {
                tryTransition(key, LatchState.INACTIVE)
            }
        }
    }

    private fun releaseLatchedModifiers() {
        latchableModifiers.forEach { key ->
            if (getModifierState(key) == LatchState.LATCHED) {
                tryTransition(key, LatchState.INACTIVE)
            }
        }
    }

    private fun notifyStateChange(key: ModifierKey, state: LatchState) {
        _statesFlow.value = HashMap(modifierStates)
        onStateChanged?.invoke(key, state)
    }
}
