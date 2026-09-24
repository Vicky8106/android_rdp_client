# Project: Android RDP Client UX & Input Port

## Architecture
Porting the mature in-session user experience, virtual keys, touch/mouse pointer modes, and keyboard input handling from reference AVNC (`C:\Users\Administrator\avnc`) into Android RDP client (`C:\Users\Administrator\teamwork_projects\android_rdp_client`).

```
+--------------------------------------------------------------------------------------+
|                                        :app                                           |
|  ALL Compose Material 3 UX & Overlays live HERE:                                     |
|  - Collapsible In-Session Toolbar & Drawer with floating opener button & gesture rects|
|  - RealVNC-style Virtual Keys Bar (expandable Fn strip, sticky modifiers, inverted-T) |
|  - Virtual Mouse Compose Floating FAB, expandable pill, and right docked scroll pillar|
|  - RemoteCanvasView integration and gesture routing                                  |
+--------------------+---------------------------+--------------------------+----------+
                     |                           |                          |
+--------------------v---+             +---------v------------+             +----------+
|    :feature-mouse      |             |  :feature-session    |             v
|  (Input engine & logic)|             |  (Keyboard & logic)  |   +---------------------+
|  - MouseController     |             |  - ScancodeTranslator|   | :feature-telemetry  |
|    (Left, Right, Mid,  |             |    (Windows VK &     |   |  - Telemetry HUD    |
|     Drag, Scroll)      |             |     extended flags)  |   |  - Frame pacer      |
|  - PointerModes        |             |  - BMC Key Hold      |   |  - Auto-reconnect   |
|    (Direct & Touchpad) |             |    Timing Engine     |   +---------------------+
|  - 3-tier Libinput     |             |    (50ms hold,       |
|    acceleration math   |             |     25ms pacing)     |
|  - Coordinate          |             |  - ModifierStateMachine
|    Transformer         |             |    (3-state latch)   |
+------------+-----------+             +----------+-----------+
             |                                    |
             +--------------------+---------------+
                                  |
                      +-----------v------------+
                      |       :core-rdp        |
                      |  - IRdpEngine          |
                      |  - RdpPointerFlags     |
                      |    (BUTTON3/Middle)    |
                      |  - LibFreeRDP JNI      |
                      +------------------------+
```

## Feature Inventory
| # | Feature | Description | Milestone | Source |
|---|---------|-------------|-----------|--------|
| 1 | Collapsible Toolbar Drawer Layout | Transparent scrim DrawerLayout with Start/End docking and inward flyout | M1 | survey |
| 2 | Floating Opener Button & Persistence | Repositionable pill button with persisted vertical bias across sessions | M1 | survey |
| 3 | System Gesture Exclusion Zones | Dynamic Android 10+ gesture exclusion rects with 1/6th height padding | M1 | survey |
| 4 | Toolbar Actions & Navigation | Soft kbd toggle, mode switch, virtual keys toggle, scale/fit, disconnect | M1 | survey |
| 5 | RealVNC Virtual Keys Compose Surface | Translucent Material 3 Surface (alpha 0.80, 16dp rounded top) with Fn strip | M2 | survey |
| 6 | Tri-State Modifier State Machine | Unlatched, Latched/Sticky (tap), Locked (long press), auto-unlatch on release | M2 | survey |
| 7 | RealVNC Desktop Keys & Inverted-T Pad | Esc, Tab, Win, Del, Ctrl, Alt, Shift, Caps, Home/End, Inverted-T arrows, PgUp/Dn | M2 | survey |
| 8 | Scroll Repeat Buttons | Enlarged scroll buttons with 200ms initial delay and 50ms repeat interval | M2 | survey |
| 9 | Hardware BMC Key Hold Timing | 50ms key-down hold, 25ms text streaming pacing via non-blocking Channel queue | M2 | survey |
| 10 | Windows VK & Extended Scancode Bridge | Mapping to Windows VK codes with 0x0100 extended bit preserved through engine | M2 | survey |
| 11 | Direct Touch Mode & Edge Coercion | Direct tap-to-click, 2-finger pan/scroll, pinch-zoom, and auto-hide edge coercion | M3 | survey |
| 12 | Touchpad Mode & 3-Tier Acceleration | Relative cursor tracking, libinput physical acceleration curve & zoom dampening | M3 | survey |
| 13 | Middle Mouse Click Support | Add handleMiddleClick to MouseController and map to RdpPointerFlags.BUTTON3 | M3 | survey |
| 14 | Virtual Mouse Compose Overlay | Draggable 56dp FAB expanding to pill (LMB drag, MMB, RMB, Scroll) & scroll pillar| M3 | survey |
| 15 | Coordinate Transformation Bridge | Bidirectional viewport-to-framebuffer matrix math with letterbox centering | M3 | survey |
| 16 | Expanded Unit & Robolectric Suites | Regression tests across :feature-mouse, :feature-session, and :app | M4 | survey |
| 17 | Release APK Assembly & Verification | 100% test pass on testDebugUnitTest, signed APK in releases/ under 100 MB | M4 | survey |

