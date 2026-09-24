# BRIEFING — 2026-09-24T11:35:00Z

## Mission
Investigate and document the current architecture, input system, UI layout, and test suite of the target Android RDP client (`C:\Users\Administrator\teamwork_projects\android_rdp_client`) to prepare for porting mature VNC in-session UX, virtual keys, touch/touchpad modes, and keyboard input handling from `C:\Users\Administrator\avnc`.

## 🔒 My Identity
- Archetype: explorer
- Roles: codebase investigation, architectural mapping, test suite auditing, synthesis
- Working directory: C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\teamwork\teamwork_preview_explorer_survey_2
- Original parent: f0fc1f73-b43f-468a-ab50-e5ec45aedb66
- Milestone: survey_target_rdp_client

## 🔒 Key Constraints
- Read-only investigation — do NOT implement or modify any source code
- Files for content delivery, Messages for coordination
- Self-contained 5-component handoff report (Observation, Logic Chain, Caveats, Conclusion, Verification Method)

## Current Parent
- Conversation ID: f0fc1f73-b43f-468a-ab50-e5ec45aedb66
- Updated: 2026-09-24T11:35:00Z

## Investigation State
- **Explored paths**:
  - `app/src/main/java/com/freerdp/client/ui/session/SessionScreen.kt` & `RemoteCanvasView.kt`
  - `feature-mouse/src/main/java/com/freerdp/feature/mouse/` (CoordinateTransformer, MouseController, GestureDisambiguationEngine, OverlayCoordinates, FloatingMouseOverlayView)
  - `feature-session/src/main/java/com/freerdp/feature/session/` (ScancodeTranslator, ModifierStateMachine, QuickActionToolbarFSM, Aliases)
  - `core-rdp/src/main/java/com/freerdp/core/` (IRdpEngine, NativeFreeRdpEngine, MockRdpEngine, RdpPointerFlags, LibFreeRDP)
  - Reference `avnc` at `C:\Users\Administrator\avnc` (Toolbar, VirtualKeysCompose, VirtualMouseCompose, KeyHandler, PointerModes, Keyboard)
  - Root and subproject `build.gradle.kts` files, `PROJECT.md`, `TEST_INFRA.md`
  - Test suites across `:core-rdp`, `:feature-mouse`, `:feature-session`, `:feature-telemetry`, `:app` (45 suites, 505 tests)
- **Key findings**:
  - 505 tests currently pass 100% across all 5 modules.
  - Middle mouse button is completely missing from `MouseController`, even though `RdpPointerFlags.MIDDLE_BUTTON_DOWN/UP` exist.
  - Extended scancode flags (`isExtended = true`) are defined in `ScancodeTranslator`, but dropped by `ModifierStateMachine` and `IRdpEngine.sendKeyEvent`.
  - Zero key hold timing (no BMC delay) exists; keys are dispatched as instant DOWN followed immediately by UP.
  - Floating overlay is an imperative `FrameLayout`, and toolbar/keyboard are basic bottom controls.
- **Unexplored areas**: None within the survey scope; complete survey completed.

## Key Decisions Made
- Produced comprehensive `survey_rdp_client.md` documenting architecture, components, data flows, hook points, and comparison with `avnc`.
- Generated 5-component `handoff.md`.

## Artifact Index
- `DISPATCH.md` — incoming instructions and dispatch log
- `BRIEFING.md` — persistent state and memory
- `survey_rdp_client.md` — comprehensive architectural analysis and survey
- `handoff.md` — 5-component handoff report
