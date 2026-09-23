package com.freerdp.core

import com.freerdp.core.protocol.ClipboardHandler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardHandlerTest {

    @Test
    fun testUnicodeTextEncodeDecodeRoundtrip() {
        val originalText = "Hello FreeRDP! 🚀 Тест"
        val encoded = ClipboardHandler.encodeUnicodeText(originalText)

        // Verify null termination: last 2 bytes must be 0
        assertTrue(encoded.size >= 2)
        assertEquals(0.toByte(), encoded[encoded.size - 1])
        assertEquals(0.toByte(), encoded[encoded.size - 2])

        val decoded = ClipboardHandler.decodeUnicodeText(encoded)
        assertEquals(originalText, decoded)
    }

    @Test
    fun testSha256Determinism() {
        val hash1 = ClipboardHandler.sha256("ConsistentText")
        val hash2 = ClipboardHandler.sha256("ConsistentText")
        val hash3 = ClipboardHandler.sha256("DifferentText")

        assertEquals(hash1, hash2)
        assertFalse(hash1 == hash3)
        assertEquals(64, hash1.length)
    }

    @Test
    fun testLocalClipboardChangeDispatchesToRemote() {
        var remoteFormat = 0
        var remoteData: ByteArray? = null

        val handler = ClipboardHandler(
            onSendRemoteClipboard = { format, data ->
                remoteFormat = format
                remoteData = data
            },
            onLocalClipboardUpdate = {}
        )

        val handled = handler.onLocalClipboardChanged("Test local copy")
        assertTrue(handled)
        assertEquals(ClipboardHandler.CF_UNICODETEXT, remoteFormat)
        assertNotNull(remoteData)
        assertEquals("Test local copy", ClipboardHandler.decodeUnicodeText(remoteData!!))
    }

    @Test
    fun testEchoSuppressionFromRemoteToLocalAndBack() {
        var sendRemoteCount = 0
        var localUpdateCount = 0
        var lastLocalText = ""

        val handler = ClipboardHandler(
            onSendRemoteClipboard = { _, _ -> sendRemoteCount++ },
            onLocalClipboardUpdate = { text ->
                localUpdateCount++
                lastLocalText = text
            }
        )

        val remoteBytes = ClipboardHandler.encodeUnicodeText("Pasted from Windows")

        // 1. Remote host sends clipboard data to client
        val remoteAccepted = handler.onRemoteClipboardReceived(ClipboardHandler.CF_UNICODETEXT, remoteBytes)
        assertTrue(remoteAccepted)
        assertEquals(1, localUpdateCount)
        assertEquals("Pasted from Windows", lastLocalText)

        // 2. Android system fires local clipboard change event as a result of updating primary clip
        val localEchoHandled = handler.onLocalClipboardChanged("Pasted from Windows")
        // MUST be suppressed to prevent ping-pong loop!
        assertFalse(localEchoHandled)
        assertEquals(0, sendRemoteCount)

        // 3. User actually copies NEW content on Android device
        val newCopyHandled = handler.onLocalClipboardChanged("Brand new text from phone")
        assertTrue(newCopyHandled)
        assertEquals(1, sendRemoteCount)
    }

    @Test
    fun testEchoSuppressionFromLocalToRemoteAndBack() {
        var localUpdateCount = 0
        var sendRemoteCount = 0

        val handler = ClipboardHandler(
            onSendRemoteClipboard = { _, _ -> sendRemoteCount++ },
            onLocalClipboardUpdate = { localUpdateCount++ }
        )

        // 1. User copies on Android
        val localHandled = handler.onLocalClipboardChanged("Local copy text")
        assertTrue(localHandled)
        assertEquals(1, sendRemoteCount)

        // 2. Remote server echoes back the same text via format update
        val echoBytes = ClipboardHandler.encodeUnicodeText("Local copy text")
        val remoteHandled = handler.onRemoteClipboardReceived(ClipboardHandler.CF_UNICODETEXT, echoBytes)
        // MUST be suppressed
        assertFalse(remoteHandled)
        assertEquals(0, localUpdateCount)
    }

    @Test
    fun testEmptyOrInvalidFormatsIgnored() {
        var sendRemoteCount = 0
        var localUpdateCount = 0

        val handler = ClipboardHandler(
            onSendRemoteClipboard = { _, _ -> sendRemoteCount++ },
            onLocalClipboardUpdate = { localUpdateCount++ }
        )

        // Empty string
        assertFalse(handler.onLocalClipboardChanged(""))
        assertEquals(0, sendRemoteCount)

        // Unsupported format ID
        assertFalse(handler.onRemoteClipboardReceived(ClipboardHandler.CF_DIB, byteArrayOf(1, 2, 3)))
        assertEquals(0, localUpdateCount)
    }
}
