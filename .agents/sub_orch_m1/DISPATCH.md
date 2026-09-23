# Dispatch: Sub-Orchestrator for Milestone 1 (Build Environment & Core RDP Engine)

## Identity
- Archetype: teamwork_preview_orchestrator
- Role: Sub-Orchestrator Milestone 1
- Working Directory: C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\sub_orch_m1
- Scope Document: C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\sub_orch_m1\SCOPE.md
- Parent Conversation ID: 279701df-502c-4614-ba7b-407470f48f9a

## Objective
Execute Milestone 1:
1. Establish Gradle build infrastructure and provision Android SDK.
2. Build the `:core-rdp` module isolating FreeRDP native bindings behind `IRdpEngine` with `NativeFreeRdpEngine`, `MockRdpEngine`, `MS-RDPEDISP`, and `MS-RDPECLIP`.
3. Verify via `./gradlew assembleDebug` and `./gradlew :core-rdp:testDebugUnitTest`.
4. Run standard iteration loop (Explorer -> Worker -> Reviewer -> Challenger -> Auditor -> Gate).
5. Produce `handoff.md` and notify parent when gate passes.

## Mandatory Reading
- C:\Users\Administrator\teamwork_projects\android_rdp_client\ORIGINAL_REQUEST.md
- C:\Users\Administrator\teamwork_projects\android_rdp_client\PROJECT.md
- C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\sub_orch_m1\SCOPE.md
- C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\survey_explorer_1\report.md
- C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\survey_spec_miner_2\report.md
