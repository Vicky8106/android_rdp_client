# Handoff Report — Mouse & Session Engine Explorer

**Agent**: `teamwork_preview_explorer_2`  
**Working Directory**: `C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\teamwork\teamwork_preview_explorer_2`  
**Date**: 2026-09-24  
**Milestone**: Preview Exploration (`:feature-mouse` and `:feature-session`)

---

## 1. Observation

### 1.1 Source Code Inspection — `:feature-mouse`
1. **`MouseController.kt`** (`com.freerdp.feature.mouse.MouseController`):
   - Line 15: `fun handleMiddleClick(screenX: Float, screenY: Float) = Unit` is defined directly in the interface.
   - Also provides default property accessors `isTouchpadMode`, `isDragging`, `cursorScreenPosition`, `virtualCursorPosition`, and methods `handleLeftClick`, `handleRightClick`, `handleDoubleClick`, `handleDragStart`, `handleDragMove`, `handleDragEnd`, `handleScroll`, `setTouchpadMode`, `setCursorVisible`.
2. **`DefaultMouseController.kt`** (`com.freerdp.feature.mouse.DefaultMouseController`):
   - Lines 65–69: Implements `handleMiddleClick(screenX: Float, screenY: Float)` by resolving coordinates (`resolveTargetCoordinates`) and dispatching `RdpPointerFlags.MIDDLE_BUTTON_DOWN` and `RdpPointerFlags.MIDDLE_BUTTON_UP`.
   - Lines 39–51: `resolveTargetCoordinates` switches between Direct mode (`transformer.screenToDesktopInt`) and Touchpad mode (clamps virtual cursor coordinates `(cursorX, cursorY)` within `[0, remoteWidth - 1]` and `[0, remoteHeight - 1]`).
   - Lines 133–159: `handleTouchpadMove(deltaX, deltaY, accelerate)` computes displacement using `acceleration.computeDelta(deltaX, deltaY, dpi, transformer.scale)` and triggers `transformer.autoCenterOn(cursorX, cursorY)` when `autoCenterEnabled` is true.
   - Lines 161–175: `setTouchpadMode(enabled)` contains reviewer regression safety release for mid-drag exit, dispatching `LEFT_BUTTON_UP` using virtual cursor desktop coordinates without double coordinate transformation.
3. **`PointerModes.kt`** (`com.freerdp.feature.mouse.PointerModes`):
   - Defines `PointerButton` enum (`None`, `Left`, `Middle`, `Right`, `WheelUp`, `WheelDown`, `WheelLeft`, `WheelRight`).
   - `BasePointerMode`: Coordinates down/up flag conversion (`PointerButton.Middle` maps to `RdpPointerFlags.MIDDLE_BUTTON_DOWN` and `MIDDLE_BUTTON_UP`), click/double-click, and remote scroll accumulation with a 20f threshold.
   - `DirectPointerMode`: Direct tap-to-click at `transformer.toFb(p)`; lines 162–168 implement letterbox edge coercion (`coerceToFbEdge(p)`) on `doClick(PointerButton.Left, p)` so clicks outside the frame in letterboxed margins are clamped to the remote desktop border. Implements `handleTwoFingerPan` and `handlePinchZoom`.
   - `RelativePointerMode`: Virtual cursor centered at `(remoteWidth / 2, remoteHeight / 2)`, relative movement with 3-tier physical pointer acceleration (`accelerator.computeDelta(dx, dy, dpi, transformer.scale)`), viewport auto-centering (`autoCenterViewport` -> `transformer.autoCenterOn`), bounds clamping, and drag-lock lifecycle (`doRemoteDrag` / `onGestureStop`).
   - `SwipeVsScale`: Two-finger gesture disambiguation engine calculating angular differential between touch points (`scaleAngleThresholdDeg = 45.0`, `swipeAngleThresholdDeg = 30.0`).
