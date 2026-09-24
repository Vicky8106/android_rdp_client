## 2026-09-24T11:44:03Z

Your working directory is: C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\teamwork\worker_m2
Your identity is: worker_m2 (Worker)
You MUST read C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\teamwork\ORIGINAL_REQUEST.md before starting work.

DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A teamwork_preview_auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

Objective:
Implement Milestone 2: RealVNC-Style Virtual Keys Bar & Soft Keyboard Timing.

Specification Documents to read:
- C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\teamwork\orchestrator_1\PROJECT.md
- C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\teamwork\teamwork_preview_spec_miner_survey_1\survey_avnc_spec.md
- C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\teamwork\teamwork_preview_spec_miner_survey_3\survey_protocol_bridge.md

Reference implementation in AVNC:
- C:\Users\Administrator\avnc\app\src\main\java\com\vncandroid\free\ui\vnc\VirtualKeysCompose.kt
- C:\Users\Administrator\avnc\app\src\main\java\com\vncandroid\free\ui\vnc\VirtualKeys.kt
- C:\Users\Administrator\avnc\app\src\main\java\com\vncandroid\free\ui\vnc\input\KeyHandler.kt
- C:\Users\Administrator\avnc\app\src\main\java\com\vncandroid\free\ui\vnc\util\Keyboard.kt

Files you OWN exclusively:
- feature-session/src/main/java/com/freerdp/feature/session/keyboard/ScancodeTranslator.kt
- feature-session/src/main/java/com/freerdp/feature/session/keyboard/KeyboardTimingManager.kt
- feature-session/src/main/java/com/freerdp/feature/session/modifier/ModifierStateMachine.kt
- app/src/main/java/com/freerdp/client/ui/session/VirtualKeysCompose.kt
- feature-session/src/test/java/com/freerdp/feature/session/keyboard/ScancodeTranslatorTest.kt
- feature-session/src/test/java/com/freerdp/feature/session/keyboard/KeyboardTimingManagerTest.kt
- app/src/test/java/com/freerdp/client/ui/session/VirtualKeysComposeTest.kt

Implementation Requirements:
1. VirtualKeysCompose.kt:
   - Port the Compose virtual keys system from avnc with full RealVNC ergonomics.
   - Material 3 Surface (alpha = 0.80f, rounded top corners 16dp, tonal elevation 8dp).
   - Collapsible/expandable Fn strip (F1–F12) animated with slide/fade.
   - Tri-state sticky modifiers: Ctrl, Alt, Shift, Super/Windows (Unlatched, Latched/Sticky on tap, Locked on long press; auto-unlatch on non-modifier release).
   - Desktop keys: Esc, Tab, Delete, Caps Lock, Home, End, PgUp, PgDn.
   - Inverted-T arrow cluster (Up centered above Down, flanked by Left and Right).
   - Enlarged scroll buttons with hold-to-repeat (200ms initial delay, 50ms interval).
2. KeyboardTimingManager.kt & ScancodeTranslator.kt:
   - Comprehensive mapping to Windows VK codes and Scancode Set 1 with extended bit (0x0100) preserved.
   - Implement BMC key-press hold timing: 50ms key-down hold duration before release for Enter, Backspace, Space, Tab, and special keys.
   - Text streaming pacing: 25ms delay between keystrokes.
   - Non-blocking coroutine actor queue (Channel<KeyCommand>) so UI never blocks while BMC delay executes.
   - Ensure clean integration with IRdpEngine.sendKeyEvent / sendUnicodeKeyEvent.
3. Tests & Verification:
   - Run tests via .\gradlew.bat :feature-session:testDebugUnitTest and .\gradlew.bat :app:testDebugUnitTest.
   - Verify 100% test pass rate.

Output Requirements:
- Write handoff.md in your working directory C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\teamwork\worker_m2\handoff.md with all implementation details, test results, and layout verification.
- Send a message to parent when done.
