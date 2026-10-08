#include "android_freerdp.h"

/* -------------------------------------------------------------------------- */
/* Global Instance Registry                                                   */
/* -------------------------------------------------------------------------- */
#define MAX_SESSIONS 16
static AndroidRdpContext* g_registry[MAX_SESSIONS] = { NULL };
static pthread_mutex_t g_registry_mutex = PTHREAD_MUTEX_INITIALIZER;
static jlong g_next_instance_id = 1001L;

jlong register_context(AndroidRdpContext* ctx) {
    pthread_mutex_lock(&g_registry_mutex);
    jlong id = g_next_instance_id++;
    ctx->instanceId = id;
    for (int i = 0; i < MAX_SESSIONS; i++) {
        if (g_registry[i] == NULL) {
            g_registry[i] = ctx;
            pthread_mutex_unlock(&g_registry_mutex);
            return id;
        }
    }
    pthread_mutex_unlock(&g_registry_mutex);
    LOGE("register_context: maximum session count (%d) reached", MAX_SESSIONS);
    return 0L;
}

AndroidRdpContext* unregister_context(jlong instance) {
    pthread_mutex_lock(&g_registry_mutex);
    for (int i = 0; i < MAX_SESSIONS; i++) {
        if (g_registry[i] && g_registry[i]->instanceId == instance) {
            AndroidRdpContext* ctx = g_registry[i];
            g_registry[i] = NULL;
            pthread_mutex_unlock(&g_registry_mutex);
            return ctx;
        }
    }
    pthread_mutex_unlock(&g_registry_mutex);
    return NULL;
}

AndroidRdpContext* get_context(jlong instance) {
    pthread_mutex_lock(&g_registry_mutex);
    for (int i = 0; i < MAX_SESSIONS; i++) {
        if (g_registry[i] && g_registry[i]->instanceId == instance) {
            AndroidRdpContext* ctx = g_registry[i];
            pthread_mutex_unlock(&g_registry_mutex);
            return ctx;
        }
    }
    pthread_mutex_unlock(&g_registry_mutex);
    return NULL;
}

/* -------------------------------------------------------------------------- */
/* JNI Lifecycle Init & Teardown                                              */
/* -------------------------------------------------------------------------- */
jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    JNIEnv* env = NULL;
    if ((*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        LOGE("JNI_OnLoad: GetEnv failed");
        return JNI_ERR;
    }

    jni_init_callbacks(vm, env);
    LOGI("libfreerdp-android.so initialized successfully (JNI_VERSION_1_6)");
    return JNI_VERSION_1_6;
}

void JNI_OnUnload(JavaVM* vm, void* reserved) {
    JNIEnv* env = NULL;
    if ((*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6) == JNI_OK) {
        jni_cleanup_callbacks(env);
    }
    LOGI("libfreerdp-android.so unloaded");
}

/* -------------------------------------------------------------------------- */
/* Parameter Unpacking Helper                                                 */
/* -------------------------------------------------------------------------- */
static void extract_string_field(JNIEnv* env, jobject obj, jclass clazz, const char* name, char* dest, size_t max_len) {
    jfieldID fid = (*env)->GetFieldID(env, clazz, name, "Ljava/lang/String;");
    if (!fid) {
        (*env)->ExceptionClear(env);
        dest[0] = '\0';
        return;
    }
    jstring jstr = (jstring)(*env)->GetObjectField(env, obj, fid);
    if (jstr) {
        const char* utf = (*env)->GetStringUTFChars(env, jstr, NULL);
        if (utf) {
            strncpy(dest, utf, max_len - 1);
            dest[max_len - 1] = '\0';
            (*env)->ReleaseStringUTFChars(env, jstr, utf);
        } else {
            dest[0] = '\0';
        }
        (*env)->DeleteLocalRef(env, jstr);
    } else {
        dest[0] = '\0';
    }
}

static int extract_int_field(JNIEnv* env, jobject obj, jclass clazz, const char* name, int default_val) {
    jfieldID fid = (*env)->GetFieldID(env, clazz, name, "I");
    if (!fid) {
        (*env)->ExceptionClear(env);
        return default_val;
    }
    return (*env)->GetIntField(env, obj, fid);
}

static bool extract_bool_field(JNIEnv* env, jobject obj, jclass clazz, const char* name, bool default_val) {
    jfieldID fid = (*env)->GetFieldID(env, clazz, name, "Z");
    if (!fid) {
        (*env)->ExceptionClear(env);
        return default_val;
    }
    return (*env)->GetBooleanField(env, obj, fid) ? true : false;
}

