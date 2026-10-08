package com.rdp.client.ui.session.compose

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rdp.client.freerdp.ModifierKey
import com.rdp.client.freerdp.ModifierState
import com.rdp.client.freerdp.RdpPointerFlags
import com.rdp.client.freerdp.RdpScancode
import com.rdp.client.utils.KeyPacer
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Material 3 Translucent Floating Virtual Keys Overlay.
 * Replicates AVNC desktop ergonomics with RealVNC Inverted-T layout,
 * expandable Fn strip, ModifierState binding, and KeyPacer integration.
 */
@Composable
fun VirtualKeysOverlay(
    isVisible: Boolean,
    keyPacer: KeyPacer,
    modifierState: ModifierState,
    onScroll: (direction: RdpPointerFlags.ScrollDirection) -> Unit,
    onToggleKeyboard: () -> Unit,
    onToggleVirtualMouse: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFnExpanded by remember { mutableStateOf(false) }

    // Recomposition trigger for ModifierState updates
    var modifierRecomposeTrigger by remember { mutableIntStateOf(0) }
    fun refreshModifiers() {
        modifierRecomposeTrigger++
    }

    AnimatedVisibility(
        visible = isVisible,
        enter = slideInVertically(initialOffsetY = { it }, animationSpec = tween(250)) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(200)) + fadeOut(),
        modifier = modifier
    ) {
        // Docked to bottom, taking into account IME insets
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.ime)
                .padding(horizontal = 6.dp, vertical = 4.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            Surface(
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 12.dp, bottomEnd = 12.dp),
                color = Color(0xCC1A1C1E), // 80% opacity dark surface
                border = BorderStroke(1.dp, Color(0x33FFFFFF)),
                tonalElevation = 8.dp,
                shadowElevation = 10.dp,
                modifier = Modifier.wrapContentSize()
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // 1. Expandable Function Keys Strip
                    AnimatedVisibility(
                        visible = isFnExpanded,
                        enter = expandVertically(animationSpec = tween(200)) + fadeIn(),
                        exit = shrinkVertically(animationSpec = tween(150)) + fadeOut()
                    ) {
                        FunctionKeysStrip(
                            keyPacer = keyPacer,
                            modifierState = modifierState,
                            onKeySent = { refreshModifiers() }
                        )
                    }

                    Spacer(modifier = Modifier.height(3.dp))

                    // 2. Main Desktop Control Bar
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Zone 1: Mode Switchers
                        ModeSwitcherZone(
                            isFnActive = isFnExpanded,
                            onToggleFn = { isFnExpanded = !isFnExpanded },
                            onToggleKeyboard = onToggleKeyboard,
                            onToggleVirtualMouse = onToggleVirtualMouse
                        )

                        HorizontalDivider(
                            modifier = Modifier
                                .height(72.dp)
                                .width(1.dp),
                            color = Color(0x33FFFFFF)
                        )

                        // Zone 2: Essential Desktop Controls (Esc, Tab, Del, Win, Modifiers)
                        key(modifierRecomposeTrigger) {
                            EssentialDesktopKeysZone(
                                keyPacer = keyPacer,
                                modifierState = modifierState,
                                onModifierToggled = { refreshModifiers() },
                                onKeySent = { refreshModifiers() }
                            )
                        }

                        HorizontalDivider(
                            modifier = Modifier
                                .height(72.dp)
                                .width(1.dp),
                            color = Color(0x33FFFFFF)
                        )

                        // Zone 3: RealVNC Inverted-T Navigation Cluster
                        NavigationClusterZone(
                            keyPacer = keyPacer,
                            modifierState = modifierState,
                            onKeySent = { refreshModifiers() }
                        )

                        HorizontalDivider(
                            modifier = Modifier
                                .height(72.dp)
                                .width(1.dp),
                            color = Color(0x33FFFFFF)
                        )

                        // Zone 4: Enlarged Continuous Scroll Buttons
                        ScrollButtonsZone(onScroll = onScroll)

                        HorizontalDivider(
                            modifier = Modifier
                                .height(72.dp)
                                .width(1.dp),
                            color = Color(0x33FFFFFF)
                        )

                        // Zone 5: Dismiss Overlay Button
                        DismissButton(onDismiss = onDismiss)
                    }
                }
            }
        }
    }
}

