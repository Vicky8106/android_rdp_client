# Dispatch: Worker M2 — Mobile Floating Mouse Overlay & Touch Gesture System

## Identity
- Archetype: teamwork_preview_worker
- Role: Floating Mouse & Gestures Implementer
- Working Directory: C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\worker_m2
- Parent Conversation ID: 279701df-502c-4614-ba7b-407470f48f9a

## Objective
Implement Milestone 2: Mobile Floating Mouse Overlay & Touch Gesture System in module `:feature-mouse`.

## Exclusive Write Ownership
- `feature-mouse/**`
- You may also update `core-rdp/src/main/java/com/freerdp/core/engine/MockRdpEngine.kt` to wrap event recording lists in `Collections.synchronizedList()` if needed for thread-safe test assertions.

## Mandatory Reading
- C:\Users\Administrator\teamwork_projects\android_rdp_client\ORIGINAL_REQUEST.md
- C:\Users\Administrator\teamwork_projects\android_rdp_client\PROJECT.md
- C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\survey_spec_miner_2\report.md

## MANDATORY INTEGRITY WARNING
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A teamwork_preview_auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

## Requirements & Scope
1. **Mouse Events & Controller (`com.freerdp.feature.mouse.MouseController`)**:
   Implement `MouseController` contract from `PROJECT.md`:
   - Left click, right click, double click, click-and-drag, long press, wheel/scroll.
   - Touchpad mode (relative cursor movement, two-finger scroll).
   - Virtual cursor state (crosshair/pointer visibility and desktop position).
   - Generates exact MS-RDPBCGR pointer event flags via `RdpPointerFlags` and dispatches to `IRdpEngine.sendPointerEvent`.
2. **Gesture Disambiguation Engine & Anti-Spurious Click Latch**:
   - Pan canvas and pinch-to-zoom.
   - Crucial: Multi-touch latch (`multiTouchLatch`) that activates whenever `pointerCount > 1` and remains latched until all fingers leave the screen (`ACTION_UP`), completely suppressing spurious tap/click events upon finger release.
   - Touch-slop hysteresis to distinguish stationary tap from pan.
3. **Orientation Adaptation & Coordinate Clamping**:
   - `OverlayCoordinates`: Normalized coordinate persistence math across orientation changes ($normX = (ox - insets.left) / (W - ow - insets.right - insets.left)$).
   - Screen boundary clamping within safe window insets (status bar, navigation bar, cutouts) ensuring the floating overlay never resets or spawns off-screen.
4. **Affine Coordinate Transformer**:
   - `CoordinateTransformer`: Viewport scale, pan translation offset, and bidirectional coordinate conversion (screen touch coordinates -> remote RDP desktop coordinates and vice-versa).
5. **Floating Mouse Overlay View / State Machine**:
   - `FloatingMouseOverlayView`: Custom Android view / Compose component supporting states: Collapsed, Expanded, Dragging (repositioning overlay on screen).
   - Overlay buttons: Left click, Right click, Drag toggle, Scroll mode, Touchpad toggle, Modifier expand.
6. **Automated Unit Tests**:
   Under `feature-mouse/src/test/java/com/freerdp/feature/mouse/`:
   - `FloatingMouseOverlayTest.kt`: Tests overlay FSM, drag repositioning, orientation persistence across screen dimension changes without clipping.
   - `MouseControllerTest.kt`: Tests left-click, right-click, double-click, click-drag sequence, scroll, and touchpad relative movement.
   - `GestureDisambiguationTest.kt`: Tests pan, pinch-zoom, and verifies multi-touch latch eliminates spurious click triggers on multi-finger lift.
   - `CoordinateTransformerTest.kt`: Tests affine transformation, zoom scaling, translation offset, and clamping.
7. **Verification**:
   Execute:
   `set JAVA_HOME=C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot`
   `.\gradlew.bat :feature-mouse:testDebugUnitTest`
   Ensure 100% test pass rate.
8. Deliver `handoff.md` and notify parent.

## 2026-09-22T19:05:19Z
You are worker_m2 (Floating Mouse & Gestures Implementer).
Working Directory: C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\worker_m2.
Dispatch details: C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\worker_m2\DISPATCH.md.

MANDATORY READING: Read C:\Users\Administrator\teamwork_projects\android_rdp_client\ORIGINAL_REQUEST.md completely before starting.
Also read C:\Users\Administrator\teamwork_projects\android_rdp_client\PROJECT.md and C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\survey_spec_miner_2\report.md.

MANDATORY INTEGRITY WARNING:
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A teamwork_preview_auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

Task:
Implement Milestone 2 in module :feature-mouse:
1. MouseController implementation (handleLeftClick, handleRightClick, handleDoubleClick, handleDragStart/Move/End, handleScroll, touchpad mode, virtual cursor).
2. Gesture disambiguation engine with multiTouchLatch (eliminates spurious clicks when releasing pinch-zoom/pan).
3. OverlayCoordinates with normalized persistence math and safe WindowInsets clamping across orientation changes.
4. CoordinateTransformer (affine viewport scaling, offset translation, screen-to-desktop and desktop-to-screen matrix conversion).
5. FloatingMouseOverlayView component with Collapsed, Expanded, Dragging states and interactive control buttons.
6. Automated unit tests in feature-mouse/src/test/java/com/freerdp/feature/mouse/ (FloatingMouseOverlayTest, MouseControllerTest, GestureDisambiguationTest, CoordinateTransformerTest).
7. Verify by running:
   $env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot"
   .\gradlew.bat :feature-mouse:testDebugUnitTest
8. Write handoff.md in your working directory and notify parent via send_message when complete.
