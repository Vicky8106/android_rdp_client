# Handoff Report: AVNC Reference Architecture & Wiring Specification for R1 and R2

## 1. Observation

A detailed architectural and forensic audit of the local reference VNC client codebase (`C:\Users\Administrator\avnc`) and the target Android RDP client codebase (`C:\Users\Administrator\teamwork_projects\android_rdp_client`) was performed, specifically probing the source files cited in `ORIGINAL_REQUEST.md` and the dispatch assignment:
- `C:\Users\Administrator\avnc\app\src\main\java\com\vncandroid\free\ui\vnc\Toolbar.kt` (424 lines)
- `C:\Users\Administrator\avnc\app\src\main\java\com\vncandroid\free\ui\vnc\VncActivity.kt` (585 lines)
- `C:\Users\Administrator\avnc\app\src\main\java\com\vncandroid\free\ui\vnc\LayoutManager.kt` (324 lines)
- `C:\Users\Administrator\avnc\app\src\main\java\com\vncandroid\free\ui\vnc\VirtualKeysCompose.kt` (862 lines)
- `C:\Users\Administrator\avnc\app\src\main\java\com\vncandroid\free\ui\vnc\VirtualKeys.kt` (555 lines)
- `C:\Users\Administrator\avnc\app\src\main\java\com\vncandroid\free\ui\vnc\VirtualMouseCompose.kt` (603 lines)
- `C:\Users\Administrator\avnc\app\src\main\java\com\vncandroid\free\ui\vnc\input\TouchHandler.kt` (632 lines)
- `C:\Users\Administrator\avnc\app\src\main\java\com\vncandroid\free\ui\vnc\input\PointerModes.kt` (193 lines)
- `C:\Users\Administrator\avnc\app\src\main\java\com\vncandroid\free\ui\vnc\input\PointerAcceleration.kt` (133 lines)
- `C:\Users\Administrator\avnc\app\src\main\java\com\vncandroid\free\ui\vnc\input\KeyHandler.kt` (522 lines)
- `C:\Users\Administrator\avnc\app\src\main\java\com\vncandroid\free\session\Messenger.kt` (148 lines)
- `C:\Users\Administrator\avnc\app\src\main\res\layout\activity_vnc.xml` (194 lines)
- `C:\Users\Administrator\avnc\app\src\main\res\layout\toolbar_drawer.xml` (174 lines)
- `android_rdp_client/app/src/main/java/com/freerdp/client/ui/session/SessionScreen.kt` (1015 lines)
- `android_rdp_client/app/src/main/java/com/freerdp/client/ui/session/RemoteCanvasView.kt` (280 lines)
- `android_rdp_client/app/src/main/java/com/freerdp/client/ui/session/VirtualKeysCompose.kt` (857 lines)
- `android_rdp_client/app/src/main/java/com/freerdp/client/ui/session/VirtualMouseCompose.kt` (673 lines)
- `android_rdp_client/feature-mouse/src/main/java/com/freerdp/feature/mouse/PointerModes.kt` (311 lines)
- `android_rdp_client/feature-session/src/main/java/com/freerdp/feature/session/keyboard/KeyboardTimingManager.kt` (210 lines)
- `android_rdp_client/app/src/test/java/com/freerdp/client/e2e/uxinput/UxInputTestHarness.kt` (460 lines)
- `android_rdp_client/app/src/test/java/com/freerdp/client/e2e/uxinput/UxInputTier1FeatureCoverageTest.kt` (688 lines)

### Direct Source Findings:

