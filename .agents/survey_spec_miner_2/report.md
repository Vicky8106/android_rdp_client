# Technical Specification & Feature Mining Report: R1 (FreeRDP Core Engine) & R2 (Floating Mouse & Touch Gestures)

- **Author**: `survey_spec_miner_2` (RDP Engine & Gestures Spec Miner)
- **Target Project**: `android_rdp_client`
- **Scope**: Requirement 1 (R1: Core Remote Desktop Foundation) & Requirement 2 (R2: Mobile-First Floating Mouse & Touch System)
- **Status**: Completed & Validated
- **Date**: 2026-09-22

---

## 1. Executive Summary & Authoritative Spec Sources

This specification document provides an exhaustive, production-grade technical blueprint for implementing **R1 (FreeRDP Core Engine & Native Isolation)** and **R2 (Floating Mouse Overlay & Gesture Subsystem)** in the Android RDP Client.

### Authoritative Specification Sources
1. **MS-RDPBCGR**: Remote Desktop Protocol: Basic Connectivity and Graphics Remoting (Slow-Path & Fast-Path Pointer/Input Events, Connection Sequence, Capabilities Exchange).
2. **MS-RDPEDISP**: Remote Desktop Protocol: Display Update Virtual Channel Extension (Dynamic Monitor Layout, Orientation & Dynamic Resolution Resizing).
3. **MS-RDPECLIP**: Remote Desktop Protocol: Clipboard Virtual Channel Extension (Format List, Data Request/Response, Clipboard Capabilities).
4. **MS-CSSP**: Credential Security Support Provider Protocol (Network Level Authentication / NLA).
5. **FreeRDP Core / aFreeRDP Native Implementation**:
   - `include/freerdp/input.h` (`PTR_FLAGS_*`, mouse event flags and serialization).
   - `client/Android/Studio/freeRDPCore/src/main/java/com/freerdp/freerdpcore/services/LibFreeRDP.java` (JNI binding contracts).
   - `include/freerdp/error.h` & `freerdp/freerdp.h` (`freerdp_get_last_error*` diagnostic codes).
   - `channels/disp.h` & `channels/cliprdr.h`.
6. **Android Framework Specifications**:
   - `android.view.MotionEvent` (Multi-touch pointer IDs, historical batching, action masks).
   - `android.graphics.Matrix` & `android.graphics.RectF` (Affine 2D coordinate transformation & inversion).
   - `androidx.core.view.WindowInsetsCompat` (Safe inset boundary calculation for display cutouts, navigation, and status bars).
   - `android.content.ClipboardManager` (Primary clip listening, MIME text/plain extraction).

---

## 2. Features Discovered

