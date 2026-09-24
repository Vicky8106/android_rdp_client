package com.freerdp.client.ui.session

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.freerdp.feature.session.MacroAction
import com.freerdp.feature.session.keyboard.KeyboardTimingManager
import com.freerdp.feature.session.keyboard.ScancodeTranslator
import com.freerdp.feature.session.modifier.LatchState
import com.freerdp.feature.session.modifier.ModifierKey
import com.freerdp.feature.session.modifier.ModifierStateMachine
import kotlinx.coroutines.delay

/**
 * State holder for the Virtual Keys bar.
 * Connects directly to [ModifierStateMachine] and [KeyboardTimingManager] if supplied,
 * or operates stand-alone for previews and testing.
 */
class VirtualKeysState(
    val modifierStateMachine: ModifierStateMachine? = null,
    val keyboardTimingManager: KeyboardTimingManager? = null,
    initialVisible: Boolean = true,
    initialFnExpanded: Boolean = false,
    initialMouseActive: Boolean = false
) {
    var isVisible by mutableStateOf(initialVisible)
    var isFnExpanded by mutableStateOf(initialFnExpanded)
    var isMouseActive by mutableStateOf(initialMouseActive)

    private val localModifierStates = mutableStateMapOf<ModifierKey, LatchState>()

    fun getModifierState(key: ModifierKey): LatchState {
        return modifierStateMachine?.getModifierState(key)
            ?: localModifierStates[key]
            ?: LatchState.INACTIVE
    }

    fun onModifierTap(key: ModifierKey) {
        if (modifierStateMachine != null) {
            modifierStateMachine.onModifierKeyTapped(key)
        } else {
            val current = getModifierState(key)
            val next = when (current) {
                LatchState.INACTIVE -> LatchState.LATCHED
                LatchState.LATCHED -> LatchState.LOCKED
                LatchState.LOCKED -> LatchState.INACTIVE
            }
            localModifierStates[key] = next
        }
    }

    fun onModifierLongPress(key: ModifierKey) {
        if (modifierStateMachine != null) {
            modifierStateMachine.onModifierKeyLongPressed(key)
        } else {
            val current = getModifierState(key)
            val next = when (current) {
                LatchState.INACTIVE -> LatchState.LOCKED
                LatchState.LATCHED -> LatchState.LOCKED
                LatchState.LOCKED -> LatchState.INACTIVE
            }
            localModifierStates[key] = next
        }
    }

    fun onDesktopKeyClick(key: ModifierKey) {
        if (modifierStateMachine != null) {
            modifierStateMachine.onSpecialKeyTapped(key)
        } else if (keyboardTimingManager != null) {
            val sc = ScancodeTranslator.getScancodeForModifierKey(key)
            keyboardTimingManager.sendKeyPressWithHold(sc.vkCode, isExtended = sc.isExtended)
        }
    }

    fun onFunctionKeyClick(fnIndex: Int) {
        val modKey = when (fnIndex) {
            1 -> ModifierKey.F1
            2 -> ModifierKey.F2
            3 -> ModifierKey.F3
            4 -> ModifierKey.F4
            5 -> ModifierKey.F5
            6 -> ModifierKey.F6
            7 -> ModifierKey.F7
            8 -> ModifierKey.F8
            9 -> ModifierKey.F9
            10 -> ModifierKey.F10
            11 -> ModifierKey.F11
            12 -> ModifierKey.F12
            else -> ModifierKey.F1
        }
        if (modifierStateMachine != null) {
            modifierStateMachine.onSpecialKeyTapped(modKey)
        } else if (keyboardTimingManager != null) {
            val sc = ScancodeTranslator.getScancodeForModifierKey(modKey)
            keyboardTimingManager.sendKeyPressWithHold(sc.vkCode, isExtended = sc.isExtended)
        }
    }

    fun toggleFn() {
        isFnExpanded = !isFnExpanded
    }

    fun toggleMouse() {
        isMouseActive = !isMouseActive
    }
}