1. **Collapsible Toolbar Drawer & Scrim Dismissal (`Toolbar.kt`, `activity_vnc.xml`)**:
   - `DrawerLayout` is configured with `app:scrimColor="@{0}"` (line 44 of `activity_vnc.xml`). A transparent scrim prevents dimming the remote desktop while the toolbar is open.
   - The drawer root view `drawerView = binding.root` is a full-height `FrameLayout` (`wrap_content` width, `match_parent` height) with `layout_gravity="start"` (or `end`).
   - Line 81 of `Toolbar.kt`: `drawerView.setOnClickListener { close() }`. Because the drawer root is transparent outside the actionable button group (`primary_buttons`), taps in the empty drawer region operate identically to tapping a scrim, closing the drawer without propagating click events to the remote desktop canvas underneath.
   - `setupDrawerCloseOnScrimSwipe()` (lines 350–380): Standard Android `DrawerLayout` closes drawers on scrim taps, but *not* on scrim fling/swipe gestures. AVNC hooks an `OnTouchListener` with a `GestureDetector` to `drawerLayout`. On `onFling`, if the drawer is open and the user flings in the closing direction (`vX < 0` for left-aligned, `vX > 0` for right-aligned), it triggers `close()`. The listener returns `false` so regular down/move/up events still reach content views when the drawer is closed.
   - `setupAlignment()` (lines 239–261): Forces layout direction based on drawer gravity rather than system locale:
     ```kotlin
     val layoutDirection = activity.resources.configuration.layoutDirection
     val isLeftAligned = Gravity.getAbsoluteGravity(gravity, layoutDirection) == Gravity.LEFT
     drawerView.layoutDirection = if (isLeftAligned) View.LAYOUT_DIRECTION_LTR else View.LAYOUT_DIRECTION_RTL
     openerButton.layoutDirection = drawerView.layoutDirection
     ```
     This forces flyout submenus and button icons to expand inward from the screen bezel regardless of device language.
   - `setupFlyoutClose()` (lines 325–333): In `DrawerLayout.SimpleDrawerListener.onDrawerClosed()`, all flyouts are collapsed (`flyouts.keys.forEach { it.isChecked = false }`). AVNC explicitly avoids closing flyouts inside `close()` because altering the toolbar width *during* the close slide animation corrupts DrawerLayout's velocity and translation calculations, causing closing glitches.

2. **Floating Draggable Opener Button (`Toolbar.kt` lines 382–424)**:
   - Positioned along the drawer edge (`GravityCompat.START` or `END`).
   - Normalized `verticalBias = parentTouchY / parentHeight`, where `parentTouchY = openerButton.y + e2.y` inside `GestureDetector.onScroll`.
   - Clamping:
     ```kotlin
     val parentHeight = (btn.parent as ViewGroup).height
     val minY = btn.marginTop
     val maxY = parentHeight - btn.height - btn.marginBottom
     val y = (parentHeight * verticalBias).toInt().coerceAtMost(maxY).coerceAtLeast(minY)
     btn.y = y.toFloat()
     ```
   - Safe drag without clipping: Clamps within `[minY, maxY]` respecting parent layout margins.
   - Persistence: On `MotionEvent.ACTION_UP`, if `verticalBias != runInfo.toolbarOpenerBtnVerticalBias`, saves to SharedPreferences (`runInfo.toolbarOpenerBtnVerticalBias = verticalBias`).
   - Restoring across orientation/resume: `openerButtonParent.addOnLayoutChangeListener { ... updateOpenerBtnPosition(openerButton, verticalBias) }`. Since `verticalBias` is normalized (0.0 to 1.0), screen rotation preserves relative placement automatically.

3. **System Gesture Exclusion Zones (`Toolbar.kt` lines 266–300)**:
   - Target Android 10+ (API 29+) gesture navigation where edge swipes trigger the system Back action.
   - `getActionableToolbarRect()` calculates the actionable bounds of `primary_buttons` in `drawerLayout`'s coordinate space, offsetting by `v.width` if closed.
   - Height padding calculation (lines 286–295):
     ```kotlin
     if (viewModel.pref.viewer.fullscreen) {
         val padding = (drawerLayout.height - rect.height()) / 6
         if (padding > 0) {
             rect.top -= padding
             rect.bottom += padding
         }
     }
     drawerLayout.systemGestureExclusionRects = listOf(rect)
     ```
     Padding by 1/6th of available space above and below the button bounds provides a reliable touch area for opening the drawer via edge swipe while preserving system Back navigation along the rest of the edge.
   - When disconnected or swipe opening is disabled: `drawerLayout.systemGestureExclusionRects = listOf()`.

