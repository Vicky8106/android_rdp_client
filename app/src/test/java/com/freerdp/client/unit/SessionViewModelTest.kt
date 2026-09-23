package com.freerdp.client.unit

import android.content.Context
import com.freerdp.client.session.SessionErrorCodes
import com.freerdp.client.session.SessionPhase
import com.freerdp.core.engine.MockRdpEngine
import com.freerdp.feature.session.LatchState
import com.freerdp.feature.session.MacroAction
import com.freerdp.feature.session.ModifierKey
import com.freerdp.feature.session.ToolbarAction
import com.freerdp.feature.session.ToolbarState
import com.freerdp.feature.telemetry.reconnect.ReconnectState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Drives the real SessionViewModel against the deterministic MockRdpEngine,
 * the real AtomicFileProfileRepository (temp file), the real Keystore vault and
 * virtual coroutine time. Robolectric supplies Android Context/Looper services.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SessionViewModelTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    // ------------------------------------------------------------- happy path

    @Test
    fun successfulConnectReachesConnectedAndPersistsLastConnected() = runTest {
        val h = sessionHarness(context, testProfile())
        h.vm.ensureStarted("profile-1")
        pumpAll()

        assertEquals(SessionPhase.Connected("work.example.com", demo = true), h.vm.phase.value)
        assertEquals(ReconnectState.Connected, h.vm.reconnectState.value)

        // last-connected timestamp persisted through the REAL repository
        val stored = h.repo.getProfile("profile-1")
        assertNotNull(stored)
        assertNotNull("timestamp written on successful connect", stored!!.lastConnectedTimestamp)
    }

    @Test
    fun connectIsIdempotentAcrossRotationStyleReEntry() = runTest {
        val h = sessionHarness(context, testProfile())
        h.vm.ensureStarted("profile-1")
        pumpAll()
        val firstConnects = h.mock.activeConfig

        // The screen re-invokes ensureStarted after every recomposition/rotation.
        h.vm.ensureStarted("profile-1")
        pumpAll()
        assertEquals(SessionPhase.Connected("work.example.com", demo = true), h.vm.phase.value)
        assertEquals(firstConnects, h.mock.activeConfig)
    }

    // ------------------------------------------------------- failure + retry

    @Test
    fun nativeUnavailableFailureSurfacesCodeHintAndRetryRecovers() = runTest {
        val mock = MockRdpEngine().apply {
            shouldFailConnection = true
            failureErrorCode = 1001
            failureErrorMessage = "FreeRDP native library is not loaded on this platform"
        }
        val h = sessionHarness(context, testProfile(), mock = mock)
        h.vm.ensureStarted("profile-1")
        pumpAll()

        val failed = h.vm.phase.value as SessionPhase.Failed
        assertEquals(1001, failed.code)
        assertNotNull("actionable hint required", failed.friendlyHint)
        assertTrue(failed.friendlyHint!!.contains("Demo"))

        // Initial failures must NOT silently auto-retry — the user gets the Retry UX.
        assertTrue(h.vm.reconnectState.value is ReconnectState.Idle)

        // Manual retry recovers once the (simulated) native library becomes available.
        mock.shouldFailConnection = false
        h.vm.retry()
        pumpAll()
        assertTrue(h.vm.phase.value is SessionPhase.Connected)
    }

    @Test
    fun missingProfileFailsWithActionable404InsteadOfHanging() = runTest {
        val h = sessionHarness(context, testProfile())
        h.vm.ensureStarted("does-not-exist")
        pumpAll()

        val failed = h.vm.phase.value as SessionPhase.Failed
        assertEquals(SessionErrorCodes.PROFILE_MISSING, failed.code)
        assertNotNull(failed.friendlyHint)
    }

    // -------------------------------------------------------------- password

    @Test
    fun missingSecretPromptsForPasswordThenConnectsAndSavesToKeystore() = runTest {
        val h = sessionHarness(context, testProfile(), storedPassword = null)
        h.vm.ensureStarted("profile-1")
        pumpAll()

        assertTrue("password prompt shown", h.vm.passwordRequired.value)
        assertTrue(h.vm.phase.value is SessionPhase.Connecting)

        h.vm.respondPassword("hunter2")
        pumpAll()

        assertTrue(h.vm.phase.value is SessionPhase.Connected)
        assertFalse(h.vm.passwordRequired.value)
        // Round-trip through the real Keystore vault
        assertEquals("hunter2", String(h.credentials.getSecret("profile-1") ?: CharArray(0)))
        // And into the engine config
        assertEquals("hunter2", h.mock.activeConfig?.password)
    }

    @Test
    fun cancellingPasswordPromptFailsRetryablyNotIntoDeadEnd() = runTest {
        val h = sessionHarness(context, testProfile(), storedPassword = null)
        h.vm.ensureStarted("profile-1")
        pumpAll()

        h.vm.respondPassword(null)
        pumpAll()

        val failed = h.vm.phase.value as SessionPhase.Failed
        assertEquals(SessionErrorCodes.PASSWORD_REQUIRED, failed.code)
        assertNotNull(failed.friendlyHint)

        // Retry re-opens the prompt (no dead end).
        h.vm.retry()
        pumpAll()
        assertTrue(h.vm.passwordRequired.value)
    }

    // ------------------------------------------------------------ certificate

    @Test
    fun certificateTrustOnFirstUsePromptsPersistsAndIsSilentlyAcceptedAfterwards() = runTest {
        val mock = MockRdpEngine().apply { simulateCertVerification = true }
        val h = sessionHarness(
            context, testProfile(), mock = mock,
            connectDispatcher = Dispatchers.IO // TOFU gate blocks the connect thread awaiting the user
        )
        h.vm.ensureStarted("profile-1")
        pumpAll() // drives the (virtual) profile load; connect continues on IO

        awaitUntil(5000, "certificate prompt appears") { h.vm.certificateRequest.value != null }
        val request = h.vm.certificateRequest.value!!
        assertEquals(mock.certVerificationHost, request.host)
        assertEquals(mock.certVerificationFingerprint, request.fingerprint)

        h.vm.respondCertificate(true)
        awaitUntil(5000, "connected after trusting") { h.vm.phase.value is SessionPhase.Connected }
        assertNull(h.vm.certificateRequest.value)
        assertTrue(
            "TOFU decision persisted",
            h.settings.isCertificateTrusted(request.host, request.fingerprint)
        )

        // Second session over the same settings: connection must succeed WITHOUT any
        // prompt (we never respond again — if a prompt appeared it would block here).
        val second = sessionHarness(
            context, testProfile(id = "profile-2"), mock = mock,
            settings = h.settings, connectDispatcher = Dispatchers.IO
        )
        second.vm.ensureStarted("profile-2")
        pumpAll()
        awaitUntil(5000, "silent re-accept of trusted fingerprint") {
            second.vm.phase.value is SessionPhase.Connected
        }
        assertNull(second.vm.certificateRequest.value)
    }

    @Test
    fun certificateRejectionFails403WithoutPersistingTrust() = runTest {
        val mock = MockRdpEngine().apply { simulateCertVerification = true }
        val h = sessionHarness(
            context, testProfile(), mock = mock,
            connectDispatcher = Dispatchers.IO
        )
        h.vm.ensureStarted("profile-1")
        pumpAll()
        awaitUntil(5000, "certificate prompt appears") { h.vm.certificateRequest.value != null }

        h.vm.respondCertificate(false)
        awaitUntil(5000, "rejected certificate fails the connect") {
            h.vm.phase.value is SessionPhase.Failed
        }
        awaitUntil(5000, "prompt state cleared") { h.vm.certificateRequest.value == null }

        val failed = h.vm.phase.value as SessionPhase.Failed
        assertEquals(403, failed.code)
        assertNotNull(failed.friendlyHint)
        assertTrue(h.settings.current.trustedCertificates.isEmpty())
    }

    // ------------------------------------------------------------ modifiers

    @Test
    fun ctrlLatchCyclesInactiveLatchedLockedWithExactScancodes() = runTest {
        val h = sessionHarness(context, testProfile())
        h.vm.ensureStarted("profile-1")
        pumpAll()

        // 1st tap -> LATCHED, key held down (Windows Set-1: Left Ctrl = 0x1D)
        h.vm.onModifierTapped(ModifierKey.CTRL)
        pumpAll()
        assertEquals(LatchState.LATCHED, h.vm.modifierStates.value[ModifierKey.CTRL])
        assertEquals(listOf(0x1D), h.mock.recordedKeyEvents.map { it.keyCode })
        assertEquals(listOf(true), h.mock.recordedKeyEvents.map { it.down })

        // 2nd tap -> LOCKED (still held, no extra events)
        h.vm.onModifierTapped(ModifierKey.CTRL)
        pumpAll()
        assertEquals(LatchState.LOCKED, h.vm.modifierStates.value[ModifierKey.CTRL])
        assertEquals(1, h.mock.recordedKeyEvents.size)

        // 3rd tap -> INACTIVE, key released
        h.vm.onModifierTapped(ModifierKey.CTRL)
        pumpAll()
        assertEquals(LatchState.INACTIVE, h.vm.modifierStates.value[ModifierKey.CTRL])
        assertEquals(listOf(0x1D, 0x1D), h.mock.recordedKeyEvents.map { it.keyCode })
        assertEquals(listOf(true, false), h.mock.recordedKeyEvents.map { it.down })
    }

    @Test
    fun specialKeysEmitDownUpScancodes() = runTest {
        val h = sessionHarness(context, testProfile())
        h.vm.ensureStarted("profile-1")
        pumpAll()

        h.vm.onModifierTapped(ModifierKey.ESC) // 0x01
        assertEquals(listOf(0x01, 0x01), h.mock.recordedKeyEvents.map { it.keyCode })
        assertEquals(listOf(true, false), h.mock.recordedKeyEvents.map { it.down })

        h.vm.onModifierTapped(ModifierKey.F5) // 0x3F
        assertEquals(listOf(0x3F, 0x3F), h.mock.recordedKeyEvents.takeLast(2).map { it.keyCode })

        h.vm.onModifierTapped(ModifierKey.F12) // 0x58
        assertEquals(listOf(0x58, 0x58), h.mock.recordedKeyEvents.takeLast(2).map { it.keyCode })
    }

    @Test
    fun ctrlAltDelMacroEmitsSixStepSequence() = runTest {
        val h = sessionHarness(context, testProfile())
        h.vm.ensureStarted("profile-1")
        pumpAll()

        h.vm.onMacro(MacroAction.CTRL_ALT_DEL)
        assertEquals(
            listOf(0x1D, 0x38, 0x53, 0x53, 0x38, 0x1D),
            h.mock.recordedKeyEvents.map { it.keyCode }
        )
        assertEquals(
            listOf(true, true, true, false, false, false),
            h.mock.recordedKeyEvents.map { it.down }
        )
    }

    @Test
    fun keyboardTextTranslatesToScancodesAndUnicodeFallback() = runTest {
        val h = sessionHarness(context, testProfile())
        h.vm.ensureStarted("profile-1")
        pumpAll()

        h.vm.onKeyboardText("Hi") // H = 0x23, I = 0x17
        assertEquals(listOf(0x23, 0x23, 0x17, 0x17), h.mock.recordedKeyEvents.map { it.keyCode })

        h.vm.onKeyboardBackspace() // 0x0E
        assertEquals(listOf(0x0E, 0x0E), h.mock.recordedKeyEvents.takeLast(2).map { it.keyCode })

        h.vm.onKeyboardText("é") // no scancode -> unicode path
        assertEquals(listOf('é', 'é'), h.mock.recordedUnicodeEvents.map { it.unicodeChar })
        assertEquals(listOf(true, false), h.mock.recordedUnicodeEvents.map { it.down })
    }

    // -------------------------------------------------------------- toolbar

    @Test
    fun quickToolbarAutoCollapsesAfter4SecondsAndAppliesActions() = runTest {
        val h = sessionHarness(context, testProfile())
        h.vm.ensureStarted("profile-1")
        pumpAll()
        assertEquals(ToolbarState.COLLAPSED, h.vm.toolbarState.value)

        h.vm.toggleToolbar()
        runCurrent()
        assertEquals(ToolbarState.EXPANDED, h.vm.toolbarState.value)

        h.vm.toolbarAction(ToolbarAction.TOGGLE_TELEMETRY_HUD)
        runCurrent()
        assertFalse(h.vm.hudVisible.value)
        assertFalse("HUD toggle persisted to settings", h.settings.current.hudEnabled)

        h.vm.toolbarAction(ToolbarAction.TOGGLE_KEYBOARD)
        runCurrent()
        assertTrue(h.vm.keyboardVisible.value)

        h.vm.toolbarAction(ToolbarAction.TOGGLE_MODIFIER_BAR)
        runCurrent()
        assertFalse(h.vm.modifierBarVisible.value)

        h.vm.toolbarAction(ToolbarAction.SWITCH_RESOLUTION)
        runCurrent()
        assertEquals(1, h.vm.zoomRequests.value)

        // Still expanded just before the 4s inactivity window closes.
        advanceTimeBy(3900)
        runCurrent()
        assertEquals(ToolbarState.EXPANDED, h.vm.toolbarState.value)

        // Past 4s -> auto-collapsed.
        advanceTimeBy(200)
        runCurrent()
        assertEquals(ToolbarState.COLLAPSED, h.vm.toolbarState.value)
    }

    @Test
    fun toolbarTouchResetsAutoCollapseTimer() = runTest {
        val h = sessionHarness(context, testProfile())
        h.vm.ensureStarted("profile-1")
        pumpAll()

        h.vm.toggleToolbar()
        advanceTimeBy(3000)
        runCurrent()
        h.vm.onToolbarTouched() // user interacted at t=3000

        advanceTimeBy(2500) // t=5500, only 2500 since touch
        runCurrent()
        assertEquals(ToolbarState.EXPANDED, h.vm.toolbarState.value)

        advanceTimeBy(1600) // 4100 since touch
        runCurrent()
        assertEquals(ToolbarState.COLLAPSED, h.vm.toolbarState.value)
    }

    // ------------------------------------------------- dynamic resolution (250ms)

    @Test
    fun viewportChangesAreDebounced250msAndCoalescedToFinalDimensions() = runTest {
        val h = sessionHarness(context, testProfile())
        h.vm.ensureStarted("profile-1")
        pumpAll()
        h.mock.clearRecordedEvents()

        // Portrait layout...
        h.vm.onViewportSizeChanged(1080, 2400, 420, orientation = 1)
        advanceTimeBy(249)
        runCurrent()
        assertTrue("no update before the 250ms debounce", h.mock.recordedResolutions.isEmpty())

        // ...rapidly rotated to landscape before the debounce fires.
        h.vm.onViewportSizeChanged(2400, 1080, 420, orientation = 2)
        advanceTimeBy(249)
        runCurrent()
        assertTrue("rapid flip must coalesce", h.mock.recordedResolutions.isEmpty())

        advanceTimeBy(2)
        runCurrent()
        assertEquals("exactly one engine update", 1, h.mock.recordedResolutions.size)
        val resolution = h.mock.recordedResolutions[0]
        assertEquals(2400, resolution.width)
        assertEquals(1080, resolution.height)
        assertEquals(2, resolution.orientation)
    }

    // -------------------------------------------------------- auto-reconnect

    @Test
    fun sessionDropAutoReconnectsAndRecoversTheUiPhase() = runTest {
        val h = sessionHarness(context, testProfile(autoReconnect = true))
        h.vm.ensureStarted("profile-1")
        pumpAll()
        assertTrue(h.vm.phase.value is SessionPhase.Connected)

        // Wi-Fi style drop observed by the reconnect machine (public contract).
        h.vm.reconnectManager.onSessionDropped("Wi-Fi lost")
        pumpAll()

        assertEquals("reconnect machine back to Connected", ReconnectState.Connected, h.vm.reconnectState.value)
        assertTrue("UI phase recovered with the engine", h.vm.phase.value is SessionPhase.Connected)
    }

    @Test
    fun networkCallbacksDriveWaitingAndFastPathRecovery() = runTest {
        val h = sessionHarness(context, testProfile(autoReconnect = true))
        h.vm.ensureStarted("profile-1")
        pumpAll()
        assertTrue("real network monitor registered", h.vm.networkMonitor.isActive)

        h.vm.reconnectManager.onNetworkLost()
        pumpAll()
        assertTrue(h.vm.reconnectState.value is ReconnectState.WaitingForNetwork)

        h.vm.reconnectManager.onNetworkAvailable()
        pumpAll()
        assertEquals(ReconnectState.Connected, h.vm.reconnectState.value)
        assertTrue(h.vm.phase.value is SessionPhase.Connected)
    }

    @Test
    fun profileAutoReconnectOptOutCancelsTheMachineLoop() = runTest {
        val h = sessionHarness(context, testProfile(autoReconnect = false))
        h.vm.ensureStarted("profile-1")
        pumpAll()
        assertTrue(h.vm.phase.value is SessionPhase.Connected)

        h.mock.triggerSessionDrop("Signal lost")
        pumpAll()

        assertTrue(h.vm.phase.value is SessionPhase.Failed)
        assertTrue(
            "retry loop cancelled for opt-out profiles",
            h.vm.reconnectState.value is ReconnectState.Idle
        )
        assertNotNull(h.vm.userMessage.value)
        assertTrue(h.vm.userMessage.value!!.contains("disabled"))
    }

    @Test
    fun reconnectChipCanBeCancelledByTheUser() = runTest {
        val h = sessionHarness(context, testProfile(autoReconnect = true))
        h.vm.ensureStarted("profile-1")
        pumpAll()

        // Make reconnect attempts fail so the chip stays visible.
        h.mock.shouldFailConnection = true
        h.vm.reconnectManager.onSessionDropped("drop")
        pumpAll()

        // User taps Cancel on the chip through the VM surface.
        h.vm.cancelReconnect()
        pumpAll()
        assertTrue(h.vm.reconnectState.value is ReconnectState.Idle)

        // And can still manually retry afterwards.
        h.mock.shouldFailConnection = false
        h.vm.retry()
        pumpAll()
        assertTrue(h.vm.phase.value is SessionPhase.Connected)
    }

    // ------------------------------------------------- toolbar pin (keep-open) --

    @Test
    fun toolbarPinKeepsToolbarOpenPastAutoCollapseTimeout() = runTest {
        val h = sessionHarness(context, testProfile())
        h.vm.ensureStarted("profile-1")
        pumpAll()
        assertEquals(ToolbarState.COLLAPSED, h.vm.toolbarState.value)

        h.vm.toggleToolbar()
        runCurrent()
        assertEquals(ToolbarState.EXPANDED, h.vm.toolbarState.value)

        h.vm.toggleToolbarPin()
        runCurrent()
        assertTrue("pin state surfaced to the UI", h.vm.toolbarPinned.value)

        advanceTimeBy(9_000) // far beyond the 4 s inactivity window
        runCurrent()
        assertEquals(
            "pinned toolbar must not auto-collapse",
            ToolbarState.EXPANDED,
            h.vm.toolbarState.value
        )

        h.vm.toggleToolbarPin() // unpin re-arms the 4 s timer from now
        runCurrent()
        assertFalse(h.vm.toolbarPinned.value)

        advanceTimeBy(3_900)
        runCurrent()
        assertEquals(ToolbarState.EXPANDED, h.vm.toolbarState.value)

        advanceTimeBy(200) // 4100 ms since unpin
        runCurrent()
        assertEquals(ToolbarState.COLLAPSED, h.vm.toolbarState.value)
    }

    @Test
    fun sessionLossCollapsesTheToolbarDeterministically() = runTest {
        val h = sessionHarness(context, testProfile(autoReconnect = false))
        h.vm.ensureStarted("profile-1")
        pumpAll()

        h.vm.toggleToolbar()
        h.vm.toggleToolbarPin()
        runCurrent()
        assertEquals(ToolbarState.EXPANDED, h.vm.toolbarState.value)
        assertTrue(h.vm.toolbarPinned.value)

        h.mock.triggerSessionDrop("Link lost")
        pumpAll() // settle delay elapses -> processSettledDisconnect -> toolbar.onSessionLost()

        assertEquals(
            "toolbar collapses on session loss even while pinned",
            ToolbarState.COLLAPSED,
            h.vm.toolbarState.value
        )
    }

    // ------------------------------------------------------------------ exit

    @Test
    fun exitFlowConfirmsThenTearsDownAndSignalsNavigation() = runTest {
        val h = sessionHarness(context, testProfile())
        h.vm.ensureStarted("profile-1")
        pumpAll()

        h.vm.requestExit()
        assertTrue(h.vm.exitConfirmVisible.value)
        h.vm.dismissExitConfirm()
        assertFalse("dialog dismissed without exiting", h.vm.exitConfirmVisible.value)
        assertTrue(h.vm.phase.value is SessionPhase.Connected)

        h.vm.confirmExit()
        pumpAll()

        assertEquals(1, h.vm.exitCount.value)
        assertEquals(SessionPhase.Idle, h.vm.phase.value)
        assertFalse("network callbacks stopped", h.vm.networkMonitor.isActive)
        assertEquals(com.freerdp.core.engine.RdpConnectionState.Disconnected, h.mock.connectionState.value)

        // Double confirm must not fire a second navigation event.
        h.vm.confirmExit()
        pumpAll()
        assertEquals(1, h.vm.exitCount.value)
    }
}