## Milestones
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| M1 | Collapsible In-Session Toolbar & Navigation | Features 1–4: Drawer layout, floating draggable opener, gesture exclusion, toolbar action controls | none | PLANNED |
| M2 | RealVNC-Style Virtual Keys Bar & Soft Keyboard Timing | Features 5–10: VirtualKeysCompose, tri-state modifier FSM, inverted-T arrows, BMC 50ms hold timing, VK scancode translation | none | PLANNED |
| M3 | Multi-Mode Touch, Touchpad & Virtual Mouse Controls | Features 11–15: Direct touch with edge coercion, touchpad with 3-tier acceleration, Middle click in MouseController, VirtualMouseCompose | none | PLANNED |
| M4 | Final Integration, E2E Validation, Hardening & Release Packaging | Features 16–17: 100% E2E test pass (Tiers 1-4), Tier 5 adversarial hardening, testDebugUnitTest pass, assembleDebug APK < 100 MB | M1, M2, M3, TEST_READY | PLANNED |

## Interface Contracts

### 1. `MouseController` (`com.freerdp.feature.mouse.MouseController`)
```kotlin
package com.freerdp.feature.mouse

interface MouseController {
    fun handleLeftClick(screenX: Float, screenY: Float)
    fun handleRightClick(screenX: Float, screenY: Float)
    fun handleMiddleClick(screenX: Float, screenY: Float) // Ported requirement
    fun handleDoubleClick(screenX: Float, screenY: Float)
    fun handleDragStart(screenX: Float, screenY: Float)
    fun handleDragMove(screenX: Float, screenY: Float)
    fun handleDragEnd(screenX: Float, screenY: Float)
    fun handleScroll(screenX: Float, screenY: Float, deltaY: Float)
    fun setTouchpadMode(enabled: Boolean)
    fun setCursorVisible(visible: Boolean)
}
```

### 2. `KeyboardTimingManager` / BMC Timing Contract (`com.freerdp.feature.session.keyboard`)
```kotlin
package com.freerdp.feature.session.keyboard

interface KeyboardTimingManager {
    val keyHoldDurationMs: Long get() = 50L
    val interKeyPacingMs: Long get() = 25L

    fun sendKeyPressWithHold(vkCode: Int, isExtended: Boolean = false, holdDurationMs: Long = 50L)
    fun sendTextWithPacing(text: String, pacingDelayMs: Long = 25L)
    fun sendKeyDown(vkCode: Int, isExtended: Boolean = false)
    fun sendKeyUp(vkCode: Int, isExtended: Boolean = false)
    fun releaseAllModifiers()
}
```

### 3. Pointer Acceleration Contract (`com.freerdp.feature.mouse`)
```kotlin
package com.freerdp.feature.mouse

interface PointerAcceleration {
    fun computeDelta(rawDeltaX: Float, rawDeltaY: Float, dpi: Float, zoomScale: Float): Pair<Float, Float>
}
```

## Code Layout
- `app/src/main/java/com/freerdp/client/ui/session/`:
  - `SessionScreen.kt`: Integrated session screen holding canvas, collapsible toolbar drawer, virtual keys overlay, and virtual mouse.
  - `RemoteCanvasView.kt`: Canvas view binding frame pacer, gesture detector, coordinate transformer, and pointer modes.
  - `VirtualKeysCompose.kt`: RealVNC-style Compose virtual keys bar (Fn bar, sticky modifiers, desktop keys, inverted-T arrow pad).
  - `VirtualMouseCompose.kt`: Draggable FAB expanding to pill controls with Left hold-and-drag, Middle click, Right click, and scroll pillar.
  - `InSessionToolbar.kt`: Collapsible toolbar drawer and floating draggable opener.
- `feature-mouse/src/main/java/com/freerdp/feature/mouse/`:
  - `MouseController.kt` & `DefaultMouseController.kt`: Updated with `handleMiddleClick` and acceleration integration.
  - `PointerModes.kt`: DirectPointerMode and RelativePointerMode with edge coercion and libinput 3-tier acceleration.
  - `PointerAcceleration.kt`: Mathematical acceleration curves with velocity mm/s conversion and zoom dampening.
  - `CoordinateTransformer.kt`: Viewport-to-framebuffer matrix transformations.
- `feature-session/src/main/java/com/freerdp/feature/session/keyboard/`:
  - `ScancodeTranslator.kt`: Comprehensive Windows VK code and extended scancode mapping for all virtual keys and soft keyboard inputs.
  - `KeyboardTimingManager.kt`: Non-blocking coroutine actor queue providing 50ms BMC hold timing and 25ms text streaming pacing.
  - `ModifierStateMachine.kt`: 3-state latching FSM with sticky and locked modifier support.
- `core-rdp/src/main/java/com/freerdp/core/protocol/`:
  - `RdpPointerFlags.kt`: MS-RDPBCGR pointer flags including `MIDDLE_BUTTON_DOWN` and `MIDDLE_BUTTON_UP`.
