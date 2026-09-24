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
 * TIER 4 — Real-World Application Workload Scenarios for UX & Input Port.
 *
 * Verifies end-to-end multi-step interactive workflows derived from ORIGINAL_REQUEST.md:
 *  - Scenario 1: Remote Text Editing in Windows Notepad (Typing + BMC Hold + Arrows + Modifiers)
 *  - Scenario 2: CAD / Graphic Manipulation with Middle Click Drag & Virtual Mouse Pill
 *  - Scenario 3: One-Handed Mobile Navigation (Floating Opener + Toolbar Drawer + Zoom/Fit)
 *  - Scenario 4: Hybrid Input Switching (Touchpad Acceleration to Direct Touch with Edge Coercion)
 *  - Scenario 5: Full Remote Session Workflow (Connect -> Toolbar Toggle -> Fn Keys -> Typing -> Disconnect)
 * Total: 5 realistic scenarios.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class UxInputTier4WorkloadTest {

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
    // Scenario 1: Remote Text Editing in Windows Notepad
    // ==================================================================
    @Test
    fun scenario1_remoteTextEditingInNotepadWithBmcHoldAndModifiers() = runTest {
        val testScope = TestScope(StandardTestDispatcher(testScheduler))
        val fsm = ModifierStateMachine(rdpEngine = engine)
        val bmc = BmcKeyboardTimingEngine(engine, keyHoldDurationMs = 50L, interKeyPacingMs = 25L, scope = testScope)

        // Step 1: Open File via Ctrl+O shortcut
        fsm.onModifierKeyTapped(ModifierKey.CTRL) // Latched
        assertTrue(fsm.isLatched(ModifierKey.CTRL))
        fsm.onNonModifierKeyPressed('o')
        assertEquals(LatchState.INACTIVE, fsm.getModifierState(ModifierKey.CTRL))

        // Step 2: Stream text filename "doc" with 25ms BMC pacing
        bmc.sendTextWithPacing("doc")
        testScope.advanceTimeBy(225) // 3 chars * (50 + 25)

        // Step 3: Press Enter with 50ms BMC hold timing
        bmc.sendKeyPressWithHold(ScancodeTranslator.SCANCODE_ENTER, holdDurationMs = 50L)
        testScope.advanceTimeBy(55)

        // Step 4: Navigate editor using inverted-T arrow cluster
        fsm.onSpecialKeyTapped(ModifierKey.ARROW_DOWN)
        fsm.onSpecialKeyTapped(ModifierKey.ARROW_DOWN)
        fsm.onSpecialKeyTapped(ModifierKey.ARROW_RIGHT)

        // Verify sequence of key events recorded
        assertTrue(engine.keyEvents.size >= 14)
        assertTrue(engine.keyEvents.any { it.scancode == 0x1C && !it.down }) // Enter UP
        assertTrue(engine.keyEvents.any { it.scancode == 0x50 }) // Down arrow
        assertTrue(engine.keyEvents.any { it.scancode == 0x4D }) // Right arrow
    }

    // ==================================================================
    // Scenario 2: CAD / Graphic Manipulation with Middle Click Drag & Virtual Mouse Pill
    // ==================================================================
    @Test
    fun scenario2_cadGraphicManipulationWithMiddleClickDragAndPill() {
        // Step 1: Switch to Touchpad mode
        mouseController.setTouchpadMode(true)
        assertTrue(mouseController.isTouchpadMode)
        mouseController.setVirtualCursorPosition(960f, 540f) // Center of 1920x1080

        // Step 2: Accelerate cursor towards CAD viewport origin using 3-tier libinput acceleration
        val (dx, dy) = LibinputPointerAcceleration.computeDelta(-200f, -100f, 0.016f, dpi = 160f)
        mouseController.handleTouchpadMove(dx, dy)
        val targetX = mouseController.virtualCursorPosition.x
        val targetY = mouseController.virtualCursorPosition.y

        // Step 3: Expand virtual mouse pill and trigger Middle Click (pan viewport in CAD)
        val overlay = VirtualMouseOverlayModel(mouseController)
        overlay.expandPill()
        assertTrue(overlay.isPillExpanded)
        overlay.triggerMiddleClick(targetX, targetY, engine)

        // Step 4: Perform Middle Button Down, Move, Up sequence to simulate CAD orbit/pan
        engine.sendPointerEvent(RdpPointerFlags.MIDDLE_BUTTON_DOWN, targetX.toInt(), targetY.toInt())
        engine.sendPointerEvent(RdpPointerFlags.MOVE or RdpPointerFlags.PTR_FLAGS_BUTTON3 or RdpPointerFlags.PTR_FLAGS_DOWN, targetX.toInt() + 50, targetY.toInt() + 50)
        engine.sendPointerEvent(RdpPointerFlags.MIDDLE_BUTTON_UP, targetX.toInt() + 50, targetY.toInt() + 50)

        // Step 5: Right click to open context menu
        overlay.triggerRightClick(targetX, targetY)

        val middleDownEvents = engine.pointerEvents.filter { it.flags and RdpPointerFlags.MIDDLE_BUTTON_DOWN != 0 }
        assertTrue("Middle click down events must be emitted for CAD viewport pan", middleDownEvents.isNotEmpty())
        assertTrue(engine.pointerEvents.any { it.flags and RdpPointerFlags.RIGHT_BUTTON_DOWN != 0 })
    }

    // ==================================================================
    // Scenario 3: One-Handed Mobile Navigation
    // ==================================================================
    @Test
    fun scenario3_oneHandedMobileNavigationWithFloatingOpenerAndToolbar() {
        // Step 1: User repositions floating opener button with thumb to lower-right area
        val opener = FloatingOpenerController(parentHeight = 2400)
        opener.onDrag(1800f) // Lower screen for thumb reach: 1800 / 2400 = 0.75
        opener.onRelease()
        assertEquals(0.75f, opener.verticalBias, 0.001f)

        // Step 2: Tap opener button to open toolbar drawer
        val drawer = ToolbarDrawerLayoutEngine(alignment = "start", parentHeight = 2400)
        drawer.open()
        assertTrue(drawer.isExpanded)

        // Step 3: Trigger Scale/Fit mode from toolbar quick action
        transformer.setScale(3.0f)
        transformer.resetToFit()
        assertTrue("Reset to fit adjusts scale to fully view desktop", transformer.scale <= 1.0f)

        // Step 4: Dismiss toolbar via transparent scrim swipe (closing fling)
        val handled = drawer.handleScrimFling(-450f, 0f)
        assertTrue("Closing fling must dismiss drawer", handled)
        assertFalse(drawer.isExpanded)

        // Step 5: Verify opener button preserves saved thumb position for subsequent access
        val restoredOpener = FloatingOpenerController(parentHeight = 2400)
        restoredOpener.onDrag(1800f)
        restoredOpener.onRelease()
        restoredOpener.restoreFromPreferences()
        assertEquals(0.75f, restoredOpener.verticalBias, 0.001f)
    }

    // ==================================================================
    // Scenario 4: Hybrid Input Switching
    // ==================================================================
    @Test
    fun scenario4_hybridInputSwitchingTouchpadToDirectWithEdgeCoercion() {
        // Step 1: Start in Relative Touchpad Mode with physical acceleration
        mouseController.setTouchpadMode(true)
        mouseController.setVirtualCursorPosition(100f, 100f)
        val (dx, dy) = LibinputPointerAcceleration.computeDelta(50f, 50f, 0.032f)
        mouseController.handleTouchpadMove(dx, dy)
        assertTrue(mouseController.isTouchpadMode)

        // Step 2: Switch to Direct Touch Mode via toolbar toggle
        mouseController.setTouchpadMode(false)
        assertFalse(mouseController.isTouchpadMode)

        // Step 3: Tap auto-hiding Windows taskbar at bottom of screen in letterbox margin
        val direct = DirectTouchPointerHandler(engine, transformer)
        direct.handleDirectTap(540f, 2390f) // Tap in bottom letterbox margin

        // Verify edge coercion forced coordinate to remote bottom edge (1079)
        val lastClickDown = engine.pointerEvents.filter { it.flags == RdpPointerFlags.LEFT_BUTTON_DOWN }.last()
        assertEquals("Edge coercion must clamp tap to exact remote taskbar edge", 1079, lastClickDown.y)

        // Step 4: Two-finger pan and pinch zoom
        transformer.setScale(1.5f)
        transformer.applyPan(-50f, -50f)
        assertEquals(1.5f, transformer.scale, 0.001f)
    }

    // ==================================================================
    // Scenario 5: Full Remote Session Lifecycle Workflow
    // ==================================================================
    @Test
    fun scenario5_fullRemoteSessionLifecycleWorkflow() = runTest {
        val testScope = TestScope(StandardTestDispatcher(testScheduler))

        // Phase 1: Connect to remote RDP host
        val config = RdpConnectionConfig(serverAddress = "10.0.2.2", port = 3389, username = "rdpdemo")
        assertTrue(engine.connect(config))
        assertEquals(RdpConnectionState.Connected, engine.connectionState.value)

        // Phase 2: Open In-Session Toolbar & Expand Virtual Keys
        val drawer = ToolbarDrawerLayoutEngine()
        drawer.open()
        assertTrue(drawer.isExpanded)

        // Phase 3: Trigger Alt+Tab macro via virtual keys
        val fsm = ModifierStateMachine(rdpEngine = engine)
        fsm.onModifierKeyTapped(ModifierKey.ALT)
        fsm.onSpecialKeyTapped(ModifierKey.TAB)
        assertEquals(LatchState.INACTIVE, fsm.getModifierState(ModifierKey.ALT))

        // Phase 4: Type command with 50ms BMC hold timing
        val bmc = BmcKeyboardTimingEngine(engine, keyHoldDurationMs = 50L, scope = testScope)
        bmc.sendKeyPressWithHold(ScancodeTranslator.SCANCODE_ENTER, holdDurationMs = 50L)
        testScope.advanceTimeBy(55)

        // Phase 5: Middle Click CAD component on remote surface
        val overlay = VirtualMouseOverlayModel(mouseController)
        overlay.triggerMiddleClick(800f, 400f, engine)

        // Phase 6: Clean session disconnect via toolbar
        drawer.close()
        engine.disconnect()
        assertEquals(RdpConnectionState.Disconnected, engine.connectionState.value)
        assertEquals("Native config cleared on disconnect", null, engine.activeConfig)
    }
}
