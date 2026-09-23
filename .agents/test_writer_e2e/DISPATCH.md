# Dispatch: E2E Test Suite Creation (Tiers 1-4)

## Identity
- Archetype: teamwork_preview_test_writer
- Role: E2E Test Suite Author
- Working Directory: C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\test_writer_e2e
- Parent Conversation ID: 279701df-502c-4614-ba7b-407470f48f9a

## Mission
Design and create the comprehensive, opaque-box, requirement-driven E2E test suite for the Android FreeRDP client.

## Deliverables
1. `C:\Users\Administrator\teamwork_projects\android_rdp_client\TEST_INFRA.md` at project root:
   - Follow the template in orchestrator instructions.
   - Document test philosophy, 4-tier test architecture, feature inventory matrix, scenario list, and runner instructions (`./gradlew testDebugUnitTest`).
2. Test files under `app/src/test/java/com/freerdp/client/e2e/`:
   - `Tier1FeatureCoverageTest.kt`: >= 5 tests per feature for all core features (R1-R4).
   - `Tier2BoundaryCornerTest.kt`: >= 5 boundary tests per feature (empty inputs, zero dimensions, max buffers, rapid orientation toggles, network drop during connect).
   - `Tier3CrossFeatureTest.kt`: Pairwise feature combinations (e.g. gesture zoom + mouse click-drag, keystore save + profile connect, reconnect backoff + orientation resize).
   - `Tier4RealWorldScenariosTest.kt`: >= 5 end-to-end realistic application scenarios (workstation login, presentation navigation, remote coding session with modifier keys, intermittent cell connection recovery).
3. `C:\Users\Administrator\teamwork_projects\android_rdp_client\TEST_READY.md` at project root:
   - Coverage summary table (counts for Tiers 1-4).
   - Feature checklist showing coverage across all tiers.
4. Report completion and deliver `handoff.md` to parent.

## Mandatory Reading
- C:\Users\Administrator\teamwork_projects\android_rdp_client\ORIGINAL_REQUEST.md
- C:\Users\Administrator\teamwork_projects\android_rdp_client\PROJECT.md
- C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\survey_spec_miner_2\report.md
- C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\survey_spec_miner_2\report.md
- C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\survey_explorer_3\report.md

## 2026-09-22T15:20:48Z
You are test_writer_e2e (E2E Test Suite Author).
Working Directory: C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\test_writer_e2e.
Dispatch details: C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\test_writer_e2e\DISPATCH.md.

MANDATORY READING: Read C:\Users\Administrator\teamwork_projects\android_rdp_client\ORIGINAL_REQUEST.md completely before starting.
Also read C:\Users\Administrator\teamwork_projects\android_rdp_client\PROJECT.md.

Task:
1. Create C:\Users\Administrator\teamwork_projects\android_rdp_client\TEST_INFRA.md following the orchestrator instructions template (methodology, feature inventory matrix, runner commands).
2. Author comprehensive opaque-box E2E test files under app/src/test/java/com/freerdp/client/e2e/:
   - Tier1FeatureCoverageTest.kt: >= 5 tests per feature for all core features (R1-R4).
   - Tier2BoundaryCornerTest.kt: >= 5 boundary tests per feature.
   - Tier3CrossFeatureTest.kt: Pairwise combinations.
   - Tier4RealWorldScenariosTest.kt: >= 5 realistic workflows.
3. Publish C:\Users\Administrator\teamwork_projects\android_rdp_client\TEST_READY.md at project root with full coverage matrix.
4. Write handoff.md and send_message to parent when complete.

