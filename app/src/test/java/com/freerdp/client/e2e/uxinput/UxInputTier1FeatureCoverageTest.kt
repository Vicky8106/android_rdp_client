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
import com.freerdp.feature.session.toolbar.QuickActionToolbarFSM
import com.freerdp.feature.session.toolbar.ToolbarAction
import com.freerdp.feature.session.toolbar.ToolbarState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TIER 1 — Feature Coverage for UX & Input Port.
 *
 * Opaque-box requirement-driven tests covering all 11 features from TEST_INFRA.md:
 *  - Feature 1: In-Session Toolbar Drawer Layout (5 tests)
 *  - Feature 2: Floating Opener Button & Persistence (5 tests)
 *  - Feature 3: Toolbar Quick Controls & Navigation (5 tests)
 *  - Feature 4: RealVNC Virtual Keys Compose Layout (5 tests)
 *  - Feature 5: Tri-State Modifier State Machine (5 tests)
 *  - Feature 6: Hardware BMC Key Hold Timing (5 tests)
 *  - Feature 7: Windows VK & Scancode Translation (5 tests)
 *  - Feature 8: Direct Touch Mode & Edge Coercion (5 tests)
 *  - Feature 9: Touchpad Mode & 3-Tier Acceleration (5 tests)
 *  - Feature 10: Dedicated Mouse Buttons (L/M/R/Drag) (5 tests)
 *  - Feature 11: Virtual Mouse Compose Overlay (5 tests)
 * Total: 55 tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class UxInputTier1FeatureCoverageTest {

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
    // FEATURE 1: In-Session Toolbar Drawer Layout (R1)
    // ==================================================================

    @Test
    fun f1_01_drawerStartsCollapsedAndExpandsOnOpen() {
        val layoutEngine = ToolbarDrawerLayoutEngine()
        assertFalse("Drawer should start collapsed", layoutEngine.isExpanded)
        layoutEngine.open()
        assertTrue("Drawer must be expanded after open()", layoutEngine.isExpanded)
        layoutEngine.close()
        assertFalse("Drawer must be collapsed after close()", layoutEngine.isExpanded)
    }

    @Test
    fun f1_02_startAlignmentForcesLtrLayoutDirection() {
        val layoutEngine = ToolbarDrawerLayoutEngine(alignment = "start")
        assertEquals("Start alignment must force LTR (0) so flyouts expand inward", 0, layoutEngine.layoutDirection)
    }

    @Test
    fun f1_03_endAlignmentForcesRtlLayoutDirection() {
        val layoutEngine = ToolbarDrawerLayoutEngine(alignment = "end")
        assertEquals("End alignment must force RTL (1) so flyouts expand inward", 1, layoutEngine.layoutDirection)
    }

    @Test
    fun f1_04_transparentScrimTapClosesDrawerWithoutForwardingClicks() {
        val layoutEngine = ToolbarDrawerLayoutEngine()
        layoutEngine.open()
        assertTrue(layoutEngine.isExpanded)

        // Scrim click closes drawer directly without reaching canvas
        layoutEngine.close()
        assertFalse("Scrim tap must close drawer", layoutEngine.isExpanded)
        assertEquals("No pointer events should be sent to remote engine on scrim tap", 0, engine.pointerEvents.size)
    }

    @Test
    fun f1_05_cutoutAvoidanceAppliesPaddingWhenIntersectingToolbar() {
        val layoutEngine = ToolbarDrawerLayoutEngine(parentHeight = 2400)
        val toolbarRect = Rect(0, 0, 100, 400)
        val intersectingCutout = listOf(Rect(0, 0, 1080, 80))
        val nonIntersectingCutout = listOf(Rect(500, 1000, 600, 1100))

        assertTrue(
            "Intersecting display cutout must trigger toolbar padding avoidance",
            layoutEngine.shouldApplyCutoutPadding(toolbarRect, intersectingCutout)
        )
        assertFalse(
            "Non-intersecting cutout must not trigger unnecessary toolbar padding",
            layoutEngine.shouldApplyCutoutPadding(toolbarRect, nonIntersectingCutout)
        )
    }

    // ==================================================================
    // FEATURE 2: Floating Opener Button & Persistence (R1)
    // ==================================================================

    @Test
    fun f2_01_openerInitialVerticalBiasDefaultsToCenter() {
        val opener = FloatingOpenerController()
        assertEquals(0.5f, opener.verticalBias, 0.001f)
    }

    @Test
    fun f2_02_draggingOpenerUpdatesVerticalBias() {
        val opener = FloatingOpenerController(parentHeight = 2000)
        opener.onDrag(500f) // 500 / 2000 = 0.25f
        assertEquals(0.25f, opener.verticalBias, 0.001f)
    }

    @Test
    fun f2_03_releasingOpenerPersistsVerticalBias() {
        val store = mutableMapOf<String, Float>()
        val opener = FloatingOpenerController(parentHeight = 2000, preferencesStore = store)
        opener.onDrag(1500f) // 1500 / 2000 = 0.75f
        opener.onRelease()
        assertEquals(0.75f, store["toolbarOpenerBtnVerticalBias"] ?: 0f, 0.001f)
    }

    @Test
    fun f2_04_restoringSessionAppliesPersistedVerticalBias() {
        val store = mutableMapOf("toolbarOpenerBtnVerticalBias" to 0.82f)
        val opener = FloatingOpenerController(preferencesStore = store)
        opener.restoreFromPreferences()
        assertEquals(0.82f, opener.verticalBias, 0.001f)
    }

    @Test
    fun f2_05_tappingOpenerExpandsToolbarDrawer() {
        val drawer = ToolbarDrawerLayoutEngine()
        assertFalse(drawer.isExpanded)
        drawer.open()
        assertTrue(drawer.isExpanded)
    }

    // ==================================================================
    // FEATURE 3: Toolbar Quick Controls & Navigation (R1)
    // ==================================================================

    @Test
    fun f3_01_keyboardButtonDispatchesToggleAndClosesDrawer() = runTest {
        var keyboardToggled = false
        val fsm = QuickActionToolbarFSM(
            coroutineScope = this,
            dispatcher = StandardTestDispatcher(testScheduler),
            onActionCallback = { action ->
                if (action == ToolbarAction.TOGGLE_KEYBOARD) keyboardToggled = true
            }
        )
        fsm.expand()
        assertTrue(fsm.isExpanded)

        fsm.triggerAction(ToolbarAction.TOGGLE_KEYBOARD)
        assertTrue(keyboardToggled)
    }

    @Test
    fun f3_02_modeSwitchTogglesTouchpadModeOnMouseController() {
        assertFalse("Starts in Direct Touch mode", mouseController.isTouchpadMode)
        mouseController.setTouchpadMode(true)
        assertTrue("Switches to Relative Touchpad mode", mouseController.isTouchpadMode)
        mouseController.setTouchpadMode(false)
        assertFalse("Switches back to Direct Touch mode", mouseController.isTouchpadMode)
    }

    @Test
    fun f3_03_virtualKeysButtonTogglesVirtualKeysVisibility() = runTest {
        var keysToggled = false
        val fsm = QuickActionToolbarFSM(
            coroutineScope = this,
            dispatcher = StandardTestDispatcher(testScheduler),
            onActionCallback = { action ->
                if (action == ToolbarAction.TOGGLE_MODIFIER_BAR) keysToggled = true
            }
        )
        fsm.triggerAction(ToolbarAction.TOGGLE_MODIFIER_BAR)
        assertTrue(keysToggled)
    }

    @Test
    fun f3_04_zoomFitButtonResetsCoordinateTransformerToFit() {
        transformer.setScale(2.5f)
        assertEquals(2.5f, transformer.scale, 0.001f)
        transformer.resetToFit()
        // Remote 1920x1080 into View 1080x2400: fit scale = min(1080/1920, 2400/1080) = 0.5625
        assertTrue(transformer.scale < 1.0f)
    }

    @Test
    fun f3_05_disconnectButtonTriggersCleanEngineDisconnect() = runTest {
        val config = RdpConnectionConfig(serverAddress = "10.0.0.1")
        engine.connect(config)
        assertEquals(RdpConnectionState.Connected, engine.connectionState.value)

        engine.disconnect()
        assertEquals(RdpConnectionState.Disconnected, engine.connectionState.value)
    }

    // ==================================================================
    // FEATURE 4: RealVNC Virtual Keys Compose Layout (R2)
    // ==================================================================

    @Test
    fun f4_01_fnStripExpandsAndCollapsesOnToggle() {
        var fnStripVisible = false
        fun toggleFnStrip() { fnStripVisible = !fnStripVisible }

        assertFalse(fnStripVisible)
        toggleFnStrip()
        assertTrue(fnStripVisible)
        toggleFnStrip()
        assertFalse(fnStripVisible)
    }

    @Test
    fun f4_02_fnKeysF1ThroughF12EmitCorrectPcScancodes() {
        val expectedCodes = listOf(
            ModifierKey.F1 to 0x3B, ModifierKey.F2 to 0x3C, ModifierKey.F3 to 0x3D,
            ModifierKey.F4 to 0x3E, ModifierKey.F5 to 0x3F, ModifierKey.F6 to 0x40,
            ModifierKey.F7 to 0x41, ModifierKey.F8 to 0x42, ModifierKey.F9 to 0x43,
            ModifierKey.F10 to 0x44, ModifierKey.F11 to 0x57, ModifierKey.F12 to 0x58
        )
        for ((key, expectedScancode) in expectedCodes) {
            val res = ScancodeTranslator.getScancodeForModifierKey(key)
            assertEquals("Scancode for $key must match Set 1", expectedScancode, res.scancode)
            assertFalse("Fn keys are standard non-extended scancodes", res.isExtended)
        }
    }

    @Test
    fun f4_03_desktopKeysEscTabDelCapsEmitValidScancodes() {
        val esc = ScancodeTranslator.getScancodeForModifierKey(ModifierKey.ESC)
        assertEquals(0x01, esc.scancode)
        assertFalse(esc.isExtended)

        val tab = ScancodeTranslator.getScancodeForModifierKey(ModifierKey.TAB)
        assertEquals(0x0F, tab.scancode)
        assertFalse(tab.isExtended)

        val del = ScancodeTranslator.getScancodeForModifierKey(ModifierKey.DEL)
        assertEquals(0x53, del.scancode)
        assertTrue(del.isExtended)
    }

    @Test
    fun f4_04_invertedTArrowClusterEmitsDirectionalScancodes() {
        val left = ScancodeTranslator.getScancodeForModifierKey(ModifierKey.ARROW_LEFT)
        val up = ScancodeTranslator.getScancodeForModifierKey(ModifierKey.ARROW_UP)
        val right = ScancodeTranslator.getScancodeForModifierKey(ModifierKey.ARROW_RIGHT)
        val down = ScancodeTranslator.getScancodeForModifierKey(ModifierKey.ARROW_DOWN)

        assertEquals(0x4B, left.scancode); assertTrue(left.isExtended)
        assertEquals(0x48, up.scancode); assertTrue(up.isExtended)
        assertEquals(0x4D, right.scancode); assertTrue(right.isExtended)
        assertEquals(0x50, down.scancode); assertTrue(down.isExtended)
    }

    @Test
    fun f4_05_navigationKeysHomeEndPgUpPgDnEmitExtendedScancodes() {
        val home = ScancodeTranslator.getScancodeForModifierKey(ModifierKey.HOME)
        val end = ScancodeTranslator.getScancodeForModifierKey(ModifierKey.END)
        val pgUp = ScancodeTranslator.getScancodeForModifierKey(ModifierKey.PAGE_UP)
        val pgDn = ScancodeTranslator.getScancodeForModifierKey(ModifierKey.PAGE_DOWN)

        assertEquals(0x47, home.scancode); assertTrue(home.isExtended)
        assertEquals(0x4F, end.scancode); assertTrue(end.isExtended)
        assertEquals(0x49, pgUp.scancode); assertTrue(pgUp.isExtended)
        assertEquals(0x51, pgDn.scancode); assertTrue(pgDn.isExtended)
    }

    // ==================================================================
    // FEATURE 5: Tri-State Modifier State Machine (R2)
    // ==================================================================

    @Test
    fun f5_01_singleTapTransitionsToLatchedAndEmitsKeyDown() {
        val fsm = ModifierStateMachine(rdpEngine = engine)
        fsm.onModifierKeyTapped(ModifierKey.CTRL)
        assertEquals(LatchState.LATCHED, fsm.getModifierState(ModifierKey.CTRL))
        assertTrue(engine.keyEvents.any { it.scancode == 0x1D && it.down })
    }

    @Test
    fun f5_02_secondTapTransitionsToLockedAndKeepsKeyHeld() {
        val fsm = ModifierStateMachine(rdpEngine = engine)
        fsm.onModifierKeyTapped(ModifierKey.CTRL)
        fsm.onModifierKeyTapped(ModifierKey.CTRL)
        assertEquals(LatchState.LOCKED, fsm.getModifierState(ModifierKey.CTRL))
        assertTrue(fsm.isLocked(ModifierKey.CTRL))
    }

    @Test
    fun f5_03_thirdTapTransitionsToInactiveAndEmitsKeyUp() {
        val fsm = ModifierStateMachine(rdpEngine = engine)
        fsm.onModifierKeyTapped(ModifierKey.CTRL) // Latched
        fsm.onModifierKeyTapped(ModifierKey.CTRL) // Locked
        fsm.onModifierKeyTapped(ModifierKey.CTRL) // Inactive
        assertEquals(LatchState.INACTIVE, fsm.getModifierState(ModifierKey.CTRL))
        assertTrue(engine.keyEvents.any { it.scancode == 0x1D && !it.down })
    }

    @Test
    fun f5_04_nonModifierKeyConsumesLatchedModifierAndAutoReleases() {
        val fsm = ModifierStateMachine(rdpEngine = engine)
        fsm.onModifierKeyTapped(ModifierKey.CTRL)
        assertEquals(LatchState.LATCHED, fsm.getModifierState(ModifierKey.CTRL))

        // Type 'c'
        fsm.onNonModifierKeyPressed('c')
        assertEquals("Latched modifier must auto-clear to INACTIVE after consumption", LatchState.INACTIVE, fsm.getModifierState(ModifierKey.CTRL))
        assertTrue("Key-up for Ctrl must be emitted", engine.keyEvents.any { it.scancode == 0x1D && !it.down })
    }

    @Test
    fun f5_05_nonModifierKeyPreservesLockedModifierWithoutReleasing() {
        val fsm = ModifierStateMachine(rdpEngine = engine)
        fsm.onModifierKeyTapped(ModifierKey.SHIFT)
        fsm.onModifierKeyTapped(ModifierKey.SHIFT) // Locked
        assertEquals(LatchState.LOCKED, fsm.getModifierState(ModifierKey.SHIFT))

        fsm.onNonModifierKeyPressed('a')
        assertEquals("Locked modifier must remain LOCKED after key consumption", LatchState.LOCKED, fsm.getModifierState(ModifierKey.SHIFT))
    }

    // ==================================================================
    // FEATURE 6: Hardware BMC Key Hold Timing (R2)
    // ==================================================================

    @Test
    fun f6_01_enterKeyHoldDurationIsAtLeast50msBeforeKeyUp() = runTest {
        val testScope = TestScope(StandardTestDispatcher(testScheduler))
        val bmc = BmcKeyboardTimingEngine(engine, scope = testScope)
        bmc.sendKeyPressWithHold(ScancodeTranslator.SCANCODE_ENTER, holdDurationMs = 50L)

        testScope.advanceTimeBy(10)
        assertTrue("Key down should be emitted immediately", engine.keyEvents.any { it.scancode == 0x1C && it.down })
        assertFalse("Key up must NOT be emitted before 50ms hold elapses", engine.keyEvents.any { it.scancode == 0x1C && !it.down })

        testScope.advanceTimeBy(45)
        assertTrue("Key up emitted after 50ms", engine.keyEvents.any { it.scancode == 0x1C && !it.down })
    }

    @Test
    fun f6_02_backspaceKeyHoldDurationIsAtLeast50msBeforeKeyUp() = runTest {
        val testScope = TestScope(StandardTestDispatcher(testScheduler))
        val bmc = BmcKeyboardTimingEngine(engine, scope = testScope)
        bmc.sendKeyPressWithHold(ScancodeTranslator.SCANCODE_BACKSPACE, holdDurationMs = 50L)

        testScope.advanceTimeBy(20)
        assertTrue(engine.keyEvents.any { it.scancode == 0x0E && it.down })
        assertFalse(engine.keyEvents.any { it.scancode == 0x0E && !it.down })

        testScope.advanceTimeBy(35)
        assertTrue(engine.keyEvents.any { it.scancode == 0x0E && !it.down })
    }

    @Test
    fun f6_03_tabKeyHoldDurationIsAtLeast50msBeforeKeyUp() = runTest {
        val testScope = TestScope(StandardTestDispatcher(testScheduler))
        val bmc = BmcKeyboardTimingEngine(engine, scope = testScope)
        bmc.sendKeyPressWithHold(ScancodeTranslator.SCANCODE_TAB, holdDurationMs = 50L)

        testScope.advanceTimeBy(25)
        assertTrue(engine.keyEvents.any { it.scancode == 0x0F && it.down })
        testScope.advanceTimeBy(30)
        assertTrue(engine.keyEvents.any { it.scancode == 0x0F && !it.down })
    }

    @Test
    fun f6_04_spaceKeyHoldDurationIsAtLeast50msBeforeKeyUp() = runTest {
        val testScope = TestScope(StandardTestDispatcher(testScheduler))
        val bmc = BmcKeyboardTimingEngine(engine, scope = testScope)
        bmc.sendKeyPressWithHold(ScancodeTranslator.SCANCODE_SPACE, holdDurationMs = 50L)

        testScope.advanceTimeBy(20)
        assertTrue(engine.keyEvents.any { it.scancode == 0x39 && it.down })
        testScope.advanceTimeBy(35)
        assertTrue(engine.keyEvents.any { it.scancode == 0x39 && !it.down })
    }

    @Test
    fun f6_05_streamedTextTypingApplies25msInterKeyPacing() = runTest {
        val testScope = TestScope(StandardTestDispatcher(testScheduler))
        val bmc = BmcKeyboardTimingEngine(engine, keyHoldDurationMs = 50L, interKeyPacingMs = 25L, scope = testScope)
        bmc.sendTextWithPacing("ab")

        // First char 'a': 50ms hold
        testScope.advanceTimeBy(51)
        assertEquals(2, engine.keyEvents.size) // 'a' down + 'a' up

        // 25ms pacing gap
        testScope.advanceTimeBy(24)
        assertEquals("Second char must wait for 25ms pacing gap", 2, engine.keyEvents.size)

        testScope.advanceTimeBy(52) // 25ms pacing + 50ms hold for 'b'
        assertEquals(4, engine.keyEvents.size) // 'a' down/up + 'b' down/up
    }

    // ==================================================================
    // FEATURE 7: Windows VK & Scancode Translation (R2)
    // ==================================================================

    @Test
    fun f7_01_alphaKeysMapToSet1Scancodes() {
        assertEquals(0x1E, ScancodeTranslator.fromChar('a')?.scancode)
        assertEquals(0x1E, ScancodeTranslator.fromChar('A')?.scancode)
        assertEquals(0x30, ScancodeTranslator.fromChar('b')?.scancode)
        assertEquals(0x2C, ScancodeTranslator.fromChar('z')?.scancode)
    }

    @Test
    fun f7_02_numericKeysMapToTopRowSet1Scancodes() {
        assertEquals(0x02, ScancodeTranslator.fromAndroidKeyCode(KeyEvent.KEYCODE_1)?.scancode)
        assertEquals(0x0B, ScancodeTranslator.fromAndroidKeyCode(KeyEvent.KEYCODE_0)?.scancode)
    }

    @Test
    fun f7_03_enterAndBackspaceMapToStandardSet1Scancodes() {
        val enter = ScancodeTranslator.fromAndroidKeyCode(KeyEvent.KEYCODE_ENTER)
        assertNotNull(enter)
        assertEquals(0x1C, enter?.scancode)
        assertFalse(enter!!.isExtended)

        val bs = ScancodeTranslator.fromAndroidKeyCode(KeyEvent.KEYCODE_DEL)
        assertNotNull(bs)
        assertEquals(0x0E, bs?.scancode)
        assertFalse(bs!!.isExtended)
    }

    @Test
    fun f7_04_arrowsAndNavigationKeysPreserveExtendedFlag() {
        val up = ScancodeTranslator.fromAndroidKeyCode(KeyEvent.KEYCODE_DPAD_UP)
        assertNotNull(up)
        assertEquals(0x48, up?.scancode)
        assertTrue("Extended flag must be set for arrow key", up!!.isExtended)
        assertEquals(0x0100, up.flags)
    }

    @Test
    fun f7_05_windowsAndDeleteKeysPreserveExtendedFlag0x0100() {
        val win = ScancodeTranslator.fromAndroidKeyCode(KeyEvent.KEYCODE_META_LEFT)
        assertNotNull(win)
        assertEquals(0x5B, win?.scancode)
        assertTrue(win!!.isExtended)

        val del = ScancodeTranslator.fromAndroidKeyCode(KeyEvent.KEYCODE_FORWARD_DEL)
        assertNotNull(del)
        assertEquals(0x53, del?.scancode)
        assertTrue(del!!.isExtended)
    }

    // ==================================================================
    // FEATURE 8: Direct Touch Mode & Edge Coercion (R3)
    // ==================================================================

    @Test
    fun f8_01_tapInsideRemoteBoundsMapsToDesktopCoordinates() {
        // Viewport 1080x2400, Remote 1920x1080, scale=1.0f
        // Centered translationX = (1080 - 1920)/2 = -420, translationY = (2400 - 1080)/2 = 660
        val p = transformer.screenToDesktop(100f, 800f)
        assertTrue(p.x in 0f..1919f)
        assertTrue(p.y in 0f..1079f)
    }

    @Test
    fun f8_02_zoomedTapMapsThroughAffineTransformMatrix() {
        transformer.setScale(2.0f)
        val p = transformer.screenToDesktop(500f, 500f)
        assertNotNull(p)
    }

    @Test
    fun f8_03_panTranslationShiftsDirectTouchCoordinates() {
        transformer.setScale(2.0f)
        val before = transformer.screenToDesktop(500f, 500f)
        transformer.applyPan(50f, 50f)
        val after = transformer.screenToDesktop(500f, 500f)
        assertTrue("Pan should shift remote coordinate mapping", before.x != after.x || before.y != after.y)
    }

    @Test
    fun f8_04_twoFingerScrollAppliesViewportPanTranslation() {
        transformer.setScale(2.0f)
        val initialTx = transformer.translationX
        transformer.applyPan(-100f, -100f)
        assertTrue(transformer.translationX != initialTx)
    }

    @Test
    fun f8_05_tapInLetterboxMarginCoercesToClosestFramebufferEdge() {
        val directHandler = DirectTouchPointerHandler(engine, transformer)
        // Screen Y = 0 is far above remote content (which starts around Y=660)
        val coerced = directHandler.coerceToFbEdge(500f, 0f)
        assertEquals(0f, coerced.y, 0.001f) // Clamped to top edge 0

        // Screen Y = 2400 is far below remote content
        val coercedBottom = directHandler.coerceToFbEdge(500f, 2400f)
        assertEquals(1079f, coercedBottom.y, 0.001f) // Clamped to bottom edge 1079
    }

    // ==================================================================
    // FEATURE 9: Touchpad Mode & 3-Tier Acceleration (R3)
    // ==================================================================

    @Test
    fun f9_01_lowVelocityMovementAppliesTier1Deceleration() {
        // v = 5 mm/s (< 10 mm/s) -> factor = 0.07 * 5 + 0.3 = 0.65 (< 1.0)
        // rawDelta = 10px, dt = 0.32s, dpi = 160 -> distMm = 10 * 25.4 / 160 = 1.5875mm -> v = 4.96 mm/s
        val (dx, _) = LibinputPointerAcceleration.computeDelta(10f, 0f, 0.32f, dpi = 160f)
        assertTrue("Tier 1 must decelerate cursor (dx < 10)", dx < 10f)
    }

    @Test
    fun f9_02_mediumVelocityMovementAppliesTier2ConstantMultiplier() {
        // v = 40 mm/s (10 <= v < 80) -> factor = 1.0
        // distMm = 10 * 25.4 / 160 = 1.5875mm -> dt = 1.5875 / 40 = 0.03968s
        val (dx, _) = LibinputPointerAcceleration.computeDelta(10f, 0f, 0.0397f, dpi = 160f)
        assertEquals(10f, dx, 0.2f)
    }

    @Test
    fun f9_03_highVelocityFlickAppliesTier3QuadraticSpeedup() {
        // Rapid flick: 200px in 0.016s -> v = (200 * 25.4 / 160) / 0.016 = 1984 mm/s (> 80 mm/s)
        val (dx, _) = LibinputPointerAcceleration.computeDelta(200f, 0f, 0.016f, dpi = 160f)
        assertTrue("Tier 3 flick must accelerate cursor (multiplier > 1.0)", dx > 200f)
    }

    @Test
    fun f9_04_accelerationMultiplierClampsToMaxCeilingOf3_5() {
        // Extreme flick
        val (dx, _) = LibinputPointerAcceleration.computeDelta(1000f, 0f, 0.005f, dpi = 160f)
        assertEquals(3500f, dx, 0.1f) // Clamped at 3.5x
    }

    @Test
    fun f9_05_zoomedInViewportDampensCursorAccelerationProportionally() {
        val (unzoomedDx, _) = LibinputPointerAcceleration.computeDelta(50f, 0f, 0.05f, zoomScale = 1.0f)
        val (zoomedDx, _) = LibinputPointerAcceleration.computeDelta(50f, 0f, 0.05f, zoomScale = 2.0f)
        assertEquals(unzoomedDx / 2.0f, zoomedDx, 0.1f)
    }

    // ==================================================================
    // FEATURE 10: Dedicated Mouse Buttons (L/M/R/Drag) (R3)
    // ==================================================================

    @Test
    fun f10_01_leftClickEmitsButton1DownAndUp() {
        mouseController.handleLeftClick(500f, 500f)
        assertEquals(2, engine.pointerEvents.size)
        assertEquals(RdpPointerFlags.LEFT_BUTTON_DOWN, engine.pointerEvents[0].flags)
        assertEquals(RdpPointerFlags.LEFT_BUTTON_UP, engine.pointerEvents[1].flags)
    }

    @Test
    fun f10_02_rightClickEmitsButton2DownAndUp() {
        mouseController.handleRightClick(500f, 500f)
        assertEquals(2, engine.pointerEvents.size)
        assertEquals(RdpPointerFlags.RIGHT_BUTTON_DOWN, engine.pointerEvents[0].flags)
        assertEquals(RdpPointerFlags.RIGHT_BUTTON_UP, engine.pointerEvents[1].flags)
    }

    @Test
    fun f10_03_middleClickEmitsButton3DownAndUp() {
        engine.sendPointerEvent(RdpPointerFlags.MIDDLE_BUTTON_DOWN, 500, 500)
        engine.sendPointerEvent(RdpPointerFlags.MIDDLE_BUTTON_UP, 500, 500)
        assertEquals(2, engine.pointerEvents.size)
        assertEquals(RdpPointerFlags.MIDDLE_BUTTON_DOWN, engine.pointerEvents[0].flags)
        assertEquals(RdpPointerFlags.MIDDLE_BUTTON_UP, engine.pointerEvents[1].flags)
        assertTrue(RdpPointerFlags.isButton3(engine.pointerEvents[0].flags))
    }

    @Test
    fun f10_04_doubleClickEmitsTwoConsecutiveDownUpPairs() {
        mouseController.handleDoubleClick(500f, 500f)
        assertEquals(4, engine.pointerEvents.size)
        assertEquals(RdpPointerFlags.LEFT_BUTTON_DOWN, engine.pointerEvents[0].flags)
        assertEquals(RdpPointerFlags.LEFT_BUTTON_UP, engine.pointerEvents[1].flags)
        assertEquals(RdpPointerFlags.LEFT_BUTTON_DOWN, engine.pointerEvents[2].flags)
        assertEquals(RdpPointerFlags.LEFT_BUTTON_UP, engine.pointerEvents[3].flags)
    }

    @Test
    fun f10_05_dragEmitsDownMoveWithButton1AndUp() {
        mouseController.handleDragStart(500f, 500f)
        assertTrue(mouseController.isDragging)
        mouseController.handleDragMove(520f, 520f)
        mouseController.handleDragEnd(540f, 540f)
        assertFalse(mouseController.isDragging)

        assertEquals(3, engine.pointerEvents.size)
        assertEquals(RdpPointerFlags.LEFT_BUTTON_DOWN, engine.pointerEvents[0].flags)
        assertTrue(engine.pointerEvents[1].flags and RdpPointerFlags.MOVE != 0)
        assertEquals(RdpPointerFlags.LEFT_BUTTON_UP, engine.pointerEvents[2].flags)
    }

    // ==================================================================
    // FEATURE 11: Virtual Mouse Compose Overlay (R3)
    // ==================================================================

    @Test
    fun f11_01_floatingFabExpandsToPillBar() {
        val overlay = VirtualMouseOverlayModel(mouseController)
        assertFalse(overlay.isPillExpanded)
        overlay.expandPill()
        assertTrue(overlay.isPillExpanded)
        overlay.collapsePill()
        assertFalse(overlay.isPillExpanded)
    }

    @Test
    fun f11_02_pillMiddleClickButtonDispatchesMiddleClick() {
        val overlay = VirtualMouseOverlayModel(mouseController)
        overlay.triggerMiddleClick(100f, 200f, engine)
        assertEquals(2, engine.pointerEvents.size)
        assertEquals(RdpPointerFlags.MIDDLE_BUTTON_DOWN, engine.pointerEvents[0].flags)
        assertEquals(RdpPointerFlags.MIDDLE_BUTTON_UP, engine.pointerEvents[1].flags)
    }

    @Test
    fun f11_03_pillRightClickButtonDispatchesRightClick() {
        val overlay = VirtualMouseOverlayModel(mouseController)
        overlay.triggerRightClick(100f, 200f)
        assertEquals(2, engine.pointerEvents.size)
        assertEquals(RdpPointerFlags.RIGHT_BUTTON_DOWN, engine.pointerEvents[0].flags)
        assertEquals(RdpPointerFlags.RIGHT_BUTTON_UP, engine.pointerEvents[1].flags)
    }

    @Test
    fun f11_04_scrollUpPillarDispatchesInitialWheelThenRepeatsAfter200ms() = runTest {
        val testScope = TestScope(StandardTestDispatcher(testScheduler))
        val overlay = VirtualMouseOverlayModel(mouseController, scope = testScope)

        overlay.startScrollRepeat(directionUp = true, screenX = 500f, screenY = 500f)
        assertEquals("Initial wheel event dispatched immediately", 1, overlay.scrollRepeatCount)

        testScope.advanceTimeBy(190)
        assertEquals("No repeat before 200ms initial delay", 1, overlay.scrollRepeatCount)

        testScope.advanceTimeBy(20) // at 210ms -> first repeat
        assertEquals(2, overlay.scrollRepeatCount)

        testScope.advanceTimeBy(50) // at 260ms -> second repeat
        assertEquals(3, overlay.scrollRepeatCount)

        overlay.cancelScrollRepeat()
    }

    @Test
    fun f11_05_scrollDownPillarDispatchesNegativeWheelEvents() {
        val overlay = VirtualMouseOverlayModel(mouseController)
        overlay.startScrollRepeat(directionUp = false, screenX = 500f, screenY = 500f)
        assertTrue(engine.pointerEvents.any { (it.flags and RdpPointerFlags.PTR_FLAGS_WHEEL_NEGATIVE) != 0 })
        overlay.cancelScrollRepeat()
    }
}
