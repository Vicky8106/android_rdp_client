# Survey & Architectural Analysis: Android RDP Client (`android_rdp_client`)

**Target Repository**: `C:\Users\Administrator\teamwork_projects\android_rdp_client`  
**Reference Codebase**: `C:\Users\Administrator\avnc`  
**Date**: 2026-09-24  
**Author**: `teamwork_preview_explorer_survey_2` (Explorer)

---

## Executive Summary

This investigation provides a comprehensive survey of the architecture, input subsystem, UI layout, and test suite of `android_rdp_client`. The client is an Android RDP application built with Clean Architecture across 5 Gradle modules (`:core-rdp`, `:feature-mouse`, `:feature-session`, `:feature-telemetry`, and `:app`). 

Currently, the test suite contains **45 test suites and 505 test cases** passing with a 100% success rate (0 failures, 0 errors) under JUnit 4 and Robolectric 4.14.1 on the JVM.

However, a detailed code audit identifies crucial limitations in session interactivity and input handling that motivate the porting of mature user experience and input components from `avnc`:
1. **Session Layout & Toolbar**: The current toolbar in `SessionScreen.kt` is a simple horizontal row toggle button bar at the bottom center. It lacks dedicated mode switching between Direct Touch and Relative Touchpad, zoom controls are basic, and the soft keyboard is triggered via a standard Compose `OutlinedTextField`.
2. **Mouse & Overlay Engine**: The floating overlay in `:feature-mouse` (`FloatingMouseOverlayView.kt`) is an imperative Android `FrameLayout` created in code without Compose. It supports Left, Right, Drag, Scroll, and Mode Toggle, but **completely lacks Middle Click** (even though MS-RDPBCGR middle click flags exist in `:core-rdp`). The relative cursor movement has fixed sensitivity without pointer acceleration.
3. **Keyboard & Scancode Translation**: Keyboard input is currently routed through a basic text field that dispatches characters immediately with zero key-hold timing. Furthermore, extended scancodes (`isExtended = true`) in `ScancodeTranslator.kt` are stripped/ignored when passed through `ModifierStateMachine` and `IRdpEngine.sendKeyEvent`, dropping critical scancode flags.
4. **Virtual Keys Bar**: Virtual modifier keys are displayed in a simple horizontal scroll row without RealVNC ergonomics (no inverted-T arrow cluster, no sticky shift, no collapsible Fn strip with dedicated layout).

---

## 1. Project Modular Architecture & Dependency Graph

### 1.1 Module Inventory & Roles

| Module | Primary Role | UI Framework | Dependencies |
|---|---|---|---|
| `:core-rdp` | Native FreeRDP JNI bridge (`LibFreeRDP`), `IRdpEngine` contract, `NativeFreeRdpEngine`, `MockRdpEngine`, protocol codecs (MS-RDPBCGR, MS-RDPEDISP, MS-RDPECLIP) | Pure Kotlin/Java (no UI) | None (leaf module) |
| `:feature-mouse` | Input logic, 2D affine coordinate transformer, gesture disambiguation engine with anti-spurious latch, floating overlay View | Android Views (Imperative FrameLayout, no Compose) | `:core-rdp` |
| `:feature-session` | Pure session logic: Profile repository (JSON), Keystore credential vault (AES-256-GCM), Toolbar FSM (4s auto-collapse), Modifier 3-state latching FSM, ScancodeTranslator | Pure Kotlin (no UI, no Compose) | `:core-rdp` |
| `:feature-telemetry` | Low-latency socket tuning, single-slot frame pacer (`FramePacer`), performance presets, auto-reconnect state machine, telemetry collector | Pure Kotlin (no UI) | `:core-rdp` |
| `:app` | Application layer: DI container (`AppContainer`), Navigation (`AppNavHost`), all Compose Material 3 UI (`SessionScreen`, `ProfileListScreen`, `ProfileEditorScreen`, `SettingsScreen`), `RemoteCanvasView` | Jetpack Compose Material 3 + AndroidView | `:core-rdp`, `:feature-mouse`, `:feature-session`, `:feature-telemetry` |