4. **`PointerAcceleration.kt`** (`com.freerdp.feature.mouse.PointerAcceleration`):
   - `DefaultPointerAcceleration`: Libinput 3-tier physical acceleration with velocity conversion (`(abs(px) * 25.4f) / max(1f, dpi)` in mm/s).
   - Tier 1 (< 10 mm/s): Deceleration curve `0.07 * v + 0.3` (clamped min 0.3).
   - Tier 2 (10 <= v < 80 mm/s): Linear baseline `1.0`.
   - Tier 3 (v >= 80 mm/s): Quadratic speedup `0.0005 * (v^2 / 80) + 1.0`, clamped to maximum `3.5`.
   - Zoom dampening for `zoomScale > 1.0`: `dampenerSlope = (zoomScale - 1f) * 0.07f`, `factor = (baseFactor - dampener).coerceIn(0.3f, 3.5f)`.
5. **`CoordinateTransformer.kt`** (`com.freerdp.feature.mouse.CoordinateTransformer`):
   - 2D affine transformation between screen viewport and remote RDP surface with scale clamping `[0.25, 5.0]`.
   - `clampAndCenterViewport()`: Automatically letterboxes/centers content smaller than viewport and clamps panning to avoid void space.
   - `resetToFit()`: Fits entire remote desktop within viewport.
   - `screenToDesktop`, `desktopToScreen`, `toFb`, `toFbUnchecked`, `coerceToFbEdge`, `coerceToFbEdgeDesktop`, and `autoCenterOn(desktopX, desktopY)`.

### 1.2 Source Code Inspection — `:feature-session`
1. **`ScancodeTranslator.kt`** (`com.freerdp.feature.session.keyboard.ScancodeTranslator`):
   - Line 25: `const val EXTENDED_KEY_FLAG = 0x0100`.
   - `ScancodeResult` encapsulates `scancode`, `isExtended`, `vkCode`, `flags`, and `extendedScancode` (`scancode or 0x0100`).
   - Defines complete Windows VK codes (`VK_BACK`, `VK_TAB`, `VK_RETURN`, `VK_LCONTROL`, `VK_RCONTROL`, `VK_LMENU`, `VK_RMENU`, `VK_LWIN`, `VK_RWIN`, `VK_UP`, `VK_DOWN`, `VK_LEFT`, `VK_RIGHT`, `VK_F1`..`VK_F12`, etc.).
   - Defines complete Windows PC Scancode Set 1 constants (`SCANCODE_ESCAPE = 0x01`, `SCANCODE_A`..`SCANCODE_Z`, `SCANCODE_0`..`SCANCODE_9`, `SCANCODE_LEFT_CTRL = 0x1D`, `SCANCODE_LEFT_ALT = 0x38`, etc.).
   - Extended scancodes (`SCANCODE_UP_ARROW = 0x48`, `SCANCODE_DOWN_ARROW = 0x50`, `SCANCODE_DELETE = 0x53`, `SCANCODE_LEFT_WIN = 0x5B`, etc.).
   - `isKnownScancode(scancode)` validates Set 1 range: `(scancode and 0xFF) in 0x01..0x7F`.
   - Bidirectional converters: `fromAndroidKeyCode`, `fromChar`, `toWindowsVkCode`, `fromWindowsVkCode`, and `getScancodeForModifierKey`.
   - Macros: `CTRL_ALT_DEL`, `ALT_TAB`, `ALT_F4`, `WIN_D`, `WIN_R`, `CTRL_C`, `CTRL_V` with balanced LIFO down/up step lists.
2. **`KeyboardTimingManager.kt`** (`com.freerdp.feature.session.keyboard.KeyboardTimingManager`):
   - Lines 27–28: `val keyHoldDurationMs: Long get() = 50L`, `val interKeyPacingMs: Long get() = 25L`.
   - `DefaultKeyboardTimingManager`: Implements non-blocking coroutine actor queue via `Channel<KeyCommand>(capacity = Channel.UNLIMITED)` on `Dispatchers.Default + SupervisorJob()`.
   - `KeyCommand.Hold`: Dispatches key-down, delays `holdDurationMs` (50ms default), dispatches key-up. Preserves `0x0100` extended flag bit.
   - `KeyCommand.TextStream`: Replaces `\r\n` / `\r` / `\n` with space, extracts code points, dispatches down, delays 50ms hold, dispatches up, delays 25ms inter-key pacing.
   - `KeyCommand.ReleaseAllModifiers`: Dispatches key-up for 8 modifier keys (L/R Shift, L/R Ctrl, L/R Alt, L/R Win).
   - BMC convenience helpers: `sendEnterKey`, `sendBackspaceKey`, `sendSpaceKey`, `sendTabKey`, `sendSpecialKey`, `sendUnicodeCharWithHold`, `sendScancodeWithHold`.
