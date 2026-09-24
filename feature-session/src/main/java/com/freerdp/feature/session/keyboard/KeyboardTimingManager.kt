package com.freerdp.feature.session.keyboard

import com.freerdp.core.engine.IRdpEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Hardware BMC & Soft Keyboard Timing Manager for RDP sessions.
 *
 * Baseboard Management Controllers (Dell iDRAC, HP iLO, Supermicro IPMI) and Windows OS
 * message pumps poll their USB HID keyboard buffers at ~10ms-16ms intervals. Instantaneous
 * 0ms key-down/key-up pulses are frequently missed.
 *
 * This manager provides:
 * 1. 50ms key-press hold duration before releasing keys.
 * 2. 25ms inter-key pacing delay during text streaming to avoid buffer overrun.
 * 3. Non-blocking coroutine actor queue ([Channel]) ensuring the Android UI thread and
 *    FreeRDP network IO loops never block while hardware timing delays execute.
 * 4. Preservation of the 0x0100 extended scancode bit.
 */
interface KeyboardTimingManager {
    val keyHoldDurationMs: Long get() = 50L
    val interKeyPacingMs: Long get() = 25L

    fun sendKeyPressWithHold(vkCode: Int, isExtended: Boolean = false, holdDurationMs: Long = 50L)
    fun sendTextWithPacing(text: String, pacingDelayMs: Long = 25L)
    fun sendKeyDown(vkCode: Int, isExtended: Boolean = false)
    fun sendKeyUp(vkCode: Int, isExtended: Boolean = false)
    fun releaseAllModifiers()

    // BMC hold helpers for common keys
    fun sendEnterKey(holdDurationMs: Long = keyHoldDurationMs)
    fun sendBackspaceKey(holdDurationMs: Long = keyHoldDurationMs)
    fun sendSpaceKey(holdDurationMs: Long = keyHoldDurationMs)
    fun sendTabKey(holdDurationMs: Long = keyHoldDurationMs)
    fun sendSpecialKey(vkCode: Int, isExtended: Boolean = false, holdDurationMs: Long = keyHoldDurationMs)
    fun sendUnicodeCharWithHold(unicodeChar: Char, holdDurationMs: Long = keyHoldDurationMs)
    fun sendScancodeWithHold(scancode: Int, isExtended: Boolean = false, holdDurationMs: Long = keyHoldDurationMs)
}

/**
 * Sequential input commands processed by the actor queue.
 */
sealed class KeyCommand {
    data class Down(
        val code: Int,
        val isExtended: Boolean = false,
        val isUnicode: Boolean = false
    ) : KeyCommand()

    data class Up(
        val code: Int,
        val isExtended: Boolean = false,
        val isUnicode: Boolean = false
    ) : KeyCommand()

    data class Hold(
        val code: Int,
        val isExtended: Boolean = false,
        val isUnicode: Boolean = false,
        val holdDurationMs: Long = 50L
    ) : KeyCommand()

    data class TextStream(
        val text: String,
        val pacingDelayMs: Long = 25L,
        val holdDurationMs: Long = 50L
    ) : KeyCommand()

    object ReleaseAllModifiers : KeyCommand()
}