### 1.2 Architectural Dependency Rule
As documented in `PROJECT.md:49-52`:
```
:app -> feature modules -> :core-rdp
```
- No feature module depends on another feature module.
- Nothing outside `:core-rdp` touches JNI or `LibFreeRDP`.
- `:feature-session` and `:feature-telemetry` are **pure logic** without Compose, ensuring JVM unit testability without Robolectric where possible.
- Compose Material 3 UI resides exclusively in `:app`.

---

## 2. Scope 1: Current Session UI & Layout Analysis

### 2.1 `app/.../SessionScreen.kt` Layout Architecture
`SessionScreen.kt` (lines 113–537) implements the root session composable:
- **Root Element**: `Box(Modifier.fillMaxSize().background(Color.Black))`
- **Layer 1: Remote Canvas**:
  - Rendered via `AndroidView` wrapping `RemoteCanvasView` (lines 229–250).
  - Wired to `SessionViewModel`:
    ```kotlin
    view.bindPacer(vm.framePacer)
    view.frameProvider = { vm.currentFrame() }
    view.frameLock = vm.frameSourceLock
    view.onFrameBlitted = { vm.onFrameBlitted() }
    view.gestureEngine = vm.createGestureEngine(view.gestureListener)
    view.mouseController = vm.createMouseController(view.transformer, view)
    ```
- **Layer 2: Floating Mouse Overlay**:
  - Conditionally included when `overlayVisible == true` (lines 253–291).
  - Uses `AndroidView` hosting `FloatingMouseOverlayView`.
  - Position is persisted via SharedPreferences `floating_mouse_overlay_prefs`.
- **Layer 3: Top Status HUD**:
  - `Column` aligned at `Alignment.TopStart` with `statusBarsPadding()`:
    - `ConnectionChip` (phase indicator, host name, demo badge).
    - Zoom percentage pill button: `Surface` showing `"$zoomPercent%"`, tapping calls `canvasView?.resetViewportToFit()`.
    - `HudCard`: Real-time telemetry metrics (FPS, RTT ms, Jitter ms, Bandwidth kbps, Dropped frames, Preset quality).
    - `ReconnectChip`: Reconnection status and cancel button.
- **Layer 4: Overlays & Scrims**:
  - Connecting scrim: Fullscreen semi-transparent surface with `CircularProgressIndicator` and "Cancel connection" button.
  - Failure card: Error code, description, actionable hint, "Enable demo engine & retry", "Retry", "Back to profiles".
- **Layer 5: Bottom Controls**:
  - Aligned at `Alignment.BottomCenter` with `navigationBarsPadding()`:
    - `RemoteKeyboardField`: Shown when `keyboardVisible == true`.
    - `ModifierBar`: Shown when `modifierBarVisible == true`.
    - `QuickToolbar`: Collapsible toolbar with pin support.
- **Layer 6: Dialogs**:
  - `PasswordDialog`, `CertificateDialog` (TOFU fingerprint verification), `ExitDialog`.

### 2.2 `app/.../RemoteCanvasView.kt` Rendering & Touch Handling
`RemoteCanvasView.kt` (280 lines) manages the display surface and touch intake:
- **VSYNC-Driven Frame Pacing**:
  - Uses `Choreographer.FrameCallback` (`vsyncCallback`, lines 132–156).
  - Subscribes via `choreographer.postFrameCallbackDelayed(this, VSYNC_INTERVAL_MS)` (16ms ≈ 60 Hz).
  - Calls `framePacerRef.acquireFrameForVsync(frameTimeNanos)` to pull the freshest composited frame.
  - When a frame is acquired, triggers `invalidate()`.
