package com.freerdp.client.ui.session

import android.graphics.PointF
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.freerdp.feature.mouse.MouseController
import kotlinx.coroutines.delay
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Controller and state holder for Virtual Mouse Compose overlay.
 * Ported from AVNC VirtualMouse pattern.
 */
class VirtualMouse(
    val mouseController: MouseController? = null,
    val onLeftDownAction: (() -> Unit)? = null,
    val onLeftUpAction: (() -> Unit)? = null,
    val onRightClickAction: (() -> Unit)? = null,
    val onMiddleClickAction: (() -> Unit)? = null,
    val onScrollUpAction: (() -> Unit)? = null,
    val onScrollDownAction: (() -> Unit)? = null,
    val onToggleKeyboard: (() -> Unit)? = null,
    val onCloseAction: (() -> Unit)? = null,
    val onModeChange: ((Boolean) -> Unit)? = null
) {
    val isVisibleState = mutableStateOf(false)
    val isExpandedState = mutableStateOf(false)

    val isVisible: Boolean get() = isVisibleState.value
    val isExpanded: Boolean get() = isExpandedState.value

    fun show(expand: Boolean = false) {
        if (!isVisibleState.value) {
            mouseController?.setTouchpadMode(true)
            mouseController?.setCursorVisible(true)
            onModeChange?.invoke(true)
            isVisibleState.value = true
        }
        if (expand) {
            isExpandedState.value = true
        }
    }

    fun hide() {
        if (isVisibleState.value) {
            releaseAllButtons()
            isExpandedState.value = false
            isVisibleState.value = false
            onCloseAction?.invoke()
        }
    }

    fun minimize() {
        isExpandedState.value = false
    }

    fun expand() {
        isExpandedState.value = true
    }

    fun toggle() {
        if (isVisibleState.value) {
            hide()
        } else {
            show(expand = false)
        }
    }

    fun onOpenKeyboard() {
        onToggleKeyboard?.invoke()
    }

    var isDragLocked by mutableStateOf(false)

    fun toggleDragLock() {
        isDragLocked = !isDragLocked
        val pos = mouseController?.cursorScreenPosition ?: PointF(0f, 0f)
        if (isDragLocked) {
            mouseController?.handleDragStart(pos.x, pos.y)
        } else {
            mouseController?.handleDragEnd(pos.x, pos.y)
        }
    }

    fun onLeftDown() {
        if (onLeftDownAction != null) {
            onLeftDownAction.invoke()
        } else {
            val pos = mouseController?.cursorScreenPosition ?: PointF(0f, 0f)
            mouseController?.handleDragStart(pos.x, pos.y)
        }
    }

    fun onLeftUp() {
        if (isDragLocked) return
        if (onLeftUpAction != null) {
            onLeftUpAction.invoke()
        } else {
            val pos = mouseController?.cursorScreenPosition ?: PointF(0f, 0f)
            mouseController?.handleDragEnd(pos.x, pos.y)
        }
    }

    fun onRightClick() {
        if (onRightClickAction != null) {
            onRightClickAction.invoke()
        } else {
            val pos = mouseController?.cursorScreenPosition ?: PointF(0f, 0f)
            mouseController?.handleRightClick(pos.x, pos.y)
        }
    }

    fun onMiddleClick() {
        if (onMiddleClickAction != null) {
            onMiddleClickAction.invoke()
        } else {
            val pos = mouseController?.cursorScreenPosition ?: PointF(0f, 0f)
            mouseController?.handleMiddleClick(pos.x, pos.y)
        }
    }

    fun onScrollUp() {
        if (onScrollUpAction != null) {
            onScrollUpAction.invoke()
        } else {
            val pos = mouseController?.cursorScreenPosition ?: PointF(0f, 0f)
            mouseController?.handleScroll(pos.x, pos.y, 1.0f)
        }
    }

    fun onScrollDown() {
        if (onScrollDownAction != null) {
            onScrollDownAction.invoke()
        } else {
            val pos = mouseController?.cursorScreenPosition ?: PointF(0f, 0f)
            mouseController?.handleScroll(pos.x, pos.y, -1.0f)
        }
    }

    fun releaseAllButtons() {
        isDragLocked = false
        val pos = mouseController?.cursorScreenPosition ?: PointF(0f, 0f)
        if (mouseController?.isDragging == true) {
            mouseController.handleDragEnd(pos.x, pos.y)
        }
    }
}

/**
 * Jetpack Compose On-Screen Real-Time Virtual Mouse Controls (RealVNC/AVNC style).
 * Features:
 * - Floating 56dp FAB expanding into 46dp pill with Left hold-and-drag, Middle click,
 *   Scroll Up/Down, Right click, Keyboard toggle, Close.
 * - Docked right vertical scroll pillar (52dp) with 46x48dp scroll buttons.
 */
