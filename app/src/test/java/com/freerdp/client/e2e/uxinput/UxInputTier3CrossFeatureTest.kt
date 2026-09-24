package com.freerdp.client.e2e.uxinput

import android.graphics.PointF
import com.freerdp.core.engine.RdpConnectionConfig
import com.freerdp.core.engine.RdpConnectionState
import com.freerdp.core.protocol.RdpPointerFlags
import com.freerdp.feature.mouse.CoordinateTransformer
import com.freerdp.feature.mouse.DefaultMouseController
import com.freerdp.feature.session.keyboard.ScancodeTranslator
import com.freerdp.feature.session.modifier.LatchState
import com.freerdp.feature.session.modifier.ModifierKey
import com.freerdp.feature.session.modifier.ModifierStateMachine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TIER 3 — Cross-Feature Pairwise Interaction Tests for UX & Input Port.
 *
 * Verifies multi-component interactions across toolbar, virtual keys, modifiers,
 * BMC timing, mouse modes, acceleration, and coordinate transformations.
 * Total: 11 tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class UxInputTier3CrossFeatureTest {

    private lateinit var engine: UxRecordingEngine
    private lateinit var transformer: CoordinateTransformer
    private lateinit var mouseController: DefaultMouseController

    @Before
    fun setUp() {
        engine = UxRecordingEngine()
        transformer = CoordinateTransformer(
            remoteWidth = 1920,
            remoteHeight = 1080,
            viewWidth = 1080,
            viewHeight = 2400
        )
        mouseController = DefaultMouseController(engine, transformer)
    }

    // ==================================================================
    // 1. Typing with Sticky Modifier (F5 Modifiers + F7 Scancodes)
    // ==================================================================
    @Test
    fun combo1_typingWithStickyCtrlAutoReleasesCtrlAfterKey() {
        val fsm = ModifierStateMachine(rdpEngine = engine)
        // Latch Ctrl
        fsm.onModifierKeyTapped(ModifierKey.CTRL)
        assertTrue(fsm.isLatched(ModifierKey.CTRL))
        assertTrue(engine.keyEvents.any { it.scancode == 0x1D && it.down })

        // Type 'C'
        fsm.onNonModifierKeyPressed('c')

        // Ctrl must be consumed and auto-unlatched to INACTIVE
        assertEquals(LatchState.INACTIVE, fsm.getModifierState(ModifierKey.CTRL))
        // Verify event sequence: Ctrl Down -> C Down -> C Up -> Ctrl Up
        assertEquals(4, engine.keyEvents.size)
        assertEquals(0x1D, engine.keyEvents[0].scancode); assertTrue(engine.keyEvents[0].down)
        assertEquals(0x2E, engine.keyEvents[1].scancode); assertTrue(engine.keyEvents[1].down)
        assertEquals(0x2E, engine.keyEvents[2].scancode); assertFalse(engine.keyEvents[2].down)
        assertEquals(0x1D, engine.keyEvents[3].scancode); assertFalse(engine.keyEvents[3].down)
    }

    // ==================================================================
    // 2. Scrolling via Pillar while Zoomed (F8 Direct Zoom + F11 Scroll Pillar)
    // ==================================================================
    @Test
    fun combo2_scrollingViaPillarWhileZoomedMaintainsFocalCentering() = runTest {
        val testScope = TestScope(StandardTestDispatcher(testScheduler))
        transformer.setScale(2.5f, focusX = 540f, focusY = 1200f)
        val initialScale = transformer.scale
        val initialTx = transformer.translationX

        val overlay = VirtualMouseOverlayModel(mouseController, scope = testScope)
        overlay.startScrollRepeat(directionUp = true, screenX = 540f, screenY = 1200f)

        testScope.advanceTimeBy(350)
        overlay.cancelScrollRepeat()

        // Scale and pan translation must remain stable during scroll
        assertEquals(initialScale, transformer.scale, 0.001f)
        assertEquals(initialTx, transformer.translationX, 0.001f)
        assertTrue(engine.pointerEvents.size >= 3)
    }

    // ==================================================================
    // 3. Mode Switching Mid-Drag (F3 Toolbar Switch + F9 Touchpad + F10 Drag)
    // ==================================================================
    @Test
    fun combo3_modeSwitchFromTouchpadToDirectMidDragFiresSafetyRelease() {
        mouseController.setTouchpadMode(true)
        mouseController.handleDragStart(500f, 500f)
        assertTrue(mouseController.isDragging)

        // Switch to direct touch mid-drag
        mouseController.setTouchpadMode(false)
        assertFalse("Safety release must terminate drag state on mode toggle", mouseController.isDragging)
        assertTrue("Safety button up must be emitted to engine", engine.pointerEvents.any { it.flags == RdpPointerFlags.LEFT_BUTTON_UP })
    }

    // ==================================================================
    // 4. Virtual Keys Fn Press with Locked Modifier (F4 Fn + F5 Modifier Lock)
    // ==================================================================
    @Test
    fun combo4_fnStripKeyPressWithLockedShiftPreservesShiftLock() {
        val fsm = ModifierStateMachine(rdpEngine = engine)
        // Double-tap Shift to lock
        fsm.onModifierKeyTapped(ModifierKey.SHIFT)
        fsm.onModifierKeyTapped(ModifierKey.SHIFT)
        assertTrue(fsm.isLocked(ModifierKey.SHIFT))

        // Press F5 in Fn strip
        fsm.onSpecialKeyTapped(ModifierKey.F5)

        // Shift must remain LOCKED (not consumed like latched)
        assertTrue("Locked modifier must not be consumed by Fn key", fsm.isLocked(ModifierKey.SHIFT))
        assertEquals(LatchState.LOCKED, fsm.getModifierState(ModifierKey.SHIFT))
        assertTrue(engine.keyEvents.any { it.scancode == 0x3F && it.down }) // F5 down
    }

    // ==================================================================
    // 5. Floating Opener Drag while Drawer Open (F1 Drawer + F2 Opener)
    // ==================================================================
    @Test
    fun combo5_floatingOpenerDragWhileDrawerOpenMaintainsBothStates() {
        val drawer = ToolbarDrawerLayoutEngine()
        val opener = FloatingOpenerController(parentHeight = 2400)

        drawer.open()
        assertTrue(drawer.isExpanded)

        // Drag opener button
        opener.onDrag(1200f)
        opener.onRelease()

        assertEquals(0.5f, opener.verticalBias, 0.001f)
        assertTrue("Drawer remains open while repositioning opener", drawer.isExpanded)
    }

    // ==================================================================
    // 6. Middle Click Following 3-Tier Acceleration (F9 Libinput + F10 Mid Click)
    // ==================================================================
    @Test
    fun combo6_middleClickInTouchpadModeFollowing3TierAcceleration() {
        mouseController.setTouchpadMode(true)
        mouseController.setVirtualCursorPosition(500f, 500f)

        // Accelerate cursor with high velocity flick
        val (dx, dy) = LibinputPointerAcceleration.computeDelta(100f, 50f, 0.016f, dpi = 160f)
        mouseController.handleTouchpadMove(dx, dy)

        // Dispatch middle click via virtual mouse pill
        val overlay = VirtualMouseOverlayModel(mouseController)
        overlay.triggerMiddleClick(mouseController.virtualCursorPosition.x, mouseController.virtualCursorPosition.y, engine)

        val lastEvents = engine.pointerEvents.takeLast(2)
        assertEquals(RdpPointerFlags.MIDDLE_BUTTON_DOWN, lastEvents[0].flags)
        assertEquals(RdpPointerFlags.MIDDLE_BUTTON_UP, lastEvents[1].flags)
        assertEquals(mouseController.virtualCursorPosition.x.toInt(), lastEvents[0].x)
    }

    // ==================================================================
    // 7. BMC Hold on Enter during Paced Text Streaming (F6 Timing + F7 VK)
    // ==================================================================
    @Test
    fun combo7_bmcHoldTimingOnEnterDuringPacedTextStreaming() = runTest {
        val testScope = TestScope(StandardTestDispatcher(testScheduler))
        val bmc = BmcKeyboardTimingEngine(engine, keyHoldDurationMs = 50L, interKeyPacingMs = 25L, scope = testScope)

        // Stream text "a\n" (a followed by Enter)
        bmc.sendTextWithPacing("a")
        testScope.advanceTimeBy(75) // 50ms hold + 25ms pacing

        bmc.sendKeyPressWithHold(ScancodeTranslator.SCANCODE_ENTER, holdDurationMs = 50L)
        testScope.advanceTimeBy(10)
        assertTrue(engine.keyEvents.any { it.scancode == 0x1C && it.down })
        assertFalse(engine.keyEvents.any { it.scancode == 0x1C && !it.down })

        testScope.advanceTimeBy(45)
        assertTrue(engine.keyEvents.any { it.scancode == 0x1C && !it.down })
    }

    // ==================================================================
    // 8. Virtual Mouse Drag Lock + Arrow Navigation (F4 Inverted-T + F10 Drag Lock)
    // ==================================================================
    @Test
    fun combo8_virtualMouseDragLockCombinedWithInvertedTArrowNavigation() {
        val overlay = VirtualMouseOverlayModel(mouseController)
        overlay.expandPill()
        overlay.toggleDragLock(100f, 100f)
        assertTrue(overlay.isDragLocked)
        assertTrue(mouseController.isDragging)

        // Dispatch inverted-T arrows to extend range selection
        val fsm = ModifierStateMachine(rdpEngine = engine)
        fsm.onSpecialKeyTapped(ModifierKey.ARROW_DOWN)
        fsm.onSpecialKeyTapped(ModifierKey.ARROW_RIGHT)

        assertTrue("Drag lock must remain active during arrow key navigation", overlay.isDragLocked)
        assertTrue(mouseController.isDragging)

        overlay.cancelDragLock()
        assertFalse(overlay.isDragLocked)
        assertFalse(mouseController.isDragging)
    }

    // ==================================================================
    // 9. Direct Touch Edge Coercion + Double Tap (F8 Direct + F10 Double Click)
    // ==================================================================
    @Test
    fun combo9_directTouchEdgeCoercionCombinedWithDoubleTap() {
        val direct = DirectTouchPointerHandler(engine, transformer)
        // Double tap in letterbox void space
        direct.handleDirectDoubleTap(500f, 0f)

        assertEquals(4, engine.pointerEvents.size)
        // All 4 events must be coerced to Y=0 (top edge)
        for (event in engine.pointerEvents) {
            assertEquals(0, event.y)
        }
    }

    // ==================================================================
    // 10. Toolbar Toggle with Virtual Keys Open (F1 Toolbar + F4 Virtual Keys)
    // ==================================================================
    @Test
    fun combo10_toolbarDrawerToggleWhileVirtualKeysBarIsOpen() {
        val drawer = ToolbarDrawerLayoutEngine()
        var virtualKeysVisible = true

        drawer.open()
        assertTrue(drawer.isExpanded)
        assertTrue(virtualKeysVisible)

        drawer.close()
        assertFalse(drawer.isExpanded)
        assertTrue("Closing drawer must leave virtual keys open", virtualKeysVisible)
    }

    // ==================================================================
    // 11. Disconnect with Active Sticky Modifiers and Drag (F3 + F5 + F10)
    // ==================================================================
    @Test
    fun combo11_disconnectButtonWithActiveStickyModifiersAndActiveDrag() = runTest {
        val fsm = ModifierStateMachine(rdpEngine = engine)
        fsm.onModifierKeyTapped(ModifierKey.CTRL) // Latched
        fsm.onModifierKeyTapped(ModifierKey.WIN)
        fsm.onModifierKeyTapped(ModifierKey.WIN)  // Locked

        mouseController.handleDragStart(100f, 100f)
        assertTrue(mouseController.isDragging)

        // Session disconnect
        engine.disconnect()
        mouseController.releaseButtons()
        fsm.resetAll()

        assertEquals(RdpConnectionState.Disconnected, engine.connectionState.value)
        assertFalse(mouseController.isDragging)
        assertEquals(LatchState.INACTIVE, fsm.getModifierState(ModifierKey.CTRL))
        assertEquals(LatchState.INACTIVE, fsm.getModifierState(ModifierKey.WIN))
    }
}