/**
 * Modern Jetpack Compose Material 3 Virtual Keys Overlay.
 * Designed with full RealVNC desktop ergonomics:
 * - Material 3 Surface (alpha = 0.80f, rounded top corners 16dp, tonal elevation 8dp)
 * - Collapsible/expandable Fn strip (F1–F12) animated with slide/fade
 * - Tri-state sticky modifiers: Ctrl, Alt, Shift, Super/Windows (Unlatched, Latched on tap, Locked on long press)
 * - Desktop keys: Esc, Tab, Delete, Caps Lock, Home, End, PgUp, PgDn
 * - Inverted-T arrow cluster (Up centered above Down, flanked by Left and Right)
 * - Enlarged scroll buttons with hold-to-repeat (200ms initial delay, 50ms interval)
 */
@Composable
fun VirtualKeysOverlay(
    state: VirtualKeysState,
    modifier: Modifier = Modifier,
    onModifierTap: ((ModifierKey) -> Unit)? = null,
    onModifierLongPress: ((ModifierKey) -> Unit)? = null,
    onToggleKeyboard: () -> Unit = {},
    onToggleMouse: () -> Unit = { state.toggleMouse() },
    onScrollUp: () -> Unit = {},
    onScrollDown: () -> Unit = {},
    onMacro: ((MacroAction) -> Unit)? = null,
    onClose: () -> Unit = { state.isVisible = false }
) {
    // If observing state machine's flow, collect updates
    val modifierStatesFromFsm = state.modifierStateMachine?.statesFlow?.collectAsState()?.value ?: emptyMap()

    VirtualKeysBar(
        modifier = modifier,
        isVisible = state.isVisible,
        isFnExpanded = state.isFnExpanded,
        isMouseActive = state.isMouseActive,
        modifierStates = modifierStatesFromFsm.ifEmpty {
            mapOf(
                ModifierKey.CTRL to state.getModifierState(ModifierKey.CTRL),
                ModifierKey.ALT to state.getModifierState(ModifierKey.ALT),
                ModifierKey.SHIFT to state.getModifierState(ModifierKey.SHIFT),
                ModifierKey.WIN to state.getModifierState(ModifierKey.WIN),
                ModifierKey.CAPS_LOCK to state.getModifierState(ModifierKey.CAPS_LOCK)
            )
        },
        onModifierTap = { onModifierTap?.invoke(it) ?: state.onModifierTap(it) },
        onModifierLongPress = { onModifierLongPress?.invoke(it) ?: state.onModifierLongPress(it) },
        onKeyClick = { state.onDesktopKeyClick(it) },
        onFunctionKeyClick = { state.onFunctionKeyClick(it) },
        onToggleFn = { state.toggleFn() },
        onToggleKeyboard = onToggleKeyboard,
        onToggleMouse = onToggleMouse,
        onScrollUp = onScrollUp,
        onScrollDown = onScrollDown,
        onMacro = onMacro,
        onClose = onClose
    )
}

/**
 * Pure Composable Virtual Keys Bar.
 */
@Composable
fun VirtualKeysBar(
    modifier: Modifier = Modifier,
    isVisible: Boolean = true,
    isFnExpanded: Boolean = false,
    isMouseActive: Boolean = false,
    modifierStates: Map<ModifierKey, LatchState> = emptyMap(),
    onModifierTap: (ModifierKey) -> Unit = {},
    onModifierLongPress: (ModifierKey) -> Unit = {},
    onKeyClick: (ModifierKey) -> Unit = {},
    onFunctionKeyClick: (Int) -> Unit = {},
    onToggleFn: () -> Unit = {},
    onToggleKeyboard: () -> Unit = {},
    onToggleMouse: () -> Unit = {},
    onScrollUp: () -> Unit = {},
    onScrollDown: () -> Unit = {},
    onMacro: ((MacroAction) -> Unit)? = null,
    onClose: () -> Unit = {}
) {
    if (isVisible) {
        Surface(
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 8.dp, bottomEnd = 8.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.80f),
            tonalElevation = 8.dp,
            shadowElevation = 10.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                // 1. Expandable Function Keys Strip (F1 - F12)
                AnimatedVisibility(
                    visible = isFnExpanded,
                    enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut()
                ) {
                    FunctionKeysStrip(onFunctionKeyClick = onFunctionKeyClick)
                }

                // 2. Primary RealVNC Controls & Navigation Bar
                MainControlsBar(
                    isFnExpanded = isFnExpanded,
                    isMouseActive = isMouseActive,
                    modifierStates = modifierStates,
                    onModifierTap = onModifierTap,
                    onModifierLongPress = onModifierLongPress,
                    onKeyClick = onKeyClick,
                    onToggleFn = onToggleFn,
                    onToggleKeyboard = onToggleKeyboard,
                    onToggleMouse = onToggleMouse,
                    onScrollUp = onScrollUp,
                    onScrollDown = onScrollDown,
                    onMacro = onMacro,
                    onClose = onClose
                )
            }
        }
    }
}

