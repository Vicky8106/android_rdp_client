# 5-Component Handoff Report: UI & SessionScreen Investigation (:app)

**Agent**: `teamwork_preview_explorer_1` (Session & UI Explorer)  
**Date**: 2026-09-24T16:05:00Z  
**Target Module**: `:app` (`app/src/main/java/com/freerdp/client/ui/session/`)  
**Scope**: Investigation of UI & SessionScreen components, test baseline execution, and gap analysis for R1 and R2.

---

## 1. Observation

### A. Direct Codebase Inspection (`app/src/main/java/com/freerdp/client/ui/session/`)

1. **`SessionScreen.kt` (1015 lines)**:
   - **Legacy Mouse Overlay**: Lines 253–291 embed the legacy View-based `FloatingMouseOverlayView` (from `:feature-mouse`) via `AndroidView`:
     ```kotlin
     AndroidView(
         factory = { ctx ->
             FloatingMouseOverlayView(ctx).also { overlay ->
                 overlay.restorePosition(overlayPrefs)
                 ...
             }
         },
         modifier = Modifier.fillMaxSize()
     )
     ```
     It does **not** use the newly implemented Compose-based `VirtualMouseCompose` (`VirtualMouseOverlay`).
   - **Legacy Toolbar**: Lines 490–498 and lines 767–857 implement a bottom-anchored Compose `QuickToolbar` (with a 4-second auto-collapse timer and pin toggle). It is an inline horizontal row at `Alignment.BottomCenter`, **not** a collapsible side-drawer layout.
   - **Legacy Modifier Bar**: Lines 478–489 and lines 666–707 implement an inline `ModifierBar` consisting of a basic horizontal scroll row with Ctrl, Alt, Win, Esc, F1..F12, and 4 macro buttons (`Ctrl+Alt+Del`, `Alt+Tab`, `Ctrl+C`, `Ctrl+V`). It does **not** use `VirtualKeysOverlay`.
   - **Legacy Keyboard Field**: Lines 475–477 and lines 878–912 implement `RemoteKeyboardField`, which directly dispatches characters via `vm.onKeyboardText(text)` and backspaces via `vm.onKeyboardBackspace()`, completely bypassing BMC 50ms key-press hold timing and 25ms text pacing.
   - **Missing Controls**:
     - No floating draggable opener button for the toolbar.
     - No persistent vertical bias for the opener button.
     - No Android 10+ system gesture exclusion rect configuration (`View.setSystemGestureExclusionRects`).
     - No pointer mode toggle (Direct Touch vs Touchpad Mode) in the toolbar.
     - No transparent scrim dismissal layer preventing spurious clicks on `RemoteCanvasView`.

2. **`RemoteCanvasView.kt` (280 lines)**:
   - **Touchpad Acceleration Omitted**: In lines 86–89:
     ```kotlin
     val controller = mouseController
     if (controller != null && controller.isTouchpadMode) {
         controller.handleTouchpadMove(deltaX, deltaY)
     }
     ```
     `controller.handleTouchpadMove(deltaX, deltaY)` is called without setting `accelerate = true`, meaning the 3-tier libinput physical acceleration curve (`LibinputPointerAcceleration`) is disabled during canvas touch gestures.
   - **Direct Touch Edge Coercion Missing**: In lines 73–75:
     ```kotlin
     override fun onSingleTap(screenX: Float, screenY: Float) {
         mouseController?.handleLeftClick(screenX, screenY)
     }
     ```
     Screen coordinates from taps are passed directly to `handleLeftClick` without letterbox/pillarbox margin coercion (`coerceToFbEdge`) to remote framebuffer boundaries (`[0, remoteWidth - 1]`, `[0, remoteHeight - 1]`).
   - **Direct Mode Pan**: In lines 96–100, one-finger pan in direct mode simply pans the viewport (`transformer.applyPan(deltaX, deltaY)`), rather than letterbox-coerced dragging.

3. **`InSessionToolbar.kt`**:
   - Checked filesystem using `find_by_name` across the entire repository: **Does not exist** (`Found 0 results`).
   - The toolbar is currently an inline private Composable `QuickToolbar` in `SessionScreen.kt`.

