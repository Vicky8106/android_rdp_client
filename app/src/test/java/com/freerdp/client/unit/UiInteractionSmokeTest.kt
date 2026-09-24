package com.freerdp.client.unit

import android.content.Context
import android.os.Looper
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.freerdp.client.di.AppContainer
import com.freerdp.client.navigation.AppNavHost
import com.freerdp.client.settings.AppSettingsRepository
import com.freerdp.core.engine.MockRdpEngine
import com.freerdp.feature.session.CredentialStorageType
import com.freerdp.feature.session.RdpProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * Real-click UI smoke test: drives the actual Compose UI through the real navigation
 * host, real view models, the real repository/vault and the MockRdpEngine — proving
 * that what a user taps on a device actually happens.
 *
 * Regression target: NavigationModel used a plain (non-snapshot) list, so push/pop
 * never triggered recomposition — every navigation button was dead on device while
 * pure model unit tests still passed, because they read `current` directly instead of
 * through composition. These tests go through [AppNavHost] exactly like the activity
 * does, so that class of bug cannot ship again.
 */
// No @GraphicsMode: NATIVE mode makes Robolectric's shadow display receiver advance
// the system clock on every vsync request, and RemoteCanvasView's vsync heartbeat then
// livelocks the paused main looper (waitForIdle never quiesces). These tests assert
// interaction, not pixels — LEGACY graphics runs the same code paths safely.
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class UiInteractionSmokeTest {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var container: AppContainer
    private lateinit var mock: MockRdpEngine

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("ui_smoke_settings", Context.MODE_PRIVATE)
            .edit().clear().commit()
        File(context.filesDir, "ui_smoke_profiles.json").delete()
        mock = MockRdpEngine()
        container = AppContainer(
            appContext = context,
            engineOverride = { mock },
            ioDispatcher = Dispatchers.Unconfined,
            settingsPrefs = context.getSharedPreferences("ui_smoke_settings", Context.MODE_PRIVATE),
            profileStorageFile = File(context.filesDir, "ui_smoke_profiles.json")
        )
    }

    private fun settle(times: Int = 5) {
        repeat(times) {
            rule.waitForIdle()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun launch() {
        rule.setContent { AppNavHost(container) }
        settle()
    }

    /**
     * Invokes a node's OnClick semantics action directly on the UI thread.
     *
     * Needed only for nodes inside [com.freerdp.client.ui.session.VirtualKeysBar]:
     * its rounded-corner container Surface clips descendants, and hit-testing
     * through an ancestor non-rect clip path silently misses in this Robolectric
     * environment (both performClick and real touch injection no-op there, while
     * the same buttons composed without the rounded ancestor respond normally).
     * The action closure itself is the real production chain (button onClick ->
     * overlay forwarding -> SessionViewModel -> ModifierStateMachine -> engine),
     * so this still verifies the full bridge — it bypasses only framework
     * hit-testing, which works correctly on real devices.
     */
    private fun clickBypassingAncestorClip(contentDescription: String) {
        val node = rule.onNodeWithContentDescription(contentDescription).fetchSemanticsNode()
        rule.runOnUiThread {
            node.config[SemanticsActions.OnClick].action!!.invoke()
        }
    }

    // ------------------------------------------------------------ first run ---

    @Test
    fun emptyStateCtaOpensEditorSavesProfileAndReturnsToList() {
        launch()
        rule.onNodeWithText("No remote desktops yet").assertExists()

        // This tap is the first thing a new user does. With a non-observable back
        // stack it recomposed nothing — the editor never appeared (reported as
        // "buttons do not work").
        rule.onNodeWithText("Create your first profile").performClick()
        settle()
        rule.onNodeWithText("Host name or IP *").assertExists()

        val fields = rule.onAllNodes(hasSetTextAction())
        fields[0].performTextInput("Office PC")
        fields[1].performTextInput("10.0.0.5")
        settle()

        // Save runs the real repository, then pops back through NavigationModel.
        rule.onNodeWithText("Create profile").performClick()
        settle()
        rule.onNodeWithText("Office PC").assertExists()
    }

    @Test
    fun editorBackArrowReturnsToProfileList() {
        launch()
        rule.onNodeWithText("Create your first profile").performClick()
        settle()
        rule.onNodeWithText("New profile").assertExists()

        rule.onNodeWithContentDescription("Back").performClick()
        settle()
        rule.onNodeWithText("No remote desktops yet").assertExists()
    }

    // ------------------------------------------------------------- settings ---

    /**
     * Material3 Switch nodes, in document order. Role-filtered because RadioButtons
     * (appearance choices, preset rows) also carry ToggleableState. On this screen the
     * order is: dynamic color, HUD, touchpad, apply-to-all, demo engine.
     */
    private fun switchNodes() = rule.onAllNodes(
        SemanticsMatcher("switch role") { node: SemanticsNode ->
            node.config.contains(SemanticsProperties.ToggleableState) &&
                node.config.contains(SemanticsProperties.Role) &&
                node.config[SemanticsProperties.Role] == Role.Switch
        }
    )

    @Test
    fun gearOpensSettingsDemoSwitchTogglesAndBackReturns() {
        launch()
        rule.onNodeWithContentDescription("Settings").performClick()
        settle()
        rule.onNodeWithText("Session defaults").assertExists()

        // performClick injects a real tap at the node's coordinates, so nodes below the
        // vertical-scroll fold must be scrolled into the viewport first (Robolectric's
        // default window is small; taps outside it silently miss).
        val count = switchNodes().fetchSemanticsNodes().size
        for (i in 0 until count) {
            switchNodes()[i].performScrollTo().performClick()
            settle()
        }
        val after = container.settings.current
        assertTrue(
            "clicking every settings switch must flip demo engine (switch count=$count); " +
                "after=dynamic:${after.dynamicColor},hud:${after.hudEnabled}," +
                "touchpad:${after.touchpadDefault},override:${after.presetOverridesProfiles}," +
                "demo:${after.demoEngine}",
            after.demoEngine
        )
        // ...and it must be PERSISTED, not just in-memory: a fresh repository instance
        // reading the same prefs file must observe it (survives process death).
        assertTrue(
            "demo engine setting must persist to disk",
            AppSettingsRepository(
                container.appContext.getSharedPreferences("ui_smoke_settings", Context.MODE_PRIVATE)
            ).current.demoEngine
        )

        rule.onNodeWithContentDescription("Back").performClick()
        settle()
        rule.onNodeWithText("Remote Desktops").assertExists()
    }

    // -------------------------------------------------------------- session ---

    private fun seedProfile(): RdpProfile {
        val profile = RdpProfile(
            id = "ui-smoke-1",
            label = "Work PC",
            hostname = "work.example.com",
            port = 3389,
            username = "alice",
            credentialStorageType = CredentialStorageType.KEYSTORE_ENCRYPTED
        )
        runBlocking { container.profileRepository.saveProfile(profile) }
        container.credentialStore.saveSecret(profile.id, "pw".toCharArray())
        return profile
    }

    @Test
    fun connectButtonOpensLiveSessionWithWorkingToolbarAndOverlay() {
        val profile = seedProfile()
        launch()

        // One-tap connect must navigate into the session and reach Connected.
        rule.onNodeWithText("Connect").performClick()
        settle()
        rule.onNodeWithContentDescription("Show session controls")
            .assertExists("connected session must show the collapsed toolbar")

        // The floating overlay is a platform View hierarchy nested inside AndroidView;
        // Compose semantics only surfaces the host, so assert its state through the VM
        // (the View's own TalkBack labels are covered by feature-mouse's view tests).
        val vm = container.sessionViewModel(profile.id)
        assertTrue(
            "floating mouse overlay must be visible in a live session",
            vm.overlayVisible.value
        )

        // Expand the quick toolbar (4s auto-collapse FSM) and drive real actions.
        rule.onNodeWithContentDescription("Show session controls").performClick()
        settle()
        rule.onNodeWithContentDescription("Disconnect").assertExists()

        val hudBefore = vm.hudVisible.value
        rule.onNodeWithContentDescription("Toggle telemetry HUD").performClick()
        settle()
        assertNotEquals(
            "HUD toolbar button must toggle the HUD",
            hudBefore, vm.hudVisible.value
        )

        val overlayBefore = vm.overlayVisible.value
        rule.onNodeWithContentDescription("Toggle mouse overlay").performClick()
        settle()
        assertNotEquals(
            "mouse-overlay toolbar button must toggle the overlay",
            overlayBefore, vm.overlayVisible.value
        )
    }

    @Test
    fun modifierMacroClicksReachTheRdpEngine() {
        // Precondition: the Ctrl+Alt+Del macro must map to real scancode steps —
        // if this fails the translator's macro table is broken, not the UI bridge.
        assertTrue(
            "Ctrl+Alt+Del macro must map to scancode steps",
            com.freerdp.feature.session.ScancodeTranslator
                .getMacroSteps(com.freerdp.feature.session.MacroAction.CTRL_ALT_DEL)
                .isNotEmpty()
        )

        seedProfile()
        launch()
        rule.onNodeWithText("Connect").performClick()
        settle()
        rule.onNodeWithContentDescription("Show session controls").performClick()
        settle()

        // Bridge check 1: a direct virtual-keys latch click must change the VM state
        // (Compose -> VirtualKeysBar -> SessionViewModel).
        clickBypassingAncestorClip("Ctrl key, off")
        settle()
        val vm = container.sessionViewModel("ui-smoke-1")
        assertNotEquals(
            "Ctrl latch button must change the VM latch state",
            com.freerdp.feature.session.LatchState.INACTIVE,
            vm.modifierStates.value[com.freerdp.feature.session.ModifierKey.CTRL]
        )

        // Bridge check 2: a tap on the virtual keys bar must produce real scancode
        // key events on the engine — Compose -> ModifierStateMachine -> IRdpEngine.
        clickBypassingAncestorClip("Send Ctrl+Alt+Del shortcut")
        settle()
        assertTrue(
            "Ctrl+Alt+Del macro must reach the engine as key events",
            mock.recordedKeyEvents.isNotEmpty()
        )
    }
}
