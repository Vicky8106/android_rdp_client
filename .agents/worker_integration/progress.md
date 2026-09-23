# Progress — worker_integration

Last visited: 2026-09-22T23:41:00Z

## Status
- [ ] Task 1: Verify and run unit tests for :feature-mouse, :feature-session, and :feature-telemetry. Fix any syntax, import, or test assertion issues.
- [ ] Task 2: Complete :app integration: Wire MainActivity.kt and remote session viewer UI in module :app so it integrates MouseController, FloatingMouseOverlayView, ModifierStateMachine, ProfileRepository, KeystoreCredentialStore, IRdpEngine, AutoReconnectManager, and TelemetryCollector.
- [ ] Task 3: Author the 4-tier E2E test suite under app/src/test/java/com/freerdp/client/e2e/ strictly per TEST_INFRA.md:
  - [ ] Tier1FeatureCoverageTest.kt
  - [ ] Tier2BoundaryCornerTest.kt
  - [ ] Tier3CrossFeatureTest.kt
  - [ ] Tier4RealWorldScenariosTest.kt
- [ ] Task 4: Publish TEST_READY.md at project root with complete coverage table.
- [ ] Task 5: Run full build and test verification (assembleDebug, testDebugUnitTest) with 100% pass rate.
- [ ] Task 6: Write handoff.md and notify parent via send_message.
