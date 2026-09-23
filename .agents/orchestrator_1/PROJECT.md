# Project: Android FreeRDP Mobile Client

## Architecture
Mobile-first Android Remote Desktop client built on FreeRDP core, designed with Clean Architecture and strict modular isolation.

```
+-----------------------------------------------------------------------------------+
|                                      :app                                         |
|  - Main Session Activity & Material 3 Remote Desktop Viewer UI                    |
|  - AppContainer DI & Lifecycle Coordination                                       |
+-------------------------+--------------------------------+------------------------+
                          |                                |
+-------------------------v--------+             +---------v------------------------+
|         :feature-mouse           |             |        :feature-session          |
|  - Floating Mouse Overlay View   |             |  - Material 3 Profile Manager    |
|  - Gesture Disambiguation Engine |             |  - Jetpack Keystore Vault        |
|  - Affine Coordinate Transformer |             |  - Quick-Action Modifier Bar     |
+-------------------------+--------+             +---------+------------------------+
                          |                                |
+-------------------------v--------------------------------v------------------------+
|                                :feature-telemetry                                 |
|  - Auto-Reconnect State Machine (Exponential Backoff + Jitter)                    |
|  - Low-Latency Render Loop & Single-Slot Frame Pacer                              |
|  - Real-Time Diagnostic Telemetry (FPS, RTT, Jitter, Bandwidth Ring Buffer)       |
+------------------------------------------+----------------------------------------+
                                           |
+------------------------------------------v----------------------------------------+
|                                    :core-rdp                                      |
|  - IRdpEngine Interface & RdpEventListener Contracts                              |
|  - NativeFreeRdpEngine (JNI LibFreeRDP Wrapper + Thread-Safe Atomic Pointer)      |
|  - MockRdpEngine (Deterministic Test Double for Robolectric / JVM Testing)         |
|  - Dynamic Resolution (MS-RDPEDISP) & Clipboard Sync (MS-RDPECLIP) Handlers       |
+-----------------------------------------------------------------------------------+
```

## Feature Inventory
| # | Feature | Description | Milestone | Source |
|---|---------|-------------|-----------|--------|
| 1 | FreeRDP JNI & Module Isolation | Modular `:core-rdp` wrapping `LibFreeRDP` with `AtomicLong` JNI pointer safety | M1 | survey |
| 2 | Dual-Engine Pattern (`IRdpEngine`) | Abstract interface with `NativeFreeRdpEngine` and deterministic `MockRdpEngine` | M1 | survey |
| 3 | TLS & NLA (CredSSP) Authentication | Secure handshake parameters and certificate verification callbacks | M1 | survey |
| 4 | Dynamic Resolution Resizing (MS-RDPEDISP) | Debounced `DISPLAY_CONTROL_MONITOR_LAYOUT` client updates on orientation changes | M1 | survey |
| 5 | Clipboard Synchronization (MS-RDPECLIP) | UTF-16LE text clipboard synchronization with SHA-256 echo-loop suppression | M1 | survey |
| 6 | Gradle Infrastructure & Build Config | AGP 9.2.1, Kotlin 2.2.20, Compose BOM, Android SDK local.properties configuration | M1 | survey |
| 7 | Floating Mouse Overlay FSM | Collapsible, expandable, repositionable floating overlay control view | M2 | survey |
| 8 | Complete Mouse Events Generation | Left-click, right-click, double-click, click-and-drag, long-press, wheel/scroll | M2 | survey |
| 9 | Touchpad Simulation Mode | Relative cursor movement, tap-to-click, two-finger scroll in overlay | M2 | survey |
| 10 | Optional Virtual Cursor | Hardware/software cursor rendered at remote desktop coordinates | M2 | survey |
| 11 | Touch Gestures (Pan & Pinch-to-Zoom) | Multi-touch canvas pan and zoom with affine scale and offset clamping | M2 | survey |
| 12 | Anti-Spurious Click Multi-Touch Latch | Hysteresis latch suppressing tap-to-click when pointerCount > 1 during zoom/pan | M2 | survey |
| 13 | Orientation Adaptation & Clamping | Normalized coordinate persistence avoiding off-screen clipping across portrait/landscape | M2 | survey |
| 14 | Affine Coordinate Transformation | Bidirectional screen-to-desktop and desktop-to-screen matrix conversion | M2 | survey |
| 15 | Material 3 UI & Navigation | Modern Android Material 3 theme, dark/light palette, edge-to-edge support | M3 | survey |
| 16 | Connection Profile CRUD Manager | Create, Read, Update, Delete server configurations with atomic JSON storage | M3 | survey |
| 17 | Keystore & EncryptedSharedPreferences | Jetpack Security AES-256-GCM credential vault with `CharArray` zeroing | M3 | survey |
| 18 | Collapsible Quick-Action Toolbar | Non-intrusive toolbar with 4s auto-collapse timer and one-tap session actions | M3 | survey |
| 19 | Mobile Modifier Helper Bar | Dedicated modifier keys (Ctrl, Alt, Win, Esc, F1-F12) with 3-state latching FSM | M3 | survey |
| 20 | Windows Scancode Set 1 Translation | Standard PC scancodes and macro shortcuts (Ctrl+Alt+Del, Alt+Tab) | M3 | survey |
| 21 | 5-Thread Isolation Architecture | Dedicated Socket I/O, Decoder, Choreographer Blitter, Input, and Main threads | M4 | survey |
| 22 | Low-Latency Socket Buffering | `TCP_NODELAY`, 128KB rx / 64KB tx buffers, FastPath PDU prioritization | M4 | survey |
| 23 | Atomic Single-Slot Frame Pacer | Drop stale frames, blit latest frame on VSYNC to minimize input-to-display latency | M4 | survey |
| 24 | Bandwidth & Battery Performance Modes | Ultra-Low Latency, Balanced Mobile, Data Saver, and Battery Saver presets | M4 | survey |
| 25 | Dynamic Orientation & Multi-Window | Responds to foldables, split-screen, and screen rotation without session tear | M4 | survey |
| 26 | Real-Time Diagnostic Telemetry | Ring buffer tracking RTT latency, FPS, frame delivery jitter, connection state | M4 | survey |
| 27 | Auto-Reconnect State Machine | Exponential backoff with full jitter and 5-step leak-free native teardown | M4 | survey |
| 28 | End-to-End Test Suite Verification | 100% passing automated test suite in `./gradlew testDebugUnitTest` and valid APK | M5 | survey |

