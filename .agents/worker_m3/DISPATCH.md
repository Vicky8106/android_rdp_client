# Dispatch: Worker M3 — Mobile Productivity, Profiles, Keystore Security & Modifiers

## Identity
- Archetype: teamwork_preview_worker
- Role: Productivity, Security & Profiles Implementer
- Working Directory: C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\worker_m3
- Parent Conversation ID: 279701df-502c-4614-ba7b-407470f48f9a

## Objective
Implement Milestone 3: Mobile Productivity, Profile CRUD, Keystore Security Vault, Quick-Action Toolbar, and Mobile Modifier Keys in module `:feature-session`.

## Exclusive Write Ownership
- `feature-session/**`

## Mandatory Reading
- C:\Users\Administrator\teamwork_projects\android_rdp_client\ORIGINAL_REQUEST.md
- C:\Users\Administrator\teamwork_projects\android_rdp_client\PROJECT.md
- C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\survey_explorer_3\report.md

## MANDATORY INTEGRITY WARNING
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A teamwork_preview_auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

## Requirements & Scope
1. **Connection Profile Manager (CRUD)**:
   - `ProfileRepository`: Create, Read, Update, Delete server configurations (`RdpProfile`).
   - Atomic file storage using JSON serialization (writes to temp file then atomic rename).
   - One-tap profile launching: binds profile configuration to `RdpConnectionConfig`.
2. **Android Keystore & EncryptedSharedPreferences Vault**:
   - `CredentialStore` contract from `PROJECT.md`:
     `fun saveSecret(profileId: String, secret: CharArray)`
     `fun getSecret(profileId: String): CharArray?`
     `fun deleteSecret(profileId: String)`
     `fun clearAll()`
   - Jetpack Security `MasterKey` (`KeyGenParameterSpec` AES-256-GCM) + `EncryptedSharedPreferences`.
   - Security hygiene: uses `CharArray` for secrets and zeroes memory (`Arrays.fill(secret, '\u0000')`) post-use.
3. **Collapsible Quick-Action Toolbar**:
   - `QuickActionToolbarFSM`: States: Hidden, Expanded.
   - 4-second inactivity auto-collapse timer (resets on user touch).
   - Quick actions: Disconnect, Toggle Keyboard, Toggle Mouse Overlay, Switch Monitor/Resolution, Telemetry HUD Toggle.
4. **Mobile Modifier Keys Helper Bar**:
   - `ModifierStateMachine`: Dedicated modifier keys (Ctrl, Alt, Win, Esc, F1-F12, Tab, Del).
   - 3-State Latching Machine:
     - `INACTIVE`: Modifier is not active.
     - `LATCHED` (Single Tap): Active for the immediately next keystroke, then auto-releases to `INACTIVE`.
     - `LOCKED` (Double Tap): Held active until tapped again.
   - Visual state indicators for latched and locked keys.
5. **Windows Scancode Set 1 Translation**:
   - `ScancodeTranslator`: Maps Android key events and modifier combinations to Windows PC Scancode Set 1 bytes.
   - Predefined desktop macro shortcuts (Ctrl+Alt+Del, Alt+Tab, Alt+F4, Win+D, Win+R).
6. **Automated Unit & Robolectric Tests**:
   Under `feature-session/src/test/java/com/freerdp/feature/session/`:
   - `ProfileRepositoryTest.kt`: Tests full CRUD lifecycle, corrupt file recovery, atomic write safety.
   - `KeystoreCredentialStoreTest.kt`: Robolectric test verifying AES-256-GCM encryption/decryption, deletion, and `CharArray` memory zeroing.
   - `QuickActionToolbarTest.kt`: Tests toolbar expand, collapse, and 4-second auto-collapse timer.
   - `ModifierStateMachineTest.kt`: Tests 3-state latching (single tap -> latched, consumption -> inactive, double tap -> locked, unlock -> inactive).
   - `ScancodeTranslatorTest.kt`: Tests Scancode Set 1 mappings and macro combinations (Ctrl+Alt+Del).
7. **Verification**:
   Execute:
   `set JAVA_HOME=C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot`
   `.\gradlew.bat :feature-session:testDebugUnitTest`
   Ensure 100% test pass rate.
8. Deliver `handoff.md` and notify parent.
