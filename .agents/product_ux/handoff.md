# Handoff — `product_ux` (product management + UX documentation), restart wave-1

**Date**: 2026-09-22T19:50Z (machine-local 2026-09-23 ~01:50, UTC+05:30)
**Status**: COMPLETE — all 8 deliverables written. No source/build files touched.

---

## 1. Files written (write ownership respected: root `*.md`, `docs/**`, `.agents/orchestrator_1/progress.md`, `.agents/product_ux/**`)

| File | Action | Contents |
|---|---|---|
| `README.md` | **new** | Product pitch, module map with honest per-module status, exact PowerShell build/test commands (`$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot"`), status snapshot table, E2E suite location + per-tier run commands, docs index |
| `PROJECT.md` | updated (4 targeted edits) | Architecture diagram corrected (Compose UI → `:app`; `:feature-session` = logic-only; dependency rule note); milestone statuses M1 DONE / M2–M4 "code-complete, verification in progress" (M4 called out FAILING) / M5 IN PROGRESS + status legend; feature-inventory row 15 UI attribution → `:app`/M5; code-layout section corrected (`:app` under construction, E2E dir not yet present). **§ Interface Contracts left byte-for-byte untouched** (edits anchored outside line ~101–190 region) |
| `ORIGINAL_REQUEST.md` | updated (acceptance section only) | Evidence-policy preamble; per-criterion inline evidence annotations; **AC-3/4/5/6/7 ticked with cited passing test names**; AC-1/2/8/9/10 left UNCHECKED with explicit reasons; added evidence-ledger table (criterion → module → planned TEST_INFRA tests → status) |
| `docs/PRODUCT.md` | **new** | Problem statement; 3 personas (on-call mobile admin/dev, presenter, traveler); 7-stage user journey (first run → add profile → connect → work → drop → recover → disconnect) incl. native-lib-missing branch; 6 UX principles (one-handed reach, ≤100 ms latency feedback, never obscure desktop, graceful degradation, secure-by-default credentials, predictable input semantics); must-have v1.0 vs backlog (multi-monitor, file transfer, RD Gateway, USB redirect, gamepad, mouse-mode polish, …); release plan v1.0 → v1.1 → v1.2+ |
| `docs/ACCEPTANCE.md` | **new** | Full traceability matrix AC-1..AC-10 → R# → module/contract → unit evidence found → planned Tier tests → status (`EVIDENCE-GREEN`/`BLOCKED-FAILING`/`PARTIAL`/`OPEN`); evidence-snapshot appendix with artifact paths + mtimes; rules for the final acceptance agent (fresh-artifact requirement, downgrade/un-tick rule) |
| `docs/UX_SPEC.md` | **new** | Screen-by-screen spec: app shell/nav, profile list (loading/empty/error/populated, one-tap connect), profile editor (field validation table, secure password field), settings (4 presets, HUD, touchpad, haptics, theme), session screen (phase model, canvas gesture map, floating overlay with edge-snap persistence, quick toolbar 4 s auto-collapse, modifier bar 3-state table, HUD FPS/RTT/jitter, reconnect chip, cert trust dialog, connecting/failure/retry), accessibility (48 dp, contentDescriptions, TalkBack), haptics table, dark/light + native-lib degradation banner |
| `.agents/orchestrator_1/progress.md` | rewritten | New timestamp, evidence-based phase/milestone status, evidence snapshot table, wave-1 active agents (`app_product`, `latency_perf`, `native_core`, `session_logic`, `e2e_tests`, `product_ux`), wave-2 plan (`reviewer`, adversarial QA, `integrator`, `acceptance`), M2-verifier charter gap flagged |
| `.agents/product_ux/handoff.md` | **new (this file)** | — |

## 2. Key decisions & rationale