@Composable
fun FunctionKeysStrip(
    onFunctionKeyClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Tag badge: "FN"
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.padding(end = 2.dp)
        ) {
            Text(
                text = "FN",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
            )
        }

        // Group 1: F1 - F4
        for (i in 1..4) {
            FunctionKeyButton("F$i", i, onFunctionKeyClick)
        }

        Spacer(modifier = Modifier.width(6.dp))

        // Group 2: F5 - F8
        for (i in 5..8) {
            FunctionKeyButton("F$i", i, onFunctionKeyClick)
        }

        Spacer(modifier = Modifier.width(6.dp))

        // Group 3: F9 - F12
        for (i in 9..12) {
            FunctionKeyButton("F$i", i, onFunctionKeyClick)
        }
    }
}

@Composable
private fun FunctionKeyButton(
    label: String,
    fnIndex: Int,
    onClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = { onClick(fnIndex) },
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.70f),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .height(32.dp)
            .defaultMinSize(minWidth = 42.dp)
            .semantics { contentDescription = "Key $label" }
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
fun MainControlsBar(
    isFnExpanded: Boolean,
    isMouseActive: Boolean,
    modifierStates: Map<ModifierKey, LatchState>,
    onModifierTap: (ModifierKey) -> Unit,
    onModifierLongPress: (ModifierKey) -> Unit,
    onKeyClick: (ModifierKey) -> Unit,
    onToggleFn: () -> Unit,
    onToggleKeyboard: () -> Unit,
    onToggleMouse: () -> Unit,
    onScrollUp: () -> Unit,
    onScrollDown: () -> Unit,
    onClose: () -> Unit,
    onMacro: ((MacroAction) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // --- ZONE 1: Quick Action / Mode Switchers (Keyboard, Mouse, Fn) ---
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Keyboard toggle
            Surface(
                onClick = onToggleKeyboard,
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.70f),
                modifier = Modifier
                    .size(width = 38.dp, height = 34.dp)
                    .semantics { contentDescription = "Toggle Keyboard" }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Keyboard,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Mouse toggle (RealVNC style)
            Surface(
                onClick = onToggleMouse,
                shape = RoundedCornerShape(8.dp),
                color = if (isMouseActive) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.70f)
                },
                border = if (isMouseActive) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                modifier = Modifier
                    .size(width = 38.dp, height = 34.dp)
                    .semantics { contentDescription = "Toggle Mouse Controls" }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Mouse,
                        contentDescription = null,
                        tint = if (isMouseActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // Fn Toggle Button (spans 2 rows, height = 72dp)
        Surface(
            onClick = onToggleFn,
            shape = RoundedCornerShape(8.dp),
            color = if (isFnExpanded) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.70f)
            },
            modifier = Modifier
                .size(width = 36.dp, height = 72.dp)
                .semantics { contentDescription = "Toggle Fn" }
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "Fn",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isFnExpanded) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
                )
                if (isFnExpanded) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Box(
                        modifier = Modifier
                            .size(4.dp)
                            .background(MaterialTheme.colorScheme.onPrimary, CircleShape)
                    )
                }
            }
        }

        BarDivider()

        // --- ZONE 2: Essential Desktop Controls (2 Rows) ---
        // Row 1: Esc, Tab, Win, Del
        // Row 2: Ctrl, Alt, Shift, Caps
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Row 1: Esc, Tab, Win, Del
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                StandardKeyButton("Esc", ModifierKey.ESC, onKeyClick, minWidth = 44.dp)
                StandardKeyButton("Tab", ModifierKey.TAB, onKeyClick, minWidth = 44.dp)
                ModifierKeyButton(
                    key = ModifierKey.WIN,
                    latchState = modifierStates[ModifierKey.WIN] ?: LatchState.INACTIVE,
                    onTap = { onModifierTap(ModifierKey.WIN) },
                    onLongPress = { onModifierLongPress(ModifierKey.WIN) },
                    minWidth = 52.dp
                )
                DeleteKeyButton(onKeyClick = { onKeyClick(ModifierKey.DEL) }, minWidth = 46.dp)
            }

            // Row 2: Ctrl, Alt, Shift, Caps
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ModifierKeyButton(
                    key = ModifierKey.CTRL,
                    latchState = modifierStates[ModifierKey.CTRL] ?: LatchState.INACTIVE,
                    onTap = { onModifierTap(ModifierKey.CTRL) },
                    onLongPress = { onModifierLongPress(ModifierKey.CTRL) },
                    minWidth = 44.dp
                )
                ModifierKeyButton(
                    key = ModifierKey.ALT,
                    latchState = modifierStates[ModifierKey.ALT] ?: LatchState.INACTIVE,
                    onTap = { onModifierTap(ModifierKey.ALT) },
                    onLongPress = { onModifierLongPress(ModifierKey.ALT) },
                    minWidth = 44.dp
                )
                ModifierKeyButton(
                    key = ModifierKey.SHIFT,
                    latchState = modifierStates[ModifierKey.SHIFT] ?: LatchState.INACTIVE,
                    onTap = { onModifierTap(ModifierKey.SHIFT) },
                    onLongPress = { onModifierLongPress(ModifierKey.SHIFT) },
                    minWidth = 52.dp
                )
                StandardKeyButton("Caps", ModifierKey.CAPS_LOCK, onKeyClick, minWidth = 46.dp)
            }
        }

        BarDivider()

        // --- ZONE 3: RealVNC Aligned Navigation Cluster ---
        // Left Column: Home & End
        // Center Column: Inverted-T Arrow Cluster (Up centered above Down, flanked by Left & Right)
        // Right Column: PgUp & PgDn
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Left Column: Home (top) & End (bottom)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                StandardKeyButton("Home", ModifierKey.HOME, onKeyClick, minWidth = 46.dp)
                StandardKeyButton("End", ModifierKey.END, onKeyClick, minWidth = 46.dp)
            }

            // Center Column: Inverted-T Arrow Pad
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top Row: Up Arrow centered above Down Arrow
                ArrowKeyButton(
                    key = ModifierKey.ARROW_UP,
                    icon = Icons.Default.KeyboardArrowUp,
                    description = "Key Up",
                    onClick = onKeyClick,
                    width = 54.dp,
                    height = 34.dp
                )

                // Bottom Row: Left, Down, Right
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ArrowKeyButton(
                        key = ModifierKey.ARROW_LEFT,
                        icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        description = "Key Left",
                        onClick = onKeyClick,
                        width = 48.dp,
                        height = 34.dp
                    )
                    ArrowKeyButton(
                        key = ModifierKey.ARROW_DOWN,
                        icon = Icons.Default.KeyboardArrowDown,
                        description = "Key Down",
                        onClick = onKeyClick,
                        width = 54.dp,
                        height = 34.dp
                    )
                    ArrowKeyButton(
                        key = ModifierKey.ARROW_RIGHT,
                        icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        description = "Key Right",
                        onClick = onKeyClick,
                        width = 48.dp,
                        height = 34.dp
                    )
                }
            }

            // Right Column: PgUp (top) & PgDn (bottom)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                StandardKeyButton("PgUp", ModifierKey.PAGE_UP, onKeyClick, minWidth = 46.dp)
                StandardKeyButton("PgDn", ModifierKey.PAGE_DOWN, onKeyClick, minWidth = 46.dp)
            }
        }

        BarDivider()

        // --- ZONE 4: Big Scroll Up & Scroll Down Controls ---
        // Generously sized buttons with hold-to-repeat acceleration (200ms initial delay, 50ms interval)
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            ScrollPadButton(
                icon = Icons.Default.KeyboardArrowUp,
                contentDescription = "Scroll Up",
                onScroll = onScrollUp,
                width = 54.dp,
                height = 34.dp
            )
            ScrollPadButton(
                icon = Icons.Default.KeyboardArrowDown,
                contentDescription = "Scroll Down",
                onScroll = onScrollDown,
                width = 54.dp,
                height = 34.dp
            )
        }

        BarDivider()

        // --- ZONE 5: Shortcut Macros (Ctrl+Alt+Del, Alt+Tab, Ctrl+C, Ctrl+V) ---
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                MacroButton(label = "Ctrl+Alt+Del", onClick = { onMacro?.invoke(MacroAction.CTRL_ALT_DEL) })
                MacroButton(label = "Alt+Tab", onClick = { onMacro?.invoke(MacroAction.ALT_TAB) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                MacroButton(label = "Ctrl+C", onClick = { onMacro?.invoke(MacroAction.CTRL_C) })
                MacroButton(label = "Ctrl+V", onClick = { onMacro?.invoke(MacroAction.CTRL_V) })
            }
        }

        BarDivider()

        // --- ZONE 6: Dismiss / Close Button ---
        Surface(
            onClick = onClose,
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.65f),
            modifier = Modifier
                .size(width = 34.dp, height = 72.dp)
                .semantics { contentDescription = "Close Virtual Keys" }
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
fun BarDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(64.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    )
}

