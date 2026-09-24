# Comprehensive Specification & UX Pattern Survey: AVNC Reference Architecture for Android RDP Client

**Author:** `teamwork_preview_spec_miner_survey_1` (Spec Miner)  
**Date:** 2026-09-24  
**Target Project:** `android_rdp_client` (`C:\Users\Administrator\teamwork_projects\android_rdp_client`)  
**Reference Codebase:** `avnc` (`C:\Users\Administrator\avnc`)  

---

## Executive Summary

This specification report documents the user experience (UX) patterns, input handling architectures, coordinate systems, state machines, math formulas, and hardware timing constants extracted from the reference AVNC codebase (`C:\Users\Administrator\avnc`). 

The Android RDP client project requires porting these mature components to replace rudimentary touch/mouse overlays and resolve touch, timing, and UI bugs. This document serves as the authoritative blueprint for implementation across the RDP client modules (`:app`, `:feature-mouse`, `:feature-session`).

---

## 1. Collapsible In-Session Toolbar & Navigation

### 1.1 Architecture & Layout Topology

The toolbar system in AVNC is implemented using a root `androidx.drawerlayout.widget.DrawerLayout` containing the remote desktop viewport in its main content area, with a custom `toolbar_drawer.xml` acting as the sliding drawer view.

```
                  DrawerLayout (root)
  +-----------------------------------------------------+
  |                                                     |
  |  +-------------------+                              |
  |  |  Toolbar Drawer   |                              |
  |  |   [drawerView]    |                              |
  |  |                   |                              |
  |  | +---+             |                              |
  |  | | P |             |                              |
  |  | | r |             |                              |
  |  | | i | +---------+ |           Scrim              |
  |  | | m | | Flyouts | |       (Transparent:          |
  |  | | a | | (Groups)| |     app:scrimColor="@{0}")   |
  |  | | r | +---------+ |                              |
  |  | | y |             |                              |
  |  | +---+             |                              |
  |  +-------------------+                              |
  |                                                     |
  |  [open_toolbar_btn] (Floating opener pill)          |
  +-----------------------------------------------------+
```

#### Primary Files
- **View Binding & Drawer Logic:** `com.vncandroid.free.ui.vnc.Toolbar.kt`
- **Layout Definition:** `app/src/main/res/layout/toolbar_drawer.xml`
- **Container Hierarchy:** `app/src/main/res/layout/activity_vnc.xml`
- **Insets & Window Fitting:** `com.vncandroid.free.ui.vnc.LayoutManager.kt`
- **Activity Hooks & User Help:** `com.vncandroid.free.ui.vnc.VncActivity.kt`

### 1.2 Layout Configuration & Alignment

1. **Alignment:**
   - Toolbar is aligned to either the start or end edge based on `pref.viewer.toolbarAlignment` (`"start"` or `"end"`).
   - Drawer gravity is assigned via `GravityCompat.START` or `GravityCompat.END`.
   - Layout direction is forced to `View.LAYOUT_DIRECTION_LTR` when left-aligned and `View.LAYOUT_DIRECTION_RTL` when right-aligned (regardless of system locale), ensuring button ordering and flyouts consistently expand inward towards screen center.
   - Text elements (such as `gesture_style_group`) retain natural system layout direction.

2. **Transparent Scrim & Dismiss:**
   - In `activity_vnc.xml`, `DrawerLayout` is configured with `app:scrimColor="@{0}"` (completely transparent).
   - Clicking anywhere outside the primary buttons hits the transparent `drawerView` root or the scrim; `drawerView.setOnClickListener { close() }` ensures a tap dismisses the toolbar immediately without blocking canvas view.
   - **Scrim Swipe-to-Close:** Standard Android `DrawerLayout` only closes when swiping directly *on* the drawer view. AVNC implements `setupDrawerCloseOnScrimSwipe()` using a custom `GestureDetector` on `drawerLayout` that catches any closing fling (`vX < 0` for left-aligned or `vX > 0` for right-aligned) and closes the drawer.

3. **Display Cutout / Notch Avoidance:**
   - Handled in `Toolbar.handleInsets(insets: WindowInsetsCompat)`:
   - Compares the `getActionableToolbarRect()` of `binding.primaryButtons` with `cutout.boundingRects`.
   - If a cutout intersects the actionable toolbar rectangle, `v.fitsSystemWindows = true` is set, dynamically applying padding to keep all buttons fully accessible.

4. **Android 10+ Gesture Navigation Exclusion:**
   - In Android 10+ (API 29+), edge swipes for drawer navigation conflict with system back gestures.
   - `updateGestureExclusionRect()` calculates `getActionableToolbarRect()` covering `primaryButtons`.
   - In fullscreen mode, Android allows padding exclusion areas beyond the 200dp limit. AVNC pads by one-sixth of available height in each direction:
     $$\text{padding} = \frac{\text{drawerLayout.height} - \text{rect.height()}}{6}$$
     $$\text{rect.top} -= \text{padding}, \quad \text{rect.bottom} += \text{padding}$$
     `drawerLayout.systemGestureExclusionRects = listOf(rect)`

### 1.3 Opener Button (`open_toolbar_btn`)

When `toolbarOpenWithButton` preference is true, an edge-docked draggable pill button (`open_toolbar_btn`) is visible:
- **Dimensions:** 32dp width, 36dp height, rounded rectangle ripple background (`bg_round_rect_ripple`), alpha = 0.50.
- **Draggable Positioning:**
  ```kotlin
  val parentTouchY = openerButton.y + e2.y
  verticalBias = parentTouchY / parentHeight
  val minY = btn.marginTop
  val maxY = parentHeight - btn.height - btn.marginBottom
  val y = (parentHeight * verticalBias).toInt().coerceAtMost(maxY).coerceAtLeast(minY)
  btn.y = y.toFloat()
  ```
- **Persistence:** On `MotionEvent.ACTION_UP`, `verticalBias` is saved to `pref.runInfo.toolbarOpenerBtnVerticalBias`.
- **Tap Action:** Calls `toolbar.open()`.