- **Rendering Pipeline (`onDraw`, lines 230–267)**:
  - Fills black background.
  - Locks `frameLock` and retrieves `backingBitmap` from `frameProvider`.
  - Maps source rectangle `Rect(0, 0, srcW, srcH)` to destination `RectF(translationX, translationY, translationX + srcW * s, translationY + srcH * s)`.
  - Draws optional virtual cursor:
    - Circle of radius 18f with blue fill (`#2196F3` at alpha 120) and white stroke (width 5f).
    - Crosshairs extending 26f in 4 directions.
- **Input & Gesture Delegation (`gestureListener`, lines 72–130)**:
  - `onTouchEvent(event)` delegates directly to `gestureEngine.onTouchEvent(event)`.
  - Disambiguated callback routing:
    - `onSingleTap(x, y)` -> `mouseController.handleLeftClick(x, y)`
    - `onDoubleTap(x, y)` -> `mouseController.handleDoubleClick(x, y)`
    - `onLongPress(x, y)` -> `mouseController.handleRightClick(x, y)`
    - `onPan(dx, dy)`:
      - If `isTouchpadMode`: calls `controller.handleTouchpadMove(dx, dy)`.
      - If `isDragging`: computes desktop delta, calls `controller.handleDragMove(screenPt.x, screenPt.y)`.
      - Else (Direct touch pan): calls `transformer.applyPan(dx, dy)` — pans the viewport **without** sending clicks.
    - `onPinchZoom(focusX, focusY, scaleFactor)` -> `transformer.applyZoom(scaleFactor, focusX, focusY)`.
    - `onTwoFingerScroll(dx, dy)`:
      - If `isTouchpadMode`: accumulates `scrollAccumulator += dy`; fires `controller.handleScroll(0, 0, ±1)` when exceeding `SCROLL_THRESHOLD = 36f`.
      - Else: `transformer.applyPan(dx, dy)` (multi-touch viewport pan).

---

## 3. Scope 2: Current Mouse and Overlay System Analysis

### 3.1 Components in `:feature-mouse`
The `:feature-mouse` module consists of 5 Kotlin files:
1. `CoordinateTransformer.kt` (196 lines):
   - Computes 2D affine transformations between screen viewport and remote RDP desktop.
   - Scale range: `minScale = 0.25f`, `maxScale = 5.0f`.
   - `screenToDesktop(x, y)` & `screenToDesktopInt(x, y)`: Clamps coordinates to `[0, remoteWidth - 1]` and `[0, remoteHeight - 1]`.
   - `desktopToScreen(x, y)`: Projects remote desktop coordinates to screen coordinates.
   - `applyPan(dx, dy)`: Translates viewport with boundary clamping so content doesn't float into void space.
   - `resetToFit()`: Computes `min(viewWidth / remoteWidth, viewHeight / remoteHeight)` and centers the desktop.
2. `MouseController.kt` (198 lines):
   - `interface MouseController`:
     ```kotlin
     val isTouchpadMode: Boolean
     val isDragging: Boolean
     val cursorScreenPosition: PointF?
     val virtualCursorPosition: PointF?
     fun handleLeftClick(screenX: Float, screenY: Float)
     fun handleRightClick(screenX: Float, screenY: Float)
     fun handleDoubleClick(screenX: Float, screenY: Float)
     fun handleDragStart(screenX: Float, screenY: Float)
     fun handleDragMove(screenX: Float, screenY: Float)
     fun handleDragEnd(screenX: Float, screenY: Float)
     fun handleScroll(screenX: Float, screenY: Float, deltaY: Float)
     fun setTouchpadMode(enabled: Boolean)
     fun setCursorVisible(visible: Boolean)
     ```
   - `DefaultMouseController`:
     - Dispatches RDP pointer events to `rdpEngine.sendPointerEvent(flags, tx, ty)`.
     - Maintains `cursorX` and `cursorY` in remote desktop coordinates.
     - In touchpad mode: `cursorX = (cursorX + deltaX * touchpadSensitivity).coerceIn(0f, maxW)`.
     - Safety release: `releaseButtons()` issues `LEFT_BUTTON_UP` if `isDragging` is true.
