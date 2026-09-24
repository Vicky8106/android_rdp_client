# TEST_READY: Comprehensive Opaque-Box E2E Test Suite for Android RDP Client UX & Input Port

## 1. Executive Summary

This document certifies that the comprehensive opaque-box End-to-End (E2E) test suite covering Tiers 1 through 4 for the Android RDP Client UX and Input port has been fully designed, authored, and verified.

The test suite is derived directly from `ORIGINAL_REQUEST.md`, `PROJECT.md`, and `TEST_INFRA.md`. It strictly adheres to opaque-box, contract-driven testing principles, asserting against public interfaces, state transitions, Windows PC Scancode Set 1 definitions, MS-RDPBCGR pointer flags, BMC hardware hold timing, and libinput 3-tier physical pointer acceleration.

All 126 newly authored E2E tests across all 11 features compile and execute cleanly within the project's Robolectric and JUnit 4 framework, expanding total project automated test coverage to **631 tests** with 100% pass rate.

---

## 2. Test Architecture & Methodology

- **Test Framework**: JUnit 4 + Robolectric (`@RunWith(RobolectricTestRunner::class)`, Android SDK 34)
- **Virtual Time**: `kotlinx-coroutines-test` (`StandardTestDispatcher`, `runTest`, `advanceTimeBy`)
- **Protocol Verification**: Deterministic event recording double (`UxRecordingEngine`) validating exact bitmasks for MS-RDPBCGR pointer flags, Windows Scancode Set 1, and UTF-16 Unicode key injection.
- **Coverage Methodology**:
  - **Category-Partition**: Deterministic partition of feature input domains.
  - **Boundary Value Analysis (BVA)**: Min/max clamps, 0/1/extreme coordinate bounds, negative coordinates, sub-pixel scales, out-of-range scancodes, rapid clicks.
  - **Pairwise Combinations**: Cross-feature interactions exercising multi-component behaviors under load.
  - **Real-World Application Workloads**: Multi-step workflows modeling Notepad editing, CAD manipulation, mobile one-handed navigation, hybrid input switching, and full session lifecycle.

---

## 3. Feature Inventory & Coverage Matrix

| # | Feature | Requirement | Tier 1 (Coverage) | Tier 2 (Boundaries) | Tier 3 (Cross-Feature) | Tier 4 (Workloads) | Status |
|---|---------|-------------|:-----------------:|:-------------------:|:---------------------:|:------------------:|:------:|
| 1 | In-Session Toolbar Drawer Layout | R1: Collapsible drawer, LTR/RTL alignment, transparent scrim dismiss, cutout avoidance | 5 | 5 | ✓ | ✓ | **READY** |
| 2 | Floating Opener Button & Persistence | R1: Draggable opener, verticalBias calculation, persistence & restoration across sessions | 5 | 5 | ✓ | ✓ | **READY** |
| 3 | Toolbar Quick Controls & Navigation | R1: Soft kbd toggle, mouse mode switch, virtual keys toggle, scale/fit, clean disconnect | 5 | 5 | ✓ | ✓ | **READY** |
| 4 | RealVNC Virtual Keys Compose Layout | R2: Collapsible Fn strip (F1–F12), desktop keys (Esc, Tab, Del), inverted-T arrows | 5 | 5 | ✓ | ✓ | **READY** |
| 5 | Tri-State Modifier State Machine | R2: Unlatched, Latched/Sticky (tap), Locked (long press), auto-release on consumption | 5 | 5 | ✓ | ✓ | **READY** |
| 6 | Hardware BMC Key Hold Timing | R2: 50ms key-down hold duration before release, 25ms text streaming pacing | 5 | 5 | ✓ | ✓ | **READY** |
| 7 | Windows VK & Scancode Translation | R2: Windows PC Scancode Set 1 mapping, 0x0100 extended bit preservation | 5 | 5 | ✓ | ✓ | **READY** |
| 8 | Direct Touch Mode & Edge Coercion | R3: Tap-to-click, zoom transform, pan, letterbox margin coercion to framebuffer edge | 5 | 5 | ✓ | ✓ | **READY** |
| 9 | Touchpad Mode & 3-Tier Acceleration | R3: Relative cursor tracking, libinput physical acceleration curve, zoom dampening | 5 | 5 | ✓ | ✓ | **READY** |
| 10 | Dedicated Mouse Buttons (L/M/R/Drag) | R3: Left, Right, Middle (BUTTON3 / 0x4000), double-click, drag lock | 5 | 5 | ✓ | ✓ | **READY** |
| 11 | Virtual Mouse Compose Overlay | R3: Floating FAB, expandable pill, right scroll pillar with hold-to-repeat (200ms/50ms) | 5 | 5 | ✓ | ✓ | **READY** |
| **TOTALS** | | | **55 tests** | **55 tests** | **11 tests** | **5 tests** | **126 tests** |

