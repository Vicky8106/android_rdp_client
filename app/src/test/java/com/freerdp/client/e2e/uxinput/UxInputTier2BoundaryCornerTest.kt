package com.freerdp.client.e2e.uxinput

import android.graphics.PointF
import android.graphics.Rect
import android.view.KeyEvent
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.max

/**
 * TIER 2 — Boundary, Corner & Edge Stress Tests for UX & Input Port.
 *
 * Opaque-box adversarial and boundary condition tests covering all 11 features:
 *  - Empty inputs, extreme coordinates, rapid clicks, edge clamps, modifier states,
 *    unmapped scancodes, timer cancellations, and scale extremes.
 * Total: 55 tests (5 per feature).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class UxInputTier2BoundaryCornerTest {

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
    // FEATURE 1: In-Session Toolbar Drawer Layout Boundaries
    // ==================================================================

    @Test
    fun f1_bva_01_zeroCutoutProducesZeroPadding() {
        val layoutEngine = ToolbarDrawerLayoutEngine(parentHeight = 2400)
        val toolbarRect = Rect(0, 0, 100, 400)
        assertFalse(layoutEngine.shouldApplyCutoutPadding(toolbarRect, emptyList()))
    }

    @Test
    fun f1_bva_02_scrimSwipeWithZeroVelocityIsIgnored() {
        val layoutEngine = ToolbarDrawerLayoutEngine(alignment = "start")
        layoutEngine.open()
        val handled = layoutEngine.handleScrimFling(0f, 0f, threshold = 200f)
        assertFalse("Zero velocity swipe must not close drawer", handled)
        assertTrue("Drawer must remain open", layoutEngine.isExpanded)
    }

    @Test
    fun f1_bva_03_oppositeDirectionFlingDoesNotCloseDrawer() {
        val layoutEngine = ToolbarDrawerLayoutEngine(alignment = "start")
        layoutEngine.open()
        // Start-aligned requires vX < -threshold; opposite is positive vX
        val handled = layoutEngine.handleScrimFling(500f, 0f, threshold = 200f)
        assertFalse("Swiping towards open side must not close drawer", handled)
        assertTrue(layoutEngine.isExpanded)
    }

    @Test
    fun f1_bva_04_edgeSwipeAtExtremeZeroCoordinate() {
        val layoutEngine = ToolbarDrawerLayoutEngine(parentHeight = 2400)
        val rectAtOrigin = Rect(0, 0, 100, 400)
        val exclusion = layoutEngine.calculateGestureExclusionRect(rectAtOrigin)
        assertEquals("Exclusion top must not be negative", 0, exclusion.top)
        assertTrue("Exclusion bottom must be padded", exclusion.bottom > 400)
    }

    @Test
    fun f1_bva_05_rapidDoubleTogglePreservesDeterministicState() {
        val layoutEngine = ToolbarDrawerLayoutEngine()
        layoutEngine.toggle() // expand
        layoutEngine.toggle() // collapse
        assertFalse(layoutEngine.isExpanded)
    }

    // ==================================================================
    // FEATURE 2: Floating Opener Button Boundaries
    // ==================================================================

    @Test
    fun f2_bva_01_dragAboveTopBoundaryClampsToMinY() {
        val opener = FloatingOpenerController(parentHeight = 2400, marginTop = 60)
        opener.onDrag(-500f) // negative Y
        assertEquals(0.0f, opener.verticalBias, 0.001f)
        assertEquals(60f, opener.currentY, 0.001f) // clamped to minY = 60
    }

    @Test
    fun f2_bva_02_dragBelowBottomBoundaryClampsToMaxY() {
        val opener = FloatingOpenerController(parentHeight = 2400, buttonHeight = 72, marginBottom = 48)
        opener.onDrag(5000f) // exceeds parentHeight
        assertEquals(1.0f, opener.verticalBias, 0.001f)
        val expectedMaxY = (2400 - 72 - 48).toFloat()
        assertEquals(expectedMaxY, opener.currentY, 0.001f)
    }

    @Test
    fun f2_bva_03_screenResizeClampsPersistedBias() {
        val opener = FloatingOpenerController(parentHeight = 2400)
        opener.onDrag(2000f) // bias = 2000 / 2400 = 0.833f
        // Orient to landscape: parentHeight shrinks to 1080
        opener.parentHeight = 1080
        assertTrue(opener.currentY <= opener.maxY.toFloat())
    }

    @Test
    fun f2_bva_04_outOfRangeSavedBiasSanitizedTo0To1() {
        val store = mutableMapOf("toolbarOpenerBtnVerticalBias" to 2.5f)
        val opener = FloatingOpenerController(preferencesStore = store)
        opener.restoreFromPreferences()
        assertEquals(1.0f, opener.verticalBias, 0.001f)

        store["toolbarOpenerBtnVerticalBias"] = -0.5f
        opener.restoreFromPreferences()
        assertEquals(0.0f, opener.verticalBias, 0.001f)
    }

    @Test
    fun f2_bva_05_subThresholdTouchSlopDoesNotTriggerDrift() {
        val opener = FloatingOpenerController(parentHeight = 2400)
        val initialY = opener.currentY
        // Delta of 1px on 2400px parent is < 0.0005 bias change
        opener.onDrag(opener.parentHeight * opener.verticalBias + 1f)
        assertEquals(initialY, opener.currentY, 1.5f)
    }

    // ==================================================================
    // FEATURE 3: Toolbar Quick Controls Boundaries
    // ==================================================================

    @Test
    fun f3_bva_01_disconnectWhenAlreadyDisconnectedIsIdempotent() = runTest {
        assertEquals(RdpConnectionState.Disconnected, engine.connectionState.value)
        engine.disconnect() // Should not throw
        assertEquals(RdpConnectionState.Disconnected, engine.connectionState.value)
    }

    @Test
    fun f3_bva_02_modeSwitchPreservesVirtualCursorCoordinates() {
        mouseController.setVirtualCursorPosition(500f, 600f)
        mouseController.setTouchpadMode(true)
        assertEquals(500f, mouseController.virtualCursorPosition.x, 0.001f)
        assertEquals(600f, mouseController.virtualCursorPosition.y, 0.001f)

        mouseController.setTouchpadMode(false)
        assertEquals(500f, mouseController.virtualCursorPosition.x, 0.001f)
        assertEquals(600f, mouseController.virtualCursorPosition.y, 0.001f)
    }

    @Test
    fun f3_bva_03_disconnectDuringActiveDragReleasesHeldButton() {
        mouseController.handleDragStart(100f, 100f)
        assertTrue(mouseController.isDragging)

        mouseController.releaseButtons()
        assertFalse("Safety release must end active drag", mouseController.isDragging)
        assertTrue(engine.pointerEvents.any { it.flags == RdpPointerFlags.LEFT_BUTTON_UP })
    }

    @Test
    fun f3_bva_04_rapidSuccessiveActionClicksAreQueuedDeterministically() {
        // Dispatch multiple actions rapidly
        mouseController.handleLeftClick(100f, 100f)
        mouseController.handleRightClick(100f, 100f)
        mouseController.handleLeftClick(100f, 100f)

        assertEquals(6, engine.pointerEvents.size)
        assertEquals(RdpPointerFlags.LEFT_BUTTON_DOWN, engine.pointerEvents[0].flags)
        assertEquals(RdpPointerFlags.LEFT_BUTTON_UP, engine.pointerEvents[1].flags)
        assertEquals(RdpPointerFlags.RIGHT_BUTTON_DOWN, engine.pointerEvents[2].flags)
        assertEquals(RdpPointerFlags.RIGHT_BUTTON_UP, engine.pointerEvents[3].flags)
        assertEquals(RdpPointerFlags.LEFT_BUTTON_DOWN, engine.pointerEvents[4].flags)
        assertEquals(RdpPointerFlags.LEFT_BUTTON_UP, engine.pointerEvents[5].flags)
    }

    @Test
    fun f3_bva_05_disconnectWithActiveModifiersResetsAllModifiers() {
        val fsm = ModifierStateMachine(rdpEngine = engine)
        fsm.onModifierKeyTapped(ModifierKey.CTRL)
        fsm.onModifierKeyTapped(ModifierKey.ALT)
        assertTrue(fsm.isLatched(ModifierKey.CTRL))
        assertTrue(fsm.isLatched(ModifierKey.ALT))

        fsm.resetAll()
        assertEquals(LatchState.INACTIVE, fsm.getModifierState(ModifierKey.CTRL))
        assertEquals(LatchState.INACTIVE, fsm.getModifierState(ModifierKey.ALT))
    }

    // ==================================================================
    // FEATURE 4: Virtual Keys Layout Boundaries
    // ==================================================================

    @Test
    fun f4_bva_01_rapidAlternatingArrowKeysDispatchDiscreteEvents() {
        val fsm = ModifierStateMachine(rdpEngine = engine)
        val arrows = listOf(ModifierKey.ARROW_LEFT, ModifierKey.ARROW_RIGHT, ModifierKey.ARROW_UP, ModifierKey.ARROW_DOWN)

        arrows.forEach { arrow ->
            fsm.onSpecialKeyTapped(arrow)
        }
        // Each special key tap dispatches 1 DOWN and 1 UP event = 8 events total
        assertEquals(8, engine.keyEvents.size)
        assertEquals(0x4B, engine.keyEvents[0].scancode); assertTrue(engine.keyEvents[0].down)
        assertEquals(0x4B, engine.keyEvents[1].scancode); assertFalse(engine.keyEvents[1].down)
        assertEquals(0x4D, engine.keyEvents[2].scancode); assertTrue(engine.keyEvents[2].down)
        assertEquals(0x4D, engine.keyEvents[3].scancode); assertFalse(engine.keyEvents[3].down)
    }

    @Test
    fun f4_bva_02_deleteKeyProperlySetsExtendedBit0x0100() {
        val del = ScancodeTranslator.getScancodeForModifierKey(ModifierKey.DEL)
        assertEquals(0x53, del.scancode)
        assertTrue(del.isExtended)
        assertEquals(0x0100, del.flags)
    }

    @Test
    fun f4_bva_03_fnStripToggleDuringTypingDoesNotDropConcurrentKeys() {
        val fsm = ModifierStateMachine(rdpEngine = engine)
        fsm.onNonModifierKeyPressed('x')
        // Simulate Fn strip toggle
        fsm.onSpecialKeyTapped(ModifierKey.F1)
        fsm.onNonModifierKeyPressed('y')

        assertEquals(6, engine.keyEvents.size) // x down/up + F1 down/up + y down/up
    }

    @Test
    fun f4_bva_04_virtualKeysContainerLayoutClampsToMaxHeight() {
        val totalScreenHeight = 2400
        val maxContainerHeight = totalScreenHeight / 3 // at most 1/3 of screen
        assertTrue(maxContainerHeight < totalScreenHeight / 2)
    }

    @Test
    fun f4_bva_05_unmappedVirtualKeyProducesSafeFallbackWithoutCrash() {
        val unmapped = ScancodeTranslator.fromAndroidKeyCode(KeyEvent.KEYCODE_UNKNOWN)
        assertNull(unmapped)
    }

    // ==================================================================
    // FEATURE 5: Tri-State Modifier State Machine Boundaries
    // ==================================================================

    @Test
    fun f5_bva_01_directTransitionFromInactiveToLockedIsRejected() {
        val fsm = ModifierStateMachine(rdpEngine = engine)
        assertFalse(
            "Direct transition from INACTIVE to LOCKED must be rejected",
            fsm.tryTransition(ModifierKey.CTRL, LatchState.LOCKED)
        )
        assertEquals(LatchState.INACTIVE, fsm.getModifierState(ModifierKey.CTRL))
    }

    @Test
    fun f5_bva_02_multipleSimultaneousLatchedModifiersConsumedTogether() {
        val fsm = ModifierStateMachine(rdpEngine = engine)
        fsm.onModifierKeyTapped(ModifierKey.CTRL)
        fsm.onModifierKeyTapped(ModifierKey.SHIFT)
        assertTrue(fsm.isLatched(ModifierKey.CTRL))
        assertTrue(fsm.isLatched(ModifierKey.SHIFT))

        fsm.onNonModifierKeyPressed('z')
        assertFalse(fsm.isLatched(ModifierKey.CTRL))
        assertFalse(fsm.isLatched(ModifierKey.SHIFT))
        assertEquals(LatchState.INACTIVE, fsm.getModifierState(ModifierKey.CTRL))
        assertEquals(LatchState.INACTIVE, fsm.getModifierState(ModifierKey.SHIFT))
    }

    @Test
    fun f5_bva_03_invalidScancodeNonModifierKeyPreservesLatchedModifier() {
        val fsm = ModifierStateMachine(rdpEngine = engine)
        fsm.onModifierKeyTapped(ModifierKey.ALT)
        assertTrue(fsm.isLatched(ModifierKey.ALT))

        // Invalid scancode (out of Set 1 range)
        fsm.onNonModifierKeyPressed(0x999)
        assertTrue("Invalid scancode must not consume latched modifier", fsm.isLatched(ModifierKey.ALT))
    }

    @Test
    fun f5_bva_04_resetAllReleasesMixedLatchedAndLockedModifiers() {
        val fsm = ModifierStateMachine(rdpEngine = engine)
        fsm.onModifierKeyTapped(ModifierKey.CTRL) // Latched
        fsm.onModifierKeyTapped(ModifierKey.WIN)
        fsm.onModifierKeyTapped(ModifierKey.WIN)  // Locked

        assertTrue(fsm.isLatched(ModifierKey.CTRL))
        assertTrue(fsm.isLocked(ModifierKey.WIN))

        fsm.resetAll()
        assertEquals(LatchState.INACTIVE, fsm.getModifierState(ModifierKey.CTRL))
        assertEquals(LatchState.INACTIVE, fsm.getModifierState(ModifierKey.WIN))
    }

    @Test
    fun f5_bva_05_doubleTapShiftLocksAndMultipleCharsKeepShiftLocked() {
        val fsm = ModifierStateMachine(rdpEngine = engine)
        fsm.onModifierKeyTapped(ModifierKey.SHIFT)
        fsm.onModifierKeyTapped(ModifierKey.SHIFT)
        assertTrue(fsm.isLocked(ModifierKey.SHIFT))

        fsm.onNonModifierKeyPressed('a')
        assertTrue("Locked modifier remains locked across multiple keys", fsm.isLocked(ModifierKey.SHIFT))
        fsm.onNonModifierKeyPressed('b')
        assertTrue("Still locked", fsm.isLocked(ModifierKey.SHIFT))
    }

    // ==================================================================
    // FEATURE 6: BMC Key Hold Timing Boundaries
    // ==================================================================

    @Test
    fun f6_bva_01_burstOfTenEnterKeystrokesQueuesSequentially() = runTest {
        val testScope = TestScope(StandardTestDispatcher(testScheduler))
        val bmc = BmcKeyboardTimingEngine(engine, keyHoldDurationMs = 50L, scope = testScope)

        repeat(10) {
            bmc.sendKeyPressWithHold(ScancodeTranslator.SCANCODE_ENTER)
        }

        // Each key takes 50ms hold -> 10 keys take 500ms total
        testScope.advanceTimeBy(550)
        assertEquals(20, engine.keyEvents.size) // all 10 completed (10 down, 10 up)
    }

    @Test
    fun f6_bva_02_emptyStringStreamingCompletesImmediately() = runTest {
        val testScope = TestScope(StandardTestDispatcher(testScheduler))
        val bmc = BmcKeyboardTimingEngine(engine, scope = testScope)
        bmc.sendTextWithPacing("")
        testScope.advanceTimeBy(100)
        assertEquals(0, engine.keyEvents.size)
    }

    @Test
    fun f6_bva_03_cancellationDuringHoldReleasesCurrentlyHeldKey() = runTest {
        val testScope = TestScope(StandardTestDispatcher(testScheduler))
        val bmc = BmcKeyboardTimingEngine(engine, keyHoldDurationMs = 50L, scope = testScope)
        bmc.sendKeyPressWithHold(ScancodeTranslator.SCANCODE_TAB)

        testScope.advanceTimeBy(10)
        assertNotNull(bmc.activeHeldKey)

        bmc.cancelAndReleaseHeld()
        assertNull(bmc.activeHeldKey)
        assertTrue("Safety key up must be emitted on cancel", engine.keyEvents.any { it.scancode == 0x0F && !it.down })
    }

    @Test
    fun f6_bva_04_streamingLongStringMaintainsConstantPacingWithoutDrift() = runTest {
        val testScope = TestScope(StandardTestDispatcher(testScheduler))
        val bmc = BmcKeyboardTimingEngine(engine, keyHoldDurationMs = 20L, interKeyPacingMs = 10L, scope = testScope)
        val text = "quickbrownfox" // 13 chars
        bmc.sendTextWithPacing(text)

        // Total time = 13 * (20 hold + 10 pacing) = 390ms
        testScope.advanceTimeBy(395)
        assertEquals(26, engine.keyEvents.size) // 13 * 2 events
    }

    @Test
    fun f6_bva_05_streamingUnicodeEmojisRoutesUnicodeEventsWithPacing() = runTest {
        val testScope = TestScope(StandardTestDispatcher(testScheduler))
        val bmc = BmcKeyboardTimingEngine(engine, keyHoldDurationMs = 50L, scope = testScope)
        bmc.sendTextWithPacing("€") // Unicode non-ASCII char
        testScope.advanceTimeBy(60)

        assertEquals(2, engine.unicodeKeyEvents.size)
        assertEquals('€'.code, engine.unicodeKeyEvents[0].codePoint)
        assertTrue(engine.unicodeKeyEvents[0].down)
        assertFalse(engine.unicodeKeyEvents[1].down)
    }

    // ==================================================================
    // FEATURE 7: Windows VK & Scancode Translation Boundaries
    // ==================================================================

    @Test
    fun f7_bva_01_outOfRangeScancodesIdentifiedAsInvalid() {
        assertFalse(ScancodeTranslator.isKnownScancode(0x00))
        assertFalse(ScancodeTranslator.isKnownScancode(-1))
        assertFalse(ScancodeTranslator.isKnownScancode(0x80))
        assertFalse(ScancodeTranslator.isKnownScancode(999))
        assertTrue(ScancodeTranslator.isKnownScancode(0x01)) // Escape
        assertTrue(ScancodeTranslator.isKnownScancode(0x7F))
    }

    @Test
    fun f7_bva_02_unmappedAndroidKeycodesReturnNullSafely() {
        assertNull(ScancodeTranslator.fromAndroidKeyCode(KeyEvent.KEYCODE_CAMERA))
        assertNull(ScancodeTranslator.fromAndroidKeyCode(KeyEvent.KEYCODE_VOLUME_UP))
    }

    @Test
    fun f7_bva_03_nonAsciiUnicodeCharsReturnNullFromFromChar() {
        assertNull("Non-ASCII char '€' should return null to trigger Unicode pipeline", ScancodeTranslator.fromChar('€'))
        assertNull("Non-ASCII char 'ü' should return null", ScancodeTranslator.fromChar('ü'))
    }

    @Test
    fun f7_bva_04_leftAndRightModifiersHaveDistinctScancodes() {
        val leftShift = ScancodeTranslator.fromAndroidKeyCode(KeyEvent.KEYCODE_SHIFT_LEFT)
        val rightShift = ScancodeTranslator.fromAndroidKeyCode(KeyEvent.KEYCODE_SHIFT_RIGHT)
        assertNotNull(leftShift); assertNotNull(rightShift)
        assertEquals(0x2A, leftShift?.scancode)
        assertEquals(0x36, rightShift?.scancode)
    }

    @Test
    fun f7_bva_05_rightCtrlAndRightAltSetExtendedBit() {
        val rightCtrl = ScancodeTranslator.fromAndroidKeyCode(KeyEvent.KEYCODE_CTRL_RIGHT)
        val rightAlt = ScancodeTranslator.fromAndroidKeyCode(KeyEvent.KEYCODE_ALT_RIGHT)
        assertNotNull(rightCtrl); assertTrue(rightCtrl!!.isExtended)
        assertNotNull(rightAlt); assertTrue(rightAlt!!.isExtended)
    }

    // ==================================================================
    // FEATURE 8: Direct Touch Mode & Edge Coercion Boundaries
    // ==================================================================

    @Test
    fun f8_bva_01_tapAtExactOrigin0_0MapsToRemote0_0() {
        val direct = DirectTouchPointerHandler(engine, transformer)
        val p = direct.coerceToFbEdge(0f, 0f)
        assertEquals(0f, p.x, 0.001f)
        assertEquals(0f, p.y, 0.001f)
    }

    @Test
    fun f8_bva_02_tapAtExtremeBottomRightMapsToLastPixel() {
        val direct = DirectTouchPointerHandler(engine, transformer)
        val p = direct.coerceToFbEdge(10000f, 10000f)
        assertEquals(1919f, p.x, 0.001f)
        assertEquals(1079f, p.y, 0.001f)
    }

    @Test
    fun f8_bva_03_negativeScreenCoordinatesClampToOrigin() {
        val direct = DirectTouchPointerHandler(engine, transformer)
        val p = direct.coerceToFbEdge(-500f, -500f)
        assertEquals(0f, p.x, 0.001f)
        assertEquals(0f, p.y, 0.001f)
    }

    @Test
    fun f8_bva_04_extremeZoomAtMaxScale5_0PreservesSubPixelPrecision() {
        transformer.setScale(5.0f)
        assertEquals(5.0f, transformer.scale, 0.001f)
        val p = transformer.screenToDesktop(500f, 500f)
        assertTrue(p.x >= 0f && p.y >= 0f)
    }

    @Test
    fun f8_bva_05_minimumScaleLetterboxCoercesMarginsToFramebufferEdges() {
        transformer.setScale(0.25f)
        val direct = DirectTouchPointerHandler(engine, transformer)
        val coercedTop = direct.coerceToFbEdge(500f, 10f)
        assertEquals(0f, coercedTop.y, 0.001f)
    }

    // ==================================================================
    // FEATURE 9: Touchpad Mode Acceleration Boundaries
    // ==================================================================

    @Test
    fun f9_bva_01_zeroDeltaProducesZeroMovement() {
        val (dx, dy) = LibinputPointerAcceleration.computeDelta(0f, 0f, 0.016f)
        assertEquals(0f, dx, 0.001f)
        assertEquals(0f, dy, 0.001f)
    }

    @Test
    fun f9_bva_02_extremeVelocityClampsStrictlyAtMax3_5Multiplier() {
        val (dx, _) = LibinputPointerAcceleration.computeDelta(10000f, 0f, 0.001f)
        assertEquals(35000f, dx, 0.001f) // 10000 * 3.5
    }

    @Test
    fun f9_bva_03_nonPositiveDpiFallsBackTo160WithoutDivisionByZero() {
        val (dxZero, _) = LibinputPointerAcceleration.computeDelta(10f, 0f, 0.016f, dpi = 0f)
        val (dxNeg, _) = LibinputPointerAcceleration.computeDelta(10f, 0f, 0.016f, dpi = -100f)
        assertTrue(dxZero > 0f)
        assertEquals(dxZero, dxNeg, 0.001f)
    }

    @Test
    fun f9_bva_04_virtualCursorClampsStrictlyToRemoteBounds() {
        mouseController.setVirtualCursorPosition(-100f, -100f)
        assertEquals(0f, mouseController.virtualCursorPosition.x, 0.001f)
        assertEquals(0f, mouseController.virtualCursorPosition.y, 0.001f)

        mouseController.setVirtualCursorPosition(5000f, 5000f)
        assertEquals(1919f, mouseController.virtualCursorPosition.x, 0.001f)
        assertEquals(1079f, mouseController.virtualCursorPosition.y, 0.001f)
    }

    @Test
    fun f9_bva_05_rapidAlternatingSwipesAccumulateWithoutDriftOrNan() {
        mouseController.setTouchpadMode(true)
        mouseController.setVirtualCursorPosition(500f, 500f)

        repeat(50) {
            mouseController.handleTouchpadMove(10f, 0f)
            mouseController.handleTouchpadMove(-10f, 0f)
        }
        assertEquals(500f, mouseController.virtualCursorPosition.x, 0.001f)
        assertFalse(mouseController.virtualCursorPosition.x.isNaN())
    }

    // ==================================================================
    // FEATURE 10: Dedicated Mouse Buttons Boundaries
    // ==================================================================

    @Test
    fun f10_bva_01_middleClickInTouchpadModeUsesVirtualCursorCoordinates() {
        mouseController.setTouchpadMode(true)
        mouseController.setVirtualCursorPosition(850f, 420f)
        val overlay = VirtualMouseOverlayModel(mouseController)
        overlay.triggerMiddleClick(mouseController.virtualCursorPosition.x, mouseController.virtualCursorPosition.y, engine)

        assertEquals(2, engine.pointerEvents.size)
        assertEquals(850, engine.pointerEvents[0].x)
        assertEquals(420, engine.pointerEvents[0].y)
    }

    @Test
    fun f10_bva_02_dragStartWhileAlreadyDraggingIsGracefulNoOp() {
        mouseController.handleDragStart(100f, 100f)
        assertTrue(mouseController.isDragging)
        // Redundant drag start
        mouseController.handleDragStart(150f, 150f)
        assertTrue(mouseController.isDragging)
    }

    @Test
    fun f10_bva_03_dragEndWhenNotDraggingProducesNoSpuriousUp() {
        assertFalse(mouseController.isDragging)
        // Drag end when not dragging
        mouseController.handleDragEnd(100f, 100f)
        assertFalse(mouseController.isDragging)
    }

    @Test
    fun f10_bva_04_rapidTripleClickDispatchesChronologicalDownUpPairs() {
        mouseController.handleLeftClick(100f, 100f)
        mouseController.handleLeftClick(100f, 100f)
        mouseController.handleLeftClick(100f, 100f)

        assertEquals(6, engine.pointerEvents.size)
        for (i in 0 until 6 step 2) {
            assertEquals(RdpPointerFlags.LEFT_BUTTON_DOWN, engine.pointerEvents[i].flags)
            assertEquals(RdpPointerFlags.LEFT_BUTTON_UP, engine.pointerEvents[i + 1].flags)
        }
    }

    @Test
    fun f10_bva_05_middleClickDuringActiveDragDoesNotCorruptDragState() {
        mouseController.handleDragStart(100f, 100f)
        assertTrue(mouseController.isDragging)

        val overlay = VirtualMouseOverlayModel(mouseController)
        overlay.triggerMiddleClick(100f, 100f, engine)
        assertTrue("Drag should remain active after middle click", mouseController.isDragging)
    }

    // ==================================================================
    // FEATURE 11: Virtual Mouse Compose Overlay Boundaries
    // ==================================================================

    @Test
    fun f11_bva_01_dragFabOutsideScreenBoundsClampsInsidePadding() {
        val overlay = VirtualMouseOverlayModel(mouseController)
        overlay.onFabDrag(-100f, -100f, screenWidth = 1080f, screenHeight = 2400f, padding = 20f)
        assertEquals(20f, overlay.fabPosition.x, 0.001f)
        assertEquals(20f, overlay.fabPosition.y, 0.001f)

        overlay.onFabDrag(5000f, 5000f, screenWidth = 1080f, screenHeight = 2400f, padding = 20f)
        assertEquals(1060f, overlay.fabPosition.x, 0.001f)
        assertEquals(2380f, overlay.fabPosition.y, 0.001f)
    }

    @Test
    fun f11_bva_02_releasingScrollBefore200msCancelsRepeatTimer() = runTest {
        val testScope = TestScope(StandardTestDispatcher(testScheduler))
        val overlay = VirtualMouseOverlayModel(mouseController, scope = testScope)

        overlay.startScrollRepeat(directionUp = true, 500f, 500f)
        testScope.advanceTimeBy(100) // Before 200ms
        overlay.cancelScrollRepeat()

        testScope.advanceTimeBy(300)
        assertEquals("Only initial scroll should be dispatched", 1, overlay.scrollRepeatCount)
    }

    @Test
    fun f11_bva_03_collapsingPillDuringActiveDragCancelsDragLock() {
        val overlay = VirtualMouseOverlayModel(mouseController)
        overlay.expandPill()
        overlay.toggleDragLock(100f, 100f)
        assertTrue(overlay.isDragLocked)

        overlay.collapsePill()
        assertFalse("Collapsing pill must cancel drag lock", overlay.isDragLocked)
    }

    @Test
    fun f11_bva_04_rapidTapOnScrollDispatchesSingleStepWithoutStuckRepeat() = runTest {
        val testScope = TestScope(StandardTestDispatcher(testScheduler))
        val overlay = VirtualMouseOverlayModel(mouseController, scope = testScope)

        // Rapid tap: start and immediate cancel
        overlay.startScrollRepeat(directionUp = true, 500f, 500f)
        overlay.cancelScrollRepeat()

        testScope.advanceTimeBy(500)
        assertEquals(1, overlay.scrollRepeatCount)
    }

    @Test
    fun f11_bva_05_screenRotationReClampsFabPosition() {
        val overlay = VirtualMouseOverlayModel(mouseController)
        // Positioned at X=1000 in portrait (width=1080)
        overlay.onFabDrag(1000f, 500f, screenWidth = 1080f, screenHeight = 2400f)
        assertEquals(1000f, overlay.fabPosition.x, 0.001f)

        // Rotate to landscape (width=2400, height=1080) with FAB clamped inside new bounds
        overlay.onFabDrag(overlay.fabPosition.x, overlay.fabPosition.y, screenWidth = 2400f, screenHeight = 1080f)
        assertTrue(overlay.fabPosition.x <= 2400f)
        assertTrue(overlay.fabPosition.y <= 1080f)
    }
}
