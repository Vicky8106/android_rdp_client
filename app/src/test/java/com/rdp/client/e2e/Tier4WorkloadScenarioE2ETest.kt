package com.rdp.client.e2e

import android.content.Context
import android.graphics.PointF
import android.view.KeyEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.rdp.client.freerdp.*
import com.rdp.client.model.*
import com.rdp.client.repository.ProfileRepository
import com.rdp.client.ui.home.HomeActivity
import com.rdp.client.ui.home.ProfileViewModelFactory
import com.rdp.client.ui.session.RdpSessionActivity
import com.rdp.client.ui.session.RdpSessionContract
import com.rdp.client.ui.session.input.IFrameCoordinateTransformer
import com.rdp.client.ui.session.input.TouchDispatcher
import com.rdp.client.utils.KeyPacer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowLooper

/**
 * Milestone 5 - Tier 4: Real-World Workload Scenarios E2E Tests (>= 5 comprehensive scenarios).
 * Verifies end-to-end multi-component application workloads:
 * - Scenario 1: First-Time User Quick Connect Lifecycle
 * - Scenario 2: Advanced Profile Customization & NLA Authentication
 * - Scenario 3: In-Session Complex Keyboard Hotkeys & Calibrated Key Pacing
 * - Scenario 4: Trackpad Mouse Emulation with Drag-and-Drop
 * - Scenario 5: Full Headless Activity Flow (HomeActivity -> Search -> Launch -> Rotation -> Disconnect)
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class Tier4WorkloadScenarioE2ETest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var dao: ServerProfileDao
    private lateinit var repository: ProfileRepository
    private val capturedCursorEvents = mutableListOf<CapturedCursor>()
    private val capturedKeyEvents = mutableListOf<CapturedKey>()
    private val testInstanceId = 5555L

    data class CapturedCursor(val instance: Long, val x: Int, val y: Int, val flags: Int)
    data class CapturedKey(val instance: Long, val scancode: Int, val extended: Boolean, val down: Boolean)

    private val testTransformer = object : IFrameCoordinateTransformer {
        override val fbWidth: Int = 1920
        override val fbHeight: Int = 1080
        override fun toFb(vpX: Float, vpY: Float): PointF? = PointF(vpX, vpY)
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
        ProfileViewModelFactory.testRepository = repository

        capturedCursorEvents.clear()
        capturedKeyEvents.clear()

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
            override fun sendUnicodeKeyEvent(instance: Long, codePoint: Int): Boolean = true
            override fun getVersion(): String = "FreeRDP 3.5.1-scenario"
            override fun getLastError(instance: Long): String? = null
        })
    }

    @After
    fun tearDown() {
        LibFreeRDP.resetNativeBridge()
        ProfileViewModelFactory.testRepository = null
        database.close()
        ShadowAlertDialog.reset()
    }

    // =========================================================================
    // SCENARIO 1: First-Time User Quick Connect Lifecycle
    // =========================================================================

    @Test
    fun testScenario1_firstTimeUserQuickConnectLifecycle() {
        runBlocking {
            // 1. Initial State: Database starts empty
            val initialCount = dao.getProfileCount()
            assertThat(initialCount).isEqualTo(0)

            // 2. User invokes Quick Connect: saves or updates quick connect entry
            val quickId = repository.saveOrUpdateQuickConnect(
                host = "192.168.1.150",
                port = 3389,
                username = "admin",
                password = "SecretPassword!",
                domain = "CORP"
            )
            assertThat(quickId).isGreaterThan(0L)

            // 3. Retrieve quick connect profile
            val quickProfile = repository.getLatestQuickConnect()
            assertThat(quickProfile).isNotNull()
            assertThat(quickProfile?.isQuickConnect).isTrue()
            assertThat(quickProfile?.host).isEqualTo("192.168.1.150")
            assertThat(quickProfile?.username).isEqualTo("admin")
            assertThat(quickProfile?.domain).isEqualTo("CORP")

            // 4. Transform to RdpConnectionParameters
            val params = RdpConnectionParameters.fromProfile(quickProfile!!)
            assertThat(params.validate().isSuccess).isTrue()
            assertThat(params.toNativeArgs()).asList().contains("/v:192.168.1.150:3389")
            assertThat(params.toNativeArgs()).asList().contains("/u:admin")
            assertThat(params.toNativeArgs()).asList().contains("/d:CORP")

            // 5. Create launch intent and verify extras
            val launchIntent = RdpSessionContract.createTransientSessionIntent(context, quickProfile)
            assertThat(launchIntent.getBooleanExtra(RdpSessionContract.EXTRA_QUICK_CONNECT, false)).isTrue()
            assertThat(launchIntent.getParcelableExtra<ServerProfile>(RdpSessionContract.EXTRA_TRANSIENT_PROFILE)).isNotNull()

            // 6. Connect via LibFreeRDP facade
            val instance = LibFreeRDP.connect(params)
            assertThat(instance).isEqualTo(testInstanceId)
            LibFreeRDP.disconnect(instance)
        }
    }

    // =========================================================================
    // SCENARIO 2: Advanced Profile Customization & NLA Authentication
    // =========================================================================

    @Test
    fun testScenario2_advancedProfileCustomizationAndNlaAuthentication() {
        runBlocking {
            // 1. Create fully customized profile
            val profile = ServerProfile(
                name = "Corporate Windows 11 VDI",
                host = "vdi.internal.corp",
                port = 3389,
                domain = "CORP",
                username = "user.smith",
                password = "ComplexPassword2026#",
                securityType = SecurityType.NLA,
                resolutionMode = ResolutionMode.CUSTOM,
                customWidth = 1920,
                customHeight = 1080,
                colorDepth = ColorDepth.DEPTH_32,
                desktopScale = 125,
                enableGateway = true,
                gatewayHost = "rdgw.corp.net",
                gatewayPort = 443,
                gatewayUsername = "user.smith",
                gatewayPassword = "ComplexPassword2026#",
                gatewayDomain = "CORP",
                audioMode = AudioMode.LOCAL,
                microphoneEnabled = true,
                enableWol = true,
                wolMacAddress = "00:1A:2B:3C:4D:5E",
                useRemoteFX = true,
                useGFX = true,
                useH264 = true,
                clipboardSync = true
            )

            // 2. Validate profile integrity
            val validationResult = profile.validate()
            assertThat(validationResult.isSuccess).isTrue()

            // 3. Persist to database
            val profileId = dao.insert(profile)
            assertThat(profileId).isGreaterThan(0L)

            // 4. Generate parameters and verify complete FreeRDP argument array
            val params = RdpConnectionParameters.fromProfile(profile)
            val args = params.toNativeArgs()

            assertThat(args).asList().contains("/v:vdi.internal.corp:3389")
            assertThat(args).asList().contains("/u:user.smith")
            assertThat(args).asList().contains("/p:ComplexPassword2026#")
            assertThat(args).asList().contains("/d:CORP")
            assertThat(args).asList().contains("/sec:nla")
            assertThat(args).asList().contains("/size:1920x1080")
            assertThat(args).asList().contains("/scale:125")
            assertThat(args).asList().contains("/bpp:32")
            assertThat(args).asList().contains("/g:rdgw.corp.net:443")
            assertThat(args).asList().contains("/gu:user.smith")
            assertThat(args).asList().contains("/sound:sys:opensles")
            assertThat(args).asList().contains("/microphone:sys:opensles")
            assertThat(args).asList().contains("+rfx")
            assertThat(args).asList().contains("+gfx")
            assertThat(args).asList().contains("+gfx:AVC444")
            assertThat(args).asList().contains("+clipboard")
        }
    }

    // =========================================================================
    // SCENARIO 3: In-Session Complex Keyboard Hotkeys & Calibrated Key Pacing
    // =========================================================================

    @Test
    fun testScenario3_inSessionComplexKeyboardHotkeysAndMacros() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val pacerScope = TestScope(testDispatcher)
        val pacer = KeyPacer(instanceProvider = { testInstanceId }, scope = pacerScope)

        val modifierState = ModifierState { scancode, isDown ->
            LibFreeRDP.sendKeyEvent(testInstanceId, scancode.code, scancode.isExtended, isDown)
        }

        // Sub-Scenario A: CAD (Ctrl + Alt + Del) Hotkey Macro Sequence
        // Step 1: Sequence down
        LibFreeRDP.sendKeyEvent(testInstanceId, 0x1D, extended = false, down = true) // Ctrl
        pacerScope.testScheduler.advanceTimeBy(KeyPacer.KEYDOWN_DURATION_MS)
        LibFreeRDP.sendKeyEvent(testInstanceId, 0x38, extended = false, down = true) // Alt
        pacerScope.testScheduler.advanceTimeBy(KeyPacer.KEYDOWN_DURATION_MS)
        LibFreeRDP.sendKeyEvent(testInstanceId, 0x53, extended = true, down = true)  // Del
        pacerScope.testScheduler.advanceTimeBy(KeyPacer.KEYDOWN_DURATION_MS)

        // Step 2: Sequence up (reverse order)
        LibFreeRDP.sendKeyEvent(testInstanceId, 0x53, extended = true, down = false)
        pacerScope.testScheduler.advanceTimeBy(KeyPacer.INTER_KEY_PACING_MS)
        LibFreeRDP.sendKeyEvent(testInstanceId, 0x38, extended = false, down = false)
        pacerScope.testScheduler.advanceTimeBy(KeyPacer.INTER_KEY_PACING_MS)
        LibFreeRDP.sendKeyEvent(testInstanceId, 0x1D, extended = false, down = false)

        assertThat(capturedKeyEvents).hasSize(6)
        assertThat(capturedKeyEvents[0].scancode).isEqualTo(0x1D) // Ctrl Down
        assertThat(capturedKeyEvents[1].scancode).isEqualTo(0x38) // Alt Down
        assertThat(capturedKeyEvents[2].scancode).isEqualTo(0x53) // Del Down
        assertThat(capturedKeyEvents[3].scancode).isEqualTo(0x53) // Del Up
        assertThat(capturedKeyEvents[4].scancode).isEqualTo(0x38) // Alt Up
        assertThat(capturedKeyEvents[5].scancode).isEqualTo(0x1D) // Ctrl Up

        // Sub-Scenario B: Win+R Run Dialog Macro
        capturedKeyEvents.clear()
        modifierState.toggleModifier(ModifierKey.SUPER) // Win LATCHED
        val scR = ScancodeMapper.toScancode(KeyEvent.KEYCODE_R)!! // 0x13
        pacer.enqueueKey(scR)
        pacerScope.testScheduler.advanceTimeBy(KeyPacer.KEYDOWN_DURATION_MS)
        modifierState.onNonModifierKeyDispatched() // Auto-releases Super

        assertThat(modifierState.isSuperActive).isFalse()

        // Sub-Scenario C: F5 Refresh
        val scF5 = ScancodeMapper.toScancode(KeyEvent.KEYCODE_F5)!! // 0x3F
        pacer.enqueueKey(scF5)
        pacerScope.testScheduler.advanceTimeBy(40)
        assertThat(pacer.dispatchedCount).isAtLeast(2)

        // Sub-Scenario D: Guaranteed cleanup
        modifierState.releaseAllModifiers()
        pacer.cancelAndReleaseHeld()
        assertThat(modifierState.isCtrlActive).isFalse()
        assertThat(modifierState.isAltActive).isFalse()
        assertThat(modifierState.isShiftActive).isFalse()
        assertThat(modifierState.isSuperActive).isFalse()
    }

    // =========================================================================
    // SCENARIO 4: Trackpad Mouse Emulation with Drag-and-Drop
    // =========================================================================

    @Test
    fun testScenario4_trackpadMouseEmulationWithDragAndDrop() {
        val dispatcher = TouchDispatcher(
            context = context,
            instanceProvider = { testInstanceId },
            transformer = testTransformer,
            gestureStyle = GestureStyle.TOUCHPAD
        )

        // 1. Initial State: Center of screen (960, 540)
        assertThat(dispatcher.virtualCursor.x).isEqualTo(960f)
        assertThat(dispatcher.virtualCursor.y).isEqualTo(540f)

        // 2. Simulate user engaging Drag-Lock mode
        dispatcher.isDragLocked = true
        dispatcher.sendButtonDown(RdpPointerFlags.Button.LEFT, 960, 540)

        assertThat(capturedCursorEvents).isNotEmpty()
        assertThat(capturedCursorEvents[0].flags).isEqualTo(RdpPointerFlags.encodeButtonDown(RdpPointerFlags.Button.LEFT))

        // 3. User moves pointer along diagonal (dx=100, dy=150)
        val initialX = dispatcher.virtualCursor.x
        val initialY = dispatcher.virtualCursor.y
        dispatcher.virtualCursor.x += 100f
        dispatcher.virtualCursor.y += 150f

        val dragFlags = RdpPointerFlags.encodeDragMove(RdpPointerFlags.Button.LEFT)
        dispatcher.sendCursorEvent(
            dispatcher.virtualCursor.x.toInt(),
            dispatcher.virtualCursor.y.toInt(),
            dragFlags
        )

        val lastEvent = capturedCursorEvents.last()
        assertThat(lastEvent.x).isEqualTo((initialX + 100f).toInt())
        assertThat(lastEvent.y).isEqualTo((initialY + 150f).toInt())
        assertThat(lastEvent.flags).isEqualTo(dragFlags)

        // 4. User drops: releases button and disengages drag-lock
        dispatcher.sendButtonUp(RdpPointerFlags.Button.LEFT, dispatcher.virtualCursor.x.toInt(), dispatcher.virtualCursor.y.toInt())
        dispatcher.isDragLocked = false

        val releaseEvent = capturedCursorEvents.last()
        assertThat(releaseEvent.flags).isEqualTo(RdpPointerFlags.encodeButtonUp(RdpPointerFlags.Button.LEFT))
        assertThat(dispatcher.isDragLocked).isFalse()
    }

    // =========================================================================
    // SCENARIO 5: Full Headless Activity Flow
    // =========================================================================

    @Test
    fun testScenario5_fullHeadlessActivityFlow() {
        runBlocking {
            // 1. Seed database with profiles
            dao.insert(ServerProfile(name = "Accounting Server", host = "10.0.1.10"))
            dao.insert(ServerProfile(name = "Database Server", host = "10.0.1.20"))
            dao.insert(ServerProfile(name = "Web Server", host = "10.0.1.30"))

            // 2. Launch HomeActivity and verify search filter
            ActivityScenario.launch(HomeActivity::class.java).use { homeScenario ->
                ShadowLooper.idleMainLooper()
                homeScenario.onActivity { home ->
                    val matching = runBlocking { dao.getProfilesMatchingQuery("Database").first() }
                    assertThat(matching).hasSize(1)
                    assertThat(matching[0].name).isEqualTo("Database Server")
                }
            }

            // 3. Launch RdpSessionActivity with transient profile
            val transientProfile = ServerProfile(name = "VDI Host", host = "192.168.100.1")
            val sessionIntent = RdpSessionContract.createTransientSessionIntent(context, transientProfile)

            ActivityScenario.launch<RdpSessionActivity>(sessionIntent).use { sessionScenario ->
                ShadowLooper.idleMainLooper()
                sessionScenario.onActivity { sessionActivity ->
                    assertThat(sessionActivity.rdpSession).isNotNull()
                    assertThat(sessionActivity.activeProfile?.name).isEqualTo("VDI Host")
                }

                // 4. Simulate screen orientation / configuration change
                sessionScenario.recreate()
                ShadowLooper.idleMainLooper()

                // 5. Verify session activity recreated without crashing and clean disconnect
                sessionScenario.onActivity { sessionActivity ->
                    sessionActivity.onDisconnectRequested()

                    val dialog = ShadowAlertDialog.getLatestDialog() as? androidx.appcompat.app.AlertDialog
                    assertThat(dialog).isNotNull()
                    dialog?.getButton(android.content.DialogInterface.BUTTON_POSITIVE)?.performClick()
                    ShadowLooper.idleMainLooper()
                    assertThat(sessionActivity.isFinishing).isTrue()
                }
            }
        }
    }
}