### 1.4 Primary Actions & Flyout Panels

The toolbar primary vertical strip contains:
1. `keyboard_btn` (`ic_keyboard`): Calls `activity.showKeyboard(); close()`.
2. `virtual_keys_btn` (`ic_keyboard_mini`): Calls `activity.virtualKeys.show(true); activity.virtualMouse.show(); close()`.
3. `mouse_btn` (`ic_mouse`): Calls `activity.virtualMouse.toggle(); close()`.
4. `view_modes_toggle` (`ic_visibility`): Toggles `viewModeGroup` flyout.
5. `gesture_style_toggle` (`ic_gesture`): Toggles `gestureStyleGroup` flyout.
6. `zoom_options_toggle` (`ic_zoom_options`): Toggles `zoomOptionsGroup` flyout. Long-click on toggle resets zoom to default.

#### Flyout Panel Behaviors
- **Mutual Exclusion:** Opening one flyout unchecks all other flyout toggles.
- **Closing Animation Synchronization:** `setupFlyoutClose()` hooks `DrawerLayout.SimpleDrawerListener.onDrawerClosed()`. Flyouts are hidden *only after* the drawer has completely closed. Changing view width while the drawer is animating causes layout stutter or drawer glitch.
- **Zoom Flyout Controls:**
  - `zoom_reset_btn`: Short click resets to default scale (`zoom1`, `zoom2`). Long click resets to 1.0 (100%).
  - `zoom_lock_btn`: Toggles `profile.fZoomLocked`.
  - `zoom_save_btn`: Persists current `frameState.zoomScale1` & `zoomScale2` into `profile`.
- **View Mode Flyout Controls:**
  - Normal (`ServerProfile.VIEW_MODE_NORMAL`): Video streaming + remote input enabled.
  - No Input (`ServerProfile.VIEW_MODE_NO_INPUT`): Video streaming only; local input ignored.
  - No Video (`ServerProfile.VIEW_MODE_NO_VIDEO`): Audio/Touchpad only; remote video frames paused to save bandwidth. Automatically forces touchpad mode and displays `no_video_overlay`.
- **Gesture Style Flyout Controls:**
  - Auto (`"auto"`), Touchscreen (`"touchscreen"` / Direct Touch), Touchpad (`"touchpad"`).

### 1.5 Session Termination & First-Run User Help

- **Clean Disconnect:** Triggered via Android Back navigation (`onBackPressed()` / `finish()`). In `onStop()`, active modifiers and pointer buttons are released, OpenGL surfaces paused, and resources freed.
- **First-Run Guide (`ViewerHelpBinding`):**
  If `pref.runInfo.hasShownViewerHelp == false`, an overlay is inserted at index 1 of `drawerLayout`:
  - Page 1: Displays `viewer_help_toolbar_animation` teaching swipe-from-edge or button tap to open toolbar.
  - Page 2: Displays `viewer_help_navbar_animation` teaching that tapping the Android Back button ends the session cleanly.

---

## 2. RealVNC-Style Virtual Keys Bar

### 2.1 Compose Overlay Architecture (`VirtualKeysCompose.kt`)

The virtual keys bar is written in Jetpack Compose (`VirtualKeysOverlay`), hosted inside an `androidx.compose.ui.platform.ComposeView` (`@id/virtual_keys_compose_view`) positioned at `bottom|center_horizontal`.

```
  +-----------------------------------------------------------------------------------------+
  |  [FN]  [F1] [F2] [F3] [F4]  |  [F5] [F6] [F7] [F8]  |  [F9] [F10] [F11] [F12] (Anim Fn) |
  +-----------------------------------------------------------------------------------------+
  | [Kb] | [Fn] | Esc  Tab  Win  Del | Home  [ ^ ]  PgUp | [ ^ ] Scroll Up   |    [ X ]     |
  | [Ms] |  *   | Ctrl Alt Shift Caps| End [<][v][>]PgDn | [ v ] Scroll Down |   (Close)    |
  |Zone1 |Zone1 |      Zone 2        |      Zone 3       |      Zone 4       |    Zone 5    |
  +-----------------------------------------------------------------------------------------+
```

#### UI Styling Properties
- **Container Surface:** `RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 8.dp, bottomEnd = 8.dp)`
- **Background Color:** `MaterialTheme.colorScheme.surface.copy(alpha = 0.80f)` (20% translucent)
- **Elevation:** Tonal elevation 8.dp, Shadow elevation 10.dp
- **Border:** 1.dp border with `MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)`
- **Dividers:** 1.dp vertical bars (`BarDivider()`) with 64.dp height between functional zones.
- **Transitions:** `slideInVertically(initialOffsetY = { it }) + fadeIn()` on entrance, `slideOutVertically(targetOffsetY = { it }) + fadeOut()` on exit.

### 2.2 Functional Zones Breakdown

#### Zone 1: Quick Action / Mode Switchers
1. **Vertical Column:**
   - **Keyboard Toggle:** 38dp x 34dp rounded button (`ic_keyboard`), calls `virtualKeys.toggleKeyboard()`.
   - **Mouse Controls Toggle:** 38dp x 34dp button (`ic_mouse`), border stroke 1.5dp when active, calls `virtualKeys.toggleMouse()`.
2. **Fn Toggle Button:**
   - 36dp width x 72dp height (spans full 2-row height).
   - Toggles expandable `FunctionKeysStrip` (F1–F12).
   - Contains a small 4dp circular dot indicator below "Fn" text when active.

#### Zone 2: Essential Desktop Controls (2 Rows)
- **Row 1:**
  - `Esc`: `KEYCODE_ESCAPE`, minWidth = 44.dp, height = 34.dp.
  - `Tab`: `KEYCODE_TAB`, minWidth = 44.dp, height = 34.dp.
  - `Win`: Dedicated Windows key (`VirtualKey.LeftSuper`), minWidth = 52.dp, height = 34.dp. Contains Windows logo icon + "Win" text + lock indicator dot.
  - `Del`: Forward Delete (`KEYCODE_FORWARD_DEL`), minWidth = 46.dp, height = 34.dp, errorContainer tint.