4. **`VirtualKeysCompose.kt` (857 lines)**:
   - Contains a complete RealVNC-style Compose overlay:
     - `VirtualKeysState`: State holder for modifier latching and timing.
     - `VirtualKeysOverlay` & `VirtualKeysBar`: Surface with 80% opacity, rounded corners, and tonal elevation.
     - `FunctionKeysStrip`: Expandable Fn strip with F1–F12.
     - `MainControlsBar`: Keyboard toggle, Mouse toggle, 72dp Fn toggle; Esc, Tab, Win, Del; Ctrl, Alt, Shift, Caps; Home, End, Inverted-T arrow cluster (Up centered over Down, flanked by Left & Right), PgUp, PgDn; Hold-to-repeat Scroll Up/Down buttons (200ms delay, 50ms repeat); Close button.
   - **Status**: The implementation exists, but is completely **orphaned and unwired**. It is never imported, called, or rendered in `SessionScreen.kt`.

5. **`VirtualMouseCompose.kt` (673 lines)**:
   - Contains a complete Compose virtual mouse overlay:
     - `VirtualMouse`: Controller managing visibility, expansion, LMB/MMB/RMB clicks, drag lock, and scroll.
     - `VirtualMouseOverlay`: Composable providing a 56dp circular floating draggable FAB (collapsed state), a 46dp rounded control pill (expanded state) with drag handle, LMB (hold & drag), MMB (middle click), Scroll Up/Down, RMB, Keyboard toggle, Close, and a docked right vertical scroll pillar (52dp wide) with hold-to-repeat scroll pads.
   - **Status**: The implementation exists, but is completely **orphaned and unwired**. It is never imported, called, or rendered in `SessionScreen.kt`.

6. **`SessionViewModel.kt` (784 lines)**:
   - Does not expose or hold an instance of `KeyboardTimingManager` (`DefaultKeyboardTimingManager`).
   - Does not expose pointer mode state (`isTouchpadMode` / `DirectTouch`) or toggle action.
   - Toolbar actions (`onToolbarAction`) handle `TOGGLE_KEYBOARD`, `TOGGLE_MOUSE_OVERLAY`, `SWITCH_RESOLUTION`, `TOGGLE_TELEMETRY_HUD`, and `TOGGLE_MODIFIER_BAR`, but do not have actions for switching pointer modes or toggling `VirtualKeysCompose`.

---

### B. Unit & E2E Test Execution Results

#### 1. UX/Input E2E Test Suite
Command executed:
```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.freerdp.client.e2e.uxinput.*"
```
Output: `BUILD SUCCESSFUL in 38s` (exit code 0).  
XML Test Artifacts parsed from `app/build/test-results/testDebugUnitTest/`:

| Test Suite Class | Tests | Failures | Errors | Skipped | Time (s) |
|---|---|---|---|---|---|
| `com.freerdp.client.e2e.uxinput.UxInputTier1FeatureCoverageTest` | 55 | 0 | 0 | 0 | 9.346 |
| `com.freerdp.client.e2e.uxinput.UxInputTier2BoundaryCornerTest` | 55 | 0 | 0 | 0 | 2.001 |
| `com.freerdp.client.e2e.uxinput.UxInputTier3CrossFeatureTest` | 11 | 0 | 0 | 0 | 0.287 |
| `com.freerdp.client.e2e.uxinput.UxInputTier4WorkloadTest` | 5 | 0 | 0 | 0 | 0.128 |
| **Total UX/Input E2E Tests** | **126** | **0** | **0** | **0** | **11.762** |

#### 2. Full `:app` Test Suite
Command executed:
```powershell
.\gradlew.bat :app:testDebugUnitTest
```
Output: `BUILD SUCCESSFUL in 1m 6s` (exit code 0).  
XML Test Artifacts parsed from `app/build/test-results/testDebugUnitTest/` (20 suites total):