@Composable
fun StandardKeyButton(
    label: String,
    key: ModifierKey,
    onKeyClick: (ModifierKey) -> Unit,
    minWidth: Dp = 44.dp,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = { onKeyClick(key) },
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.70f),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .height(34.dp)
            .defaultMinSize(minWidth = minWidth)
            .semantics { contentDescription = "Key $label" }
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ModifierKeyButton(
    key: ModifierKey,
    latchState: LatchState,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    minWidth: Dp = 44.dp,
    modifier: Modifier = Modifier
) {
    val isLatched = latchState == LatchState.LATCHED
    val isLocked = latchState == LatchState.LOCKED

    val label = when (key) {
        ModifierKey.CTRL -> "Ctrl"
        ModifierKey.ALT -> "Alt"
        ModifierKey.SHIFT -> "Shift"
        ModifierKey.WIN -> "Win"
        ModifierKey.CAPS_LOCK -> "Caps"
        else -> key.name
    }

    val stateWord = when (latchState) {
        LatchState.LOCKED -> "locked"
        LatchState.LATCHED -> "latched"
        LatchState.INACTIVE -> "off"
    }
    val contentDesc = "$label key, $stateWord"

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = when {
            isLocked -> MaterialTheme.colorScheme.primary
            isLatched -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.70f)
        },
        contentColor = when {
            isLocked -> MaterialTheme.colorScheme.onPrimary
            isLatched -> MaterialTheme.colorScheme.onPrimaryContainer
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        border = if (isLatched && !isLocked) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = modifier
            .height(48.dp)
            .combinedClickable(onClick = onTap, onLongClick = onLongPress)
            .semantics { contentDescription = contentDesc }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge
            )
            if (isLocked) {
                Spacer(modifier = Modifier.width(3.dp))
                Box(
                    modifier = Modifier
                        .size(4.dp)
                        .background(MaterialTheme.colorScheme.onPrimary, CircleShape)
                )
            }
        }
    }
}