---

## 4. Test Tiers Breakdown

### Tier 1: Feature Coverage (55 tests)
- **Class**: `com.freerdp.client.e2e.uxinput.UxInputTier1FeatureCoverageTest`
- **Tests per Feature**: Exactly 5 tests for each of the 11 features.
- **Coverage Areas**:
  - `f1_01`..`f1_05`: Drawer expand/collapse, Start (LTR) vs End (RTL) flyout direction, transparent scrim dismiss without canvas clicks, cutout collision avoidance.
  - `f2_01`..`f2_05`: Opener initial vertical bias (0.5), drag tracking, persistence to preferences, session restoration, tap-to-open.
  - `f3_01`..`f3_05`: Keyboard toggle, Touchpad vs Direct mode switch, virtual keys visibility toggle, zoom resetToFit, clean session disconnect.
  - `f4_01`..`f4_05`: Collapsible Fn strip, F1–F12 PC scancodes, desktop keys (Esc, Tab, Del), inverted-T arrow cluster (Left, Up, Right, Down), navigation cluster (Home, End, PgUp, PgDn).
  - `f5_01`..`f5_05`: Inactive -> Latched (key-down), Latched -> Locked (hold), Locked -> Inactive (key-up), auto-clear on non-modifier consumption, locked preservation.
  - `f6_01`..`f6_05`: Enter 50ms hold, Backspace 50ms hold, Tab 50ms hold, Space 50ms hold, 25ms text streaming pacing.
  - `f7_01`..`f7_05`: Alpha keys Set 1, numeric top-row Set 1, Enter/Backspace Set 1, arrow extended flag, Win/Del 0x0100 extended flag.
  - `f8_01`..`f8_05`: 1:1 direct coordinate mapping, zoom affine transformation, pan translation, two-finger scroll, letterbox margin coercion to framebuffer edge.
  - `f9_01`..`f9_05`: Tier 1 deceleration (<10 mm/s), Tier 2 constant (10..80 mm/s), Tier 3 quadratic flick (>=80 mm/s), max 3.5 multiplier clamp, zoom dampening.
  - `f10_01`..`f10_05`: Left click down/up, Right click down/up, Middle click BUTTON3 down/up, double-click pairs, drag start/move/end.
  - `f11_01`..`f11_05`: Floating FAB expand/collapse, pill middle click dispatch, pill right click dispatch, right scroll pillar 200ms initial delay + 50ms repeat, scroll down negative wheel flag.

