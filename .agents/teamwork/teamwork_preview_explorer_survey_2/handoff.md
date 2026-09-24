# Handoff Report: Target Android RDP Client Architecture & Survey

**Agent**: `teamwork_preview_explorer_survey_2` (Explorer)  
**Parent**: `parent` (`f0fc1f73-b43f-468a-ab50-e5ec45aedb66`)  
**Scope**: Codebase architecture, input subsystem, UI layout, and test suite of `android_rdp_client` (`C:\Users\Administrator\teamwork_projects\android_rdp_client`)  
**Deliverable**: `C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\teamwork\teamwork_preview_explorer_survey_2\survey_rdp_client.md`

---

## 1. Observation

1. **Session Screen & Canvas Architecture**:
   - `app/src/main/java/com/freerdp/client/ui/session/SessionScreen.kt` lines 229–250 embeds `RemoteCanvasView` via Compose `AndroidView`:
     ```kotlin
     RemoteCanvasView(ctx).also { view ->
         view.bindPacer(vm.framePacer)
         view.frameProvider = { vm.currentFrame() }
         view.frameLock = vm.frameSourceLock
         view.onFrameBlitted = { vm.onFrameBlitted() }
         view.gestureEngine = vm.createGestureEngine(view.gestureListener)
         view.mouseController = vm.createMouseController(view.transformer, view)
         view.setZoomCallback { scale -> zoomPercent = (scale * 100).toInt() }
         canvasView = view
     }
     ```
   - In `app/src/main/java/com/freerdp/client/ui/session/RemoteCanvasView.kt`:
     - Line 132–156: `vsyncCallback` pulls frames via `framePacerRef.acquireFrameForVsync(frameTimeNanos)` on a 16ms delayed Choreographer cadence (`VSYNC_INTERVAL_MS = 16L`).
     - Line 225–228: `onTouchEvent` forwards all touch inputs to `gestureEngine?.onTouchEvent(event)`.
     - Lines 72–130: `gestureListener` routes disambiguated gestures to `mouseController` (tap -> left click, double tap -> double click, long press -> right click, pan -> touchpad move / drag / viewport pan, pinch zoom -> affine zoom, two finger scroll -> touchpad scroll / viewport pan).
     - Lines 243–251: Custom virtual cursor is drawn on canvas if `controller.isCursorVisible`.
   - Lines 465–500 in `SessionScreen.kt`: Bottom controls stack:
     - `RemoteKeyboardField`: Standard `OutlinedTextField` capturing text changes.
     - `ModifierBar`: Horizontal scroll row with 3-state latching buttons (Ctrl, Alt, Win, Esc) + F1-F12 + macros.
     - `QuickToolbar`: Collapsible row with 4s auto-collapse timer (`QuickActionToolbarFSM`).

2. **Mouse Subsystem & Missing Middle Click**:
   - In `feature-mouse/src/main/java/com/freerdp/feature/mouse/MouseController.kt` (lines 11–25):
     `MouseController` defines `handleLeftClick`, `handleRightClick`, `handleDoubleClick`, `handleDragStart`, `handleDragMove`, `handleDragEnd`, `handleScroll`, `setTouchpadMode`, `setCursorVisible`.
     **It has NO middle click method.**
   - In `core-rdp/src/main/java/com/freerdp/core/protocol/RdpPointerFlags.kt` (lines 12, 22–23):
     ```kotlin
     const val PTR_FLAGS_BUTTON3 = 0x4000
     const val MIDDLE_BUTTON_DOWN = PTR_FLAGS_BUTTON3 or PTR_FLAGS_DOWN
     const val MIDDLE_BUTTON_UP = PTR_FLAGS_BUTTON3
     ```
     Middle click flags are defined in `:core-rdp`, but unexposed and uncalled by `:feature-mouse`.
   - In `feature-mouse/src/main/java/com/freerdp/feature/mouse/FloatingMouseOverlayView.kt` (lines 30–97):
     Floating overlay is an imperative Android `FrameLayout` created in code with subviews `bubbleView` and `paletteView` (with buttons: LMB, RMB, Drag Toggle, Scroll Up/Down, Mode Toggle, Cursor Toggle, Collapse).