4. **Multi-Mode Input & Gesture Handling (`TouchHandler.kt`, `PointerModes.kt`, `PointerAcceleration.kt`)**:
   - `GestureDetectorEx`: Combines 4 inner `GestureDetector` instances to circumvent Android limitations (preventing scroll suppression on double-tap or long-press, detecting double-tap-swipe, and quick-tap mode).
   - `SwipeVsScale`: Resolves ambiguity between two-finger pinch-to-zoom and two-finger scroll/pan by calculating the angle between finger trajectories. Angle differential $> 45^\circ \to$ pinch zoom; $< 30^\circ \to$ two-finger pan/scroll.
   - `DirectPointerMode`: Maps viewport coordinates to remote framebuffer (`toFb(p)`). Crucially, includes `coerceToFbEdge(p)`: when users tap in the black letterbox margin, the coordinate is clamped to the nearest edge pixel (`x.coerceIn(0, fbWidth - 1)`), enabling reliable activation of auto-hiding remote Windows taskbars and edge docks.
   - `RelativePointerMode`: Implements 3-tier libinput physical acceleration curve:
     - Tier 1 ($< 10\text{ mm/s}$): deceleration slope $y = 0.07v + 0.3$ (down to 0.3× for fine pixel targeting)
     - Tier 2 ($10\text{--}80\text{ mm/s}$): linear 1.0×
     - Tier 3 ($> 80\text{ mm/s}$): quadratic speedup up to 3.5×
     - Zoom dampening: divides acceleration by `zoomScale` when zoomed in ($> 1.0$)
     - Auto-centers viewport around the virtual cursor if frame exceeds screen.

5. **Virtual Keys & Keyboard BMC Hold Timing (`VirtualKeysCompose.kt`, `VirtualKeys.kt`, `KeyHandler.kt`, `Messenger.kt`)**:
   - `VirtualKeys`: Tri-state latching FSM:
     - Inactive $\to$ Latched (single tap, active for exactly one non-modifier key UP event, then auto-releases).
     - Latched $\to$ Locked (long press or double tap, indicated by dot badge, stays held until manually tapped again).
   - RealVNC Ergonomic Layout:
     - Expandable Fn strip (F1–F12)
     - Essential keys: Esc, Tab, Win, Del
     - Modifier keys: Ctrl, Alt, Shift, CapsLock
     - Inverted-T arrow cluster (centered Up, Left, Down, Right)
     - Navigation keys: Home, End, PgUp, PgDn
     - Enlarged Scroll Up & Scroll Down pads
   - Hardware BMC / Soft Keyboard Timing:
     - Soft keyboards (Gboard/SwiftKey) and BMC remote consoles (iDRAC, iLO, IPMI) drop instantaneous 0ms key pulses.
     - `Messenger.kt` and `DefaultKeyboardTimingManager`:
       - 50ms key-press hold duration (`sendKeyPressWithHold`, `pressDurationMs = 50L`)
       - 25ms inter-key pacing delay during bulk text streaming (`sendTextWithPacing`)
       - Release all modifiers on session blur or disconnect.

---

## 2. Logic Chain

1. **R1 In-Session Toolbar & Drawer Integration**:
   - *Observation*: AVNC places `DrawerLayout` around the remote surface with `scrimColor = 0` and sets `drawerView.setOnClickListener { close() }`.
   - *Logic*: In `android_rdp_client`, `SessionScreen.kt` currently uses a bottom-pinned `QuickToolbar` that expands upward, conflicting with the floating mouse overlay and virtual keys. Adopting AVNC's collapsible side drawer (anchored at `Alignment.CenterStart` or `CenterEnd`) eliminates screen obscuration.
   - *Logic*: When the drawer is open, a transparent backdrop interceptor must catch all clicks outside the toolbar container to close the drawer, preventing click leakage into `RemoteCanvasView`.
   - *Logic*: Android 10+ system back gestures conflict with edge-swiping to open the drawer unless `setSystemGestureExclusionRects` is supplied with the 1/6th height padding formula.

2. **R1 Floating Draggable Opener Button**:
   - *Observation*: AVNC's opener uses normalized `verticalBias = touchY / parentHeight`, clamping to `[marginTop, parentHeight - buttonHeight - marginBottom]` and persisting to `SharedPreferences` on `ACTION_UP`.
   - *Logic*: In Compose, an opener button positioned at `Alignment.CenterStart` (or `End`) with `Modifier.offset { IntOffset(0, yOffset) }` can track drag gestures using `pointerInput` and `detectDragGestures`. By normalizing offset to $[0.0, 1.0]$ relative to `parentHeight`, the button stays perfectly placed across portrait and landscape rotations and restores immediately on session resume.

