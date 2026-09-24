# Handoff Report: AVNC Specification Survey & Architecture Breakdown

**Agent:** `teamwork_preview_spec_miner_survey_1` (Spec Miner)  
**Parent:** `f0fc1f73-b43f-468a-ab50-e5ec45aedb66`  
**Date:** 2026-09-24  
**Deliverable Path:** `C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\teamwork\teamwork_preview_spec_miner_survey_1\survey_avnc_spec.md`  

---

## 1. Observation

### Collapsible Toolbar & Layout System
- `Toolbar.kt` lines 60–109: Uses `DrawerLayout` with `app:scrimColor="@{0}"` (transparent scrim) and an alignment setting (`GravityCompat.START` or `END`). It sets `drawerView.layoutDirection = if (isLeftAligned) View.LAYOUT_DIRECTION_LTR else RTL` to maintain proper inward flyout expansion.
- `Toolbar.kt` lines 280–298: In Android 10+ (`Build.VERSION.SDK_INT >= 29`), calculates `getActionableToolbarRect()` covering `primaryButtons` and pads it by one-sixth of the available height (`val padding = (drawerLayout.height - rect.height()) / 6`) to generate `systemGestureExclusionRects`, preventing edge swipes from conflicting with system back navigation gestures.
- `Toolbar.kt` lines 383–423: `open_toolbar_btn` is a draggable floating pill with vertical position persisted as `verticalBias = parentTouchY / parentHeight` in `pref.runInfo.toolbarOpenerBtnVerticalBias`.
- `Toolbar.kt` lines 350–380: `setupDrawerCloseOnScrimSwipe()` detects closing flings on the transparent scrim and closes the drawer.
- `Toolbar.kt` lines 325–334: `setupFlyoutClose()` waits until `onDrawerClosed()` completes before resetting flyout toggle buttons, preventing width changes during drawer animations.

### RealVNC-Style Virtual Keys Bar
- `VirtualKeysCompose.kt` lines 73–115: Material 3 `Surface` with `alpha = 0.80f`, tonal elevation 8.dp, shadow elevation 10.dp, rounded top corners 16.dp. Contains an expandable function strip (F1–F12) animated with `slideInVertically(initialOffsetY = { -it }) + fadeIn()` and a 5-zone main controls bar.
- `VirtualKeysCompose.kt` lines 251–480:
  - Zone 1: Keyboard toggle (38x34dp), Mouse toggle (38x34dp), and Fn toggle (36x72dp) with active indicator dot.
  - Zone 2: Row 1 (Esc 44dp, Tab 44dp, Win 52dp, Del 46dp) and Row 2 (Ctrl 44dp, Alt 44dp, Shift 52dp, Caps 46dp).
  - Zone 3: Navigation cluster with Home/End (left column, 46dp), Inverted-T Arrow pad (center column, Up 54x34dp centered directly above Down 54x34dp, flanked by Left 48x34dp and Right 48x34dp), and PgUp/PgDn (right column, 46dp).
  - Zone 4: Big Scroll Up & Scroll Down buttons (54x34dp each) with hold-to-repeat (200ms initial delay, 50ms interval).
  - Zone 5: Dismiss button (34x72dp, errorContainer tint, `ic_clear`).
- `VirtualKeys.kt` lines 80–81, 187–223: Tri-state modifier machine:
  - Sticky/Latched: Single tap sets `activeToggleKeys[vk] = true` and `lockedToggleKeys[vk] = false`. Sends `ACTION_DOWN`. Visualised with `primaryContainer` and 1.5dp border. Automatically unlatched and released (`ACTION_UP`) on non-modifier `ACTION_UP` via `onAfterKeyEvent` hook (`releaseUnlockedMetaKeys()`).
  - Locked: Long press sets `lockedToggleKeys[vk] = true`. Visualised with solid `primary` background and indicator dot. Persists across multiple keystrokes until toggled off.
  - Super/Win single-tap: If `pref.input.vkUseSuperWithSingleTap` is true, sends an immediate press and release to open the Start menu without locking.

### Keyboard Input Handling & BMC Timing
- `InputView.kt` lines 48–115: `BaseInputConnection` intercepts:
  - `sendKeyEvent(event)` for `KEYCODE_ENTER` and `KEYCODE_DEL`.
  - `performEditorAction` for Enter.
  - `deleteSurroundingText` for repeated Backspace.
  - `commitText` for `"\n"`, `" "`, `"\t"`, `"\b"`, single characters, and bulk paste (debounced 400ms).