3. **Keyboard & Scancode Translation Deficiencies**:
   - In `feature-session/src/main/java/com/freerdp/feature/session/keyboard/ScancodeTranslator.kt` (lines 7–12):
     ```kotlin
     data class ScancodeResult(
         val scancode: Int,
         val isExtended: Boolean = false
     ) {
         val flags: Int get() = if (isExtended) 0x0100 else 0x0000
     }
     ```
   - In `feature-session/src/main/java/com/freerdp/feature/session/modifier/ModifierStateMachine.kt`:
     - Line 132: `val scancode = ScancodeTranslator.getScancodeForModifierKey(key).scancode` ignores `isExtended` (e.g. for `ModifierKey.WIN` where `isExtended = true`).
     - Lines 146–154: `fun onNonModifierKeyPressed(scancode: Int, isExtended: Boolean = false)` drops `isExtended`, calling `rdpEngine?.sendKeyEvent(scancode, down = true)` directly.
     - Lines 181–184: `triggerMacro` iterates `steps.forEach { step -> rdpEngine?.sendKeyEvent(step.scancode, down = step.down) }`, completely ignoring `step.isExtended`.
     - Lines 153–154, 162–163, 174–175: Keys are sent with instant DOWN followed immediately by UP on the same thread with 0ms delay (no key-hold duration).
   - In `core-rdp/src/main/java/com/freerdp/core/engine/IRdpEngine.kt` (line 12):
     `fun sendKeyEvent(keyCode: Int, down: Boolean)` accepts only `keyCode` and `down`, without extended scancode flags.

4. **Test Suite Status & Configuration**:
   - `build.gradle.kts`: AGP 9.2.1, Kotlin 2.2.20, Java 21, Compose BOM 2024.12.01, compileSdk 35, minSdk 26.
   - Command executed:
     `powershell -NoProfile -Command "foreach ($m in @('core-rdp', 'feature-mouse', 'feature-session', 'feature-telemetry', 'app')) { ... }"`
   - Output observed:
     - `core-rdp`: 10 suites, 95 tests, 0 failures, 0 errors
     - `feature-mouse`: 4 suites, 35 tests, 0 failures, 0 errors
     - `feature-session`: 5 suites, 86 tests, 0 failures, 0 errors
     - `feature-telemetry`: 10 suites, 121 tests, 0 failures, 0 errors
     - `app`: 16 suites, 168 tests, 0 failures, 0 errors
     - **Total**: 45 test suites, 505 tests, 0 failures, 0 errors.

5. **Reference VNC Codebase (`C:\Users\Administrator\avnc`)**:
   - `app/src/main/java/com/vncandroid/free/ui/vnc/VirtualKeysCompose.kt`: 862-line Material 3 Compose virtual keys overlay with expandable Fn strip, sticky modifiers (Ctrl, Alt, Shift, Win), Esc, Tab, Del, and inverted-T arrow cluster.
   - `app/src/main/java/com/vncandroid/free/ui/vnc/input/PointerModes.kt`: `DirectPointerMode` vs `RelativePointerMode` (with `PointerAcceleration`).
   - `app/src/main/java/com/vncandroid/free/ui/vnc/input/KeyHandler.kt`: BMC key-press hold timing and IME key event translation.

---

## 2. Logic Chain