| # | Category | Feature | Description | Inputs | Outputs | Error Behavior | Discovered Via |
|---|----------|---------|-------------|--------|---------|----------------|----------------|
| 1 | R1: Engine Isolation | `IRdpEngine` Interface | Pure Kotlin interface abstracting native FreeRDP calls, allowing headless mock testing on JVM/Robolectric | Connect config, pointer coords, scan codes | Session state emissions, render frames | Throws `RdpBridgeException` on invalid state | Architecture Design / FreeRDP JNI |
| 2 | R1: Engine Lifecycle | Native Context Allocation & Teardown | Safe lifecycle management of native `long inst` pointer via `freerdp_new` and `freerdp_free` with AtomicLong tracking | Android Context, instance pointer | Native memory allocated / freed | Double-free prevention; ignores `0` handle | FreeRDP `LibFreeRDP.java` |
| 3 | R1: Threading | Background Dispatch Isolation | Execution of blocking FreeRDP connect loop, frame decoding, and channel I/O on dedicated single-thread dispatchers | Coroutine dispatcher / HandlerThread | Non-blocking UI; thread-confined native calls | Marshals exceptions to Main Looper | Android Concurrency Best Practices |
| 4 | R1: Security / NLA | CredSSP (NLA) Handshake | Negotiates TLS tunnel and performs NTLMv2/Kerberos authentication before RDP session negotiation | Host, port, username, password, domain | Authenticated RDP channel | Emits `FREERDP_ERROR_AUTHENTICATION_FAILED` (0x00020009) | MS-CSSP / MS-RDPBCGR §5.1 |
| 5 | R1: Security / TLS | Certificate Fingerprint Validation | Validates server X.509 certificate against stored trusted SHA-256 fingerprint; prompts on mismatch | Certificate byte array, host name, flags | Boolean accept/reject decision | Rejects connection if untrusted | FreeRDP `VERIFY_CERT_FLAG_*` |
| 6 | R1: Dynamic Resize | MS-RDPEDISP Monitor Layout | Sends `DISPLAY_CONTROL_MONITOR_LAYOUT` PDU when Android orientation switches or window resizes | Width, height, physical size (mm), orientation | Server resizes desktop without reconnect | Falls back to smart-sizing viewport if unsupported | MS-RDPEDISP §2.2.2.2 |
| 7 | R1: Dynamic Resize | Debounced Layout Dispatch | Debounces rapid layout events (e.g. during split-screen drag or rotation animation) before notifying server | Window change events, 250ms debounce | Single layout PDU to RDP channel | Discards intermediate size changes | Performance & Stability Engineering |
| 8 | R1: Clipboard Sync | Local-to-Remote Clipboard | Listens to Android primary clip changes and advertises `CF_UNICODETEXT` (UTF-16LE) to server | Android `ClipData` (text/plain) | `CLIPRDR_FORMAT_LIST` PDU | Truncates or skips unsupported MIME payloads | MS-RDPECLIP §3.1.5.2 |
| 9 | R1: Clipboard Sync | Remote-to-Local Clipboard | Handles server `CLIPRDR_FORMAT_LIST`, requests `CF_UNICODETEXT`, and populates Android `ClipboardManager` | Server format list, UTF-16LE data | Android primary clip set | Logs error on UTF-16 decode failure | MS-RDPECLIP §3.1.5.3 |
| 10 | R1: Clipboard Sync | Clipboard Echo Suppression | Compares SHA-256 hash or string equality of synchronized clip to suppress infinite local-remote echo loops | Clipboard content string / hash | Suppress redundant broadcast if source matches | None (silent drop of duplicate) | MS-RDPECLIP Implementation Pattern |
| 11 | R1: Diagnostics | Diagnostic Error Code Translation | Maps native FreeRDP 32-bit error codes (`freerdp_get_last_error`) to typed Kotlin sealed domain errors | Native `UINT32` error code | `RdpSessionError` sealed class instance | Maps unknown codes to `GenericProtocolError` | FreeRDP `error.h` |
| 12 | R2: Overlay UI | Repositionable Floating Bubble | Floating circular bubble (48dp) movable anywhere along screen margins with drag gestures | MotionEvent (Action Move) | Overlay view position update | Clamps within safe system insets | Android WindowManager / UI spec |
| 13 | R2: Overlay UI | Edge-Snapping Behavior | Snaps collapsed overlay bubble smoothly to nearest left or right edge when released | Release coordinates (x, y) | Animated spring to `insets.left` or `screenWidth - w` | Clamps vertically to avoid status/nav bars | Android Gesture UX Patterns |
| 14 | R2: Overlay UI | Collapsible / Expandable Palette | Toggles between compact 48dp bubble and expanded quick-action button palette | Tap on collapsed bubble / Close button | Visibility state transition (Collapsed <-> Expanded) | Auto-collapses on tap outside | ORIGINAL_REQUEST.md §R2 |
| 15 | R2: Overlay UI | Normalized Coordinate Persistence | Persists overlay position as normalized floats `[0.0, 1.0]` across orientation changes and app restarts | Screen coordinates, display bounds | `SharedPreferences` normalized coordinates | Resets to default `(0.9, 0.5)` if out of bounds | Responsive UI Design |
| 16 | R2: Overlay Actions | Dedicated Left/Right Click Buttons | On-screen palette buttons for Left Click (LMB) and Right Click (RMB) at current cursor position | Button tap event | FreeRDP Down + Up pointer event sequence | Ignored if session disconnected | ORIGINAL_REQUEST.md §R2 |
| 17 | R2: Overlay Actions | Click-and-Drag Lock Toggle | Toggles sticky Left Mouse Button Down state for dragging files, windows, or selecting text | Button toggle event | `PTR_FLAGS_BUTTON1 \| PTR_FLAGS_DOWN` kept active | Automatically releases LMB upon session disconnect | ORIGINAL_REQUEST.md §R2 |
| 18 | R2: Overlay Actions | Wheel Scroll Rocker | Up/Down scroll buttons sending discrete RDP wheel rotation units (120 units / `0x0078`) | Scroll button click | `PTR_FLAGS_WHEEL` / `PTR_FLAGS_WHEEL_NEGATIVE` | Clamps delta to protocol limits | MS-RDPBCGR §2.2.8.1.1.3.1.1 |
| 19 | R2: Input Modes | Direct Touch Mode | Direct 1:1 mapping: tap on screen maps directly to remote desktop coordinates at touch point | Single touch coordinates | Cursor positioned and clicked at touch point | Clamped to `[0, remoteWidth-1]`, `[0, remoteHeight-1]` | RDP Client Input Model |
| 20 | R2: Input Modes | Touchpad Mode (Relative Cursor) | Entire screen acts as laptop trackpad: swiping moves a virtual cursor relatively; tapping clicks | Relative delta `(dx, dy)` | `PTR_FLAGS_MOVE` with updated `(cx, cy)` | Clamped to virtual desktop boundaries | ORIGINAL_REQUEST.md §R2 |
| 21 | R2: Input Modes | Optional Virtual Cursor | Toggles rendering of visual pointer crosshair/arrow on the client surface in Direct or Touchpad modes | Preference toggle | Cursor overlay visibility toggled | Hidden when external mouse is connected | ORIGINAL_REQUEST.md §R2 |
| 22 | R2: Gestures | Pan-Without-Clicking | Dragging with 1 finger (in Pan mode) or 2 fingers pans remote desktop viewport without sending click | Touch move delta exceeding touch slop | Viewport translation matrix `(transX, transY)` | No pointer down/up sent to RDP server | ORIGINAL_REQUEST.md §R2 |
| 23 | R2: Gestures | Pinch-to-Zoom Engine | 2-finger pinch smoothly scales remote desktop viewport between 1.0x (Fit) and 4.0x zoom | Multi-touch ScaleGestureDetector scaleFactor | Viewport scale and focus point update | Bounded to `[minScale, maxScale]` | ORIGINAL_REQUEST.md §R2 |
| 24 | R2: Gestures | Spurious Click Suppression | Multi-touch latch prevents accidental tap/click when fingers lift sequentially from a pinch/pan gesture | MotionEvent pointer transitions | Suppresses `ACTION_UP` click emission | Guarantees zero phantom clicks on zoom release | UX Hardening / aFreeRDP Bug Analysis |
| 25 | R2: Gestures | Long-Press to Right Click | Holding finger stationary for 500ms triggers haptic vibration and sends Right Click at release | 500ms stationary hold | Haptic feedback + RMB Down + Up | Aborted if movement exceeds touch slop | ORIGINAL_REQUEST.md §R2 |
| 26 | R2: Gestures | Double-Tap to Double-Click | Rapid two taps within 300ms and 24dp radius emit standard Windows double-click sequence | Sequential tap events | 2x (LMB Down + LMB Up) with 50ms interval | Emits single click if second tap is too slow | Windows Desktop Ergonomics |
| 27 | R2: Coordinate Math | Affine Matrix Viewport Mapping | Transforms 2D Android touch coordinates to remote RDP pixel coordinates using inverse matrix | Screen `(x_screen, y_screen)` | Desktop `(x_rdp, y_rdp)` integers | Clamped to remote desktop canvas rect | Android Matrix Mathematics |

