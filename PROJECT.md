# Project: Android FreeRDP Mobile Client

## Architecture
Mobile-first Android Remote Desktop client built on FreeRDP core, designed with Clean Architecture and strict modular isolation.

```
+--------------------------------------------------------------------------------------+
|                                        :app                                           |
|  ALL Compose Material 3 product UI lives HERE (feature modules hold logic only):      |
|  - Profile List / Profile Editor / Settings screens                                   |
|  - Session Screen: remote-desktop canvas, cert-trust dialog, reconnect chip           |
|  - Telemetry HUD (FPS/RTT/jitter), quick toolbar & modifier bar rendering             |
|  - AppContainer DI & Lifecycle Coordination                                           |
+--------------------+---------------------------+--------------------------+----------+
                     |                           |                          |
+--------------------v---+             +---------v------------+             +----------+
|    :feature-mouse      |             |  :feature-session    |             v
|  (overlay View + input |             |  LOGIC ONLY — no     |   +---------------------+
|   logic; no Compose)   |             |  Compose UI here)    |   | :feature-telemetry  |
|  - Floating Mouse      |             |  - Profile CRUD      |   |  - Auto-Reconnect   |
|    Overlay FSM         |             |    Repository (JSON) |   |    State Machine    |
|  - Gesture             |             |  - Keystore          |   |    (Exp. Backoff +  |
|    Disambiguation      |             |    CredentialStore   |   |     Jitter)         |
|    Engine              |             |  - Quick-Action      |   |  - Low-Latency      |
|  - Affine Coordinate   |             |    Toolbar FSM (4s)  |   |    Socket Config &  |
|    Transformer         |             |  - Modifier Bar FSM  |   |    TCP_NODELAY      |
|  - MouseController     |             |    (3-state latch)   |   |  - Single-Slot      |
|  - Overlay coordinate  |             |  - Scancode          |   |    Frame Pacer &    |
|    persistence         |             |    Translator        |   |    Thread Isolation |
+------------+----------+             +----------+-----------+   |  - Performance      |
             |                                   |               |    Presets (4)      |
             |                                   |               |  - Telemetry Ring   |
             |                                   |               |    Buffer (FPS/RTT/ |
             |                                   |               |    Jitter)          |
             |                                   |               +----------+----------+
             |                                   |                          |
             +-------------------+---------------+--------------------------+
                                 |
                     +-----------v------------+
                     |       :core-rdp        |
                     |  - IRdpEngine & RdpEventListener Contracts
                     |  - NativeFreeRdpEngine (JNI LibFreeRDP wrapper,
                     |    AtomicLong pointer safety)
                     |  - LibFreeRDP JNI bridge (confined to this module)
                     |  - MockRdpEngine (deterministic JVM test double)
                     |  - MS-RDPBCGR flags, MS-RDPEDISP, MS-RDPECLIP handlers
                     +------------------------+
```
> Dependency rule: `:app` → feature modules → `:core-rdp`. No feature module depends on another
> feature module; nothing outside `:core-rdp` touches JNI. `:feature-session` and
> `:feature-telemetry` are **pure logic** (no Compose) so they stay unit-testable on the JVM.

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
| 15 | Material 3 UI & Navigation | Modern Android Material 3 theme, dark/light palette, edge-to-edge support — all Compose screens are implemented in `:app` (feature modules are logic-only) | M5 | survey |
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

> **Status legend & final snapshot (2026-09-23, UTC+05:30):** statuses are evidence-based,
> sourced from `*/build/test-results/testDebugUnitTest/TEST-*.xml` (re-parsed by `acceptance_w2`
> at close-out) and `.agents/*/handoff.md`. `DONE (gate)` = implementation + tests complete,
> wave-2 independent re-run green, and final full-project gate green. Failing evidence is called
> out explicitly; none remains.

| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| M1 | Build & Core RDP Engine | Gradle setup, SDK config, `:core-rdp` module, `IRdpEngine`, `LibFreeRDP` bridge, `MockRdpEngine`, MS-RDPEDISP, MS-RDPECLIP | none | **DONE (gate)** — independent audit verdict CLEAN; final `:core-rdp` **95/95 tests green** (10 suites, parsed at close-out); APK forensically verified |
| M2 | Floating Mouse & Gestures | `:feature-mouse` module, floating overlay FSM, mouse events, touchpad mode, gesture engine, anti-spurious click latch, orientation persistence | M1 | **DONE (gate)** — wave-2 verified: `reviewer_w2` fresh 32/32 twice + line-by-line review; final **35/35 tests green** (4 suites; +3 fixer regression tests) |
| M3 | Profiles, Security & Modifiers | `:feature-session` **logic module**: Profile CRUD repository, Keystore encryption vault, quick-action toolbar FSM, modifier keys FSM, scancode translator (Material 3 UI lives in `:app`, delivered under M5) | M1 | **DONE (gate)** — wave-2 verified by `reviewer_w2`/`integrator_w2`; final **86/86 tests green** (5 suites; +4 hardening tests: vault key-custody ×2, IV-uniqueness, toolbar generation guard) |
| M4 | Low-Latency Pipeline & Telemetry | `:feature-telemetry` module, 5-thread isolation, socket tuning, single-slot frame pacer, performance presets, telemetry engine, auto-reconnect FSM | M1 | **DONE (gate)** — final **120/120 tests green** (10 suites). The earlier 25/33 failing snapshot (`baseline_build.log` 2026-09-23 01:19) is **superseded**: fixed by `latency_perf`/wave-1 and re-verified in every subsequent gate |
| M5 | Integration & Acceptance | `:app` Material 3 product UI, feature-module integration, native FreeRDP `.so` packaging, 4-tier E2E suite creation + run (Tiers 1-4), Tier 5 adversarial hardening, final `assembleDebug` & `testDebugUnitTest` gates | M1, M2, M3, M4 | **DONE (gate)** — full `testDebugUnitTest` **497/497 green ×2** (44 suites; 74 e2e tests present & green); `assembleDebug` → `app\build\outputs\apk\debug\app-debug.apk` **17,205,283 B @ 2026-09-23 04:43:23**; reviewer APPROVE-WITH-FINDINGS (MAJORs fixed), challenger CHALLENGE-PASS; native `.so` packaging = documented infeasible-on-this-machine with verified graceful 1001 UX (`.agents/native_finish/handoff.md` §3); `FreeRdpFlagMapping`→`:core-rdp` consumption remains a documented follow-up (dependency cycle) |

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
  - `src/main/java/com/freerdp/feature/session/`: **logic only** — atomic-JSON profile repository, Jetpack Keystore `CredentialStore`, quick-action toolbar FSM, modifier keys bar FSM, scancode translator. (Material 3 screens live in `:app`; no Compose in this module.)
  - `src/test/java/com/freerdp/feature/session/`: Robolectric tests for EncryptedSharedPreferences and profile CRUD.
- `feature-telemetry/`:
  - `src/main/java/com/freerdp/feature/telemetry/`: Auto-reconnect FSM, low-latency socket buffer config, single-slot frame pacer, telemetry ring buffer.
  - `src/test/java/com/freerdp/feature/telemetry/`: Unit tests for reconnect backoff, thread isolation, and telemetry metrics.
- `app/`:
  - `src/main/java/com/freerdp/client/`: Application entry point, `AppContainer`, `MainActivity` — Compose Material 3 product UI (profile list/editor, settings, session screen), **complete** as of the final gate (161 tests green).
  - `src/test/java/com/freerdp/client/e2e/`: 4-tier E2E opaque-box test harness per `TEST_INFRA.md` — **present and green** (Tier1 35, Tier2 26, Tier3 8, Tier4 5 = 74 tests at final gate).