| Category | Suite Name | Tests | Failures | Errors | Skipped |
|---|---|---|---|---|---|
| **E2E Classic** | `com.freerdp.client.e2e.Tier1FeatureCoverageTest` | 35 | 0 | 0 | 0 |
| | `com.freerdp.client.e2e.Tier2BoundaryCornerTest` | 26 | 0 | 0 | 0 |
| | `com.freerdp.client.e2e.Tier3CrossFeatureTest` | 8 | 0 | 0 | 0 |
| | `com.freerdp.client.e2e.Tier4RealWorldScenariosTest` | 5 | 0 | 0 | 0 |
| **E2E UX/Input** | `com.freerdp.client.e2e.uxinput.UxInputTier1FeatureCoverageTest` | 55 | 0 | 0 | 0 |
| | `com.freerdp.client.e2e.uxinput.UxInputTier2BoundaryCornerTest` | 55 | 0 | 0 | 0 |
| | `com.freerdp.client.e2e.uxinput.UxInputTier3CrossFeatureTest` | 11 | 0 | 0 | 0 |
| | `com.freerdp.client.e2e.uxinput.UxInputTier4WorkloadTest` | 5 | 0 | 0 | 0 |
| **Unit / Robolectric** | `com.freerdp.client.unit.AppContainerTest` | 8 | 0 | 0 | 0 |
| | `com.freerdp.client.unit.ClipboardSyncTest` | 4 | 0 | 0 | 0 |
| | `com.freerdp.client.unit.CoroutineDiagnosticsTest` | 5 | 0 | 0 | 0 |
| | `com.freerdp.client.unit.NavigationModelTest` | 7 | 0 | 0 | 0 |
| | `com.freerdp.client.unit.ProfileEditorViewModelTest` | 7 | 0 | 0 | 0 |
| | `com.freerdp.client.unit.ProfilesViewModelTest` | 7 | 0 | 0 | 0 |
| | `com.freerdp.client.unit.ProfileValidationTest` | 7 | 0 | 0 | 0 |
| | `com.freerdp.client.unit.SessionGraphicsTest` | 6 | 0 | 0 | 0 |
| | `com.freerdp.client.unit.SessionPhaseReducerTest` | 10 | 0 | 0 | 0 |
| | `com.freerdp.client.unit.SessionViewModelTest` | 23 | 0 | 0 | 0 |
| | `com.freerdp.client.unit.SettingsPersistenceTest` | 5 | 0 | 0 | 0 |
| | `com.freerdp.client.unit.UiInteractionSmokeTest` | 5 | 0 | 0 | 0 |
| **Grand Total (:app)** | **20 Suites** | **294** | **0** | **0** | **0** |

---

## 2. Logic Chain

1. **Test Double Isolation vs UI Implementation**:
   - Observation 1B shows that all 126 UX/Input E2E tests pass.
   - However, inspection of `UxInputTestHarness.kt` shows that these tests directly instantiate self-contained test engine classes (`ToolbarDrawerLayoutEngine`, `FloatingOpenerController`, `LibinputPointerAcceleration`, `BmcKeyboardTimingEngine`, `VirtualMouseOverlayModel`, `DirectTouchPointerHandler`).
   - Therefore, the test harness proves the correctness of the behavioral contracts, but the active production UI in `app/src/main/java/` has not yet integrated these engines into `SessionScreen.kt` and `RemoteCanvasView.kt`.

2. **R1 Gap Analysis (Collapsible In-Session Toolbar & Floating Opener)**:
   - **Drawer Architecture**: In `avnc/Toolbar.kt`, the toolbar is a drawer that slides from the edge (`start` or `end` alignment) with a transparent root view that dismisses on click or closing fling without passing events to the desktop. In `SessionScreen.kt`, there is only a bottom row (`QuickToolbar`).
   - **Floating Draggable Opener**: `avnc` and `FloatingOpenerController` provide a floating handle draggable along the screen edge whose normalized position (`verticalBias`) is clamped between `marginTop` and `maxY` and persisted to SharedPreferences (`toolbarOpenerBtnVerticalBias`). `SessionScreen.kt` lacks this opener button.
   - **System Gesture Exclusions**: Android 10+ system back navigation overrides edge swipes unless `setSystemGestureExclusionRects` is called with the padded toolbar rectangle (`padding = (parentHeight - toolbarHeight) / 6`). `SessionScreen.kt` currently sets no exclusion rects.
   - **Quick Actions**: R1 mandates 5 quick actions: Soft Keyboard toggle, Pointer Mode switch (Direct Touch vs Touchpad), Virtual Keys toggle, Zoom/Fit reset, and Disconnect. `SessionScreen.kt`'s `QuickToolbar` lacks Pointer Mode toggle and Virtual Keys toggle.