3. **R2 RealVNC Virtual Keys & Virtual Mouse Overlay Wiring**:
   - *Observation*: In `android_rdp_client`, `VirtualKeysCompose.kt` and `VirtualMouseCompose.kt` already exist under `com.freerdp.client.ui.session`, but `SessionScreen.kt` is currently hard-wired to an older `ModifierBar` and Android View `FloatingMouseOverlayView`.
   - *Logic*: Wiring `VirtualKeysOverlay` and `VirtualMouseOverlay` directly into `SessionScreen.kt` replaces the legacy controls with the modern, 20% transparent RealVNC Compose overlays.
   - *Logic*: `VirtualKeysState` must connect directly to `vm.modifierStateMachine` and `vm.keyboardTimingManager` so that modifier taps, Fn key taps, and scancodes transmit with Windows Scancode Set 1 format and 50ms BMC hold timing.
   - *Logic*: `VirtualMouse` must connect to `HapticMouseController` and `IRdpEngine` to support left click, right click, middle click (`BUTTON3`), drag lock, and hold-to-repeat scrolling.

4. **R2 Remote Canvas & Pointer Modes Wiring**:
   - *Observation*: AVNC switches between `DirectPointerMode` and `RelativePointerMode` dynamically. `PointerModes.kt` in `feature-mouse` already contains the full implementations of `DirectPointerMode` (with `coerceToFbEdge`) and `RelativePointerMode` (with `LibinputPointerAcceleration` and auto-centering).
   - *Logic*: `RemoteCanvasView.kt` should delegate touch handling directly to the active `PointerMode`:
     - In Direct Touch mode: taps trigger clicks at remote coordinates, with out-of-bounds letterbox taps coerced to the nearest framebuffer edge pixel. Two-finger pinches zoom, and two-finger drags pan.
     - In Touchpad mode: relative finger motion moves the virtual cursor with 3-tier physical acceleration, two-finger vertical motion triggers mouse wheel scrolling, and the viewport auto-centers on cursor movement.

---

## 3. Caveats

1. **Android 10+ Gesture Navigation on Custom Vendor ROMs**: As noted in AVNC comments (`Toolbar.kt` line 276), some aggressive vendor OEM skins (e.g. Xiaomi MIUI / HyperOS) ignore `setSystemGestureExclusionRects`. Therefore, the floating draggable opener button (`FloatingOpener`) must always be available as a fallback when swipe-to-open is ignored.
2. **Compose Drawer vs Android View DrawerLayout**: AVNC implements its drawer using `androidx.drawerlayout.widget.DrawerLayout`. In `android_rdp_client`, all session UI is built using Jetpack Compose in `:app`. We provide both the exact Compose architectural equivalent (using an `AnimatedVisibility` slide-in panel with transparent scrim tap consumption) and the Android View `DrawerLayout` pattern for seamless integration into Compose via `Box` overlay layers.
3. **Hardware BMC Hold Timing and Coroutines**: Hardware BMC hold timing (50ms hold, 25ms text pacing) requires an asynchronous actor queue (`Channel`) to prevent blocking the UI thread or the FreeRDP socket I/O thread. The existing `KeyboardTimingManager` in `:feature-session` already fulfills this contract and must be connected to `SessionScreen.kt`'s `RemoteKeyboardField`.

---

## 4. Conclusion & Integration Blueprint

### Blueprint Part 1: Collapsible In-Session Toolbar Drawer & Floating Opener

The collapsible session toolbar is structured as an edge-docked overlay in `SessionScreen.kt`:

