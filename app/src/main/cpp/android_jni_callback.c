#include "android_freerdp.h"

static JavaVM* g_jvm = NULL;
static jclass g_libFreeRdpClass = NULL;

static jmethodID g_mid_onConnectionSuccess = NULL;
static jmethodID g_mid_onConnectionFailure = NULL;
static jmethodID g_mid_onGraphicsUpdate    = NULL;
static jmethodID g_mid_onAuthenticate      = NULL;
static jmethodID g_mid_onCursorMoved       = NULL;

/* -------------------------------------------------------------------------- */
/* Initialize Global References & Method ID Cache                             */
/* -------------------------------------------------------------------------- */
void jni_init_callbacks(JavaVM* vm, JNIEnv* env) {
    g_jvm = vm;
    if (!env) return;

    jclass localClass = (*env)->FindClass(env, "com/rdp/client/freerdp/LibFreeRDP");
    if (!localClass) {
        LOGE("jni_init_callbacks: Failed to find class com.rdp.client.freerdp.LibFreeRDP");
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        return;
    }

    g_libFreeRdpClass = (jclass)(*env)->NewGlobalRef(env, localClass);
    (*env)->DeleteLocalRef(env, localClass);

    if (!g_libFreeRdpClass) {
        LOGE("jni_init_callbacks: Failed to create global reference for LibFreeRDP");
        return;
    }

    // Cache static method IDs matching Kotlin LibFreeRDP companion / static methods
    g_mid_onConnectionSuccess = (*env)->GetStaticMethodID(
        env, g_libFreeRdpClass, "onConnectionSuccess", "(J)V");
    g_mid_onConnectionFailure = (*env)->GetStaticMethodID(
        env, g_libFreeRdpClass, "onConnectionFailure", "(JILjava/lang/String;)V");
    g_mid_onGraphicsUpdate = (*env)->GetStaticMethodID(
        env, g_libFreeRdpClass, "onGraphicsUpdate", "(JIIII)V");
    g_mid_onAuthenticate = (*env)->GetStaticMethodID(
        env, g_libFreeRdpClass, "onAuthenticate", "(J)V");
    g_mid_onCursorMoved = (*env)->GetStaticMethodID(
        env, g_libFreeRdpClass, "onCursorMoved", "(JII)V");

    LOGI("jni_init_callbacks: LibFreeRDP callback method IDs successfully initialized");
}

/* -------------------------------------------------------------------------- */
/* Clean Up Global References                                                 */
/* -------------------------------------------------------------------------- */
void jni_cleanup_callbacks(JNIEnv* env) {
    if (g_libFreeRdpClass && env) {
        (*env)->DeleteGlobalRef(env, g_libFreeRdpClass);
        g_libFreeRdpClass = NULL;
    }
    g_mid_onConnectionSuccess = NULL;
    g_mid_onConnectionFailure = NULL;
    g_mid_onGraphicsUpdate    = NULL;
    g_mid_onAuthenticate      = NULL;
    g_mid_onCursorMoved       = NULL;
    LOGI("jni_cleanup_callbacks: Callbacks cleaned up");
}

/* -------------------------------------------------------------------------- */
/* Thread Attachment Helpers                                                  */
/* -------------------------------------------------------------------------- */
JNIEnv* jni_get_env(int* attached) {
    *attached = 0;
    if (!g_jvm) {
        LOGE("jni_get_env: JavaVM pointer is NULL");
        return NULL;
    }

    JNIEnv* env = NULL;
    jint status = (*g_jvm)->GetEnv(g_jvm, (void**)&env, JNI_VERSION_1_6);

    if (status == JNI_EDETACHED) {
        JavaVMAttachArgs args;
        args.version = JNI_VERSION_1_6;
        args.name = "FreeRDP-WorkerThread";
        args.group = NULL;

        if ((*g_jvm)->AttachCurrentThread(g_jvm, &env, &args) == JNI_OK) {
            *attached = 1;
        } else {
            LOGE("jni_get_env: AttachCurrentThread failed");
            return NULL;
        }
    } else if (status != JNI_OK) {
        LOGE("jni_get_env: GetEnv failed with status: %d", status);
        return NULL;
    }

    return env;
}

void jni_release_env(int attached) {
    if (attached && g_jvm) {
        (*g_jvm)->DetachCurrentThread(g_jvm);
    }
}

