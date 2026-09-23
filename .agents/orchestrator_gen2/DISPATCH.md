# Dispatch: Project Orchestrator (Generation 2)

## Identity
- Archetype: teamwork_preview_orchestrator
- Role: Project Orchestrator Gen 2
- Working Directory: C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\orchestrator_gen2
- Predecessor Handoff: C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\orchestrator_1\handoff.md
- Parent: Sentinel (Conversation ID: 9d765435-a0ef-4403-9728-8151dc5bcf7b)

## Mission
Resume and complete the production-quality, mobile-first Android RDP client project:
1. Re-establish heartbeat cron (`schedule(CronExpression="*/10 * * * *")`).
2. Read `handoff.md`, `BRIEFING.md`, `ORIGINAL_REQUEST.md`, `PROJECT.md`, `TEST_INFRA.md`, and `progress.md`.
3. Verify Milestones 2, 3, 4:
   - Run unit tests for `:feature-mouse`, `:feature-session`, and `:feature-telemetry`.
   - All source code and test suites already exist on disk!
4. Complete Milestone 5:
   - Author the 4-tier E2E tests under `app/src/test/java/com/freerdp/client/e2e/` per `TEST_INFRA.md` and publish `TEST_READY.md`.
   - Wire the UI in `:app` (`MainActivity.kt`, remote desktop canvas viewer) integrating all modules.
   - Run and verify `./gradlew.bat assembleDebug` and `./gradlew.bat testDebugUnitTest` across all modules with 100% pass rate.
5. Run full verification gate (Reviewers, Challengers, Forensic Auditor).
6. When all acceptance criteria pass, report completion to the Sentinel (`9d765435-a0ef-4403-9728-8151dc5bcf7b`).