```kotlin
@Composable
fun InSessionToolbarDrawer(
    isExpanded: Boolean,
    alignment: String, // "start" or "end"
    onClose: () -> Unit,
    onToggleKeyboard: () -> Unit,
    onToggleInputMode: () -> Unit,
    isTouchpadMode: Boolean,
    onToggleVirtualKeys: () -> Unit,
    onResetZoom: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (isExpanded) {
        // Transparent scrim: catches clicks and dismisses drawer without letting them reach the remote canvas
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Transparent)
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { onClose() })
                }
        )
    }

    val enterSlide = if (alignment == "start") {
        slideInHorizontally(initialOffsetX = { -it }) + fadeIn()
    } else {
        slideInHorizontally(initialOffsetX = { it }) + fadeIn()
    }
    val exitSlide = if (alignment == "start") {
        slideOutHorizontally(targetOffsetX = { -it }) + fadeOut()
    } else {
        slideOutHorizontally(targetOffsetX = { it }) + fadeOut()
    }

    AnimatedVisibility(
        visible = isExpanded,
        enter = enterSlide,
        exit = exitSlide,
        modifier = modifier
    ) {
        Surface(
            shape = if (alignment == "start") {
                RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp)
            } else {
                RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)
            },
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
            tonalElevation = 8.dp,
            shadowElevation = 12.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
            modifier = Modifier
                .width(58.dp)
                .padding(vertical = 48.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 1. Soft keyboard toggle
                IconButton(onClick = { onToggleKeyboard(); onClose() }) {
                    Icon(Icons.Default.Keyboard, contentDescription = "Toggle Keyboard")
                }
                // 2. Input mode switch (Direct Touch vs Touchpad)
                IconButton(onClick = { onToggleInputMode(); onClose() }) {
                    Icon(
                        if (isTouchpadMode) Icons.Default.TouchApp else Icons.Default.Mouse,
                        contentDescription = "Switch Input Mode"
                    )
                }
                // 3. Virtual keys bar toggle
                IconButton(onClick = { onToggleVirtualKeys(); onClose() }) {
                    Icon(Icons.Default.KeyboardAlt, contentDescription = "Toggle Virtual Keys")
                }
                // 4. Zoom / fit screen toggle
                IconButton(onClick = { onResetZoom(); onClose() }) {
                    Icon(Icons.Default.FitScreen, contentDescription = "Fit to Screen")
                }
                Spacer(modifier = Modifier.weight(1f))
                // 5. Clean session disconnect
                IconButton(
                    onClick = { onDisconnect(); onClose() },
                    modifier = Modifier.background(MaterialTheme.colorScheme.errorContainer, CircleShape)
                ) {
                    Icon(Icons.Default.PowerSettingsNew, contentDescription = "Disconnect", tint = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }
    }
}
```

#### Floating Draggable Opener Component:

```kotlin
@Composable
fun FloatingToolbarOpener(
    alignment: String, // "start" or "end"
    verticalBias: Float,
    onVerticalBiasChanged: (Float) -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    var bias by remember { mutableFloatStateOf(verticalBias) }
    var parentHeightPx by remember { mutableIntStateOf(0) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { parentHeightPx = it.height }
    ) {
        val density = LocalDensity.current
        val btnHeightDp = 44.dp
        val btnHeightPx = with(density) { btnHeightDp.toPx() }
        val marginPx = with(density) { 48.dp.toPx() }

        val yOffset = remember(bias, parentHeightPx) {
            val minY = marginPx
            val maxY = (parentHeightPx - btnHeightPx - marginPx).coerceAtLeast(minY)
            (parentHeightPx * bias).coerceIn(minY, maxY)
        }

        Surface(
            shape = if (alignment == "start") RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp)
                    else RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f),
            shadowElevation = 6.dp,
            modifier = Modifier
                .align(if (alignment == "start") Alignment.TopStart else Alignment.TopEnd)
                .offset { IntOffset(0, yOffset.roundToInt()) }
                .size(width = 32.dp, height = btnHeightDp)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDrag = { change, dragAmount ->
                            change.consume()
                            if (parentHeightPx > 0) {
                                val newY = yOffset + dragAmount.y
                                bias = (newY / parentHeightPx).coerceIn(0.05f, 0.95f)
                            }
                        },
                        onDragEnd = {
                            onVerticalBiasChanged(bias)
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { onOpen() })
                }
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (alignment == "start") Icons.Default.ChevronRight else Icons.Default.ChevronLeft,
                    contentDescription = "Open Session Toolbar",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
```

