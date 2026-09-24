# Original User Request

## Initial Request — 2026-09-22T15:08:29Z

Build a production-quality, mobile-first Android RDP client using FreeRDP Android as the core RDP engine, optimized for one-handed phone and tablet use with an adaptive floating mouse and gesture system. Crucially, the connection must be optimized for ultra-low latency, smooth real-time rendering, and minimal lag.

Working directory: C:\Users\Administrator\teamwork_projects\android_rdp_client
Integrity mode: development

## Requirements

### R1. Core Remote Desktop Foundation
Integrate FreeRDP Android native components to establish reliable, standards-compliant RDP connectivity supporting NLA, TLS, dynamic resolution resizing, and clipboard synchronization. Keep upstream FreeRDP core bindings modular and isolated for future upgrades.

### R2. Mobile-First Floating Mouse & Touch System
Implement a repositionable, collapsible floating mouse overlay (supporting left/right click, wheel/scroll, double-click, click-and-drag, long-press, touchpad mode, and optional cursor) and touch gestures (pinch-to-zoom, pan-without-clicking) that adapt smoothly across portrait and landscape orientations without permanently obscuring the remote display.

### R3. Mobile Productivity & Session Management
Provide a Material 3 interface featuring one-tap profile connections, auto-reconnect on network/sleep changes, secure Android Keystore credential storage, a collapsible quick-action toolbar, and dedicated mobile modifier keys (Ctrl, Alt, Esc, Win, Function keys).

### R4. Adaptive Low-Latency Performance & Telemetry
Implement low-latency socket buffering, background thread isolation for frame decoding/rendering, bandwidth- and battery-aware performance modes (Wi-Fi vs mobile data presets), dynamic orientation and multi-window/foldable support, and session diagnostic telemetry (latency, FPS, connection state). Ensure connection streaming minimizes input lag and frame stutter.

## Acceptance Criteria

### Build & Compilation
- [ ] `./gradlew assembleDebug` completes with zero errors and outputs a valid debug APK.
- [ ] FreeRDP native bindings/AAR dependency is correctly linked and isolated in a dedicated module/package.

### Gesture & Mouse Control State Machine
- [ ] Automated unit tests verify all floating mouse overlay events: left-click, right-click, double-click, drag, scroll, and touchpad mode.
- [ ] Touch gesture tests verify pan and pinch-to-zoom operations without spurious touch-to-click triggers.
- [ ] Mouse overlay persists repositioned coordinates and responds to screen orientation switches without resetting or going off-screen.

### Session, Low-Latency & Security Management
- [ ] Connection profile manager supports creating, reading, updating, and deleting server configurations.
- [ ] Credentials and sensitive server tokens are encrypted via Android Keystore / EncryptedSharedPreferences with automated unit test validation.
- [ ] Auto-reconnect state machine transitions cleanly through network drop, pause/resume, and reconnect phases without crashing or leaking native RDP sessions.
- [ ] Network buffering, frame rate pacing, and rendering dispatch loop are configured to prioritize low-latency interactivity and report real-time ping/latency stats.

### Automated Test Suite
- [ ] `./gradlew testDebugUnitTest` runs all unit and Robolectric tests and passes with 100% success rate.

## Follow-up — 2026-09-23T13:46:41Z

Diagnose and resolve all stability, crash, and rendering bugs in the Android RDP client repository (`android_rdp_client`), verify interactive RDP streaming on the local emulator without freezes or disconnects, build a clean working APK, and push the updated code to GitHub.

Working directory: C:\Users\Administrator\teamwork_projects\android_rdp_client
Integrity mode: development

## Requirements

### R1. Engine Stability & Lifecycle Bug Fixes
Resolve all connection hangs, certificate verification (TOFU) timeout races, and native JNI / FreeRDP lifecycle crashes (such as use-after-free and SIGSEGV during abort, timeout, or disconnect). Ensure clean cancellation and thread synchronization between native FreeRDP callbacks and coroutines.

