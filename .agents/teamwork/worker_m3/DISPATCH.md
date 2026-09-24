## 2026-09-24T11:44:03Z

Your working directory is: C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\teamwork\worker_m3
Your identity is: worker_m3 (Worker)
You MUST read C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\teamwork\ORIGINAL_REQUEST.md before starting work.

DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A teamwork_preview_auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

Objective:
Implement Milestone 3: Multi-Mode Touch, Touchpad & Virtual Mouse Controls.

Specification Documents to read:
- C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\teamwork\orchestrator_1\PROJECT.md
- C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\teamwork\teamwork_preview_spec_miner_survey_1\survey_avnc_spec.md
- C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\teamwork\teamwork_preview_spec_miner_survey_3\survey_protocol_bridge.md

Reference implementation in AVNC:
- C:\Users\Administrator\avnc\app\src\main\java\com\vncandroid\free\ui\vnc\VirtualMouseCompose.kt
- C:\Users\Administrator\avnc\app\src\main\java\com\vncandroid\free\ui\vnc\input\TouchHandler.kt
- C:\Users\Administrator\avnc\app\src\main\java\com\vncandroid\free\ui\vnc\input\PointerModes.kt
- C:\Users\Administrator\avnc\app\src\main\java\com\vncandroid\free\ui\vnc\input\PointerAcceleration.kt
- C:\Users\Administrator\avnc\app\src\main\java\com\vncandroid\free\ui\vnc\FrameState.kt

Files you OWN exclusively:
- feature-mouse/src/main/java/com/freerdp/feature/mouse/MouseController.kt
- feature-mouse/src/main/java/com/freerdp/feature/mouse/DefaultMouseController.kt
- feature-mouse/src/main/java/com/freerdp/feature/mouse/PointerModes.kt
- feature-mouse/src/main/java/com/freerdp/feature/mouse/PointerAcceleration.kt
- feature-mouse/src/main/java/com/freerdp/feature/mouse/CoordinateTransformer.kt
- app/src/main/java/com/freerdp/client/ui/session/VirtualMouseCompose.kt
- feature-mouse/src/test/java/com/freerdp/feature/mouse/

Implementation Requirements:
1. MouseController & Middle Click:
   - Add `fun handleMiddleClick(screenX: Float, screenY: Float)` to `MouseController`.
   - In `DefaultMouseController`, implement middle click mapping to `RdpPointerFlags.MIDDLE_BUTTON_DOWN` then `RdpPointerFlags.MIDDLE_BUTTON_UP`.
2. Pointer Modes & Pointer Acceleration:
   - Direct Touch Mode: Direct tap-to-click at touch coordinates (`toFb(p)`), two-finger scroll/pan, pinch-to-zoom, and edge coercion (`coerceToFbEdge`) so users can tap 1-pixel remote taskbars even with letterboxing.
   - Touchpad / Mouse Pointer Mode: Relative cursor tracking, auto-centering viewport, and libinput 3-tier physical pointer acceleration:
     - Velocity conversion via display DPI (mm/s).
     - Tier 1 (< 10 mm/s): deceleration slope 0.07 * v + 0.3.
     - Tier 2 (10 <= v < 80 mm/s): constant 1.0.
     - Tier 3 (v >= 80 mm/s): quadratic speedup 0.0005 * (v^2 / 80) + 1.0, clamped [0.3, 3.5].
     - Zoom dampening when zoomScale > 1.
3. VirtualMouseCompose.kt:
   - Port Compose virtual mouse from AVNC.
   - Floating 56dp FAB expanding into 46dp pill with Left hold-and-drag, Middle click, Scroll Up/Down, Right click, Keyboard toggle, Close.
   - Docked right vertical scroll pillar (52dp) with 46x48dp scroll buttons.
4. Tests & Verification:
   - Run tests via .\gradlew.bat :feature-mouse:testDebugUnitTest and .\gradlew.bat :app:testDebugUnitTest.
   - Verify 100% test pass rate.

Output Requirements:
- Write handoff.md in your working directory C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\teamwork\worker_m3\handoff.md with all implementation details, test results, and layout verification.
- Send a message to parent when done.
