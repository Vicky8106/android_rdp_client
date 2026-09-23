# Dispatch: Worker M1 — Build Infrastructure & `:core-rdp` Foundation

## Identity
- Archetype: teamwork_preview_worker
- Role: Core Infrastructure & RDP Engine Implementer
- Working Directory: C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\worker_m1
- Parent Conversation ID: 279701df-502c-4614-ba7b-407470f48f9a

## Objective
Implement Milestone 1: Build Infrastructure & `:core-rdp` Foundation.

## Mandatory Reading
- C:\Users\Administrator\teamwork_projects\android_rdp_client\ORIGINAL_REQUEST.md
- C:\Users\Administrator\teamwork_projects\android_rdp_client\PROJECT.md
- C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\explorer_m1_1\report.md
- C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\survey_spec_miner_2\report.md
- C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\survey_explorer_1\report.md

## Exclusive Write Ownership
You own:
- `local.properties`
- `gradle.properties`
- `settings.gradle.kts`
- `build.gradle.kts`
- `gradlew`, `gradlew.bat`, `gradle/wrapper/*`
- `core-rdp/**`
- Module starter skeletons for `:feature-mouse`, `:feature-session`, `:feature-telemetry`, `:app` (build.gradle.kts and AndroidManifest.xml)

## MANDATORY INTEGRITY WARNING
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A teamwork_preview_auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

## Tasks
1. **Provision Android SDK**:
   Follow Section 2.3 of `C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\explorer_m1_1\report.md`.
   Download cmdline-tools (~146 MB), extract to `C:\Android\Sdk\cmdline-tools\latest`, pre-seed licenses in `C:\Android\Sdk\licenses`, install `platform-tools`, `platforms;android-35`, `build-tools;35.0.0`. Clean up temp archives immediately.
2. **Copy Gradle Wrapper Assets**:
   Copy `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, and `gradle-wrapper.properties` from `C:\Users\Administrator\avnc` to `C:\Users\Administrator\teamwork_projects\android_rdp_client`.
3. **Write Root & Module Gradle Files**:
   Create `local.properties` (`sdk.dir=C\:\\Android\\Sdk`), `gradle.properties` (`org.gradle.java.home=C:\\Program Files\\Microsoft\\jdk-21.0.12.101-hotspot`), `settings.gradle.kts`, root `build.gradle.kts`, and starter skeletons for all 5 modules per Sections 4 and 5 of `explorer_m1_1\report.md`.
4. **Implement `:core-rdp`**:
   - `com.freerdp.freerdpcore.services.LibFreeRDP`: JNI wrapper class for upstream FreeRDP functions and callbacks.
   - `com.freerdp.core.engine.IRdpEngine` & `RdpEventListener`: Complete contract from `PROJECT.md`.
   - `com.freerdp.core.engine.NativeFreeRdpEngine`: Real JNI engine managing `AtomicLong` pointer, certificate verification, and buffer updates.
   - `com.freerdp.core.engine.MockRdpEngine`: Deterministic test double implementing `IRdpEngine` for headless unit and Robolectric tests.
   - `com.freerdp.core.protocol.RdpPointerFlags`: MS-RDPBCGR pointer flags bitmask.
   - `com.freerdp.core.protocol.DisplayControlHandler`: MS-RDPEDISP debounced resolution updates.
   - `com.freerdp.core.protocol.ClipboardHandler`: MS-RDPECLIP UTF-16LE text sync with echo suppression.
5. **Implement Unit Tests for `:core-rdp`**:
   In `core-rdp/src/test/java/com/freerdp/core/`:
   - `MockRdpEngineTest.kt`: Tests connect, disconnect, pointer event dispatch, resolution updates, and listeners.
   - `RdpPointerFlagsTest.kt`: Validates all bitmask flags against MS-RDPBCGR specs.
   - `DisplayControlHandlerTest.kt`: Tests debouncing and layout calculation.
   - `ClipboardHandlerTest.kt`: Tests text encoding/decoding and echo suppression.
6. **Verify Build and Tests**:
   Run:
   `set JAVA_HOME=C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot`
   `.\gradlew assembleDebug`
   `.\gradlew :core-rdp:testDebugUnitTest`
   Ensure both commands succeed with 0 errors and 100% test pass rate.
7. **Write Handoff Report**:
   Save to `C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\worker_m1\handoff.md` and send_message to parent.
