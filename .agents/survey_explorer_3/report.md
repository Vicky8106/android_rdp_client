# Architectural Blueprint, Security Specification & Comprehensive Testing Strategy
**Project:** Mobile-First Android RDP Client  
**Author:** survey_explorer_3 (Architecture & Testing Strategist)  
**Date:** 2026-09-22  
**Working Directory:** `C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\survey_explorer_3`  
**Reference Document:** `C:\Users\Administrator\teamwork_projects\android_rdp_client\ORIGINAL_REQUEST.md`  

---

## 1. Executive Summary & Core Architectural Tenets

This report delivers the foundational architecture, security specifications, performance pipelines, and test engineering framework for a production-grade, low-latency, mobile-first Android RDP client powered by FreeRDP.

### 1.1 System Vision
The client enables low-friction, one-handed remote administration and desktop access on mobile phones, foldables, and tablets. It bridges the fundamental ergonomic gap between desktop mouse/keyboard expectations and mobile capacitive touch via an adaptive floating mouse, customizable mobile modifier keys, and an intelligent gesture engine, while maintaining ultra-low input-to-render latency and hardware-backed cryptographic credential protection.

### 1.2 Core Architectural Tenets
1. **Complete Engine Decoupling (JNI Isolation):** Native FreeRDP C/C++ libraries and JNI bindings are strictly encapsulated behind a pure Kotlin domain interface (`IRdpEngine` / `RdpSession`). No UI component, ViewModel, or business logic interacts with JNI handles or C pointers. This enables seamless native upgrades, robust test double mocking, and complete crash isolation.
2. **Zero-Jank Multi-Thread Pipeline:** Native socket I/O, packet parsing, frame decompression, and surface rendering run on dedicated, non-blocking OS threads. The Android UI thread is never blocked by network packets, cryptographic handshakes, or frame decoding.
3. **Defense-in-Depth Security:** Sensitive server credentials, user passwords, and private tokens are encrypted using AES-256-GCM backed by the Android KeyStore and Jetpack Security (`EncryptedSharedPreferences`). Plaintext secrets exist in memory only as transient `CharArray` structures wiped immediately after native dispatch.
4. **Deterministic Finite State Machines (FSM):** Critical lifecycle behaviors—including session connection/reconnection, modifier key latching, and floating overlay docking—are modeled as formal state machines with exhaustively enumerated transitions, deterministic error states, and no unhandled edge conditions.
5. **100% Deterministic Testing Architecture:** Every layer of the system—from Keystore encryption and gesture disambiguation to network loss recovery—is verifiable via fast, deterministic JVM and Robolectric tests (`./gradlew testDebugUnitTest`) without requiring live external RDP servers or physical hardware devices.

---

## 2. Requirement R3 Deep Dive: Mobile Productivity & Session Management

### 2.1 Material Design 3 (M3) UI Architecture
The user interface follows Material Design 3 guidelines, leveraging modern Android UI components, dynamic theming (`MaterialTheme.colorScheme`), and adaptive layouts optimized for both compact portrait phones and expanded landscape/tablet displays.

#### 2.1.1 Color Theming & Elevation
- **Dynamic Color:** Automatically adapts to Android 12+ system wallpaper palettes using `dynamicDarkColorScheme()` and `dynamicLightColorScheme()`, with fallback to a high-contrast slate-blue enterprise dark palette (`#0F172A` background, `#38BDF8` primary accent, `#1E293B` surface).
- **Surface Elevation:** Utilizes tonal elevation instead of artificial drop shadows, ensuring UI overlays remain legible over high-contrast remote desktop sessions.

#### 2.1.2 Screen Hierarchy & Layouts
1. **Profile Dashboard Screen (`ProfileListScreen`):**
   - **Header:** Material 3 `TopAppBar` with search/filter, quick-connect button, and settings menu.
   - **List/Grid:** `LazyColumn` / `RecyclerView` displaying connection cards with host status indicator (recent, ping latency, resolution tag).
   - **Actions:** Extended Floating Action Button (FAB) for "+ New Server", Swipe-to-delete with undo Snackbar, and Long-press contextual action bar (Duplicate, Export, Pin).
2. **Profile Editor Screen (`ProfileEditorScreen`):**
   - Organized into clear M3 expandable cards or tabs:
     - *General:* Profile label, Server Hostname/IP, Port (default 3389).
     - *Credentials:* Username, Domain, Password Storage Toggle (Save securely / Prompt every time / None).
     - *Display & Canvas:* Resolution Preset (Native match, 1080p, 720p, Custom), Color Depth (16-bit, 24-bit, 32-bit), Viewport Scaling (Fit, 1:1, Stretch).
     - *Performance Preset:* Ultra-Low Latency, Balanced, Data Saver.
     - *Redirection:* Local sound redirection, microphone, clipboard sync.
     - *Advanced RDP:* NLA/CredSSP toggle, TLS version enforcement, Server Certificate validation policy (Strict / Warn / Ignore).
   - Real-time field validation with inline error messaging (RFC-compliant hostname checking, port range 1..65535).
3. **Active Session Viewport (`RdpSessionScreen`):**
   - Edge-to-edge `SurfaceView` managing hardware-accelerated remote frame blitting.
   - Layered overlay hierarchy:
     - Layer 0: Remote Desktop Surface (`SurfaceView`).
     - Layer 1: Gesture Detection Shield (intercepts pan/zoom vs mouse interactions).
     - Layer 2: Floating Mouse Overlay (repositionable, draggable pill with L/R buttons).
     - Layer 3: Collapsible Quick-Action Toolbar (docked pill at screen edge).
     - Layer 4: On-Screen Mobile Modifier Bar (docked above soft keyboard or bottom edge).
     - Layer 5: Real-time Telemetry HUD (translucent diagnostics chip).

---

### 2.2 Connection Profile Manager (CRUD) Architecture

#### 2.2.1 Data Entity Specification
The connection profile is defined as an immutable data class:

```kotlin
data class RdpProfile(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val hostname: String,
    val port: Int = 3389,
    val username: String = "",
    val domain: String = "",
    val credentialStorageType: CredentialStorageType = CredentialStorageType.KEYSTORE_ENCRYPTED,
    val displayConfig: DisplayConfig = DisplayConfig.DEFAULT,
    val performancePreset: PerformancePreset = PerformancePreset.BALANCED_MOBILE,
    val redirectionConfig: RedirectionConfig = RedirectionConfig.DEFAULT,
    val networkConfig: NetworkConfig = NetworkConfig.DEFAULT,
    val securityConfig: SecurityConfig = SecurityConfig.DEFAULT,
    val lastConnectedTimestamp: Long? = null,
    val isPinned: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

enum class CredentialStorageType {
    NONE,
    KEYSTORE_ENCRYPTED,
    PROMPT_ON_CONNECT
}

data class DisplayConfig(
    val resolutionMode: ResolutionMode = ResolutionMode.NATIVE_DISPLAY,
    val customWidth: Int = 1920,
    val customHeight: Int = 1080,
    val colorDepth: ColorDepth = ColorDepth.DEPTH_32,
    val scalingMode: ScalingMode = ScalingMode.FIT_SCREEN
)

enum class ResolutionMode { NATIVE_DISPLAY, FIXED_1080P, FIXED_720P, CUSTOM }
enum class ColorDepth(val bpp: Int) { DEPTH_16(16), DEPTH_24(24), DEPTH_32(32) }
enum class ScalingMode { FIT_SCREEN, ONE_TO_ONE, STRETCH }

data class RedirectionConfig(
    val soundEnabled: Boolean = false,
    val microphoneEnabled: Boolean = false,
    val clipboardEnabled: Boolean = true
)

data class NetworkConfig(
    val autoReconnect: Boolean = true,
    val maxReconnectAttempts: Int = 5,
    val connectionTimeoutMs: Long = 10000L
)

data class SecurityConfig(
    val nlaEnabled: Boolean = true,
    val tlsEnabled: Boolean = true,
    val certValidationMode: CertValidationMode = CertValidationMode.WARN_ON_MISMATCH
)

enum class CertValidationMode { STRICT, WARN_ON_MISMATCH, ACCEPT_ALL }
```

