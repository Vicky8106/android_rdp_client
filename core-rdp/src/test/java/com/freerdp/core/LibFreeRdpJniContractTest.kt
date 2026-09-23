package com.freerdp.core

import com.freerdp.freerdpcore.services.LibFreeRDP
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * Pins the Java↔native JNI contract of [LibFreeRDP] via reflection.
 *
 * The native glue (`client/Android/**/android_freerdp.c` in FreeRDP) resolves these
 * methods **by name and descriptor** through `GetStaticMethodID`/`RegisterNatives`.
 * Renaming or reshaping any entry silently breaks native→Java event delivery — worst
 * case `JNI_OnLoad` fails on the required `OnPointerSet` lookup and the library can
 * never load. These assertions fail loudly if the contract drifts.
 */
class LibFreeRdpJniContractTest {

    private class RecordingCallbacks : LibFreeRDP.NativeCallbacks {
        val events = mutableListOf<String>()
        var verifyResult = 0
        var authResult = false

        override fun onPreConnect(inst: Long) { events += "preConnect:$inst" }
        override fun onConnectionSuccess(inst: Long) { events += "success:$inst" }
        override fun onConnectionFailure(inst: Long) { events += "failure:$inst" }
        override fun onDisconnecting(inst: Long) { events += "disconnecting:$inst" }
        override fun onDisconnected(inst: Long) { events += "disconnected:$inst" }
        override fun onSettingsChanged(inst: Long, width: Int, height: Int, bpp: Int) {
            events += "settings:$inst:${width}x${height}@$bpp"
        }
        override fun onAuthenticate(
            inst: Long, username: StringBuilder, domain: StringBuilder, password: StringBuilder
        ): Boolean {
            events += "authenticate:$inst"
            return authResult
        }
        override fun onGatewayAuthenticate(
            inst: Long, username: StringBuilder, domain: StringBuilder, password: StringBuilder
        ): Boolean = false

        override fun onVerifyCertificateEx(
            inst: Long, host: String, port: Long, commonName: String, subject: String,
            issuer: String, fingerprint: String, flags: Long
        ): Int {
            events += "verify:$inst:$host:$port:$fingerprint"
            return verifyResult
        }

        override fun onVerifyChangedCertificateEx(
            inst: Long, host: String, port: Long, commonName: String, subject: String,
            issuer: String, newFingerprint: String, oldSubject: String, oldIssuer: String,
            oldFingerprint: String, flags: Long
        ): Int = verifyResult

        override fun onVerifyCertificateLegacy(
            inst: Long, commonName: String, subject: String, issuer: String,
            fingerprint: String, hostMismatch: Boolean
        ): Int = verifyResult

        override fun onVerifyChangedCertificateLegacy(
            inst: Long, commonName: String, subject: String, issuer: String,
            newFingerprint: String, oldSubject: String, oldIssuer: String, oldFingerprint: String
        ): Int = verifyResult

        override fun onExperimentalFeature(inst: Long, feature: Int): Boolean {
            events += "experimental:$inst:$feature"
            return false
        }

        override fun onGraphicsUpdate(inst: Long, x: Int, y: Int, width: Int, height: Int) {
            events += "graphics:$inst:$x,$y,${width}x$height"
        }

        override fun onGraphicsResize(inst: Long, width: Int, height: Int, bpp: Int) {
            events += "resize:$inst:${width}x${height}@$bpp"
        }

        override fun onRemoteClipboardChanged(inst: Long, data: String) {
            events += "clipboard:$inst:$data"
        }

        override fun onRemoteClipboardImageChanged(inst: Long, pngData: ByteArray) {
            events += "clipboardImage:$inst:${pngData.size}"
        }

        override fun onPointerSet(inst: Long, pixels: IntArray, width: Int, height: Int, hotX: Int, hotY: Int) {
            events += "pointer:$inst:${width}x$height"
        }

