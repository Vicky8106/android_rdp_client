package com.freerdp.freerdpcore.services;

import android.content.Context;
import android.graphics.Bitmap;

/**
 * JNI bridge to the FreeRDP Android native libraries.
 *
 * <p><b>Native packaging status:</b> the four libraries loaded below
 * ({@code libwinpr3.so}, {@code libfreerdp3.so}, {@code libfreerdp-client3.so},
 * {@code libfreerdp-android.so}) are <b>not</b> packaged with this repository yet.
 * The loader degrades gracefully: on any failure {@link #isNativeLoaded()} returns
 * {@code false} and {@link #getLoadError()} exposes the first load error so callers
 * can surface a typed failure instead of crashing.</p>
 *
 * <p><b>JNI callback contract:</b> the static {@code On*} methods below are looked up
 * <b>by name and descriptor</b> from the native glue ({@code android_freerdp.c},
 * {@code android_cliprdr.c} in FreeRDP's {@code client/Android} tree) via
 * {@code GetStaticMethodID}. Their names, parameter types and order are a hard binary
 * contract — renaming or reshaping any of them silently breaks native→Java event
 * delivery, and dropping {@code OnPointerSet} breaks {@code JNI_OnLoad} outright.
 * This contract is pinned by {@code LibFreeRdpJniContractTest} in the unit test suite.</p>
 *
 * <p><b>Threading:</b> static {@code On*} entry points are invoked from JNI-attached
 * native FreeRDP worker threads. Dispatch is null-safe and must never throw; the single
 * registered {@link NativeCallbacks} implementation is responsible for stale-instance
 * filtering (see {@code NativeFreeRdpEngine}).</p>
 */
public class LibFreeRDP {
    private static volatile boolean sNativeLoaded = false;
    private static Throwable sLoadError = null;

    /** Single registered native→Java dispatch target; cleared on engine teardown. */
    private static volatile NativeCallbacks sCallbacks = null;

    static {
        try {
            System.loadLibrary("winpr3");
            System.loadLibrary("freerdp3");
            System.loadLibrary("freerdp-client3");
            System.loadLibrary("freerdp-android");
            sNativeLoaded = true;
        } catch (Throwable t) {
            sNativeLoaded = false;
            sLoadError = t;
        }
    }

    public static boolean isNativeLoaded() {
        return sNativeLoaded;
    }

    /** The first library load failure, or {@code null} when everything loaded fine. */
    public static Throwable getLoadError() {
        return sLoadError;
    }

    // ------------------------------------------------------------------
    // Callback registration (Java side of the native event pipe)
    // ------------------------------------------------------------------

    /**
     * Registers the dispatch target for all native {@code On*} callbacks.
     * Engines must remove themselves during teardown so the static field never
     * retains a dead engine (identity-checked removal in
     * {@code NativeFreeRdpEngine#teardownSession()}).
     */
    public static void setNativeCallbacks(NativeCallbacks callbacks) {
        sCallbacks = callbacks;
    }

    /** Currently registered callbacks, or {@code null}. Used for identity-checked removal. */
    public static NativeCallbacks getNativeCallbacks() {
        return sCallbacks;
    }

    // ------------------------------------------------------------------
    // Native methods exported by libfreerdp-android.so (FreeRDP 3.x
    // client/Android glue: Java_com_freerdp_freerdpcore_services_LibFreeRDP_*).
    // Keep names/signatures in sync with the JNI exports.
    // ------------------------------------------------------------------

    public static native long freerdp_new(Context context);
    public static native void freerdp_free(long inst);
    public static native boolean freerdp_connect(long inst);
    public static native boolean freerdp_disconnect(long inst);
    public static native boolean freerdp_parse_arguments(long inst, String[] args);
    public static native boolean freerdp_send_cursor_event(long inst, int x, int y, int flags);
    public static native boolean freerdp_send_key_event(long inst, int keycode, boolean down);
    public static native boolean freerdp_send_unicodekey_event(long inst, int unicode, boolean down);
    public static native boolean freerdp_send_clipboard_data(long inst, String data);
    public static native boolean freerdp_send_clipboard_image_data(long inst, byte[] data, String mimeType);
    public static native boolean freerdp_send_monitor_layout(long inst, int width, int height);
    public static native boolean freerdp_update_graphics(long inst, Bitmap bitmap, int x, int y, int width, int height);

