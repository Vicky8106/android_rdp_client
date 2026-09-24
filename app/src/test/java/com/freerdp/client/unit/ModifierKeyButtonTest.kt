package com.freerdp.client.unit

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.freerdp.client.ui.session.ModifierKeyButton
import com.freerdp.feature.session.LatchState
import com.freerdp.feature.session.ModifierKey
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Focused regression for [ModifierKeyButton]: tap and long-press must both
 * dispatch. Long-press (INACTIVE -> LOCKED) was accepted as a parameter but
 * never attached to the underlying Surface, so it silently did nothing.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class ModifierKeyButtonTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun tapDispatchesToOnTap() {
        val hit = AtomicBoolean(false)
        rule.setContent {
            ModifierKeyButton(
                key = ModifierKey.CTRL,
                latchState = LatchState.INACTIVE,
                onTap = { hit.set(true) },
                onLongPress = {}
            )
        }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Ctrl key, off").performClick()
        rule.waitForIdle()
        assertTrue("tap must dispatch to onTap", hit.get())
    }

    @Test
    fun longPressActionIsAttachedAndDispatches() {
        val hit = AtomicBoolean(false)
        rule.setContent {
            ModifierKeyButton(
                key = ModifierKey.CTRL,
                latchState = LatchState.INACTIVE,
                onTap = {},
                onLongPress = { hit.set(true) }
            )
        }
        rule.waitForIdle()
        val node = rule.onNodeWithContentDescription("Ctrl key, off").fetchSemanticsNode()
        assertTrue(
            "button must expose an OnLongClick action",
            node.config.contains(SemanticsActions.OnLongClick)
        )
        rule.runOnUiThread {
            node.config[SemanticsActions.OnLongClick].action!!.invoke()
        }
        rule.waitForIdle()
        assertTrue("long-press must dispatch to onLongPress", hit.get())
    }
}