- `Messenger.kt` lines 27, 88–99:
  ```kotlin
  fun sendKeyPress(keySym: Int, xtCode: Int = 0, pressDurationMs: Long = 18L): Boolean {
      return execute {
          client.sendKeyEvent(keySym, xtCode, true)
          if (pressDurationMs > 0) {
              try { Thread.sleep(pressDurationMs) } catch (_: InterruptedException) {}
          }
          client.sendKeyEvent(keySym, xtCode, false)
      }
  }
  ```
  `sender` is a single-thread executor (`Executors.newSingleThreadExecutor()`).
- `VirtualKeys.kt` lines 274, 293–295:
  Keystroke streaming uses `pressDurationMs = 18L` and `pacingDelay = 22L`.
- `Messenger.kt` lines 101–123: `releaseAllModifiers()` iterates 10 modifiers (Shift_L/R, Control_L/R, Alt_L/R, Meta_L/R, Caps_Lock, Space) with 3ms pause between each (`Thread.sleep(3)`).
- `KeyHandler.kt` lines 301–340: Detects Gboard uppercase desync where Shift is released before character dispatch and wraps event with synthetic Shift `ACTION_DOWN`/`UP`.

### Touch, Touchpad & Virtual Mouse Controls
- `PointerModes.kt` lines 22–193:
  - `DirectPointerMode`: Maps viewport point to framebuffer via `toFb(p)`. If tap is outside frame, `coerceToFbEdge()` maps it to the closest frame edge pixel to open auto-hiding panels.
  - `RelativePointerMode`: Maintains virtual cursor `pointerPosition`, applies pointer acceleration, and auto-centers the viewport via `viewModel.panFrame(centerDiffX, centerDiffY)`.
- `PointerAcceleration.kt` lines 48–132:
  - Converts velocity to mm/s using display DPI: `(vPixelPerSec * 25.4f) / dpi`.
  - 3-tier response:
    - Tier 1 ($v < 10\text{ mm/s}$): Deceleration slope $0.07 \times v + 0.3$.
    - Tier 2 ($10 \le v < 80\text{ mm/s}$): Constant 1:1 baseline ($1.0$).
    - Tier 3 ($v \ge 80\text{ mm/s}$): Quadratic speedup $0.0005 \times (v^2 / 80) + 1.0$, clamped to $[0.3, 3.5]$.
  - Dynamic zoom dampening: When `zoomScale > 1`, dampens factor by $((\text{zoomScale} - 1) \times 0.07 \times f)$.
- `TouchHandler.kt` lines 382–386, 543–630:
  - 4-detector cascade (`GestureDetectorEx`) supports tap, double-tap, long-press, double-tap-swipe, and quick tap without detector lockouts.
  - `SwipeVsScale`: Distinguishes 2-finger scroll vs pinch-zoom using vector angle difference $\Delta\theta$: $> 45^\circ \rightarrow$ scale; $< 30^\circ \rightarrow$ scroll.
- `VirtualMouseCompose.kt` lines 79–601:
  - Floating circular FAB (56dp, primary, alpha 0.80) draggable anywhere.
  - Expands to horizontal pill: Left click (supports hold-and-drag via `detectTapGestures` `onPress`), Mid click, Scroll Up/Down (200ms initial + 50ms repeat), Right click, Keyboard toggle, Close.
  - Docked vertical scroll pillar at right margin (52dp wide) with large 46x48dp scroll buttons.
- `FrameState.kt` lines 141, 243–268, 287–324, 337–365:
  - `baseScale = min(max(wW, wH) / max(fbW, fbH), min(wW, wH) / min(fbW, fbH))`.
  - `toFb(p): fbX = (vpX - frameX) / scale`, `fbY = (vpY - frameY) / scale`.
  - Safe area bounds coercing: Centered when frame is smaller than safe area; clamped to edge when larger.
  - Zoom snapping: Captures scale at 1.0 (100%) within $\pm 30\%$ range (`snapLimit = 0.3f`).

---

## 2. Logic Chain