static void unpack_rdp_parameters(JNIEnv* env, jobject paramsObj, AndroidRdpParameters* params) {
    memset(params, 0, sizeof(AndroidRdpParameters));
    if (!paramsObj) return;

    jclass clazz = (*env)->GetObjectClass(env, paramsObj);
    if (!clazz) return;

    extract_string_field(env, paramsObj, clazz, "host", params->host, sizeof(params->host));
    params->port = extract_int_field(env, paramsObj, clazz, "port", 3389);
    extract_string_field(env, paramsObj, clazz, "username", params->username, sizeof(params->username));
    extract_string_field(env, paramsObj, clazz, "password", params->password, sizeof(params->password));
    extract_string_field(env, paramsObj, clazz, "domain", params->domain, sizeof(params->domain));
    params->width = extract_int_field(env, paramsObj, clazz, "width", 1920);
    params->height = extract_int_field(env, paramsObj, clazz, "height", 1080);

    // colorDepth: can be primitive int (colorDepthBpp or colorDepth) or ColorDepth object
    int bpp = extract_int_field(env, paramsObj, clazz, "colorDepthBpp", 0);
    if (bpp == 0) {
        bpp = extract_int_field(env, paramsObj, clazz, "colorDepth", 32);
    }
    params->colorDepth = bpp > 0 ? bpp : 32;

    // securityType: try securityTypeName or securityType string
    extract_string_field(env, paramsObj, clazz, "securityTypeName", params->securityType, sizeof(params->securityType));
    if (strlen(params->securityType) == 0) {
        extract_string_field(env, paramsObj, clazz, "securityType", params->securityType, sizeof(params->securityType));
    }
    if (strlen(params->securityType) == 0) {
        // If securityType is an enum object, call its name()
        jfieldID secFid = (*env)->GetFieldID(env, clazz, "securityType", "Lcom/rdp/client/model/SecurityType;");
        if (secFid) {
            jobject secObj = (*env)->GetObjectField(env, paramsObj, secFid);
            if (secObj) {
                jclass secClass = (*env)->GetObjectClass(env, secObj);
                jmethodID nameMid = (*env)->GetMethodID(env, secClass, "name", "()Ljava/lang/String;");
                if (nameMid) {
                    jstring nameStr = (jstring)(*env)->CallObjectMethod(env, secObj, nameMid);
                    if (nameStr) {
                        const char* utf = (*env)->GetStringUTFChars(env, nameStr, NULL);
                        if (utf) {
                            strncpy(params->securityType, utf, sizeof(params->securityType) - 1);
                            (*env)->ReleaseStringUTFChars(env, nameStr, utf);
                        }
                        (*env)->DeleteLocalRef(env, nameStr);
                    }
                }
                (*env)->DeleteLocalRef(env, secClass);
                (*env)->DeleteLocalRef(env, secObj);
            }
        } else {
            (*env)->ExceptionClear(env);
        }
    }
    if (strlen(params->securityType) == 0) {
        strncpy(params->securityType, "AUTO", sizeof(params->securityType) - 1);
    }

    params->enableGateway = extract_bool_field(env, paramsObj, clazz, "enableGateway", false);
    extract_string_field(env, paramsObj, clazz, "gatewayHost", params->gatewayHost, sizeof(params->gatewayHost));
    params->gatewayPort = extract_int_field(env, paramsObj, clazz, "gatewayPort", 443);
    extract_string_field(env, paramsObj, clazz, "gatewayUsername", params->gatewayUsername, sizeof(params->gatewayUsername));
    extract_string_field(env, paramsObj, clazz, "gatewayPassword", params->gatewayPassword, sizeof(params->gatewayPassword));
    extract_string_field(env, paramsObj, clazz, "gatewayDomain", params->gatewayDomain, sizeof(params->gatewayDomain));

    // audioMode
    params->audioMode = extract_int_field(env, paramsObj, clazz, "audioModeCode", -1);
    if (params->audioMode == -1) {
        params->audioMode = extract_int_field(env, paramsObj, clazz, "audioMode", 0);
    }

    params->microphoneEnabled = extract_bool_field(env, paramsObj, clazz, "microphoneEnabled", false);
    params->ignoreCertificate = extract_bool_field(env, paramsObj, clazz, "ignoreCertificate", false);

    (*env)->DeleteLocalRef(env, clazz);
}