3. `GestureDisambiguationEngine.kt` (249 lines):
   - Implements Anti-Spurious Click Multi-Touch Latch:
     - On `MotionEvent.ACTION_POINTER_DOWN` or `pointerCount > 1`, `multiTouchLatch = true` and cancels pending long press.
     - While `multiTouchLatch == true`, single finger tap/click is suppressed on `ACTION_UP`.
     - Latch clears only on final finger lift (`ACTION_UP` or `ACTION_CANCEL`).
   - Disambiguates single tap, double tap (`doubleTapTimeoutMs = 300L`, `doubleTapSlop = 48f`), long press (`longPressTimeoutMs = 500L`), single-finger pan, pinch zoom, and two-finger scroll.
4. `OverlayCoordinates.kt` (163 lines):
   - Represents normalized position `(normalizedX, normalizedY)` in `[0.0, 1.0]`.
   - Translates to/from screen pixel coordinates taking `SafeInsets` into account.
   - Provides `snapToNearestEdge` to dock the overlay against the left or right screen border.
5. `FloatingMouseOverlayView.kt` (410 lines):
   - Extends Android `FrameLayout`. Built entirely in imperative code without Compose.
   - Has 3 states: `COLLAPSED` (48dp circular bubble), `EXPANDED` (button palette LinearLayout), `DRAGGING` (repositioning bubble).
   - Palette buttons: LMB, RMB, Drag Toggle, Scroll Up, Scroll Down, Mode Toggle, Cursor Toggle, Collapse.
   - Dispatches clicks to `mouseController` via `getTargetScreenPosition()`.

### 3.2 Key Deficiencies in Mouse System vs Reference `avnc`
1. **Missing Middle Click**: `MouseController` has no `handleMiddleClick(screenX, screenY)`, despite middle click being critical for desktop software (closing tabs, opening links, CAD/terminal paste).
2. **Missing Pointer Acceleration**: `DefaultMouseController.handleTouchpadMove` uses simple linear multiplier `deltaX * touchpadSensitivity`. In contrast, `avnc` implements `PointerAcceleration` (`RelativePointerMode`) with non-linear curve for precise small movements and fast large sweeps.
3. **Imperative View vs Compose**: `FloatingMouseOverlayView` is an Android `FrameLayout` using raw `Button` widgets, rather than a modern Compose overlay like `avnc`'s `VirtualMouseCompose.kt`.

---

## 4. Scope 3: Current Keyboard & Scancode Translation Analysis

### 4.1 `feature-session/.../ScancodeTranslator.kt`
`ScancodeTranslator.kt` (336 lines) defines Windows PC Scancode Set 1 mappings:
- Base scancodes: Escape (`0x01`), 1-0 (`0x02`..`0x0B`), Backspace (`0x0E`), Tab (`0x0F`), Enter (`0x1C`), Space (`0x39`), Letters A-Z, etc.
- Extended scancodes (`isExtended = true`):
  - Left Win (`0x5B`), Right Win (`0x5C`), Apps (`0x5D`)
  - Arrows: Up (`0x48`), Left (`0x4B`), Right (`0x4D`), Down (`0x50`)
  - Navigation: Home (`0x47`), End (`0x4F`), Page Up (`0x49`), Page Down (`0x51`), Insert (`0x52`), Delete (`0x53`)
- Translation functions:
  - `fromAndroidKeyCode(keyCode: Int): ScancodeResult?`
  - `fromChar(char: Char): ScancodeResult?`
  - `getScancodeForModifierKey(key: ModifierKey): ScancodeResult`
  - `getMacroSteps(macro: MacroAction): List<MacroStep>`

### 4.2 Key Event Dispatching to FreeRDP & Protocol Flow
```
SessionScreen (Compose)
   │
   ├── RemoteKeyboardField (OutlinedTextField)
   │     │ onValueChange
   │     ▼
   ├── SessionViewModel.onKeyboardText(text)
   │     │ for each char
   │     ▼
   └── ModifierStateMachine.onNonModifierKeyPressed(char)
         │
         ├── ScancodeTranslator.fromChar(char) -> ScancodeResult(scancode)
         │     ├── rdpEngine.sendKeyEvent(scancode, down = true)
         │     └── rdpEngine.sendKeyEvent(scancode, down = false)
         │
         └── Fallback: rdpEngine.sendUnicodeKeyEvent(char, down = true/false)
```

