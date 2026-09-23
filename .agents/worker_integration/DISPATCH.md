# Dispatch: Integration & Final E2E Worker

## Identity
- Archetype: teamwork_preview_worker
- Role: Integration & Full Test Suite Implementer
- Working Directory: C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\worker_integration
- Parent Conversation ID: 279701df-502c-4614-ba7b-407470f48f9a

## Objective
Finalize Milestones 2, 3, 4, and implement Milestone 5:
1. Verify and test `:feature-mouse`, `:feature-session`, and `:feature-telemetry`. Fix any compilation or test issues so all module unit tests pass 100%.
2. Complete `:app` integration (`MainActivity.kt`, session viewer UI, `AppContainer`) cleanly binding all modules together.
3. Author the 4-tier E2E test suite in `app/src/test/java/com/freerdp/client/e2e/` strictly per `TEST_INFRA.md`:
   - `Tier1FeatureCoverageTest.kt` (>= 5 tests per feature for R1, R2, R3, R4)
   - `Tier2BoundaryCornerTest.kt` (>= 5 boundary tests per feature)
   - `Tier3CrossFeatureTest.kt` (pairwise cross-feature combinations)
   - `Tier4RealWorldScenariosTest.kt` (>= 5 realistic end-to-end user workflows)
4. Publish `TEST_READY.md` at project root.
5. Run full test suite and assembly:
   `set JAVA_HOME=C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot`
   `.\gradlew.bat assembleDebug`
   `.\gradlew.bat testDebugUnitTest`
   Verify 100% test pass rate across all modules and valid debug APK output.
6. Write `handoff.md` and notify parent.

## Mandatory Reading
- C:\Users\Administrator\teamwork_projects\android_rdp_client\ORIGINAL_REQUEST.md
- C:\Users\Administrator\teamwork_projects\android_rdp_client\PROJECT.md
- C:\Users\Administrator\teamwork_projects\android_rdp_client\TEST_INFRA.md
- C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\orchestrator_1\handoff.md

## MANDATORY INTEGRITY WARNING
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A teamwork_preview_auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

## 2026-09-22T23:39:22Z
You are worker_integration (Integration & E2E Worker).
Working Directory: C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\worker_integration.
Dispatch details: C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\worker_integration\DISPATCH.md.

Task:
1. Verify and run unit tests for :feature-mouse, :feature-session, and :feature-telemetry (all source and test files already exist on disk from prior workers). Fix any syntax, import, or test assertion issues so all three modules pass:
   $env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot"
   .\gradlew.bat :feature-mouse:testDebugUnitTest :feature-session:testDebugUnitTest :feature-telemetry:testDebugUnitTest
2. Complete :app integration: Wire MainActivity.kt and remote session viewer UI in module :app so it integrates MouseController, FloatingMouseOverlayView, ModifierStateMachine, ProfileRepository, KeystoreCredentialStore, IRdpEngine, AutoReconnectManager, and TelemetryCollector.
3. Author the 4-tier E2E test suite under app/src/test/java/com/freerdp/client/e2e/ strictly per TEST_INFRA.md:
   - Tier1FeatureCoverageTest.kt (>= 5 tests per requirement category R1-R4)
   - Tier2BoundaryCornerTest.kt (>= 5 boundary tests per category)
   - Tier3CrossFeatureTest.kt (pairwise feature interactions)
   - Tier4RealWorldScenariosTest.kt (>= 5 realistic end-to-end workflows)
4. Publish C:\Users\Administrator\teamwork_projects\android_rdp_client\TEST_READY.md at project root with complete coverage table.
5. Run full build and test verification:
   $env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot"
   .\gradlew.bat assembleDebug
   .\gradlew.bat testDebugUnitTest
   Ensure 100% of tests pass across all modules and valid debug APK is produced.
6. Write handoff.md in your working directory and notify parent via send_message when complete.