3. **`ModifierStateMachine.kt`** (`com.freerdp.feature.session.modifier.ModifierStateMachine`):
   - 3-state latching FSM (`INACTIVE` -> `LATCHED` -> `LOCKED` -> `INACTIVE`).
   - Latchable modifiers: `CTRL`, `ALT`, `SHIFT`, `WIN`.
   - `onModifierKeyTapped`: Single tap -> `LATCHED` (emits key-down); second tap -> `LOCKED` (no event, key remains held); third tap -> `INACTIVE` (emits key-up).
   - `onModifierKeyLongPressed`: `INACTIVE` -> `LOCKED` directly (emits key-down once); `LATCHED` -> `LOCKED`; `LOCKED` -> `INACTIVE`.
   - Auto-release: Non-modifier key press (`onNonModifierKeyPressed`) or special key tap (`onSpecialKeyTapped`) automatically releases any `LATCHED` modifier to `INACTIVE` (emitting key-up) while preserving `LOCKED` modifiers.
   - Rejects unmapped scancodes (`!ScancodeTranslator.isKnownScancode(scancode)`), dropping invalid keys and preserving modifier latches.
   - Observability: Exposes `statesFlow: StateFlow<Map<ModifierKey, LatchState>>`.

### 1.3 Test Suite Execution Results
Fresh run of `.\gradlew.bat :feature-mouse:testDebugUnitTest :feature-session:testDebugUnitTest --rerun-tasks` executed on 2026-09-24 with exit code 0:

- **`:feature-mouse` (6 suites / 55 tests / 0 failures / 0 errors / 0 skipped)**:
  - `com.freerdp.feature.mouse.CoordinateTransformerTest`: 11 tests, 0 failures, 0 errors
  - `com.freerdp.feature.mouse.FloatingMouseOverlayTest`: 7 tests, 0 failures, 0 errors
  - `com.freerdp.feature.mouse.GestureDisambiguationTest`: 9 tests, 0 failures, 0 errors
  - `com.freerdp.feature.mouse.MouseControllerTest`: 13 tests, 0 failures, 0 errors
  - `com.freerdp.feature.mouse.PointerAccelerationTest`: 6 tests, 0 failures, 0 errors
  - `com.freerdp.feature.mouse.PointerModesTest`: 9 tests, 0 failures, 0 errors
- **`:feature-session` (7 suites / 99 tests / 0 failures / 0 errors / 0 skipped)**:
  - `com.freerdp.feature.session.KeystoreCredentialStoreTest`: 20 tests, 0 failures, 0 errors
  - `com.freerdp.feature.session.ModifierStateMachineTest`: 20 tests, 0 failures, 0 errors
  - `com.freerdp.feature.session.ProfileRepositoryTest`: 17 tests, 0 failures, 0 errors
  - `com.freerdp.feature.session.QuickActionToolbarTest`: 15 tests, 0 failures, 0 errors
  - `com.freerdp.feature.session.ScancodeTranslatorTest`: 14 tests, 0 failures, 0 errors
  - `com.freerdp.feature.session.keyboard.KeyboardTimingManagerTest`: 7 tests, 0 failures, 0 errors
  - `com.freerdp.feature.session.keyboard.ScancodeTranslatorTest`: 6 tests, 0 failures, 0 errors

**Module Total**: **154 tests / 154 passing / 0 failures / 0 errors / 0 skipped (100% pass rate)**.

---

## 2. Logic Chain