#### 2.2.2 Storage Layer: Profile Repository
To ensure robust, zero-dependency data management that works reliably without heavy SQLite native driver issues across platforms, the storage architecture supports both an atomic JSON-backed repository (`AtomicFileProfileRepository`) and Room database:
- **Atomic File Transactions:** Profiles are serialized to a structured JSON database (`profiles.json`) using atomic temporary file creation followed by atomic filesystem rename (`File.renameTo` / `AtomicMoveNotSupportedException` fallback). This guarantees that power interruptions or sudden app kills never corrupt the profile registry.
- **Repository Interface:**
```kotlin
interface ProfileRepository {
    fun getAllProfiles(): Flow<List<RdpProfile>>
    suspend fun getProfile(id: String): RdpProfile?
    suspend fun saveProfile(profile: RdpProfile): Result<Unit>
    suspend fun deleteProfile(id: String): Result<Unit>
    suspend fun duplicateProfile(id: String): Result<RdpProfile>
}
```
- **Separation of Secrets:** Under NO circumstances are passwords or private authentication tokens stored inside `RdpProfile` or written to `profiles.json`. Only the profile's UUID reference is shared; the secret itself resides exclusively in the cryptographic vault.

---

### 2.3 Android Keystore & EncryptedSharedPreferences Security Engine

#### 2.3.1 Security Threat Model
RDP client credentials (passwords, domain tokens, NLA certificates) grant administrative remote access to corporate infrastructure. The client must defend against:
1. **Unencrypted File Extraction:** Rooted devices or ADB backup extraction must yield only AES-256 encrypted ciphertext with no usable keys.
2. **Key Exfiltration:** Cryptographic keys must be bound to the hardware TEE (Trusted Execution Environment) or StrongBox Keymaster via `AndroidKeyStore`.
3. **Process Memory Scraping:** Plaintext secrets must not persist in the JVM String pool or garbage collector heap.

#### 2.3.2 Cryptographic Implementation: Jetpack Security
The application uses Jetpack Security (`androidx.security:security-crypto:1.1.0-alpha06`) backed by the Android Keystore system:

```kotlin
class KeystoreCredentialStore(
    private val context: Context
) : CredentialStore {

    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(context, "_rdp_master_key_")
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .setRequestStrongBoxStorage(false) // graceful fallback to TEE
            .build()
    }

    private val encryptedPrefs: SharedPreferences by lazy {
        EncryptedSharedPreferences.create(
            context,
            "secure_rdp_vault",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    override suspend fun saveCredential(profileId: String, secret: CharArray): Result<Unit> = runCatching {
        val encodedSecret = Base64.encodeToString(
            secret.map { it.code.toByte() }.toByteArray(),
            Base64.NO_WRAP
        )
        encryptedPrefs.edit().putString(vaultKey(profileId), encodedSecret).commit()
    }

    override suspend fun getCredential(profileId: String): Result<CharArray?> = runCatching {
        val encoded = encryptedPrefs.getString(vaultKey(profileId), null) ?: return@runCatching null
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val chars = CharArray(bytes.size) { i -> bytes[i].toInt().toChar() }
        chars
    }

    override suspend fun deleteCredential(profileId: String): Result<Unit> = runCatching {
        encryptedPrefs.edit().remove(vaultKey(profileId)).commit()
    }

    override suspend fun clearAll(): Result<Unit> = runCatching {
        encryptedPrefs.edit().clear().commit()
    }

    private fun vaultKey(profileId: String): String = "secret_$profileId"
}
```

#### 2.3.3 Secure Memory Hygiene & Backup Rules
- **Memory Zeroing:** Methods handling credentials accept and return `CharArray`. Once the password is passed across the JNI bridge to FreeRDP, the array is immediately wiped: `Arrays.fill(secret, '\u0000')`.
- **Backup Exclusion:** `AndroidManifest.xml` explicitly disallows automated cloud backup for credential storage:
  ```xml
  <application
      android:allowBackup="false"
      android:dataExtractionRules="@xml/data_extraction_rules"
      android:fullBackupContent="@xml/backup_rules">
  ```
  Where `data_extraction_rules.xml` excludes `sharedpref/secure_rdp_vault.xml`.

---

### 2.4 Collapsible Quick-Action Toolbar

The quick-action toolbar provides rapid access to essential session controls without permanently obscuring the remote display.

#### 2.4.1 Finite State Machine & Docking Behavior
- **States:**
  - `COLLAPSED`: Rendered as an unobtrusive, semi-transparent 40x24dp pill anchored to the top screen edge or side margin.
  - `EXPANDED`: Full horizontal action bar presenting core controls.
  - `DRAGGING`: User is repositioning the toolbar anchor point along the screen perimeter.
- **Auto-Collapse Inactivity Timer:** When expanded, a coroutine timer starts a 4-second countdown. Any touch on the toolbar or remote display resets the timer. If 4 seconds elapse with no interaction, it smoothly transitions to `COLLAPSED`.

#### 2.4.2 Actions & Controls
1. **Keyboard Toggle:** Requests Android IME (Soft Keyboard) with custom `InputConnection` intercepting keys.
2. **Mouse Mode Switch:** Cycles between `TOUCHPAD_MODE`, `DIRECT_TOUCH`, and `FLOATING_POINTER`.
3. **Display Fit / Zoom Reset:** Instantly toggles between 1:1 pixel rendering and Fit-to-Screen view.
4. **Telemetry HUD Toggle:** Shows/hides the real-time diagnostic telemetry HUD overlay.
5. **Modifier Bar Toggle:** Shows/hides the on-screen mobile modifier keys bar.
6. **Disconnect Session:** Prompts graceful disconnect confirmation or triggers immediate clean teardown.

---

### 2.5 Mobile Modifier Keys & On-Screen Helper Bar

Mobile keyboards lack desktop modifier keys (`Ctrl`, `Alt`, `Win`, `Esc`, `Function Keys`). The mobile modifier helper bar provides these keys in an accessible, touch-friendly strip.

#### 2.5.1 Keys Supported
- **Modifiers:** `Ctrl`, `Alt`, `Shift`, `Win` (Super/Command), `Fn`
- **Navigation & Editing:** `Esc`, `Tab`, `Del`, `Ins`, `Home`, `End`, `PgUp`, `PgDn`
- **Function Keys:** `F1` through `F12` (accessed via expandable sub-panel or scrollable strip)
- **Arrow Navigation:** `Left`, `Up`, `Right`, `Down`, `Enter`