/**
 * Expandable Function Keys Strip (F1-F12 grouped in 3 PC clusters).
 */
@Composable
private fun FunctionKeysStrip(
    keyPacer: KeyPacer,
    modifierState: ModifierState,
    onKeySent: () -> Unit
) {
    Row(
        modifier = Modifier
            .padding(bottom = 6.dp)
            .background(Color(0x40000000), RoundedCornerShape(8.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Tag Badge
        Surface(
            shape = RoundedCornerShape(4.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.padding(end = 2.dp)
        ) {
            Text(
                text = "FN",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            )
        }

        // Cluster 1: F1 - F4
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            for (i in 1..4) {
                FnKeyButton("F$i", 0x3A + i, keyPacer, modifierState, onKeySent)
            }
        }

        Spacer(modifier = Modifier.width(6.dp))

        // Cluster 2: F5 - F8
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            for (i in 5..8) {
                FnKeyButton("F$i", 0x3A + i, keyPacer, modifierState, onKeySent)
            }
        }

        Spacer(modifier = Modifier.width(6.dp))

        // Cluster 3: F9 - F12
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            FnKeyButton("F9", 0x43, keyPacer, modifierState, onKeySent)
            FnKeyButton("F10", 0x44, keyPacer, modifierState, onKeySent)
            FnKeyButton("F11", 0x57, keyPacer, modifierState, onKeySent)
            FnKeyButton("F12", 0x58, keyPacer, modifierState, onKeySent)
        }
    }
}

@Composable
private fun FnKeyButton(
    label: String,
    scancode: Int,
    keyPacer: KeyPacer,
    modifierState: ModifierState,
    onKeySent: () -> Unit
) {
    KeyButton(
        text = label,
        width = 40.dp,
        height = 32.dp,
        onClick = {
            keyPacer.enqueueKey(RdpScancode(scancode, isExtended = false)) {
                modifierState.onNonModifierKeyDispatched()
            }
            onKeySent()
        }
    )
}

/**
 * Zone 1: Mode Switchers (IME toggle, Virtual Mouse toggle, Fn strip toggle).
 */
@Composable
private fun ModeSwitcherZone(
    isFnActive: Boolean,
    onToggleFn: () -> Unit,
    onToggleKeyboard: () -> Unit,
    onToggleVirtualMouse: () -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            // Software Keyboard Toggle
            IconButtonKey(
                icon = Icons.Default.Keyboard,
                width = 36.dp,
                height = 34.dp,
                onClick = onToggleKeyboard
            )
            // Virtual Mouse Overlay Toggle
            IconButtonKey(
                icon = Icons.Default.Mouse,
                width = 36.dp,
                height = 34.dp,
                onClick = onToggleVirtualMouse
            )
        }

        // [Fn] Toggle Button
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = if (isFnActive) MaterialTheme.colorScheme.primary else Color(0x33FFFFFF),
            border = BorderStroke(1.dp, if (isFnActive) MaterialTheme.colorScheme.primary else Color(0x4DFFFFFF)),
            modifier = Modifier
                .width(75.dp)
                .height(34.dp)
                .clip(RoundedCornerShape(6.dp))
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { onToggleFn() })
                }
        ) {
            Box(contentAlignment = Alignment.Center) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "[Fn]",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isFnActive) MaterialTheme.colorScheme.onPrimary else Color.White
                    )
                    if (isFnActive) {
                        Spacer(modifier = Modifier.width(3.dp))
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(MaterialTheme.colorScheme.onPrimary, CircleShape)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Zone 2: Essential Desktop Keys & Modifier Keys Row.
 */
@Composable
private fun EssentialDesktopKeysZone(
    keyPacer: KeyPacer,
    modifierState: ModifierState,
    onModifierToggled: () -> Unit,
    onKeySent: () -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Row 1: Esc, Tab, Win (Tap), Forward Del
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            KeyButton("Esc", width = 42.dp, height = 34.dp, onClick = {
                keyPacer.enqueueKey(RdpScancode(0x01, false)) {
                    modifierState.onNonModifierKeyDispatched()
                }
                onKeySent()
            })
            KeyButton("Tab", width = 42.dp, height = 34.dp, onClick = {
                keyPacer.enqueueKey(RdpScancode(0x0F, false)) {
                    modifierState.onNonModifierKeyDispatched()
                }
                onKeySent()
            })
            // Dedicated Win Key (Tap sends Win, long-press latches Win modifier)
            KeyButton(
                text = "Win",
                width = 50.dp,
                height = 34.dp,
                onClick = {
                    keyPacer.enqueueKey(RdpScancode(0x5B, true)) {
                        modifierState.onNonModifierKeyDispatched()
                    }
                    onKeySent()
                },
                onLongClick = {
                    modifierState.toggleModifier(ModifierKey.SUPER)
                    onModifierToggled()
                }
            )
            KeyButton(
                text = "Del",
                width = 46.dp,
                height = 34.dp,
                onClick = {
                    keyPacer.enqueueKey(RdpScancode(0x53, true)) {
                        modifierState.onNonModifierKeyDispatched()
                    }
                    onKeySent()
                }
            )
        }

        // Row 2: Ctrl, Alt, Shift, Super Modifier Keys Row
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            ModifierKeyButton("Ctrl", modifierState.ctrlState, width = 42.dp, height = 34.dp) {
                modifierState.toggleModifier(ModifierKey.CTRL)
                onModifierToggled()
            }
            ModifierKeyButton("Alt", modifierState.altState, width = 42.dp, height = 34.dp) {
                modifierState.toggleModifier(ModifierKey.ALT)
                onModifierToggled()
            }
            ModifierKeyButton("Shift", modifierState.shiftState, width = 50.dp, height = 34.dp) {
                modifierState.toggleModifier(ModifierKey.SHIFT)
                onModifierToggled()
            }
            ModifierKeyButton("Win", modifierState.superState, width = 46.dp, height = 34.dp) {
                modifierState.toggleModifier(ModifierKey.SUPER)
                onModifierToggled()
            }
        }
    }
}