## Milestones
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| M1 | Build & Core RDP Engine | Gradle setup, SDK config, `:core-rdp` module, `IRdpEngine`, `LibFreeRDP` bridge, `MockRdpEngine`, MS-RDPEDISP, MS-RDPECLIP | none | DONE |
| M2 | Floating Mouse & Gestures | `:feature-mouse` module, floating overlay FSM, mouse events, touchpad mode, gesture engine, anti-spurious click latch, orientation persistence | M1 | READY |
| M3 | Profiles, Security & Modifiers | `:feature-session` module, Material 3 UI, Profile CRUD, Keystore encryption vault, quick-action toolbar, modifier keys helper bar | M1 | READY |
| M4 | Low-Latency Pipeline & Telemetry | `:feature-telemetry` module, 5-thread isolation, socket tuning, single-slot frame pacer, performance presets, telemetry engine, auto-reconnect FSM | M1 | READY |
| M5 | Integration & Acceptance | `:app` integration, multi-tier E2E testing pass (Tiers 1-4), Tier 5 adversarial hardening, assembleDebug & testDebugUnitTest verification | M1, M2, M3, M4 | PENDING |

## Interface Contracts

### 1. `IRdpEngine` (`com.freerdp.core.engine.IRdpEngine`)
```kotlin
package com.freerdp.core.engine

import android.graphics.Bitmap
import kotlinx.coroutines.flow.StateFlow

interface IRdpEngine {
    val connectionState: StateFlow<RdpConnectionState>
    val sessionMetrics: StateFlow<RdpSessionMetrics>

    suspend fun connect(config: RdpConnectionConfig): Boolean
    suspend fun disconnect()
    fun sendPointerEvent(flags: Int, x: Int, y: Int)
    fun sendKeyEvent(keyCode: Int, down: Boolean)
    fun sendUnicodeKeyEvent(unicodeChar: Char, down: Boolean)
    fun updateResolution(width: Int, height: Int, physicalWidthMm: Int, physicalHeightMm: Int, orientation: Int)
    fun sendClipboardText(text: String)
    fun setEventListener(listener: RdpEventListener?)
}
```