---

## 3. Edge Cases & Boundary Behaviors

| # | Feature | Input / Trigger Condition | Observed / Required Behavior |
|---|---------|---------------------------|-------------------------------|
| 1 | FreeRDP Binding | JNI call executed while `inst == 0` (uninitialized or destroyed) | Instantly throw `IllegalStateException` in Kotlin layer; NEVER pass 0 to native code to avoid JVM crash / SIGSEGV |
| 2 | FreeRDP Binding | Native connection thread crashes or terminates unexpectedly | Background monitor thread detects EOF on socket, triggers `onDisconnected` with error code, frees native resources |
| 3 | Dynamic Resizing | Android screen rotates from Portrait (1080x2400) to Landscape (2400x1080) while connected | Send `DISPLAY_CONTROL_MONITOR_LAYOUT` with new width/height; reallocate backing Bitmap; adjust viewport matrix |
| 4 | Dynamic Resizing | RDP server does not support MS-RDPEDISP (e.g. Windows Server 2008 / older xrdp) | Detect absence of DisplayControl caps; gracefully fallback to local client viewport scaling (Smart-Sizing) |
| 5 | Dynamic Resizing | Window resized to odd or zero dimensions (e.g. 1079 x 2399 in multi-window) | Align dimensions to multiples of 4: `w = w & ~3`, `h = h & ~3`; enforce minimum resolution of 640x480 |
| 6 | NLA / Security | Server presents self-signed or changed TLS certificate | Intercept `onCertificateVerify`; check against encrypted keystore fingerprints; prompt user if changed; abort if rejected |
| 7 | NLA / Security | Server requires NLA but user provided invalid credentials | Catch `FREERDP_ERROR_AUTHENTICATION_FAILED` (0x00020009); transition state machine to `AuthFailed`; prompt user |
| 8 | Clipboard Sync | User copies 50MB of binary text or unsupported rich format | Filter to `text/plain`; enforce max text payload cap (e.g. 1MB); prevent native buffer overflow or OOM |
| 9 | Clipboard Sync | Rapid alternating copy on Android and Windows host | Echo suppression algorithm matches SHA-256 hash; prevents ping-pong loop that floods RDP virtual channel |
| 10 | Floating Overlay | Overlay dragged towards screen notch or navigation bar | Clamp coordinates using `WindowInsetsCompat.getInsets()`; overlay edge cannot penetrate unsafe cutout zones |
| 11 | Floating Overlay | Phone rotated 90 degrees while overlay is at custom position | Recompute pixel position using normalized coordinates `(normX, normY)`; snap to valid screen boundary in new orientation |
| 12 | Floating Overlay | App killed by OS and relaunched in different orientation | Load `(normX, normY)` from SharedPreferences; clamp to current display geometry; never spawn off-screen |
| 13 | Touch Gestures | User performs pinch-to-zoom; finger 1 lifts at t=0, finger 2 lifts at t=25ms | `multiTouchEngaged` latch blocks click on finger 2 `ACTION_UP`; zero spurious clicks emitted on zoom finish |
| 14 | Touch Gestures | User pans remote desktop across screen, dragging finger across icons | Movement exceeds `touchSlop` (8dp); cancels all click/long-press timers; viewport pans smoothly without click |
| 15 | Touch Gestures | Incoming phone call or system notification shades pulled down mid-gesture (`ACTION_CANCEL`) | Cancel all timers; unconditionally release any held mouse button (`PTR_FLAGS_BUTTON1`) to prevent stuck keys |
| 16 | Touch Gestures | Long-press held for 450ms, then finger moves 20dp | Cancel long-press timer; convert gesture into viewport Pan or cursor move; no right click emitted |
| 17 | Touchpad Mode | Cursor moved to extreme desktop boundary (e.g. (1919, 1079)) and swiped further | Clamping function locks cursor at `remoteWidth - 1` and `remoteHeight - 1`; delta discarded; no integer overflow |
| 18 | Touchpad Mode | Drag Lock enabled on floating overlay, then user switches input mode to Direct Touch | Automatically send LMB Up (`PTR_FLAGS_BUTTON1`) to release remote mouse down before mode transition |
| 19 | Coordinate Math | Viewport zoomed to 400%; user clicks on pixel near bottom-right edge | Matrix inverse precisely translates zoomed coordinate to exact RDP pixel `(x, y)` without floating-point drift |
| 20 | Coordinate Math | Viewport zoomed out smaller than screen (e.g. phone in landscape with letterboxing) | Matrix centers desktop; clicks on black letterbox bars are discarded or clamped to nearest valid desktop border |

---

## 4. Requirement 1 (R1): Core Remote Desktop Foundation Specification

### 4.1 Native Interface Isolation Architecture

To ensure strict modularity, testability, and future-proof FreeRDP upgrades, native C/JNI dependencies must NEVER be exposed directly to UI components, ViewModels, or session controllers.