### 4.3 Critical Architectural Bugs & Missing Features in Keyboard System
1. **Extended Scancode Flag Dropped**:
   - `ScancodeResult` defines `val flags: Int get() = if (isExtended) 0x0100 else 0x0000`.
   - In `ModifierStateMachine.kt`:
     - Line 132: `val scancode = ScancodeTranslator.getScancodeForModifierKey(key).scancode` — ignores `isExtended`!
     - Line 146: `fun onNonModifierKeyPressed(scancode: Int, isExtended: Boolean = false)` accepts `isExtended`, but lines 153–154 call `rdpEngine?.sendKeyEvent(scancode, down = true)` without passing `isExtended`!
     - Line 181: `triggerMacro` iterates `steps.forEach { step -> rdpEngine?.sendKeyEvent(step.scancode, down = step.down) }` — `step.isExtended` is ignored!
     - `IRdpEngine.sendKeyEvent(keyCode: Int, down: Boolean)` does not even accept extended flags. In FreeRDP, extended scancodes must be flagged (e.g. `scancode or 0x0100` or `KBD_FLAG_EXTENDED`).
2. **Zero Key Hold Timing (No BMC Timing)**:
   - When keys or characters are sent, DOWN and UP are fired back-to-back synchronously on the same thread:
     ```kotlin
     rdpEngine?.sendKeyEvent(scancode, down = true)
     rdpEngine?.sendKeyEvent(scancode, down = false)
     ```
   - Windows RDP servers and desktop applications frequently drop keys when the down-to-up interval is 0ms.
   - Reference `avnc` implements `KeyHandler.kt` with BMC key-press hold timing (e.g., holding Enter, Backspace, Space for ~50ms before releasing) to guarantee remote OS event capture.
3. **No Soft Keyboard Real-Time Key Interception**:
   - Input is captured solely via an `OutlinedTextField` string buffer.
   - Special keys (Enter, Tab, Space) typed on Android soft keyboards are either converted to string characters or lost, instead of generating proper hardware/virtual key events.

---

## 5. Scope 4: Existing Test Suites & Gradle Configuration Analysis

### 5.1 Gradle & Toolchain Configuration
- **Root Gradle**: AGP 9.2.1, Kotlin 2.2.20, Compose Compiler plugin 2.2.20.
- **Java Toolchain**: Java 21 across all modules (`jvmToolchain(21)`).
- **SDK Targets**: `compileSdk = 35`, `minSdk = 26`, `targetSdk = 35`.
- **Compose BOM**: `androidx.compose:compose-bom:2024.12.01`.
- **Unit Test Options**: `isIncludeAndroidResources = true`, `isReturnDefaultValues = true`.

### 5.2 Test Matrix by Module
Verified across all 45 test XML files in `*/build/test-results/testDebugUnitTest/`:

| Module | Test Suites | Total Tests | Failures | Errors | Primary Test Target |
|---|---|---|---|---|---|
| `:core-rdp` | 10 | 95 | 0 | 0 | `MockRdpEngine`, `NativeFreeRdpEngine`, JNI contract, `RdpPointerFlags`, `ClipboardHandler`, `DisplayControlHandler` |
| `:feature-mouse` | 4 | 35 | 0 | 0 | `CoordinateTransformer`, `FloatingMouseOverlay`, `GestureDisambiguationEngine`, `DefaultMouseController` |
| `:feature-session` | 5 | 86 | 0 | 0 | `KeystoreCredentialStore`, `ModifierStateMachine`, `ProfileRepository`, `QuickActionToolbarFSM`, `ScancodeTranslator` |
| `:feature-telemetry` | 10 | 121 | 0 | 0 | `AutoReconnectManager`, `FramePacer`, `LowLatencySocketConfig`, `PerformancePreset`, `TelemetryCollector`, `DynamicLayoutListener` |
| `:app` | 16 | 168 | 0 | 0 | 4-Tier E2E tests (`Tier1`–`Tier4`), `SessionViewModelTest`, `UiInteractionSmokeTest`, `AppContainerTest`, `ClipboardSyncTest`, view models |
| **Total** | **45** | **505** | **0** | **0** | **100% Green** |