    /**
     * Last error message for the given FreeRDP <b>instance</b>.
     *
     * <p>Semantics note: the native implementation ({@code jni_freerdp_get_last_error_string})
     * takes the {@code freerdp*} instance pointer and reads its context — it does <b>not</b>
     * take an error code. Passing a non-pointer value here would crash in native code.
     * There is intentionally no {@code freerdp_get_last_error(long)} native: the upstream
     * glue does not export it and calling it would throw {@code UnsatisfiedLinkError}
     * exactly when a connection fails.</p>
     */
    public static native String freerdp_get_last_error_string(long inst);

    // ------------------------------------------------------------------
    // Native → Java static callbacks.
    // Signatures verified against FreeRDP master client/Android sources:
    //   android_freerdp.c : OnPreConnect(J)V, OnConnectionSuccess(J)V, OnConnectionFailure(J)V,
    //                       OnDisconnecting(J)V, OnDisconnected(J)V, OnSettingsChanged(JIII)V,
    //                       OnAuthenticate(J+3x StringBuilder)Z, OnGatewayAuthenticate(...)Z,
    //                       OnVerifyCertificateEx(J,String,J,String,String,String,String,J)I,
    //                       OnVerifyChangedCertificateEx(J,String,J,Stringx7,J)I,
    //                       OnExperimentalFeature(JI)Z, OnGraphicsUpdate(JIIII)V,
    //                       OnGraphicsResize(JIII)V, OnPointerSet(J[IIIII)V [required by JNI_OnLoad],
    //                       OnPointerSetNull(J)V, OnPointerSetDefault(J)V
    //   android_cliprdr.c : OnRemoteClipboardChanged(JLjava/lang/String;)V,
    //                       OnRemoteClipboardImageChanged(J[B)V
    //   android_rail.c    : OnRailWindowUpdate(JJII[I)V, OnRailWindowMove(JJIIII)V,
    //                       OnRailWindowHide(JJ)V, OnRailWindowDestroy(JJ)V,
    //                       OnRailSessionEnd(J)V, OnRailMonitoredDesktop(J[JJ)V
    //   stable-2.0 legacy : OnVerifyCertificate(J,String,String,String,String,Z)I,
    //                       OnResolveChangedCertificate(J,Stringx7)I
    // Return polarity (libfreerdp/crypto/tls.c `accept_certificate` switch):
    //   1 = accept & persist to known_hosts, 2 = accept once, 0 = reject.
    //
    // Declared public so JVM unit tests can pin the exact name+descriptor contract;
    // JNI GetStaticMethodID resolves them regardless of Java visibility.
    // ------------------------------------------------------------------

    public static void OnPreConnect(long inst) {
        NativeCallbacks c = sCallbacks;
        if (c != null) c.onPreConnect(inst);
    }

    public static void OnConnectionSuccess(long inst) {
        NativeCallbacks c = sCallbacks;
        if (c != null) c.onConnectionSuccess(inst);
    }

    public static void OnConnectionFailure(long inst) {
        NativeCallbacks c = sCallbacks;
        if (c != null) c.onConnectionFailure(inst);
    }

    public static void OnDisconnecting(long inst) {
        NativeCallbacks c = sCallbacks;
        if (c != null) c.onDisconnecting(inst);
    }

    public static void OnDisconnected(long inst) {
        NativeCallbacks c = sCallbacks;
        if (c != null) c.onDisconnected(inst);
    }

