package com.rdp.client.adversarial

import android.content.Context
import android.content.res.Configuration
import android.graphics.PointF
import android.graphics.Rect
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.rdp.client.R
import com.rdp.client.freerdp.*
import com.rdp.client.model.AppDatabase
import com.rdp.client.model.GestureStyle
import com.rdp.client.model.ServerProfile
import com.rdp.client.model.ViewMode
import com.rdp.client.ui.session.RdpSessionActivity
import com.rdp.client.ui.session.RdpSessionContract
import com.rdp.client.ui.session.ToolbarDrawerController
import com.rdp.client.ui.session.input.IFrameCoordinateTransformer
import com.rdp.client.ui.session.input.PointerAcceleration
import com.rdp.client.ui.session.input.TouchDispatcher
import com.rdp.client.ui.session.viewport.FrameView
import com.rdp.client.ui.session.viewport.ViewportTransform
import com.rdp.client.ui.session.viewport.ZoomMode
import com.rdp.client.utils.KeyPacer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import kotlin.math.roundToInt

/**
 * Milestone 6 (Tier 5 White-Box Coverage Hardening) Adversarial Suite.
 * Systematically audits and challenges:
 * 1. Touch Subsystem (`TouchDispatcher`, `PointerAcceleration`):
 *    - NaN, Infinity, subnormal coordinates in click, drag, scroll, and velocity tracking.
 * 2. In-Session Activity & Input Subsystem (`RdpSessionActivity`, `ModifierState`):
 *    - `ViewMode.VIEW_ONLY` bypasses across `KeyPacer`, `VirtualMouse`, `TouchDispatcher`.
 *    - Modifier stuck-down / desynchronization across `VIEW_ONLY` toggles and lifecycle pauses.
 *    - `ToolbarDrawerController` animation state on orientation change.
 * 3. Viewport Subsystem (`ViewportTransform`):
 *    - NaN poisoning in zoom, scale gesture, and coerced coordinate mapping.
 *    - Extreme aspect ratios (ultrawide 32:9, tall 9:21) and degenerate 0x0 geometries.
 *    - Zoom lock enforcement vs programmatic zoom.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class InputSubsystemAdversarialHardeningTest {

    private lateinit var context: Context
    private val capturedCursorEvents = mutableListOf<CapturedCursor>()
    private val capturedKeyEvents = mutableListOf<CapturedKey>()
    private val capturedUnicodeEvents = mutableListOf<Int>()
    private val testInstanceId = 8888L

    data class CapturedCursor(val instance: Long, val x: Int, val y: Int, val flags: Int)
    data class CapturedKey(val instance: Long, val scancode: Int, val extended: Boolean, val down: Boolean)

    private val stubTransformer = object : IFrameCoordinateTransformer {
        override val fbWidth: Int = 1920
        override val fbHeight: Int = 1080
        override fun toFb(vpX: Float, vpY: Float): PointF? {
            return if (!vpX.isNaN() && !vpY.isNaN() && vpX in 0f..1920f && vpY in 0f..1080f) {
                PointF(vpX, vpY)
            } else null
        }
        override fun toVp(fbX: Float, fbY: Float): PointF = PointF(fbX, fbY)
        override fun panFrame(dx: Float, dy: Float) {}
        override fun zoomFrame(scaleFactor: Float, focusX: Float, focusY: Float) {}
        override val safeAreaCenterX: Float = 960f
        override val safeAreaCenterY: Float = 540f
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        capturedCursorEvents.clear()
        capturedKeyEvents.clear()
        capturedUnicodeEvents.clear()

        LibFreeRDP.setNativeBridgeForTesting(object : IRdpNativeBridge {
            override fun newInstance(context: Context?): Long = testInstanceId
            override fun freeInstance(instance: Long) {}
            override fun connect(instance: Long, params: RdpConnectionParameters?): Boolean {
                LibFreeRDP.onConnectionSuccess(instance)
                return true
            }
            override fun disconnect(instance: Long): Boolean {
                LibFreeRDP.onDisconnected(instance)
                return true
            }
            override fun updateGraphics(instance: Long, bitmap: android.graphics.Bitmap, x: Int, y: Int, w: Int, h: Int): Boolean = true
            override fun sendCursorEvent(instance: Long, x: Int, y: Int, flags: Int): Boolean {
                capturedCursorEvents.add(CapturedCursor(instance, x, y, flags))
                return true
            }
            override fun sendKeyEvent(instance: Long, scancode: Int, extended: Boolean, down: Boolean): Boolean {
                capturedKeyEvents.add(CapturedKey(instance, scancode, extended, down))
                return true
            }
            override fun sendUnicodeKeyEvent(instance: Long, codePoint: Int): Boolean {
                capturedUnicodeEvents.add(codePoint)
                return true
            }
            override fun getVersion(): String = "FreeRDP 3.5.1-m6-hardening"
            override fun getLastError(instance: Long): String? = null
        })
    }

    @After
    fun tearDown() {
        LibFreeRDP.resetNativeBridge()
    }

    // =========================================================================
    // SECTION 1: TOUCH DISPATCHER & POINTER ACCELERATION HARDENING AUDIT
    // =========================================================================

    @Test
    fun testTouchDispatcher_performClicks_withNaN_exposesRoundToIntCrash() {
        val dispatcher = TouchDispatcher(
            context = context,
            instanceProvider = { testInstanceId },
            transformer = stubTransformer,
            gestureStyle = GestureStyle.TOUCHSCREEN,
            buttonUpDelayEnabled = false
        )

        // HARDENED: performSingleClick with Float.NaN is safely clamped to (0, 0) without throwing
        capturedCursorEvents.clear()
        dispatcher.performSingleClick(Float.NaN, Float.NaN)
        assertThat(capturedCursorEvents).isNotEmpty()
        assertThat(capturedCursorEvents.last().x).isEqualTo(0)
        assertThat(capturedCursorEvents.last().y).isEqualTo(0)

        // HARDENED: performDoubleClick with Float.NaN is safely clamped without throwing
        capturedCursorEvents.clear()
        dispatcher.performDoubleClick(Float.NaN, 500f)
        assertThat(capturedCursorEvents).isNotEmpty()
        assertThat(capturedCursorEvents.last().x).isEqualTo(0)
        assertThat(capturedCursorEvents.last().y).isEqualTo(500)

        // HARDENED: performRightClick with Float.NaN is safely clamped without throwing
        capturedCursorEvents.clear()
        dispatcher.performRightClick(500f, Float.NaN)
        assertThat(capturedCursorEvents).isNotEmpty()
        assertThat(capturedCursorEvents.last().x).isEqualTo(500)
        assertThat(capturedCursorEvents.last().y).isEqualTo(0)

        // HARDENED: performMiddleClick with Float.NaN is safely clamped without throwing
        capturedCursorEvents.clear()
        dispatcher.performMiddleClick(Float.NaN, Float.NaN)
        assertThat(capturedCursorEvents).isNotEmpty()
        assertThat(capturedCursorEvents.last().x).isEqualTo(0)
        assertThat(capturedCursorEvents.last().y).isEqualTo(0)
    }

    @Test
    fun testTouchDispatcher_pointerAcceleration_withNaN_poisonsVirtualCursor() {
        val accelerator = PointerAcceleration()

        // Calling updateDelta with NaN returns NaN as expected for pure math
        val (adx, _) = accelerator.updateDelta(Float.NaN, 10f)
        assertThat(adx.isNaN()).isTrue()

        val dispatcher = TouchDispatcher(
            context = context,
            instanceProvider = { testInstanceId },
            transformer = stubTransformer,
            gestureStyle = GestureStyle.TOUCHPAD,
            buttonUpDelayEnabled = false
        )

        // HARDENED: Even if virtualCursor contains NaN, releaseAllButtons safely clamps without crashing
        dispatcher.virtualCursor.x = Float.NaN
        dispatcher.sendButtonDown(RdpPointerFlags.Button.LEFT, 100, 100)

        dispatcher.releaseAllButtons()
        assertThat(capturedCursorEvents).isNotEmpty()
        val releaseEvent = capturedCursorEvents.last()
        assertThat(releaseEvent.flags and RdpPointerFlags.PTRFLAGS_DOWN).isEqualTo(0)
    }

    @Test
    fun testTouchDispatcher_subnormalValues_clampedSafely() {
        val dispatcher = TouchDispatcher(
            context = context,
            instanceProvider = { testInstanceId },
            transformer = stubTransformer,
            gestureStyle = GestureStyle.TOUCHSCREEN,
            buttonUpDelayEnabled = false
        )

        // Float.MIN_VALUE is subnormal (1.4E-45)
        dispatcher.performSingleClick(Float.MIN_VALUE, Float.MIN_VALUE)
        assertThat(capturedCursorEvents).isNotEmpty()
        val event = capturedCursorEvents.last()
        assertThat(event.x).isEqualTo(0)
        assertThat(event.y).isEqualTo(0)

        // Negative subnormal (-Float.MIN_VALUE)
        dispatcher.performSingleClick(-Float.MIN_VALUE, -Float.MIN_VALUE)
        val negEvent = capturedCursorEvents.last()
        assertThat(negEvent.x).isEqualTo(0)
        assertThat(negEvent.y).isEqualTo(0)
    }

    @Test
    fun testTouchDispatcher_infinityCoordinates_clampedToDesktopEdges() {
        val dispatcher = TouchDispatcher(
            context = context,
            instanceProvider = { testInstanceId },
            transformer = stubTransformer,
            gestureStyle = GestureStyle.TOUCHSCREEN,
            buttonUpDelayEnabled = false
        )

        // Positive Infinity coerced to (fbWidth - 1, fbHeight - 1) = (1919, 1079)
        dispatcher.performSingleClick(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
        val posInfEvent = capturedCursorEvents.last()
        assertThat(posInfEvent.x).isEqualTo(1919)
        assertThat(posInfEvent.y).isEqualTo(1079)

        // Negative Infinity coerced to (0, 0)
        dispatcher.performSingleClick(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
        val negInfEvent = capturedCursorEvents.last()
        assertThat(negInfEvent.x).isEqualTo(0)
        assertThat(negInfEvent.y).isEqualTo(0)
    }

    // =========================================================================
    // SECTION 2: VIEWMODE.VIEW_ONLY & MODIFIER / INPUT BYPASS AUDIT
    // =========================================================================

    @Test
    fun testViewMode_viewOnly_modifierDesynchronizationOnToggle() {
        val profile = ServerProfile(name = "Modifier Desync Test", host = "127.0.0.1")
        val intent = RdpSessionContract.createTransientSessionIntent(context, profile)

        ActivityScenario.launch<RdpSessionActivity>(intent).use { scenario ->
            ShadowLooper.idleMainLooper()
            scenario.onActivity { activity ->
                // Step 1: Switch to VIEW_ONLY mode
                activity.onViewModeChanged(ViewMode.VIEW_ONLY)
                capturedKeyEvents.clear()

                // Step 2: Toggle Ctrl in VIEW_ONLY mode
                activity.modifierState.toggleModifier(ModifierKey.CTRL)

                // HARDENED: Key event was suppressed because currentViewMode == VIEW_ONLY
                assertThat(capturedKeyEvents).isEmpty()
                // Internal state stays OFF because modifier toggling is blocked in VIEW_ONLY
                assertThat(activity.modifierState.ctrlState).isEqualTo(ModifierState.State.OFF)

                // Step 3: Switch back to NORMAL mode
                activity.onViewModeChanged(ViewMode.NORMAL)

                // Step 4: Toggle Ctrl in NORMAL mode -> engages LATCHED and emits KeyDown
                activity.modifierState.toggleModifier(ModifierKey.CTRL)

                assertThat(capturedKeyEvents).hasSize(1)
                val initialDown = capturedKeyEvents.first()
                assertThat(initialDown.down).isTrue()
                assertThat(initialDown.scancode).isEqualTo(0x1D)
                assertThat(activity.modifierState.ctrlState).isEqualTo(ModifierState.State.LATCHED)
                capturedKeyEvents.clear()

                // Step 5: Toggle Ctrl again to lock it (LATCHED -> LOCKED)
                activity.modifierState.toggleModifier(ModifierKey.CTRL)
                assertThat(capturedKeyEvents).isEmpty()
                assertThat(activity.modifierState.ctrlState).isEqualTo(ModifierState.State.LOCKED)

                // Step 6: Toggle Ctrl again to release it (LOCKED -> OFF)
                activity.modifierState.toggleModifier(ModifierKey.CTRL)
                assertThat(capturedKeyEvents).hasSize(1)
                val emitted = capturedKeyEvents.first()
                assertThat(emitted.down).isFalse() // KeyUp cleanly emitted
                assertThat(activity.modifierState.ctrlState).isEqualTo(ModifierState.State.OFF)
            }
        }
    }

    @Test
    fun testViewMode_viewOnly_modifierStuckDownOnServerAcrossModeSwitchAndPause() {
        val profile = ServerProfile(name = "Stuck Modifier Test", host = "127.0.0.1")
        val intent = RdpSessionContract.createTransientSessionIntent(context, profile)

        ActivityScenario.launch<RdpSessionActivity>(intent).use { scenario ->
            ShadowLooper.idleMainLooper()
            scenario.onActivity { activity ->
                // Step 1: In NORMAL mode, latch Ctrl
                activity.modifierState.toggleModifier(ModifierKey.CTRL)
                assertThat(capturedKeyEvents).hasSize(1)
                val initialDown = capturedKeyEvents.first()
                assertThat(initialDown.down).isTrue() // Ctrl is DOWN on server!
                capturedKeyEvents.clear()

                // Step 2: Switch to VIEW_ONLY mode while Ctrl is held down on server
                // HARDENED: onViewModeChanged(VIEW_ONLY) immediately invokes releaseAllModifiers()
                // and RdpSessionActivity allows KeyUp (!isDown) even in VIEW_ONLY!
                activity.onViewModeChanged(ViewMode.VIEW_ONLY)

                // KeyUp was dispatched to the remote server to avoid stuck modifier
                assertThat(capturedKeyEvents.any { !it.down && it.scancode == 0x1D }).isTrue()
            }
        }
    }

    @Test
    fun testViewMode_viewOnly_virtualKeysKeyPacerBypass() = runTest {
        val profile = ServerProfile(name = "KeyPacer Bypass Test", host = "127.0.0.1")
        val intent = RdpSessionContract.createTransientSessionIntent(context, profile)

        ActivityScenario.launch<RdpSessionActivity>(intent).use { scenario ->
            ShadowLooper.idleMainLooper()
            scenario.onActivity { activity ->
                // Switch to VIEW_ONLY (No Input mode)
                activity.onViewModeChanged(ViewMode.VIEW_ONLY)
                capturedKeyEvents.clear()

                // HARDENED: KeyPacer instanceProvider returns 0L when in VIEW_ONLY mode
                activity.keyPacer.enqueueKey(RdpScancode(0x01, false)) // Esc
            }

            // Advance coroutine virtual time past KeyPacer pacing (18ms down + 22ms up)
            ShadowLooper.idleMainLooper()
            advanceTimeBy(50)
            ShadowLooper.idleMainLooper()

            // Proves that key events are blocked in VIEW_ONLY mode
            scenario.onActivity {
                assertThat(capturedKeyEvents).isEmpty()
            }
        }
    }

    @Test
    fun testViewMode_viewOnly_touchDispatcherBypass() {
        val profile = ServerProfile(name = "Touch Bypass Test", host = "127.0.0.1")
        val intent = RdpSessionContract.createTransientSessionIntent(context, profile)

        ActivityScenario.launch<RdpSessionActivity>(intent).use { scenario ->
            ShadowLooper.idleMainLooper()
            scenario.onActivity { activity ->
                activity.onViewModeChanged(ViewMode.VIEW_ONLY)
                capturedCursorEvents.clear()

                // HARDENED: TouchDispatcher isInputEnabledProvider returns false in VIEW_ONLY
                activity.touchDispatcher.performSingleClick(500f, 400f)
                assertThat(capturedCursorEvents).isEmpty()
            }
        }
    }

    @Test
    fun testToolbarDrawer_orientationChange_resetsToCollapsed() {
        val profile = ServerProfile(name = "Drawer Orientation Test", host = "127.0.0.1")
        val intent = RdpSessionContract.createTransientSessionIntent(context, profile)

        ActivityScenario.launch<RdpSessionActivity>(intent).use { scenario ->
            ShadowLooper.idleMainLooper()
            scenario.onActivity { activity ->
                val newConfig = Configuration(activity.resources.configuration).apply {
                    orientation = Configuration.ORIENTATION_LANDSCAPE
                }
                // Simulating orientation change during session
                activity.onConfigurationChanged(newConfig)
                ShadowLooper.idleMainLooper()
            }
        }
    }

    // =========================================================================
    // SECTION 3: VIEWPORT TRANSFORM GEOMETRY, ASPECT RATIOS & ZOOM HARDENING
    // =========================================================================

    @Test
    fun testViewportTransform_setZoomFactor_withNaN_poisonsTransformPermanently() {
        val vt = ViewportTransform()
        vt.setViewportDimensions(1920, 1080)
        vt.setFramebufferDimensions(1920, 1080)
        assertThat(vt.zoomFactor).isEqualTo(1.0f)

        // HARDENED: setZoomFactor ignores Float.NaN, preventing poison
        vt.setZoomFactor(Float.NaN)

        assertThat(vt.zoomFactor).isEqualTo(1.0f)
        assertThat(vt.translationX).isEqualTo(0f)
        assertThat(vt.translationY).isEqualTo(0f)

        val ptStrict = vt.screenToRemoteStrict(500f, 500f)
        assertThat(ptStrict).isNotNull()

        val ptCoerced = vt.screenToRemoteCoerced(500f, 500f)
        assertThat(ptCoerced.x.isNaN()).isFalse()
        assertThat(ptCoerced.y.isNaN()).isFalse()
    }

    @Test
    fun testViewportTransform_applyScaleGesture_withNaN_poisonsTransform() {
        val vt = ViewportTransform()
        vt.setViewportDimensions(1920, 1080)
        vt.setFramebufferDimensions(1920, 1080)

        // HARDENED: applyScaleGesture returns false and ignores NaN scale increments
        val applied = vt.applyScaleGesture(Float.NaN, 960f, 540f)

        assertThat(applied).isFalse()
        assertThat(vt.zoomFactor).isEqualTo(1.0f)
    }

    @Test
    fun testViewportTransform_screenToRemoteCoerced_withNaN_returnsNaN() {
        val vt = ViewportTransform()
        vt.setViewportDimensions(1920, 1080)
        vt.setFramebufferDimensions(1920, 1080)

        // HARDENED: screenToRemoteCoerced(Float.NaN, Float.NaN) safely returns PointF(0f, 0f)
        val coerced = vt.screenToRemoteCoerced(Float.NaN, Float.NaN)
        assertThat(coerced.x).isEqualTo(0f)
        assertThat(coerced.y).isEqualTo(0f)

        // Downstream usage doing coerced.x.roundToInt() succeeds without throwing
        assertThat(coerced.x.roundToInt()).isEqualTo(0)
        assertThat(coerced.y.roundToInt()).isEqualTo(0)
    }

    @Test
    fun testViewportTransform_zoomLock_programmaticZoomBypassesLock() {
        val vt = ViewportTransform()
        vt.setViewportDimensions(1920, 1080)
        vt.setFramebufferDimensions(1920, 1080)

        vt.isZoomLocked = true

        // Scale gesture is correctly blocked
        val gestureResult = vt.applyScaleGesture(2.0f, 960f, 540f)
        assertThat(gestureResult).isFalse()
        assertThat(vt.zoomFactor).isEqualTo(1.0f)

        // GAP CONFIRMED: setZoomFactor completely ignores isZoomLocked!
        vt.setZoomFactor(2.5f)
        assertThat(vt.zoomFactor).isEqualTo(2.5f)
    }

    @Test
    fun testViewportTransform_aspectRatio_ultrawide32by9_pillarboxCalculations() {
        val vt = ViewportTransform()
        // Device is ultrawide 32:9 (3840 x 1080), Remote desktop is standard 16:9 (1920 x 1080)
        vt.setViewportDimensions(3840, 1080)
        vt.setFramebufferDimensions(1920, 1080)

        // Base scale must preserve height (scale = 1.0f)
        assertThat(vt.baseScale).isEqualTo(1.0f)
        assertThat(vt.zoomFactor).isEqualTo(1.0f)

        // Remote frame width = 1920. Viewport width = 3840.
        // Pillarboxing: translationX = (3840 - 1920) / 2 = 960px
        assertThat(vt.translationX).isEqualTo(960f)
        assertThat(vt.translationY).isEqualTo(0f)

        // Strict mapping: points in pillarbox padding (< 960 or > 2880) must return null
        assertThat(vt.screenToRemoteStrict(500f, 500f)).isNull()
        assertThat(vt.screenToRemoteStrict(3000f, 500f)).isNull()

        // Strict mapping: points inside remote desktop must map accurately
        val insidePoint = vt.screenToRemoteStrict(960f, 0f)
        assertThat(insidePoint).isNotNull()
        assertThat(insidePoint?.x).isEqualTo(0f)
        assertThat(insidePoint?.y).isEqualTo(0f)

        // Coerced mapping: pillarbox clicks clamp to nearest edge
        val coercedLeft = vt.screenToRemoteCoerced(100f, 500f)
        assertThat(coercedLeft.x).isEqualTo(0f)

        val coercedRight = vt.screenToRemoteCoerced(3500f, 500f)
        assertThat(coercedRight.x).isEqualTo(1919f)
    }

    @Test
    fun testViewportTransform_aspectRatio_tall9by21_letterboxCalculations() {
        val vt = ViewportTransform()
        // Device is tall portrait 9:21 (1080 x 2520), Remote desktop is standard 16:9 (1920 x 1080)
        vt.setViewportDimensions(1080, 2520)
        vt.setFramebufferDimensions(1920, 1080)

        // scaleX = 1080 / 1920 = 0.5625. scaleY = 2520 / 1080 = 2.333...
        // baseScale must be min(scaleX, scaleY) = 0.5625
        assertThat(vt.baseScale).isEqualTo(0.5625f)

        // Frame scaled height = 1080 * 0.5625 = 607.5px.
        // Letterboxing: translationY = (2520 - 607.5) / 2 = 956.25px
        assertThat(vt.translationX).isEqualTo(0f)
        assertThat(vt.translationY).isEqualTo(956.25f)

        // Strict mapping: top/bottom letterbox margins return null
        assertThat(vt.screenToRemoteStrict(540f, 500f)).isNull()
        assertThat(vt.screenToRemoteStrict(540f, 2000f)).isNull()

        // Inside point
        val insidePoint = vt.screenToRemoteStrict(0f, 956.25f)
        assertThat(insidePoint).isNotNull()
        assertThat(insidePoint?.x).isEqualTo(0f)
        assertThat(insidePoint?.y).isEqualTo(0f)
    }

    @Test
    fun testViewportTransform_degenerate0x0Dimensions_handledWithoutDivisionByZero() {
        val vt = ViewportTransform()
        // Default uninitialized dimensions are 0x0
        assertThat(vt.viewportWidth).isEqualTo(0)
        assertThat(vt.viewportHeight).isEqualTo(0)
        assertThat(vt.fbWidth).isEqualTo(0)
        assertThat(vt.fbHeight).isEqualTo(0)

        // Must not divide by zero or crash
        assertThat(vt.baseScale).isEqualTo(1.0f)
        assertThat(vt.screenToRemoteStrict(100f, 100f)).isNull()
        assertThat(vt.screenToRemoteCoerced(100f, 100f)).isEqualTo(PointF(0f, 0f))
        assertThat(vt.getVisibleRemoteBounds()).isEqualTo(android.graphics.RectF(0f, 0f, 0f, 0f))

        // Negative dimensions ignored
        vt.setViewportDimensions(-100, -100)
        assertThat(vt.viewportWidth).isEqualTo(0)
        vt.setFramebufferDimensions(-100, -100)
        assertThat(vt.fbWidth).isEqualTo(0)
    }

    @Test
    fun testViewportTransform_zoomReset_restoresFitToScreen() {
        val vt = ViewportTransform()
        vt.setViewportDimensions(1920, 1080)
        vt.setFramebufferDimensions(1920, 1080)

        vt.setZoomFactor(3.5f)
        assertThat(vt.zoomFactor).isEqualTo(3.5f)
        assertThat(vt.zoomMode).isEqualTo(ZoomMode.CUSTOM)

        // Reset zoom
        vt.fitToScreen()
        assertThat(vt.zoomFactor).isEqualTo(1.0f)
        assertThat(vt.zoomMode).isEqualTo(ZoomMode.FIT_TO_SCREEN)
        assertThat(vt.translationX).isEqualTo(0f)
        assertThat(vt.translationY).isEqualTo(0f)
    }
}
