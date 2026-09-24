package com.freerdp.client.ui.session

import android.content.Context
import android.graphics.Rect
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

const val PREF_KEY_TOOLBAR_OPENER_VERTICAL_BIAS = "toolbarOpenerBtnVerticalBias"
const val DEFAULT_TOOLBAR_OPENER_VERTICAL_BIAS = 0.5f

/**
 * Calculates Android 10+ (API 29+) System Gesture Exclusion Rect.
 * Formula: padding = (parentHeight - toolbarHeight) / 6
 */
fun calculateGestureExclusionRect(toolbarRect: Rect, parentHeight: Int): Rect {
    val padding = max(0, (parentHeight - toolbarRect.height()) / 6)
    return Rect(
        toolbarRect.left,
        max(0, toolbarRect.top - padding),
        toolbarRect.right,
        min(parentHeight, toolbarRect.bottom + padding)
    )
}

/**
 * Collapsible In-Session Toolbar Drawer.
 * Anchored to the start or end screen edge, animated with slide-in/out and fade.
 * Contains:
 * - Transparent scrim dismissal layer that captures and consumes touches without forwarding to canvas
 * - Gesture exclusion zones configuration on Android 10+ (API 29+)
 * - 5 Quick Action controls:
 *   1. Soft keyboard toggle
 *   2. Pointer mode switch (Direct Touch vs Touchpad mode)
 *   3. Virtual keys bar toggle
 *   4. Display scale/fit toggle (fit to screen / reset zoom)
 *   5. Clean session disconnect button
 */