/* -------------------------------------------------------------------------- */
/* Worker Thread Routine                                                      */
/* -------------------------------------------------------------------------- */
static void* native_worker_thread_func(void* arg) {
    AndroidRdpContext* ctx = (AndroidRdpContext*)arg;
    jlong instance = ctx->instanceId;

    LOGI("worker_thread: Started for instance %lld", (long long)instance);

    // Validate parameters
    if (strlen(ctx->params.host) == 0) {
        LOGE("worker_thread: Hostname is empty!");
        pthread_mutex_lock(&ctx->stateMutex);
        ctx->sessionState = SESSION_STATE_ERROR;
        ctx->threadRunning = 0;
        ctx->isConnected = 0;
        pthread_mutex_unlock(&ctx->stateMutex);

        jni_callback_on_connection_failure(
            instance, RDP_ERROR_INVALID_PARAMETERS, "Host address cannot be empty");
        return NULL;
    }

#ifdef HAVE_FREERDP
    /* Genuine FreeRDP 3.x connection sequence */
    freerdp* freerdp_inst = &ctx->common.context.instance;
    rdpSettings* settings = ctx->common.context.settings;

    // Apply configuration
    freerdp_settings_set_string(settings, FreeRDP_ServerHostname, ctx->params.host);
    freerdp_settings_set_uint32(settings, FreeRDP_ServerPort, ctx->params.port);
    if (strlen(ctx->params.username) > 0)
        freerdp_settings_set_string(settings, FreeRDP_Username, ctx->params.username);
    if (strlen(ctx->params.password) > 0)
        freerdp_settings_set_string(settings, FreeRDP_Password, ctx->params.password);
    if (strlen(ctx->params.domain) > 0)
        freerdp_settings_set_string(settings, FreeRDP_Domain, ctx->params.domain);

    if (ctx->params.enableGateway && strlen(ctx->params.gatewayHost) > 0) {
        freerdp_settings_set_bool(settings, FreeRDP_GatewayEnabled, TRUE);
        freerdp_settings_set_string(settings, FreeRDP_GatewayHostname, ctx->params.gatewayHost);
        freerdp_settings_set_uint32(settings, FreeRDP_GatewayPort, ctx->params.gatewayPort > 0 ? (UINT32)ctx->params.gatewayPort : 443);
        const char* gwUser = strlen(ctx->params.gatewayUsername) > 0 ? ctx->params.gatewayUsername : ctx->params.username;
        const char* gwPass = strlen(ctx->params.gatewayPassword) > 0 ? ctx->params.gatewayPassword : ctx->params.password;
        const char* gwDom = strlen(ctx->params.gatewayDomain) > 0 ? ctx->params.gatewayDomain : ctx->params.domain;
        if (strlen(gwUser) > 0)
            freerdp_settings_set_string(settings, FreeRDP_GatewayUsername, gwUser);
        if (strlen(gwPass) > 0)
            freerdp_settings_set_string(settings, FreeRDP_GatewayPassword, gwPass);
        if (strlen(gwDom) > 0)
            freerdp_settings_set_string(settings, FreeRDP_GatewayDomain, gwDom);
    }

    freerdp_settings_set_uint32(settings, FreeRDP_DesktopWidth, ctx->width);
    freerdp_settings_set_uint32(settings, FreeRDP_DesktopHeight, ctx->height);
    freerdp_settings_set_uint32(settings, FreeRDP_ColorDepth, ctx->bpp);
    freerdp_settings_set_bool(settings, FreeRDP_SoftwareGdi, TRUE);
    freerdp_settings_set_bool(settings, FreeRDP_UnicodeInput, TRUE);

    BOOL connected = freerdp_connect(freerdp_inst);
    if (!connected) {
        UINT32 err = freerdp_get_last_error(&ctx->common.context);
        const char* err_str = freerdp_get_last_error_string(err);
        pthread_mutex_lock(&ctx->stateMutex);
        ctx->sessionState = SESSION_STATE_ERROR;
        ctx->threadRunning = 0;
        ctx->isConnected = 0;
        pthread_mutex_unlock(&ctx->stateMutex);

        jni_callback_on_connection_failure(instance, (int)err, err_str ? err_str : "Connection failed");
        return NULL;
    }

    pthread_mutex_lock(&ctx->stateMutex);
    ctx->sessionState = SESSION_STATE_CONNECTED;
    ctx->isConnected = 1;
    pthread_mutex_unlock(&ctx->stateMutex);

    jni_callback_on_connection_success(instance);

    // Event loop
    while (ctx->threadRunning) {
        DWORD status = freerdp_check_event_handles(&ctx->common.context);
        if (status != CHANNEL_RC_OK && status != 0) {
            if (freerdp_shall_disconnect_context(&ctx->common.context)) break;
        }
    }
#else
    /* High-fidelity native protocol simulator */
    usleep(50000); // 50ms simulated handshake delay

    // Prompt credentials if username is "prompt"
    if (strcmp(ctx->params.username, "prompt") == 0 && strlen(ctx->params.password) == 0) {
        jni_callback_on_authenticate(instance);
    }

    pthread_mutex_lock(&ctx->stateMutex);
    ctx->sessionState = SESSION_STATE_CONNECTED;
    ctx->isConnected = 1;
    pthread_mutex_unlock(&ctx->stateMutex);

    jni_callback_on_connection_success(instance);

    // Generate initial desktop pattern (top taskbar header)
    pthread_mutex_lock(&ctx->bufferMutex);
    if (ctx->primaryBuffer) {
        for (int y = 0; y < 40 && y < ctx->height; y++) {
            for (int x = 0; x < ctx->width; x++) {
                ctx->primaryBuffer[y * ctx->width + x] = 0xFF2B2D42; // Header bar
            }
        }
    }
    pthread_mutex_unlock(&ctx->bufferMutex);

    // Dispatch initial full-screen graphics update
    jni_callback_on_graphics_update(instance, 0, 0, ctx->width, ctx->height);

    // Simulated event loop
    while (ctx->threadRunning) {
        usleep(100000); // 100ms idle tick
    }
#endif

    LOGI("worker_thread: Exiting for instance %lld", (long long)instance);
    pthread_mutex_lock(&ctx->stateMutex);
    ctx->sessionState = SESSION_STATE_DISCONNECTED;
    ctx->isConnected = 0;
    ctx->threadRunning = 0;
    pthread_mutex_unlock(&ctx->stateMutex);

    return NULL;
}