- **Row 2:**
  - `Ctrl`: `VirtualKey.LeftCtrl`, minWidth = 44.dp, height = 34.dp.
  - `Alt`: `VirtualKey.LeftAlt`, minWidth = 44.dp, height = 34.dp.
  - `Shift`: `VirtualKey.LeftShift`, minWidth = 52.dp, height = 34.dp.
  - `Caps`: `VirtualKey.CapsLock`, minWidth = 46.dp, height = 34.dp.

#### Zone 3: RealVNC Aligned Navigation Cluster
Ergonomically spaced 3-column navigation:
- **Left Column:** `Home` (top, 46x34dp) and `End` (bottom, 46x34dp).
- **Center Column (Inverted-T Arrow Cluster):**
  - Up Arrow centered directly above Down Arrow: width = 54.dp, height = 34.dp.
  - Bottom row: Left Arrow (48x34dp), Down Arrow (54x34dp), Right Arrow (48x34dp).
- **Right Column:** `PgUp` (top, 46x34dp) and `PgDn` (bottom, 46x34dp).

#### Zone 4: Big Scroll Up & Scroll Down Controls
- Designed for rapid touch scrolling with generous target sizing.
- Width = 54.dp, Height = 34.dp each, stacked vertically.
- Icons: 22dp `KeyboardArrowUp` and `KeyboardArrowDown`.
- Continuous Scroll Acceleration:
  ```kotlin
  LaunchedEffect(isHolding) {
      if (isHolding) {
          onScroll()
          delay(200) // Initial trigger delay
          while (isHolding) {
              onScroll()
              delay(50) // Accelerated continuous stream (20 events/sec)
          }
      }
  }
  ```

#### Zone 5: Dismiss / Close Button
- Width = 34.dp, Height = 72.dp (spans full 2-row height).
- Background: `MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.65f)`.
- Icon: `ic_clear` (18dp).
- Action: Calls `virtualKeys.hide(saveVisibility = true)`.

### 2.3 Expandable Function Keys Strip (F1–F12)

Animated visibility above the main bar:
- `slideInVertically(initialOffsetY = { -it }) + fadeIn()`
- Horizontal scroll container with 4dp spacing between buttons.
- "FN" badge at start: 10sp bold text inside `primaryContainer`.
- 3 distinct function clusters separated by 6dp spacers:
  - Cluster 1: F1, F2, F3, F4 (minWidth = 42.dp, height = 32.dp).
  - Cluster 2: F5, F6, F7, F8.
  - Cluster 3: F9, F10, F11, F12.
- Support hold-to-repeat using Android `ViewConfiguration` repeat constants.

### 2.4 Modifier Key Tri-State Machine (`VirtualKeys.kt`)

Modifier keys (`Ctrl`, `Alt`, `Shift`, `Win`, `Caps`) implement a tri-state latching and locking mechanism:

```
                  +---------------------------+
                  |  State 0: Inactive / Off  |
                  +---------------------------+
                     /                     \
        Single Tap  /                       \  Long Press
                   v                         v
  +-------------------------------+   +-----------------------------+
  |   State 1: Latched / Sticky   |   |   State 2: Locked / Held    |
  | - activeToggleKeys = true     |   | - activeToggleKeys = true   |
  | - lockedToggleKeys = false    |   | - lockedToggleKeys = true   |
  | - Border: 1.5dp primary       |   | - Solid primary background  |
  | - Color: primaryContainer     |   | - Indicator dot visible     |
  +-------------------------------+   +-----------------------------+
          |                  \                       |
  Non-modifier key            \ Tap again            | Tap or Long-press
  ACTION_UP received           \                     | again
          |                     \                    |
          v                      v                   v
  +-----------------------------------------------------------------+
  |                  Release to State 0 (Inactive)                  |
  +-----------------------------------------------------------------+
```

#### State Machine Implementation Details
1. **Data Structures:**
   ```kotlin
   val activeToggleKeys = mutableStateMapOf<VirtualKey, Boolean>()
   val lockedToggleKeys = mutableStateMapOf<VirtualKey, Boolean>()
   ```
2. **Single Tap (`onToggleKeyClick`):**
   - If Windows key and `pref.input.vkUseSuperWithSingleTap` is true: sends a transient press and release (`sendKey(keyCode)`), allowing one-tap Start menu opening without locking modifiers.
   - If currently inactive: sets `activeToggleKeys[vk] = true`, dispatches `ACTION_DOWN` to remote session.
   - If currently active: clears active and locked states, dispatches `ACTION_UP`. If no modifiers remain active, calls `messenger.releaseAllModifiers()`.
3. **Long Press (`onToggleKeyLongClick`):**
   - If currently locked: unlocks and clears state, dispatches `ACTION_UP`.
   - If not locked: sets `lockedToggleKeys[vk] = true` and `activeToggleKeys[vk] = true`, dispatches `ACTION_DOWN`.
4. **Auto-Unlatch on Key Up (`releaseUnlockedMetaKeys`):**
   - Registered as `onAfterKeyEvent` hook on `InputHandler`.
   - When a non-modifier key is released (`event.action == ACTION_UP && !KeyEvent.isModifierKey(event.keyCode)`):
     - All keys with `activeToggleKeys == true` and `lockedToggleKeys != true` are set to `false`.
     - `ACTION_UP` is dispatched to the remote session for each.
     - Any key in `lockedToggleKeys == true` remains latched.
5. **Session Teardown Release:**
   - `releaseMetaKeys()` iterates all active modifiers, sends `ACTION_UP`, and invokes protocol-level modifier purge (`releaseAllModifiers()`).

---

## 3. Keyboard Input Handling & BMC Timing

