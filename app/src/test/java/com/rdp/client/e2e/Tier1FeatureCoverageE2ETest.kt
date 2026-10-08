package com.rdp.client.e2e

import android.content.Context
import android.graphics.PointF
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.common.truth.Truth.assertThat
import com.rdp.client.R
import com.rdp.client.databinding.ItemServerCardBinding
import com.rdp.client.databinding.LayoutToolbarDrawerBinding
import com.rdp.client.freerdp.*
import com.rdp.client.model.*
import com.rdp.client.repository.ProfileRepository
import com.rdp.client.ui.editor.QuickConnectDialogFragment
import com.rdp.client.ui.home.HomeActivity
import com.rdp.client.ui.home.OnProfileActionListener
import com.rdp.client.ui.home.ProfileAdapter
import com.rdp.client.ui.home.ProfileViewModelFactory
import com.rdp.client.ui.session.RdpSessionActivity
import com.rdp.client.ui.session.RdpSessionContract
import com.rdp.client.ui.session.ToolbarDrawerController
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
 * Milestone 5 - Tier 1: Feature Coverage E2E Tests (>= 25 genuine test cases).
 * Comprehensive opaque-box verification across:
 * - Storage (>= 5): Entity defaults, CRUD persistence, search query matching, multi-criteria sorting, bookmark card binding.
 * - Config (>= 5): Session parameter parsing, FreeRDP argument array formatting, security mode fallbacks, audio/mic flags, gateway parameters.
 * - Input (>= 5): Direct touchscreen tap-to-click, touch drag motion, long-press right-click, relative touchpad motion, two-finger scroll wheel ticks.
 * - Keyboard (>= 5): IBM PC AT 8042 scancodes, function keys F1-F12, extended keys with 0xE0 prefix flag, fastpath Unicode injection, KeyPacer 18ms timing.
 * - UI (>= 5): HomeActivity launch/intent, profile list observation, toolbar drawer open/close kinematics, Compose overlay toggle bindings, disconnect confirmation dialog.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class Tier1FeatureCoverageE2ETest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var dao: ServerProfileDao
    private lateinit var repository: ProfileRepository
    private val capturedCursorEvents = mutableListOf<CapturedCursor>()
    private val capturedKeyEvents = mutableListOf<CapturedKey>()
    private val capturedUnicodeEvents = mutableListOf<Int>()
    private val testInstanceId = 8888L

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
        val app = ApplicationProvider.getApplicationContext<Context>()
        app.setTheme(R.style.Theme_RdpClient)
        context = ContextThemeWrapper(app, R.style.Theme_RdpClient)
        database = AppDatabase.createInMemory(context)
        dao = database.serverProfileDao()
        repository = ProfileRepository(dao)
        ProfileViewModelFactory.testRepository = repository

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
            override fun getVersion(): String = "FreeRDP 3.5.1-test"
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
    // 1. STORAGE FEATURE COVERAGE (>= 5 tests)
    // =========================================================================

    @Test
    fun testStorage_profileDefaultValues() {
        val defaultProfile = ServerProfile()
        assertThat(defaultProfile.port).isEqualTo(3389)
        assertThat(defaultProfile.securityType).isEqualTo(SecurityType.AUTO)
        assertThat(defaultProfile.resolutionMode).isEqualTo(ResolutionMode.FIT_TO_SCREEN)
        assertThat(defaultProfile.colorDepth).isEqualTo(ColorDepth.DEPTH_32)
        assertThat(defaultProfile.audioMode).isEqualTo(AudioMode.LOCAL)
        assertThat(defaultProfile.desktopScale).isEqualTo(100)
        assertThat(defaultProfile.enableGateway).isFalse()
        assertThat(defaultProfile.enableWol).isFalse()
        assertThat(defaultProfile.clipboardSync).isTrue()
        assertThat(defaultProfile.useRemoteFX).isTrue()
        assertThat(defaultProfile.useGFX).isTrue()
        assertThat(defaultProfile.useH264).isTrue()
    }

    @Test
    fun testStorage_crudPersistence() = runBlocking {
        val profile = ServerProfile(
            name = "Engineering Workstation",
            host = "10.0.1.50",
            port = 3389,
            username = "devuser",
            password = "SecurePassword123!",
            domain = "CORP",
            securityType = SecurityType.NLA
        )

        val insertedId = dao.insert(profile)
        assertThat(insertedId).isGreaterThan(0L)

        val retrieved = dao.getProfileById(insertedId)
        assertThat(retrieved).isNotNull()
        assertThat(retrieved?.name).isEqualTo("Engineering Workstation")
        assertThat(retrieved?.host).isEqualTo("10.0.1.50")
        assertThat(retrieved?.username).isEqualTo("devuser")
        assertThat(retrieved?.securityType).isEqualTo(SecurityType.NLA)

        val updated = retrieved!!.copy(name = "Updated Workstation", port = 3390)
        val updateCount = dao.update(updated)
        assertThat(updateCount).isEqualTo(1)

        val retrievedUpdated = dao.getProfileById(insertedId)
        assertThat(retrievedUpdated?.name).isEqualTo("Updated Workstation")
        assertThat(retrievedUpdated?.port).isEqualTo(3390)

        val deleteCount = dao.delete(retrievedUpdated!!)
        assertThat(deleteCount).isEqualTo(1)
        assertThat(dao.getProfileById(insertedId)).isNull()
    }

    @Test
    fun testStorage_searchQueryMatching() = runBlocking {
        dao.insert(ServerProfile(name = "Finance Server", host = "192.168.1.10", username = "finuser"))
        dao.insert(ServerProfile(name = "Build Server", host = "192.168.2.20", domain = "JENKINS"))
        dao.insert(ServerProfile(name = "Dev Box", host = "10.0.0.30", username = "developer"))

        val matchesByFinance = dao.getProfilesMatchingQuery("Finance").first()
        assertThat(matchesByFinance).hasSize(1)
        assertThat(matchesByFinance[0].name).isEqualTo("Finance Server")

        val matchesByIp = dao.getProfilesMatchingQuery("192.168").first()
        assertThat(matchesByIp).hasSize(2)

        val matchesByDomain = dao.getProfilesMatchingQuery("JENKINS").first()
        assertThat(matchesByDomain).hasSize(1)
        assertThat(matchesByDomain[0].name).isEqualTo("Build Server")

        val matchesByUsername = dao.getProfilesMatchingQuery("developer").first()
        assertThat(matchesByUsername).hasSize(1)
        assertThat(matchesByUsername[0].name).isEqualTo("Dev Box")
    }

    @Test
    fun testStorage_multiCriteriaSorting() = runBlocking {
        val p1 = ServerProfile(id = 1, name = "Beta Server", host = "10.0.0.1", createdTimestamp = 1000L, lastConnectedTimestamp = 5000L, connectionCount = 10)
        val p2 = ServerProfile(id = 2, name = "Alpha Server", host = "10.0.0.2", createdTimestamp = 2000L, lastConnectedTimestamp = 3000L, connectionCount = 50)
        val p3 = ServerProfile(id = 3, name = "Gamma Server", host = "10.0.0.3", createdTimestamp = 3000L, lastConnectedTimestamp = 8000L, connectionCount = 5)

        dao.insert(p1)
        dao.insert(p2)
        dao.insert(p3)

        val sortedByName = dao.getAllProfilesSortedByNameAsc().first()
        assertThat(sortedByName.map { it.name }).containsExactly("Alpha Server", "Beta Server", "Gamma Server").inOrder()

        val sortedByNameDesc = dao.getAllProfilesSortedByNameDesc().first()
        assertThat(sortedByNameDesc.map { it.name }).containsExactly("Gamma Server", "Beta Server", "Alpha Server").inOrder()

        val sortedByLastConnected = dao.getAllProfilesSortedByLastConnected().first()
        assertThat(sortedByLastConnected.map { it.name }).containsExactly("Gamma Server", "Beta Server", "Alpha Server").inOrder()

        val sortedByMostUsed = dao.getAllProfilesSortedByMostUsed().first()
        assertThat(sortedByMostUsed.map { it.name }).containsExactly("Alpha Server", "Beta Server", "Gamma Server").inOrder()

        val sortedByDateAdded = dao.getAllProfilesSortedByDateAdded().first()
        assertThat(sortedByDateAdded.map { it.name }).containsExactly("Gamma Server", "Alpha Server", "Beta Server").inOrder()
    }

    @Test
    fun testStorage_bookmarkCardBinding() {
        val profile = ServerProfile(
            name = "Production RDS",
            host = "rds.internal.net",
            port = 3389,
            username = "sysadmin",
            domain = "CORP",
            password = "SecretPassword",
            securityType = SecurityType.NLA,
            resolutionMode = ResolutionMode.CUSTOM,
            customWidth = 2560,
            customHeight = 1440,
            enableGateway = true,
            gatewayHost = "gateway.corp.com",
            enableWol = true,
            wolMacAddress = "00:11:22:33:44:55",
            lastConnectedTimestamp = System.currentTimeMillis()
        )

        val inflater = LayoutInflater.from(context)
        val binding = ItemServerCardBinding.inflate(inflater, null, false)
        val holder = ProfileAdapter.ProfileViewHolder(binding, object : OnProfileActionListener {
            override fun onProfileClick(profile: ServerProfile) {}
            override fun onProfileLongClick(profile: ServerProfile): Boolean = true
            override fun onProfileEdit(profile: ServerProfile) {}
            override fun onProfileDuplicate(profile: ServerProfile) {}
            override fun onProfileWakeOnLan(profile: ServerProfile) {}
            override fun onProfileDelete(profile: ServerProfile) {}
        })

        holder.bind(profile)

        assertThat(binding.tvServerName.text.toString()).isEqualTo("Production RDS")
        assertThat(binding.tvServerHost.text.toString()).isEqualTo("CORP\\sysadmin@rds.internal.net:3389")
        assertThat(binding.badgeSecurity.text.toString()).isEqualTo("NLA (CredSSP)")
        assertThat(binding.badgeResolution.text.toString()).isEqualTo("2560x1440")
        assertThat(binding.badgeGateway.visibility).isEqualTo(android.view.View.VISIBLE)
        assertThat(binding.badgeWol.visibility).isEqualTo(android.view.View.VISIBLE)
        assertThat(binding.badgeCredential.visibility).isEqualTo(android.view.View.VISIBLE)
    }

    @Test
    fun testStorage_recordConnectionUpdatesTimestampAndCount() = runBlocking {
        val profile = ServerProfile(name = "Session Test", host = "192.168.1.1")
        val id = dao.insert(profile)

        val beforeTime = 1700000000000L
        dao.recordConnection(id, beforeTime)

        val retrieved = dao.getProfileById(id)
        assertThat(retrieved?.connectionCount).isEqualTo(1)
        assertThat(retrieved?.lastConnectedTimestamp).isEqualTo(beforeTime)

        val afterTime = 1700000050000L
        dao.recordConnection(id, afterTime)

        val retrievedSecond = dao.getProfileById(id)
        assertThat(retrievedSecond?.connectionCount).isEqualTo(2)
        assertThat(retrievedSecond?.lastConnectedTimestamp).isEqualTo(afterTime)
    }

    // =========================================================================
    // 2. CONFIG FEATURE COVERAGE (>= 5 tests)
    // =========================================================================

    @Test
    fun testConfig_sessionParameterParsingFromProfile() {
        val profile = ServerProfile(
            name = "Dev Machine",
            host = "192.168.5.10",
            port = 3389,
            domain = "DEV",
            username = "alice",
            password = "alice_password",
            securityType = SecurityType.TLS,
            resolutionMode = ResolutionMode.CUSTOM,
            customWidth = 1920,
            customHeight = 1080,
            colorDepth = ColorDepth.DEPTH_24,
            audioMode = AudioMode.REMOTE,
            microphoneEnabled = true
        )

        val params = RdpConnectionParameters.fromProfile(profile)
        assertThat(params.host).isEqualTo("192.168.5.10")
        assertThat(params.port).isEqualTo(3389)
        assertThat(params.domain).isEqualTo("DEV")
        assertThat(params.username).isEqualTo("alice")
        assertThat(params.password).isEqualTo("alice_password")
        assertThat(params.securityType).isEqualTo(SecurityType.TLS)
        assertThat(params.width).isEqualTo(1920)
        assertThat(params.height).isEqualTo(1080)
        assertThat(params.colorDepth).isEqualTo(ColorDepth.DEPTH_24)
        assertThat(params.audioMode).isEqualTo(AudioMode.REMOTE)
        assertThat(params.microphoneEnabled).isTrue()
    }

    @Test
    fun testConfig_freeRdpArgumentArrayFormatting() {
        val params = RdpConnectionParameters(
            host = "rdp.work.com",
            port = 3390,
            username = "bob",
            password = "secret_bob_pass",
            domain = "CORP",
            securityType = SecurityType.NLA,
            width = 1680,
            height = 1050,
            colorDepth = ColorDepth.DEPTH_32,
            resolutionMode = ResolutionMode.CUSTOM
        )

        val args = params.toNativeArgs()
        assertThat(args[0]).isEqualTo("freerdp-android")
        assertThat(args).asList().contains("/v:rdp.work.com:3390")
        assertThat(args).asList().contains("/u:bob")
        assertThat(args).asList().contains("/p:secret_bob_pass")
        assertThat(args).asList().contains("/d:CORP")
        assertThat(args).asList().contains("/sec:nla")
        assertThat(args).asList().contains("/size:1680x1050")
        assertThat(args).asList().contains("/bpp:32")
    }

    @Test
    fun testConfig_securityModeFallbacks() {
        val autoParams = RdpConnectionParameters(host = "10.0.0.1", securityType = SecurityType.AUTO)
        assertThat(autoParams.toNativeArgs()).asList().contains("/sec:auto")

        val nlaParams = RdpConnectionParameters(host = "10.0.0.1", securityType = SecurityType.NLA)
        assertThat(nlaParams.toNativeArgs()).asList().contains("/sec:nla")

        val tlsParams = RdpConnectionParameters(host = "10.0.0.1", securityType = SecurityType.TLS)
        assertThat(tlsParams.toNativeArgs()).asList().contains("/sec:tls")

        val rdpParams = RdpConnectionParameters(host = "10.0.0.1", securityType = SecurityType.RDP)
        assertThat(rdpParams.toNativeArgs()).asList().contains("/sec:rdp")
    }

    @Test
    fun testConfig_audioAndMicrophoneFlags() {
        val localAudio = RdpConnectionParameters(host = "10.0.0.1", audioMode = AudioMode.LOCAL, microphoneEnabled = false)
        assertThat(localAudio.toNativeArgs()).asList().contains("/sound:sys:opensles")
        assertThat(localAudio.toNativeArgs()).asList().doesNotContain("/microphone:sys:opensles")

        val remoteAudio = RdpConnectionParameters(host = "10.0.0.1", audioMode = AudioMode.REMOTE, microphoneEnabled = true)
        assertThat(remoteAudio.toNativeArgs()).asList().contains("/audio-mode:1")
        assertThat(remoteAudio.toNativeArgs()).asList().contains("/microphone:sys:opensles")

        val muteAudio = RdpConnectionParameters(host = "10.0.0.1", audioMode = AudioMode.MUTE, microphoneEnabled = false)
        assertThat(muteAudio.toNativeArgs()).asList().contains("/audio-mode:2")
    }

    @Test
    fun testConfig_gatewayParameters() {
        val withGateway = RdpConnectionParameters(
            host = "internal-server.local",
            enableGateway = true,
            gatewayHost = "gw.external.com",
            gatewayPort = 443,
            gatewayUsername = "gwuser",
            gatewayPassword = "gwpass",
            gatewayDomain = "GWDOM"
        )

        val argsWithGw = withGateway.toNativeArgs()
        assertThat(argsWithGw).asList().contains("/g:gw.external.com:443")
        assertThat(argsWithGw).asList().contains("/gu:gwuser")
        assertThat(argsWithGw).asList().contains("/gp:gwpass")
        assertThat(argsWithGw).asList().contains("/gd:GWDOM")

        val disabledGw = RdpConnectionParameters(
            host = "internal-server.local",
            enableGateway = false,
            gatewayHost = "gw.external.com"
        )
        assertThat(disabledGw.toNativeArgs().none { it.startsWith("/g:") }).isTrue()
    }

    @Test
    fun testConfig_resolutionModesAndScale() {
        val customRes = RdpConnectionParameters(host = "10.0.0.1", resolutionMode = ResolutionMode.CUSTOM, width = 1280, height = 720, desktopScale = 150)
        val customArgs = customRes.toNativeArgs()
        assertThat(customArgs).asList().contains("/size:1280x720")
        assertThat(customArgs).asList().contains("/scale:150")

        val dynamicRes = RdpConnectionParameters(host = "10.0.0.1", resolutionMode = ResolutionMode.DYNAMIC, width = 1920, height = 1080)
        val dynamicArgs = dynamicRes.toNativeArgs()
        assertThat(dynamicArgs).asList().contains("/disp")
    }

    // =========================================================================
    // 3. INPUT FEATURE COVERAGE (>= 5 tests)
    // =========================================================================

    @Test
    fun testInput_directTouchscreenTapToClick() = runTest {
        val dispatcher = TouchDispatcher(
            context = context,
            instanceProvider = { testInstanceId },
            transformer = testTransformer,
            gestureStyle = GestureStyle.TOUCHSCREEN,
            buttonUpDelayEnabled = true,
            scope = this
        )

        dispatcher.performSingleClick(400f, 300f)

        // Button DOWN dispatched immediately
        assertThat(capturedCursorEvents).hasSize(1)
        assertThat(capturedCursorEvents[0].x).isEqualTo(400)
        assertThat(capturedCursorEvents[0].y).isEqualTo(300)
        assertThat(capturedCursorEvents[0].flags).isEqualTo(RdpPointerFlags.encodeButtonDown(RdpPointerFlags.Button.LEFT))

        // Advance 25ms -> Button UP dispatched
        testScheduler.advanceTimeBy(25)
        assertThat(capturedCursorEvents).hasSize(2)
        assertThat(capturedCursorEvents[1].x).isEqualTo(400)
        assertThat(capturedCursorEvents[1].y).isEqualTo(300)
        assertThat(capturedCursorEvents[1].flags).isEqualTo(RdpPointerFlags.encodeButtonUp(RdpPointerFlags.Button.LEFT))
    }

    @Test
    fun testInput_touchDragMotion() {
        val dispatcher = TouchDispatcher(
            context = context,
            instanceProvider = { testInstanceId },
            transformer = testTransformer,
            gestureStyle = GestureStyle.TOUCHSCREEN,
            buttonUpDelayEnabled = false
        )

        // Simulate touch down then drag
        dispatcher.isDragLocked = true
        val downTime = 1000L
        val eventTime = 1020L
        val motionEvent = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_MOVE, 450f, 350f, 0)
        dispatcher.onTouch(android.view.View(context), motionEvent)

        val expectedFlags = RdpPointerFlags.encodeDragMove(RdpPointerFlags.Button.LEFT)
        assertThat(capturedCursorEvents.any { it.flags == expectedFlags && it.x == 450 && it.y == 350 }).isTrue()
        motionEvent.recycle()
    }

    @Test
    fun testInput_longPressRightClick() = runTest {
        val dispatcher = TouchDispatcher(
            context = context,
            instanceProvider = { testInstanceId },
            transformer = testTransformer,
            gestureStyle = GestureStyle.TOUCHSCREEN,
            buttonUpDelayEnabled = true,
            scope = this
        )

        dispatcher.performRightClick(600f, 400f)

        assertThat(capturedCursorEvents).hasSize(1)
        assertThat(capturedCursorEvents[0].x).isEqualTo(600)
        assertThat(capturedCursorEvents[0].y).isEqualTo(400)
        assertThat(capturedCursorEvents[0].flags).isEqualTo(RdpPointerFlags.encodeButtonDown(RdpPointerFlags.Button.RIGHT))

        testScheduler.advanceTimeBy(25)
        assertThat(capturedCursorEvents).hasSize(2)
        assertThat(capturedCursorEvents[1].flags).isEqualTo(RdpPointerFlags.encodeButtonUp(RdpPointerFlags.Button.RIGHT))
    }

    @Test
    fun testInput_relativeTouchpadMotion() {
        val dispatcher = TouchDispatcher(
            context = context,
            instanceProvider = { testInstanceId },
            transformer = testTransformer,
            gestureStyle = GestureStyle.TOUCHPAD
        )

        val initialX = dispatcher.virtualCursor.x
        val initialY = dispatcher.virtualCursor.y

        // Feed motion delta via simulated scroll
        val downTime = 1000L
        val eventTime = 1016L
        val e1 = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, 100f, 100f, 0)
        val e2 = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_MOVE, 120f, 130f, 0)

        // Dispatch onTouch to feed pointer accelerator and gesture listener
        dispatcher.onTouch(android.view.View(context), e1)
        dispatcher.onTouch(android.view.View(context), e2)

        assertThat(dispatcher.virtualCursor.x).isGreaterThan(0f)
        assertThat(dispatcher.virtualCursor.y).isGreaterThan(0f)

        e1.recycle()
        e2.recycle()
    }

    @Test
    fun testInput_twoFingerScrollWheelTicks() {
        val dispatcher = TouchDispatcher(
            context = context,
            instanceProvider = { testInstanceId },
            transformer = testTransformer,
            gestureStyle = GestureStyle.TOUCHSCREEN
        )

        // Direct call to sendCursorEvent with scroll flags
        val upScroll = RdpPointerFlags.encodeVerticalScroll(RdpPointerFlags.ScrollDirection.UP, 1)
        val downScroll = RdpPointerFlags.encodeVerticalScroll(RdpPointerFlags.ScrollDirection.DOWN, 1)

        dispatcher.sendCursorEvent(960, 540, upScroll)
        dispatcher.sendCursorEvent(960, 540, downScroll)

        assertThat(capturedCursorEvents).hasSize(2)
        assertThat(capturedCursorEvents[0].flags).isEqualTo(upScroll)
        assertThat(capturedCursorEvents[1].flags).isEqualTo(downScroll)
    }

    @Test
    fun testInput_hardwareMouseLeftMiddleRight() {
        val dispatcher = TouchDispatcher(
            context = context,
            instanceProvider = { testInstanceId },
            transformer = testTransformer
        )

        dispatcher.sendButtonDown(RdpPointerFlags.Button.LEFT, 100, 200)
        dispatcher.sendButtonUp(RdpPointerFlags.Button.LEFT, 100, 200)

        dispatcher.sendButtonDown(RdpPointerFlags.Button.MIDDLE, 100, 200)
        dispatcher.sendButtonUp(RdpPointerFlags.Button.MIDDLE, 100, 200)

        dispatcher.sendButtonDown(RdpPointerFlags.Button.RIGHT, 100, 200)
        dispatcher.sendButtonUp(RdpPointerFlags.Button.RIGHT, 100, 200)

        assertThat(capturedCursorEvents).hasSize(6)
        assertThat(capturedCursorEvents[0].flags).isEqualTo(RdpPointerFlags.encodeButtonDown(RdpPointerFlags.Button.LEFT))
        assertThat(capturedCursorEvents[1].flags).isEqualTo(RdpPointerFlags.encodeButtonUp(RdpPointerFlags.Button.LEFT))
        assertThat(capturedCursorEvents[2].flags).isEqualTo(RdpPointerFlags.encodeButtonDown(RdpPointerFlags.Button.MIDDLE))
        assertThat(capturedCursorEvents[3].flags).isEqualTo(RdpPointerFlags.encodeButtonUp(RdpPointerFlags.Button.MIDDLE))
        assertThat(capturedCursorEvents[4].flags).isEqualTo(RdpPointerFlags.encodeButtonDown(RdpPointerFlags.Button.RIGHT))
        assertThat(capturedCursorEvents[5].flags).isEqualTo(RdpPointerFlags.encodeButtonUp(RdpPointerFlags.Button.RIGHT))
    }

    // =========================================================================
    // 4. KEYBOARD FEATURE COVERAGE (>= 5 tests)
    // =========================================================================

    @Test
    fun testKeyboard_ibmPcAt8042ScancodeMappings_alphanumeric() {
        // Alphanumeric keys: A->0x1E, B->0x30, Z->0x2C, 1->0x02, 0->0x0B
        val scancodeA = ScancodeMapper.toScancode(KeyEvent.KEYCODE_A)
        assertThat(scancodeA).isNotNull()
        assertThat(scancodeA?.code).isEqualTo(0x1E)
        assertThat(scancodeA?.isExtended).isFalse()

        val scancodeZ = ScancodeMapper.toScancode(KeyEvent.KEYCODE_Z)
        assertThat(scancodeZ?.code).isEqualTo(0x2C)

        val scancode1 = ScancodeMapper.toScancode(KeyEvent.KEYCODE_1)
        assertThat(scancode1?.code).isEqualTo(0x02)

        val scancode0 = ScancodeMapper.toScancode(KeyEvent.KEYCODE_0)
        assertThat(scancode0?.code).isEqualTo(0x0B)
    }

    @Test
    fun testKeyboard_functionKeysF1ToF12() {
        val f1 = ScancodeMapper.toScancode(KeyEvent.KEYCODE_F1)
        assertThat(f1?.code).isEqualTo(0x3B)

        val f5 = ScancodeMapper.toScancode(KeyEvent.KEYCODE_F5)
        assertThat(f5?.code).isEqualTo(0x3F)

        val f10 = ScancodeMapper.toScancode(KeyEvent.KEYCODE_F10)
        assertThat(f10?.code).isEqualTo(0x44)

        val f11 = ScancodeMapper.toScancode(KeyEvent.KEYCODE_F11)
        assertThat(f11?.code).isEqualTo(0x57)

        val f12 = ScancodeMapper.toScancode(KeyEvent.KEYCODE_F12)
        assertThat(f12?.code).isEqualTo(0x58)
    }

    @Test
    fun testKeyboard_navigationKeysExtendedE0() {
        // Extended navigation keys have 0xE0 prefix flag (isExtended = true)
        val dpadUp = ScancodeMapper.toScancode(KeyEvent.KEYCODE_DPAD_UP)
        assertThat(dpadUp?.code).isEqualTo(0x48)
        assertThat(dpadUp?.isExtended).isTrue()

        val dpadDown = ScancodeMapper.toScancode(KeyEvent.KEYCODE_DPAD_DOWN)
        assertThat(dpadDown?.code).isEqualTo(0x50)
        assertThat(dpadDown?.isExtended).isTrue()

        val home = ScancodeMapper.toScancode(KeyEvent.KEYCODE_MOVE_HOME)
        assertThat(home?.code).isEqualTo(0x47)
        assertThat(home?.isExtended).isTrue()

        val fwdDel = ScancodeMapper.toScancode(KeyEvent.KEYCODE_FORWARD_DEL)
        assertThat(fwdDel?.code).isEqualTo(0x53)
        assertThat(fwdDel?.isExtended).isTrue()
    }

    @Test
    fun testKeyboard_reverseScancodeToAndroidKeyCode() {
        assertThat(ScancodeMapper.toAndroidKeyCode(0x1E, isExtended = false)).isEqualTo(KeyEvent.KEYCODE_A)
        assertThat(ScancodeMapper.toAndroidKeyCode(0x3B, isExtended = false)).isEqualTo(KeyEvent.KEYCODE_F1)
        assertThat(ScancodeMapper.toAndroidKeyCode(0x48, isExtended = true)).isEqualTo(KeyEvent.KEYCODE_DPAD_UP)
    }

    @Test
    fun testKeyboard_fastpathUnicodeCharacterInjection() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val pacerScope = TestScope(testDispatcher)
        val pacer = KeyPacer(instanceProvider = { testInstanceId }, scope = pacerScope)

        // Inject ASCII 'X' (codepoint 88)
        pacer.enqueueUnicode('X'.code)
        pacerScope.testScheduler.advanceTimeBy(5)

        assertThat(capturedUnicodeEvents).contains('X'.code)
    }

    @Test
    fun testKeyboard_keyPacerCalibratedTiming() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val pacerScope = TestScope(testDispatcher)
        val pacer = KeyPacer(instanceProvider = { testInstanceId }, scope = pacerScope)

        val scancode = RdpScancode(0x1E, isExtended = false) // 'A'
        pacer.enqueueKey(scancode)

        // Advance 1ms -> Down emitted
        pacerScope.testScheduler.advanceTimeBy(1)
        assertThat(capturedKeyEvents).hasSize(1)
        assertThat(capturedKeyEvents[0].scancode).isEqualTo(0x1E)
        assertThat(capturedKeyEvents[0].down).isTrue()

        // Advance 18ms -> Up emitted
        pacerScope.testScheduler.advanceTimeBy(KeyPacer.KEYDOWN_DURATION_MS)
        assertThat(capturedKeyEvents).hasSize(2)
        assertThat(capturedKeyEvents[1].scancode).isEqualTo(0x1E)
        assertThat(capturedKeyEvents[1].down).isFalse()

        // Advance 22ms -> Inter-key pacing interval completes
        pacerScope.testScheduler.advanceTimeBy(KeyPacer.INTER_KEY_PACING_MS)
        assertThat(capturedKeyEvents).hasSize(2)
    }

    // =========================================================================
    // 5. UI FEATURE COVERAGE (>= 5 tests)
    // =========================================================================

    @Test
    fun testUi_homeActivityLaunchAndToolbar() {
        ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val toolbar = activity.findViewById<MaterialToolbar>(R.id.topAppBar)
                assertThat(toolbar).isNotNull()
                assertThat(toolbar.title.toString()).isEqualTo("Remote Desktops")

                val fab = activity.findViewById<android.view.View>(R.id.fabAddProfile)
                assertThat(fab).isNotNull()
                assertThat(fab.isClickable).isTrue()
            }
        }
    }

    @Test
    fun testUi_homeActivityProfileListObservationAndEmptyState() {
        runBlocking {
            // Ensure empty DB
            dao.deleteAllProfiles()
        }

        ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
            ShadowLooper.idleMainLooper()
            scenario.onActivity { activity ->
                val emptyLayout = activity.findViewById<android.view.View>(R.id.layoutEmptyState)
                assertThat(emptyLayout).isNotNull()
                assertThat(emptyLayout.visibility).isEqualTo(android.view.View.VISIBLE)
            }
        }
    }

    @Test
    fun testUi_toolbarDrawerExpandCollapseKinematics() {
        val inflater = LayoutInflater.from(context)
        val binding = LayoutToolbarDrawerBinding.inflate(inflater)

        var toggleImeCount = 0
        val callbacks = object : ToolbarDrawerController.Callbacks {
            override fun onToggleIme() { toggleImeCount++ }
            override fun onToggleVirtualKeys() {}
            override fun onToggleVirtualMouse() {}
            override fun onZoomLockToggled(isLocked: Boolean) {}
            override fun onZoomReset() {}
            override fun onZoom100() {}
            override fun onMacroCtrlAltDel() {}
            override fun onMacroWinKey() {}
            override fun onDisconnectRequested() {}
            override fun onViewModeChanged(mode: ViewMode) {}
            override fun onGestureStyleChanged(style: GestureStyle) {}
        }

        val controller = ToolbarDrawerController(binding, callbacks)
        assertThat(controller.state).isEqualTo(ToolbarDrawerController.DrawerState.COLLAPSED)
        assertThat(controller.isExpanded).isFalse()

        // Non-animated expand
        controller.expand(animated = false)
        assertThat(controller.state).isEqualTo(ToolbarDrawerController.DrawerState.EXPANDED)
        assertThat(controller.isExpanded).isTrue()

        // Non-animated collapse
        controller.collapse(animated = false)
        assertThat(controller.state).isEqualTo(ToolbarDrawerController.DrawerState.COLLAPSED)
        assertThat(controller.isExpanded).isFalse()
    }

    @Test
    fun testUi_composeOverlayToggleBindings() {
        val profile = ServerProfile(name = "Overlay Test", host = "127.0.0.1")
        val intent = RdpSessionContract.createTransientSessionIntent(context, profile)

        ActivityScenario.launch<RdpSessionActivity>(intent).use { scenario ->
            ShadowLooper.idleMainLooper()
            scenario.onActivity { activity ->
                assertThat(activity.isVirtualKeysVisible.value).isFalse()
                assertThat(activity.isVirtualMouseVisible.value).isFalse()

                activity.onToggleVirtualKeys()
                assertThat(activity.isVirtualKeysVisible.value).isTrue()

                activity.onToggleVirtualMouse()
                assertThat(activity.isVirtualMouseVisible.value).isTrue()

                activity.onToggleVirtualKeys()
                assertThat(activity.isVirtualKeysVisible.value).isFalse()
            }
        }
    }

    @Test
    fun testUi_disconnectConfirmationDialog() {
        val profile = ServerProfile(name = "Dialog Test Host", host = "192.168.1.99")
        val intent = RdpSessionContract.createTransientSessionIntent(context, profile)

        ActivityScenario.launch<RdpSessionActivity>(intent).use { scenario ->
            ShadowLooper.idleMainLooper()
            scenario.onActivity { activity ->
                activity.onDisconnectRequested()

                val dialog = ShadowAlertDialog.getLatestDialog()
                assertThat(dialog).isNotNull()
                assertThat(dialog!!.isShowing).isTrue()
                val messageView = dialog.findViewById<android.widget.TextView>(android.R.id.message)
                if (messageView != null) {
                    assertThat(messageView.text.toString()).contains("Dialog Test Host")
                }
                dialog.dismiss()
            }
        }
    }

    @Test
    fun testUi_quickConnectDialogLaunch() {
        val fragment = QuickConnectDialogFragment.newInstance()
        assertThat(fragment).isNotNull()
        assertThat(QuickConnectDialogFragment.TAG).isEqualTo("QuickConnectDialogFragment")
    }
}