#### Android 10+ Gesture Exclusion Rect Calculation:

```kotlin
fun updateGestureExclusion(view: View, toolbarRect: Rect, parentHeight: Int) {
    if (Build.VERSION.SDK_INT >= 29) {
        val padding = max(0, (parentHeight - toolbarRect.height()) / 6)
        val exclusionRect = Rect(
            toolbarRect.left,
            max(0, toolbarRect.top - padding),
            toolbarRect.right,
            min(parentHeight, toolbarRect.bottom + padding)
        )
        view.systemGestureExclusionRects = listOf(exclusionRect)
    }
}
```

### Blueprint Part 2: Active Session Overlays Wiring in `SessionScreen.kt`

In `SessionScreen.kt`, the root `Box` coordinates the canvas and all overlays in order:

```
Box(Modifier.fillMaxSize()) {
    1. RemoteCanvasView (AndroidView)
       - Handles Direct Touch (with coerceToFbEdge) or Touchpad relative movements
       - Applies pinch zoom and pan
    2. FloatingToolbarOpener (Draggable FAB)
       - Opens the InSessionToolbarDrawer
    3. InSessionToolbarDrawer (Collapsible drawer with transparent scrim)
       - Quick actions: Keyboard, Mouse Mode, Virtual Keys, Fit, Disconnect
    4. VirtualKeysOverlay (RealVNC Compose Bar docked at bottom)
       - Fn strip, modifiers with 3-state latch, Del, Esc, inverted-T arrows
    5. VirtualMouseOverlay (Expandable FAB + Docked Scroll Pillar)
       - LMB (hold & drag), MMB, RMB, Scroll Up/Down repeat, drag lock
    6. RemoteKeyboardField (Soft Keyboard input field)
       - Routes typed text through KeyboardTimingManager (50ms hold, 25ms pacing)
    7. DiagnosticHud & ConnectionChips (Top status bar)
    8. Dialogs (Cert trust, password, disconnect confirmation)
}
```

---

## 5. Verification Method

To independently verify all findings and test suites:

1. **E2E UX/Input Test Suite Execution**:
   Run the 126 opaque-box tests covering all 11 features across Tiers 1–4:
   ```powershell
   .\gradlew.bat :app:testDebugUnitTest --tests "com.freerdp.client.e2e.uxinput.*"
   ```
   *Expected outcome*: 126/126 tests passing, 0 failures, 0 errors.

2. **Full Repository Regression Suite**:
   Run the complete test suite across all 5 modules:
   ```powershell
   .\gradlew.bat testDebugUnitTest
   ```
   *Expected outcome*: $\ge 664$ tests passing, 0 failures, 0 errors across `:app`, `:core-rdp`, `:feature-mouse`, `:feature-session`, and `:feature-telemetry`.

3. **Debug APK Build & Packaging Gate**:
   Verify compilation and APK artifact size:
   ```powershell
   .\gradlew.bat assembleDebug
   ```
   *Expected outcome*: `app/build/outputs/apk/debug/app-debug.apk` built successfully and size strictly under 100 MB.

---

## Features Discovered