### 3.1 Input Pipeline Overview

```
 [Soft Keyboard / IME]     [Hardware Keyboard]       [Virtual Keys Bar]
          |                         |                        |
          v                         |                        |
  +----------------+                |                        |
  |   InputView    |                |                        |
  | InputConnection|                |                        |
  +----------------+                |                        |
          |                         v                        |
          +-----------------> [KeyHandler] <-----------------+
                                    |
                             [Dispatcher]
                                    |
                              [Messenger]
                        (Single-Thread Executor)
                                    |
                                    v
                         [RDP / FreeRDP Engine]
```

#### Primary Files
- **IME Connection & Soft Keyboard Interception:** `com.vncandroid.free.ui.vnc.input.InputView.kt`
- **Scancode/KeySym Resolution & Fake Modifiers:** `com.vncandroid.free.ui.vnc.input.KeyHandler.kt`
- **Hardware Pacing & BMC Duration:** `com.vncandroid.free.session.Messenger.kt`
- **Soft Keyboard Helpers:** `com.vncandroid.free.util.Keyboard.kt`

### 3.2 Soft Keyboard Input Interception (`InputView.kt`)

Modern Android software keyboards (Gboard, SwiftKey, Samsung Keyboard) do not reliably generate standard `onKeyDown`/`onKeyUp` events for text input. AVNC solves this with a custom `BaseInputConnection`:

1. **`EditorInfo` Configuration:**
   ```kotlin
   outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or
           EditorInfo.IME_FLAG_NO_EXTRACT_UI or
           EditorInfo.IME_FLAG_NO_FULLSCREEN
   outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
           InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
           InputType.TYPE_TEXT_FLAG_MULTI_LINE
   ```
2. **Enter & Backspace Interception:**
   - `sendKeyEvent(event)`:
     - `KEYCODE_ENTER` + `ACTION_DOWN` $\rightarrow$ calls `sendEnterKey()`, returns `true`.
     - `KEYCODE_DEL` + `ACTION_DOWN` $\rightarrow$ calls `sendBackspaceKey()`, returns `true`.
   - `performEditorAction(actionCode)` $\rightarrow$ calls `sendEnterKey()`, returns `true`.
   - `deleteSurroundingText(beforeLength, afterLength)`:
     - Repeats `sendBackspaceKey()` `beforeLength` times, returns `true`.
3. **`commitText(text, newCursorPosition)` Handling:**
   - `"\n"`, `"\r"`, or `"\r\n"` $\rightarrow$ `sendEnterKey()`.
   - `" "` $\rightarrow$ `sendSpaceKey()`.
   - `"\t"` $\rightarrow$ `sendTabKey()`.
   - `"\b"` $\rightarrow$ `sendBackspaceKey()`.
   - Single character (`codePointCount == 1`) $\rightarrow$ `sendCharKey(codePoint)`.
   - Bulk text / Paste (`length > 1`):
     - Debounces duplicate IME paste events within 400ms:
       ```kotlin
       val now = SystemClock.uptimeMillis()
       if (now - lastPasteTime < 400L && lastPasteText == str) return true
       lastPasteTime = now
       lastPasteText = str
       activity.sendTextToServer(str)
       ```

### 3.3 Hardware-Calibrated BMC Key Hold Timing

#### The BMC / IPMI Problem
Baseboard Management Controllers (Dell iDRAC, HP iLO, Supermicro IPMI, Lenovo XCC) and legacy server KVMs poll their virtual USB HID keyboard interfaces at typical hardware intervals of **10ms to 16ms**. 
If a client sends key-down followed immediately by key-up (0ms or sub-5ms delay), the BMC USB polling cycle misses the keystroke entirely. Conversely, sending a stream of characters without inter-key release delays causes the BMC input buffer to overflow, dropping characters.

#### Hardware Constants & Mechanisms

| Constant | Value | Purpose | Reference |
|---|---|---|---|
| `pressDurationMs` | **18 ms** | Hold time between key-down and key-up on wire | `Messenger.kt:88` |
| `pacingDelay` | **22 ms** | Inter-key delay between key release and next key press | `VirtualKeys.kt:274` |
| `modifierReleaseDelay` | **3 ms** | Inter-key pause when clearing all modifier states | `Messenger.kt:118` |
| `fButtonUpDelay` | **200 ms** | Mouse button-down to button-up pause for legacy remote games/apps | `Messenger.kt:126` |
| `pasteDebounceDelay`| **400 ms** | Duplicate paste suppression window | `InputView.kt:117` |

1. **Thread Execution via Single-Thread Executor (`Messenger.kt`):**
   ```kotlin
   private val sender = Executors.newSingleThreadExecutor()

   fun sendKeyPress(keySym: Int, xtCode: Int = 0, pressDurationMs: Long = 18L): Boolean {
       return execute {
           client.sendKeyEvent(keySym, xtCode, true)
           if (pressDurationMs > 0) {
               try {
                   Thread.sleep(pressDurationMs)
               } catch (_: InterruptedException) {}
           }
           client.sendKeyEvent(keySym, xtCode, false)
       }
   }
   ```
   Because execution is queued sequentially on a dedicated background thread:
   - Keystroke order is strictly preserved.
   - The UI thread and network dispatch coroutines never block.
   - The 18ms sleep ensures physical server BMCs capture the HID report.

2. **Bulk Text Streaming (`VirtualKeys.sendTextToServer`):**
   - Text is sanitized by replacing `\r\n`, `\r`, and `\n` with spaces to prevent accidental command execution.
   - Active modifier keys are flushed: `releaseMetaKeys()` and `releaseAllModifiers()`.
   - Text is pushed to the remote clipboard: `sendClipboardText(sanitizedText)`.
   - Keystroke coroutine streams characters sequentially:
     ```kotlin
     sendTextJob = activity.lifecycleScope.launch(Dispatchers.Default) {
         val pacingDelay = 22L
         var idx = 0
         while (idx < sanitizedText.length) {
             if (!isActive) break
             val codePoint = sanitizedText.codePointAt(idx)
             idx += Character.charCount(codePoint)
             val keySym = resolveKeySym(codePoint)
             if (keySym != 0) {
                 messenger.sendKeyPress(keySym, 0, pressDurationMs = 18L)
                 delay(pacingDelay)
             }
         }
     }
     ```