1. **Middle Click / BUTTON3 support**:
   - `RdpPointerFlags.PTR_FLAGS_BUTTON3 = 0x4000` is defined in `core-rdp`.
   - `MouseController.handleMiddleClick` is declared in `feature-mouse`.
   - `DefaultMouseController.handleMiddleClick` sends `MIDDLE_BUTTON_DOWN` and `MIDDLE_BUTTON_UP` with proper screen-to-desktop or virtual-cursor target resolution.
   - Tested by `MouseControllerTest.testMiddleClickGeneratesDownAndUpSequence`, `MouseControllerTest.testTouchpadModeMiddleClickAtVirtualCursor`, and `PointerModesTest.testDirectModeRightAndMiddleClick`.
   - Therefore, Middle Click / BUTTON3 is fully implemented and verified in `:feature-mouse`.

2. **Direct Touch & Edge Coercion**:
   - `CoordinateTransformer.coerceToFbEdgeDesktop(p)` clamps out-of-frame coordinates to `[0, remoteWidth - 1]` and `[0, remoteHeight - 1]`.
   - `DirectPointerMode.doClick` detects when `toFb(p)` is null and falls back to `coerceToFbEdge(p)`.
   - Tested by `PointerModesTest.testDirectModeEdgeCoercionOutsideFrame`.
   - Therefore, Direct Touch with letterbox edge coercion is fully functional in `:feature-mouse`.

3. **Touchpad Mode & Pointer Acceleration**:
   - `DefaultPointerAcceleration` implements physical mm/s velocity scaling across Tier 1 (<10 mm/s), Tier 2 (10..80 mm/s), and Tier 3 (>=80 mm/s), plus zoom dampening for `zoomScale > 1.0`.
   - `RelativePointerMode` and `DefaultMouseController.handleTouchpadMove` integrate acceleration, auto-centering viewport (`transformer.autoCenterOn`), and boundary clamping.
   - Tested by `PointerAccelerationTest` (6/6 tests) and `PointerModesTest` (9/9 tests).
   - Therefore, Touchpad mode with libinput 3-tier acceleration is fully implemented in `:feature-mouse`.

4. **Soft Keyboard & BMC Timing**:
   - `DefaultKeyboardTimingManager` enforces 50ms key-down hold before key-up and 25ms inter-key pacing during text streaming.
   - Handled asynchronously via a non-blocking coroutine actor queue without stalling the UI thread.
   - Tested by `KeyboardTimingManagerTest` (7/7 tests verifying 49ms vs 50ms hold, 25ms pacing, extended bit preservation, CRLF sanitization).
   - Therefore, BMC timing and soft keyboard pacing are fully functional in `:feature-session`.

5. **Modifier Keys & Scancode Translation**:
   - `ScancodeTranslator` maps Android keycodes, characters, and Windows VK codes to PC Scancode Set 1, preserving the `0x0100` extended bit for navigation/editing keys.
   - `ModifierStateMachine` implements 3-state latching (Inactive -> Latched -> Locked -> Inactive), tap/long-press transitions, and auto-release on non-modifier key consumption.
   - Tested by `ModifierStateMachineTest` (20/20) and `ScancodeTranslatorTest` (20/20).
   - Therefore, sticky modifiers and scancodes are fully implemented in `:feature-session`.

---

## 3. Caveats

1. **Wiring Gap in `:app` UI (`SessionScreen.kt`)**:
   - While `:feature-mouse` and `:feature-session` have 100% complete logic implementations and unit tests, the Compose UI integration in `app/src/main/java/com/freerdp/client/ui/session/` has not yet completed the wiring required by R1/R2 of the latest prompt (`2026-09-24T15:53:26Z`):
     - `VirtualKeysCompose.kt` and `VirtualMouseCompose.kt` exist as standalone files in `:app` but are **not yet rendered in `SessionScreen.kt`**. `SessionScreen.kt` currently still renders the old `RemoteKeyboardField`, `ModifierBar`, `QuickToolbar`, and the AndroidView `FloatingMouseOverlayView`.
     - The collapsible session toolbar drawer (`InSessionToolbar`) with floating draggable opener button has not yet been built in Compose within `SessionScreen.kt`.
     - `SessionViewModel.kt` currently sends soft keyboard input character-by-character to `modifierMachine.onNonModifierKeyPressed(it)` directly, rather than routing through `DefaultKeyboardTimingManager` for 50ms BMC hold and 25ms text pacing.
     - `DirectPointerMode` and `RelativePointerMode` are currently not instantiated in `SessionViewModel.kt` / `RemoteCanvasView.kt`.