#### 2.5.2 Latching State Machine (Three-State Toggle)
Each modifier key operates on a 3-state latching model:
```
           Tap (from Inactive)
INACTIVE -----------------------> LATCHED
   ^                                 |
   |                                 | Tap (from Latched)
   |                                 v
   | Tap (from Locked)            LOCKED
   +---------------------------------+
```
- **State 0: INACTIVE:** Modifier is not pressed.
- **State 1: LATCHED (Single Tap):** Modifier is active for the *immediate next* non-modifier key press, after which it automatically unlatches and returns to INACTIVE. (Visual indicator: Amber highlight).
- **State 2: LOCKED (Double Tap / Subsequent Tap):** Modifier remains locked down indefinitely across multiple subsequent key presses until explicitly tapped again to release. (Visual indicator: Solid Blue highlight).

#### 2.5.3 Quick Macro Actions
Dedicated shortcuts generate atomic scancode sequences:
- **Ctrl + Alt + Del:** Emits: Down(Ctrl) -> Down(Alt) -> Down(Del) -> Up(Del) -> Up(Alt) -> Up(Ctrl).
- **Alt + Tab:** Emits: Down(Alt) -> Down(Tab) -> Up(Tab) -> Up(Alt).
- **Win + D (Show Desktop):** Emits: Down(Win) -> Down('D') -> Up('D') -> Up(Win).
- **Win + R (Run Dialog):** Emits: Down(Win) -> Down('R') -> Up('R') -> Up(Win).

#### 2.5.4 Windows Scancode Mapping Table
FreeRDP requires standard Windows Scan Codes (Set 1) with extended bit flags:

| Key | Scancode | Extended Flag (`KBD_EXTENDED`) | Description |
| :--- | :---: | :---: | :--- |
| `Ctrl` (Left) | `0x1D` | `false` | Left Control |
| `Alt` (Left) | `0x38` | `false` | Left Alt / Menu |
| `Shift` (Left) | `0x2A` | `false` | Left Shift |
| `Win` (Left) | `0x5B` | `true` | Left Windows / Super |
| `Esc` | `0x01` | `false` | Escape |
| `Tab` | `0x0F` | `false` | Tab |
| `Delete` | `0x53` | `true` | Delete |
| `Insert` | `0x52` | `true` | Insert |
| `Home` | `0x47` | `true` | Home |
| `End` | `0x4F` | `true` | End |
| `Page Up` | `0x49` | `true` | Page Up |
| `Page Down` | `0x51` | `true` | Page Down |
| `F1` .. `F12` | `0x3B` .. `0x44`, `0x57`, `0x58` | `false` | Function Keys 1 through 12 |
| `Arrow Left` | `0x4B` | `true` | Left Arrow |
| `Arrow Up` | `0x48` | `true` | Up Arrow |
| `Arrow Right`| `0x4D` | `true` | Right Arrow |
| `Arrow Down` | `0x50` | `true` | Down Arrow |

---

## 3. Requirement R4 Deep Dive: Adaptive Low-Latency Performance & Telemetry

### 3.1 Auto-Reconnect State Machine (FSM)

Mobile network connections are volatile: switching between Wi-Fi and 5G/4G, walking through dead zones, or app backgrounding/device lock frequently breaks active TCP streams. The client must recover seamlessly without crashing or leaking native memory.

#### 3.1.1 State Machine Specification
```
                 [ User Connect ]
                        |
                        v
                 +--------------+
                 |  CONNECTING  |
                 +--------------+
                   |          |
         Success   |          | Failure / Timeout
                   v          v
          +-----------+    +-----------------------+
          | CONNECTED |    | RECONNECTING_BACKOFF  | <----+
          +-----------+    +-----------------------+      |
            |       |         |               ^           |
   Net Drop |       | User    | Timer Tick    | Fail      |
   or Pause |       | Discon  v               |           |
            v       |      +-------------+    |           |
          +-------+ |      | RECONNECT   |----+           |
          |PAUSED | |      | ATTEMPT     |                |
          +-------+ |      +-------------+                |
            |       |             |                       |
            +-------|-------------+ (Max Retries Expired) |
                    |                                     |
                    v                                     v
            +---------------+                     +---------------+
            | TEARING_DOWN  |                     | TERMINAL_FAIL |
            +---------------+                     +---------------+
                    |
                    v
            +---------------+
            | DISCONNECTED  |
            +---------------+
```

#### 3.1.2 State Transition Table

| Current State | Trigger / Event | Action Taken | Next State |
| :--- | :--- | :--- | :--- |
| `DISCONNECTED` | `CONNECT_REQUESTED(profile)` | Initialize native context, start I/O loop | `CONNECTING` |
| `CONNECTING` | `NATIVE_CONNECTED` | Reset retry counter to 0, start render thread | `CONNECTED` |
| `CONNECTING` | `NATIVE_CONNECT_FAILED` | Check retry count; calculate backoff $T_{backoff}$ | `RECONNECTING_BACKOFF` |
| `CONNECTED` | `NETWORK_LOST` | Signal native pause, preserve session credentials | `PAUSED` |
| `PAUSED` | `NETWORK_AVAILABLE` | Trigger immediate reconnect attempt | `CONNECTING` |
| `PAUSED` | `PAUSE_TIMEOUT (30s)` | Schedule reconnect with exponential backoff | `RECONNECTING_BACKOFF` |
| `CONNECTED` | `USER_DISCONNECT` | Abort network loop, flush input, free context | `TEARING_DOWN` |
| `RECONNECTING_BACKOFF`| `BACKOFF_TIMER_ELAPSED` | Clean teardown of prior context, launch connect | `CONNECTING` |
| `RECONNECTING_BACKOFF`| `MAX_RETRIES_EXCEEDED` | Release all native resources, notify UI | `TERMINAL_FAIL` |
| `TEARING_DOWN` | `CLEANUP_COMPLETED` | Reset session state, emit Disconnected | `DISCONNECTED` |

#### 3.1.3 Exponential Backoff with Full Jitter
To prevent thundering herd problems when an access point recovers:
$$T_{backoff} = \text{random}(0, \; \min(T_{max}, \; T_{base} \cdot 2^{attempt}))$$
Where $T_{base} = 1000\text{ ms}$, $T_{max} = 30000\text{ ms}$, and $\text{maxAttempts} = 5$.
*Fast-Path Override:* If Android's `ConnectivityManager.NetworkCallback.onAvailable()` fires while in `RECONNECTING_BACKOFF`, the backoff timer is cancelled immediately, and reconnection starts without waiting for the delay to expire.

#### 3.1.4 Clean Native Teardown Protocol
Failing to cleanly tear down native FreeRDP contexts leads to dangling socket file descriptors, native thread leaks, and SIGSEGV crashes on subsequent connection attempts.
The teardown sequence is strictly ordered:
1. Set cancellation flag on the Kotlin coroutine channel.
2. Signal FreeRDP native transport cancellation: `freerdp_abort_connect(context->instance)`.
3. Wait on native thread join with a 1500ms hard timeout.
4. Execute JNI context deallocation: `freerdp_client_context_free(context)` and `freerdp_free(instance)`.
5. Release Android Surface buffer lock and clear framebuffer reference.

---

### 3.2 Low-Latency Socket Buffering & Network Optimization

Interactive remote desktop performance is dominated by perceived input-to-render lag. High network throughput is irrelevant if packets sit in intermediate OS buffers (bufferbloat).