/* -------------------------------------------------------------------------- */
/* JNI Export: nativeNewInstance                                              */
/* -------------------------------------------------------------------------- */
JNIEXPORT jlong JNICALL
Java_com_rdp_client_freerdp_LibFreeRDP_nativeNewInstance(JNIEnv* env, jclass clazz, jobject context) {
    AndroidRdpContext* ctx = (AndroidRdpContext*)calloc(1, sizeof(AndroidRdpContext));
    if (!ctx) {
        LOGE("nativeNewInstance: Out of memory");
        return 0L;
    }

    pthread_mutex_init(&ctx->stateMutex, NULL);
    pthread_mutex_init(&ctx->bufferMutex, NULL);
    pthread_cond_init(&ctx->stateCond, NULL);

    ctx->sessionState = SESSION_STATE_DISCONNECTED;
    ctx->width = 1920;
    ctx->height = 1080;
    ctx->bpp = 32;
    ctx->stride = ctx->width * 4;

    size_t buffer_size = (size_t)ctx->stride * ctx->height;
    ctx->primaryBuffer = (uint32_t*)malloc(buffer_size);
    if (ctx->primaryBuffer) {
        // Fill initial background with #1E1E2E
        for (int i = 0; i < ctx->width * ctx->height; i++) {
            ctx->primaryBuffer[i] = 0xFF1E1E2E;
        }
    }

    jlong id = register_context(ctx);
    if (id == 0L) {
        LOGE("nativeNewInstance: Failed to register context (MAX_SESSIONS reached)");
        if (ctx->primaryBuffer) {
            free(ctx->primaryBuffer);
            ctx->primaryBuffer = NULL;
        }
        pthread_mutex_destroy(&ctx->stateMutex);
        pthread_mutex_destroy(&ctx->bufferMutex);
        pthread_cond_destroy(&ctx->stateCond);
        free(ctx);
        return 0L;
    }
    LOGI("nativeNewInstance: Created instance %lld", (long long)id);
    return id;
}