@Composable
fun InSessionToolbarDrawer(
    isExpanded: Boolean,
    alignment: String = "start", // "start" or "end"
    onClose: () -> Unit,
    onToggleKeyboard: () -> Unit,
    onToggleInputMode: () -> Unit,
    isTouchpadMode: Boolean,
    onToggleVirtualKeys: () -> Unit,
    onResetZoom: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val currentView = LocalView.current
    var drawerBounds by remember { mutableStateOf<Rect?>(null) }
    var parentHeightPx by remember { mutableIntStateOf(0) }

    DisposableEffect(isExpanded, drawerBounds, parentHeightPx) {
        if (Build.VERSION.SDK_INT >= 29) {
            val bounds = drawerBounds
            if (isExpanded && bounds != null && parentHeightPx > 0) {
                val exclRect = calculateGestureExclusionRect(bounds, parentHeightPx)
                currentView.systemGestureExclusionRects = listOf(exclRect)
            } else {
                currentView.systemGestureExclusionRects = emptyList()
            }
        }
        onDispose {
            if (Build.VERSION.SDK_INT >= 29) {
                currentView.systemGestureExclusionRects = emptyList()
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { parentHeightPx = it.height }
    ) {
        // Transparent scrim dismissal layer: captures touches outside drawer, closing drawer without leaking to canvas
        if (isExpanded) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Transparent)
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { onClose() })
                    }
            )
        }

        val enterAnim = if (alignment == "start") {
            slideInHorizontally(initialOffsetX = { -it }) + fadeIn()
        } else {
            slideInHorizontally(initialOffsetX = { it }) + fadeIn()
        }
        val exitAnim = if (alignment == "start") {
            slideOutHorizontally(targetOffsetX = { -it }) + fadeOut()
        } else {
            slideOutHorizontally(targetOffsetX = { it }) + fadeOut()
        }

        AnimatedVisibility(
            visible = isExpanded,
            enter = enterAnim,
            exit = exitAnim,
            modifier = Modifier.align(if (alignment == "start") Alignment.CenterStart else Alignment.CenterEnd)
        ) {
            Surface(
                shape = if (alignment == "start") {
                    RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp)
                } else {
                    RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)
                },
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                tonalElevation = 8.dp,
                shadowElevation = 12.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                modifier = Modifier
                    .width(58.dp)
                    .padding(vertical = 48.dp)
                    .onGloballyPositioned { coordinates ->
                        val b = coordinates.boundsInRoot()
                        drawerBounds = Rect(
                            b.left.roundToInt(),
                            b.top.roundToInt(),
                            b.right.roundToInt(),
                            b.bottom.roundToInt()
                        )
                    }
                    .semantics { contentDescription = "In-session toolbar drawer" }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(vertical = 12.dp, horizontal = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // 1. Soft keyboard toggle
                    IconButton(
                        onClick = { onToggleKeyboard(); onClose() },
                        modifier = Modifier.semantics { contentDescription = "Toggle Keyboard" }
                    ) {
                        Icon(
                            Icons.Default.Keyboard,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    // 2. Pointer mode switch (Direct Touch vs Touchpad mode)
                    IconButton(
                        onClick = { onToggleInputMode(); onClose() },
                        modifier = Modifier.semantics { contentDescription = "Switch Pointer Mode" }
                    ) {
                        Icon(
                            imageVector = if (isTouchpadMode) Icons.Default.TouchApp else Icons.Default.Mouse,
                            contentDescription = null,
                            tint = if (isTouchpadMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }

                    // 3. Virtual keys bar toggle
                    IconButton(
                        onClick = { onToggleVirtualKeys(); onClose() },
                        modifier = Modifier.semantics { contentDescription = "Toggle Virtual Keys" }
                    ) {
                        Icon(
                            Icons.Default.Keyboard,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary
                        )
                    }

                    // 4. Display scale/fit toggle (fit to screen / reset zoom)
                    IconButton(
                        onClick = { onResetZoom(); onClose() },
                        modifier = Modifier.semantics { contentDescription = "Fit to Screen" }
                    ) {
                        Icon(
                            Icons.Default.FitScreen,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    // 5. Clean session disconnect button
                    IconButton(
                        onClick = { onDisconnect(); onClose() },
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.errorContainer, CircleShape)
                            .semantics { contentDescription = "Disconnect" }
                    ) {
                        Icon(
                            Icons.Default.PowerSettingsNew,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }
    }
}

/**
 * Floating draggable opener button with persistent vertical bias across sessions.
 */
@Composable
fun FloatingToolbarOpener(
    alignment: String = "start",
    initialVerticalBias: Float = DEFAULT_TOOLBAR_OPENER_VERTICAL_BIAS,
    onVerticalBiasChanged: (Float) -> Unit = {},
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("session_toolbar_prefs", Context.MODE_PRIVATE) }
    var verticalBias by remember {
        val saved = prefs.getFloat(PREF_KEY_TOOLBAR_OPENER_VERTICAL_BIAS, initialVerticalBias)
        mutableFloatStateOf(saved.coerceIn(0.0f, 1.0f))
    }
    var parentHeightPx by remember { mutableIntStateOf(0) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { parentHeightPx = it.height }
    ) {
        val density = LocalDensity.current
        val btnHeightDp = 48.dp
        val btnHeightPx = with(density) { btnHeightDp.toPx() }
        val marginPx = with(density) { 48.dp.toPx() }

        val yOffset = remember(verticalBias, parentHeightPx) {
            val minY = marginPx
            val maxY = max(minY, parentHeightPx - btnHeightPx - marginPx)
            (parentHeightPx * verticalBias).coerceIn(minY, maxY)
        }

        Surface(
            shape = if (alignment == "start") RoundedCornerShape(topEnd = 14.dp, bottomEnd = 14.dp)
                    else RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f),
            shadowElevation = 6.dp,
            modifier = Modifier
                .align(if (alignment == "start") Alignment.TopStart else Alignment.TopEnd)
                .offset { IntOffset(0, yOffset.roundToInt()) }
                .size(width = 34.dp, height = btnHeightDp)
                .semantics { contentDescription = "Open session toolbar" }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDrag = { change, dragAmount ->
                            change.consume()
                            if (parentHeightPx > 0) {
                                // Accumulate onto live state: yOffset is a captured snapshot
                                // and would pin every event near the gesture start.
                                verticalBias = (verticalBias + dragAmount.y / parentHeightPx)
                                    .coerceIn(0.0f, 1.0f)
                            }
                        },
                        onDragEnd = {
                            prefs.edit().putFloat(PREF_KEY_TOOLBAR_OPENER_VERTICAL_BIAS, verticalBias).apply()
                            onVerticalBiasChanged(verticalBias)
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { onOpen() })
                }
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (alignment == "start") Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

/**
 * Integrated InSessionToolbar combining FloatingToolbarOpener and InSessionToolbarDrawer.
 */
@Composable
fun InSessionToolbar(
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    alignment: String = "start",
    initialVerticalBias: Float = DEFAULT_TOOLBAR_OPENER_VERTICAL_BIAS,
    onVerticalBiasChanged: (Float) -> Unit = {},
    isTouchpadMode: Boolean = false,
    onToggleKeyboard: () -> Unit = {},
    onToggleInputMode: () -> Unit = {},
    onToggleVirtualKeys: () -> Unit = {},
    onResetZoom: () -> Unit = {},
    onDisconnect: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        FloatingToolbarOpener(
            alignment = alignment,
            initialVerticalBias = initialVerticalBias,
            onVerticalBiasChanged = onVerticalBiasChanged,
            onOpen = { onExpandedChange(true) }
        )

        InSessionToolbarDrawer(
            isExpanded = isExpanded,
            alignment = alignment,
            onClose = { onExpandedChange(false) },
            onToggleKeyboard = onToggleKeyboard,
            onToggleInputMode = onToggleInputMode,
            isTouchpadMode = isTouchpadMode,
            onToggleVirtualKeys = onToggleVirtualKeys,
            onResetZoom = onResetZoom,
            onDisconnect = onDisconnect
        )
    }
}