/* -------------------------------------------------------------------------- */
/* Callback: onConnectionSuccess                                              */
/* -------------------------------------------------------------------------- */
void jni_callback_on_connection_success(jlong instance) {
    int attached = 0;
    JNIEnv* env = jni_get_env(&attached);
    if (!env || !g_libFreeRdpClass || !g_mid_onConnectionSuccess) {
        jni_release_env(attached);
        return;
    }

    (*env)->CallStaticVoidMethod(env, g_libFreeRdpClass, g_mid_onConnectionSuccess, instance);
    if ((*env)->ExceptionCheck(env)) {
        LOGE("Exception in onConnectionSuccess callback");
        (*env)->ExceptionClear(env);
    }

    jni_release_env(attached);
}

/* -------------------------------------------------------------------------- */
/* Callback: onConnectionFailure                                              */
/* -------------------------------------------------------------------------- */
void jni_callback_on_connection_failure(jlong instance, int errorCode, const char* message) {
    int attached = 0;
    JNIEnv* env = jni_get_env(&attached);
    if (!env || !g_libFreeRdpClass || !g_mid_onConnectionFailure) {
        jni_release_env(attached);
        return;
    }

    jstring jmsg = (*env)->NewStringUTF(env, message ? message : "Unknown RDP error");
    (*env)->CallStaticVoidMethod(
        env, g_libFreeRdpClass, g_mid_onConnectionFailure, instance, (jint)errorCode, jmsg);

    if (jmsg) {
        (*env)->DeleteLocalRef(env, jmsg);
    }
    if ((*env)->ExceptionCheck(env)) {
        LOGE("Exception in onConnectionFailure callback");
        (*env)->ExceptionClear(env);
    }

    jni_release_env(attached);
}

/* -------------------------------------------------------------------------- */
/* Callback: onGraphicsUpdate                                                 */
/* -------------------------------------------------------------------------- */
void jni_callback_on_graphics_update(jlong instance, int x, int y, int w, int h) {
    if (!g_jvm || !g_libFreeRdpClass || !g_mid_onGraphicsUpdate) {
        return;
    }

    JNIEnv* env = NULL;
    int attached = 0;
    jint status = (*g_jvm)->GetEnv(g_jvm, (void**)&env, JNI_VERSION_1_6);

    if (status == JNI_EDETACHED || !env) {
        JavaVMAttachArgs args;
        args.version = JNI_VERSION_1_6;
        args.name = "FreeRDP-GraphicsUpdate";
        args.group = NULL;
        if ((*g_jvm)->AttachCurrentThread(g_jvm, &env, &args) == JNI_OK) {
            attached = 1;
        } else {
            LOGE("jni_callback_on_graphics_update: AttachCurrentThread failed");
            return;
        }
    } else if (status != JNI_OK) {
        LOGE("jni_callback_on_graphics_update: GetEnv failed (%d)", status);
        return;
    }

    (*env)->CallStaticVoidMethod(
        env, g_libFreeRdpClass, g_mid_onGraphicsUpdate, instance, (jint)x, (jint)y, (jint)w, (jint)h);

    if ((*env)->ExceptionCheck(env)) {
        LOGE("Exception in onGraphicsUpdate callback");
        (*env)->ExceptionClear(env);
    }

    if (attached) {
        (*g_jvm)->DetachCurrentThread(g_jvm);
    }
}

/* -------------------------------------------------------------------------- */
/* Callback: onAuthenticate                                                   */
/* -------------------------------------------------------------------------- */
void jni_callback_on_authenticate(jlong instance) {
    int attached = 0;
    JNIEnv* env = jni_get_env(&attached);
    if (!env || !g_libFreeRdpClass || !g_mid_onAuthenticate) {
        jni_release_env(attached);
        return;
    }

    (*env)->CallStaticVoidMethod(env, g_libFreeRdpClass, g_mid_onAuthenticate, instance);

    if ((*env)->ExceptionCheck(env)) {
        LOGE("Exception in onAuthenticate callback");
        (*env)->ExceptionClear(env);
    }

    jni_release_env(attached);
}

/* -------------------------------------------------------------------------- */
/* Callback: onCursorMoved                                                    */
/* -------------------------------------------------------------------------- */
void jni_callback_on_cursor_moved(jlong instance, int x, int y) {
    int attached = 0;
    JNIEnv* env = jni_get_env(&attached);
    if (!env || !g_libFreeRdpClass || !g_mid_onCursorMoved) {
        jni_release_env(attached);
        return;
    }

    (*env)->CallStaticVoidMethod(
        env, g_libFreeRdpClass, g_mid_onCursorMoved, instance, (jint)x, (jint)y);

    if ((*env)->ExceptionCheck(env)) {
        LOGE("Exception in onCursorMoved callback");
        (*env)->ExceptionClear(env);
    }

    jni_release_env(attached);
}