2. **Repository-Wide Test Count (R3: ≥ 664 tests)**:
   - `:feature-mouse` (55 tests) and `:feature-session` (99 tests) total 154 passing unit tests.
   - Combined with `:app` (161 tests, including 126 `uxinput` tests), `:core-rdp` (95 tests), and `:feature-telemetry` (120 tests), current on-disk unit test suites count is ~530 tests. Reaching ≥ 664 passing tests across the repository will require the integration/builder agent to run or expand the remaining suites.

---

## 4. Conclusion

1. **`:feature-mouse`**:
   - **Status**: COMPLETE & VERIFIED.
   - All components (`MouseController`, `DefaultMouseController`, `PointerModes`, `PointerAcceleration`, `CoordinateTransformer`, `GestureDisambiguationEngine`, `FloatingMouseOverlayView`) are fully implemented.
   - `handleMiddleClick` is present in `MouseController.kt` and `DefaultMouseController.kt`.
   - Direct Touch (with edge coercion), Relative Touchpad (with 3-tier libinput acceleration and zoom dampening), middle click (`RdpPointerFlags.MIDDLE_BUTTON_DOWN/UP`), and auto-centering are verified.
   - Unit tests: **55/55 PASS (100%)**.
2. **`:feature-session`**:
   - **Status**: COMPLETE & VERIFIED.
   - All components (`ScancodeTranslator`, `KeyboardTimingManager`, `DefaultKeyboardTimingManager`, `ModifierStateMachine`, `QuickActionToolbarFSM`, `ProfileRepository`, `KeystoreCredentialStore`) are fully implemented.
   - Windows VK constants, PC Scancode Set 1, `0x0100` extended bit preservation, 50ms BMC key hold, 25ms text pacing, and 3-state latching FSM are verified.
   - Unit tests: **99/99 PASS (100%)**.
3. **Primary Actionable Gaps for Downstream Builders**:
   - Wire `VirtualKeysCompose` and `VirtualMouseCompose` into `SessionScreen.kt`.
   - Build the collapsible in-session toolbar drawer and floating draggable opener in `SessionScreen.kt`.
   - Route `SessionViewModel` keyboard and mouse dispatches through `DefaultKeyboardTimingManager` and `PointerModes`.

---

## 5. Verification Method

To independently verify these findings, execute the following commands from the repository root (`C:\Users\Administrator\teamwork_projects\android_rdp_client`):

1. **Run `:feature-mouse` unit tests**:
   ```powershell
   .\gradlew.bat :feature-mouse:testDebugUnitTest --rerun-tasks
   ```
   *Expected outcome*: BUILD SUCCESSFUL, 6 test suites, 55 tests, 0 failures, 0 errors.

2. **Run `:feature-session` unit tests**:
   ```powershell
   .\gradlew.bat :feature-session:testDebugUnitTest --rerun-tasks
   ```
   *Expected outcome*: BUILD SUCCESSFUL, 7 test suites, 99 tests, 0 failures, 0 errors.

3. **Inspect Middle Click / BUTTON3**:
   - Inspect `feature-mouse/src/main/java/com/freerdp/feature/mouse/MouseController.kt` (line 15).
   - Inspect `feature-mouse/src/main/java/com/freerdp/feature/mouse/DefaultMouseController.kt` (lines 65–69).
   - Inspect `core-rdp/src/main/java/com/freerdp/core/protocol/RdpPointerFlags.kt` (lines 12, 22–23).

4. **Inspect BMC Timing and Modifiers**:
   - Inspect `feature-session/src/main/java/com/freerdp/feature/session/keyboard/KeyboardTimingManager.kt` (lines 27–44, 78–208).
   - Inspect `feature-session/src/main/java/com/freerdp/feature/session/keyboard/ScancodeTranslator.kt` (lines 7–14, 25, 204–212).
   - Inspect `feature-session/src/main/java/com/freerdp/feature/session/modifier/ModifierStateMachine.kt` (lines 61–172).