### 3.4 KeyHandler Logic & Android Workarounds

1. **Gboard Shift Desync Workaround (`generateFakeShifts`):**
   Gboard transmits uppercase letters by sending Shift `ACTION_DOWN`, followed immediately by Shift `ACTION_UP`, and then the letter event with `isShiftPressed == true`. AVNC detects `model.source.isShiftPressed && !hasSentShiftDown` and wraps the character event with synthesized Shift `ACTION_DOWN` and `ACTION_UP`.
2. **Alt Key Typing Collision Fix (`generateFakeAlt`):**
   On Android virtual keyboards, characters like `Ç` or `ß` generate Alt combinations (`Alt+C`, `Alt+S`). Passing the Alt modifier causes remote VNC/RDP sessions to execute shortcuts instead of typing the character. AVNC detects software keyboard events with Alt pressed for these characters and removes the Alt key.
3. **Enter, Tab, Space, Backspace Priority:**
   Android returns a Unicode character for Enter, Space, and Tab, but remote servers often reject Unicode for control keys. AVNC forces `useUChar = false` for `KEYCODE_ENTER`, `KEYCODE_NUMPAD_ENTER`, `KEYCODE_SPACE`, and `KEYCODE_TAB`, routing them exclusively through scancodes.

---

## 4. Touch, Touchpad & Virtual Mouse Controls

### 4.1 Pointer Modes Architecture (`PointerModes.kt`)

```
                          BasePointerMode
                     +-----------------------+
                     | - doClick()           |
                     | - doDoubleClick()     |
                     | - doRemoteScroll()    |
                     | - doButtonDown/Up()   |
                     +-----------------------+
                                 ^
                                 |
               +-----------------+-----------------+
               |                                   |
      DirectPointerMode                   RelativePointerMode
  +-------------------------+         +--------------------------+
  | - transformPoint: toFb()|         | - transformPoint: cursor |
  | - Action at touch point |         | - Relative movement (dx) |
  | - Edge snapping         |         | - Libinput acceleration  |
  | - Pinch-to-zoom active  |         | - Auto-centering canvas  |
  +-------------------------+         +--------------------------+
```

### 4.2 Direct Touch Mode (`DirectPointerMode`)

1. **Coordinate Transformation:**
   Uses `viewModel.frameState.toFb(p)` to convert screen viewport points directly into remote framebuffer coordinates:
   $$\text{fbX} = \frac{\text{vpX} - \text{frameX}}{\text{scale}}, \quad \text{fbY} = \frac{\text{vpY} - \text{frameY}}{\text{scale}}$$
   Where $\text{scale} = \text{baseScale} \times \text{zoomScale}$.
2. **Actions:**
   - Tap $\rightarrow$ Left click at touch coordinate.
   - Two-finger tap $\rightarrow$ Right click at touch coordinate.
   - Three-finger tap $\rightarrow$ Middle click at touch coordinate.
   - Double-tap-swipe / Long-press-swipe $\rightarrow$ Click-and-drag.
3. **Edge Coercion (`coerceToFbEdge`):**
   If a user taps outside the visible remote frame (e.g. in letterboxed bars), the tap is coerced to the nearest edge coordinate (`[0, fbWidth - 1]`, `[0, fbHeight - 1]`). This enables triggering auto-hiding taskbars and dock panels.

### 4.3 Touchpad Mode (`RelativePointerMode`)

1. **Relative Movement:**
   - Tracks a persistent internal virtual cursor position: `pointerPosition = PointF(x, y)`.
   - Initialized from the server's current cursor position on gesture start.
   - Screen gestures supply $(\Delta x, \Delta y)$ deltas which are passed to the acceleration engine.
2. **Auto-Centering Remote Canvas:**
   As the virtual cursor moves towards the viewport boundaries, the viewport automatically pans to keep the cursor centered inside the safe area:
   ```kotlin
   val vp = viewModel.frameState.toVP(pointerPosition)
   val centerDiffX = viewModel.frameState.safeArea.centerX() - vp.x
   val centerDiffY = viewModel.frameState.safeArea.centerY() - vp.y
   viewModel.panFrame(centerDiffX, centerDiffY)
   ```

### 4.4 Libinput-Inspired Pointer Acceleration (`PointerAcceleration.kt`)

AVNC uses physical screen DPI to calculate finger velocity in millimeters per second, applying a three-tier acceleration curve:

#### Physical Metric Calculation
$$\text{velocity}_{\text{mm/s}} = \frac{|\text{velocity}_{\text{px/s}}| \times 25.4}{\text{dpi}}$$

#### 3-Tier Acceleration Profile

```
  Acceleration
  Factor (f)
      ^
  3.5 |                                      --- (Quadratic Acceleration)
      |                                  ---
  1.0 |            +-------------------+     (Linear 1:1 Baseline)
      |           /
  0.3 | ---------+                           (Precision Deceleration)
      |
      +----------+---------------------+-----> Velocity (mm/s)
         0      T1 (10 mm/s)          T2 (80 mm/s)
```

1. **Tier 1: Precision Deceleration ($v < 10\text{ mm/s}$)**
   - Allows fine pixel-level targeting.
   $$\text{slope} = \frac{1.0 - 0.3}{10.0} = 0.07$$
   $$f(v) = 0.07 \times v + 0.3$$
2. **Tier 2: Linear Baseline ($10\text{ mm/s} \le v < 80\text{ mm/s}$)**
   - Normal finger traversal.
   $$f(v) = 1.0$$