@Composable
fun VirtualMouseOverlay(
    virtualMouse: VirtualMouse,
    modifier: Modifier = Modifier
) {
    val isVisible by virtualMouse.isVisibleState
    val isExpanded by virtualMouse.isExpandedState

    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            // Floating 56dp FAB / 46dp Control Pill
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 20.dp, bottom = 80.dp)
                    .offset {
                        val clampedX = if (isExpanded) offsetX.coerceAtMost(0f) else offsetX
                        IntOffset(clampedX.roundToInt(), offsetY.roundToInt())
                    }
                    .alpha(0.80f)
            ) {
                AnimatedContent(
                    targetState = isExpanded,
                    transitionSpec = {
                        fadeIn() togetherWith fadeOut()
                    },
                    label = "VirtualMouseExpandTransition"
                ) { expanded ->
                    if (!expanded) {
                        VirtualMouseFab(
                            onExpand = { virtualMouse.expand() },
                            onDrag = { dx, dy ->
                                offsetX += dx
                                offsetY += dy
                            }
                        )
                    } else {
                        VirtualMouseBar(
                            virtualMouse = virtualMouse,
                            onDrag = { dx, dy ->
                                offsetX += dx
                                offsetY += dy
                            }
                        )
                    }
                }
            }

            // Docked Right Vertical Scroll Pillar (52dp wide)
            VirtualMouseScrollPillar(
                virtualMouse = virtualMouse,
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }
    }
}

/**
 * 56dp circular floating action button (collapsed state).
 */
@Composable
fun VirtualMouseFab(
    onExpand: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val touchSlop = LocalViewConfiguration.current.touchSlop
    Surface(
        modifier = modifier
            .size(56.dp)
            .clip(CircleShape)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var hasMoved = false
                    var totalDx = 0f
                    var totalDy = 0f
                    var lastPos = down.position
                    val downTime = System.currentTimeMillis()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.changedToUp()) {
                            val duration = System.currentTimeMillis() - downTime
                            val dist = hypot(totalDx, totalDy)
                            if (!hasMoved || (duration < 350 && dist < touchSlop * 2f)) {
                                onExpand()
                            }
                            break
                        }
                        val currentPos = change.position
                        val dx = currentPos.x - lastPos.x
                        val dy = currentPos.y - lastPos.y
                        totalDx += dx
                        totalDy += dy
                        if (!hasMoved && hypot(totalDx, totalDy) > touchSlop) {
                            hasMoved = true
                        }
                        if (hasMoved) {
                            change.consume()
                            onDrag(dx, dy)
                        }
                        lastPos = currentPos
                    }
                }
            },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        tonalElevation = 8.dp,
        shadowElevation = 10.dp
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Default.Mouse,
                contentDescription = "Virtual Mouse - Tap to expand",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

/**
 * 46dp rounded control pill (expanded state) with Left hold-and-drag, Middle click,
 * Scroll Up/Down, Right click, Keyboard toggle, Close.
 */