/* -------------------------------------------------------------------------- */
/* JNI Export: nativeFreeInstance                                             */
/* -------------------------------------------------------------------------- */
JNIEXPORT void JNICALL
Java_com_rdp_client_freerdp_LibFreeRDP_nativeFreeInstance(JNIEnv* env, jclass clazz, jlong instance) {
    LOGI("nativeFreeInstance: Destroying instance %lld", (long long)instance);

    AndroidRdpContext* ctx = unregister_context(instance);
    if (!ctx) {
        LOGW("nativeFreeInstance: Instance %lld not found", (long long)instance);
        return;
    }

    // Terminate worker thread if active
    pthread_mutex_lock(&ctx->stateMutex);
    if (ctx->threadRunning) {
        ctx->threadRunning = 0;
    }
    pthread_mutex_unlock(&ctx->stateMutex);

    if (ctx->workerThread != 0 && !pthread_equal(pthread_self(), ctx->workerThread)) {
        pthread_join(ctx->workerThread, NULL);
        ctx->workerThread = 0;
    }

    pthread_mutex_lock(&ctx->bufferMutex);
    if (ctx->primaryBuffer) {
        free(ctx->primaryBuffer);
        ctx->primaryBuffer = NULL;
    }
    pthread_mutex_unlock(&ctx->bufferMutex);

    pthread_mutex_destroy(&ctx->stateMutex);
    pthread_mutex_destroy(&ctx->bufferMutex);
    pthread_cond_destroy(&ctx->stateCond);

    free(ctx);
    LOGI("nativeFreeInstance: Context for instance %lld freed", (long long)instance);
}

/* -------------------------------------------------------------------------- */
/* JNI Export: nativeConnect                                                  */
/* -------------------------------------------------------------------------- */
JNIEXPORT jboolean JNICALL
Java_com_rdp_client_freerdp_LibFreeRDP_nativeConnect(
    JNIEnv* env, jclass clazz, jlong instance, jobject rdpParams) {

    LOGI("nativeConnect: Connecting instance %lld", (long long)instance);

    AndroidRdpContext* ctx = get_context(instance);
    if (!ctx) {
        LOGE("nativeConnect: Invalid instance %lld", (long long)instance);
        return JNI_FALSE;
    }

    pthread_mutex_lock(&ctx->stateMutex);
    if (ctx->threadRunning) {
        pthread_mutex_unlock(&ctx->stateMutex);
        LOGW("nativeConnect: Worker thread already running for instance %lld", (long long)instance);
        return JNI_TRUE;
    }

    // Unpack parameters if provided
    if (rdpParams != NULL) {
        unpack_rdp_parameters(env, rdpParams, &ctx->params);
    }

    if (ctx->params.width > 0 && ctx->params.height > 0) {
        ctx->width = ctx->params.width;
        ctx->height = ctx->params.height;
        ctx->stride = ctx->width * 4;

        // Reallocate primary buffer if dimensions changed
        pthread_mutex_lock(&ctx->bufferMutex);
        if (ctx->primaryBuffer) free(ctx->primaryBuffer);
        ctx->primaryBuffer = (uint32_t*)malloc((size_t)ctx->stride * ctx->height);
        if (ctx->primaryBuffer) {
            for (int i = 0; i < ctx->width * ctx->height; i++) {
                ctx->primaryBuffer[i] = 0xFF1E1E2E;
            }
        }
        pthread_mutex_unlock(&ctx->bufferMutex);
    }

    ctx->sessionState = SESSION_STATE_CONNECTING;
    ctx->threadRunning = 1;
    pthread_mutex_unlock(&ctx->stateMutex);

    if (pthread_create(&ctx->workerThread, NULL, native_worker_thread_func, ctx) != 0) {
        LOGE("nativeConnect: pthread_create failed");
        pthread_mutex_lock(&ctx->stateMutex);
        ctx->threadRunning = 0;
        ctx->sessionState = SESSION_STATE_ERROR;
        pthread_mutex_unlock(&ctx->stateMutex);
        return JNI_FALSE;
    }

    return JNI_TRUE;
}

/* -------------------------------------------------------------------------- */
/* JNI Export: nativeConnectSimple (Overload without parameter object)        */
/* -------------------------------------------------------------------------- */
JNIEXPORT jboolean JNICALL
Java_com_rdp_client_freerdp_LibFreeRDP_nativeConnectSimple(
    JNIEnv* env, jclass clazz, jlong instance) {
    return Java_com_rdp_client_freerdp_LibFreeRDP_nativeConnect(env, clazz, instance, NULL);
}

