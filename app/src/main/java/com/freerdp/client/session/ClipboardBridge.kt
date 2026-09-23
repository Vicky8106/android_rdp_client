package com.freerdp.client.session

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/**
 * Boundary to the Android system clipboard.
 *
 * The [startListening]/[stopListening] pair registers for local clipboard changes so the
 * session can push them to the remote side (local -> remote sync). [dispatchPrimaryClipChanged]
 * is the single funnel used both by the framework listener and by tests to simulate a
 * system clipboard event — the read path always goes through the real ClipboardManager.
 */
interface ClipboardBridge {
    fun read(): String?
    fun write(text: String)
    fun startListening(onChanged: (String) -> Unit)
    fun stopListening()
}

class AndroidClipboardBridge(context: Context) : ClipboardBridge {

    private val clipboard: ClipboardManager? =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    private var listener: ((String) -> Unit)? = null
    private var lastDispatchedText: String? = null

    private val frameworkListener = ClipboardManager.OnPrimaryClipChangedListener {
        dispatchPrimaryClipChanged()
    }

    /** Emits the current primary clip to the active listener (deduplicates identical repeats). */
    fun dispatchPrimaryClipChanged() {
        val text = read() ?: return
        if (text == lastDispatchedText) return
        lastDispatchedText = text
        listener?.invoke(text)
    }

    override fun read(): String? {
        val clip = clipboard?.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(null)?.toString()
    }

    override fun write(text: String) {
        clipboard?.setPrimaryClip(ClipData.newPlainText("RDP session", text))
        // New content must be dispatchable even if the OS fires no listener in some environments.
        lastDispatchedText = text
    }

    override fun startListening(onChanged: (String) -> Unit) {
        listener = onChanged
        lastDispatchedText = null
        clipboard?.addPrimaryClipChangedListener(frameworkListener)
    }

    override fun stopListening() {
        listener = null
        clipboard?.removePrimaryClipChangedListener(frameworkListener)
    }
}