class DefaultKeyboardTimingManager(
    private val rdpEngine: IRdpEngine,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
    override val keyHoldDurationMs: Long = 50L,
    override val interKeyPacingMs: Long = 25L
) : KeyboardTimingManager {

    private val commandChannel = Channel<KeyCommand>(capacity = Channel.UNLIMITED)
    private val actorJob: Job

    init {
        actorJob = coroutineScope.launch {
            for (cmd in commandChannel) {
                processCommand(cmd)
            }
        }
    }

    override fun sendKeyPressWithHold(vkCode: Int, isExtended: Boolean, holdDurationMs: Long) {
        commandChannel.trySend(KeyCommand.Hold(code = vkCode, isExtended = isExtended, isUnicode = false, holdDurationMs = holdDurationMs))
    }

    override fun sendTextWithPacing(text: String, pacingDelayMs: Long) {
        commandChannel.trySend(KeyCommand.TextStream(text = text, pacingDelayMs = pacingDelayMs, holdDurationMs = keyHoldDurationMs))
    }

    override fun sendKeyDown(vkCode: Int, isExtended: Boolean) {
        commandChannel.trySend(KeyCommand.Down(code = vkCode, isExtended = isExtended, isUnicode = false))
    }

    override fun sendKeyUp(vkCode: Int, isExtended: Boolean) {
        commandChannel.trySend(KeyCommand.Up(code = vkCode, isExtended = isExtended, isUnicode = false))
    }

    override fun releaseAllModifiers() {
        commandChannel.trySend(KeyCommand.ReleaseAllModifiers)
    }

    override fun sendEnterKey(holdDurationMs: Long) {
        sendKeyPressWithHold(ScancodeTranslator.VK_RETURN, isExtended = false, holdDurationMs = holdDurationMs)
    }

    override fun sendBackspaceKey(holdDurationMs: Long) {
        sendKeyPressWithHold(ScancodeTranslator.VK_BACK, isExtended = false, holdDurationMs = holdDurationMs)
    }

    override fun sendSpaceKey(holdDurationMs: Long) {
        sendKeyPressWithHold(ScancodeTranslator.VK_SPACE, isExtended = false, holdDurationMs = holdDurationMs)
    }

    override fun sendTabKey(holdDurationMs: Long) {
        sendKeyPressWithHold(ScancodeTranslator.VK_TAB, isExtended = false, holdDurationMs = holdDurationMs)
    }

    override fun sendSpecialKey(vkCode: Int, isExtended: Boolean, holdDurationMs: Long) {
        sendKeyPressWithHold(vkCode, isExtended = isExtended, holdDurationMs = holdDurationMs)
    }

    override fun sendUnicodeCharWithHold(unicodeChar: Char, holdDurationMs: Long) {
        commandChannel.trySend(KeyCommand.Hold(code = unicodeChar.code, isExtended = false, isUnicode = true, holdDurationMs = holdDurationMs))
    }

    override fun sendScancodeWithHold(scancode: Int, isExtended: Boolean, holdDurationMs: Long) {
        commandChannel.trySend(KeyCommand.Hold(code = scancode, isExtended = isExtended, isUnicode = false, holdDurationMs = holdDurationMs))
    }

    private suspend fun processCommand(cmd: KeyCommand) {
        when (cmd) {
            is KeyCommand.Down -> {
                sendNative(cmd.code, down = true, isExtended = cmd.isExtended, isUnicode = cmd.isUnicode)
            }
            is KeyCommand.Up -> {
                sendNative(cmd.code, down = false, isExtended = cmd.isExtended, isUnicode = cmd.isUnicode)
            }
            is KeyCommand.Hold -> {
                sendNative(cmd.code, down = true, isExtended = cmd.isExtended, isUnicode = cmd.isUnicode)
                delay(cmd.holdDurationMs)
                sendNative(cmd.code, down = false, isExtended = cmd.isExtended, isUnicode = cmd.isUnicode)
            }
            is KeyCommand.TextStream -> {
                val sanitized = cmd.text.replace("\r\n", " ").replace('\r', ' ').replace('\n', ' ')
                var idx = 0
                while (idx < sanitized.length) {
                    val cp = sanitized.codePointAt(idx)
                    idx += Character.charCount(cp)

                    sendNative(cp, down = true, isExtended = false, isUnicode = true)
                    delay(cmd.holdDurationMs)
                    sendNative(cp, down = false, isExtended = false, isUnicode = true)

                    if (idx < sanitized.length) {
                        delay(cmd.pacingDelayMs)
                    }
                }
            }
            is KeyCommand.ReleaseAllModifiers -> {
                // Release Left & Right Ctrl, Alt, Shift, Win modifiers
                val modifiersToRelease = listOf(
                    Pair(ScancodeTranslator.VK_LSHIFT, false),
                    Pair(ScancodeTranslator.VK_RSHIFT, false),
                    Pair(ScancodeTranslator.VK_LCONTROL, false),
                    Pair(ScancodeTranslator.VK_RCONTROL, true),
                    Pair(ScancodeTranslator.VK_LMENU, false),
                    Pair(ScancodeTranslator.VK_RMENU, true),
                    Pair(ScancodeTranslator.VK_LWIN, true),
                    Pair(ScancodeTranslator.VK_RWIN, true)
                )
                modifiersToRelease.forEach { (vk, isExt) ->
                    sendNative(vk, down = false, isExtended = isExt, isUnicode = false)
                }
            }
        }
    }

    private fun sendNative(code: Int, down: Boolean, isExtended: Boolean, isUnicode: Boolean) {
        if (isUnicode) {
            rdpEngine.sendUnicodeKeyEvent(code.toChar(), down)
        } else {
            val codeToSend = if (isExtended && (code and ScancodeTranslator.EXTENDED_KEY_FLAG == 0)) {
                code or ScancodeTranslator.EXTENDED_KEY_FLAG
            } else {
                code
            }
            rdpEngine.sendKeyEvent(codeToSend, down)
        }
    }

    fun shutdown() {
        commandChannel.close()
        actorJob.cancel()
    }
}