3. **Tier 3: Quadratic Acceleration ($v \ge 80\text{ mm/s}$)**
   - Rapid swiping across large remote resolutions (e.g. 4K / multi-monitor).
   $$f(v) = 0.0005 \times \left( \frac{v^2}{80} \right) + 1.0, \quad \text{clamped to } [0.3, 3.5]$$

#### Zoom-Aware Dampening (`TouchpadProfile`)
When zoomed in, high cursor acceleration causes overshoot. AVNC dampens the acceleration factor proportionally to `zoomScale`:
$$\text{dampenerSlope} = (\text{zoomScale} - 1) \times 0.07$$
$$\text{dampener} = \text{clamp}( \text{dampenerSlope} \times f, \, 0.0, \, 1.0 )$$
$$f_{\text{final}} = f - \text{dampener}$$

### 4.5 Touch Gesture Architecture (`TouchHandler.kt`)

#### 4-Detector Architecture (`GestureDetectorEx`)
Standard Android `GestureDetector` terminates scrolling when a double-tap or long-press is triggered. AVNC chains four detectors:
- `innerDetector1`: Handles single tap, long press, and flings.
- `innerDetector2` (`setIsLongpressEnabled(false)`): Detects double tap.
- `innerDetector3` (`setIsLongpressEnabled(false)`): Detects double-tap-and-drag.
- `innerDetector4` (`setOnDoubleTapListener(null)`): Detects instant quick taps (`quickTap1Enabled`).

#### Swipe vs. Scale Disambiguation (`SwipeVsScale`)
Two-finger gestures can trigger both pinch-to-zoom and two-finger scrolling. AVNC calculates the direction angles $\theta_1, \theta_2$ of the two touch paths relative to the x-axis:
$$\theta = \text{atan2}(\Delta y, \Delta x) \times \frac{180}{\pi} \pmod{360}$$
$$\Delta\theta = |\theta_1 - \theta_2|$$
- If $\Delta\theta > 45^\circ$: Detected as **Pinch-to-Zoom** (`shouldScale() == true`).
- If $\Delta\theta < 30^\circ$: Detected as **Two-Finger Scroll/Pan** (`shouldSwipe() == true`).

### 4.6 Virtual Mouse Compose Overlay (`VirtualMouseCompose.kt`)

The virtual mouse system provides two user-accessible overlay components:

1. **Floating Mouse Action Button (Collapsed State):**
   - 56dp circular FAB (`MaterialTheme.colorScheme.primary`), alpha = 0.80, elevation 10dp.
   - Draggable across the screen with touch slop disambiguation.
   - Tap expands into the horizontal floating control bar.
2. **Floating Mouse Control Bar (Expanded State):**
   - 46dp high rounded pill (radius 24dp), alpha = 0.80, elevation 12dp.
   - **Reposition Handle:** 18dp drag grip (`ic_drag_indicator`) to move the bar anywhere.
   - **Left Click Button:** 38x36dp button using `detectTapGestures` `onPress` callback (`onLeftDown()`, `tryAwaitRelease()`, `onLeftUp()`). Supports tapping for clicks and holding while dragging across the touchpad!
   - **Middle Click Button:** 34x36dp button ("Mid").
   - **Scroll Up & Down Buttons:** 44x38dp buttons with 24dp arrow icons and hold-to-repeat acceleration (200ms initial delay, 50ms interval).
   - **Right Click Button:** 38x36dp button ("Right").
   - **Soft Keyboard Button:** 34x34dp button (`ic_keyboard`).
   - **Minimize Button:** 34x34dp circular close icon (`Icons.Default.Close`) with `errorContainer` tint.
3. **Ergonomic Floating Scroll Pillar:**
   - 52dp wide vertical pill docked at screen right edge.
   - Top drag handle allows vertical repositioning along screen edge.
   - Large Scroll Up button (46x48dp, 28dp icon) and Scroll Down button (46x48dp, 28dp icon) with hold-to-repeat (200ms + 50ms).

---

## 5. Frame State & Coordinate Mathematics

### 5.1 Base Scale Calculation (`FrameState.calculateBaseScale`)

$$\text{scale}_1 = \frac{\max(\text{windowWidth}, \text{windowHeight})}{\max(\text{fbWidth}, \text{fbHeight})}$$
$$\text{scale}_2 = \frac{\min(\text{windowWidth}, \text{windowHeight})}{\min(\text{fbWidth}, \text{fbHeight})}$$
$$\text{baseScale} = \min(\text{scale}_1, \text{scale}_2)$$
$$\text{scale} = \text{baseScale} \times \text{zoomScale}$$

This guarantees:
1. The remote desktop is completely visible on screen.
2. Remote aspect ratio is strictly maintained (no distortion).
3. Screen real estate is maximally utilized regardless of orientation.

### 5.2 Viewport to Framebuffer Transforms

$$\text{toFb}(x_{\text{vp}}, y_{\text{vp}}): \quad x_{\text{fb}} = \frac{x_{\text{vp}} - \text{frameX}}{\text{scale}}, \quad y_{\text{fb}} = \frac{y_{\text{vp}} - \text{frameY}}{\text{scale}}$$
$$\text{toVP}(x_{\text{fb}}, y_{\text{fb}}): \quad x_{\text{vp}} = x_{\text{fb}} \times \text{scale} + \text{frameX}, \quad y_{\text{vp}} = y_{\text{fb}} \times \text{scale} + \text{frameY}$$

### 5.3 Safe Area Bounds Coercion (`coercePosition`)

When panning or zooming, the frame position is constrained within the safe area bounds:
$$\text{scaledFb} = \text{fbSize} \times \text{scale}$$
$$\text{diff} = (\text{safeMax} - \text{safeMin}) - \text{scaledFb}$$
$$\text{framePos} = \begin{cases} 
\frac{\text{diff}}{2} + \text{safeMin} & \text{if } \text{diff} \ge 0 \quad (\text{frame smaller than safe area: center it}) \\
\text{clamp}(\text{currentPos}, \, \text{diff} + \text{safeMin}, \, \text{safeMin}) & \text{if } \text{diff} < 0 \quad (\text{frame larger: keep safe area filled})
\end{cases}$$