#### 3.2.1 Socket Configuration
- **`TCP_NODELAY` (Nagle Algorithm Disabled):** Standard TCP delays small packets up to 200ms to consolidate payloads. For mouse movements and keystrokes, `TCP_NODELAY` must be explicitly enabled to force immediate packet dispatch.
- **`SO_KEEPALIVE`:** Configured with 5-second interval and 3 probes to detect dead carrier drops before OS timeouts.
- **Low-Latency Socket Buffers:** OS defaults of 2MB–8MB buffer sizes allow hundreds of stale video frames to queue up during bandwidth drops. Receive and send buffers are tuned to:
  - `SO_RCVBUF = 131072` (128 KB)
  - `SO_SNDBUF = 65536` (64 KB)
- **IP Quality of Service (QoS):** Sets `IP_TOS = 0x10` (IPTOS_LOWDELAY) and DSCP Expedited Forwarding (EF / 46) to prioritize RDP packets over competing background traffic on Wi-Fi and LTE routers.

#### 3.2.2 FastPath RDP PDUs
The client negotiates FastPath input and output PDUs during the capability exchange phase (`TS_INPUT_CAPABILITYSET`). FastPath eliminates standard RDP packet headers (saving 3 bytes per input event) and routes events directly to the session processor without intermediate queueing.

---

### 3.3 Background Thread Isolation & Render Pipeline

To eliminate UI stutter and achieve sustained 60 FPS rendering, the client implements a strict 5-thread isolation architecture.

#### 3.3.1 Thread Topology
```
[ FreeRDP Native Socket ]
         |
         v
+------------------+      Encoded Tiles      +---------------------+
|  RdpIoThread     | ----------------------> |  RdpDecoderThread   |
| (Native epoll)   |                         | (RemoteFX / H.264)  |
+------------------+                         +---------------------+
                                                        |
                                                        v Decoded RGBA
                                             +---------------------+
                                             |  Atomic Frame Buffer|
                                             +---------------------+
                                                        |
                                                        v VSYNC Trigger
+------------------+     Touch/Mouse PDUs    +---------------------+
|  RdpInputChannel | <---------------------- |  RdpRenderThread    |
| (Coroutine FIFO) |                         | (SurfaceView Blit)  |
+------------------+                         +---------------------+
         ^                                              |
         | Raw MotionEvents                             v
+------------------------------------------------------------------+
|                    Android Main UI Thread                        |
+------------------------------------------------------------------+
```

1. **`RdpIoThread`:** Pure native network thread executing FreeRDP's `freerdp_check_event_handles()` in an epoll/select loop. Handles TLS/NLA encryption and packet demuxing.
2. **`RdpDecoderThread`:** Background worker that decodes compressed graphics (RemoteFX, Planar RLE, or H.264/AVC444). Writes decoded pixel rectangles directly into the shared pixel buffer.
3. **`RdpRenderThread`:** Synchronized with Android's `Choreographer` display refresh (VSYNC). Blits dirty rectangles to the hardware-accelerated `SurfaceView` using double-buffered dirty-rect clipping.
4. **`RdpInputChannel`:** Dedicated Kotlin coroutine channel (`Channel<RdpInputEvent>(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)`). Decouples touch generation from network serialization so heavy rendering never lags user touch feedback.
5. **Main UI Thread:** Handles Android View hierarchy, M3 Toolbar animations, Modifier Bar toggles, and Telemetry HUD rendering. Never performs disk I/O, socket operations, or bitmap decoding.

#### 3.3.2 Frame Pacing & Dynamic Frame Dropping
If network fluctuations cause a burst of video frames to arrive faster than the display can render (e.g. 90 FPS burst on a 60 Hz screen), queuing all frames induces growing latency. The render pipeline implements **Single-Slot Atomic Frame Pacing**:
- The decoder updates an `AtomicReference<FrameBuffer>` holding the latest complete frame.
- When the display VSYNC fires, the renderer takes whatever is currently in the atomic reference.
- Intermediate stale frames are dropped before rendering, guaranteeing that the user always sees the most recent frame and input lag never accumulates.

---

### 3.4 Bandwidth & Battery-Aware Performance Modes

The client defines four optimized performance presets adapting to network quality and power constraints:

| Parameter | Ultra-Low Latency | Balanced Mobile | Data Saver | Battery Saver |
| :--- | :---: | :---: | :---: | :---: |
| **Target FPS Cap** | 60 FPS | 30 FPS | 15 FPS | 30 FPS |
| **Color Depth** | 32-bit ARGB8888 | 24-bit RGB888 | 16-bit RGB565 | 16-bit RGB565 |
| **Compression Codec** | RemoteFX / AVC444 | RemoteFX / Tile | Planar RLE | Planar RLE |
| **Audio Redirection** | Enabled (Low-delay) | Enabled (Compressed)| Disabled | Disabled |
| **Font Smoothing** | Enabled | Enabled | Disabled | Disabled |
| **Desktop Wallpaper**| Enabled | Disabled | Disabled | Disabled |
| **Full Window Drag** | Enabled | Disabled | Disabled | Disabled |
| **Menu Animations**  | Enabled | Disabled | Disabled | Disabled |
| **Telemetry Rate**   | 10 Hz (Real-time) | 2 Hz | 1 Hz | 0.5 Hz |
| **Target Network**   | Wi-Fi 5/6, LAN | 5G, LTE Unmetered | Metered / 3G | Any (< 20% Battery) |

#### 3.4.1 Dynamic Network & Battery Auto-Switching
The application registers a `ConnectivityManager.NetworkCallback` and a `BroadcastReceiver` for `Intent.ACTION_BATTERY_LOW`:
- When switching from Wi-Fi to a Metered Cellular connection, the client dynamically prompts or automatically transitions from `Ultra-Low Latency` to `Balanced Mobile` or `Data Saver`.
- When device battery drops below 15%, background polling and telemetry rates are throttled, and frame rate is capped at 30 FPS.

---

### 3.5 Dynamic Orientation, Foldables & Multi-Window Support

#### 3.5.1 FreeRDP Dynamic Display Resizing (MS-RDPEDISP)
When an Android device rotates (e.g. 1080x2400 portrait to 2400x1080 landscape) or unfolds (e.g. outer screen 840x2260 to inner display 1812x2176 on a foldable device):
- The Activity does NOT recreate (`android:configChanges="orientation|screenSize|smallestScreenSize|screenLayout"`).
- The `SurfaceView` catches dimension changes via `SurfaceHolder.Callback.surfaceChanged(width, height)`.
- If the remote server supports the **MS-RDPEDISP** Virtual Channel (Windows 8.1+, Windows Server 2012 R2+), the client dispatches a `DISPLAYCONTROL_MONITOR_LAYOUT_PDU`:
  ```c
  DISPLAYCONTROL_MONITOR_LAYOUT layout;
  layout.Flags = DISPLAYCONTROL_MONITOR_PRIMARY;
  layout.Left = 0;
  layout.Top = 0;
  layout.Width = newWidth;
  layout.Height = newHeight;
  layout.PhysicalWidth = physicalWidthMm;
  layout.PhysicalHeight = physicalHeightMm;
  layout.Orientation = orientationDegrees;
  layout.DesktopScaleFactor = scalePercent;
  ```
- Windows dynamically reconfigures its desktop resolution within 300ms without dropping the session or resetting open applications.

#### 3.5.2 Client-Side Matrix Scaling Fallback
For legacy servers lacking RDPEDISP (e.g. Windows 7 / Server 2008), the client falls back to hardware-accelerated matrix scaling:
- Computes uniform scale factor $S = \min(W_{screen} / W_{remote}, \; H_{screen} / H_{remote})$.
- Centers the remote desktop with letterboxing/pillarboxing.
- Directs touch coordinates through the inverse matrix so remote clicks match visual pixel positions accurately.