### R2. Interactive Remote Desktop Streaming & Input
Ensure that after connecting and completing authentication, the RDP session reliably negotiates capabilities and streams real-time remote desktop frames without freezing or dropping into a permanent black screen. Touch gestures and floating mouse controls must reliably transmit mouse events to the remote session.

### R3. Regression Testing & In-Tree Verification
Maintain 100% pass rate across all existing unit, integration, and Robolectric suites (≥ 497 tests). Add regression tests verifying the resolved lifecycle race conditions and timeout behaviors. Verify the end-to-end connection flow against the host RDP endpoint (`10.0.2.2:3389`) using the running emulator (`emulator-5554`).

### R4. APK Build & Remote Repository Push
Build a functional debug APK in `app/build/outputs/apk/debug/app-debug.apk`. Commit all source fixes and tests with concise, descriptive commit messages, and push the changes directly to `origin/main` on GitHub (`https://github.com/Vicky8106/android_rdp_client.git`).

## Verification Resources
- Running Android emulator: `emulator-5554` (accessible via `C:\Android\Sdk\platform-tools\adb.exe`)
- Host test RDP service: `10.0.2.2:3389` (user `rdpdemo`, password `Sup3rdemo!23`)
- Existing test suite command: `.\gradlew.bat testDebugUnitTest`
- Build command: `.\gradlew.bat assembleDebug`
- Git push check: `git push origin main`

## Acceptance Criteria

### Stability & Lifecycle
- [ ] Connecting to an RDP host and interacting with certificate verification (TOFU dialog) does not trigger native crashes (SIGSEGV), process termination, or unhandled exceptions even if user input is delayed.
- [ ] Active sessions disconnect cleanly without leaking native thread contexts or causing use-after-free crashes in `freerdp_*` instances.

### Session Streaming & Interaction
- [ ] Remote desktop surface renders live graphical updates from the remote host without freezing or remaining blank.
- [ ] Input interactions (clicks, movement, keyboard events) are properly received and processed by the remote session.

### Test & Build Integrity
- [ ] `.\gradlew.bat testDebugUnitTest` completes with 0 failures, 0 errors across all modules.
- [ ] `.\gradlew.bat assembleDebug` succeeds and produces a valid APK.

### GitHub Delivery
- [ ] All bug fixes and tests are committed cleanly.
- [ ] `git push origin main` succeeds and synchronizes the local repository with GitHub `origin/main`.

## Follow-up — 2026-09-23T15:26:01Z

This is a single self-contained fix; keep it small and focused.

Finalize packaging for the Android RDP Client project (`android_rdp_client`) by synchronizing the release APK with the latest stability and frame-rendering build, cleaning up untracked workspace artifacts, verifying the full test suite, and pushing to GitHub `origin/main`.

Working directory: C:\Users\Administrator\teamwork_projects\android_rdp_client
Integrity mode: development

## Requirements

### R1. Release APK Synchronization
Synchronize `releases/app-debug.apk` with the latest working debug build from `app/build/outputs/apk/debug/app-debug.apk` containing the FreeRDP native libraries and stability fixes. Ensure the binary size remains strictly under GitHub's 100 MB upload limit.

### R2. Test Suite & Build Verification
Verify that the complete unit and Robolectric test suite (`.\gradlew.bat testDebugUnitTest`) passes with 100% success rate (505 tests, 0 failures, 0 errors). Ensure `.\gradlew.bat assembleDebug` builds without errors.

### R3. Repository Cleanup & Git Push
Clean up untracked temporary agent directories (`.agents/auditor_gen3_1/`, etc.) and scratch files so the working tree is clean. Commit all release assets and documentation updates with a clear, descriptive commit message, and push the commit to `origin/main` (`https://github.com/Vicky8106/android_rdp_client.git`).