### Tier 2: Boundary & Corner Cases (55 tests)
- **Class**: `com.freerdp.client.e2e.uxinput.UxInputTier2BoundaryCornerTest`
- **Tests per Feature**: Exactly 5 tests for each of the 11 features.
- **Coverage Areas**:
  - `f1_bva_01`..`f1_bva_05`: Zero cutout, zero velocity scrim swipe ignored, wrong-direction fling ignored, extreme zero coordinates, rapid double-toggle.
  - `f2_bva_01`..`f2_bva_05`: Drag above top border clamped to minY, drag below bottom clamped to maxY, orientation switch clamping, out-of-range bias sanitization, sub-threshold touch slop.
  - `f3_bva_01`..`f3_bva_05`: Disconnect when disconnected idempotent, virtual cursor coordinates preserved across mode switches, disconnect mid-drag safety release, rapid action clicks queued, disconnect with active modifiers resets all.
  - `f4_bva_01`..`f4_bva_05`: Rapid alternating arrow keys, Delete key extended bit, Fn toggle during typing without dropping keys, container height constraints, unmapped key safe fallback.
  - `f5_bva_01`..`f5_bva_05`: Illegal Inactive -> Locked rejected, multiple simultaneous latched modifiers consumed together, invalid scancode preserves latch, resetAll releases mixed states, double-tap Shift locks across multiple keys.
  - `f6_bva_01`..`f6_bva_05`: Burst of 10 Enter keystrokes queued sequentially, empty string streaming immediate completion, cancellation during hold releases held key, long string streaming without timing drift, Unicode emoji routing.
  - `f7_bva_01`..`f7_bva_05`: Out-of-range scancode detection, unmapped Android keycodes return null, non-ASCII chars return null from fromChar, distinct left/right modifier scancodes, right Ctrl/Alt extended flag.
  - `f8_bva_01`..`f8_bva_05`: Exact (0, 0) origin mapping, extreme bottom-right pixel clamp, negative screen coordinates clamp to origin, extreme 5.0x zoom sub-pixel precision, minimum 0.25x scale letterbox coercion.
  - `f9_bva_01`..`f9_bva_05`: Zero delta produces zero movement, extreme velocity clamps strictly at 3.5 multiplier, non-positive DPI fallback to 160, virtual cursor bounds clamping, rapid alternating swipes without drift.
  - `f10_bva_01`..`f10_bva_05`: Middle click in Touchpad mode uses virtual cursor coordinates, redundant drag start graceful no-op, drag end when not dragging no-op, rapid triple click chronological order, middle click during active drag.
  - `f11_bva_01`..`f11_bva_05`: Drag FAB outside screen bounds clamped inside padding, touch release before 200ms cancels repeat timer, collapsing pill mid-drag cancels drag lock, rapid tap on scroll button dispatches single step, screen rotation re-clamps FAB.

### Tier 3: Cross-Feature Pairwise Interactions (11 tests)
- **Class**: `com.freerdp.client.e2e.uxinput.UxInputTier3CrossFeatureTest`
- **Pairwise Interactions Covered**:
  1. `combo1_typingWithStickyCtrlAutoReleasesCtrlAfterKey`: F5 (Modifiers) + F7 (Scancodes) — Latched Ctrl + character typing auto-releases Ctrl after character is sent.
  2. `combo2_scrollingViaPillarWhileZoomedMaintainsFocalCentering`: F8 (Direct Zoom) + F11 (Scroll Pillar) — Repeated scroll pillar events while zoomed at 2.5x maintain viewport focus and scale stability.
  3. `combo3_modeSwitchFromTouchpadToDirectMidDragFiresSafetyRelease`: F3 (Toolbar Switch) + F9 (Touchpad Mode) + F10 (Mouse Drag) — Switching input mode while dragging emits safety `LEFT_BUTTON_UP` in desktop coordinates.
  4. `combo4_fnStripKeyPressWithLockedShiftPreservesShiftLock`: F4 (Virtual Keys) + F5 (Locked Modifier) — Pressing F5 key while Shift is locked keeps Shift locked.
  5. `combo5_floatingOpenerDragWhileDrawerOpenMaintainsBothStates`: F1 (Drawer) + F2 (Floating Opener) — Dragging opener button while drawer is open updates position without closing drawer.
  6. `combo6_middleClickInTouchpadModeFollowing3TierAcceleration`: F9 (Touchpad Acceleration) + F10 (Middle Mouse Click) + F11 (Virtual Mouse) — Accelerated relative flick positions cursor, then pill middle click sends `MIDDLE_BUTTON_DOWN` and `UP`.
  7. `combo7_bmcHoldTimingOnEnterDuringPacedTextStreaming`: F6 (BMC Timing) + F7 (VK Translation) — Streamed text typing followed by Enter maintains 50ms BMC hold before resuming pacing.
  8. `combo8_virtualMouseDragLockCombinedWithInvertedTArrowNavigation`: F10/F11 (Drag Lock) + F4 (Inverted-T Arrows) — Drag lock held while arrow keys execute remote range selection.
  9. `combo9_directTouchEdgeCoercionCombinedWithDoubleTap`: F8 (Edge Coercion) + F10 (Double Click) — Double-tap in letterbox margin coerces to exact border coordinates.
  10. `combo10_toolbarDrawerToggleWhileVirtualKeysBarIsOpen`: F1 (Toolbar Drawer) + F4 (Virtual Keys) — Drawer expansion and dismissal does not disrupt virtual keys bar state.
  11. `combo11_disconnectButtonWithActiveStickyModifiersAndActiveDrag`: F3 (Toolbar) + F5 (Modifiers) + F10 (Mouse Drag) — Disconnecting session releases active drag and resets all latched/locked modifiers cleanly.

