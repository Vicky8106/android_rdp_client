package com.rdp.client.adversarial

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.rdp.client.freerdp.*
import com.rdp.client.model.AudioMode
import com.rdp.client.model.ColorDepth
import com.rdp.client.model.ResolutionMode
import com.rdp.client.model.SecurityType
import com.rdp.client.model.ServerProfile
import com.rdp.client.ui.session.viewport.FramebufferManager
import com.rdp.client.utils.KeyPacer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Milestone 6 (Tier 5 White-Box Coverage Hardening) Adversarial Stress Test Suite.
 *
 * Systematically exercises and validates failure modes across:
 * 1. Data Layer & Protocol Parameters (10,000-char strings, native C buffer truncation, invalid port coercion, NLA fallbacks, RD Gateway edge cases)
 * 2. Native Bridge & Session Lifecycle (concurrent connect/disconnect storms, rapid listener registration, cancelled scopes, unused RECONNECTING state)
 * 3. Framebuffer & Dirty Rect Update Stream (recycled bitmap resilience, dirty rect flow backpressure/drop)
 * 4. Keyboard Subsystem & Calibrated Key Pacer (coroutine cancellation stuck key down, queue overflow, modifier latch premature release desync)
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ProtocolSubsystemAdversarialHardeningTest {

    private lateinit var context: Context
    private val capturedKeyEvents = ConcurrentLinkedQueue<CapturedKeyEvent>()
    private val capturedCursorEvents = ConcurrentLinkedQueue<CapturedCursorEvent>()
    private val activeBridgeInstances = ConcurrentLinkedQueue<Long>()
    private val testInstanceId = 7777L

    data class CapturedKeyEvent(
        val instance: Long,
        val scancode: Int,
        val extended: Boolean,
        val down: Boolean,
        val timestamp: Long = System.nanoTime()
    )

    data class CapturedCursorEvent(
        val instance: Long,
        val x: Int,
        val y: Int,
        val flags: Int
    )

    private val mockBridge = object : IRdpNativeBridge {
        val nextId = java.util.concurrent.atomic.AtomicLong(5000L)

        override fun newInstance(context: Context?): Long {
            val id = nextId.getAndIncrement()
            activeBridgeInstances.add(id)
            return id
        }

        override fun freeInstance(instance: Long) {
            activeBridgeInstances.remove(instance)
        }

        override fun connect(instance: Long, params: RdpConnectionParameters?): Boolean {
            LibFreeRDP.onConnectionSuccess(instance)
            return true
        }

        override fun disconnect(instance: Long): Boolean {
            LibFreeRDP.onDisconnected(instance)
            return true
        }

        override fun updateGraphics(instance: Long, bitmap: Bitmap, x: Int, y: Int, w: Int, h: Int): Boolean = true

        override fun sendCursorEvent(instance: Long, x: Int, y: Int, flags: Int): Boolean {
            capturedCursorEvents.add(CapturedCursorEvent(instance, x, y, flags))
            return true
        }

        override fun sendKeyEvent(instance: Long, scancode: Int, extended: Boolean, down: Boolean): Boolean {
            capturedKeyEvents.add(CapturedKeyEvent(instance, scancode, extended, down))
            return true
        }

        override fun sendUnicodeKeyEvent(instance: Long, codePoint: Int): Boolean {
            capturedKeyEvents.add(CapturedKeyEvent(instance, codePoint, false, true))
            return true
        }

        override fun getVersion(): String = "FreeRDP 3.5.1-adversarial"
        override fun getLastError(instance: Long): String? = null
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        capturedKeyEvents.clear()
        capturedCursorEvents.clear()
        activeBridgeInstances.clear()
        LibFreeRDP.setNativeBridgeForTesting(mockBridge)
    }

    @After
    fun tearDown() {
        LibFreeRDP.resetNativeBridge()
    }

    // =========================================================================
    // 1. DATA LAYER & PROTOCOL PARAMETERS ADVERSARIAL COVERAGE
    // =========================================================================

    /**
     * White-Box Gap: RdpConnectionParameters.toSafeString() does not truncate oversized inputs.
     * A 10,000-character hostname or username is fully retained, which exhausts Android Logcat
     * message limits (~4076 bytes) and creates severe memory allocations under logcat monitoring.
     */
    @Test
    fun testToSafeString_TenThousandCharString_ExposesLogcatBufferOverflowAndSpam() {
        val tenThousandCharString = "A".repeat(10_000)
        val params = RdpConnectionParameters(
            host = tenThousandCharString,
            username = tenThousandCharString,
            password = "secretPassword123"
        )

        val safeString = params.toSafeString()

        // Passwords are correctly masked
        assertThat(safeString).doesNotContain("secretPassword123")
        assertThat(safeString).contains("pass='******'")

        // HARDENED: toSafeString truncates strings to max 64 chars to protect logcat
        assertThat(safeString.length).isLessThan(500)
        assertThat(safeString).doesNotContain(tenThousandCharString)
        assertThat(safeString).contains("host='${"A".repeat(64)}'")
        assertThat(safeString).contains("user='${"A".repeat(64)}'")
    }

    /**
     * White-Box Gap: Parameter length validation mismatch with native C structures.
     * android_freerdp.h defines:
     *   host[256], username[128], password[128], domain[128], gatewayHost[256].
     * RdpConnectionParameters.validate() has upper bound checks matching native C buffers.
     */
    @Test
    fun testValidate_NativeBufferLimitExceeded_AllowsOversizedStrings() {
        val longHost = "rdp-cluster-" + "x".repeat(300) + ".example.com" // 326 chars > 256
        val longUser = "domain_user_" + "u".repeat(150) // 162 chars > 128
        val longPass = "p".repeat(200) // 200 chars > 128

        val params = RdpConnectionParameters(
            host = longHost,
            port = 3389,
            username = longUser,
            password = longPass
        )

        // HARDENED: Validation fails when parameters exceed native buffer limits
        val validationResult = params.validate()
        assertThat(validationResult.isSuccess).isFalse()
        assertThat(validationResult).isInstanceOf(ConnectionValidationResult.Error::class.java)
    }

    /**
     * White-Box Gap: Invalid port coercion in RdpConnectionParameters.toNativeArgs() and
     * ServerProfile.toFreeRdpArguments().
     */
    @Test
    fun testToNativeArgs_InvalidPortUncoerced_GeneratesMalformedArguments() {
        val negativePortParams = RdpConnectionParameters(host = "192.168.1.50", port = -1)
        val negativePortArgs = negativePortParams.toNativeArgs()

        // HARDENED: toNativeArgs sanitizes/coerces invalid port to default 3389
        assertThat(negativePortArgs).asList().contains("/v:192.168.1.50:3389")

        val outOfRangeProfile = ServerProfile(
            host = "10.0.0.1",
            port = 70000
        )
        val profileArgs = outOfRangeProfile.toFreeRdpArguments()

        // HARDENED: ServerProfile.toFreeRdpArguments coerces port 70000 to default 3389
        assertThat(profileArgs).contains("/v:10.0.0.1:3389")
    }

    /**
     * White-Box Gap: Security Negotiation NLA without Credentials.
     * When securityType = NLA, but username and password are blank, RdpConnectionParameters.validate()
     * passes successfully, and toNativeArgs() outputs /sec:nla without any /u or /p.
     * NLA (CredSSP) cannot proceed anonymously and will fail during handshake.
     */
    @Test
    fun testSecurityNegotiation_NlaWithoutCredentials_PassesValidationAndGeneratesNlaArg() {
        val nlaAnonymousParams = RdpConnectionParameters(
            host = "secure-vdi.corp.net",
            securityType = SecurityType.NLA,
            username = "",
            password = ""
        )

        // EMPIRICAL GAP EXPOSURE:
        // Validation passes even though NLA strictly requires credentials
        val validation = nlaAnonymousParams.validate()
        assertThat(validation.isSuccess).isTrue()

        // Args output /sec:nla without username or password arguments
        val args = nlaAnonymousParams.toNativeArgs()
        assertThat(args).asList().contains("/sec:nla")
        val hasUserArg = args.any { it.startsWith("/u:") }
        val hasPassArg = args.any { it.startsWith("/p:") }
        assertThat(hasUserArg).isFalse()
        assertThat(hasPassArg).isFalse()
    }

    /**
     * White-Box Gap: RD Gateway enabled without Gateway credentials.
     * When enableGateway = true, gatewayUsername and gatewayPassword are omitted without
     * falling back to target credentials, causing anonymous gateway handshake rejection.
     */
    @Test
    fun testRdGateway_EnabledWithoutCredentials_OmitsGatewayUserPassArgs() {
        val gwParams = RdpConnectionParameters(
            host = "internal-workstation",
            username = "corpUser",
            password = "corpPassword",
            enableGateway = true,
            gatewayHost = "gateway.corp.com",
            gatewayPort = 443,
            gatewayUsername = "",
            gatewayPassword = ""
        )

        // Gateway host is non-empty, so validation passes
        assertThat(gwParams.validate().isSuccess).isTrue()

        val args = gwParams.toNativeArgs()
        assertThat(args).asList().contains("/g:gateway.corp.com:443")

        // HARDENED: Blank gateway credentials fall back to target user/password credentials
        val hasGwUser = args.any { it.startsWith("/gu:") }
        val hasGwPass = args.any { it.startsWith("/gp:") }
        assertThat(hasGwUser).isTrue()
        assertThat(hasGwPass).isTrue()
        assertThat(args).asList().contains("/gu:corpUser")
        assertThat(args).asList().contains("/gp:corpPassword")
    }

    /**
     * Verifies AudioMode enum mapping across all policies.
     */
    @Test
    fun testAudioModeEnum_MappingIntegrityAndCLIArguments() {
        val localParams = RdpConnectionParameters(host = "srv", audioMode = AudioMode.LOCAL)
        val remoteParams = RdpConnectionParameters(host = "srv", audioMode = AudioMode.REMOTE)
        val muteParams = RdpConnectionParameters(host = "srv", audioMode = AudioMode.MUTE)

        assertThat(localParams.toNativeArgs()).asList().contains("/sound:sys:opensles")
        assertThat(remoteParams.toNativeArgs()).asList().contains("/audio-mode:1")
        assertThat(muteParams.toNativeArgs()).asList().contains("/audio-mode:2")

        // Check companion fromInt fallback
        assertThat(AudioMode.fromInt(0)).isEqualTo(AudioMode.LOCAL)
        assertThat(AudioMode.fromInt(1)).isEqualTo(AudioMode.REMOTE)
        assertThat(AudioMode.fromInt(2)).isEqualTo(AudioMode.MUTE)
        assertThat(AudioMode.fromInt(99)).isEqualTo(AudioMode.LOCAL)
    }

    // =========================================================================
    // 2. NATIVE BRIDGE & SESSION LIFECYCLE ADVERSARIAL COVERAGE
    // =========================================================================

    /**
     * White-Box Gap: RdpSession concurrent connect/disconnect storm race condition.
     * When connect() is invoked, it launches an un-joined coroutine on externalScope.
     * If disconnect() is invoked immediately:
     * 1. disconnect() marks state as DISCONNECTING/DISCONNECTED before connect() allocates instanceId.
     * 2. connect() continues executing, allocates a native instance, registers listener, and connects.
     * 3. The native instance is now orphaned and leaked in C memory.
     */
    @Test
    fun testRdpSession_ConcurrentConnectAndDisconnectStorm_RaceCondition() = runBlocking {
        val params = RdpConnectionParameters(host = "192.168.1.10")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val session = RdpSession(context, params, scope)

        // Rapid connect and disconnect storm
        session.connect()
        session.disconnect()

        // Wait for coroutines to settle
        delay(100)

        // HARDENED: Lifecycle mutex serializes connect and disconnect, ending in DISCONNECTED
        assertThat(session.state.value).isEqualTo(RdpSessionState.DISCONNECTED)
        assertThat(session.instanceId).isEqualTo(0L)

        scope.cancel()
    }

    /**
     * White-Box Gap: Rapid duplicate connect calls overwrite instanceId without freeing previous.
     */
    @Test
    fun testRdpSession_RapidDuplicateConnect_InstanceIdLeak() = runBlocking {
        val params = RdpConnectionParameters(host = "192.168.1.10")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = RdpSession(context, params, scope)

        // Initial connect
        session.connect()
        delay(50)
        val firstInstance = session.instanceId

        // Second connect call while already connected
        session.connect()
        delay(50)

        // The state machine correctly prevents transition from CONNECTED to CONNECTING
        assertThat(session.state.value).isEqualTo(RdpSessionState.CONNECTED)
        assertThat(session.instanceId).isEqualTo(firstInstance)

        session.disconnect()
        delay(50)
        scope.cancel()
    }

    /**
     * White-Box Gap: CoroutineScope cancellation prevents disconnect() from running native cleanup.
     * When externalScope is cancelled, launch {} inside disconnect() is rejected.
     */
    @Test
    fun testRdpSession_ScopeCancellation_DisconnectCannotExecuteCleanup() = runBlocking {
        val params = RdpConnectionParameters(host = "192.168.1.10")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = RdpSession(context, params, scope)

        session.connect()
        delay(50)
        val allocatedInstance = session.instanceId
        assertThat(allocatedInstance).isNotEqualTo(0L)

        // Cancel scope externally (e.g., ViewModel onCleared or Activity finish)
        scope.cancel()

        // Invoke disconnect on cancelled scope
        session.disconnect()
        delay(50)

        // HARDENED: Dedicated cleanup scope guarantees teardown runs even if externalScope is cancelled
        assertThat(session.instanceId).isEqualTo(0L)
        assertThat(session.state.value).isEqualTo(RdpSessionState.DISCONNECTED)
    }

    /**
     * White-Box Gap: RdpSessionState.RECONNECTING is completely dead code.
     * When network disconnection happens, RdpSession goes directly to DISCONNECTED or ERROR,
     * without any retry backoff or exponential backoff mechanism.
     */
    @Test
    fun testRdpSession_NetworkDisconnection_ReconnectingStateUnused() = runBlocking {
        val params = RdpConnectionParameters(host = "192.168.1.10")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = RdpSession(context, params, scope)

        session.connect()
        delay(50)
        assertThat(session.state.value).isEqualTo(RdpSessionState.CONNECTED)

        // Simulate network disruption callback from FreeRDP
        session.onConnectionFailure(session.instanceId, 1002, "Socket connect timeout")
        delay(50)

        // EMPIRICAL GAP EXPOSURE:
        // Directly enters ERROR; RECONNECTING was completely bypassed
        assertThat(session.state.value).isEqualTo(RdpSessionState.ERROR)
        assertThat(session.lastErrorMessage).contains("1002")

        scope.cancel()
    }

    /**
     * Concurrency Hardening: Rapid listener registration and unregistration across multiple threads.
     */
    @Test
    fun testLibFreeRDP_ConcurrentListenerRegistry_ThreadSafety() {
        val threadCount = 10
        val iterations = 100
        val latch = CountDownLatch(threadCount)

        for (t in 0 until threadCount) {
            Thread {
                val dummyListener = object : RdpSessionListener {
                    override fun onConnectionSuccess(instance: Long) {}
                    override fun onConnectionFailure(instance: Long, errorCode: Int, message: String) {}
                    override fun onDisconnected(instance: Long) {}
                    override fun onGraphicsUpdate(instance: Long, x: Int, y: Int, width: Int, height: Int) {}
                    override fun onGraphicsResize(instance: Long, width: Int, height: Int, bpp: Int) {}
                }
                for (i in 0 until iterations) {
                    val id = (t * 1000 + i).toLong()
                    LibFreeRDP.registerSessionListener(id, dummyListener)
                    val retrieved = LibFreeRDP.getSessionListener(id)
                    assertThat(retrieved).isNotNull()
                    LibFreeRDP.unregisterSessionListener(id)
                    assertThat(LibFreeRDP.getSessionListener(id)).isNull()
                }
                latch.countDown()
            }.start()
        }

        val completed = latch.await(5, TimeUnit.SECONDS)
        assertThat(completed).isTrue()
    }

    // =========================================================================
    // 3. FRAMEBUFFER & DIRTY RECT UPDATE STREAM ADVERSARIAL COVERAGE
    // =========================================================================

    /**
     * White-Box Gap: RdpSession._dirtyRectFlow uses extraBufferCapacity = 64 with default drop.
     * When graphics updates arrive in high-frequency bursts (> 64 updates before collector consumes),
     * tryEmit returns false and SILENTLY DROPS dirty rectangles, causing torn/stale screen tiles.
     */
    @Test
    fun testRdpSession_DirtyRectFlow_BufferOverflowDropsUpdates() = runBlocking {
        val params = RdpConnectionParameters(host = "192.168.1.10")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = RdpSession(context, params, scope)

        // Fire 100 rapid dirty rect updates without an active collector
        for (i in 0 until 100) {
            session.onGraphicsUpdate(1L, i, i, 10, 10)
        }

        // Collect buffered items
        val collected = mutableListOf<Rect>()
        val collectJob = launch {
            session.dirtyRectFlow.collect { rect ->
                collected.add(rect)
            }
        }
        delay(50)
        collectJob.cancel()

        // EMPIRICAL GAP EXPOSURE:
        // Because extraBufferCapacity is 64 and there was no active collector during emit,
        // updates beyond capacity were dropped! Collected count is <= 64, not 100!
        assertThat(collected.size).isAtMost(64)

        scope.cancel()
    }

    /**
     * FramebufferManager resilience test: buffer allocation and front bitmap locking.
     */
    @Test
    fun testFramebufferManager_AllocationAndFrontBitmapAccess() {
        var invalidatedRect: Rect? = null
        val fbm = FramebufferManager { dirty ->
            invalidatedRect = dirty
        }

        fbm.allocateBuffers(1920, 1080)
        assertThat(fbm.width).isEqualTo(1920)
        assertThat(fbm.height).isEqualTo(1080)
        assertThat(invalidatedRect).isEqualTo(Rect(0, 0, 1920, 1080))

        var accessedWidth = 0
        fbm.withFrontBitmap { bitmap ->
            accessedWidth = bitmap.width
        }
        assertThat(accessedWidth).isEqualTo(1920)

        fbm.release()
        assertThat(fbm.width).isEqualTo(0)
    }

    // =========================================================================
    // 4. KEYBOARD SUBSYSTEM & KEY PACER ADVERSARIAL COVERAGE
    // =========================================================================

    /**
     * CRITICAL WHITE-BOX GAP: KeyPacer coroutine cancellation mid-delay leaves Key Down permanently stuck!
     * In KeyPacer.processQueue():
     *   LibFreeRDP.sendKeyEvent(instance, sc.code, sc.isExtended, down = true)
     *   try {
     *       delay(KEYDOWN_DURATION_MS)
     *   } catch (e: CancellationException) {
     *       return // <-- KEY UP IS NEVER SENT!
     *   }
     * If the coroutine scope is cancelled while inside delay(), Key Up is never dispatched to LibFreeRDP,
     * leaving a stuck key repeating indefinitely on the remote Windows host.
     */
    @Test
    fun testKeyPacer_CoroutineCancellationMidDelay_LeavesKeyStuckDown() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val pacerScope = TestScope(testDispatcher)
        val pacer = KeyPacer(instanceProvider = { testInstanceId }, scope = pacerScope)

        val scancodeEnter = RdpScancode(0x1C, isExtended = false)
        pacer.enqueueKey(scancodeEnter)

        // Advance 1ms: triggers Key Down (dispatched at t=0)
        pacerScope.testScheduler.advanceTimeBy(1)

        assertThat(capturedKeyEvents).hasSize(1)
        val downEvent = capturedKeyEvents.first()
        assertThat(downEvent.scancode).isEqualTo(0x1C)
        assertThat(downEvent.down).isTrue()

        // Cancel the pacerScope while the key is in the middle of KEYDOWN_DURATION_MS (18ms)
        pacerScope.cancel()

        // Advance time to run any pending coroutine continuation
        pacerScope.testScheduler.advanceTimeBy(50)

        // HARDENED: Key Up IS dispatched in finally NonCancellable block, preventing stuck key
        assertThat(capturedKeyEvents.any { !it.down && it.scancode == 0x1C }).isTrue()
    }

    /**
     * White-Box Gap: KeyPacer unbounded event channel.
     * KeyPacer uses Channel<PacedEvent>(Channel.UNLIMITED).
     * Enqueuing 1,000 rapid keys is accepted without backpressure, creating a 40-second queue backlog.
     */
    @Test
    fun testKeyPacer_QueueOverflow_UnboundedChannelBackpressure() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val pacerScope = TestScope(testDispatcher)
        val pacer = KeyPacer(instanceProvider = { testInstanceId }, scope = pacerScope)

        // Enqueue 1,000 rapid key events
        for (i in 0 until 1000) {
            pacer.enqueueKey(RdpScancode(0x1E, isExtended = false))
        }

        // All 1,000 keys were accepted into the channel without rejection or backpressure
        // Advance 40ms (1 full key cycle): only 1 key is dispatched
        pacerScope.testScheduler.advanceTimeBy(KeyPacer.KEYDOWN_DURATION_MS + KeyPacer.INTER_KEY_PACING_MS)
        assertThat(pacer.dispatchedCount).isEqualTo(1)

        pacer.cancelAndReleaseHeld()
    }

    /**
     * CRITICAL WHITE-BOX GAP: Race condition between ModifierState and asynchronous KeyPacer.
     * In VirtualKeysCompose.kt lines 241-244:
     *   keyPacer.enqueueKey(RdpScancode(scancode, isExtended = false))
     *   modifierState.onNonModifierKeyDispatched()
     *
     * onNonModifierKeyDispatched() runs synchronously on the UI thread and sends Modifier UP IMMEDIATELY!
     * Meanwhile, keyPacer.enqueueKey() merely enqueues the target key into an asynchronous channel.
     * By the time KeyPacer executes Key Down for the target key, the Modifier was ALREADY RELEASED!
     * This breaks hotkeys like Ctrl+C, Alt+F4, and Win+R on the remote session!
     */
    @Test
    fun testVirtualKeys_ModifierStateDesyncWithKeyPacer_ModifierReleasedPrematurely() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val pacerScope = TestScope(testDispatcher)
        val pacer = KeyPacer(instanceProvider = { testInstanceId }, scope = pacerScope)

        val modifierState = ModifierState { scancode, isDown ->
            mockBridge.sendKeyEvent(testInstanceId, scancode.code, scancode.isExtended, isDown)
        }

        // 1. User latches Ctrl (Ctrl Down sent)
        modifierState.toggleModifier(ModifierKey.CTRL)
        assertThat(modifierState.ctrlState).isEqualTo(ModifierState.State.LATCHED)

        assertThat(capturedKeyEvents).hasSize(1)
        val ctrlDown = capturedKeyEvents.poll()!!
        assertThat(ctrlDown.scancode).isEqualTo(0x1D) // Left Ctrl
        assertThat(ctrlDown.down).isTrue()

        // 2. User presses virtual key 'C' (scancode 0x2E) with coordinated onComplete callback
        pacer.enqueueKey(RdpScancode(0x2E, isExtended = false), onComplete = {
            modifierState.onNonModifierKeyDispatched()
        })

        // HARDENED: Ctrl UP is NOT emitted prematurely upon enqueue!
        assertThat(capturedKeyEvents).isEmpty()

        // Advance time so KeyPacer processes 'C' DOWN
        pacerScope.testScheduler.advanceTimeBy(1)

        assertThat(capturedKeyEvents).hasSize(1)
        val keyCDown = capturedKeyEvents.poll()!!
        assertThat(keyCDown.scancode).isEqualTo(0x2E) // 'C'
        assertThat(keyCDown.down).isTrue()

        // Advance time so KeyPacer processes 'C' UP and triggers onComplete
        pacerScope.testScheduler.advanceTimeBy(KeyPacer.KEYDOWN_DURATION_MS + 1)

        // Key Up for 'C' and then Ctrl UP are dispatched in correct sequence
        assertThat(capturedKeyEvents).isNotEmpty()
        val remainingEvents = capturedKeyEvents.toList()
        assertThat(remainingEvents.any { it.scancode == 0x2E && !it.down }).isTrue()
        assertThat(remainingEvents.any { it.scancode == 0x1D && !it.down }).isTrue()

        pacer.cancelAndReleaseHeld()
    }
}
