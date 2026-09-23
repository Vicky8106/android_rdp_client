# Survey Task Dispatch — Explorer 3: Architecture, Security, Telemetry & Testing Strategy

## Identity
- Archetype: teamwork_preview_explorer
- Role: Architecture & Testing Strategist
- Working Directory: C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\survey_explorer_3
- Parent Conversation ID: 279701df-502c-4614-ba7b-407470f48f9a

## Objective
Analyze requirements R3 and R4 and design the holistic architecture and testing strategy:
1. R3: Material 3 UI, connection profile manager (CRUD), secure Android Keystore / EncryptedSharedPreferences storage, quick-action toolbar, mobile modifier keys (Ctrl, Alt, Esc, Win, Function keys).
2. R4: Auto-reconnect state machine (network drop, pause/resume, clean session teardown/reconnect), low-latency socket buffering, background thread isolation (render loop, frame pacing), bandwidth/battery performance modes, dynamic orientation/multi-window, real-time diagnostic telemetry (FPS, latency, state).
3. Android project architecture (modules, packages, separation of concerns, dependency injection or clean service locators).
4. Testing strategy: Unit tests, Robolectric test architecture, mock FreeRDP test double for deterministic testing of session lifecycle, and E2E opaque-box test runner setup.

## Scope Boundaries
- Do NOT implement application source code.
- Analyze requirements and synthesize architectural blueprint and testing methodology.
- Output report to `C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\survey_explorer_3\report.md`.

## Mandatory Reading
- C:\Users\Administrator\teamwork_projects\android_rdp_client\ORIGINAL_REQUEST.md

## 2026-09-22T15:10:41Z
You are survey_explorer_3 (Architecture & Testing Strategist).
Your working directory is C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\survey_explorer_3.
Your dispatch details are at C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\survey_explorer_3\DISPATCH.md.

MANDATORY READING: Read C:\Users\Administrator\teamwork_projects\android_rdp_client\ORIGINAL_REQUEST.md completely before starting.

Task:
1. Analyze R3 (Material 3 UI, profile manager CRUD, Keystore / EncryptedSharedPreferences encryption, quick-action toolbar, mobile modifier keys) and R4 (auto-reconnect state machine, low-latency socket buffering, background thread isolation, performance modes, real-time diagnostic telemetry).
2. Propose a clean modular Android architecture and package breakdown.
3. Design the testing architecture: unit tests, Robolectric tests for Android Keystore/EncryptedSharedPreferences, gesture simulations, session state transitions, and opaque-box E2E test harness.
4. Write your findings to C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\survey_explorer_3\report.md and write a handoff.md.
5. Notify parent via send_message when complete.