### 5.3 Test Runners & Testing Utilities
1. **Robolectric**: Version `4.14.1` with `@Config(sdk = [33])` or `[34]`. Used for Android SDK integration (Context, Looper, SharedPreferences, MotionEvent) on JVM.
2. **Deterministic Mock Double (`MockRdpEngine`)**:
   - Implements `IRdpEngine`.
   - Records all events in synchronized thread-safe lists:
     - `recordedPointerEvents: List<PointerEvent>`
     - `recordedKeyEvents: List<KeyEvent>`
     - `recordedUnicodeEvents: List<UnicodeEvent>`
     - `recordedResolutions: List<ResolutionEvent>`
     - `recordedClipboardTexts: List<String>`
3. **Coroutine Testing Harness**:
   - `pumpAll(horizonMs = 600_000L)` in `app/.../TestSupport.kt`:
     Advances `StandardTestDispatcher` and testScheduler virtual time to ensure all background coroutines and ViewModel init collectors execute deterministically.
4. **Compose UI Testing**:
   - `createComposeRule()` in `UiInteractionSmokeTest.kt`:
     Executes click interactions through `AppNavHost` with `shadowOf(Looper.getMainLooper()).idle()` to verify UI state without device hardware.

---

## 6. Detailed Comparison: Target RDP Client vs Reference `avnc`

| Feature / Subsystem | Current `android_rdp_client` | Reference `avnc` Implementation | Required Integration in RDP Client |
|---|---|---|---|
| **Session Toolbar** | Basic horizontal `Row` at bottom center; toggles keyboard, mouse, zoom, HUD; auto-collapses in 4s | Collapsible side Drawer / overlay (`Toolbar.kt`, `LayoutManager.kt`) with flyouts for input mode, zoom lock/save, keyboard toggle | Adopt collapsible in-session toolbar pattern with input mode selector (Direct Touch vs Touchpad), zoom mode toggle, keyboard toggle, and disconnect |
| **Virtual Keys Bar** | Simple horizontal scroll row: 4 modifiers (Ctrl, Alt, Win, Esc) + F1-F12 + 4 macro buttons | Rich Compose overlay (`VirtualKeysCompose.kt`): Collapsible Fn strip (F1-F12), sticky modifiers (Ctrl, Alt, Shift, Win), Esc, Tab, Del, Inverted-T arrow cluster, scroll buttons | Port `VirtualKeysCompose.kt` RealVNC layout with sticky modifier latching, inverted-T arrow keys, Esc/Tab/Del into `:app` |
| **Virtual Mouse / Floating Overlay** | Imperative Android `FrameLayout` (`FloatingMouseOverlayView.kt`); LMB, RMB, Drag, Scroll, Mode toggle; **no middle click**; fixed sensitivity | Compose-based overlay (`VirtualMouseCompose.kt`) with draggable floating bubble, expanding into LMB, RMB, MMB (Middle), scroll, drag lock | Modernize mouse controls into Compose or upgrade overlay to include Middle Click, drag lock, and smooth scrolling controls |
| **Touch / Touchpad Engine** | `DefaultMouseController` + `GestureDisambiguationEngine`; linear mouse movement, no pointer acceleration | `TouchHandler.kt`, `PointerModes.kt` (`DirectPointerMode` vs `RelativePointerMode`), `PointerAcceleration` | Add dual pointer mode engine (`DirectTouch` vs `Touchpad` with acceleration curve) feeding RDP pointer flags |
| **Keyboard Input & Timing** | `OutlinedTextField` string buffer; instant key DOWN+UP (0ms hold); ignores `isExtended` scancodes | `KeyHandler.kt`, `Keyboard.kt`; BMC hold timing (~50ms) for Enter, Backspace, Space, Tab; proper IME visibility tracking | Integrate soft-keyboard input handling with BMC key hold timing and proper extended scancode transmission (`scancode or 0x0100`) |

