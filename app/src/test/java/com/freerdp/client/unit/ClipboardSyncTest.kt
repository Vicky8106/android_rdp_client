package com.freerdp.client.unit

import android.content.Context
import com.freerdp.core.protocol.ClipboardHandler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Bidirectional clipboard synchronization with echo-loop suppression, exercised end
 * to end through the REAL AndroidClipboardBridge (Robolectric's ClipboardManager),
 * the REAL core-rdp ClipboardHandler (SHA-256 echo detection) and MockRdpEngine.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ClipboardSyncTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test
    fun localClipboardChangeIsPushedToTheRemoteSession() = runTest {
        val h = sessionHarness(context, testProfile())
        h.vm.ensureStarted("profile-1")
        advanceUntilIdle()

        h.vm.startClipboardListening()
        // User copies in another app -> system clipboard changes.
        val systemClipboard: android.content.ClipboardManager =
            context.getSystemService(android.content.ClipboardManager::class.java)
        systemClipboard.setPrimaryClip(
            android.content.ClipData.newPlainText("other", "hello local")
        )
        h.bridge.dispatchPrimaryClipChanged()

        assertEquals(listOf("hello local"), h.mock.recordedClipboardTexts)
        assertEquals(1, h.vm.stats.value.clipboardLocalToRemote)
    }

    @Test
    fun remoteEchoOfLocalCopyIsSuppressed() = runTest {
        val h = sessionHarness(context, testProfile())
        h.vm.ensureStarted("profile-1")
        advanceUntilIdle()
        h.vm.startClipboardListening()

        // Local copy first: registered as lastLocalHash by the real ClipboardHandler.
        h.vm.onLocalClipboardChanged("shared text")
        assertEquals(1, h.mock.recordedClipboardTexts.size)
        assertEquals(1, h.vm.stats.value.clipboardLocalToRemote)

        // The remote echoes the exact same text back (classic RDP clipboard loop).
        h.mock.triggerClipboardReceived(
            ClipboardHandler.CF_UNICODETEXT,
            ClipboardHandler.encodeUnicodeText("shared text")
        )

        assertEquals(
            "echo must not be written back to the system clipboard",
            0, h.vm.stats.value.clipboardRemoteToLocal
        )
        assertEquals(
            "echo must not be re-sent to the remote",
            1, h.mock.recordedClipboardTexts.size
        )
    }

    @Test
    fun remoteCopyIsWrittenToSystemClipboardAndNeverLoopsBack() = runTest {
        val h = sessionHarness(context, testProfile())
        h.vm.ensureStarted("profile-1")
        advanceUntilIdle()
        h.vm.startClipboardListening()

        // Remote copies new text -> arrives through the engine listener.
        h.mock.triggerClipboardReceived(
            ClipboardHandler.CF_UNICODETEXT,
            ClipboardHandler.encodeUnicodeText("from remote")
        )

        assertEquals(1, h.vm.stats.value.clipboardRemoteToLocal)
        assertEquals("from remote", h.bridge.read())

        // The system listener firing after our own write must NOT push it back out.
        h.bridge.dispatchPrimaryClipChanged()
        h.vm.onLocalClipboardChanged("from remote") // belt & braces: direct re-delivery
        assertEquals(
            "no echo back to the remote",
            0, h.mock.recordedClipboardTexts.size
        )
        assertEquals(0, h.vm.stats.value.clipboardLocalToRemote)
    }

    @Test
    fun fullRoundTripKeepsCountersConsistent() = runTest {
        val h = sessionHarness(context, testProfile())
        h.vm.ensureStarted("profile-1")
        advanceUntilIdle()
        h.vm.startClipboardListening()

        // 1) local -> remote
        h.vm.onLocalClipboardChanged("alpha")
        assertEquals(1, h.vm.stats.value.clipboardLocalToRemote)

        // 2) remote echoes 'alpha' back (suppressed) and sends NEW text 'beta'
        h.mock.triggerClipboardReceived(
            ClipboardHandler.CF_UNICODETEXT,
            ClipboardHandler.encodeUnicodeText("alpha")
        )
        h.mock.triggerClipboardReceived(
            ClipboardHandler.CF_UNICODETEXT,
            ClipboardHandler.encodeUnicodeText("beta")
        )
        assertEquals(1, h.vm.stats.value.clipboardRemoteToLocal)
        assertEquals("beta", h.bridge.read())

        // 3) local side now copies 'beta' in another app -> identical to remote's text:
        //    the ClipboardHandler still suppresses the echo.
        h.vm.onLocalClipboardChanged("beta")
        assertEquals(1, h.vm.stats.value.clipboardLocalToRemote)
        assertEquals(listOf("alpha"), h.mock.recordedClipboardTexts)

        // 4) and a genuinely new local copy goes out.
        h.vm.onLocalClipboardChanged("gamma")
        assertEquals(2, h.vm.stats.value.clipboardLocalToRemote)
        assertEquals(listOf("alpha", "gamma"), h.mock.recordedClipboardTexts)
    }
}