### 2. `RdpEventListener` (`com.freerdp.core.engine.RdpEventListener`)
```kotlin
package com.freerdp.core.engine

import android.graphics.Bitmap

interface RdpEventListener {
    fun onConnectionSuccess()
    fun onConnectionFailure(errorCode: Int, message: String)
    fun onDisconnected()
    fun onGraphicsUpdate(bitmap: Bitmap, x: Int, y: Int, width: Int, height: Int)
    fun onResolutionChanged(width: Int, height: Int)
    fun onClipboardDataReceived(format: Int, data: ByteArray)
    fun onCertificateVerification(fingerprint: String, host: String): Boolean
}
```

### 3. `MouseController` (`com.freerdp.feature.mouse.MouseController`)
```kotlin
package com.freerdp.feature.mouse

import com.freerdp.core.engine.IRdpEngine

interface MouseController {
    fun handleLeftClick(screenX: Float, screenY: Float)
    fun handleRightClick(screenX: Float, screenY: Float)
    fun handleDoubleClick(screenX: Float, screenY: Float)
    fun handleDragStart(screenX: Float, screenY: Float)
    fun handleDragMove(screenX: Float, screenY: Float)
    fun handleDragEnd(screenX: Float, screenY: Float)
    fun handleScroll(screenX: Float, screenY: Float, deltaY: Float)
    fun setTouchpadMode(enabled: Boolean)
    fun setCursorVisible(visible: Boolean)
}
```

### 4. `CredentialStore` (`com.freerdp.feature.session.security.CredentialStore`)
```kotlin
package com.freerdp.feature.session.security

interface CredentialStore {
    fun saveSecret(profileId: String, secret: CharArray)
    fun getSecret(profileId: String): CharArray?
    fun deleteSecret(profileId: String)
    fun clearAll()
}
```

### 5. `AutoReconnectManager` (`com.freerdp.feature.telemetry.reconnect.AutoReconnectManager`)
```kotlin
package com.freerdp.feature.telemetry.reconnect

import kotlinx.coroutines.flow.StateFlow

interface AutoReconnectManager {
    val reconnectState: StateFlow<ReconnectState>
    fun onNetworkLost()
    fun onNetworkAvailable()
    fun onSessionDropped(reason: String)
    fun onUserPause()
    fun onUserResume()
    fun cancelReconnect()
}
```

## Code Layout
- `settings.gradle.kts`: Root multi-project definition (`:app`, `:core-rdp`, `:feature-mouse`, `:feature-session`, `:feature-telemetry`).
- `build.gradle.kts`: Root Gradle build script with plugin management.
- `gradle.properties`: Build performance options and `org.gradle.java.home`.
- `local.properties`: `sdk.dir` definition.
- `core-rdp/`:
  - `src/main/java/com/freerdp/core/engine/`: `IRdpEngine`, `LibFreeRDP`, `NativeFreeRdpEngine`, `MockRdpEngine`, `RdpConnectionConfig`, `RdpEventListener`.
  - `src/main/java/com/freerdp/core/protocol/`: MS-RDPBCGR pointer flags, MS-RDPEDISP, MS-RDPECLIP, Windows Scancode Set 1.
  - `src/test/java/com/freerdp/core/`: Unit tests for engine contracts, mock behavior, and protocol flags.
- `feature-mouse/`:
  - `src/main/java/com/freerdp/feature/mouse/`: Floating mouse overlay view, gesture detector, anti-spurious click latch, affine transformer, orientation persistence.
  - `src/test/java/com/freerdp/feature/mouse/`: Unit tests for gestures, coordinate clamping, and mouse event generation.
- `feature-session/`:
  - `src/main/java/com/freerdp/feature/session/`: Profile manager, Material 3 UI, Jetpack Keystore `CredentialStore`, modifier keys bar FSM.
  - `src/test/java/com/freerdp/feature/session/`: Robolectric tests for EncryptedSharedPreferences and profile CRUD.
- `feature-telemetry/`:
  - `src/main/java/com/freerdp/feature/telemetry/`: Auto-reconnect FSM, low-latency socket buffer config, single-slot frame pacer, telemetry ring buffer.
  - `src/test/java/com/freerdp/feature/telemetry/`: Unit tests for reconnect backoff, thread isolation, and telemetry metrics.
- `app/`:
  - `src/main/java/com/freerdp/client/`: Application entry point, `AppContainer`, `MainActivity`, remote session canvas viewer.
  - `src/test/java/com/freerdp/client/`: Comprehensive 4-tier E2E opaque-box test harness.