@Composable
fun DeleteKeyButton(
    onKeyClick: () -> Unit,
    minWidth: Dp = 46.dp,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onKeyClick,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f),
        contentColor = MaterialTheme.colorScheme.error,
        modifier = modifier
            .height(34.dp)
            .defaultMinSize(minWidth = minWidth)
            .semantics { contentDescription = "Key Del" }
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Del",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun ArrowKeyButton(
    key: ModifierKey,
    icon: ImageVector,
    description: String,
    onClick: (ModifierKey) -> Unit,
    width: Dp,
    height: Dp,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = { onClick(key) },
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.70f),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .size(width = width, height = height)
            .semantics { contentDescription = description }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
fun ScrollPadButton(
    icon: ImageVector,
    contentDescription: String,
    onScroll: () -> Unit,
    width: Dp,
    height: Dp,
    modifier: Modifier = Modifier
) {
    var isHolding by remember { mutableStateOf(false) }

    LaunchedEffect(isHolding) {
        if (isHolding) {
            onScroll()
            delay(200L) // Initial delay
            while (isHolding) {
                onScroll()
                delay(50L) // Accelerated repeat interval (20 events/sec)
            }
        }
    }

    Surface(
        onClick = onScroll,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f),
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = modifier
            .size(width = width, height = height)
            .semantics { this.contentDescription = contentDescription }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isHolding = true
                        tryAwaitRelease()
                        isHolding = false
                    }
                )
            }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
fun MacroButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier
            .height(34.dp)
            .defaultMinSize(minWidth = 56.dp)
            .semantics { contentDescription = "Send $label shortcut" }
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