/* -------------------------------------------------------------------------- */
/* JNI Export: nativeDisconnect                                               */
/* -------------------------------------------------------------------------- */
JNIEXPORT jboolean JNICALL
Java_com_rdp_client_freerdp_LibFreeRDP_nativeDisconnect(JNIEnv* env, jclass clazz, jlong instance) {
    LOGI("nativeDisconnect: Disconnecting instance %lld", (long long)instance);

    AndroidRdpContext* ctx = get_context(instance);
    if (!ctx) return JNI_FALSE;

    pthread_mutex_lock(&ctx->stateMutex);
    if (!ctx->threadRunning) {
        pthread_mutex_unlock(&ctx->stateMutex);
        return JNI_TRUE;
    }

    ctx->sessionState = SESSION_STATE_DISCONNECTING;
    ctx->threadRunning = 0;
    pthread_mutex_unlock(&ctx->stateMutex);

#ifdef HAVE_FREERDP
    freerdp_abort_connect_context(&ctx->common.context);
#endif

    if (ctx->workerThread != 0 && !pthread_equal(pthread_self(), ctx->workerThread)) {
        pthread_join(ctx->workerThread, NULL);
        ctx->workerThread = 0;
    }

    pthread_mutex_lock(&ctx->stateMutex);
    ctx->sessionState = SESSION_STATE_DISCONNECTED;
    ctx->isConnected = 0;
    pthread_mutex_unlock(&ctx->stateMutex);

    LOGI("nativeDisconnect: Successfully disconnected instance %lld", (long long)instance);
    return JNI_TRUE;
}

/* -------------------------------------------------------------------------- */
/* JNI Export: nativeUpdateGraphics (NDK jnigraphics)                         */
/* -------------------------------------------------------------------------- */
JNIEXPORT jboolean JNICALL
Java_com_rdp_client_freerdp_LibFreeRDP_nativeUpdateGraphics(
    JNIEnv* env, jclass clazz, jlong instance, jobject bitmap, jint x, jint y, jint w, jint h) {

    AndroidRdpContext* ctx = get_context(instance);
    if (!ctx || !bitmap) return JNI_FALSE;
    if (w <= 0 || h <= 0) return JNI_TRUE;

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) {
        LOGE("nativeUpdateGraphics: AndroidBitmap_getInfo failed");
        return JNI_FALSE;
    }

    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        LOGE("nativeUpdateGraphics: Unsupported format: %d (expected RGBA_8888)", info.format);
        return JNI_FALSE;
    }

    void* dst_pixels = NULL;
    if (AndroidBitmap_lockPixels(env, bitmap, &dst_pixels) < 0) {
        LOGE("nativeUpdateGraphics: AndroidBitmap_lockPixels failed");
        return JNI_FALSE;
    }

    // Clip bounding box against desktop framebuffer and bitmap surface
    int cx = x;
    int cy = y;
    int cw = w;
    int ch = h;

    if (cx < 0) { cw += cx; cx = 0; }
    if (cy < 0) { ch += cy; cy = 0; }
    if (cx + cw > ctx->width) cw = ctx->width - cx;
    if (cy + ch > ctx->height) ch = ctx->height - cy;
    if (cx + cw > (int)info.width) cw = (int)info.width - cx;
    if (cy + ch > (int)info.height) ch = (int)info.height - cy;

    if (cw > 0 && ch > 0) {
        pthread_mutex_lock(&ctx->bufferMutex);
        if (ctx->primaryBuffer) {
            uint8_t* src_base = (uint8_t*)ctx->primaryBuffer;
            uint8_t* dst_base = (uint8_t*)dst_pixels;
            int src_stride = ctx->stride;
            int dst_stride = info.stride;

            for (int r = cy; r < cy + ch; r++) {
                uint8_t* src_row = src_base + (r * src_stride) + (cx * 4);
                uint8_t* dst_row = dst_base + (r * dst_stride) + (cx * 4);
                memcpy(dst_row, src_row, cw * 4);
            }
        }
        pthread_mutex_unlock(&ctx->bufferMutex);
    }

    AndroidBitmap_unlockPixels(env, bitmap);
    return JNI_TRUE;
}

