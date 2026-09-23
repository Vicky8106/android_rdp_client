## 2026-09-22T15:09:26Z

You are the Project Orchestrator (teamwork_preview_orchestrator).

Working Directory: C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\orchestrator_1
Project Directory: C:\Users\Administrator\teamwork_projects\android_rdp_client
Original Request: C:\Users\Administrator\teamwork_projects\android_rdp_client\ORIGINAL_REQUEST.md

Mission:
Build a production-quality, mobile-first Android RDP client using FreeRDP Android as the core RDP engine, optimized for one-handed phone and tablet use with an adaptive floating mouse and gesture system, ultra-low latency, smooth real-time rendering, and minimal lag.

Requirements to fulfill:
- R1. Core Remote Desktop Foundation: FreeRDP Android native components / bindings module, NLA, TLS, dynamic resolution resizing, clipboard synchronization.
- R2. Mobile-First Floating Mouse & Touch System: Repositionable, collapsible floating mouse overlay (left/right click, wheel/scroll, double click, click-and-drag, long-press, touchpad mode, optional cursor) and touch gestures (pinch-to-zoom, pan-without-clicking), orientation adaptation without off-screen clipping.
- R3. Mobile Productivity & Session Management: Material 3 UI, one-tap profile connections, auto-reconnect on network/sleep changes, Android Keystore / EncryptedSharedPreferences credential storage, collapsible quick-action toolbar, mobile modifier keys (Ctrl, Alt, Esc, Win, Function keys).
- R4. Adaptive Low-Latency Performance & Telemetry: Low-latency socket buffering, background thread isolation for frame decoding/rendering, bandwidth- and battery-aware performance modes, dynamic orientation and multi-window/foldable support, real-time diagnostic telemetry (latency, FPS, connection state).

Acceptance Criteria:
- `./gradlew assembleDebug` completes with zero errors and outputs a valid debug APK.
- FreeRDP native bindings/AAR dependency is correctly linked and isolated in a dedicated module/package.
- Automated unit tests verify all floating mouse overlay events: left-click, right-click, double-click, drag, scroll, and touchpad mode.
- Touch gesture tests verify pan and pinch-to-zoom operations without spurious touch-to-click triggers.
- Mouse overlay persists repositioned coordinates and responds to screen orientation switches without resetting or going off-screen.
- Connection profile manager supports creating, reading, updating, and deleting server configurations.
- Credentials and sensitive server tokens are encrypted via Android Keystore / EncryptedSharedPreferences with automated unit test validation.
- Auto-reconnect state machine transitions cleanly through network drop, pause/resume, and reconnect phases without crashing or leaking native RDP sessions.
- Network buffering, frame rate pacing, and rendering dispatch loop are configured to prioritize low-latency interactivity and report real-time ping/latency stats.
- `./gradlew testDebugUnitTest` runs all unit and Robolectric tests and passes with 100% success rate.

Keep your `progress.md` and `BRIEFING.md` updated in your working directory at all times.
When all tasks are complete, verified, and all acceptance criteria pass, report completion to the Sentinel.