## Verification Resources
- Test suite command: `.\gradlew.bat testDebugUnitTest`
- Build command: `.\gradlew.bat assembleDebug`
- Target remote repository: `https://github.com/Vicky8106/android_rdp_client.git` (branch `main`)
- Verification checks:
  - `git status` reports working tree clean
  - `git push origin main` succeeds
  - `git ls-remote origin main` matches local `HEAD`

## Acceptance Criteria

### Packaging & Build
- [ ] `releases/app-debug.apk` exists, matches the latest `app/build/outputs/apk/debug/app-debug.apk` (hash/size match), and is under 100 MB.
- [ ] `.\gradlew.bat assembleDebug` succeeds.

### Automated Tests
- [ ] `.\gradlew.bat testDebugUnitTest` passes with 0 failures and 0 errors across all 5 modules (505 tests).

### Repository & Push
- [ ] Working tree has no untracked scratch artifacts or lingering temporary agent directories.
- [ ] All changes are committed and pushed to `origin/main`.
- [ ] `git ls-remote origin main` confirms the remote tip equals local `HEAD`.

## Follow-up — 2026-09-24T11:21:05Z

Port the mature in-session user experience, virtual keys, touch/mouse pointer modes, and keyboard input handling from the local VNC repository (`C:\Users\Administrator\avnc`) into the Android RDP client (`C:\Users\Administrator\teamwork_projects\android_rdp_client`), resolving all interaction, streaming, and stability bugs.

Working directory: C:\Users\Administrator\teamwork_projects\android_rdp_client
Integrity mode: development

## Reference Resources
- Local reference VNC codebase: `C:\Users\Administrator\avnc`
  - Session layout & toolbar: `app/src/main/java/com/vncandroid/free/ui/vnc/Toolbar.kt`, `VncActivity.kt`, `LayoutManager.kt`
  - Virtual keys: `app/src/main/java/com/vncandroid/free/ui/vnc/VirtualKeysCompose.kt`, `VirtualKeys.kt`
  - Virtual mouse & gestures: `app/src/main/java/com/vncandroid/free/ui/vnc/VirtualMouseCompose.kt`, `input/TouchHandler.kt`, `input/PointerModes.kt`
  - Keyboard & BMC hold timing: `app/src/main/java/com/vncandroid/free/ui/vnc/input/KeyHandler.kt`, `util/Keyboard.kt`
- Target RDP codebase: `C:\Users\Administrator\teamwork_projects\android_rdp_client`
  - Session UI: `app/src/main/java/com/freerdp/client/ui/session/SessionScreen.kt`, `RemoteCanvasView.kt`
  - Mouse & overlay: `feature-mouse/src/main/java/com/freerdp/feature/mouse/`
  - Keyboard & scancodes: `feature-session/src/main/java/com/freerdp/feature/session/keyboard/ScancodeTranslator.kt`

## Requirements

### R1. Collapsible In-Session Toolbar & Navigation
Adopt the `avnc` collapsible session toolbar and layout pattern in `android_rdp_client`. Provide direct, accessible controls for toggling the Android soft keyboard, switching mouse/input mode (Touchpad mode vs. Direct Touch), toggling the virtual keys bar, switching screen scale/fit modes, and executing clean session disconnection.

### R2. RealVNC-Style Virtual Keys Bar & Soft Keyboard Timing
Port the Compose virtual keys system from `avnc` (`VirtualKeysCompose.kt`) with full RealVNC ergonomics: collapsible Fn strip (F1–F12), sticky modifier keys (Ctrl, Alt, Shift, Super/Windows), Esc, Tab, Delete, and an inverted-T arrow cluster. Integrate `avnc`'s keyboard input handling (`KeyHandler.kt`), including BMC key-press hold timing for Android soft-keyboard Enter, Backspace, Space, Tab, and special keys, properly translated into RDP keyboard scancodes.