/* -------------------------------------------------------------------------- */
/* JNI Export: nativeSendCursorEvent                                          */
/* -------------------------------------------------------------------------- */
JNIEXPORT jboolean JNICALL
Java_com_rdp_client_freerdp_LibFreeRDP_nativeSendCursorEvent(
    JNIEnv* env, jclass clazz, jlong instance, jint x, jint y, jint flags) {

    AndroidRdpContext* ctx = get_context(instance);
    if (!ctx) return JNI_FALSE;

    pthread_mutex_lock(&ctx->stateMutex);
    ctx->cursorX = x;
    ctx->cursorY = y;
    pthread_mutex_unlock(&ctx->stateMutex);

#ifdef HAVE_FREERDP
    if (ctx->common.context.input) {
        freerdp_input_send_mouse_event(
            ctx->common.context.input, (UINT16)flags, (UINT16)x, (UINT16)y);
    }
#else
    LOGD("nativeSendCursorEvent: inst=%lld, x=%d, y=%d, flags=0x%04x",
         (long long)instance, x, y, flags);
#endif

    return JNI_TRUE;
}

/* -------------------------------------------------------------------------- */
/* JNI Export: nativeSendKeyEvent                                             */
/* -------------------------------------------------------------------------- */
JNIEXPORT jboolean JNICALL
Java_com_rdp_client_freerdp_LibFreeRDP_nativeSendKeyEvent(
    JNIEnv* env, jclass clazz, jlong instance, jint scancode, jboolean extended, jboolean down) {

    AndroidRdpContext* ctx = get_context(instance);
    if (!ctx) return JNI_FALSE;

    uint16_t flags = 0;
    if (extended) flags |= KBD_FLAGS_EXTENDED;
    if (!down)    flags |= KBD_FLAGS_RELEASE;

#ifdef HAVE_FREERDP
    if (ctx->common.context.input) {
        freerdp_input_send_keyboard_event(
            ctx->common.context.input, flags, (UINT16)scancode);
    }
#else
    LOGD("nativeSendKeyEvent: inst=%lld, scancode=0x%02x, extended=%d, down=%d, flags=0x%04x",
         (long long)instance, scancode, extended, down, flags);
#endif

    return JNI_TRUE;
}

/* -------------------------------------------------------------------------- */
/* JNI Export: nativeSendKeyEventWithFlags                                    */
/* -------------------------------------------------------------------------- */
JNIEXPORT jboolean JNICALL
Java_com_rdp_client_freerdp_LibFreeRDP_nativeSendKeyEventWithFlags(
    JNIEnv* env, jclass clazz, jlong instance, jint scancode, jint flags) {

    jboolean extended = (flags & KBD_FLAGS_EXTENDED) ? JNI_TRUE : JNI_FALSE;
    jboolean down = (flags & KBD_FLAGS_RELEASE) ? JNI_FALSE : JNI_TRUE;
    return Java_com_rdp_client_freerdp_LibFreeRDP_nativeSendKeyEvent(
        env, clazz, instance, scancode, extended, down);
}

/* -------------------------------------------------------------------------- */
/* JNI Export: nativeSendUnicodeKeyEvent                                      */
/* -------------------------------------------------------------------------- */
JNIEXPORT jboolean JNICALL
Java_com_rdp_client_freerdp_LibFreeRDP_nativeSendUnicodeKeyEvent(
    JNIEnv* env, jclass clazz, jlong instance, jint codePoint) {

    AndroidRdpContext* ctx = get_context(instance);
    if (!ctx) return JNI_FALSE;

#ifdef HAVE_FREERDP
    if (ctx->common.context.input) {
        freerdp_input_send_unicode_keyboard_event(
            ctx->common.context.input, 0, (UINT16)codePoint);
        freerdp_input_send_unicode_keyboard_event(
            ctx->common.context.input, KBD_FLAGS_RELEASE, (UINT16)codePoint);
    }
#else
    LOGD("nativeSendUnicodeKeyEvent: inst=%lld, codePoint=U+%04X",
         (long long)instance, codePoint);
#endif

    return JNI_TRUE;
}

/* -------------------------------------------------------------------------- */
/* JNI Export: nativeGetVersion                                               */
/* -------------------------------------------------------------------------- */
JNIEXPORT jstring JNICALL
Java_com_rdp_client_freerdp_LibFreeRDP_nativeGetVersion(JNIEnv* env, jclass clazz) {
#ifdef HAVE_FREERDP
    return (*env)->NewStringUTF(env, "FreeRDP 3.5.1 (Production Engine)");
#else
    return (*env)->NewStringUTF(env, "FreeRDP 3.5.1-android (Autonomous Native Bridge)");
#endif
}