@Composable
fun VirtualMouseBar(
    virtualMouse: VirtualMouse,
    onDrag: (Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        tonalElevation = 8.dp,
        shadowElevation = 12.dp,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.80f),
        modifier = modifier.clip(RoundedCornerShape(24.dp))
    ) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp, vertical = 4.dp)
                .height(46.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            // Drag handle to reposition mouse bar anywhere on screen
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(18.dp)
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            onDrag(dragAmount.x, dragAmount.y)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.DragIndicator,
                    contentDescription = "Reposition",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.size(16.dp)
                )
            }

            // Left Click Button (Supports Hold & Drag)
            var isLeftPressed by remember { mutableStateOf(false) }
            FilledTonalButton(
                onClick = { /* Handled by pointerInput */ },
                colors = if (isLeftPressed) {
                    ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    ButtonDefaults.filledTonalButtonColors()
                },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .height(36.dp)
                    .defaultMinSize(minWidth = 38.dp, minHeight = 36.dp)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                isLeftPressed = true
                                virtualMouse.onLeftDown()
                                tryAwaitRelease()
                                isLeftPressed = false
                                virtualMouse.onLeftUp()
                            }
                        )
                    }
            ) {
                Text("Left", fontSize = 12.sp)
            }

            // Drag Lock Button (Click & Drag mode)
            FilledTonalButton(
                onClick = { virtualMouse.toggleDragLock() },
                colors = if (virtualMouse.isDragLocked) {
                    ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    ButtonDefaults.filledTonalButtonColors()
                },
                contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .height(36.dp)
                    .defaultMinSize(minWidth = 36.dp, minHeight = 36.dp)
                    .semantics { contentDescription = "Toggle click-and-drag mode" }
            ) {
                Text(if (virtualMouse.isDragLocked) "Lock: ON" else "Drag", fontSize = 12.sp)
            }

            // Middle Click Button
            FilledTonalButton(
                onClick = { virtualMouse.onMiddleClick() },
                contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .height(36.dp)
                    .defaultMinSize(minWidth = 34.dp, minHeight = 36.dp)
            ) {
                Text("Mid", fontSize = 12.sp)
            }

            // Scroll Up Button (Supports hold-to-repeat, enlarged)
            var isScrollUpHolding by remember { mutableStateOf(false) }
            LaunchedEffect(isScrollUpHolding) {
                if (isScrollUpHolding) {
                    virtualMouse.onScrollUp()
                    delay(200)
                }
                while (isScrollUpHolding) {
                    virtualMouse.onScrollUp()
                    delay(50)
                }
            }
            FilledTonalButton(
                onClick = { virtualMouse.onScrollUp() },
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .height(38.dp)
                    .defaultMinSize(minWidth = 44.dp, minHeight = 38.dp)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                isScrollUpHolding = true
                                tryAwaitRelease()
                                isScrollUpHolding = false
                            }
                        )
                    }
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowUp,
                    contentDescription = "Scroll Up",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }

            // Scroll Down Button (Supports hold-to-repeat, enlarged)
            var isScrollDownHolding by remember { mutableStateOf(false) }
            LaunchedEffect(isScrollDownHolding) {
                if (isScrollDownHolding) {
                    virtualMouse.onScrollDown()
                    delay(200)
                }
                while (isScrollDownHolding) {
                    virtualMouse.onScrollDown()
                    delay(50)
                }
            }
            FilledTonalButton(
                onClick = { virtualMouse.onScrollDown() },
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .height(38.dp)
                    .defaultMinSize(minWidth = 44.dp, minHeight = 38.dp)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                isScrollDownHolding = true
                                tryAwaitRelease()
                                isScrollDownHolding = false
                            }
                        )
                    }
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = "Scroll Down",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }

            // Right Click Button
            FilledTonalButton(
                onClick = { virtualMouse.onRightClick() },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .height(36.dp)
                    .defaultMinSize(minWidth = 38.dp, minHeight = 36.dp)
            ) {
                Text("Right", fontSize = 12.sp)
            }

            // Keyboard Toggle Button
            IconButton(
                onClick = { virtualMouse.onOpenKeyboard() },
                modifier = Modifier.size(34.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Keyboard,
                    contentDescription = "Keyboard",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Close / Minimize Button
            IconButton(
                onClick = { virtualMouse.minimize() },
                modifier = Modifier
                    .size(34.dp)
                    .background(
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
                        shape = CircleShape
                    )
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Collapse to floating button",
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(17.dp)
                )
            }
        }
    }
}

/**
 * Ergonomic 52dp wide vertical scroll pillar docked at the right screen edge.
 * Features 46x48dp scroll buttons with hold-to-repeat (200ms initial + 50ms sustained).
 */
@Composable
fun VirtualMouseScrollPillar(
    virtualMouse: VirtualMouse,
    modifier: Modifier = Modifier
) {
    var scrollPillarOffsetY by remember { mutableFloatStateOf(0f) }
    Box(
        modifier = modifier
            .padding(end = 6.dp)
            .offset { IntOffset(0, scrollPillarOffsetY.roundToInt()) }
            .alpha(0.85f)
    ) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            tonalElevation = 8.dp,
            shadowElevation = 10.dp,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
            modifier = Modifier.width(52.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier.padding(vertical = 4.dp, horizontal = 3.dp)
            ) {
                // Top drag handle to reposition pillar along right edge
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(16.dp)
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                scrollPillarOffsetY += dragAmount.y
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 20.dp, height = 3.dp)
                            .background(
                                MaterialTheme.colorScheme.outlineVariant,
                                RoundedCornerShape(1.5.dp)
                            )
                    )
                }

                // Big Scroll Up Button (46dp x 48dp, 28dp arrow icon)
                var isPillarUpHolding by remember { mutableStateOf(false) }
                LaunchedEffect(isPillarUpHolding) {
                    if (isPillarUpHolding) {
                        virtualMouse.onScrollUp()
                        delay(200)
                    }
                    while (isPillarUpHolding) {
                        virtualMouse.onScrollUp()
                        delay(50)
                    }
                }
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f),
                    modifier = Modifier
                        .size(width = 46.dp, height = 48.dp)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onPress = {
                                    isPillarUpHolding = true
                                    tryAwaitRelease()
                                    isPillarUpHolding = false
                                }
                            )
                        }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowUp,
                            contentDescription = "Scroll Up",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                // Big Scroll Down Button (46dp x 48dp, 28dp arrow icon)
                var isPillarDownHolding by remember { mutableStateOf(false) }
                LaunchedEffect(isPillarDownHolding) {
                    if (isPillarDownHolding) {
                        virtualMouse.onScrollDown()
                        delay(200)
                    }
                    while (isPillarDownHolding) {
                        virtualMouse.onScrollDown()
                        delay(50)
                    }
                }
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f),
                    modifier = Modifier
                        .size(width = 46.dp, height = 48.dp)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onPress = {
                                    isPillarDownHolding = true
                                    tryAwaitRelease()
                                    isPillarDownHolding = false
                                }
                            )
                        }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = "Scroll Down",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }
        }
    }
}