### R3. Multi-Mode Touch, Touchpad & Virtual Mouse Controls
Port the input engine from `avnc` (`TouchHandler.kt`, `PointerModes.kt`, `VirtualMouseCompose.kt`) to support:
1. **Direct Touch Mode**: Direct tap-to-click at touch coordinates, two-finger scroll/pan, and pinch-to-zoom.
2. **Touchpad / Mouse Pointer Mode**: Relative cursor movement with acceleration, dedicated left, middle, and right click controls, drag lock, and smooth scrolling controls.
Ensure all coordinate transformations and pointer events map accurately to the FreeRDP native protocol layer.

### R4. Test Suite, Build Verification & Release Packaging
Update and expand unit and Robolectric tests across `:feature-mouse`, `:feature-session`, and `:app` to cover the ported virtual keys, scancode conversions, keyboard timing, and mouse modes. Maintain 100% test pass rate on `.\gradlew.bat testDebugUnitTest` and assemble a verified, functional debug APK under 100 MB in `releases/app-debug.apk`.

## Verification Resources
- Test suite command: `.\gradlew.bat testDebugUnitTest`
- Build command: `.\gradlew.bat assembleDebug`
- Running Android emulator: `emulator-5554` (via `C:\Android\Sdk\platform-tools\adb.exe`)
- Host test RDP service: `10.0.2.2:3389` (user `rdpdemo`, password `Sup3rdemo!23`)
- Output release APK: `releases/app-debug.apk`

## Acceptance Criteria

### Toolbar & In-Session UX
- [ ] In-session toolbar smoothly expands and collapses without obscuring the desktop canvas unnecessarily.
- [ ] Toolbar controls for keyboard toggle, input mode switch (touchpad vs. direct touch), virtual keys bar toggle, zoom/fit, and disconnect function responsively.

### Virtual Keys & Keyboard Input
- [ ] Virtual keys bar renders RealVNC-style layout (Fn bar, sticky Ctrl/Alt/Shift/Win modifiers, Esc, Tab, Delete, inverted-T arrow keys) and transmits correct RDP key events.
- [ ] Soft keyboard input (letters, numbers, Enter, Backspace, Space, Tab) dispatches with proper key hold timing and functions reliably in remote text fields.

### Mouse & Touch Interaction
- [ ] Direct Touch mode accurately executes clicks and drags at the touch point.
- [ ] Touchpad mode provides smooth cursor movement with acceleration, dedicated mouse buttons (left, right, middle), and scroll controls.

### Build & Test Integrity
- [ ] `.\gradlew.bat testDebugUnitTest` passes 100% with 0 failures and 0 errors across all modules.
- [ ] `.\gradlew.bat assembleDebug` builds successfully and produces a signed APK in `releases/app-debug.apk` under 100 MB.

## Follow-up — 2026-09-24T15:53:26Z

Complete the Android RDP Client UX and input port from the local reference VNC client (`C:\Users\Administrator\avnc`) into `C:\Users\Administrator\teamwork_projects\android_rdp_client`: build the collapsible in-session toolbar drawer, wire all Compose overlays (toolbar, virtual keys, virtual mouse, pointer modes) into `SessionScreen` and `RemoteCanvasView`, verify that all 664+ tests pass with zero failures, and package the updated debug APK in `releases/` under 100 MB.

Working directory: C:\Users\Administrator\teamwork_projects\android_rdp_client
Integrity mode: development

## Reference Resources
- Local reference VNC codebase: `C:\Users\Administrator\avnc`
  - Toolbar & drawer: `app/src/main/java/com/vncandroid/free/ui/vnc/Toolbar.kt`, `VncActivity.kt`, `LayoutManager.kt`
  - Virtual keys: `app/src/main/java/com/vncandroid/free/ui/vnc/VirtualKeysCompose.kt`, `VirtualKeys.kt`
  - Virtual mouse & pointer modes: `app/src/main/java/com/vncandroid/free/ui/vnc/VirtualMouseCompose.kt`, `input/TouchHandler.kt`, `input/PointerModes.kt`
  - Keyboard timing: `app/src/main/java/com/vncandroid/free/ui/vnc/input/KeyHandler.kt`