```
+-------------------------------------------------------------------------+
|                              Android UI Layer                           |
|             (SessionActivity, RemoteSurfaceView, OverlayView)           |
+-------------------------------------------------------------------------+
                                    |
                                    v
+-------------------------------------------------------------------------+
|                         Domain / Session Layer                          |
|                       (RdpSessionController)                            |
+-------------------------------------------------------------------------+
                                    |
                                    v
+-------------------------------------------------------------------------+
|                  Native Interface Isolation Boundary                    |
|                                                                         |
|  interface IRdpEngine {                                                 |
|      fun initialize(context: Context): Long                             |
|      fun connect(inst: Long, config: RdpConnectionConfig): Boolean      |
|      fun disconnect(inst: Long): Boolean                                |
|      fun sendCursorEvent(inst: Long, x: Int, y: Int, flags: Int): Bool  |
|      fun sendKeyEvent(inst: Long, scanCode: Int, down: Boolean): Bool   |
|      fun sendUnicodeKeyEvent(inst: Long, codePoint: Int): Boolean       |
|      fun sendDisplayUpdate(inst: Long, w: Int, h: Int, dpi: Int): Bool  |
|      fun sendClipboardData(inst: Long, formatId: Int, data: ByteArray)  |
|      fun destroy(inst: Long)                                            |
|      fun setEventListener(listener: RdpEventListener?)                  |
|  }                                                                      |
+-------------------------------------------------------------------------+
            |                                               |
            v                                               v
+-------------------------------+             +---------------------------+
|    NativeFreeRdpEngine        |             |      MockRdpEngine        |
|  (Production - Loads .so JNI) |             |  (JVM & Robolectric Tests)|
+-------------------------------+             +---------------------------+
            |
            v
+-------------------------------+
|  libfreerdp-android.so (JNI)  |
|  libfreerdp-client.so         |
|  libfreerdp.so / libwinpr.so  |
+-------------------------------+
```

#### Key Architecture Principles:
1. **Zero Native Leakage**: All interactions go through `IRdpEngine`. The engine returns standard Kotlin data models and callbacks.
2. **Headless JVM Testability**: The automated test suite (`./gradlew testDebugUnitTest`) can instantiate `MockRdpEngine` to test the entire session state machine, gesture engine, coordinate math, and reconnect logic without requiring native `.so` binaries.
3. **Pointer Safety Contract**:
   - `inst` is managed as an `AtomicLong` inside `RdpSessionBridge`.
   - Every call verifies `inst != 0L`.
   - Thread safety: Calls into `IRdpEngine` are dispatched on a single dedicated background thread (`Dispatchers.IO` or `HandlerThread("RdpDispatchThread")`).

---

### 4.2 FreeRDP JNI Method Bindings & Lifecycle Contracts

The native JNI bridge maps to FreeRDP C functions:

```kotlin
package com.freerdp.freerdpcore.services

interface LibFreeRDPBinding {
    fun freerdpNew(context: Context): Long
    fun freerdpFree(inst: Long)
    fun freerdpConnect(inst: Long): Boolean
    fun freerdpDisconnect(inst: Long): Boolean
    fun freerdpParseArguments(inst: Long, args: Array<String>): Boolean
    fun freerdpUpdateGraphics(inst: Long, bitmap: Bitmap, x: Int, y: Int, w: Int, h: Int): Boolean
    fun freerdpSendCursorEvent(inst: Long, x: Int, y: Int, flags: Int): Boolean
    fun freerdpSendKeyEvent(inst: Long, keycode: Int, down: Boolean): Boolean
    fun freerdpSendUnicodeKeyEvent(inst: Long, codePoint: Int): Boolean
    fun freerdpSendClipboardData(inst: Long, formatId: Int, data: ByteArray): Boolean
    fun freerdpSendDisplayUpdate(inst: Long, width: Int, height: Int, dpi: Int): Boolean
    fun freerdpGetLastError(inst: Long): Long
    fun freerdpGetLastErrorString(code: Long): String
}
```

#### Event Callback Interface (`RdpEventListener`):
```kotlin
interface RdpEventListener {
    fun onConnected(inst: Long)
    fun onDisconnected(inst: Long, reasonCode: Int, reasonMessage: String)
    fun onConnectionFailed(inst: Long, errorCode: Int, errorMessage: String)
    fun onGraphicsUpdate(inst: Long, x: Int, y: Int, width: Int, height: Int)
    fun onDesktopResize(inst: Long, width: Int, height: Int)
    fun onClipboardDataReceived(inst: Long, formatId: Int, data: ByteArray)
    fun onCertificateVerify(inst: Long, host: String, fingerprint: String, flags: Long): Boolean
    fun onAuthenticate(inst: Long): RdpCredentials?
}
```

---

### 4.3 NLA (CredSSP) & TLS Handshake Specification

The connection sequence follows **[MS-CSSP]** and **[MS-RDPBCGR]**:

```
Client (Android)                                      Server (Windows RDP)
      |                                                        |
      |----------------- TCP SYN / ACK Connect --------------->|
      |                                                        |
      |---------- RDP Connection Request (PDU_TYPE_DEMAND) ---->|
      |           (Flags: PROTOCOL_RDP | PROTOCOL_SSL | NLA)   |
      |                                                        |
      |<--------- RDP Connection Confirm (PDU_TYPE_CONFIRM) ---|
      |           (Selected: PROTOCOL_HYBRID / NLA)            |
      |                                                        |
      |<================ TLS Handshake =======================>|
      |           (Client verifies Server X.509 Certificate)   |
      |                                                        |
      |<================ CredSSP / NTLMv2 Tunnel =============>|
      |  1. TSRequest (NegTokenInit)                           |
      |  2. TSRequest (NegTokenResp: NTLM Challenge)           |
      |  3. TSRequest (Authenticate: NTLM Response + PubKey)   |
      |                                                        |
      |--- MCS Connect Initial PDU (Client Core/Security Caps)->|
      |<-- MCS Connect Response PDU (Server Capabilities) -----|
      |                                                        |
      |<========= Security Exchange & Licensing Negotiation ==>|
      |                                                        |
      |--- Capabilities Exchange PDU (Input, Font, General) -->|
      |<-- Capabilities Exchange PDU (Bitmap, Sound, Order) ---|
      |                                                        |
      |<================ Display & Input Ready ================>|
```

#### Security Policy Configuration Parameters:
- `NlaSecurity: Boolean = true` (Default: ON. If server demands NLA, client supplies credentials via CredSSP).
- `TlsSecurity: Boolean = true` (Enforces TLS 1.2+ encryption).
- `RdpSecurity: Boolean = false` (Legacy standard RDP encryption disabled for security).
- `IgnoreCertificate: Boolean = false` (Validates certificate SHA-256 fingerprint against Keystore).

---

### 4.4 Dynamic Resolution Resizing (MS-RDPEDISP)

Dynamic resolution adjustment enables seamless orientation changes (portrait <-> landscape) and Android multi-window resizing without dropping or resetting the RDP session.

#### Protocol Sequence:
1. **Channel Setup**: During connection initialization, client requests dynamic virtual channel:
   `Microsoft::Windows::RDS::DisplayControl`
2. **Server Capabilities Announcement**: Server sends `DISPLAY_CONTROL_CAPS_PDU`:
   - `MaxNumMonitors: UINT32`
   - `MaxMonitorAreaFactor: UINT32`
3. **Android Layout Change Event**:
   - `View.onSizeChanged(w, h, oldw, oldh)` or `Activity.onConfigurationChanged(newConfig)`.
   - **Debounce Guard**: Initiate a 250ms countdown timer. If another resize event occurs during the countdown, reset timer.
4. **Client Layout Update Dispatch**:
   - Client sends `DISPLAY_CONTROL_MONITOR_LAYOUT_PDU`:
   ```c
   struct DISPLAY_CONTROL_MONITOR_LAYOUT {
       UINT32 Flags = 0x00000001; // DISPLAY_CONTROL_MONITOR_PRIMARY
       INT32  Left = 0;
       INT32  Top = 0;
       UINT32 Width = targetWidth;   // Must be aligned: width & ~3
       UINT32 Height = targetHeight; // Must be aligned: height & ~3
       UINT32 PhysicalWidth = (UINT32)((targetWidth / xdpi) * 25.4f);
       UINT32 PhysicalHeight = (UINT32)((targetHeight / ydpi) * 25.4f);
       UINT32 Orientation = isLandscape ? 0 : 1;
       UINT32 DesktopScaleFactor = (UINT32)(displayDensity * 100);
       UINT32 DeviceScaleFactor = 100;
   };
   ```
5. **Server Desktop Reconfiguration**: Server sends `onDesktopResize(newWidth, newHeight)`.
6. **Client Surface Reallocation**:
   - Native client recreates internal pixel buffer.
   - UI layer creates new backing `Bitmap` and re-initializes viewport `Matrix`.

---

### 4.5 Clipboard Synchronization (MS-RDPECLIP)

The static virtual channel `cliprdr` manages bidirectional text and data synchronization between Android's `ClipboardManager` and the Windows RDP host.

#### Supported Clipboard Format IDs:
- `CF_TEXT = 1` (ANSI text)
- `CF_UNICODETEXT = 13` (UTF-16LE text with 2-byte null terminator)
- `CF_DIB = 8` (Device Independent Bitmap)

#### Echo Suppression & Loop Prevention Engine:
To prevent endless synchronization loops (Android -> Windows -> Android -> Windows):
```kotlin
class ClipboardSyncManager(
    private val rdpEngine: IRdpEngine,
    private val androidClipboard: ClipboardManager
) {
    private var lastSyncedHash: Int = 0
    private var lastSource: ClipboardSource = ClipboardSource.NONE

    enum class ClipboardSource { NONE, LOCAL_ANDROID, REMOTE_RDP }

    fun onLocalClipboardChanged(text: String) {
        val contentHash = text.hashCode()
        if (contentHash == lastSyncedHash && lastSource == ClipboardSource.REMOTE_RDP) {
            return // Echo detected from our own remote paste; suppress
        }
        lastSyncedHash = contentHash
        lastSource = ClipboardSource.LOCAL_ANDROID

        // Announce format to RDP server
        rdpEngine.sendClipboardFormatList(listOf(CF_UNICODETEXT))
    }

    fun onRemoteFormatDataReceived(formatId: Int, rawBytes: ByteArray) {
        if (formatId == CF_UNICODETEXT) {
            val text = String(rawBytes, Charsets.UTF_16LE).trimEnd('\u0000')
            val contentHash = text.hashCode()
            if (contentHash == lastSyncedHash && lastSource == ClipboardSource.LOCAL_ANDROID) {
                return // Echo detected from our own local copy; suppress
            }
            lastSyncedHash = contentHash
            lastSource = ClipboardSource.REMOTE_RDP

            // Update Android Clipboard
            androidClipboard.setPrimaryClip(ClipData.newPlainText("RDP Sync", text))
        }
    }
}
```