    public static void OnSettingsChanged(long inst, int width, int height, int bpp) {
        NativeCallbacks c = sCallbacks;
        if (c != null) c.onSettingsChanged(inst, width, height, bpp);
    }

    public static boolean OnAuthenticate(long inst, StringBuilder username, StringBuilder domain,
                                         StringBuilder password) {
        NativeCallbacks c = sCallbacks;
        return c != null && c.onAuthenticate(inst, username, domain, password);
    }

    public static boolean OnGatewayAuthenticate(long inst, StringBuilder username, StringBuilder domain,
                                                StringBuilder password) {
        NativeCallbacks c = sCallbacks;
        return c != null && c.onGatewayAuthenticate(inst, username, domain, password);
    }

    public static int OnVerifyCertificateEx(long inst, String host, long port, String commonName,
                                            String subject, String issuer, String fingerprint, long flags) {
        NativeCallbacks c = sCallbacks;
        if (c == null) return 0; // no listener → reject (secure default)
        return c.onVerifyCertificateEx(inst, host, port, commonName, subject, issuer, fingerprint, flags);
    }

    public static int OnVerifyChangedCertificateEx(long inst, String host, long port, String commonName,
                                                   String subject, String issuer, String newFingerprint,
                                                   String oldSubject, String oldIssuer, String oldFingerprint,
                                                   long flags) {
        NativeCallbacks c = sCallbacks;
        if (c == null) return 0; // no listener → reject (secure default)
        return c.onVerifyChangedCertificateEx(inst, host, port, commonName, subject, issuer,
                newFingerprint, oldSubject, oldIssuer, oldFingerprint, flags);
    }

    /** FreeRDP 2.x-era glue callback (compatibility with stable-2.0 builds). */
    public static int OnVerifyCertificate(long inst, String commonName, String subject,
                                          String issuer, String fingerprint, boolean hostMismatch) {
        NativeCallbacks c = sCallbacks;
        if (c == null) return 0;
        return c.onVerifyCertificateLegacy(inst, commonName, subject, issuer, fingerprint, hostMismatch);
    }

    /** FreeRDP 2.x-era glue callback (compatibility with stable-2.0 builds). */
    public static int OnVerifyChangedCertificate(long inst, String commonName, String subject,
                                                 String issuer, String newFingerprint,
                                                 String oldSubject, String oldIssuer,
                                                 String oldFingerprint) {
        NativeCallbacks c = sCallbacks;
        if (c == null) return 0;
        return c.onVerifyChangedCertificateLegacy(inst, commonName, subject, issuer, newFingerprint,
                oldSubject, oldIssuer, oldFingerprint);
    }

    public static boolean OnExperimentalFeature(long inst, int feature) {
        NativeCallbacks c = sCallbacks;
        return c != null && c.onExperimentalFeature(inst, feature);
    }

    public static void OnGraphicsUpdate(long inst, int x, int y, int width, int height) {
        NativeCallbacks c = sCallbacks;
        if (c != null) c.onGraphicsUpdate(inst, x, y, width, height);
    }

    public static void OnGraphicsResize(long inst, int width, int height, int bpp) {
        NativeCallbacks c = sCallbacks;
        if (c != null) c.onGraphicsResize(inst, width, height, bpp);
    }

    public static void OnRemoteClipboardChanged(long inst, String data) {
        NativeCallbacks c = sCallbacks;
        if (c != null) c.onRemoteClipboardChanged(inst, data);
    }

    public static void OnRemoteClipboardImageChanged(long inst, byte[] pngData) {
        NativeCallbacks c = sCallbacks;
        if (c != null) c.onRemoteClipboardImageChanged(inst, pngData);
    }

    public static void OnPointerSet(long inst, int[] pixels, int width, int height, int hotX, int hotY) {
        NativeCallbacks c = sCallbacks;
        if (c != null) c.onPointerSet(inst, pixels, width, height, hotX, hotY);
    }

    public static void OnPointerSetNull(long inst) {
        NativeCallbacks c = sCallbacks;
        if (c != null) c.onPointerSetNull(inst);
    }