        override fun onPointerSetNull(inst: Long) { events += "pointerNull:$inst" }
        override fun onPointerSetDefault(inst: Long) { events += "pointerDefault:$inst" }
        override fun onRailWindowUpdate(inst: Long, windowId: Long, width: Int, height: Int, pixels: IntArray) = Unit
        override fun onRailWindowMove(inst: Long, windowId: Long, x: Int, y: Int, w: Int, h: Int) = Unit
        override fun onRailWindowHide(inst: Long, windowId: Long) = Unit
        override fun onRailWindowDestroy(inst: Long, windowId: Long) = Unit
        override fun onRailSessionEnd(inst: Long) = Unit
        override fun onRailMonitoredDesktop(inst: Long, windowIds: LongArray, activeWindowId: Long) = Unit
    }

    @After
    fun tearDown() {
        // Static state must not leak across tests (same JVM, same classloader).
        LibFreeRDP.setNativeCallbacks(null)
    }

    // ------------------------------------------------------------------
    // Static callback descriptors (GetStaticMethodID contract)
    // ------------------------------------------------------------------

    private fun assertStaticCallback(
        name: String,
        returnType: Class<*>,
        vararg params: Class<*>
    ) {
        val method = try {
            LibFreeRDP::class.java.getDeclaredMethod(name, *params)
        } catch (e: NoSuchMethodError) {
            throw AssertionError("Missing JNI static callback $name — native glue would fail", e)
        } catch (e: NoSuchMethodException) {
            throw AssertionError("Missing JNI static callback $name — native glue would fail", e)
        }
        assertTrue("$name must be static", Modifier.isStatic(method.modifiers))
        assertEquals("return type of $name", returnType, method.returnType)
    }

    private val pLong = Long::class.javaPrimitiveType!!
    private val pInt = Int::class.javaPrimitiveType!!
    private val pBoolean = Boolean::class.javaPrimitiveType!!

    @Test
    fun lifecycleCallbacksMatchNativeDescriptors() {
        assertStaticCallback("OnPreConnect", Void.TYPE, pLong)
        assertStaticCallback("OnConnectionSuccess", Void.TYPE, pLong)
        assertStaticCallback("OnConnectionFailure", Void.TYPE, pLong)
        assertStaticCallback("OnDisconnecting", Void.TYPE, pLong)
        assertStaticCallback("OnDisconnected", Void.TYPE, pLong)
        assertStaticCallback("OnSettingsChanged", Void.TYPE, pLong, pInt, pInt, pInt)
    }

    @Test
    fun graphicsCallbacksMatchNativeDescriptors() {
        // (JIIII)V dirty-region update, (JIII)V resize
        assertStaticCallback(
            "OnGraphicsUpdate", Void.TYPE, pLong, pInt, pInt, pInt, pInt
        )
        assertStaticCallback("OnGraphicsResize", Void.TYPE, pLong, pInt, pInt, pInt)
    }

    @Test
    fun certificateCallbacksMatchNativeDescriptors() {
        // OnVerifyCertificateEx: (JLjava/lang/String;JLjava/lang/String;...String;J)I
        assertStaticCallback(
            "OnVerifyCertificateEx", Int::class.javaPrimitiveType!!,
            pLong, String::class.java, pLong, String::class.java,
            String::class.java, String::class.java, String::class.java, pLong
        )
        assertStaticCallback(
            "OnVerifyChangedCertificateEx", Int::class.javaPrimitiveType!!,
            pLong, String::class.java, pLong, String::class.java, String::class.java,
            String::class.java, String::class.java, String::class.java, String::class.java,
            String::class.java, pLong
        )
        // FreeRDP 2.x-era legacy variants (stable-2.0 glue)
        assertStaticCallback(
            "OnVerifyCertificate", Int::class.javaPrimitiveType!!,
            pLong, String::class.java, String::class.java, String::class.java,
            String::class.java, pBoolean
        )
        assertStaticCallback(
            "OnVerifyChangedCertificate", Int::class.javaPrimitiveType!!,
            pLong, String::class.java, String::class.java, String::class.java,
            String::class.java, String::class.java, String::class.java, String::class.java
        )
    }

