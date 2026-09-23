# GATES.md - test_writer_e2e

## Gate 1: Test Infrastructure Documentation
- [x] Create `TEST_INFRA.md` at project root documenting testing methodology, 4-tier architecture, feature inventory matrix (R1-R4), scenario list, and runner commands (`./gradlew testDebugUnitTest`).

## Gate 2: Tier 1 Feature Coverage Test Suite
- [x] Create `app/src/test/java/com/freerdp/client/e2e/Tier1FeatureCoverageTest.kt` with >= 5 tests per core feature (R1, R2, R3, R4) testing happy paths and primary behaviors against interface contracts.

## Gate 3: Tier 2 Boundary & Corner Case Test Suite
- [x] Create `app/src/test/java/com/freerdp/client/e2e/Tier2BoundaryCornerTest.kt` with >= 5 boundary tests per feature (empty inputs, zero dimensions, max buffers, rapid orientation toggles, network drop during connect, boundary coordinate clamping).

## Gate 4: Tier 3 Cross-Feature Combination Test Suite
- [x] Create `app/src/test/java/com/freerdp/client/e2e/Tier3CrossFeatureTest.kt` with pairwise combinations (gesture zoom + mouse click-drag, keystore save + profile connect, reconnect backoff + orientation resize, modifier keys + mouse operations, etc.).

## Gate 5: Tier 4 Real-World Scenario Test Suite
- [x] Create `app/src/test/java/com/freerdp/client/e2e/Tier4RealWorldScenariosTest.kt` with >= 5 realistic end-to-end workflows (workstation login, presentation navigation, remote coding session with modifier keys, intermittent cell connection recovery, multi-window/foldable dynamic resize).

## Gate 6: Test Suite Readiness Declaration
- [x] Publish `TEST_READY.md` at project root with comprehensive coverage summary table and feature checklist across all 4 tiers.

## Gate 7: Self-Verification and Handoff
- [x] Verify all files exist, are well-structured, syntax-valid, self-contained, strictly adhere to `PROJECT.md § Interface Contracts`, write `handoff.md`, and notify parent.
