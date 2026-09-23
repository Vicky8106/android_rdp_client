package com.freerdp.core.protocol

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

class ClipboardHandler(
    private val onSendRemoteClipboard: (format: Int, data: ByteArray) -> Unit,
    private val onLocalClipboardUpdate: (text: String) -> Unit
) {
    companion object {
        const val CF_TEXT = 1
        const val CF_DIB = 8
        const val CF_UNICODETEXT = 13

        fun encodeUnicodeText(text: String): ByteArray {
            val bytes = text.toByteArray(StandardCharsets.UTF_16LE)
            val nullTerminated = ByteArray(bytes.size + 2)
            System.arraycopy(bytes, 0, nullTerminated, 0, bytes.size)
            nullTerminated[bytes.size] = 0
            nullTerminated[bytes.size + 1] = 0
            return nullTerminated
        }

        fun decodeUnicodeText(data: ByteArray): String {
            val str = String(data, StandardCharsets.UTF_16LE)
            return str.trimEnd('\u0000')
        }

        fun sha256(text: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(text.toByteArray(StandardCharsets.UTF_8))
            return hash.joinToString("") { "%02x".format(it) }
        }
    }

    // Echo-suppression state is read/written from multiple threads in production:
    // local sends arrive on the UI/dispatch thread while remote receives arrive on
    // JNI-attached native worker threads. All entry points are synchronized so the
    // SHA-256 hash pair is always read+written atomically (no torn echo decisions).
    private var lastLocalHash: String? = null
    private var lastRemoteHash: String? = null

    @Synchronized
    fun onLocalClipboardChanged(text: String): Boolean {
        if (text.isEmpty()) return false
        val hash = sha256(text)
        if (hash == lastRemoteHash) {
            // Echo loop detected from remote copy, suppress
            return false
        }
        lastLocalHash = hash
        val encoded = encodeUnicodeText(text)
        onSendRemoteClipboard(CF_UNICODETEXT, encoded)
        return true
    }

    @Synchronized
    fun onRemoteClipboardReceived(format: Int, data: ByteArray): Boolean {
        if (format != CF_UNICODETEXT) return false
        val text = decodeUnicodeText(data)
        if (text.isEmpty()) return false
        val hash = sha256(text)
        if (hash == lastLocalHash) {
            // Echo loop detected from local copy, suppress
            return false
        }
        lastRemoteHash = hash
        onLocalClipboardUpdate(text)
        return true
    }

    @Synchronized
    fun getLastLocalHash(): String? = lastLocalHash

    @Synchronized
    fun getLastRemoteHash(): String? = lastRemoteHash

    @Synchronized
    fun clearEchoState() {
        lastLocalHash = null
        lastRemoteHash = null
    }
}