3. **R2 Gap Analysis (Active Session Overlay Integration & Wiring)**:
   - **`VirtualKeysCompose` Wiring**: `VirtualKeysCompose.kt` is fully implemented, but `SessionScreen.kt` still renders the legacy `ModifierBar`. `VirtualKeysOverlay` must be integrated as an overlay in `SessionScreen.kt` anchored above the bottom insets.
   - **`VirtualMouseCompose` Wiring**: `VirtualMouseCompose.kt` is fully implemented, but `SessionScreen.kt` still renders the legacy `FloatingMouseOverlayView` inside an `AndroidView`. `VirtualMouseOverlay` must replace `FloatingMouseOverlayView`, connecting the floating FAB, expandable pill, and right vertical scroll pillar to the active `MouseController`.
   - **`RemoteCanvasView` Pointer Modes**:
     - Direct Touch mode must coerce touches outside the active aspect ratio (letterbox/pillarbox margins) to remote framebuffer edges (`[0, remoteWidth - 1]`, `[0, remoteHeight - 1]`), matching `DirectTouchPointerHandler.coerceToFbEdge`.
     - Touchpad mode must pass `accelerate = true` to `DefaultMouseController.handleTouchpadMove`, activating the 3-tier libinput curve with zoom dampening.
   - **Keyboard Timing Manager**:
     - `DefaultKeyboardTimingManager` exists in `:feature-session`, but is not instantiated in `SessionViewModel` or `AppContainer`.
     - BMC 50ms key-press hold and 25ms text streaming pacing must be wired into `RemoteKeyboardField` and virtual key desktop triggers (Enter, Backspace, Tab, Space).

---

## 3. Caveats

1. **Read-Only Explorer Scope**: In accordance with the system constraints, no production or test source files were edited during this investigation.
2. **JVM Test Scope vs Device Verification**: The 294 passing tests in `:app` execute on the Robolectric / JVM runner. Hardware features (real touch slop, multi-touch capacitive latency, system gesture navigation bar touches, and soft keyboard IME windows) must be verified on an active Android emulator or physical device.
3. **No New Test Breakages**: Since `UxInputTestHarness.kt` is self-contained, updating `SessionScreen.kt` and `RemoteCanvasView.kt` will not regress existing unit or E2E tests, provided existing method signatures and `SessionViewModel` bindings remain intact.

---

## 4. Conclusion

1. **Repository Health**: The `:app` test suite is 100% healthy, with 294 passing tests (0 failures, 0 errors, 0 skipped), including all 126 new UX/Input E2E tests.
2. **Primary Missing Work for R1**:
   - Implement `InSessionToolbar.kt` in `app/src/main/java/com/freerdp/client/ui/session/` (or equivalent Compose drawer component) matching `avnc`'s `Toolbar.kt` and `ToolbarDrawerLayoutEngine`.
   - Implement the Draggable Floating Opener Button in `SessionScreen.kt` with persistent `toolbarOpenerBtnVerticalBias`.
   - Add transparent scrim overlay that absorbs dismiss touches without forwarding clicks to `RemoteCanvasView`.
   - Add `View.setSystemGestureExclusionRects` calculation for Android 10+ (API 29+).
   - Update toolbar actions to include Pointer Mode toggle (Direct Touch vs Touchpad) and Virtual Keys bar toggle.
3. **Primary Missing Work for R2**:
   - Replace legacy `FloatingMouseOverlayView` in `SessionScreen.kt` with `VirtualMouseOverlay` from `VirtualMouseCompose.kt`.
   - Replace legacy `ModifierBar` in `SessionScreen.kt` with `VirtualKeysOverlay` from `VirtualKeysCompose.kt`.
   - In `RemoteCanvasView.kt`, wire letterbox edge coercion for Direct Touch taps/drags and enable `accelerate = true` on `handleTouchpadMove`.
   - In `SessionViewModel.kt` and `AppContainer.kt`, instantiate `KeyboardTimingManager` and route text typing and special keys through its paced actor queue.

---

## 5. Verification Method

To independently verify these findings:

1. **Verify UX/Input E2E Test Suite**:
   ```powershell
   .\gradlew.bat :app:testDebugUnitTest --tests "com.freerdp.client.e2e.uxinput.*"
   ```
   *Expected: BUILD SUCCESSFUL, 126 tests executed across 4 classes, 0 failures, 0 errors.*

2. **Verify Full `:app` Test Suite**:
   ```powershell
   .\gradlew.bat :app:testDebugUnitTest
   ```
   *Expected: BUILD SUCCESSFUL, 294 tests executed across 20 classes, 0 failures, 0 errors.*

3. **Verify Absence of `InSessionToolbar.kt`**:
   ```powershell
   Test-Path 'app\src\main\java\com\freerdp\client\ui\session\InSessionToolbar.kt'
   ```
   *Expected: False.*

4. **Verify Legacy Overlay Usage in `SessionScreen.kt`**:
   Inspect line 254 (`FloatingMouseOverlayView`) and line 479 (`ModifierBar`) to confirm that `VirtualKeysOverlay` and `VirtualMouseOverlay` are not currently rendered.