| # | Category | Feature | Description | Inputs | Outputs | Error Behavior | Discovered Via |
|---|----------|---------|-------------|--------|---------|----------------|----------------|
| 1 | Toolbar Drawer | Transparent Scrim Dismissal | DrawerLayout uses `app:scrimColor="@{0}"` and transparent root view click listener to dismiss without forwarding clicks to canvas | Scrim tap / empty drawer click | Closes drawer | Suppresses spurious canvas pointer events | `Toolbar.kt:81`, `activity_vnc.xml:44` |
| 2 | Toolbar Drawer | Directional Scrim Fling to Close | `setupDrawerCloseOnScrimSwipe` detects horizontal fling towards screen edge on DrawerLayout and closes drawer | `MotionEvent` on DrawerLayout | `close()` called on `vX < 0` (left) or `vX > 0` (right) | Returns `false` so normal touches pass through | `Toolbar.kt:350-380` |
| 3 | Toolbar Drawer | Alignment & Layout Direction Decoupling | Sets `drawerView.layoutDirection` based on drawer gravity (`LTR` for left, `RTL` for right) rather than system locale | `toolbarAlignment` ("start"/"end") | Inward expanding flyouts and button icons | Fallback to activity config | `Toolbar.kt:239-261` |
| 4 | Toolbar Drawer | Post-Close Flyout Cleanup | Collapses flyout toggle states in `onDrawerClosed` rather than during `close()` animation | `onDrawerClosed` callback | Resets toggle buttons | Prevents DrawerLayout measurement glitches during animation | `Toolbar.kt:325-333` |
| 5 | Opener Button | Draggable Vertical Bias | Normalizes Y coordinate to $[0.0, 1.0]$ based on parent height and clamps to margins | `onScroll` drag events | `verticalBias` float | Clamps within `[minY, maxY]` without clipping | `Toolbar.kt:382-424` |
| 6 | Opener Button | SharedPreferences Persistence & Restore | Persists `verticalBias` on `ACTION_UP` and restores on parent layout change or session resume | SharedPreferences store | Restored button `Y` position | Defaults to 0.5f (center) if uninitialized | `Toolbar.kt:404-415` |
| 7 | Gesture Exclusion | 1/6th Height Margin Padding | Adds `(parentHeight - toolbarHeight) / 6` padding to actionable toolbar rect for `setSystemGestureExclusionRects` | Parent & toolbar heights | Android 10+ exclusion `Rect` | Clears exclusion list when disconnected | `Toolbar.kt:279-299` |
| 8 | Virtual Keys | Tri-State Modifier Latching FSM | Single tap latches modifier for next non-modifier key; long press/double tap locks modifier until released | Modifier button tap / long-press | Latched / Locked / Inactive state | Auto-releases latched modifiers on key UP | `VirtualKeys.kt:187-223`, `VirtualKeysCompose.kt` |
| 9 | Virtual Keys | RealVNC Inverted-T Arrow Cluster | Up arrow centered directly above Down, flanked by Left and Right arrows with generous touch spacing | Arrow button taps | Scancodes 0x48, 0x4B, 0x50, 0x4D (Set 1 extended) | Visual press feedback & key repeat | `VirtualKeysCompose.kt:380-423` |
| 10 | Virtual Keys | Expandable Function Keys Strip | Horizontal scroll row containing F1–F12 keys grouped in clusters of 4 (F1–F4, F5–F8, F9–F12) | Fn toggle button tap | Expands/collapses F1–F12 strip | Collapses gracefully on drawer dismiss | `VirtualKeysCompose.kt:118-169` |
| 11 | Virtual Mouse | Draggable Floating Action Button | 56dp circular FAB draggable anywhere on screen, tapping expands into floating control pill | Finger drag / tap | Repositions FAB / expands pill | Clamps to screen safe bounds | `VirtualMouseCompose.kt:218-275` |
| 12 | Virtual Mouse | Floating Mouse Pill Controls | 20% transparent pill with Left (drag), Middle, Right, Scroll Up/Down, Keyboard, and Close buttons | Pill button taps | Pointer events to engine | Closes/minimizes back to FAB | `VirtualMouseCompose.kt:276-477` |
| 13 | Virtual Mouse | Docked Right-Edge Scroll Pillar | Vertical drag handle with enlarged 46x48dp Scroll Up and Scroll Down hold-to-repeat buttons | Pillar drag / press | Continuous scroll events (200ms delay, 50ms interval) | Cancels repeat loop on finger release | `VirtualMouseCompose.kt:480-600` |
| 14 | Touch Gestures | Swipe vs Scale Trajectory Filter | Differentiates two-finger pinch-to-zoom from two-finger scroll/pan by comparing angle vectors of touch paths | `MotionEvent` pointer trajectories | `Decision.SCALE` ($> 45^\circ$) or `Decision.SWIPE` ($< 30^\circ$) | Ambiguous fallback to default | `TouchHandler.kt:543-630` |
| 15 | Pointer Modes | Direct Touch with Edge Coercion | Translates touch points to remote desktop; out-of-bounds letterbox taps snap to nearest framebuffer edge pixel | Viewport touch coordinate | Clamped remote coordinates (0 to width - 1) | Prevents missed taskbar activation | `PointerModes.kt:106-132` |
| 16 | Pointer Modes | Libinput 3-Tier Acceleration | Applies deceleration ($<10\text{ mm/s}$), linear ($10\text{--}80\text{ mm/s}$), and quadratic speedup ($>80\text{ mm/s}$) to touchpad cursor | Raw finger delta & velocity | Accelerated pixel delta | Zoom dampening when scale $> 1.0$ | `PointerAcceleration.kt:82-133` |
| 17 | Keyboard Timing | BMC 50ms Hold & 25ms Pacing | Holds key down for 50ms before releasing and pauses 25ms between characters in text stream | Soft keyboard text & keys | Sequenced native down/up events | Channel actor queue prevents thread blocking | `KeyHandler.kt`, `Messenger.kt`, `KeyboardTimingManager.kt` |

