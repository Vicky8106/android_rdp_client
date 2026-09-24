# E2E Test Infra: Android RDP Client UX & Input Port

## Test Philosophy
- Opaque-box, requirement-driven. No dependency on implementation internals.
- Verification channels: public component APIs, state flows, view interactions, FreeRDP protocol event verifications.
- Methodology: Category-Partition + BVA + Pairwise + Workload Testing.

## Feature Inventory Under Test
| # | Feature | Requirement | Tier 1 | Tier 2 | Tier 3 |
|---|---------|-------------|:------:|:------:|:------:|
| 1 | In-Session Toolbar Drawer Layout | R1: Collapsible toolbar & drawer | 5 | 5 | ✓ |
| 2 | Floating Opener Button & Persistence | R1: Draggable opener with verticalBias | 5 | 5 | ✓ |
| 3 | Toolbar Quick Controls & Navigation | R1: Kbd, mode switch, keys, zoom, disconnect | 5 | 5 | ✓ |
| 4 | RealVNC Virtual Keys Compose Layout | R2: Fn bar, modifiers, inverted-T arrows | 5 | 5 | ✓ |
| 5 | Tri-State Modifier State Machine | R2: Unlatched, Sticky (tap), Locked (long press)| 5 | 5 | ✓ |
| 6 | Hardware BMC Key Hold Timing | R2: 50ms hold, 25ms text pacing | 5 | 5 | ✓ |
| 7 | Windows VK & Scancode Translation | R2: Scancode mapping with isExtended | 5 | 5 | ✓ |
| 8 | Direct Touch Mode & Edge Coercion | R3: Tap-to-click, 2-finger scroll, zoom, edge | 5 | 5 | ✓ |
| 9 | Touchpad Mode & 3-Tier Acceleration | R3: Relative tracking, libinput curve, dampening| 5 | 5 | ✓ |
| 10 | Dedicated Mouse Buttons (L/M/R/Drag) | R3: Left, Right, Middle (BUTTON3), Drag lock | 5 | 5 | ✓ |
| 11 | Virtual Mouse Compose Overlay | R3: Floating FAB, pill bar, right scroll pillar | 5 | 5 | ✓ |

## Test Architecture
- Test Runner: JUnit 4 + Robolectric (`@RunWith(RobolectricTestRunner::class)`)
- Execution Command: `.\gradlew.bat testDebugUnitTest`
- Target modules: `:feature-mouse`, `:feature-session`, `:app`
- Test Output: Pass/fail assertions verifying correct state, emitted pointer events, and scancodes.

## Real-World Application Scenarios (Tier 4)
| # | Scenario | Features Exercised | Complexity |
|---|----------|--------------------|------------|
| 1 | Remote Text Editing in Windows Notepad (Typing + BMC Hold + Arrows + Modifiers) | F4, F5, F6, F7, F9, F10 | High |
| 2 | CAD / Graphic Manipulation with Middle Click Drag & Virtual Mouse Pill | F10, F11, F12, F13, F14 | High |
| 3 | One-Handed Mobile Navigation (Floating Opener + Toolbar Drawer + Zoom/Fit) | F1, F2, F3, F4 | Medium |
| 4 | Hybrid Input Switching (Touchpad Acceleration to Direct Touch with Edge Coercion)| F3, F8, F9, F11 | High |
| 5 | Full Remote Session Workflow (Connect -> Toolbar Toggle -> Fn Keys -> Typing -> Disconnect) | F1, F4, F5, F6, F9, F10 | High |

## Coverage Thresholds
- Tier 1: ≥5 per feature (5 × 11 = 55 tests)
- Tier 2: ≥5 per feature (5 × 11 = 55 tests)
- Tier 3: pairwise coverage of major feature interactions (≥11 tests)
- Tier 4: ≥5 realistic application scenarios (5 tests)
- Total E2E target: ≥126 tests
