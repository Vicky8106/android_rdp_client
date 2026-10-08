package com.rdp.client.ui.session.compose

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rdp.client.freerdp.RdpPointerFlags
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

/**
 * Floating Virtual Mouse Overlay matching AVNC parity.
 * Draggable 56dp FAB expanding to 64dp translucent vertical pill with
 * Left/Middle/Right click, Drag-Lock mode, continuous scroll pads, and boundary clamping.
 */
@Composable
fun VirtualMouseOverlay(
    isVisible: Boolean,
    onButtonClick: (RdpPointerFlags.Button) -> Unit,
    onButtonDown: (RdpPointerFlags.Button) -> Unit,
    onButtonUp: (RdpPointerFlags.Button) -> Unit,
    onScroll: (RdpPointerFlags.ScrollDirection) -> Unit,
    modifier: Modifier = Modifier
) {
    if (!isVisible) return

    val density = LocalDensity.current
    val configuration = LocalConfiguration.current

    // Screen dimensions in pixels
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }

    val fabSizePx = with(density) { 56.dp.toPx() }
    val pillWidthPx = with(density) { 64.dp.toPx() }
    val pillHeightPx = with(density) { 310.dp.toPx() }

    // State
    var isExpanded by remember { mutableStateOf(false) }
    var isDragLocked by remember { mutableStateOf(false) }

    // Position offset (initially positioned at bottom right)
    var offset by remember {
        mutableStateOf(
            Offset(
                x = screenWidthPx - fabSizePx - with(density) { 16.dp.toPx() },
                y = screenHeightPx - fabSizePx - with(density) { 96.dp.toPx() }
            )
        )
    }

    // Boundary Clamping function
    fun clampOffset(pos: Offset, currentWidth: Float, currentHeight: Float): Offset {
        val maxX = (screenWidthPx - currentWidth).coerceAtLeast(0f)
        val maxY = (screenHeightPx - currentHeight).coerceAtLeast(0f)
        return Offset(
            x = pos.x.coerceIn(0f, maxX),
            y = pos.y.coerceIn(0f, maxY)
        )
    }

    // Automatically re-clamp when orientation or screen dimensions shift
    LaunchedEffect(screenWidthPx, screenHeightPx, isExpanded) {
        val curW = if (isExpanded) pillWidthPx else fabSizePx
        val curH = if (isExpanded) pillHeightPx else fabSizePx
        offset = clampOffset(offset, curW, curH)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // Outer box ignores passthrough
            }
    ) {
        if (!isExpanded) {
            // -------------------------------------------------------------
            // Collapsed State: 56dp Floating Action Button
            // -------------------------------------------------------------
            val clampedFabOffset = clampOffset(offset, fabSizePx, fabSizePx)

            FloatingActionButton(
                onClick = { isExpanded = true },
                shape = CircleShape,
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f),
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                elevation = FloatingActionButtonDefaults.elevation(8.dp),
                modifier = Modifier
                    .offset { IntOffset(clampedFabOffset.x.roundToInt(), clampedFabOffset.y.roundToInt()) }
                    .size(56.dp)
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            offset = clampOffset(offset + dragAmount, fabSizePx, fabSizePx)
                        }
                    }
            ) {
                Icon(
                    imageVector = Icons.Default.Mouse,
                    contentDescription = "Expand Virtual Mouse",
                    modifier = Modifier.size(28.dp)
                )
            }
        } else {
            // -------------------------------------------------------------
            // Expanded State: 64dp Translucent Vertical Mouse Pill
            // -------------------------------------------------------------
            val clampedPillOffset = clampOffset(offset, pillWidthPx, pillHeightPx)

            Surface(
                shape = RoundedCornerShape(26.dp),
                color = Color(0xCC1A1C1E), // 80% alpha translucent dark pill
                border = BorderStroke(1.dp, Color(0x4DFFFFFF)),
                tonalElevation = 10.dp,
                shadowElevation = 12.dp,
                modifier = Modifier
                    .offset { IntOffset(clampedPillOffset.x.roundToInt(), clampedPillOffset.y.roundToInt()) }
                    .width(64.dp)
                    .wrapContentHeight()
                    .pointerInput(Unit) {
                        // Consume all events inside the pill so touches never leak to remote desktop canvas
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // 1. Reposition Grip Handle (drag to move panel)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(28.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0x22FFFFFF))
                            .pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    offset = clampOffset(offset + dragAmount, pillWidthPx, pillHeightPx)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            repeat(3) {
                                Box(
                                    modifier = Modifier
                                        .width(22.dp)
                                        .height(2.5.dp)
                                        .background(Color(0x99FFFFFF), RoundedCornerShape(2.dp))
                                )
                            }
                        }
                    }

                    // 2. Tactile Scroll Up Button (Continuous Repeat)
                    TactileScrollButton(
                        icon = Icons.Default.KeyboardArrowUp,
                        onRepeat = { onScroll(RdpPointerFlags.ScrollDirection.UP) }
                    )

                    // 3. Tactile Scroll Down Button (Continuous Repeat)
                    TactileScrollButton(
                        icon = Icons.Default.KeyboardArrowDown,
                        onRepeat = { onScroll(RdpPointerFlags.ScrollDirection.DOWN) }
                    )

                    // 4. Left Click Button (with Drag Lock)
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isDragLocked) Color(0xFFE65100) else Color(0x33FFFFFF), // Amber-orange warning when locked
                        border = BorderStroke(
                            1.dp,
                            if (isDragLocked) Color(0xFFFFB74D) else Color(0x4DFFFFFF)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = {
                                        if (isDragLocked) {
                                            // Release drag lock
                                            isDragLocked = false
                                            onButtonUp(RdpPointerFlags.Button.LEFT)
                                        } else {
                                            // Normal Left click
                                            onButtonClick(RdpPointerFlags.Button.LEFT)
                                        }
                                    },
                                    onLongPress = {
                                        // Engage/Disengage Drag Lock
                                        isDragLocked = !isDragLocked
                                        if (isDragLocked) {
                                            onButtonDown(RdpPointerFlags.Button.LEFT)
                                        } else {
                                            onButtonUp(RdpPointerFlags.Button.LEFT)
                                        }
                                    }
                                )
                            }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = if (isDragLocked) "L 🔒" else "L",
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                if (isDragLocked) {
                                    Text(
                                        text = "HOLD",
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.Yellow
                                    )
                                }
                            }
                        }
                    }

                    // 5. Middle Click Button ("M")
                    MouseButtonPill("M", onClick = { onButtonClick(RdpPointerFlags.Button.MIDDLE) })

                    // 6. Right Click Button ("R")
                    MouseButtonPill("R", onClick = { onButtonClick(RdpPointerFlags.Button.RIGHT) })

                    // 7. Collapse Button
                    IconButton(
                        onClick = {
                            if (isDragLocked) {
                                isDragLocked = false
                                onButtonUp(RdpPointerFlags.Button.LEFT)
                            }
                            isExpanded = false
                        },
                        modifier = Modifier
                            .size(36.dp)
                            .background(Color(0x22FFFFFF), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Collapse Virtual Mouse",
                            tint = Color(0xCCFFFFFF),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TactileScrollButton(
    icon: ImageVector,
    onRepeat: () -> Unit
) {
    var isPressed by remember { mutableStateOf(false) }

    LaunchedEffect(isPressed) {
        if (isPressed) {
            onRepeat()
            delay(200L) // Initial delay before continuous repeat
            while (isActive && isPressed) {
                onRepeat()
                delay(50L) // High-frequency continuous tick
            }
        }
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isPressed) Color(0x66FFFFFF) else Color(0x33FFFFFF),
        border = BorderStroke(1.dp, Color(0x4DFFFFFF)),
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .clip(RoundedCornerShape(12.dp))
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
                contentDescription = "Scroll",
                tint = Color.White,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

@Composable
private fun MouseButtonPill(
    label: String,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0x33FFFFFF),
        border = BorderStroke(1.dp, Color(0x4DFFFFFF)),
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onClick() })
            }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
    }
}
