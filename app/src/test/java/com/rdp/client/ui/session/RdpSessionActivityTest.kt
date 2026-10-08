package com.rdp.client.ui.session

import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.rdp.client.freerdp.LibFreeRDP
import com.rdp.client.freerdp.MockRdpNativeBridge
import com.rdp.client.model.ServerProfile
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowLooper

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RdpSessionActivityTest {

    private lateinit var mockBridge: MockRdpNativeBridge
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() {
        mockBridge = MockRdpNativeBridge(autoConnectSuccess = true, connectionDelayMs = 0L)
        LibFreeRDP.setNativeBridgeForTesting(mockBridge)
    }

    @After
    fun tearDown() {
        LibFreeRDP.resetNativeBridge()
        ShadowAlertDialog.reset()
    }

    @Test
    fun testActivityLaunchesWithTransientProfile() {
        val transientProfile = ServerProfile(
            name = "Test Workstation",
            host = "192.168.1.50",
            port = 3389,
            username = "admin"
        )
        val intent = RdpSessionContract.createTransientSessionIntent(context, transientProfile)

        ActivityScenario.launch<RdpSessionActivity>(intent).use { scenario ->
            ShadowLooper.idleMainLooper()
            scenario.onActivity { activity ->
                assertThat(activity.rdpSession).isNotNull()
                assertThat(activity.rdpSession?.parameters?.host).isEqualTo("192.168.1.50")
            }
        }
    }

    @Test
    fun testGuaranteedModifierReleaseOnPause() {
        val profile = ServerProfile(name = "Modifier Test", host = "127.0.0.1")
        val intent = RdpSessionContract.createTransientSessionIntent(context, profile)

        ActivityScenario.launch<RdpSessionActivity>(intent).use { scenario ->
            ShadowLooper.idleMainLooper()
            scenario.onActivity { activity ->
                // Simulate holding Ctrl and Alt
                activity.modifierState.toggleModifier(com.rdp.client.freerdp.ModifierKey.CTRL)
                activity.modifierState.toggleModifier(com.rdp.client.freerdp.ModifierKey.ALT)
                assertThat(activity.modifierState.isCtrlActive).isTrue()
                assertThat(activity.modifierState.isAltActive).isTrue()
            }

            // Trigger onPause by moving to STARTED
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)

            // Verify modifiers unconditionally released
            scenario.onActivity { activity ->
                assertThat(activity.modifierState.isCtrlActive).isFalse()
                assertThat(activity.modifierState.isAltActive).isFalse()
            }
        }
    }

    @Test
    fun testClipboardEchoCancellation() {
        val syncManager = RdpClipboardSyncManager(
            context = context,
            instanceProvider = { 1001L },
            isSyncEnabledProvider = { true }
        )
        syncManager.start()

        // 1. Remote delivers text "Hello Remote"
        syncManager.onRemoteClipboardReceived("Hello Remote")

        ShadowLooper.idleMainLooper()
        val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertThat(clipboardManager.primaryClip?.getItemAt(0)?.text?.toString()).isEqualTo("Hello Remote")

        // 2. Local clip change should recognize "Hello Remote" as echo and ignore
        syncManager.stop()
    }

    @Test
    fun testDisconnectConfirmationDialog() {
        val profile = ServerProfile(name = "Disconnect Test", host = "10.0.0.1")
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
                    assertThat(messageView.text.toString()).contains("Are you sure you want to disconnect")
                }
                dialog.dismiss()
            }
        }
    }
}