---

### 3.6 Real-Time Diagnostic Telemetry Engine

To monitor connection health and troubleshoot remote lag, the client incorporates a lightweight diagnostic telemetry engine.

#### 3.6.1 Metrics Pipeline
1. **Network RTT (Ping):** Measures round-trip time in milliseconds using RDP Server-to-Client heartbeat echo requests (`TS_HEARTBEAT_PDU`).
2. **Render FPS:** Measures actual frames blitted to the `SurfaceView` per second using a 1-second sliding time window.
3. **Decode FPS:** Measures compressed frame decoding rate to identify CPU bottlenecks.
4. **Bandwidth Throughput:** Tracks inbound (downlink) and outbound (uplink) throughput in KB/s.
5. **Inter-Frame Jitter:** Computes standard deviation of frame delivery intervals ($\Delta t_{frame}$) to detect micro-stutter:
   $$\sigma_{jitter} = \sqrt{\frac{1}{N} \sum_{i=1}^{N} (\Delta t_i - \overline{\Delta t})^2}$$
6. **Active Codec & Resolution:** Displays current active decoder (e.g. `AVC444`, `RFX`, `RLE`) and session dimensions.

#### 3.6.2 Ring Buffer & Diagnostic Exporter
- **In-Memory Ring Buffer:** Stores the last 1,000 telemetry snapshots (100 seconds at 10 Hz) with zero memory allocations using pre-allocated circular primitive arrays.
- **HUD View:** Translucent M3 chip positioned in screen corner showing:
  `FPS: 60 | RTT: 18ms | Jitter: 1.8ms | 1.4 MB/s | LAN`
- **Diagnostic Export:** Generates an anonymized diagnostic JSON report for troubleshooting server connection failures.

---

## 4. Modular Android Architecture & Package Breakdown

### 4.1 Architectural Pattern: Clean Architecture & UDF MVVM
The application architecture adheres to Unidirectional Data Flow (UDF) and Clean Architecture principles:
- **Presentation Layer:** Jetpack Compose / Material 3 Views + StateFlow ViewModels.
- **Domain Layer:** Pure Kotlin use cases, state machine engines, and repository interfaces. Completely free of Android SDK or native dependencies.
- **Data & Native Layer:** Concrete repositories, Room / JSON storage, Android KeyStore crypto, and the FreeRDP JNI wrapper.

### 4.2 Comprehensive Package Breakdown

```
com.freerdp.mobile
├── core
│   ├── common/                          # Cross-cutting utilities & coroutine dispatchers
│   │   ├── CoroutineDispatchers.kt      # Main, IO, Default, Render thread dispatchers
│   │   ├── Result.kt                    # Standard Result/Outcome monad
│   │   └── RingBuffer.kt                # Lock-free circular primitive buffer
│   │
│   ├── model/                           # Pure domain entities (Zero dependencies)
│   │   ├── RdpProfile.kt                # Profile configuration model
│   │   ├── ConnectionState.kt           # Session lifecycle states
│   │   ├── SessionTelemetry.kt          # Telemetry metrics data class
│   │   ├── InputEvents.kt               # Mouse, touch, keyboard domain events
│   │   ├── ModifierKey.kt               # Modifier key definitions and latch states
│   │   └── PerformancePreset.kt         # Performance profile presets
│   │
│   ├── engine/                          # FreeRDP JNI Wrapper & Engine Abstraction
│   │   ├── IRdpEngine.kt                # Core engine contract interface
│   │   ├── RdpEngineImpl.kt             # Native FreeRDP JNI wrapper implementation
│   │   ├── NativeFrameBuffer.kt         # Direct ByteBuffer memory bridge
│   │   └── ScancodeTranslator.kt        # Virtual-Key to Windows Scancode mapper
│   │
│   ├── security/                        # Cryptographic Vault
│   │   ├── CredentialStore.kt           # Secret storage interface
│   │   ├── KeystoreCredentialStore.kt   # EncryptedSharedPreferences + MasterKey
│   │   └── SecurityValidator.kt         # Hostname, cert & token hygiene checks
│   │
│   ├── data/                            # Persistence & Repository Layer
│   │   ├── ProfileRepository.kt         # Profile repository interface
│   │   ├── AtomicFileProfileRepo.kt     # Atomic JSON file-backed repository
│   │   └── ProfileValidator.kt          # Validation rules for profiles
│   │
│   ├── network/                         # Low-Latency Networking & Lifecycle
│   │   ├── NetworkMonitor.kt            # ConnectivityManager network callback
│   │   ├── LowLatencySocketConfig.kt    # TCP_NODELAY, buffer sizing utilities
│   │   └── ReconnectStateMachine.kt     # Exponential backoff auto-reconnect FSM
│   │
│   └── telemetry/                       # Real-Time Telemetry Engine
│       ├── TelemetryCollector.kt        # Metrics aggregator (FPS, RTT, Jitter)
│       └── TelemetrySnapshot.kt         # Immutable metrics snapshot
│
├── feature
│   ├── profiles/                        # Material 3 Profile Management
│   │   ├── ProfileListScreen.kt         # Connections dashboard UI
│   │   ├── ProfileEditorScreen.kt       # Profile create/edit forms
│   │   └── ProfileViewModel.kt          # Dashboard state & CRUD management
│   │
│   ├── session/                         # Active RDP Session Viewport
│   │   ├── RdpSessionActivity.kt        # Full-screen session container
│   │   ├── RdpSurfaceView.kt            # Hardware-accelerated frame blitter
│   │   ├── RdpSessionViewModel.kt       # Session lifecycle & connection state
│   │   └── RenderLoop.kt                # VSYNC Choreographer render driver
│   │
│   ├── mouse/                           # Floating Mouse Overlay Subsystem
│   │   ├── FloatingMouseOverlay.kt      # Draggable floating mouse view
│   │   ├── FloatingMouseViewModel.kt    # Repositioning & state persistence
│   │   └── MouseMode.kt                 # Touchpad vs Pointer modes
│   │
│   ├── gestures/                        # Touch Gesture Engine
│   │   ├── TouchGestureDetector.kt      # Pan, zoom, tap, drag disambiguator
│   │   ├── CoordinateTransformer.kt     # Screen-to-Remote matrix projection
│   │   └── GestureEventFilter.kt        # Spurious click elimination filter
│   │
│   ├── toolbar/                         # Collapsible Quick-Action Toolbar
│   │   ├── QuickActionToolbarView.kt    # Dockable toolbar pill UI
│   │   ├── ToolbarAction.kt             # Action enum definitions
│   │   └── InactivityTimer.kt           # 4-second auto-collapse controller
│   │
│   └── modifierbar/                     # Mobile Modifier Keys Helper Bar
│       ├── ModifierBarView.kt           # On-screen modifier keys strip
│       ├── ModifierBarViewModel.kt      # Modifier state management
│       └── ModifierStateMachine.kt      # Latching / locking FSM logic
│
└── di/                                  # Dependency Container & Service Locator
    ├── AppContainer.kt                  # Lightweight DI container
    └── ServiceLocator.kt                # Global service access point
```

### 4.3 Lightweight Dependency Injection (`AppContainer`)
To guarantee reproducible compilation on Windows build environments without annotation processor quirks (such as Kapt/KSP version mismatches), the architecture uses a lightweight, interface-driven `AppContainer`:

```kotlin
interface AppContainer {
    val profileRepository: ProfileRepository
    val credentialStore: CredentialStore
    val networkMonitor: NetworkMonitor
    val telemetryCollector: TelemetryCollector
    fun createRdpEngine(): IRdpEngine
}

class DefaultAppContainer(private val context: Context) : AppContainer {
    override val credentialStore: CredentialStore by lazy {
        KeystoreCredentialStore(context)
    }

    override val profileRepository: ProfileRepository by lazy {
        AtomicFileProfileRepo(File(context.filesDir, "profiles.json"))
    }

    override val networkMonitor: NetworkMonitor by lazy {
        AndroidNetworkMonitor(context)
    }

    override val telemetryCollector: TelemetryCollector by lazy {
        RealTimeTelemetryCollector()
    }

    override fun createRdpEngine(): IRdpEngine {
        return RdpEngineImpl()
    }
}
```
This pattern allows test runners to swap `AppContainer` with `TestAppContainer` injecting `MockRdpEngine`, `MockCredentialStore`, and `FakeProfileRepository` instantly.

---

## 5. Comprehensive Testing Architecture & Quality Strategy

### 5.1 The Testing Pyramid

```
                / \
               /   \
              / E2E \       10% (Opaque-Box E2E Integration Suite)
             /-------\
            / Robo-   \     20% (Robolectric Keystore, Gestures & UI)
           /  lectric  \
          /-------------\
         /   Pure JVM    \  70% (Domain Models, FSMs, Matrix Math,
        /   Unit Tests    \      Telemetry, Scancode Translation)
       ---------------------
```

1. **Pure JVM Unit Tests (Fast, 0ms overhead):** Validates all domain logic, state machines, validators, matrix coordinate calculations, and backoff algorithms without loading Android frameworks.
2. **Robolectric Integration Tests:** Validates Android KeyStore encryption, `EncryptedSharedPreferences`, Android ViewModels, Activity lifecycles, and `MotionEvent` dispatching without requiring an Android emulator or device.
3. **Opaque-Box E2E Test Suite:** Validates complete end-to-end user workflows from profile creation to connection, gesture handling, network interruption recovery, and clean session teardown.

---

### 5.2 Robolectric Keystore & EncryptedSharedPreferences Testing

#### 5.2.1 The Android Keystore Challenge in Tests
Testing Android Keystore normally fails in local JVM tests because the hardware `keystore2` daemon is absent, throwing `KeyStoreException: KeyStore not initialized`.

#### 5.2.2 The Robolectric Testing Strategy
By utilizing Robolectric with Android API level 33, Robolectric provides an in-memory KeyStore shadow (`ShadowKeyStore`) that supports standard Java Security providers.

```kotlin
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class KeystoreCredentialStoreTest {

    private lateinit var context: Context
    private lateinit var credentialStore: CredentialStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        credentialStore = KeystoreCredentialStore(context)
    }

    @Test
    fun testSaveAndRetrieveCredential() = runTest {
        val profileId = "profile-uuid-1234"
        val password = "SuperSecretPassword123!".toCharArray()

        val saveResult = credentialStore.saveCredential(profileId, password)
        assertTrue("Save credential must succeed", saveResult.isSuccess)

        val retrievedResult = credentialStore.getCredential(profileId)
        assertTrue("Get credential must succeed", retrievedResult.isSuccess)
        assertNotNull("Retrieved password must not be null", retrievedResult.getOrNull())
        assertArrayEquals("Retrieved password must match original", password, retrievedResult.getOrNull())
    }

    @Test
    fun testDeleteCredentialRemovesSecret() = runTest {
        val profileId = "profile-uuid-delete"
        credentialStore.saveCredential(profileId, "SecretToDelete".toCharArray())

        val deleteResult = credentialStore.deleteCredential(profileId)
        assertTrue("Delete must succeed", deleteResult.isSuccess)

        val retrievedResult = credentialStore.getCredential(profileId)
        assertNull("Deleted credential must return null", retrievedResult.getOrNull())
    }

    @Test
    fun testOverwriteCredentialUpdatesSecret() = runTest {
        val profileId = "profile-uuid-overwrite"
        credentialStore.saveCredential(profileId, "InitialPass".toCharArray())
        credentialStore.saveCredential(profileId, "UpdatedPass".toCharArray())

        val retrieved = credentialStore.getCredential(profileId).getOrNull()
        assertArrayEquals("UpdatedPass".toCharArray(), retrieved)
    }

    @Test
    fun testClearAllPurgesAllSecrets() = runTest {
        credentialStore.saveCredential("profile-1", "Pass1".toCharArray())
        credentialStore.saveCredential("profile-2", "Pass2".toCharArray())

        credentialStore.clearAll()

        assertNull(credentialStore.getCredential("profile-1").getOrNull())
        assertNull(credentialStore.getCredential("profile-2").getOrNull())
    }
}
```

---

### 5.3 Gesture & Floating Mouse Simulation Testing

#### 5.3.1 Synthetic MotionEvent Generator
To verify gesture disambiguation and floating mouse actions without manual touching, the test framework includes a deterministic `MotionEventBuilder`:

```kotlin
object MotionEventBuilder {
    fun down(x: Float, y: Float, downTime: Long = SystemClock.uptimeMillis()): MotionEvent {
        return MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
    }

    fun move(x: Float, y: Float, downTime: Long, eventTime: Long): MotionEvent {
        return MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_MOVE, x, y, 0)
    }

    fun up(x: Float, y: Float, downTime: Long, eventTime: Long): MotionEvent {
        return MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_UP, x, y, 0)
    }

    fun pointerDown(x0: Float, y0: Float, x1: Float, y1: Float, downTime: Long): MotionEvent {
        val properties = arrayOf(
            MotionEvent.PointerProperties().apply { id = 0 },
            MotionEvent.PointerProperties().apply { id = 1 }
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply { x = x0; y = y0 },
            MotionEvent.PointerCoords().apply { x = x1; y = y1 }
        )
        return MotionEvent.obtain(
            downTime, downTime,
            MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            2, properties, coords, 0, 0, 1.0f, 1.0f, 0, 0, 0, 0
        )
    }
}
```

#### 5.3.2 Test Cases: Gesture Disambiguation & Mouse State Machine
1. **Single Tap vs Drag:**
   - Tap down at $(100, 100)$ and up at $(102, 101)$ within 80ms $\rightarrow$ emits single Left-Click event.
   - Tap down at $(100, 100)$, move to $(150, 100)$ over 300ms $\rightarrow$ emits Pan / Viewport Scroll event; verifies zero mouse click events are emitted.
2. **Pinch-to-Zoom Isolation:**
   - Two pointers down at $(200, 200)$ and $(400, 400)$, moving apart to $(150, 150)$ and $(450, 450)$ $\rightarrow$ verifies zoom scale increases and **strictly zero** touch-to-click events trigger.
3. **Floating Mouse Repositioning vs Mouse Click:**
   - Touch down on Floating Mouse pill handle and drag across screen $\rightarrow$ verifies Floating Mouse $(X, Y)$ coordinate state updates, and no clicks are forwarded to the underlying RDP session.
4. **Screen Clamping & Orientation Invariance:**
   - When screen dimensions change (e.g. $1080 \times 2400 \rightarrow 2400 \times 1080$), floating mouse coordinates at relative position $(x/W = 0.8, y/H = 0.5)$ remain within visible display boundaries and do not slip off-screen or underneath system bars.