---

## 7. Integration Hook Points & Migration Roadmap

### 7.1 Integration Hook Points
1. **Toolbar Integration Point**:
   - File: `app/src/main/java/com/freerdp/client/ui/session/SessionScreen.kt` (lines 465–500, `bottom controls` column, and lines 767–857 `QuickToolbar`).
   - Hook: Replace/upgrade `QuickToolbar` composable with collapsible toolbar providing direct controls for Soft Keyboard, Input Mode switch (Touchpad vs Direct), Virtual Keys toggle, Zoom/Fit toggle, and Disconnect.
2. **Virtual Keys Bar Integration Point**:
   - File: `app/src/main/java/com/freerdp/client/ui/session/SessionScreen.kt` (lines 478–489 `ModifierBar`).
   - Hook: Replace `ModifierBar` with Compose RealVNC-style `VirtualKeysOverlay` incorporating Fn strip, sticky modifiers (Ctrl, Alt, Shift, Win), Esc, Tab, Delete, and Inverted-T arrow cluster.
   - Connect to `SessionViewModel.modifierMachine` and `ScancodeTranslator`.
3. **Input Engine & Mouse Mode Hook Point**:
   - Files: `feature-mouse/src/main/java/com/freerdp/feature/mouse/MouseController.kt`, `CoordinateTransformer.kt`, and `app/.../RemoteCanvasView.kt`.
   - Hook:
     - Add `handleMiddleClick(screenX: Float, screenY: Float)` to `MouseController` and `DefaultMouseController` using `RdpPointerFlags.MIDDLE_BUTTON_DOWN` (`0x4000 or 0x8000`) and `MIDDLE_BUTTON_UP` (`0x4000`).
     - Introduce pointer acceleration to relative touchpad motion in `DefaultMouseController` or an input mode handler.
4. **Keyboard Handling & Scancode Dispatch Hook Point**:
   - Files:
     - `feature-session/src/main/java/com/freerdp/feature/session/keyboard/ScancodeTranslator.kt`
     - `feature-session/src/main/java/com/freerdp/feature/session/modifier/ModifierStateMachine.kt`
     - `core-rdp/src/main/java/com/freerdp/core/engine/IRdpEngine.kt`
   - Hook:
     - Ensure extended scancodes (`isExtended == true`) are encoded properly into key events (e.g. `scancode or 0x0100` or flag parameter) so Windows/Arrows/Delete are not truncated.
     - Implement BMC key hold timing (coroutine delay between key-down and key-up, e.g. 50ms) for special keys (Enter, Backspace, Space, Tab).
     - Wire soft keyboard IME input directly rather than relying on an `OutlinedTextField` string consumer.

---

## 8. Summary of Findings & Actionable Recommendations

1. **Architecture Integrity**: Clean Architecture separation across `:core-rdp`, `:feature-mouse`, `:feature-session`, `:feature-telemetry`, and `:app` is clean and well-enforced. New Compose UI components belong strictly in `:app`, while protocol logic and scancodes belong in `:feature-session` and `:feature-mouse`.
2. **Current Test Suite**: 505 unit/Robolectric tests are active and 100% passing. Any modifications must maintain 100% pass rate across these suites.
3. **Porting Readiness**:
   - `avnc` contains clean reference implementations for `VirtualKeysCompose.kt`, `KeyHandler.kt`, `PointerModes.kt`, and `Toolbar.kt`.
   - These patterns can be adapted into `android_rdp_client` with clear separation: Compose overlays go into `:app/ui/session/`, input modes and pointer math go into `:feature-mouse/`, and keyboard timing/scancodes go into `:feature-session/` and `:app/session/`.