---

## 5. Requirement 2 (R2): Mobile-First Floating Mouse & Gesture System

### 5.1 Floating Mouse Overlay State Machine

The floating overlay provides on-screen mouse operations without obstructing the remote desktop.

```
       +---------------------------------------------+
       |                                             |
       v                                             |
+---------------+   Tap Bubble   +---------------+   | Tap Close
|   COLLAPSED   | -------------> |   EXPANDED    | --+
| (48dp Bubble) | <------------- | (Full Palette)|
+---------------+  Tap Outside   +---------------+
       |                                 |
       | Touch Down on Handle            |
       v                                 |
+---------------------+                  |
|  DRAGGING_OVERLAY   |                  |
+---------------------+                  |
       |                                 |
       | Action Up                       |
       v                                 |
+---------------------+                  |
|   SNAPPING_EDGE     | -----------------+
|  (Snap to Margin)   |
+---------------------+
```

#### Overlay UI Components:
1. **Collapsed Mode**: 48dp circular floating bubble, semi-transparent (`alpha = 0.75`). Parked against screen edge.
2. **Expanded Mode**: Compact toolbar / dock containing:
   - `[LMB]` Left Click button (tap to click; visual down/up feedback)
   - `[RMB]` Right Click button (tap to right-click)
   - `[DRAG]` Drag Lock toggle button (toggles sticky LMB Down state)
   - `[SCROLL]` Vertical Wheel Scroll Rocker (Up / Down)
   - `[MODE]` Mode Switcher (Direct Touch vs Touchpad Relative Pointer)
   - `[CURSOR]` Toggle virtual cursor visibility
   - `[COLLAPSE]` Minimize back to bubble

---

### 5.2 Boundary Clamping & Normalized Coordinate Persistence

#### Safe Area & Inset Calculation:
Let screen bounds be `(W, H)` and safe insets from `WindowInsetsCompat` be `(I_left, I_top, I_right, I_bottom)`.
The overlay's allowed top-left coordinate `(ox, oy)` for dimensions `(ow, oh)`:
$$ox_{min} = I_{left}$$
$$ox_{max} = W - ow - I_{right}$$
$$oy_{min} = I_{top}$$
$$oy_{max} = H - oh - I_{bottom}$$

$$ox_{clamped} = \min(\max(ox, ox_{min}), ox_{max})$$
$$oy_{clamped} = \min(\max(oy, oy_{min}), oy_{max})$$

#### Edge-Snapping Behavior:
When the collapsed bubble is released at `(ox, oy)`:
$$snapX = \begin{cases} ox_{min}, & \text{if } ox + \frac{ow}{2} < \frac{W}{2} \\ ox_{max}, & \text{otherwise} \end{cases}$$
Smooth spring animation interpolates `ox` to `snapX`.