### 5.4 Zoom Snapping at 1.0 (100%) Scale

When pinching to zoom, `FrameState.snapZoom()` pauses the scaling factor at exactly 1.0 within a ±30% range (`snapLimit = 0.3f`), allowing users to easily hit exact 1:1 pixel scaling without needing a toolbar button.

---

## 6. Features Discovered & Probe Matrix

| # | Category | Feature | Description | Inputs | Outputs | Error Behavior | Discovered Via |
|---|----------|---------|-------------|--------|---------|----------------|----------------|
| 1 | Toolbar | Collapsible In-Session Drawer | Full session toolbar in `DrawerLayout` with edge swipe and floating button | Edge swipe, Opener button tap | Drawer open/close animation | Locked closed when disconnected | `Toolbar.kt:60-109` |
| 2 | Toolbar | Cutout-Aware Padding | Adds padding to primary buttons when display cutout / notch overlaps | `WindowInsetsCompat.displayCutout` | Sets `fitsSystemWindows = true` | No-op if drawBehindCutout is disabled | `Toolbar.kt:116-129` |
| 3 | Toolbar | Gesture Exclusion Rect | Excludes toolbar swipe zone from Android 10+ system navigation | `primaryButtons` layout bounds | `systemGestureExclusionRects` list | Ignored on non-compliant ROMs | `Toolbar.kt:278-298` |
| 4 | Toolbar | Scrim Swipe Close | Closes drawer on closing fling in scrim area | `MotionEvent` on `DrawerLayout` | Calls `close()` | None | `Toolbar.kt:350-380` |
| 5 | Toolbar | Repositionable Opener Button | Floating pill button with vertical drag tracking | Drag gesture (`onScroll`) | Persists `verticalBias` | Clamped to screen top/bottom margins | `Toolbar.kt:383-423` |
| 6 | Toolbar | Synchronized Flyouts | Flyouts (Zoom, View Mode, Gesture) that close after drawer closing | Toggle buttons | Flyout container visibility | Defers reset until drawer close animation ends | `Toolbar.kt:301-334` |
| 7 | Virtual Keys | Material 3 Virtual Keys Overlay | 20% transparent overlay with Fn bar, navigation, and modifiers | `VirtualKeysComposeView` | Rendered Compose UI | Hidden in PiP mode | `VirtualKeysCompose.kt:73-115` |
| 8 | Virtual Keys | Expandable Fn Strip | F1–F12 horizontal scrolling strip grouped in clusters of 4 | Fn toggle button | Slide in/out animation | Hidden when `isFnMode` is false | `VirtualKeysCompose.kt:118-169` |
| 9 | Virtual Keys | Modifier Tri-State Machine | Sticky latching (single tap) and lock (long press) for Ctrl, Alt, Shift, Win | Tap, Long-press | Protocol `ACTION_DOWN`/`UP` + UI highlight | Released on teardown | `VirtualKeys.kt:187-223` |
| 10 | Virtual Keys | Inverted-T Arrow Cluster | Up arrow centered directly above Down, flanked by Left and Right | Tap, Hold | Remote cursor / arrow key events | Repeated on hold | `VirtualKeysCompose.kt:367-429` |
| 11 | Virtual Keys | Big Hold-to-Repeat Scroll Buttons | 54x34dp scroll buttons with continuous repeat acceleration | Tap, Hold | Remote Wheel Up / Down clicks | Initial delay 200ms, repeat 50ms | `VirtualKeysCompose.kt:434-455` |
| 12 | Virtual Keys | Paced Text Streaming | Converts text into paced keysyms with clipboard sync | Text string | Remote clipboard + individual key events | CRLF sanitized to spaces | `VirtualKeys.kt:244-301` |
| 13 | Keyboard | IME Enter/Backspace Interception | Intercepts soft keyboard Enter and Backspace to prevent lost events | Soft keyboard events | Protocol Enter / Backspace key presses | Unhandled events fallback to base | `InputView.kt:48-62` |
| 14 | Keyboard | Hardware BMC Hold Timing | 18ms keypress hold duration on single-thread executor | Any soft/virtual key press | Key-down, 18ms sleep, key-up | InterruptedException caught | `Messenger.kt:88-99` |
| 15 | Keyboard | Modifiers Release Sequence | Purges all modifier states with 3ms pacing | Session end or text streaming | Sequence of modifier key-ups | Swallows InterruptedException | `Messenger.kt:101-123` |
| 16 | Keyboard | Gboard Shift Workaround | Synthesizes fake Shift down/up for desynced soft keyboard uppercase | `isShiftPressed && !hasSentShiftDown` | Injected Shift `ACTION_DOWN`/`UP` | None | `KeyHandler.kt:301-340` |
| 17 | Mouse/Touch | Direct Pointer Mode | 1:1 tap-to-click at touch coordinates with edge snapping | Touch events | Framebuffer clicks at `toFb(p)` | Coerced to frame edge if outside | `PointerModes.kt:106-132` |
| 18 | Mouse/Touch | Touchpad / Relative Mode | Relative cursor movement with auto-centering viewport | Touch drag deltas | Virtual cursor movement + frame panning | Clamped to framebuffer limits | `PointerModes.kt:137-193` |
| 19 | Mouse/Touch | Libinput Pointer Acceleration | 3-tier physical mm/s acceleration with zoom dampening | VelocityTracker deltas | Accelerated $(\Delta x, \Delta y)$ | Factor clamped between 0.3 and 3.5 | `PointerAcceleration.kt:22-132` |
| 20 | Mouse/Touch | Swipe vs. Scale Disambiguation | Vector angle differential to distinguish pinch-zoom from 2-finger scroll | 2-finger touch vectors | Routes to scale or scroll dispatcher | Angle diff between 30° and 45° ignored | `TouchHandler.kt:543-630` |
| 21 | Mouse/Touch | Virtual Mouse Overlay | Draggable FAB expanding to Left, Mid, Right click and Scroll buttons | Touch drag / tap | Protocol mouse clicks & drags | Left click supports press-and-drag | `VirtualMouseCompose.kt:79-178` |
| 22 | Mouse/Touch | Docked Scroll Pillar | Ergonomic vertical scroll pillar docked at right margin | Touch drag / tap | Continuous Wheel Up / Down events | 200ms initial delay, 50ms interval | `VirtualMouseCompose.kt:479-601` |