1. **Evidence-based ticking policy for `ORIGINAL_REQUEST.md`.** Ticked ONLY AC-3, AC-4, AC-5,
   AC-6, AC-7 — each backed by a named, machine-generated `TEST-*.xml` result whose mtime
   (2026-09-23 00:43 local) post-dates the last source edit (12:42:28 local), with test names
   mapping 1:1 to the criterion text; `MouseControllerTest` and `ProfileRepositoryTest` spot-read
   to confirm substantive assertions (not tautologies). AC-7 ticked **with** a written caveat
   (Robolectric exercises the AES-GCM fallback path; AndroidKeyStore untestable on JVM).
2. **Left UNCHECKED**: AC-1 (M1-gate APK evidence exists, but latest combined run
   `baseline_build.log` = BUILD FAILED before `assembleDebug` and `:app` rewrite in flight),
   AC-2 (`.so` not packaged), AC-8/AC-9/AC-10 (failing evidence — named failing tests recorded).
3. **M4 reported as FAILING, not merely "unverified":** the brief said verification was in
   progress; the verification run finished at 19:49Z with 8/33 telemetry failures. Docs reflect
   the run's actual result, with names listed, and note fixes may already be in flight —
   re-check `feature-telemetry/build/test-results` before acting.
4. **Architecture truth:** all Compose Material 3 UI assigned to `:app`;
   `:feature-session`/`:feature-telemetry` documented as logic-only; inventory row 15 milestone
   changed M3 → M5 for consistency with the new M3 scope.
5. **Interface Contracts immutability:** zero edits inside `## Interface Contracts` — all
   `PROJECT.md` edits used anchored replacements outside that section.
6. **Status vocabulary:** "code-complete, verification in progress" strictly = code+tests exist
   AND latest on-disk run green AND no independent reviewer re-run yet. Failing runs are called
   "LATEST RUN FAILING" with failure names, never softened.

## 3. Status ledger snapshot (2026-09-22T19:50Z)

| Gate | Status | Evidence |
|---|---|---|
| M1 `:core-rdp` | **DONE — passed** | 50/50 (00:37 local); auditor verdict CLEAN; APK inspected |
| M2 `:feature-mouse` | code-complete, verification in progress | 32/32 green (00:43 local); wave-2 re-run pending |
| M3 `:feature-session` | code-complete, verification in progress | 41/41 green (00:43 local); wave-2 re-run pending |
| M4 `:feature-telemetry` | code-complete, verification in progress — **LATEST RUN FAILING** | 25/33; 8 failures at 01:19 local: `AutoReconnectManagerTest`×7 (`testNetworkLostTransitionsToWaitingForNetwork`, `testSessionDroppedTriggersReconnectingState`, `testUserPauseAndResumeLifecycle`, `testCancelReconnectResetsToIdle`, `testFastPathNetworkRecoveryBypassesDelay`, `testFiveStepTeardownExecutedInOrder`, `testExhaustedRetriesTransitionsToFailed`), `LowLatencySocketConfigTest.testSocketConfigurationParameters`×1 |
| M5 Integration | **IN PROGRESS** | `:app` UI under construction; E2E dir absent; `.so` packaging investigated; final gates not run |
| Acceptance AC-1..AC-10 | 5 provisionally ticked w/ evidence · 5 open/blocked | see `docs/ACCEPTANCE.md` |

## 4. Warnings / gaps for successors

- **No dedicated M2 verifier** in the announced wave-1 roster — wave-2 `reviewer` must re-run
  `:feature-mouse` with `--rerun-tasks` (or orchestrator assigns one).
- Do not trust the tick in `ORIGINAL_REQUEST.md` without wave-2 re-verification; rules for
  revocation are in `docs/ACCEPTANCE.md` §3.
- Telemetry failures may be fixed while you read this — always re-read
  `feature-telemetry/build/test-results/testDebugUnitTest/*.xml` mtimes before quoting status.
- `TEST_INFRA.md` coverage-matrix "Covered" columns are **specification**, not current reality;
  the Tier test classes do not exist yet.
- Reproduce any claim with:
  ```powershell
  $env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot"
  .\gradlew.bat testDebugUnitTest --rerun-tasks
  ```