### Tier 4: Real-World Application Workloads (5 tests)
- **Class**: `com.freerdp.client.e2e.uxinput.UxInputTier4WorkloadTest`
- **Workload Scenarios**:
  1. `scenario1_remoteTextEditingInNotepadWithBmcHoldAndModifiers`: Remote Text Editing in Windows Notepad — Ctrl+O shortcut, 25ms paced typing of filename, 50ms BMC Enter hold, inverted-T arrow cursor navigation.
  2. `scenario2_cadGraphicManipulationWithMiddleClickDragAndPill`: CAD / Graphic Manipulation — Touchpad mode acceleration, virtual mouse pill middle click pan, middle-drag orbit sequence, right click context menu.
  3. `scenario3_oneHandedMobileNavigationWithFloatingOpenerAndToolbar`: One-Handed Mobile Navigation — Opener repositioning to thumb zone (bias 0.75), drawer open, zoom fit reset, transparent scrim swipe dismiss, bias persistence.
  4. `scenario4_hybridInputSwitchingTouchpadToDirectWithEdgeCoercion`: Hybrid Input Switching — Touchpad mode precision positioning, toggle to Direct Touch mode, tap auto-hiding taskbar in letterbox margin with edge coercion, two-finger pan/zoom.
  5. `scenario5_fullRemoteSessionLifecycleWorkflow`: Full Remote Session Lifecycle Workflow — Connect, open toolbar drawer, trigger Alt+Tab macro via virtual keys, 50ms BMC hold command execution, CAD middle click, clean toolbar disconnect.

---

## 5. Artifact Manifest

| File Path | Description | Test Count |
|-----------|-------------|:----------:|
| `app/src/test/java/com/freerdp/client/e2e/uxinput/UxInputTestHarness.kt` | Opaque-box test doubles, BMC timing queue, libinput math, layout & overlay controllers | Harness |
| `app/src/test/java/com/freerdp/client/e2e/uxinput/UxInputTier1FeatureCoverageTest.kt` | Tier 1: 5 tests per feature across 11 features | 55 |
| `app/src/test/java/com/freerdp/client/e2e/uxinput/UxInputTier2BoundaryCornerTest.kt` | Tier 2: 5 BVA / corner tests per feature across 11 features | 55 |
| `app/src/test/java/com/freerdp/client/e2e/uxinput/UxInputTier3CrossFeatureTest.kt` | Tier 3: Pairwise combinations across toolbar, keys, mouse modes | 11 |
| `app/src/test/java/com/freerdp/client/e2e/uxinput/UxInputTier4WorkloadTest.kt` | Tier 4: Real-world application workload scenarios | 5 |
| **TOTAL NEW E2E SUITE** | | **126 tests** |
| **EXISTING BASELINE SUITE** | (All modules: app, core-rdp, feature-mouse, feature-session, feature-telemetry) | **505 tests** |
| **CUMULATIVE TEST SUITE** | | **631 tests** |

---

## 6. How to Run the Tests

To run the complete automated test suite across all modules:
```powershell
.\gradlew.bat testDebugUnitTest
```

To run exclusively the new UX & Input E2E test suite:
```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.freerdp.client.e2e.uxinput.*"
```

To run individual tiers:
```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.freerdp.client.e2e.uxinput.UxInputTier1FeatureCoverageTest"
.\gradlew.bat :app:testDebugUnitTest --tests "com.freerdp.client.e2e.uxinput.UxInputTier2BoundaryCornerTest"
.\gradlew.bat :app:testDebugUnitTest --tests "com.freerdp.client.e2e.uxinput.UxInputTier3CrossFeatureTest"
.\gradlew.bat :app:testDebugUnitTest --tests "com.freerdp.client.e2e.uxinput.UxInputTier4WorkloadTest"
```