- Target RDP codebase: `C:\Users\Administrator\teamwork_projects\android_rdp_client`
  - Session UI: `app/src/main/java/com/freerdp/client/ui/session/SessionScreen.kt`, `RemoteCanvasView.kt`
  - Mouse engine: `feature-mouse/src/main/java/com/freerdp/feature/mouse/`
  - Session & keyboard engine: `feature-session/src/main/java/com/freerdp/feature/session/keyboard/`

## Requirements

### R1. Collapsible In-Session Toolbar & Floating Opener
Implement the collapsible session toolbar drawer and floating draggable opener button in `SessionScreen.kt`. Support transparent scrim dismissal without spurious canvas clicks, Android 10+ system gesture exclusion zones, persistent opener vertical bias across sessions, and quick actions: soft keyboard toggle, pointer mode switch (Direct Touch vs Touchpad), virtual keys bar toggle, display scale/fit, and clean session disconnect.

### R2. Active Session Overlay Integration & Wiring
Wire `InSessionToolbar`, `VirtualKeysCompose` (collapsible Fn strip F1–F12, sticky modifiers Ctrl/Alt/Shift/Win, inverted-T arrow pad), and `VirtualMouseCompose` (draggable FAB, expandable pill with LMB/MMB/RMB, hold-to-repeat scroll pillar) into `SessionScreen.kt` and `RemoteCanvasView.kt`. Connect `PointerModes` (Direct Touch with letterbox edge coercion, Touchpad mode with 3-tier libinput acceleration) and `KeyboardTimingManager` (50ms BMC key hold, 25ms text pacing) directly to the active FreeRDP session engine.

### R3. Test Suite Pass & Regression Verification
Maintain a 100% pass rate across the full automated test suite across all 5 modules (`:app`, `:core-rdp`, `:feature-mouse`, `:feature-session`, `:feature-telemetry`), including all unit, Robolectric, and 126 opaque-box UX/Input E2E tests in `app/src/test/java/com/freerdp/client/e2e/uxinput/`, verifying ≥ 664 passing tests with 0 failures and 0 errors.

### R4. Release APK Assembly & Verification
Build a functional debug APK via `.\gradlew.bat assembleDebug`, synchronize `releases/app-debug.apk` with `app/build/outputs/apk/debug/app-debug.apk`, verify matching SHA-256 checksums, and ensure the resulting APK binary is strictly under 100 MB.

## Acceptance Criteria

### Toolbar & In-Session UX
- [ ] In-session toolbar drawer smoothly expands and collapses via floating opener or scrim tap without triggering canvas click events.
- [ ] Draggable floating opener persists and restores vertical bias between sessions.
- [ ] Quick action controls for soft keyboard toggle, pointer mode switch, virtual keys toggle, zoom/fit reset, and session disconnect work cleanly.

### Virtual Keys & Keyboard Timing
- [ ] RealVNC virtual keys bar renders correctly, handles sticky/locked modifier states, and dispatches Windows PC Scancode Set 1 codes to the RDP engine.
- [ ] Soft keyboard text streaming and Enter/Backspace/Tab keys respect 50ms BMC hold timing and 25ms pacing.

### Pointer & Touch Modes
- [ ] Direct Touch mode executes clicks and drags at touch coordinates with letterbox edge coercion.
- [ ] Touchpad mode provides smooth cursor movement with 3-tier physical acceleration and supports left, right, and middle mouse clicks (BUTTON3).
- [ ] Virtual mouse overlay allows relative mouse interaction, middle click, drag lock, and hold-to-repeat scrolling.

### Build & Test Integrity
- [ ] `.\gradlew.bat testDebugUnitTest` completes with 100% pass rate (0 failures, 0 errors, ≥ 664 tests).
- [ ] `.\gradlew.bat assembleDebug` succeeds and `releases/app-debug.apk` matches the generated APK and is under 100 MB.