---

## 7. Edge Cases & Observed Behaviors

| # | Feature | Input | Observed Behavior |
|---|---------|-------|-------------------|
| 1 | Toolbar Opener Button | Drag beyond screen bounds | Clamped between `minY = btn.marginTop` and `maxY = parentHeight - btn.height - btn.marginBottom`. |
| 2 | Toolbar Flyouts | Rapid opening/closing of drawer | Flyouts remain visible during drawer motion; toggles reset strictly inside `onDrawerClosed()` callback to prevent DrawerLayout measurement corruption. |
| 3 | Toolbar Exclusion Rect | Fullscreen window on Android 10+ | Android allows exclusion areas > 200dp in fullscreen; AVNC expands the height exclusion by $1/6$ of available space above and below. |
| 4 | Modifier Keys | Non-modifier key released (`ACTION_UP`) | All latched (sticky) modifiers are automatically unlatched and released; locked (long-pressed) modifiers remain held. |
| 5 | Super/Win Key | Single tap with `vkUseSuperWithSingleTap == true` | Dispatches instantaneous key press and release (18ms) to toggle Windows Start menu without latching meta state. |
| 6 | Virtual Keys Text Paste | Text containing `\r\n` or `\n` | Replaced with single space `' '` before streaming to prevent remote shells from prematurely executing commands. |
| 7 | Soft Keyboard Enter | Gboard / SwiftKey soft enter | Caught in `InputView.InputConnection.commitText` (`str == "\n"`) or `sendKeyEvent`, routed directly as `sendEnterKey()` with 18ms BMC hold timing. |
| 8 | Soft Keyboard Backspace | Repeated backspace in empty field | Intercepted in `deleteSurroundingText(beforeLength, afterLength)`; dispatches `beforeLength` discrete `sendBackspaceKey()` events. |
| 9 | Soft Keyboard Alt Characters | Typing `Ç` or `ß` on virtual keyboard | Android sends `isAltPressed == true` which breaks server text typing; `KeyHandler.generateFakeAlt` intercepts and suppresses Alt. |
| 10 | Direct Touch Mode | Tap on letterboxed black bars outside canvas | `coerceToFbEdge()` projects coordinates to the nearest pixel on the frame edge, triggering auto-hiding remote taskbars/docks. |
| 11 | Touchpad Mode | Fast swipe across screen | Enters Tier 3 quadratic acceleration ($v \ge 80\text{ mm/s}$), accelerating cursor up to 3.5x across high-resolution displays. |
| 12 | Touchpad Mode | Pinch-to-zoom active | `TouchpadProfile` dampens acceleration factor as `zoomScale` increases, allowing steady pixel-accurate control when zoomed in. |
| 13 | Two-finger Gestures | Diagonal pinch vs. parallel swipe | `SwipeVsScale` evaluates angle differential $\Delta\theta$; if $> 45^\circ$, scales canvas; if $< 30^\circ$, triggers parallel scrolling. |
| 14 | Frame Panning | Pinch-to-zoom with focus point | `FrameState.updateZoom` translates canvas by $(-df_x, -df_y)$ to lock the pinch midpoint fixed in viewport space. |
| 15 | Frame Zoom Snapping | Pinch zoom crossing 1.0 (100%) | `snapZoom()` captures zoom scale at exactly 1.0 within ±30% range (`snapLimit = 0.3f`), resetting only when finger lifts. |

---

## 8. Architectural Recommendations for Porting to `android_rdp_client`

1. **Toolbar & Navigation:**
   - Integrate the `DrawerLayout` pattern with transparent scrim into `SessionScreen.kt`.
   - Port the floating `open_toolbar_btn` with `verticalBias` persistence and `systemGestureExclusionRects` to support gesture navigation in full-screen RDP.
   - Wire toolbar actions to RDP session methods: keyboard toggle, input mode toggle (Touchpad vs. Direct Touch), virtual keys bar toggle, zoom fit/1:1, and clean disconnect via `freerdp_disconnect()`.
2. **Virtual Keys Bar:**
   - Port `VirtualKeysCompose.kt` directly into `:feature-mouse` or `:feature-session`.
   - Bind modifier keys (Ctrl, Alt, Shift, Super/Win) to RDP keyboard scancodes via `ScancodeTranslator.kt`.
   - Adopt the tri-state modifier state machine (sticky vs. pressed vs. locked).
3. **Keyboard Input & BMC Timing:**
   - Adopt `InputView.InputConnection` in `RemoteCanvasView.kt` or session input overlay to properly catch soft-keyboard Enter, Backspace, Space, Tab, and pasted text.
   - Implement the 18ms key hold timing and 22ms inter-key delay on a dedicated FreeRDP sender executor thread for all keyboard input to eliminate skipped keystrokes on remote Windows RDP sessions and virtual KVMs.
4. **Touch, Touchpad & Virtual Mouse:**
   - Replace the existing floating mouse overlay in `:feature-mouse` with `VirtualMouseCompose.kt` (collapsed FAB + expanded control pill + ergonomic right-docked scroll pillar).
   - Implement `DirectPointerMode` and `RelativePointerMode` using `PointerAcceleration.kt` and `SwipeVsScale` in the RDP gesture processor.
   - Ensure all viewport-to-framebuffer transformations account for local aspect ratio letterboxing and zoom scale before dispatching `sendMouseEvent` to FreeRDP.