    public static void OnPointerSetDefault(long inst) {
        NativeCallbacks c = sCallbacks;
        if (c != null) c.onPointerSetDefault(inst);
    }

    public static void OnRailWindowUpdate(long inst, long windowId, int width, int height, int[] pixels) {
        NativeCallbacks c = sCallbacks;
        if (c != null) c.onRailWindowUpdate(inst, windowId, width, height, pixels);
    }

    public static void OnRailWindowMove(long inst, long windowId, int x, int y, int w, int h) {
        NativeCallbacks c = sCallbacks;
        if (c != null) c.onRailWindowMove(inst, windowId, x, y, w, h);
    }

    public static void OnRailWindowHide(long inst, long windowId) {
        NativeCallbacks c = sCallbacks;
        if (c != null) c.onRailWindowHide(inst, windowId);
    }

    public static void OnRailWindowDestroy(long inst, long windowId) {
        NativeCallbacks c = sCallbacks;
        if (c != null) c.onRailWindowDestroy(inst, windowId);
    }

    public static void OnRailSessionEnd(long inst) {
        NativeCallbacks c = sCallbacks;
        if (c != null) c.onRailSessionEnd(inst);
    }

    public static void OnRailMonitoredDesktop(long inst, long[] windowIds, long activeWindowId) {
        NativeCallbacks c = sCallbacks;
        if (c != null) c.onRailMonitoredDesktop(inst, windowIds, activeWindowId);
    }

    // ------------------------------------------------------------------
    // Dispatch target implemented by NativeFreeRdpEngine. Every method
    // receives the native instance pointer so implementations can drop
    // stale callbacks belonging to an already-torn-down session.
    // ------------------------------------------------------------------

    public interface NativeCallbacks {
        void onPreConnect(long inst);

        void onConnectionSuccess(long inst);

        void onConnectionFailure(long inst);

        void onDisconnecting(long inst);

        void onDisconnected(long inst);

        void onSettingsChanged(long inst, int width, int height, int bpp);

        boolean onAuthenticate(long inst, StringBuilder username, StringBuilder domain, StringBuilder password);

        boolean onGatewayAuthenticate(long inst, StringBuilder username, StringBuilder domain, StringBuilder password);

        int onVerifyCertificateEx(long inst, String host, long port, String commonName, String subject,
                                  String issuer, String fingerprint, long flags);

        int onVerifyChangedCertificateEx(long inst, String host, long port, String commonName, String subject,
                                         String issuer, String newFingerprint, String oldSubject,
                                         String oldIssuer, String oldFingerprint, long flags);

        int onVerifyCertificateLegacy(long inst, String commonName, String subject, String issuer,
                                      String fingerprint, boolean hostMismatch);

        int onVerifyChangedCertificateLegacy(long inst, String commonName, String subject, String issuer,
                                             String newFingerprint, String oldSubject, String oldIssuer,
                                             String oldFingerprint);

        boolean onExperimentalFeature(long inst, int feature);

        void onGraphicsUpdate(long inst, int x, int y, int width, int height);

        void onGraphicsResize(long inst, int width, int height, int bpp);

        void onRemoteClipboardChanged(long inst, String data);

        void onRemoteClipboardImageChanged(long inst, byte[] pngData);

        void onPointerSet(long inst, int[] pixels, int width, int height, int hotX, int hotY);

        void onPointerSetNull(long inst);

        void onPointerSetDefault(long inst);

        void onRailWindowUpdate(long inst, long windowId, int width, int height, int[] pixels);

        void onRailWindowMove(long inst, long windowId, int x, int y, int w, int h);

        void onRailWindowHide(long inst, long windowId);

        void onRailWindowDestroy(long inst, long windowId);

        void onRailSessionEnd(long inst);

        void onRailMonitoredDesktop(long inst, long[] windowIds, long activeWindowId);
    }
}
