# BRIEFING — 2026-09-24T11:45:00Z

## Mission
Implement Milestone 2: RealVNC-Style Virtual Keys Bar & Soft Keyboard Timing for Android RDP Client.

## 🔒 My Identity
- Archetype: worker
- Roles: implementer, qa, specialist
- Working directory: C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\teamwork\worker_m2
- Original parent: f0fc1f73-b43f-468a-ab50-e5ec45aedb66
- Milestone: Milestone 2 (RealVNC-Style Virtual Keys Bar & Soft Keyboard Timing)

## 🔒 Key Constraints
- DO NOT CHEAT: Genuine implementations only, no hardcoded test outputs or facade implementations.
- File ownership scope:
  - feature-session/src/main/java/com/freerdp/feature/session/keyboard/ScancodeTranslator.kt
  - feature-session/src/main/java/com/freerdp/feature/session/keyboard/KeyboardTimingManager.kt
  - feature-session/src/main/java/com/freerdp/feature/session/modifier/ModifierStateMachine.kt
  - app/src/main/java/com/freerdp/client/ui/session/VirtualKeysCompose.kt
  - feature-session/src/test/java/com/freerdp/feature/session/keyboard/ScancodeTranslatorTest.kt
  - feature-session/src/test/java/com/freerdp/feature/session/keyboard/KeyboardTimingManagerTest.kt
  - app/src/test/java/com/freerdp/client/ui/session/VirtualKeysComposeTest.kt
- 100% test pass rate via `.\gradlew.bat :feature-session:testDebugUnitTest` and `.\gradlew.bat :app:testDebugUnitTest`.
- Output handoff.md in worker_m2 folder and send message to parent.

## Current Parent
- Conversation ID: f0fc1f73-b43f-468a-ab50-e5ec45aedb66
- Updated: not yet

## Task Summary
- **What to build**:
  1. VirtualKeysCompose.kt: Compose virtual keys bar with RealVNC ergonomics (M3 Surface, expandable Fn F1-F12 bar, tri-state sticky modifiers Ctrl/Alt/Shift/Super, desktop keys, inverted-T arrow cluster, scroll buttons with hold-to-repeat).
  2. ModifierStateMachine.kt: State machine handling Unlatched, Latched/Sticky, Locked tri-state logic with auto-unlatching on non-modifier key release.
  3. ScancodeTranslator.kt: Mapping Windows VK codes and Scancode Set 1 with extended bit (0x0100) preserved for RDP protocol.
  4. KeyboardTimingManager.kt: Coroutine actor queue (Channel<KeyCommand>), BMC key-press hold timing (50ms key-down hold before release for Enter, Backspace, Space, Tab, special keys), text streaming pacing (25ms between keystrokes), integrating with IRdpEngine.
  5. Unit tests for ScancodeTranslator, KeyboardTimingManager, and VirtualKeysCompose (and ModifierStateMachine).
- **Success criteria**: All requirements met, code compiles and all unit tests pass with genuine logic.
- **Interface contracts**: PROJECT.md, survey_avnc_spec.md, survey_protocol_bridge.md

## Change Tracker
- **Files modified**: None yet
- **Build status**: Pending initial run
- **Pending issues**: None

## Quality Status
- **Build/test result**: Pending
- **Lint status**: Clean
- **Tests added/modified**: Pending

## Loaded Skills
- **Source**: C:\Users\Administrator\.agents\skills\unlazy\SKILL.md
- **Local copy**: C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\teamwork\worker_m2\unlazy_SKILL.md
- **Core methodology**: Acceptance gates before execution, decomposition, running approved checks, re-verifying evidence before reporting.

## Key Decisions Made
- [Initial] Read all spec documents, survey docs, and AVNC reference files before planning.

## Artifact Index
- DISPATCH.md — Assignment instructions
- progress.md — Liveness heartbeat and milestone tracker
