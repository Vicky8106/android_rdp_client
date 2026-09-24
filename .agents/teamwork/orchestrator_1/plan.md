# Master Orchestration Plan: Android RDP Client UX & Input Port

## Objective
Port the mature in-session user experience, virtual keys, touch/mouse pointer modes, and keyboard input handling from the local VNC repository (`C:\Users\Administrator\avnc`) into the Android RDP client (`C:\Users\Administrator\teamwork_projects\android_rdp_client`), resolving all interaction, streaming, and stability bugs with 100% test pass rate and release APK under 100 MB.

## Execution Phases

### Phase 0: Survey & Specification Mining
- Spawn 3 parallel agents:
  1. Spec Miner / Explorer 1: Reference AVNC Architecture & Patterns (`Toolbar.kt`, `VncActivity.kt`, `LayoutManager.kt`, `VirtualKeysCompose.kt`, `TouchHandler.kt`, `PointerModes.kt`, `KeyHandler.kt`, `VirtualMouseCompose.kt`).
  2. Explorer 2: Target Android RDP Client Architecture & Current Input/Session Stack (`SessionScreen.kt`, `RemoteCanvasView.kt`, `:feature-mouse`, `:feature-session`, FreeRDP native protocol mapping).
  3. Spec Miner / Explorer 3: FreeRDP Protocol Mapping & Scancode Translation (`ScancodeTranslator.kt`, FreeRDP pointer/keyboard native bindings, timing, event queues).
- Output: Synthesize reports into `PROJECT.md` Feature Inventory and Interface Contracts.

### Phase 1: Architectural Decomposition & Project Specification
- Construct `PROJECT.md` with:
  - Architecture and Code Layout
  - Feature Inventory (M1 to M4 mappings)
  - Interface Contracts
  - Milestones breakdown

### Phase 2: Dual Track Execution
- **Track A: E2E Testing Track**
  - E2E Testing Orchestrator / Test Writers create comprehensive opaque-box test suites (Tiers 1-4) across toolbar, virtual keys, keyboard timing, and mouse modes.
  - Publish `TEST_READY.md`.
- **Track B: Implementation Track**
  - Milestone 1: Collapsible In-Session Toolbar & Navigation
  - Milestone 2: RealVNC-Style Virtual Keys Bar & Soft Keyboard Timing
  - Milestone 3: Multi-Mode Touch, Touchpad & Virtual Mouse Pointer Engine

### Phase 3: Final Integration & E2E Validation
- Pass 100% of the E2E test suite (Tiers 1-4).
- Tier 5 Adversarial Coverage Hardening (Challengers + Workers + Reviewers).

### Phase 4: Release Packaging & Verification
- Verify `.\gradlew.bat testDebugUnitTest` passes 100% across all modules with 0 failures and 0 errors.
- Verify `.\gradlew.bat assembleDebug` produces valid signed APK in `releases/app-debug.apk` under 100 MB.
- Forensic Integrity Audit verification.