    @Test
    fun authenticateAndClipboardCallbacksMatchNativeDescriptors() {
        val sb = StringBuilder::class.java
        assertStaticCallback(
            "OnAuthenticate", pBoolean, pLong, sb, sb, sb
        )
        assertStaticCallback(
            "OnGatewayAuthenticate", pBoolean, pLong, sb, sb, sb
        )
        assertStaticCallback(
            "OnRemoteClipboardChanged", Void.TYPE, pLong, String::class.java
        )
        assertStaticCallback(
            "OnRemoteClipboardImageChanged", Void.TYPE, pLong, ByteArray::class.java
        )
        assertStaticCallback(
            "OnExperimentalFeature", pBoolean, pLong, pInt
        )
    }

    @Test
    fun pointerSetIsPresentAsRequiredByJniOnLoad() {
        // JNI_OnLoad calls GetStaticMethodID("OnPointerSet", "(J[IIIII)V") WITHOUT an
        // ExceptionClear: if this method is missing the pending NoSuchMethodError makes
        // System.loadLibrary throw and libfreerdp-android can never load.
        assertStaticCallback(
            "OnPointerSet", Void.TYPE, pLong, IntArray::class.java, pInt, pInt, pInt, pInt
        )
        assertStaticCallback("OnPointerSetNull", Void.TYPE, pLong)
        assertStaticCallback("OnPointerSetDefault", Void.TYPE, pLong)
    }

    @Test
    fun railCallbacksMatchNativeDescriptors() {
        assertStaticCallback(
            "OnRailWindowUpdate", Void.TYPE, pLong, pLong, pInt, pInt, IntArray::class.java
        )
        assertStaticCallback("OnRailWindowMove", Void.TYPE, pLong, pLong, pInt, pInt, pInt, pInt)
        assertStaticCallback("OnRailWindowHide", Void.TYPE, pLong, pLong)
        assertStaticCallback("OnRailWindowDestroy", Void.TYPE, pLong, pLong)
        assertStaticCallback("OnRailSessionEnd", Void.TYPE, pLong)
        assertStaticCallback("OnRailMonitoredDesktop", Void.TYPE, pLong, LongArray::class.java, pLong)
    }

    // ------------------------------------------------------------------
    // Native method descriptors (RegisterNatives/symbol contract)
    // ------------------------------------------------------------------

    @Test
    fun nativeMethodDescriptorsMatchUpstreamExports() {
        // (J)Ljava/lang/String; — takes the freerdp* INSTANCE, not an error code.
        val getLastErrorString =
            LibFreeRDP::class.java.getDeclaredMethod("freerdp_get_last_error_string", pLong)
        assertEquals(String::class.java, getLastErrorString.returnType)
        assertTrue(Modifier.isNative(getLastErrorString.modifiers))

        // Regression guard: freerdp_get_last_error(long) has NO upstream export —
        // calling it threw UnsatisfiedLinkError exactly when a connection failed.
        try {
            LibFreeRDP::class.java.getDeclaredMethod("freerdp_get_last_error", pLong)
            fail("freerdp_get_last_error must not be declared: no native symbol backs it")
        } catch (expected: NoSuchMethodException) {
            // correct — removed deliberately
        }

        for (name in listOf(
            "freerdp_new", "freerdp_free", "freerdp_connect", "freerdp_disconnect",
            "freerdp_parse_arguments", "freerdp_send_cursor_event", "freerdp_send_key_event",
            "freerdp_send_unicodekey_event", "freerdp_send_clipboard_data",
            "freerdp_send_clipboard_image_data", "freerdp_send_monitor_layout",
            "freerdp_update_graphics"
        )) {
            val methods = LibFreeRDP::class.java.declaredMethods.filter { it.name == name }
            assertEquals("native $name must be declared exactly once", 1, methods.size)
            assertTrue("$name must be native", Modifier.isNative(methods[0].modifiers))
        }
    }

    // ------------------------------------------------------------------
    // Static dispatch: name → registered callbacks
    // ------------------------------------------------------------------

    @Test
    fun staticCallbacksDispatchToRegisteredTarget() {
        val recorder = RecordingCallbacks()
        LibFreeRDP.setNativeCallbacks(recorder)
        assertSame(recorder, LibFreeRDP.getNativeCallbacks())

        LibFreeRDP.OnConnectionSuccess(7L)
        LibFreeRDP.OnGraphicsResize(7L, 1024, 768, 32)
        LibFreeRDP.OnRemoteClipboardChanged(7L, "hello")
        assertEquals(
            listOf("success:7", "resize:7:1024x768@32", "clipboard:7:hello"),
            recorder.events
        )
    }