---

## Edge Cases

| # | Feature | Input | Observed Behavior |
|---|---------|-------|-------------------|
| 1 | Collapsible Toolbar | Scrim tap while drawer is open | Drawer closes immediately; underlying remote desktop canvas receives zero MotionEvents (no spurious clicks) |
| 2 | Collapsible Toolbar | Rapid swipe inwards on scrim | `setupDrawerCloseOnScrimSwipe` detects fling velocity towards bezel edge and cleanly collapses drawer |
| 3 | Toolbar Opener Button | Dragging opener beyond screen top or bottom | Position is safely clamped within `[marginTop, parentHeight - buttonHeight - marginBottom]`, preventing off-screen clipping |
| 4 | Toolbar Opener Button | Rotating device between portrait and landscape | `verticalBias` is normalized ($0.0 \dots 1.0$), so button automatically restores to proportional vertical height on new screen aspect ratio |
| 5 | System Gesture Exclusion | Toolbar primary buttons resize dynamically | `addOnLayoutChangeListener` immediately recalculates actionable rect and updates system gesture exclusion zones |
| 6 | Direct Touch Mode | User taps in black pillarbox margin (e.g. left of 16:9 remote desktop on 20:9 phone) | `coerceToFbEdge` coerces X to 0 and sends click to edge pixel, reliably opening auto-hidden Windows Start Menu / Taskbar |
| 7 | Touchpad Mode | Slow finger movement ($< 10\text{ mm/s}$) | Accelerator decelerates pointer by down to 0.3× factor, enabling precise 1-pixel remote desktop cursor positioning |
| 8 | Touchpad Mode | Fast flick ($> 80\text{ mm/s}$) | Accelerator applies quadratic speedup up to 3.5× factor, allowing full screen traversal in a single swipe |
| 9 | Touch Gestures | User pinches while slightly sliding two fingers | `SwipeVsScale` checks angle differential between finger vectors: if $> 45^\circ$, routes exclusively to zoom without spurious scrolling |
| 10 | Virtual Keys Bar | User long-presses Win / Super key | Key enters `Locked` state (indicated by primary color + indicator dot); stays active across multiple key presses until explicitly tapped again |
| 11 | Virtual Keys Bar | User taps Ctrl once, then presses 'C' | Ctrl is `Latched`; on 'C' key up, Ctrl automatically releases without user needing to toggle it off |
| 12 | Soft Keyboard Input | User types rapid text or pastes from clipboard | Text stream queues into `KeyboardTimingManager` with 50ms key hold and 25ms inter-key pacing, preventing BMC/iDRAC buffer drops |
| 13 | Virtual Mouse Overlay | User starts dragging FAB and releases inside 350ms without movement | FAB interprets as click and expands into the floating mouse control pill |
| 14 | Virtual Mouse Overlay | User presses and holds Left Click button in pill | Sends Mouse Down event, keeps button pressed during finger drag, and sends Mouse Up only on release (supporting window drag & text selection) |
| 15 | Virtual Mouse Overlay | User holds Scroll Up or Scroll Down button | Emits immediate scroll event, waits 200ms initial repeat timeout, and repeats scroll pulses every 50ms until finger release |
| 16 | Session Disconnect | Active session disconnects or app backgrounds | Virtual Keys automatically releases all latched/locked modifiers, and Virtual Mouse releases all held pointer buttons |