1. **Toolbar Integration**: Standard Android fullscreen apps suffer when navigation bars are hidden because edge swipes trigger OS navigation gestures. AVNC solves this on API 29+ using `systemGestureExclusionRects` padded by one-sixth height, combined with a repositionable floating opener button. To prevent visual stutter, scrim swipes are handled via a custom fling detector, and flyouts are closed only after the drawer animation finishes.
2. **Virtual Keys Usability**: Touch devices need desktop keys (Fn, Esc, Tab, Win, Del, inverted-T arrows) without consuming permanent canvas space. Compose overlays with 20% translucency (`alpha = 0.80f`) and slide animations provide visibility without permanently blocking the remote UI. Tri-state modifier handling (sticky tap vs locked long-press) is essential because mobile keyboards cannot physically hold Ctrl/Alt while tapping another key.
3. **Keystroke Reliability**: Android software keyboards (Gboard/SwiftKey) bypass keycodes in favor of `commitText` and drop down/up events for Enter and Backspace. Intercepting these in `InputConnection` and applying an 18ms key-down hold delay on a single-thread executor matches physical server BMC USB HID polling rates (10–16ms). This prevents lost keystrokes on remote Windows consoles and BMCs.
4. **Pointer Ergonomics**: Direct Touch mode needs edge coercion so users can hit 1-pixel taskbars in letterboxed resolutions. Touchpad mode requires relative tracking with libinput-style acceleration and zoom dampening so small movements are pixel-precise while fast flicks cross multi-monitor screens. The floating Virtual Mouse overlay and right-docked scroll pillar enable complete one-handed control.

---

## 3. Caveats

- The reference AVNC implementation uses VNC (RFB protocol) with X11 keysyms (`XKeySym`). When porting to `android_rdp_client`, the keysyms must be translated into RDP scancodes (`ScancodeTranslator.kt`) or virtual key codes expected by FreeRDP.
- `systemGestureExclusionRects` is respected on most standard Android ROMs but may be ignored by heavily customized vendor skins (e.g. MIUI/HyperOS). The draggable opener button serves as the guaranteed fallback.
- No code was modified in either repository during this read-only investigation.

---

## 4. Conclusion

All UX patterns, state machines, math formulas, timing constants, and layouts from AVNC have been comprehensively probed and documented in `survey_avnc_spec.md`. The design is directly portable to the Android RDP client repository (`android_rdp_client`) across `:app`, `:feature-mouse`, and `:feature-session`.

Key values for porting:
- **BMC Key Hold Timing:** 18ms hold, 22ms inter-key pacing, 3ms modifier purge delay.
- **Pointer Acceleration:** 3-tier curve (T1 = 10 mm/s, T2 = 80 mm/s, min = 0.3, max = 3.5, baseline = 1.0).
- **Two-Finger Disambiguation:** Angle diff $> 45^\circ$ for zoom, $< 30^\circ$ for scroll.
- **Virtual Keys Layout:** 5 zones, Material 3 Surface (alpha = 0.80f, rounded top corners 16dp).
- **Virtual Mouse:** Collapsed 56dp FAB, expanded 46dp pill with Left hold-and-drag, and 52dp docked right scroll pillar.

---

## 5. Verification Method

To verify the findings:
1. Inspect the comprehensive survey report:
   `view_file` on `C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\teamwork\teamwork_preview_spec_miner_survey_1\survey_avnc_spec.md`
2. Verify reference code locations in AVNC:
   - `view_file` on `C:\Users\Administrator\avnc\app\src\main\java\com\vncandroid\free\ui\vnc\Toolbar.kt` (lines 60–129, 278–298, 383–423)
   - `view_file` on `C:\Users\Administrator\avnc\app\src\main\java\com\vncandroid\free\ui\vnc\VirtualKeysCompose.kt` (lines 73–169, 251–480)
   - `view_file` on `C:\Users\Administrator\avnc\app\src\main\java\com\vncandroid\free\ui\vnc\VirtualKeys.kt` (lines 187–223, 271–301)
   - `view_file` on `C:\Users\Administrator\avnc\app\src\main\java\com\vncandroid\free\session\Messenger.kt` (lines 88–123)
   - `view_file` on `C:\Users\Administrator\avnc\app\src\main\java\com\vncandroid\free\ui\vnc\input\InputView.kt` (lines 48–115, 126–185)
   - `view_file` on `C:\Users\Administrator\avnc\app\src\main\java\com\vncandroid\free\ui\vnc\input\PointerAcceleration.kt` (lines 48–132)
   - `view_file` on `C:\Users\Administrator\avnc\app\src\main\java\com\vncandroid\free\ui\vnc\VirtualMouseCompose.kt` (lines 79–178, 276–475, 479–601)