    @Test
    fun staticCallbacksAreNullSafeWithoutRegistration() {
        // Native threads can race registration/teardown; never NPE across JNI.
        LibFreeRDP.setNativeCallbacks(null)
        LibFreeRDP.OnPreConnect(1L)
        LibFreeRDP.OnConnectionSuccess(1L)
        LibFreeRDP.OnConnectionFailure(1L)
        LibFreeRDP.OnDisconnecting(1L)
        LibFreeRDP.OnDisconnected(1L)
        LibFreeRDP.OnSettingsChanged(1L, 800, 600, 32)
        LibFreeRDP.OnGraphicsUpdate(1L, 0, 0, 10, 10)
        LibFreeRDP.OnGraphicsResize(1L, 800, 600, 16)
        LibFreeRDP.OnRemoteClipboardChanged(1L, "x")
        LibFreeRDP.OnRemoteClipboardImageChanged(1L, byteArrayOf(1))
        LibFreeRDP.OnPointerSetNull(1L)
        LibFreeRDP.OnPointerSetDefault(1L)
        LibFreeRDP.OnPointerSet(1L, IntArray(4), 2, 2, 0, 0)
        // Fail-closed defaults with no listener: cert rejected, auth declined.
        assertEquals(
            "no listener must reject the certificate (fail closed)",
            0,
            LibFreeRDP.OnVerifyCertificateEx(1L, "h", 443, "cn", "s", "i", "fp", 0L)
        )
        assertFalse(LibFreeRDP.OnAuthenticate(1L, StringBuilder(), StringBuilder(), StringBuilder()))
    }

    @Test
    fun certificateResultIsForwardedVerbatim() {
        val recorder = RecordingCallbacks()
        LibFreeRDP.setNativeCallbacks(recorder)
        recorder.verifyResult = 2 // accept-once (FreeRDP accept_certificate polarity)
        val result = LibFreeRDP.OnVerifyCertificateEx(
            5L, "example.com", 3389, "cn", "subject", "issuer", "SHA256:ABCD", 1L
        )
        assertEquals(2, result)
        assertEquals(listOf("verify:5:example.com:3389:SHA256:ABCD"), recorder.events)

        recorder.verifyResult = 0
        assertEquals(
            0,
            LibFreeRDP.OnVerifyCertificateEx(5L, "example.com", 3389, "cn", "s", "i", "fp", 0L)
        )
    }

    @Test
    fun authenticatePassesLiveStringBuildersForInPlaceMutation() {
        val recorder = RecordingCallbacks()
        LibFreeRDP.setNativeCallbacks(recorder)
        recorder.authResult = true
        val u = StringBuilder("old-user")
        val d = StringBuilder("")
        val p = StringBuilder("secret")
        assertTrue(LibFreeRDP.OnAuthenticate(3L, u, d, p))
        // The JNI contract mutates the builders in place (native reads them back).
        assertEquals("old-user", u.toString())
        assertEquals("", d.toString())
        assertEquals("secret", p.toString())
        assertEquals(listOf("authenticate:3"), recorder.events)
    }

    @Test
    fun setNativeCallbacksRoundTripsAndClears() {
        val recorder = RecordingCallbacks()
        LibFreeRDP.setNativeCallbacks(recorder)
        assertSame(recorder, LibFreeRDP.getNativeCallbacks())
        LibFreeRDP.setNativeCallbacks(null)
        assertNull(LibFreeRDP.getNativeCallbacks())
    }

    @Test
    fun loaderDegradesGracefullyOnJvmWithoutNativeLibraries() {
        // On the JVM unit-test runner System.loadLibrary always fails: the loader must
        // catch it, not throw, and surface both facts for the typed Failed state.
        assertFalse(LibFreeRDP.isNativeLoaded())
        val error = LibFreeRDP.getLoadError()
        assertTrue(
            "getLoadError must expose the load failure for the typed Failed state",
            error != null
        )
        assertTrue(
            "load error should name the missing library, got: $error",
            error.toString().contains("winpr3")
        )
    }
}
