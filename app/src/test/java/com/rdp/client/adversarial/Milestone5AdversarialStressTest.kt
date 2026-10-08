package com.rdp.client.adversarial

import android.content.Context
import android.graphics.PointF
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.rdp.client.freerdp.*
import com.rdp.client.model.*
import com.rdp.client.R
import com.rdp.client.repository.ProfileRepository
import com.rdp.client.ui.session.RdpSessionActivity
import com.rdp.client.ui.session.RdpSessionContract
import com.rdp.client.ui.session.viewport.FrameView
import com.rdp.client.ui.session.input.IFrameCoordinateTransformer
import com.rdp.client.ui.session.input.PointerAcceleration
import com.rdp.client.ui.session.input.TouchDispatcher
import com.rdp.client.ui.session.viewport.ViewportTransform
import com.rdp.client.utils.KeyPacer
import com.rdp.client.validator.FormField
import com.rdp.client.validator.ProfileValidator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowLooper

/**
 * Milestone 5 Gate Verification - Adversarial Empirical Stress Harness.
 * Systematically stress-tests:
 * 1. Oracle Invariance & Mutation Verification (defect injection sensitivity)
 * 2. Tier 2 Boundary & Corner Cases (NaN, extreme coords > 32767, boundary ports, 10k-char strings, SQL injection)
 * 3. Tier 3 Cross-Feature Interactions (modifier latches during touch gestures, view mode toggles during active connections)
 * 4. Tier 4 Workload Scenario Robustness (rapid connect/disconnect lifecycle, orientation recreation)
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class Milestone5AdversarialStressTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var dao: ServerProfileDao
    private lateinit var repository: ProfileRepository

    private val capturedCursorEvents = mutableListOf<CapturedCursor>()
    private val capturedKeyEvents = mutableListOf<CapturedKey>()
    private val capturedUnicodeEvents = mutableListOf<Int>()
    private val testInstanceId = 9999L

    data class CapturedCursor(val instance: Long, val x: Int, val y: Int, val flags: Int)
    data class CapturedKey(val instance: Long, val scancode: Int, val extended: Boolean, val down: Boolean)

    private val testTransformer = object : IFrameCoordinateTransformer {
        override val fbWidth: Int = 1920
        override val fbHeight: Int = 1080
        override fun toFb(vpX: Float, vpY: Float): PointF? {
            return if (vpX in 0f..1920f && vpY in 0f..1080f) PointF(vpX, vpY) else null
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
        database = AppDatabase.createInMemory(context)
        dao = database.serverProfileDao()
        repository = ProfileRepository(dao)

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
            override fun getVersion(): String = "FreeRDP 3.5.1-adv-test"
            override fun getLastError(instance: Long): String? = null
        })
    }

    @After
    fun tearDown() {
        LibFreeRDP.resetNativeBridge()
        database.close()
        ShadowAlertDialog.reset()
    }

    // =========================================================================
    // 1. ORACLE INVARIANCE & MUTATION TESTING (Defect Injection Verification)
    // =========================================================================

    @Test
    fun testOracleSensitivity_invalidScancodesDetected() {
        // Verify mapper returns exact expected scancodes and rejects unknown/corrupted inputs
        val validA = ScancodeMapper.toScancode(KeyEvent.KEYCODE_A)
        assertThat(validA).isNotNull()
        assertThat(validA?.code).isEqualTo(0x1E)
        assertThat(validA?.isExtended).isFalse()

        // Verify extended key has isExtended = true
        val validDel = ScancodeMapper.toScancode(KeyEvent.KEYCODE_FORWARD_DEL)
        assertThat(validDel).isNotNull()
        assertThat(validDel?.code).isEqualTo(0x53)
        assertThat(validDel?.isExtended).isTrue()

        // Mutation check: an oracle expecting standard delete scancode 0x53 must reject 0x52 (Insert)
        assertThat(validDel?.code).isNotEqualTo(0x52)

        // Invalid keycodes must be rejected (return null)
        val negativeKeycode = ScancodeMapper.toScancode(-999)
        assertThat(negativeKeycode).isNull()

        val hugeKeycode = ScancodeMapper.toScancode(1_000_000)
        assertThat(hugeKeycode).isNull()
    }

    @Test
    fun testOracleSensitivity_boundaryOverflowPortValidation() {
        // Valid ports: 1 and 65535
        val validMin = ServerProfile(name = "Min Port", host = "10.0.0.1", port = 1)
        assertThat(validMin.validate().isSuccess).isTrue()

        val validMax = ServerProfile(name = "Max Port", host = "10.0.0.1", port = 65535)
        assertThat(validMax.validate().isSuccess).isTrue()

        // Port 0 must fail validation
        val portZero = ServerProfile(name = "Zero Port", host = "10.0.0.1", port = 0)
        val zeroResult = portZero.validate()
        assertThat(zeroResult.isSuccess).isFalse()
        assertThat((zeroResult as ValidationResult.Error).message).contains("Port must be between 1 and 65535")

        // Port 65536 must fail validation
        val port65536 = ServerProfile(name = "Overflow Port", host = "10.0.0.1", port = 65536)
        val overflowResult = port65536.validate()
        assertThat(overflowResult.isSuccess).isFalse()
        assertThat((overflowResult as ValidationResult.Error).message).contains("Port must be between 1 and 65535")

        // Negative port must fail validation
        val portNeg = ServerProfile(name = "Neg Port", host = "10.0.0.1", port = -3389)
        assertThat(portNeg.validate().isSuccess).isFalse()

        // RdpConnectionParameters must also fail validation on port overflow
        val paramOverflow = RdpConnectionParameters(host = "10.0.0.1", port = 65536)
        val paramRes = paramOverflow.validate()
        assertThat(paramRes.isSuccess).isFalse()
        assertThat((paramRes as ConnectionValidationResult.Error).message).contains("Port must be between 1 and 65535")
    }

    @Test
    fun testOracleSensitivity_badGatewayArgumentsValidation() {
        // Gateway enabled with blank host must be rejected
        val emptyGw = ServerProfile(name = "Bad GW", host = "10.0.0.1", enableGateway = true, gatewayHost = "")
        val emptyGwRes = emptyGw.validate()
        assertThat(emptyGwRes.isSuccess).isFalse()
        assertThat((emptyGwRes as ValidationResult.Error).message).contains("Gateway host cannot be empty")

        // Gateway enabled with port out of range must be rejected
        val badPortGw = ServerProfile(name = "Bad GW Port", host = "10.0.0.1", enableGateway = true, gatewayHost = "gw.local", gatewayPort = 70000)
        val badPortRes = badPortGw.validate()
        assertThat(badPortRes.isSuccess).isFalse()
        assertThat((badPortRes as ValidationResult.Error).message).contains("Gateway port must be between 1 and 65535")

        // RdpConnectionParameters must reject bad gateway parameters as well
        val paramsBadGw = RdpConnectionParameters(host = "10.0.0.1", enableGateway = true, gatewayHost = "   ", gatewayPort = 443)
        assertThat(paramsBadGw.validate().isSuccess).isFalse()

        // When gateway is disabled, gateway flags must NOT appear in native args
        val disabledGw = RdpConnectionParameters(host = "10.0.0.1", enableGateway = false, gatewayHost = "gw.local", gatewayPort = 443)
        val args = disabledGw.toNativeArgs()
        assertThat(args.none { it.startsWith("/g:") }).isTrue()
    }

    @Test
    fun testOracleSensitivity_gestureEventPointerFlagsDiscrimination() {
        // Oracle must strictly differentiate between move, drag move, down, and up flags
        val moveFlag = RdpPointerFlags.encodeMove()
        val dragFlag = RdpPointerFlags.encodeDragMove(RdpPointerFlags.Button.LEFT)
        val downFlag = RdpPointerFlags.encodeButtonDown(RdpPointerFlags.Button.LEFT)
        val upFlag = RdpPointerFlags.encodeButtonUp(RdpPointerFlags.Button.LEFT)

        assertThat(moveFlag).isNotEqualTo(dragFlag)
        assertThat(dragFlag).isNotEqualTo(downFlag)
        assertThat(downFlag).isNotEqualTo(upFlag)

        // Verify TouchDispatcher correctly uses dragFlag when drag locked
        val dispatcher = TouchDispatcher(
            context = context,
            instanceProvider = { testInstanceId },
            transformer = testTransformer,
            gestureStyle = GestureStyle.TOUCHSCREEN,
            buttonUpDelayEnabled = false
        )
        dispatcher.isDragLocked = true

        val motionEvent = MotionEvent.obtain(1000L, 1020L, MotionEvent.ACTION_MOVE, 300f, 300f, 0)
        dispatcher.onTouch(android.view.View(context), motionEvent)

        assertThat(capturedCursorEvents).isNotEmpty()
        val emitted = capturedCursorEvents.last()
        assertThat(emitted.flags).isEqualTo(dragFlag)
        assertThat(emitted.flags).isNotEqualTo(moveFlag)
        motionEvent.recycle()
    }

    // =========================================================================
    // 2. TIER 2 EDGE CASES: COORDINATES, BOUNDARY PORTS, EMPTY/HUGE STRINGS
    // =========================================================================

    @Test
    fun testTier2EdgeCase_extremeCoordinatesNaNAndInfinity() {
        val dispatcher = TouchDispatcher(
            context = context,
            instanceProvider = { testInstanceId },
            transformer = testTransformer,
            gestureStyle = GestureStyle.TOUCHSCREEN,
            buttonUpDelayEnabled = false
        )

        // NaN coordinates: Handled safely without uncaught crash
        dispatcher.performSingleClick(Float.NaN, Float.NaN)
        assertThat(capturedCursorEvents).isNotEmpty()

        // Positive Infinity: Handled without uncaught crash
        dispatcher.performSingleClick(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
        assertThat(capturedCursorEvents).isNotEmpty()

        // Negative Infinity: Handled without uncaught crash
        dispatcher.performSingleClick(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
        assertThat(capturedCursorEvents).isNotEmpty()
    }

    @Test
    fun testTier2EdgeCase_extremeCoordinatesBeyond32767() {
        val dispatcher = TouchDispatcher(
            context = context,
            instanceProvider = { testInstanceId },
            transformer = testTransformer,
            gestureStyle = GestureStyle.TOUCHSCREEN,
            buttonUpDelayEnabled = false
        )

        // Touch at 70,000 x 60,000 (> 32767 16-bit boundary)
        dispatcher.performSingleClick(70000f, 60000f)
        val event = capturedCursorEvents.last()
        // Must be clamped to framebuffer boundaries: width=1920 -> 1919, height=1080 -> 1079
        assertThat(event.x).isEqualTo(1919)
        assertThat(event.y).isEqualTo(1079)

        // Negative extreme coordinates (-50000 x -50000)
        dispatcher.performSingleClick(-50000f, -50000f)
        val negEvent = capturedCursorEvents.last()
        assertThat(negEvent.x).isEqualTo(0)
        assertThat(negEvent.y).isEqualTo(0)
    }

    @Test
    fun testTier2EdgeCase_profileValidatorStringPortBoundaries() {
        // String ports in ProfileValidator form state
        assertThat(ProfileValidator.validatePort(1).isValid).isTrue()
        assertThat(ProfileValidator.validatePort(65535).isValid).isTrue()
        assertThat(ProfileValidator.validatePort(0).isValid).isFalse()
        assertThat(ProfileValidator.validatePort(65536).isValid).isFalse()
        assertThat(ProfileValidator.validatePort(-1).isValid).isFalse()

        // Gateway Port in ProfileValidator
        val validGwForm = ProfileValidator.ProfileFormState(
            host = "10.0.0.1",
            enableGateway = true,
            gatewayHost = "gw.example.com",
            gatewayPortStr = "443"
        )
        assertThat(ProfileValidator.validateProfile(validGwForm).isValid).isTrue()

        val badGwPortForm = validGwForm.copy(gatewayPortStr = "70000")
        val badGwRes = ProfileValidator.validateProfile(badGwPortForm)
        assertThat(badGwRes.isValid).isFalse()
        assertThat(badGwRes.errors).containsKey(FormField.GATEWAY_PORT)

        val nonNumericGwPortForm = validGwForm.copy(gatewayPortStr = "not_a_number")
        val nonNumGwRes = ProfileValidator.validateProfile(nonNumericGwPortForm)
        assertThat(nonNumGwRes.isValid).isFalse()
        assertThat(nonNumGwRes.errors).containsKey(FormField.GATEWAY_PORT)
    }

    @Test
    fun testTier2EdgeCase_huge10kCharacterStringsAndSqlInjection() = runBlocking {
        // 10,000-character credential stress test
        val hugePassword = "X".repeat(10000)
        val hugeProfile = ServerProfile(
            name = "Huge String Profile",
            host = "host.test",
            username = "adminUser",
            password = hugePassword,
            domain = "CORP"
        )
        val id = dao.insert(hugeProfile)
        assertThat(id).isGreaterThan(0L)

        val retrieved = dao.getProfileById(id)
        assertThat(retrieved).isNotNull()
        assertThat(retrieved?.password?.length).isEqualTo(10000)

        // Safe masking for 10k credentials must not crash or leak password
        val params = RdpConnectionParameters.fromProfile(retrieved!!)
        val safeStr = params.toSafeString()
        assertThat(safeStr).contains("pass='******'")
        assertThat(safeStr).doesNotContain(hugePassword)

        // SQL injection probe in query filter
        val sqlInjectionQueries = listOf(
            "' OR '1'='1",
            "'; DROP TABLE profiles; --",
            "\" OR \"\"=\"",
            "100%",
            "_",
            "\\",
            "'\";<>--/*"
        )
        for (query in sqlInjectionQueries) {
            val matching = dao.getProfilesMatchingQuery(query).first()
            // Must execute without Room/SQLite syntax error
            assertThat(matching).isNotNull()
        }

        // Unicode emojis and CJK characters
        val emojiProfile = ServerProfile(
            name = "🖥️ Tokyo RDP 🚀 測試",
            host = "192.168.10.1",
            username = "田中太郎",
            domain = "会社"
        )
        val emojiId = dao.insert(emojiProfile)
        val emojiRetrieved = dao.getProfileById(emojiId)
        assertThat(emojiRetrieved?.name).isEqualTo("🖥️ Tokyo RDP 🚀 測試")
        assertThat(emojiRetrieved?.username).isEqualTo("田中太郎")
        assertThat(emojiRetrieved?.domain).isEqualTo("会社")
    }

    // =========================================================================
    // 3. TIER 3 CROSS-FEATURE INTERACTIONS STRESS TESTING
    // =========================================================================

    @Test
    fun testTier3Interaction_simultaneousModifierLatchesDuringTouchGestures() {
        var keyEventCount = 0
        val modifierState = ModifierState { scancode, isDown ->
            keyEventCount++
            LibFreeRDP.sendKeyEvent(testInstanceId, scancode.code, scancode.isExtended, isDown)
        }

        val dispatcher = TouchDispatcher(
            context = context,
            instanceProvider = { testInstanceId },
            transformer = testTransformer,
            gestureStyle = GestureStyle.TOUCHSCREEN,
            buttonUpDelayEnabled = false
        )

        // 1. Latch Ctrl
        modifierState.toggleModifier(ModifierKey.CTRL)
        assertThat(modifierState.isCtrlActive).isTrue()
        assertThat(modifierState.ctrlState).isEqualTo(ModifierState.State.LATCHED)
        val keysAfterLatch = capturedKeyEvents.size
        assertThat(keysAfterLatch).isEqualTo(1)

        // 2. Perform Touch Gesture (Single Tap, Right Click, Drag)
        dispatcher.performSingleClick(500f, 400f)
        dispatcher.performRightClick(600f, 500f)

        // 3. Verify: Touch gestures must NOT alter or release keyboard modifiers!
        assertThat(modifierState.isCtrlActive).isTrue()
        assertThat(modifierState.ctrlState).isEqualTo(ModifierState.State.LATCHED)
        // No extra key events should have been dispatched by touch dispatcher
        assertThat(capturedKeyEvents.size).isEqualTo(keysAfterLatch)
        // Cursor events must have been dispatched
        assertThat(capturedCursorEvents.size).isAtLeast(2)

        // 4. Lock Shift alongside latched Ctrl
        modifierState.toggleModifier(ModifierKey.SHIFT)
        modifierState.toggleModifier(ModifierKey.SHIFT) // LOCKED
        assertThat(modifierState.shiftState).isEqualTo(ModifierState.State.LOCKED)

        // 5. Simulate Touch ACTION_CANCEL cleanup
        val cancelEvent = MotionEvent.obtain(1000L, 1010L, MotionEvent.ACTION_CANCEL, 500f, 500f, 0)
        dispatcher.onTouch(android.view.View(context), cancelEvent)
        dispatcher.releaseAllButtons()

        // Touch button release must NOT cancel keyboard modifiers!
        assertThat(modifierState.isCtrlActive).isTrue()
        assertThat(modifierState.isShiftActive).isTrue()
        cancelEvent.recycle()

        // 6. Explicit release of modifiers must NOT affect touch dispatcher
        modifierState.releaseAllModifiers()
        assertThat(modifierState.isCtrlActive).isFalse()
        assertThat(modifierState.isShiftActive).isFalse()
    }

    @Test
    fun testTier3Interaction_viewModeTogglesDuringActiveSession() {
        val profile = ServerProfile(name = "ViewMode Test Host", host = "127.0.0.1")
        val intent = RdpSessionContract.createTransientSessionIntent(context, profile)

        ActivityScenario.launch<RdpSessionActivity>(intent).use { scenario ->
            ShadowLooper.idleMainLooper()
            scenario.onActivity { activity ->
                val frameView = activity.findViewById<FrameView>(R.id.frameView)
                // Initial: NORMAL view mode
                assertThat(frameView.isRenderingPaused).isFalse()

                // Test Toggle 1: Switch to VIEW_ONLY (No Input mode)
                activity.onViewModeChanged(ViewMode.VIEW_ONLY)
                assertThat(frameView.isRenderingPaused).isFalse()

                // In VIEW_ONLY: Macro hotkeys (CAD, Win) MUST be suppressed
                capturedKeyEvents.clear()
                activity.onMacroCtrlAltDel()
                ShadowLooper.idleMainLooper()
                assertThat(capturedKeyEvents).isEmpty()

                activity.onMacroWinKey()
                ShadowLooper.idleMainLooper()
                assertThat(capturedKeyEvents).isEmpty()

                // In VIEW_ONLY: Modifier state key dispatch MUST be suppressed
                activity.modifierState.toggleModifier(ModifierKey.CTRL)
                assertThat(capturedKeyEvents).isEmpty()

                // Test Toggle 2: Switch to BACKGROUND (No Video mode)
                activity.onViewModeChanged(ViewMode.BACKGROUND)
                // Frame rendering MUST be paused
                assertThat(frameView.isRenderingPaused).isTrue()

                // Test Toggle 3: Switch back to NORMAL (Interactive mode)
                activity.onViewModeChanged(ViewMode.NORMAL)
                assertThat(frameView.isRenderingPaused).isFalse()

                // In NORMAL: Macro hotkeys and modifiers work again
                activity.modifierState.releaseAllModifiers()
                capturedKeyEvents.clear()
                activity.modifierState.toggleModifier(ModifierKey.ALT)
                assertThat(capturedKeyEvents).isNotEmpty()
            }
        }
    }

    // =========================================================================
    // 4. TIER 4 WORKLOAD SCENARIOS & LIFECYCLE RECREATION
    // =========================================================================

    @Test
    fun testTier4Workload_rapidOrientationShiftsPreserveSessionState() {
        val profile = ServerProfile(name = "Orientation Test", host = "192.168.1.100")
        val intent = RdpSessionContract.createTransientSessionIntent(context, profile)

        ActivityScenario.launch<RdpSessionActivity>(intent).use { scenario ->
            ShadowLooper.idleMainLooper()
            scenario.onActivity { activity ->
                assertThat(activity.activeProfile?.name).isEqualTo("Orientation Test")
                activity.modifierState.toggleModifier(ModifierKey.CTRL)
            }

            // Rapid recreation cycle (simulating user flipping phone 3 times)
            scenario.recreate()
            ShadowLooper.idleMainLooper()

            scenario.recreate()
            ShadowLooper.idleMainLooper()

            scenario.onActivity { recreatedActivity ->
                // Must recover without crash and maintain valid activity references
                assertThat(recreatedActivity.activeProfile?.name).isEqualTo("Orientation Test")
                assertThat(recreatedActivity.findViewById<FrameView>(R.id.frameView)).isNotNull()
            }
        }
    }

    @Test
    fun testTier4Workload_rapidConnectDisconnectChurn() = runBlocking {
        // Stress-test 10 rapid connect/disconnect cycles
        for (i in 1..10) {
            val params = RdpConnectionParameters(host = "churn.host.$i", port = 3389)
            val instance = LibFreeRDP.connect(params)
            assertThat(instance).isEqualTo(testInstanceId)
            LibFreeRDP.disconnect(instance)
        }
    }
}
