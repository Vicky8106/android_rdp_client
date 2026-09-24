## 2026-09-24T16:05:07Z
You are worker_1 (UI & Session Integration Specialist).
Working directory: C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\teamwork\worker_1
Authoritative user request: C:\Users\Administrator\teamwork_projects\android_rdp_client\ORIGINAL_REQUEST.md (MUST READ FIRST)
Project spec: C:\Users\Administrator\teamwork_projects\android_rdp_client\PROJECT.md

MANDATORY INTEGRITY WARNING:
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A teamwork_preview_auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

CONTEXT & INPUT ARTIFACTS:
Read the handoff reports from the exploration phase:
1. C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\teamwork\teamwork_preview_explorer_1\handoff.md
2. C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\teamwork\teamwork_preview_explorer_2\handoff.md
3. C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\teamwork\teamwork_preview_spec_miner_1\handoff.md
4. C:\Users\Administrator\teamwork_projects\android_rdp_client\TEST_READY.md

TASKS:
Fulfill Requirements R1 and R2 cleanly and robustly without breaking existing tests:

1. R1: Implement InSessionToolbar in app/src/main/java/com/freerdp/client/ui/session/InSessionToolbar.kt:
   - Collapsible in-session toolbar drawer (docked at start or end, animated slide-in/out).
   - Floating draggable opener button with persistent vertical bias across sessions (SharedPreferences key `toolbarOpenerBtnVerticalBias`).
   - Transparent scrim dismissal layer: taps outside the drawer dismiss the drawer without forwarding touch/click events to the remote desktop canvas underneath.
   - Android 10+ (API 29+) system gesture exclusion zones with 1/6th height padding formula `(parentHeight - toolbarHeight) / 6`.
   - 5 Quick Action controls:
     a. Soft keyboard toggle
     b. Pointer mode switch (Direct Touch vs Touchpad mode)
     c. Virtual keys bar toggle
     d. Display scale/fit toggle (fit to screen / reset zoom)
     e. Clean session disconnect button

2. R2: Active Session Overlay Integration & Wiring in SessionScreen.kt and RemoteCanvasView.kt:
   - Wire `InSessionToolbar` (collapsible drawer and floating draggable opener) into `SessionScreen.kt`.
   - Wire `VirtualKeysCompose` (`VirtualKeysOverlay` from `VirtualKeysCompose.kt`) into `SessionScreen.kt` replacing the legacy `ModifierBar`. Ensure it connects to `ModifierStateMachine` and `KeyboardTimingManager`.
   - Wire `VirtualMouseCompose` (`VirtualMouseOverlay` from `VirtualMouseCompose.kt`) into `SessionScreen.kt` replacing legacy `FloatingMouseOverlayView`. Connect LMB (with hold & drag), MMB (Middle click / BUTTON3), RMB, scroll repeat pillar, and drag lock directly to `MouseController`.
   - In `RemoteCanvasView.kt`:
     - Direct Touch mode: Apply letterbox edge coercion (`coerceToFbEdgeDesktop`) on taps and drags so touches in letterbox margins clamp to framebuffer borders.
     - Touchpad mode: Ensure `handleTouchpadMove(deltaX, deltaY, accelerate = true)` activates the 3-tier libinput acceleration curve with zoom dampening.
   - In `SessionViewModel.kt` / `RemoteKeyboardField`: Wire `DefaultKeyboardTimingManager` for 50ms BMC key hold timing and 25ms text streaming pacing on soft keyboard input.

3. Build and Test Verification:
   - Run `.\gradlew.bat :app:testDebugUnitTest`
   - Run `.\gradlew.bat testDebugUnitTest` across all 5 modules (:app, :core-rdp, :feature-mouse, :feature-session, :feature-telemetry)
   - Ensure 100% pass rate (0 failures, 0 errors across >= 664 tests).

Write your completion report to `C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\teamwork\worker_1\handoff.md` and message parent when finished.