/**
 * Zone 3: RealVNC Inverted-T Navigation Pad.
 */
@Composable
private fun NavigationClusterZone(
    keyPacer: KeyPacer,
    modifierState: ModifierState,
    onKeySent: () -> Unit
) {
    fun sendNavKey(scancode: Int) {
        keyPacer.enqueueKey(RdpScancode(scancode, isExtended = true)) {
            modifierState.onNonModifierKeyDispatched()
        }
        onKeySent()
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Row 1: Home, Up, PgUp
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
            RepeatableKeyButton("Home", width = 46.dp, height = 34.dp, onRepeat = { sendNavKey(0x47) })
            Spacer(modifier = Modifier.width(48.dp))
            RepeatableIconButtonKey(Icons.Default.KeyboardArrowUp, width = 54.dp, height = 34.dp, onRepeat = { sendNavKey(0x48) })
            Spacer(modifier = Modifier.width(48.dp))
            RepeatableKeyButton("PgUp", width = 46.dp, height = 34.dp, onRepeat = { sendNavKey(0x49) })
        }

        // Row 2: End, Left, Down, Right, PgDn
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
            RepeatableKeyButton("End", width = 46.dp, height = 34.dp, onRepeat = { sendNavKey(0x4F) })
            RepeatableIconButtonKey(Icons.Default.KeyboardArrowLeft, width = 48.dp, height = 34.dp, onRepeat = { sendNavKey(0x4B) })
            RepeatableIconButtonKey(Icons.Default.KeyboardArrowDown, width = 54.dp, height = 34.dp, onRepeat = { sendNavKey(0x50) })
            RepeatableIconButtonKey(Icons.Default.KeyboardArrowRight, width = 48.dp, height = 34.dp, onRepeat = { sendNavKey(0x4D) })
            RepeatableKeyButton("PgDn", width = 46.dp, height = 34.dp, onRepeat = { sendNavKey(0x51) })
        }
    }
}

/**
 * Zone 4: Enlarged Continuous Scroll Buttons (54x34dp).
 */
@Composable
private fun ScrollButtonsZone(
    onScroll: (direction: RdpPointerFlags.ScrollDirection) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        RepeatableIconButtonKey(
            icon = Icons.Default.ArrowUpward,
            width = 54.dp,
            height = 34.dp,
            onRepeat = { onScroll(RdpPointerFlags.ScrollDirection.UP) }
        )
        RepeatableIconButtonKey(
            icon = Icons.Default.ArrowDownward,
            width = 54.dp,
            height = 34.dp,
            onRepeat = { onScroll(RdpPointerFlags.ScrollDirection.DOWN) }
        )
    }
}