#### Orientation Persistence (Normalized Coordinates):
To maintain relative screen positioning when phone rotates between Portrait and Landscape:
$$normX = \frac{ox_{clamped} - ox_{min}}{\max(1, ox_{max} - ox_{min})}$$
$$normY = \frac{oy_{clamped} - oy_{min}}{\max(1, oy_{max} - oy_{min})}$$
On orientation change to new bounds `(W', H')` and new insets `(I_{left}', I_{top}', I_{right}', I_{bottom}')`:
$$ox' = I_{left}' + normX \times (W' - ow - I_{right}' - I_{left}')$$
$$oy' = I_{top}' + normY \times (H' - oh - I_{bottom}' - I_{top}')$$
Values `(normX, normY)` are persisted to `SharedPreferences` to preserve position across app restarts.

---

### 5.3 Input Modes: Direct Touch vs Touchpad (Trackpad) Mode

| Feature | Direct Touch Mode | Touchpad (Trackpad) Mode |
|---------|-------------------|--------------------------|
| **Primary Interaction** | Tapping screen maps 1:1 to remote coordinate | Screen behaves like a laptop trackpad; virtual pointer moves |
| **Pointer Movement** | No movement events emitted until touch down | Relative delta $(\Delta x \cdot s, \Delta y \cdot s)$ moves virtual cursor $(cx, cy)$ |
| **Left Click** | Single tap at tap point $(x_{rdp}, y_{rdp})$ | 1-finger tap anywhere on touchpad clicks at $(cx, cy)$ |
| **Right Click** | Long press (500ms) at touch point | 2-finger tap anywhere on touchpad clicks at $(cx, cy)$ |
| **Double Click** | Double tap at touch point | Rapid two 1-finger taps on touchpad |
| **Scroll / Wheel** | 2-finger vertical swipe | 2-finger vertical swipe |
| **Drag & Select** | Drag Lock button active OR tap-hold-drag | Drag Lock button active OR double-tap-and-drag |
| **Cursor Visibility**| Optional (hidden by default) | Mandatory (shows cursor crosshair / arrow at $(cx, cy)$) |

---

### 5.4 Anti-Spurious Click Gesture State Machine

The core failure in legacy mobile RDP clients (such as aFreeRDP) is **phantom/spurious clicks** when releasing fingers from a pinch-to-zoom or pan gesture. This is solved by the **Multi-Touch Latch Pattern**:

```
+--------------------------------------------------------------------------+
|                             GESTURE STATE MACHINE                        |
+--------------------------------------------------------------------------+
                                    |
                             [ACTION_DOWN]
                                    v
                         +---------------------+
                         | TOUCH_DOWN_PENDING  |
                         | (Start 500ms Timer) |
                         +---------------------+
                        /           |           \
           dist > slop /            | 500ms      \ [ACTION_POINTER_DOWN]
                      v             | elapsed     v
           +--------------+         v         +------------------+
           |   PANNING    |    +------------+ |  PINCH_ZOOMING   |
           |  (Move View) |    | LONG_PRESS | |  (multiTouch=1)  |
           +--------------+    +------------+ +------------------+
                  |                  |                  |
            [ACTION_UP]         [ACTION_UP]        [ACTION_POINTER_UP]
                  v                  v                  v
           +--------------+    +------------+ +------------------+
           | NO CLICK     |    | RIGHT CLICK| |  WAIT FOR LAST   |
           | (Pan Ended)  |    | AT (x, y)  | |  (multiTouch=1)  |
           +--------------+    +------------+ +------------------+
                                                        |
                                                   [ACTION_UP]
                                                        v
                                              +------------------+
                                              | SUPPRESS CLICK!  |
                                              | multiTouch = 0   |
                                              +------------------+
```

#### Anti-Spurious Rules:
1. **Multi-Touch Latch**: If `event.pointerCount > 1` at ANY instant during a touch sequence, set `multiTouchLatch = true`.
2. **Latch Hold Across Pointer Lift**: When one finger lifts (`ACTION_POINTER_UP`), keep `multiTouchLatch = true`.
3. **Suppression on Terminal Lift**: When the final finger lifts (`ACTION_UP`), if `multiTouchLatch == true`, discard the event completely and emit NO mouse clicks. Reset `multiTouchLatch = false`.
4. **Touch Slop Hysteresis**: If total displacement $\sqrt{\Delta x^2 + \Delta y^2} > \text{touchSlop}$ (8dp), cancel tap and long-press timers immediately.
5. **Cancellation Safety**: On `ACTION_CANCEL`, immediately release any active mouse down state (`PTR_FLAGS_BUTTON1`) to avoid stuck button state on the remote host.

---

### 5.5 Viewport Coordinate Transformation & Boundary Mathematics

The viewport handles rendering the remote desktop `(remoteWidth, remoteHeight)` inside Android `(viewWidth, viewHeight)` with zoom and pan.

#### Transformation Matrix:
The affine transform matrix $M$ maps RDP desktop coordinates to Android screen pixels:
$$\begin{bmatrix} x_{screen} \\ y_{screen} \\ 1 \end{bmatrix} = \begin{bmatrix} scale & 0 & transX \\ 0 & scale & transY \\ 0 & 0 & 1 \end{bmatrix} \begin{bmatrix} x_{rdp} \\ y_{rdp} \\ 1 \end{bmatrix}$$

#### Inverse Transformation (Screen Touch to RDP):
To calculate the exact remote RDP pixel clicked:
$$x_{rdp} = \frac{x_{screen} - transX}{scale}$$
$$y_{rdp} = \frac{y_{screen} - transY}{scale}$$

#### Canvas Clamping:
$$x_{clamped} = \min(\max(0, \lfloor x_{rdp} \rfloor), remoteWidth - 1)$$
$$y_{clamped} = \min(\max(0, \lfloor y_{rdp} \rfloor), remoteHeight - 1)$$

#### Zooming & Focal Point Invariance:
When scaling by factor $k = \frac{newScale}{currentScale}$ around touch focal point $(f_x, f_y)$:
$$transX' = f_x - (f_x - transX) \times k$$
$$transY' = f_y - (f_y - transY) \times k$$

#### Viewport Clamping (Prevent Panning into Void Space):
Let $contentW = remoteWidth \times scale$ and $contentH = remoteHeight \times scale$:
- If $contentW \le viewWidth$:
  $$transX = \frac{viewWidth - contentW}{2} \quad (\text{Center horizontally})$$
- If $contentW > viewWidth$:
  $$transX = \min(0, \max(viewWidth - contentW, transX))$$
- If $contentH \le viewHeight$:
  $$transY = \frac{viewHeight - contentH}{2} \quad (\text{Center vertically})$$
- If $contentH > viewHeight$:
  $$transY = \min(0, \max(viewHeight - contentH, transY))$$

---

### 5.6 RDP Mouse Event Construction & Fast-Path Protocol Encoding

Pointer events sent to FreeRDP use the standard MS-RDPBCGR bitmask flags:

```kotlin
object RdpPointerFlags {
    const val PTR_FLAGS_HWHEEL          = 0x0400 // Horizontal scroll
    const val PTR_FLAGS_WHEEL           = 0x0200 // Vertical scroll
    const val PTR_FLAGS_WHEEL_NEGATIVE  = 0x0100 // Negative wheel rotation direction
    const val PTR_FLAGS_MOVE            = 0x0800 // Pointer movement
    const val PTR_FLAGS_DOWN            = 0x8000 // Button pressed down (if omitted: button released)
    const val PTR_FLAGS_BUTTON1         = 0x1000 // Left mouse button
    const val PTR_FLAGS_BUTTON2         = 0x2000 // Right mouse button
    const val PTR_FLAGS_BUTTON3         = 0x4000 // Middle mouse button
    const val WHEEL_ROTATION_MASK       = 0x01FF // Mask for wheel rotation delta
    const val WHEEL_STEP_DEFAULT        = 0x0078 // Standard Windows wheel delta: 120 units
}
```

#### Event Generation Sequences:

1. **Mouse Move**:
   `freerdp_send_cursor_event(inst, x, y, PTR_FLAGS_MOVE)`
2. **Left Click**:
   `freerdp_send_cursor_event(inst, x, y, PTR_FLAGS_BUTTON1 or PTR_FLAGS_DOWN)`
   `freerdp_send_cursor_event(inst, x, y, PTR_FLAGS_BUTTON1)`
3. **Right Click**:
   `freerdp_send_cursor_event(inst, x, y, PTR_FLAGS_BUTTON2 or PTR_FLAGS_DOWN)`
   `freerdp_send_cursor_event(inst, x, y, PTR_FLAGS_BUTTON2)`
4. **Double Click**:
   `freerdp_send_cursor_event(inst, x, y, PTR_FLAGS_BUTTON1 or PTR_FLAGS_DOWN)`
   `freerdp_send_cursor_event(inst, x, y, PTR_FLAGS_BUTTON1)`
   *Delay: 50ms*
   `freerdp_send_cursor_event(inst, x, y, PTR_FLAGS_BUTTON1 or PTR_FLAGS_DOWN)`
   `freerdp_send_cursor_event(inst, x, y, PTR_FLAGS_BUTTON1)`
5. **Click-and-Drag**:
   - Touch down: `freerdp_send_cursor_event(inst, x0, y0, PTR_FLAGS_BUTTON1 or PTR_FLAGS_DOWN)`
   - Move: `freerdp_send_cursor_event(inst, xi, yi, PTR_FLAGS_MOVE or PTR_FLAGS_BUTTON1 or PTR_FLAGS_DOWN)`
   - Touch up: `freerdp_send_cursor_event(inst, xn, yn, PTR_FLAGS_BUTTON1)`
6. **Vertical Scroll Up**:
   `freerdp_send_cursor_event(inst, x, y, PTR_FLAGS_WHEEL or WHEEL_STEP_DEFAULT)` (0x0278)
7. **Vertical Scroll Down**:
   `freerdp_send_cursor_event(inst, x, y, PTR_FLAGS_WHEEL or PTR_FLAGS_WHEEL_NEGATIVE or WHEEL_STEP_DEFAULT)` (0x0378)
8. **Horizontal Scroll Left**:
   `freerdp_send_cursor_event(inst, x, y, PTR_FLAGS_HWHEEL or PTR_FLAGS_WHEEL_NEGATIVE or WHEEL_STEP_DEFAULT)` (0x0578)
9. **Horizontal Scroll Right**:
   `freerdp_send_cursor_event(inst, x, y, PTR_FLAGS_HWHEEL or WHEEL_STEP_DEFAULT)` (0x0478)

---

## 6. Acceptance Criteria Verification Matrix & Test Strategy

| Requirement | Acceptance Criteria | Automated Test Scenario | Oracle / Verification Method |
|-------------|---------------------|--------------------------|------------------------------|
| **R1.1** | Native Interface Isolation | `RdpSessionControllerTest` runs with `MockRdpEngine` | Verify zero native library dependencies required on host JVM |
| **R1.2** | Dynamic Resolution Resizing | Simulate orientation flip from 1080x2400 to 2400x1080 | Verify `DISPLAY_CONTROL_MONITOR_LAYOUT` PDU emitted with debouncing |
| **R1.3** | NLA & TLS Handshake | Simulate server cert mismatch and valid cert | Verify callback triggers fingerprint prompt on mismatch and connects on match |
| **R1.4** | Clipboard Sync & Echo Suppression | Simulate Android clip change followed by server format request | Verify `CF_UNICODETEXT` response; verify suppression on echo |
| **R2.1** | Floating Overlay Reposition & Clamping | Drag overlay to coordinates `(-50, -50)` and `(5000, 5000)` | Verify coordinates clamped strictly to `[I_left, W - ow - I_right]` |
| **R2.2** | Overlay Orientation Persistence | Rotate screen from Portrait to Landscape | Verify normalized `(normX, normY)` accurately maps to new landscape bounds |
| **R2.3** | Spurious Click Suppression | Inject 2-finger multi-touch sequence; release sequentially | Assert `onLeftClick` event count is strictly `0` |
| **R2.4** | Pan-Without-Clicking | Inject 1-finger move exceeding 8dp touch slop | Assert viewport `transX/transY` shifts; zero `PTR_FLAGS_BUTTON1` emitted |
| **R2.5** | Touchpad Mode Pointer Control | Inject relative swiping motion in touchpad mode | Verify `PTR_FLAGS_MOVE` emitted with clamped remote `(cx, cy)` coordinates |
| **R2.6** | Coordinate Math Inversion | Test coordinate mapping under 1.5x zoom and (100, 200) pan | Verify screen touch (250, 500) maps to exact RDP pixel (100, 200) |
| **R2.7** | Mouse Flag Bitmask Accuracy | Inspect generated flags for Scroll Down | Assert value equals `0x0378` (`PTR_FLAGS_WHEEL \| PTR_FLAGS_WHEEL_NEGATIVE \| 120`) |

---

## 7. Next Steps for Implementation Team

1. **Milestone 1 (FreeRDP Native & Engine Isolation)**:
   - Implement `IRdpEngine` and `MockRdpEngine` in `core:rdp-engine` module.
   - Implement `RdpSessionBridge` with thread isolation and `AtomicLong` pointer protection.
   - Implement `ClipboardSyncManager` and `DisplayUpdateHandler`.
2. **Milestone 2 (Floating Mouse Overlay & Gesture Engine)**:
   - Implement `FloatingMouseOverlayView` with boundary clamping, snap-to-edge animation, and normalized persistence.
   - Implement `RdpGestureDetector` with the multi-touch latch pattern and touch slop hysteresis.
   - Implement `ViewportMatrixController` for affine transformation and clamping.
   - Build unit test suite validating all items in the Acceptance Criteria Verification Matrix.
