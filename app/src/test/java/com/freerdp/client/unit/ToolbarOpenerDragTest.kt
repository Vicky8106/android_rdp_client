package com.freerdp.client.unit

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.freerdp.client.ui.session.FloatingToolbarOpener
import com.freerdp.client.ui.session.PREF_KEY_TOOLBAR_OPENER_VERTICAL_BIAS
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * Focused regression for the toolbar opener drag: a long downward swipe must
 * move the persisted vertical bias substantially. A stale-capture bug made
 * each drag event recompute from the gesture-start offset, pinning movement
 * to a few dozen px no matter how far the finger traveled (found live on an
 * emulator: +57px for a 640px swipe).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class ToolbarOpenerDragTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun longSwipeMovesBiasSubstantially() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("session_toolbar_prefs", Context.MODE_PRIVATE)
        prefs.edit().putFloat(PREF_KEY_TOOLBAR_OPENER_VERTICAL_BIAS, 0.2f).apply()
        rule.setContent {
            FloatingToolbarOpener(initialVerticalBias = 0.2f, onOpen = {})
        }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Open session toolbar")
            .performTouchInput { swipeDown(startY = 0f, endY = 600f) }
        rule.waitForIdle()
        val after = prefs.getFloat(PREF_KEY_TOOLBAR_OPENER_VERTICAL_BIAS, 0.2f)
        assertTrue(
            "600px swipe must move bias substantially (was 0.2, now $after)",
            abs(after - 0.2f) > 0.2f
        )
    }
}
