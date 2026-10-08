#ifndef ANDROID_FREERDP_H
#define ANDROID_FREERDP_H

#include <jni.h>
#include <android/log.h>
#include <android/bitmap.h>
#include <pthread.h>
#include <stdint.h>
#include <stdbool.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#ifdef __cplusplus
extern "C" {
#endif

/* -------------------------------------------------------------------------- */
/* Logging Macros                                                             */
/* -------------------------------------------------------------------------- */
#define LOG_TAG "FreeRDP-Native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

/* -------------------------------------------------------------------------- */
/* Protocol Error Codes                                                       */
/* -------------------------------------------------------------------------- */
#define RDP_ERROR_NONE                     0
#define RDP_ERROR_INVALID_PARAMETERS       1001
#define RDP_ERROR_SOCKET_CONNECT_FAILED    1002
#define RDP_ERROR_TLS_HANDSHAKE_FAILED     1003
#define RDP_ERROR_AUTHENTICATION_FAILED    1004
#define RDP_ERROR_SERVER_DISCONNECT        1005
#define RDP_ERROR_PROTOCOL_NEGOTIATION     1006
#define RDP_ERROR_INTERNAL                 1007

/* -------------------------------------------------------------------------- */
/* Session State Machine Constants                                            */
/* -------------------------------------------------------------------------- */
typedef enum {
    SESSION_STATE_DISCONNECTED = 0,
    SESSION_STATE_CONNECTING    = 1,
    SESSION_STATE_CONNECTED     = 2,
    SESSION_STATE_RECONNECTING  = 3,
    SESSION_STATE_DISCONNECTING = 4,
    SESSION_STATE_ERROR         = 5
} SessionState;

/* -------------------------------------------------------------------------- */
/* MS-RDPBCGR Pointer Event Flags                                             */
/* -------------------------------------------------------------------------- */
#define PTRFLAGS_HWHEEL          0x0400
#define PTRFLAGS_WHEEL           0x0200
#define PTRFLAGS_WHEEL_NEGATIVE  0x0100
#define PTRFLAGS_MOVE            0x0800
#define PTRFLAGS_DOWN            0x8000
#define PTRFLAGS_BUTTON1         0x1000
#define PTRFLAGS_BUTTON2         0x2000
#define PTRFLAGS_BUTTON3         0x4000

/* -------------------------------------------------------------------------- */
/* IBM PC AT 8042 Keyboard Flags                                              */
/* -------------------------------------------------------------------------- */
#define KBD_FLAGS_EXTENDED       0x0100
#define KBD_FLAGS_EXTENDED1      0x0200
#define KBD_FLAGS_DOWN           0x0000
#define KBD_FLAGS_RELEASE        0x8000

/* -------------------------------------------------------------------------- */
/* Forward Declarations of FreeRDP Context                                    */
/* -------------------------------------------------------------------------- */
#ifdef HAVE_FREERDP
#include <freerdp/freerdp.h>
#include <freerdp/client.h>
#include <freerdp/gdi/gdi.h>
#include <freerdp/input.h>
#endif

/* -------------------------------------------------------------------------- */
/* Connection Parameters Model (Unpacked from RdpConnectionParameters)        */
/* -------------------------------------------------------------------------- */
typedef struct {
    char host[256];
    int port;
    char username[128];
    char password[128];
    char domain[128];
    int width;
    int height;
    int colorDepth;
    char securityType[32]; // "AUTO", "NLA", "TLS", "RDP"
    bool enableGateway;
    char gatewayHost[256];
    int gatewayPort;
    char gatewayUsername[128];
    char gatewayPassword[128];
    char gatewayDomain[128];
    int audioMode; // 0 = LOCAL, 1 = REMOTE, 2 = MUTE
    bool microphoneEnabled;
    bool ignoreCertificate;
} AndroidRdpParameters;

/* -------------------------------------------------------------------------- */
/* Session Context Structure                                                  */
/* -------------------------------------------------------------------------- */
typedef struct tagAndroidRdpContext {
#ifdef HAVE_FREERDP
    rdpClientContext common; // Must be first field when extending rdpClientContext
#endif

    jlong instanceId;

    // Concurrency & Threading
    pthread_t workerThread;
    int threadRunning;
    int isConnected;
    SessionState sessionState;

    pthread_mutex_t stateMutex;
    pthread_mutex_t bufferMutex;
    pthread_cond_t stateCond;

    // Desktop Framebuffer
    uint32_t* primaryBuffer;
    int width;
    int height;
    int bpp;
    int stride; // bytes per row (width * 4)

    // Remote Cursor Tracking
    int cursorX;
    int cursorY;

    // Connection parameters cache
    AndroidRdpParameters params;

} AndroidRdpContext;

/* -------------------------------------------------------------------------- */
/* Reverse JNI Callback Signatures (android_jni_callback.c)                   */
/* -------------------------------------------------------------------------- */
void jni_init_callbacks(JavaVM* vm, JNIEnv* env);
void jni_cleanup_callbacks(JNIEnv* env);
JNIEnv* jni_get_env(int* attached);
void jni_release_env(int attached);

void jni_callback_on_connection_success(jlong instance);
void jni_callback_on_connection_failure(jlong instance, int errorCode, const char* message);
void jni_callback_on_graphics_update(jlong instance, int x, int y, int w, int h);
void jni_callback_on_authenticate(jlong instance);
void jni_callback_on_cursor_moved(jlong instance, int x, int y);

/* -------------------------------------------------------------------------- */
/* Context Registry Functions                                                 */
/* -------------------------------------------------------------------------- */
jlong register_context(AndroidRdpContext* ctx);
AndroidRdpContext* unregister_context(jlong instance);
AndroidRdpContext* get_context(jlong instance);

#ifdef __cplusplus
}
#endif

#endif // ANDROID_FREERDP_H
