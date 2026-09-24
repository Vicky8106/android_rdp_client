package com.freerdp.client.e2e.uxinput

import android.graphics.PointF
import android.graphics.Rect
import com.freerdp.core.engine.IRdpEngine
import com.freerdp.core.engine.MockRdpEngine
import com.freerdp.core.engine.RdpConnectionConfig
import com.freerdp.core.protocol.RdpPointerFlags
import com.freerdp.feature.mouse.CoordinateTransformer
import com.freerdp.feature.mouse.DefaultMouseController
import com.freerdp.feature.mouse.MouseController
import com.freerdp.feature.session.keyboard.ScancodeResult
import com.freerdp.feature.session.keyboard.ScancodeTranslator
import com.freerdp.feature.session.modifier.LatchState
import com.freerdp.feature.session.modifier.ModifierKey
import com.freerdp.feature.session.modifier.ModifierStateMachine
import com.freerdp.feature.session.toolbar.QuickActionToolbarFSM
import com.freerdp.feature.session.toolbar.ToolbarAction
import com.freerdp.feature.session.toolbar.ToolbarState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Recorded Pointer Event for opaque-box assertions.
 */
data class RecordedPointerEvent(
    val flags: Int,
    val x: Int,
    val y: Int,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Recorded Key Event for opaque-box assertions.
 */
data class RecordedKeyEvent(
    val scancode: Int,
    val down: Boolean,
    val isExtended: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Recorded Unicode Key Event for opaque-box assertions.
 */
data class RecordedUnicodeKeyEvent(
    val codePoint: Int,
    val down: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Opaque-box test double recording all FreeRDP native protocol dispatches.
 */
class UxRecordingEngine(private val delegate: MockRdpEngine = MockRdpEngine()) : IRdpEngine by delegate {
    val pointerEvents = mutableListOf<RecordedPointerEvent>()
    val keyEvents = mutableListOf<RecordedKeyEvent>()
    val unicodeKeyEvents = mutableListOf<RecordedUnicodeKeyEvent>()
    val activeConfig: RdpConnectionConfig? get() = delegate.activeConfig

    override fun sendPointerEvent(flags: Int, x: Int, y: Int) {
        pointerEvents.add(RecordedPointerEvent(flags, x, y))
        delegate.sendPointerEvent(flags, x, y)
    }

    override fun sendKeyEvent(keyCode: Int, down: Boolean) {
        val isExtended = (keyCode and 0x0100) != 0
        val baseScancode = keyCode and 0x00FF
        keyEvents.add(RecordedKeyEvent(baseScancode, down, isExtended))
        delegate.sendKeyEvent(keyCode, down)
    }

    override fun sendUnicodeKeyEvent(unicodeChar: Char, down: Boolean) {
        unicodeKeyEvents.add(RecordedUnicodeKeyEvent(unicodeChar.code, down))
        delegate.sendUnicodeKeyEvent(unicodeChar, down)
    }

    fun clearEvents() {
        pointerEvents.clear()
        keyEvents.clear()
        unicodeKeyEvents.clear()
    }
}

/**
 * Libinput 3-tier physical pointer acceleration calculator per PROJECT.md & survey spec.
 */
object LibinputPointerAcceleration {
    const val MIN_MULTIPLIER = 0.3f
    const val MAX_MULTIPLIER = 3.5f
    const val DEFAULT_DPI = 160f

    /**
     * Computes accelerated delta based on physical velocity (mm/s), DPI, and zoom scale.
     */
    fun computeDelta(
        rawDeltaX: Float,
        rawDeltaY: Float,
        dtSeconds: Float,
        dpi: Float = DEFAULT_DPI,
        zoomScale: Float = 1.0f
    ): Pair<Float, Float> {
        if (rawDeltaX == 0f && rawDeltaY == 0f) return Pair(0f, 0f)

        val effectiveDpi = if (dpi <= 0f) DEFAULT_DPI else dpi
        val effectiveDt = if (dtSeconds <= 0f) 0.016f else dtSeconds

        // Convert pixel displacement to physical millimeters: mm = px * 25.4 / dpi
        val distPx = kotlin.math.sqrt(rawDeltaX.pow(2) + rawDeltaY.pow(2))
        val distMm = distPx * 25.4f / effectiveDpi
        val velocityMmS = distMm / effectiveDt

        // 3-Tier Libinput curve
        val factor = when {
            velocityMmS < 10.0f -> {
                // Tier 1: deceleration
                0.07f * velocityMmS + 0.3f
            }
            velocityMmS < 80.0f -> {
                // Tier 2: linear 1.0
                1.0f
            }
            else -> {
                // Tier 3: quadratic speedup
                (0.0005f * (velocityMmS.pow(2) / 80.0f) + 1.0f)
            }
        }.coerceIn(MIN_MULTIPLIER, MAX_MULTIPLIER)

        // Zoom dampening: divide by zoomScale when zoomed in (zoomScale > 1.0)
        val dampening = if (zoomScale > 1.0f) zoomScale else 1.0f
        val finalMultiplier = factor / dampening

        return Pair(rawDeltaX * finalMultiplier, rawDeltaY * finalMultiplier)
    }
}

/**
 * BMC Key Hold and Text Streaming Pacing Engine implementing PROJECT.md contract.
 */
class BmcKeyboardTimingEngine(
    private val rdpEngine: IRdpEngine,
    val keyHoldDurationMs: Long = 50L,
    val interKeyPacingMs: Long = 25L,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    private sealed class KeyCommand {
        data class PressWithHold(val scancode: Int, val isExtended: Boolean, val holdMs: Long) : KeyCommand()
        data class StreamText(val text: String, val pacingMs: Long) : KeyCommand()
        data class RawKeyDown(val scancode: Int, val isExtended: Boolean) : KeyCommand()
        data class RawKeyUp(val scancode: Int, val isExtended: Boolean) : KeyCommand()
        object ReleaseModifiers : KeyCommand()
    }

    private val commandQueue = Channel<KeyCommand>(Channel.UNLIMITED)
    private var workerJob: Job? = null
    var activeHeldKey: Int? = null
        private set

    init {
        startWorker()
    }

    private fun startWorker() {
        workerJob = scope.launch {
            for (cmd in commandQueue) {
                when (cmd) {
                    is KeyCommand.PressWithHold -> {
                        val fullCode = if (cmd.isExtended) cmd.scancode or 0x0100 else cmd.scancode
                        activeHeldKey = fullCode
                        rdpEngine.sendKeyEvent(fullCode, down = true)
                        delay(cmd.holdMs)
                        rdpEngine.sendKeyEvent(fullCode, down = false)
                        activeHeldKey = null
                    }
                    is KeyCommand.StreamText -> {
                        for (ch in cmd.text) {
                            val sc = ScancodeTranslator.fromChar(ch)
                            if (sc != null) {
                                val fullCode = if (sc.isExtended) sc.scancode or 0x0100 else sc.scancode
                                activeHeldKey = fullCode
                                rdpEngine.sendKeyEvent(fullCode, down = true)
                                delay(keyHoldDurationMs)
                                rdpEngine.sendKeyEvent(fullCode, down = false)
                                activeHeldKey = null
                            } else {
                                rdpEngine.sendUnicodeKeyEvent(ch, down = true)
                                delay(keyHoldDurationMs)
                                rdpEngine.sendUnicodeKeyEvent(ch, down = false)
                            }
                            delay(cmd.pacingMs)
                        }
                    }
                    is KeyCommand.RawKeyDown -> {
                        val fullCode = if (cmd.isExtended) cmd.scancode or 0x0100 else cmd.scancode
                        activeHeldKey = fullCode
                        rdpEngine.sendKeyEvent(fullCode, down = true)
                    }
                    is KeyCommand.RawKeyUp -> {
                        val fullCode = if (cmd.isExtended) cmd.scancode or 0x0100 else cmd.scancode
                        rdpEngine.sendKeyEvent(fullCode, down = false)
                        if (activeHeldKey == fullCode) activeHeldKey = null
                    }
                    is KeyCommand.ReleaseModifiers -> {
                        activeHeldKey?.let { code ->
                            rdpEngine.sendKeyEvent(code, down = false)
                            activeHeldKey = null
                        }
                    }
                }
            }
        }
    }

    fun sendKeyPressWithHold(scancode: Int, isExtended: Boolean = false, holdDurationMs: Long = keyHoldDurationMs) {
        commandQueue.trySend(KeyCommand.PressWithHold(scancode, isExtended, holdDurationMs))
    }

    fun sendTextWithPacing(text: String, pacingDelayMs: Long = interKeyPacingMs) {
        commandQueue.trySend(KeyCommand.StreamText(text, pacingDelayMs))
    }

    fun cancelAndReleaseHeld() {
        activeHeldKey?.let { code ->
            rdpEngine.sendKeyEvent(code, down = false)
            activeHeldKey = null
        }
        workerJob?.cancel()
        startWorker()
    }
}

/**
 * Controller for testing In-Session Toolbar Drawer Layout per R1 specifications.
 */
class ToolbarDrawerLayoutEngine(
    var alignment: String = "start", // "start" or "end"
    var parentHeight: Int = 2400,
    var toolbarHeight: Int = 400
) {
    var isExpanded: Boolean = false
        private set

    fun open() { isExpanded = true }
    fun close() { isExpanded = false }
    fun toggle() { isExpanded = !isExpanded }

    val layoutDirection: Int
        get() = if (alignment == "start") 0 /* LAYOUT_DIRECTION_LTR */ else 1 /* LAYOUT_DIRECTION_RTL */

    /**
     * Android 10+ Gesture exclusion rect calculation:
     * Pads by one-sixth of available height in each direction:
     * padding = (parentHeight - toolbarHeight) / 6
     */
    fun calculateGestureExclusionRect(toolbarRect: Rect): Rect {
        val padding = max(0, (parentHeight - toolbarRect.height()) / 6)
        return Rect(
            toolbarRect.left,
            max(0, toolbarRect.top - padding),
            toolbarRect.right,
            min(parentHeight, toolbarRect.bottom + padding)
        )
    }

    /**
     * Scrim swipe to close detection:
     * Left-aligned requires closing fling (vX < 0)
     * Right-aligned requires closing fling (vX > 0)
     */
    fun handleScrimFling(vX: Float, vY: Float, threshold: Float = 200f): Boolean {
        if (!isExpanded) return false
        val closingFling = if (alignment == "start") {
            vX < -threshold
        } else {
            vX > threshold
        }
        if (closingFling) {
            close()
            return true
        }
        return false
    }

    /**
     * Determines whether display cutout intersects actionable toolbar bounds.
     */
    fun shouldApplyCutoutPadding(actionableRect: Rect, cutoutRects: List<Rect>): Boolean {
        return cutoutRects.any { Rect.intersects(actionableRect, it) }
    }
}

/**
 * Floating Opener Button position & persistence controller.
 */
class FloatingOpenerController(
    var parentHeight: Int = 2400,
    var buttonHeight: Int = 72,
    var marginTop: Int = 48,
    var marginBottom: Int = 48,
    private val preferencesStore: MutableMap<String, Float> = mutableMapOf()
) {
    var verticalBias: Float = preferencesStore["toolbarOpenerBtnVerticalBias"] ?: 0.5f
        private set

    val minY: Int get() = marginTop
    val maxY: Int get() = max(minY, parentHeight - buttonHeight - marginBottom)

    val currentY: Float
        get() {
            val rawY = parentHeight * verticalBias
            return rawY.toInt().coerceIn(minY, maxY).toFloat()
        }

    fun onDrag(parentTouchY: Float) {
        val rawBias = if (parentHeight > 0) parentTouchY / parentHeight else 0.5f
        verticalBias = rawBias.coerceIn(0.0f, 1.0f)
    }

    fun onRelease() {
        preferencesStore["toolbarOpenerBtnVerticalBias"] = verticalBias
    }

    fun restoreFromPreferences(biasKey: String = "toolbarOpenerBtnVerticalBias") {
        verticalBias = (preferencesStore[biasKey] ?: 0.5f).coerceIn(0.0f, 1.0f)
    }
}

/**
 * Virtual Mouse Compose Overlay model with FAB, Pill, and Right Scroll Pillar.
 */
class VirtualMouseOverlayModel(
    private val mouseController: MouseController,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    var isPillExpanded: Boolean = false
        private set

    var fabPosition: PointF = PointF(100f, 1000f)
    var isDragLocked: Boolean = false
        private set

    private var scrollRepeatJob: Job? = null
    var scrollRepeatCount: Int = 0
        private set

    fun expandPill() { isPillExpanded = true }
    fun collapsePill() {
        if (isDragLocked) {
            cancelDragLock()
        }
        isPillExpanded = false
    }
    fun togglePill() { if (isPillExpanded) collapsePill() else expandPill() }

    fun onFabDrag(screenX: Float, screenY: Float, screenWidth: Float, screenHeight: Float, padding: Float = 16f) {
        val clampedX = screenX.coerceIn(padding, screenWidth - padding)
        val clampedY = screenY.coerceIn(padding, screenHeight - padding)
        fabPosition = PointF(clampedX, clampedY)
    }

    fun triggerLeftClick(x: Float, y: Float) {
        mouseController.handleLeftClick(x, y)
    }

    fun triggerRightClick(x: Float, y: Float) {
        mouseController.handleRightClick(x, y)
    }

    fun triggerMiddleClick(x: Float, y: Float, engine: IRdpEngine) {
        engine.sendPointerEvent(RdpPointerFlags.MIDDLE_BUTTON_DOWN, x.toInt(), y.toInt())
        engine.sendPointerEvent(RdpPointerFlags.MIDDLE_BUTTON_UP, x.toInt(), y.toInt())
    }

    fun toggleDragLock(x: Float, y: Float) {
        if (isDragLocked) {
            cancelDragLock()
        } else {
            isDragLocked = true
            mouseController.handleDragStart(x, y)
        }
    }

    fun cancelDragLock() {
        if (isDragLocked) {
            isDragLocked = false
            val pos = mouseController.virtualCursorPosition ?: PointF(0f, 0f)
            mouseController.handleDragEnd(pos.x, pos.y)
        }
    }

    fun startScrollRepeat(
        directionUp: Boolean,
        screenX: Float,
        screenY: Float,
        initialDelayMs: Long = 200L,
        repeatIntervalMs: Long = 50L
    ) {
        cancelScrollRepeat()
        val delta = if (directionUp) 1.0f else -1.0f
        mouseController.handleScroll(screenX, screenY, delta)
        scrollRepeatCount = 1

        scrollRepeatJob = scope.launch {
            delay(initialDelayMs)
            while (true) {
                mouseController.handleScroll(screenX, screenY, delta)
                scrollRepeatCount++
                delay(repeatIntervalMs)
            }
        }
    }

    fun cancelScrollRepeat() {
        scrollRepeatJob?.cancel()
        scrollRepeatJob = null
    }
}

/**
 * Direct Touch handler with edge coercion to framebuffer edges per R3 specifications.
 */
class DirectTouchPointerHandler(
    private val engine: IRdpEngine,
    private val transformer: CoordinateTransformer
) {
    /**
     * Coerces screen coordinates to framebuffer edge if touch hits pillarbox/letterbox margin.
     */
    fun coerceToFbEdge(screenX: Float, screenY: Float): PointF {
        val rawDx = (screenX - transformer.translationX) / transformer.scale
        val rawDy = (screenY - transformer.translationY) / transformer.scale
        val clampedX = rawDx.coerceIn(0f, max(0f, (transformer.remoteWidth - 1).toFloat()))
        val clampedY = rawDy.coerceIn(0f, max(0f, (transformer.remoteHeight - 1).toFloat()))
        return PointF(clampedX, clampedY)
    }

    fun handleDirectTap(screenX: Float, screenY: Float) {
        val fb = coerceToFbEdge(screenX, screenY)
        engine.sendPointerEvent(RdpPointerFlags.LEFT_BUTTON_DOWN, fb.x.toInt(), fb.y.toInt())
        engine.sendPointerEvent(RdpPointerFlags.LEFT_BUTTON_UP, fb.x.toInt(), fb.y.toInt())
    }

    fun handleDirectDoubleTap(screenX: Float, screenY: Float) {
        val fb = coerceToFbEdge(screenX, screenY)
        engine.sendPointerEvent(RdpPointerFlags.LEFT_BUTTON_DOWN, fb.x.toInt(), fb.y.toInt())
        engine.sendPointerEvent(RdpPointerFlags.LEFT_BUTTON_UP, fb.x.toInt(), fb.y.toInt())
        engine.sendPointerEvent(RdpPointerFlags.LEFT_BUTTON_DOWN, fb.x.toInt(), fb.y.toInt())
        engine.sendPointerEvent(RdpPointerFlags.LEFT_BUTTON_UP, fb.x.toInt(), fb.y.toInt())
    }
}