---

### 5.4 Session State Machine & Mock FreeRDP Test Double

#### 5.4.1 The `MockRdpEngine` Test Double
To test session lifecycle, error handling, and reconnection deterministically, the `IRdpEngine` interface is backed by a fully controllable test double:

```kotlin
interface IRdpEngine {
    val connectionState: StateFlow<ConnectionState>
    fun connect(profile: RdpProfile, credentials: CharArray?)
    fun disconnect()
    fun sendMouseEvent(flags: Int, x: Int, y: Int)
    fun sendKeyboardEvent(flags: Int, scancode: Int)
    fun sendMonitorLayout(width: Int, height: Int)
}

class MockRdpEngine : IRdpEngine {
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    val sentMouseEvents = mutableListOf<Triple<Int, Int, Int>>()
    val sentKeyEvents = mutableListOf<Pair<Int, Int>>()
    val sentMonitorLayouts = mutableListOf<Pair<Int, Int>>()
    var disconnectCallCount = 0

    override fun connect(profile: RdpProfile, credentials: CharArray?) {
        _connectionState.value = ConnectionState.Connecting
    }

    override fun disconnect() {
        disconnectCallCount++
        _connectionState.value = ConnectionState.Disconnected
    }

    override fun sendMouseEvent(flags: Int, x: Int, y: Int) {
        sentMouseEvents.add(Triple(flags, x, y))
    }

    override fun sendKeyboardEvent(flags: Int, scancode: Int) {
        sentKeyEvents.add(Pair(flags, scancode))
    }

    override fun sendMonitorLayout(width: Int, height: Int) {
        sentMonitorLayouts.add(Pair(width, height))
    }

    // Test Control Hooks
    fun simulateConnected() {
        _connectionState.value = ConnectionState.Connected
    }

    fun simulateConnectionError(reason: String) {
        _connectionState.value = ConnectionState.Failed(reason)
    }

    fun simulateNetworkLost() {
        _connectionState.value = ConnectionState.Paused
    }
}
```

#### 5.4.2 Session Reconnect FSM Test Suite
```kotlin
class ReconnectStateMachineTest {

    private lateinit var mockEngine: MockRdpEngine
    private lateinit var stateMachine: ReconnectStateMachine

    @Before
    fun setUp() {
        mockEngine = MockRdpEngine()
        stateMachine = ReconnectStateMachine(
            engine = mockEngine,
            maxRetries = 3,
            baseDelayMs = 100L
        )
    }

    @Test
    fun testCleanConnectTransition() = runTest {
        stateMachine.startConnect(testProfile)
        assertEquals(ConnectionState.Connecting, mockEngine.connectionState.value)

        mockEngine.simulateConnected()
        assertEquals(ConnectionState.Connected, stateMachine.currentState.value)
        assertEquals(0, stateMachine.currentRetryCount)
    }

    @Test
    fun testNetworkDropTriggersExponentialBackoffAndRecovery() = runTest {
        stateMachine.startConnect(testProfile)
        mockEngine.simulateConnected()

        // Simulate network loss
        stateMachine.onNetworkLost()
        assertEquals(ConnectionState.Paused, stateMachine.currentState.value)

        // Advance time through backoff delay
        testScheduler.advanceTimeBy(150L)
        assertEquals(1, stateMachine.currentRetryCount)

        // Network restored
        stateMachine.onNetworkAvailable()
        mockEngine.simulateConnected()

        assertEquals(ConnectionState.Connected, stateMachine.currentState.value)
        assertEquals(0, stateMachine.currentRetryCount) // reset on success
    }

    @Test
    fun testExceedingMaxRetriesTransitionsToTerminalFailed() = runTest {
        stateMachine.startConnect(testProfile)
        mockEngine.simulateConnectionError("Connection Refused")

        // Exhaust retries
        for (i in 1..3) {
            testScheduler.advanceTimeBy(5000L)
            mockEngine.simulateConnectionError("Timeout")
        }

        assertTrue(
            "State must transition to Terminal Failed",
            stateMachine.currentState.value is ConnectionState.TerminalFailed
        )
        assertTrue("Disconnect must be invoked on engine", mockEngine.disconnectCallCount >= 1)
    }

    @Test
    fun testUserDisconnectAbortsPendingReconnectTimer() = runTest {
        stateMachine.startConnect(testProfile)
        mockEngine.simulateConnectionError("Network Down")

        // In backoff wait
        stateMachine.userDisconnect()

        testScheduler.advanceTimeBy(10000L)
        assertEquals(ConnectionState.Disconnected, stateMachine.currentState.value)
        assertEquals(0, stateMachine.currentRetryCount)
    }
}
```

---

### 5.5 Opaque-Box E2E Test Harness (Requirements-Driven Tiers 1–4)

To satisfy the Acceptance Criteria without flakiness, an **Opaque-Box E2E Test Suite** is organized into four tiers executable in standard JVM/Robolectric test runs:

| Tier | Category | Scope & Invariants Tested |
| :--- | :--- | :--- |
| **Tier 1** | **Build & Modular Wiring** | Validates dependency graph, `AppContainer` initialization, non-null core interfaces, and scancode table integrity. |
| **Tier 2** | **Profile CRUD & Keystore Security** | Creates, reads, updates, and deletes profiles. Validates that passwords stored in `KeystoreCredentialStore` are encrypted on disk and purged on profile deletion. |
| **Tier 3** | **Session Lifecycle & Auto-Reconnect** | Executes complete connection lifecycle: Connect -> Authenticate -> Network Drop -> Exponential Backoff -> Reconnect -> Clean Disconnect. Verifies zero resource leaks. |
| **Tier 4** | **Floating Mouse, Gestures & Modifier Bar** | Simulates touch gestures (pinch-zoom, scroll, pan), verifies floating mouse repositioning and boundary clamping, and tests modifier key latching and macro sequences. |

#### 5.5.1 Tier 2 E2E Workflow Test: Profile & Keystore Security
```kotlin
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ProfileAndKeystoreSecurityE2ETest {

    private lateinit var appContainer: AppContainer

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        appContainer = DefaultAppContainer(context)
    }

    @Test
    fun testFullProfileLifecycleWithSecureCredentials() = runTest {
        val repo = appContainer.profileRepository
        val vault = appContainer.credentialStore

        // 1. Create Profile
        val profile = RdpProfile(
            label = "Production Windows Server",
            hostname = "192.168.1.150",
            port = 3389,
            username = "Administrator"
        )
        val plainPassword = "EnterprisePassword456$".toCharArray()

        repo.saveProfile(profile)
        vault.saveCredential(profile.id, plainPassword)

        // 2. Read Profile & Verify Separation
        val retrievedProfile = repo.getProfile(profile.id)
        assertNotNull(retrievedProfile)
        assertEquals("Production Windows Server", retrievedProfile?.label)

        // 3. Verify Decryption from Keystore
        val decryptedSecret = vault.getCredential(profile.id).getOrNull()
        assertNotNull(decryptedSecret)
        assertArrayEquals(plainPassword, decryptedSecret)

        // 4. Update Profile
        val updated = retrievedProfile!!.copy(label = "Updated Server Name", port = 3390)
        repo.saveProfile(updated)
        assertEquals(3390, repo.getProfile(profile.id)?.port)

        // 5. Delete Profile & Ensure Credential Purged
        repo.deleteProfile(profile.id)
        vault.deleteCredential(profile.id)

        assertNull(repo.getProfile(profile.id))
        assertNull(vault.getCredential(profile.id).getOrNull())
    }
}
```