/**
 * Zone 5: Dismiss Button (36x72dp).
 */
@Composable
private fun DismissButton(onDismiss: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color(0x33E53935), // Translucent red
        border = BorderStroke(1.dp, Color(0x66E53935)),
        modifier = Modifier
            .width(36.dp)
            .height(72.dp)
            .clip(RoundedCornerShape(8.dp))
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onDismiss() })
            }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Close Virtual Keys",
                tint = Color(0xFFFF8A80),
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

// -----------------------------------------------------------------------------
// Core UI Primitives & Key Components
// -----------------------------------------------------------------------------

@Composable
private fun KeyButton(
    text: String,
    width: Dp,
    height: Dp,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = Color(0x33FFFFFF),
        border = BorderStroke(1.dp, Color(0x4DFFFFFF)),
        modifier = Modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(6.dp))
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = { onLongClick?.invoke() }
                )
            }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = text,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White
            )
        }
    }
}

@Composable
private fun ModifierKeyButton(
    label: String,
    state: ModifierState.State,
    width: Dp,
    height: Dp,
    onClick: () -> Unit
) {
    val (containerColor, contentColor, borderStroke) = when (state) {
        ModifierState.State.OFF -> Triple(
            Color(0x33FFFFFF),
            Color.White,
            BorderStroke(1.dp, Color(0x4DFFFFFF))
        )
        ModifierState.State.LATCHED -> Triple(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
            BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
        )
        ModifierState.State.LOCKED -> Triple(
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.onPrimary,
            BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
        )
    }

    Surface(
        shape = RoundedCornerShape(6.dp),
        color = containerColor,
        border = borderStroke,
        modifier = Modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(6.dp))
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onClick() })
            }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = label,
                    fontSize = 12.sp,
                    fontWeight = if (state != ModifierState.State.OFF) FontWeight.Bold else FontWeight.Medium,
                    color = contentColor
                )
                when (state) {
                    ModifierState.State.LATCHED -> {
                        Spacer(modifier = Modifier.width(2.dp))
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .background(contentColor, CircleShape)
                        )
                    }
                    ModifierState.State.LOCKED -> {
                        Spacer(modifier = Modifier.width(2.dp))
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Locked",
                            tint = contentColor,
                            modifier = Modifier.size(10.dp)
                        )
                    }
                    else -> {}
                }
            }
        }
    }
}

@Composable
private fun IconButtonKey(
    icon: ImageVector,
    width: Dp,
    height: Dp,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = Color(0x33FFFFFF),
        border = BorderStroke(1.dp, Color(0x4DFFFFFF)),
        modifier = Modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(6.dp))
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onClick() })
            }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun RepeatableKeyButton(
    text: String,
    width: Dp,
    height: Dp,
    onRepeat: () -> Unit
) {
    var isPressed by remember { mutableStateOf(false) }

    LaunchedEffect(isPressed) {
        if (isPressed) {
            onRepeat()
            delay(400L) // Initial hold delay
            while (isActive && isPressed) {
                onRepeat()
                delay(60L) // Continuous repeat pacing
            }
        }
    }

    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (isPressed) Color(0x66FFFFFF) else Color(0x33FFFFFF),
        border = BorderStroke(1.dp, Color(0x4DFFFFFF)),
        modifier = Modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(6.dp))
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        tryAwaitRelease()
                        isPressed = false
                    }
                )
            }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = text,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White
            )
        }
    }
}

@Composable
private fun RepeatableIconButtonKey(
    icon: ImageVector,
    width: Dp,
    height: Dp,
    onRepeat: () -> Unit
) {
    var isPressed by remember { mutableStateOf(false) }

    LaunchedEffect(isPressed) {
        if (isPressed) {
            onRepeat()
            delay(200L) // Initial scroll/nav delay
            while (isActive && isPressed) {
                onRepeat()
                delay(50L) // High-speed repeat cadence
            }
        }
    }

    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (isPressed) Color(0x66FFFFFF) else Color(0x33FFFFFF),
        border = BorderStroke(1.dp, Color(0x4DFFFFFF)),
        modifier = Modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(6.dp))
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        tryAwaitRelease()
                        isPressed = false
                    }
                )
            }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