1. **Premise 1 (Module Boundary)**: Clean Architecture dictates `:app` owns all Compose UI, while `:feature-mouse` and `:feature-session` own input and session logic without depending on Compose (Observation 1, 4).
2. **Premise 2 (Input Deficiencies)**: While `:core-rdp` has protocol support for mouse button 3 (`RdpPointerFlags.MIDDLE_BUTTON_DOWN/UP`) and extended scancodes (0x0100), neither `MouseController` nor `ModifierStateMachine` utilizes them (Observation 2, 3).
3. **Premise 3 (Keyboard Hold Timing)**: Disagreeing with real-world OS keyboard scan semantics, `ModifierStateMachine` and `SessionViewModel` issue instant DOWN followed by UP on the same thread without delay. This causes dropped keys in remote applications (Observation 3).
4. **Premise 4 (Ergonomics Gap)**: Compared to `avnc`'s `VirtualKeysCompose.kt`, `android_rdp_client`'s bottom modifier bar is an un-ergonomic single scroll line without an inverted-T arrow cluster or sticky shift, and the floating overlay is an imperative View rather than a Compose overlay (Observation 1, 2, 5).
5. **Deduction**: Porting the Compose virtual keys overlay and input patterns from `avnc` into `android_rdp_client` will resolve the ergonomic and functional gaps while respecting the existing module boundaries (`:app` for Compose UI, `:feature-mouse` for pointer modes/acceleration/middle-click, `:feature-session` for scancodes/latching, `:core-rdp` for protocol transport).

---

## 3. Caveats

1. **JNI Native Library Packaging**: `LibFreeRDP.java` notes that pre-built native libraries (`libwinpr3.so`, `libfreerdp3.so`, `libfreerdp-android.so`) are not packaged directly in the repository; the application falls back gracefully to `MockRdpEngine` or code 1001 UX in JVM/test environments.
2. **Robolectric vs Device Execution**: All 505 existing tests run on host JVM via Robolectric and MockRdpEngine. Tests asserting Compose UI use `createComposeRule()` with `shadowOf(Looper.getMainLooper()).idle()`.
3. **Scope Discipline**: This task was strictly read-only. No source files or tests were modified during this investigation.

---

## 4. Conclusion

1. The target Android RDP client has a robust, clean modular architecture and an existing 505-test suite running 100% green.
2. The specific areas requiring porting and integration from `avnc` are clearly isolated:
   - **Collapsible In-Session Toolbar**: Integrate into `app/.../SessionScreen.kt` replacing/enhancing `QuickToolbar`.
   - **RealVNC-Style Virtual Keys Bar**: Port `VirtualKeysCompose.kt` into `app/.../ui/session/` with Fn strip, sticky modifiers (Ctrl/Alt/Shift/Win), Esc/Tab/Del, and Inverted-T arrow cluster.
   - **Multi-Mode Mouse & Touchpad**: Add Middle Click to `MouseController` and `DefaultMouseController`; incorporate pointer acceleration into touchpad mode.
   - **Keyboard Handling & Timing**: Implement BMC key hold timing for Enter, Backspace, Space, Tab; fix extended scancode propagation (`isExtended` -> `0x0100`).
3. Comprehensive findings and integration hook points are recorded in `survey_rdp_client.md`.

---

## 5. Verification Method

1. **Inspect Survey Report**:
   Read `C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\teamwork\teamwork_preview_explorer_survey_2\survey_rdp_client.md` to verify the module maps, line numbers, and architectural hook points.
2. **Verify Existing Test Suite Pass Rate**:
   Execute from project root:
   ```cmd
   .\gradlew.bat testDebugUnitTest
   ```
   *Expected outcome*: `BUILD SUCCESSFUL`, 505 tests pass with 0 failures and 0 errors across `:core-rdp`, `:feature-mouse`, `:feature-session`, `:feature-telemetry`, and `:app`.
3. **Verify Key Inspection Points**:
   - `MouseController.kt`: Verify absence of middle click method.
   - `ModifierStateMachine.kt:132-154`: Verify `isExtended` is ignored on key dispatch.
   - `SessionScreen.kt:229-250`: Verify `RemoteCanvasView` wiring and bottom controls stack.