#### 5.5.2 Tier 4 E2E Workflow Test: Modifier Bar Latching & Macro Dispatch
```kotlin
class ModifierBarAndMacroE2ETest {

    private lateinit var mockEngine: MockRdpEngine
    private lateinit var modifierFsm: ModifierStateMachine

    @Before
    fun setUp() {
        mockEngine = MockRdpEngine()
        modifierFsm = ModifierStateMachine(mockEngine)
    }

    @Test
    fun testCtrlKeyLatchingAndAutoRelease() {
        // Step 1: Tap Ctrl once -> LATCHED
        modifierFsm.onModifierKeyTapped(ModifierKey.CTRL)
        assertEquals(LatchState.LATCHED, modifierFsm.getModifierState(ModifierKey.CTRL))
        assertTrue(mockEngine.sentKeyEvents.contains(Pair(0x0000 /* KeyDown */, 0x1D /* LCtrl Scancode */)))

        // Step 2: User presses non-modifier key 'C' (Scancode 0x2E)
        modifierFsm.onNonModifierKeyPressed(0x2E)

        // Step 3: Ctrl must automatically release
        assertEquals(LatchState.INACTIVE, modifierFsm.getModifierState(ModifierKey.CTRL))
        assertTrue(mockEngine.sentKeyEvents.contains(Pair(0x0100 /* KeyUp */, 0x1D)))
    }

    @Test
    fun testCtrlKeyLockingPersistsAcrossMultipleKeys() {
        // Step 1: Double tap Ctrl -> LOCKED
        modifierFsm.onModifierKeyTapped(ModifierKey.CTRL)
        modifierFsm.onModifierKeyTapped(ModifierKey.CTRL)
        assertEquals(LatchState.LOCKED, modifierFsm.getModifierState(ModifierKey.CTRL))

        // Step 2: Press 'A' then 'C'
        modifierFsm.onNonModifierKeyPressed(0x1E) // 'A'
        modifierFsm.onNonModifierKeyPressed(0x2E) // 'C'

        // Step 3: Must still be LOCKED
        assertEquals(LatchState.LOCKED, modifierFsm.getModifierState(ModifierKey.CTRL))

        // Step 4: Third tap releases lock
        modifierFsm.onModifierKeyTapped(ModifierKey.CTRL)
        assertEquals(LatchState.INACTIVE, modifierFsm.getModifierState(ModifierKey.CTRL))
    }

    @Test
    fun testCtrlAltDelMacroSequence() {
        modifierFsm.triggerMacro(MacroAction.CTRL_ALT_DEL)

        val keys = mockEngine.sentKeyEvents
        // Must contain Down(Ctrl), Down(Alt), Down(Del), Up(Del), Up(Alt), Up(Ctrl)
        val expectedScancodes = listOf(0x1D, 0x38, 0x53, 0x53, 0x38, 0x1D)
        val actualScancodes = keys.takeLast(6).map { it.second }
        assertEquals(expectedScancodes, actualScancodes)
    }
}
```

---

## 6. Acceptance Criteria Verification Matrix

| Requirement / Criterion | Architectural Component | Verification Method | Pass Target |
| :--- | :--- | :--- | :--- |
| **R3: Material 3 UI** | `ProfileListScreen`, `ProfileEditorScreen`, `RdpSessionScreen` | Compose / Robolectric UI tests validating M3 themes, cards, dynamic colors | 100% |
| **R3: Profile CRUD** | `ProfileRepository`, `AtomicFileProfileRepo` | `ProfileRepositoryTest` verifying Create, Read, Update, Delete, Duplicate | 100% |
| **R3: Keystore Encryption** | `KeystoreCredentialStore`, Jetpack Security | `KeystoreCredentialStoreTest` via Robolectric with `ShadowKeyStore` | 100% |
| **R3: Quick-Action Toolbar**| `QuickActionToolbarView`, `InactivityTimer` | Unit tests for auto-collapse timer (4s) and mode switching | 100% |
| **R3: Mobile Modifier Keys**| `ModifierStateMachine`, `ScancodeTranslator` | `ModifierBarAndMacroE2ETest` verifying Latch, Lock, and Macro sequences | 100% |
| **R4: Auto-Reconnect FSM** | `ReconnectStateMachine`, Exponential Backoff | `ReconnectStateMachineTest` verifying drop, retry, backoff, and recovery | 100% |
| **R4: Low-Latency Buffers** | `LowLatencySocketConfig`, FastPath PDUs | JVM socket configuration tests checking `TCP_NODELAY` and buffer sizes | 100% |
| **R4: Thread Isolation** | `RdpIoThread`, `RdpDecoderThread`, `RdpRenderThread` | Pipeline concurrency test verifying non-blocking UI and frame pacing | 100% |
| **R4: Performance Presets** | `PerformancePreset`, `DynamicSettingsManager` | Preset unit tests verifying parameter tuning matrix (FPS, BPP, Codecs) | 100% |
| **R4: Dynamic Resizing** | `CoordinateTransformer`, `MS-RDPEDISP` PDU | Orientation and layout PDU tests validating dynamic resolution resizing | 100% |
| **R4: Real-Time Telemetry** | `TelemetryCollector`, `RingBuffer` | Unit tests verifying sliding window FPS, RTT, and Jitter calculations | 100% |
| **Test Suite Execution** | `./gradlew testDebugUnitTest` | Full multi-tier test harness running on standard JVM / Robolectric | 100% Pass |

---

## 7. Implementation Roadmap & Recommended Milestones

Based on this blueprint, the recommended milestone execution path is:
1. **Milestone 1: Project Foundation & Core RDP Engine Isolation (`:core-rdp`, `:core-model`):**
   - Establish Gradle build setup (`settings.gradle.kts`, `build.gradle.kts`).
   - Define `IRdpEngine` interface and mock test double.
   - Implement scancode mappings and model entities.
2. **Milestone 2: Security Vault & Profile Management (`:core-security`, `:core-data`, `:feature-profiles`):**
   - Implement `KeystoreCredentialStore` with Jetpack Security.
   - Implement `AtomicFileProfileRepo` with atomic file transactions.
   - Build Material 3 Profile List and Profile Editor screens.
   - Run Robolectric Keystore test suite.
3. **Milestone 3: Low-Latency Pipeline, Telemetry & Auto-Reconnect (`:core-network`, `:core-telemetry`):**
   - Implement `ReconnectStateMachine` with exponential backoff and full jitter.
   - Implement `LowLatencySocketConfig` and frame pacing atomic queue.
   - Implement real-time `TelemetryCollector` and diagnostic HUD overlay.
4. **Milestone 4: Floating Mouse, Mobile Modifier Keys & Toolbar (`:feature-mouse`, `:feature-modifierbar`, `:feature-toolbar`):**
   - Implement floating mouse draggable overlay and screen boundary clamping.
   - Implement 3-state modifier key latching/locking machine and macro shortcuts.
   - Implement collapsible quick-action toolbar with 4-second inactivity auto-collapse.
5. **Milestone 5: End-to-End Test Suite & Verification:**
   - Execute Tiers 1 through 4 Opaque-Box E2E test harness.
   - Verify `./gradlew assembleDebug` and `./gradlew testDebugUnitTest` achieve 100% pass rate.

---
*Report concluded. Ready for handoff and milestone decomposition.*
