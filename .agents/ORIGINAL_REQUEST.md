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
