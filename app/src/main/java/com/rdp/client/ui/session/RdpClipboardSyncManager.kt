package com.rdp.client.ui.session

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import com.rdp.client.freerdp.LibFreeRDP
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages bi-directional text clipboard synchronization between Android OS and FreeRDP session.
 * Employs echo cancellation to eliminate cyclical clipboard reflection.
 */
class RdpClipboardSyncManager(
    private val context: Context,
    private val instanceProvider: () -> Long,
    private val isSyncEnabledProvider: () -> Boolean,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
) {

    private val TAG = "RdpClipboardSync"
    private val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    private val isRunning = AtomicBoolean(false)

    // Echo cancellation caches
    @Volatile private var lastLocalCopiedText: String? = null
    @Volatile private var lastRemoteReceivedText: String? = null

    private val clipChangedListener = ClipboardManager.OnPrimaryClipChangedListener {
        onLocalClipboardChanged()
    }

    fun start() {
        if (!isRunning.compareAndSet(false, true)) return
        clipboardManager?.addPrimaryClipChangedListener(clipChangedListener)
        Log.i(TAG, "Clipboard synchronization started")
    }

    fun stop() {
        if (!isRunning.compareAndSet(true, false)) return
        clipboardManager?.removePrimaryClipChangedListener(clipChangedListener)
        lastLocalCopiedText = null
        lastRemoteReceivedText = null
        Log.i(TAG, "Clipboard synchronization stopped")
    }

    /**
     * Fired when local Android clipboard changes.
     */
    private fun onLocalClipboardChanged() {
        if (!isRunning.get() || !isSyncEnabledProvider()) return
        val instance = instanceProvider()
        if (instance == 0L) return

        val clip = clipboardManager?.primaryClip ?: return
        if (clip.itemCount == 0) return

        val text = clip.getItemAt(0)?.coerceToText(context)?.toString() ?: return
        if (text.isEmpty()) return

        // Echo cancellation check: ignore if this came from the remote session
        if (text == lastRemoteReceivedText || text == lastLocalCopiedText) {
            return
        }

        lastLocalCopiedText = text
        Log.d(TAG, "Syncing local clipboard to remote session (${text.length} chars)")

        scope.launch(Dispatchers.IO) {
            sendTextToRemote(instance, text)
        }
    }

    /**
     * Dispatches text to remote FreeRDP session.
     */
    private fun sendTextToRemote(instance: Long, text: String) {
        try {
            // Send via fastpath Unicode or native clipboard bridge
            for (ch in text) {
                LibFreeRDP.sendUnicodeKeyEvent(instance, ch.code)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed sending clipboard text to remote: ${e.message}", e)
        }
    }

    /**
     * Fired when remote RDP session pushes a CLIPRDR clipboard update.
     */
    fun onRemoteClipboardReceived(text: String) {
        if (!isRunning.get() || !isSyncEnabledProvider()) return
        if (text.isEmpty()) return

        // Echo cancellation check: ignore if this was copied locally
        if (text == lastLocalCopiedText || text == lastRemoteReceivedText) {
            return
        }

        lastRemoteReceivedText = text
        Log.d(TAG, "Syncing remote clipboard to Android local (${text.length} chars)")

        scope.launch(Dispatchers.Main) {
            try {
                val clipData = ClipData.newPlainText("RDP Clipboard", text)
                clipboardManager?.setPrimaryClip(clipData)
            } catch (e: Throwable) {
                Log.e(TAG, "Failed setting local Android clipboard: ${e.message}", e)
            }
        }
    }
}
