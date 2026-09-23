# integrator_w2 Handoff — Final Integration Gate (t5), Android FreeRDP Mobile Client, wave 2

**Agent:** `integrator_w2` (only source-editing rights this wave)
**Date:** 2026-09-23 (local, UTC+05:30)
**Status: GATE PASS** — two consecutive pristine-pinned full gate runs (E & F) green:
**487 tests / 0 failures / 0 errors / 0 skipped across all 5 modules, 44 suites**, plus a definitive
from-scratch `assembleDebug --rerun-tasks` producing the final APK under hash-verified-pristine sources.
**Zero source edits were needed** (§5); all cheap-gap audits clean (§6); doc edits deferred to
acceptance are listed exactly (§8).

---

## 1. Headline result

| Gate item | Result |
|---|---|
| 1. Full `testDebugUnitTest`, BUILD SUCCESSFUL, 0 failures | ✅ **487/487** (project total; expect ~480+ ✅) |
| 2. `assembleDebug` BUILD SUCCESSFUL, APK exists & newer than run start | ✅ `app\build\outputs\apk\debug\app-debug.apk`, 17,188,899 B, 2026-09-23 04:07:39.081 |
| 3. Full gate run TWICE (tests actually re-executed) | ✅ Runs **E** (04:02:38–04:03:22) and **F** (04:04:55–04:05:42), both `--rerun-tasks` (145/145 tasks executed each), byte-identical tables |
| 4. Failures root-caused, no weakened tests | ✅ No real failures ever reproduced on pristine sources; all one-off failures root-caused to concurrent-agent interference (§7); no test/assertion touched |
| 5. Cheap-gap audit | ✅ gradle.properties sane; 0 TODO/FIXME; 0 debug prints in main sources; stale docs listed for acceptance (§8) |
| 6. APK sanity | ✅ size > 0, fresh mtime, merged manifest contains `android.permission.INTERNET` AND `com.freerdp.client.App` |

Per-module totals (identical in run E, run F, and the final on-disk snapshot):

| Module | Suites | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|---:|
| `:app` | 15 | **160** (74 e2e + 86 unit) | 0 | 0 | 0 |
| `:core-rdp` | 10 | **95** | 0 | 0 | 0 |
| `:feature-mouse` | 4 | **32** | 0 | 0 | 0 |
| `:feature-session` | 5 | **82** | 0 | 0 | 0 |
| `:feature-telemetry` | 10 | **118** | 0 | 0 | 0 |
| **PROJECT TOTAL** | **44** | **487** | **0** | **0** | **0** |

> Count note vs. brief: `:app` is **160**, not "155". app_finish's own per-suite rows also sum to 160
> (74 e2e + 86 unit; its table's `TOTAL 155` was an addition typo). Every suite was re-executed and
> re-counted from `TEST-*.xml` under `--rerun-tasks` — no count reduced, no assertion weakened.

---

## 2. Exact commands + exit codes (full ledger)

Environment for EVERY invocation (PowerShell):

```powershell
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot"
cd C:\Users\Administrator\teamwork_projects\android_rdp_client
```

Attempts in order (interference incidents fully documented in §7):

| # | Command | Window (local) | Exit | Outcome |
|---|---|---|---:|---|
| 1 | `.\gradlew.bat testDebugUnitTest --console=plain` (background) | 03:42:57–03:43:1x | 0 | Output log EMPTY despite exit 0 — known silent-capture failure (app_finish risk 1); unusable as evidence, re-ran foreground |
| 2 | `.\gradlew.bat testDebugUnitTest --console=plain` (foreground) | ~03:44 | 0 | BUILD SUCCESSFUL in 1s, **145/145 UP-TO-DATE** — valid pass, but tests did not re-execute |
| 3 | `.\gradlew.bat testDebugUnitTest --console=plain --rerun-tasks` | 03:45:10–03:46:08 | 0 | BUILD SUCCESSFUL in 56s, 145 executed (real). XMLs re-captured post-hoc were **polluted 8s after exit** by a concurrent agent's run (feature-session rewritten 03:46:16 w/ 5 `ModifierStateMachineTest` failures, green again 03:48:04) — not from my run (Gradle would have failed it) |
| 3b | `.\gradlew.bat assembleDebug --console=plain` | ~03:46:1x | 0 | UP-TO-DATE → APK older than run start → switched to scripted gate runner |
| A | `gate_run.ps1 runA` = `testDebugUnitTest --console=plain --rerun-tasks` → XML capture → `assembleDebug --console=plain` | 03:48:51–03:49:45 | **0** | **PASS** — 487/487, 44/44 suites fresh, APK 03:49:45 |
| B | same, label runB | 03:49:54–03:50:53 | 1 | FAIL — `:feature-telemetry:testDebugUnitTest`: mass `NoClassDefFoundError`/`ClassNotFoundException` (42 tests) + executor `java.io.EOFException`; other 4 modules 369/369 green. Concurrent-run classpath clobber (latency_perf R6 pattern) |
| B2 | same, label runB2 | 03:51:07–03:52:12 | 1 | FAIL — `SessionPhaseReducerTest.failureWhileIdleIsIgnored` (1 assertion) + `NoSuchFileException: ...in-progress-results-generic.bin`. Mutant active mid-run + result-dir deleted by concurrent run. On-disk reducer/test verified pristine afterward (`SessionPhase.kt:81 is SessionPhase.Idle -> this`, file mtimes untouched) |
| C | same, label runC | 03:53:54–03:55:12 | **0** | PASS tests+assemble; XML capture incomplete (39/44 — feature-session results dir wiped at the capture instant by a concurrent run). Stable in-window source hash |
| D | same, label runD | 03:57:12–03:58:09 | **0** | PASS with complete table 487/487; `SOURCE_CHANGED_DURING_RUN=True` — after-hash caught a transient telemetry mutant (03:58:10), restored afterwards → APK lineage from this run not trusted |
| **E** | same + **pristine-hash pinning** (`before==after==6e268c87…` required), label runE | **04:02:38–04:03:22** | **0** | **✅ OFFICIAL GATE RUN 1** — 487/487, 44 suites, 0 stale XML, APK fresh, hashes pristine |
| **F** | same, label runF | **04:04:55–04:05:42** | **0** | **✅ OFFICIAL GATE RUN 2** — 487/487, 44 suites, 0 stale XML, APK forced fresh via `:app:packageDebug --rerun` (exit 0), hashes pristine |
| G | `.\gradlew.bat assembleDebug --rerun-tasks --console=plain` (definitive rebuild) | 04:07:20–04:07:39 | **0** | 153/153 tasks executed, pristine hash before AND after, **final APK 17,188,899 B** |

Gate runner script (evidence tooling, lives in session scratchpad, not in the repo):
`<SCRATCHPAD>\gate_run.ps1` — runs tests `--rerun-tasks`, captures all `TEST-*.xml` **inside the same
invocation within ~1 s of Gradle exit**, validates: exit code, 0 failures/errors, every XML mtime ≥ run
start (no stale), exactly 44 suites/487 tests, APK > 0 and mtime ≥ run start (auto-forces
`:app:packageDebug --rerun` if stale), and SHA-256 content hash of ALL `*/src/**/*.{kt,java,xml}`
before/after pinned to the pristine reference. Per-run CSVs: `gate_run{A,B2,C,D,E,F}_xml.csv` in scratchpad.

**Pristine reference hash** `6e268c876caf7f0bda9e373defdf5cbf34dd241d1fa04b0467ce9c465a8a2c17`
was established by running the challenger's own `.agents\challenger_w2\restore_audit.ps1` (read-only:
file-hash vs. pristine `.orig` backups + MUTANT-marker scan over their 11 mutable files) → **`bad=0`**,
i.e. every challenger-touched file was byte-identical to its pristine backup at gate-certification time.

---

## 3. Per-suite tables — BOTH official gate runs

Run **E** and run **F** produced byte-identical results; every suite executed in both (mtimes fall
inside each run's window; 0 stale). Columns: tests / failures / errors / skipped — **same for E and F**.

| Module | Suite | Tests | Failures | Errors | Skipped |
|---|---|---:|---:|---:|---:|
| app | e2e.Tier1FeatureCoverageTest | 35 | 0 | 0 | 0 |
| app | e2e.Tier2BoundaryCornerTest | 26 | 0 | 0 | 0 |
| app | e2e.Tier3CrossFeatureTest | 8 | 0 | 0 | 0 |
| app | e2e.Tier4RealWorldScenariosTest | 5 | 0 | 0 | 0 |
| app | unit.AppContainerTest | 7 | 0 | 0 | 0 |
| app | unit.ClipboardSyncTest | 4 | 0 | 0 | 0 |
| app | unit.CoroutineDiagnosticsTest | 5 | 0 | 0 | 0 |
| app | unit.NavigationModelTest | 7 | 0 | 0 | 0 |
| app | unit.ProfileEditorViewModelTest | 7 | 0 | 0 | 0 |
| app | unit.ProfilesViewModelTest | 7 | 0 | 0 | 0 |
| app | unit.ProfileValidationTest | 7 | 0 | 0 | 0 |
| app | unit.SessionGraphicsTest | 5 | 0 | 0 | 0 |
| app | unit.SessionPhaseReducerTest | 10 | 0 | 0 | 0 |
| app | unit.SessionViewModelTest | 22 | 0 | 0 | 0 |
| app | unit.SettingsPersistenceTest | 5 | 0 | 0 | 0 |
| **app subtotal** | | **160** | **0** | **0** | **0** |
| core-rdp | ClipboardHandlerTest | 6 | 0 | 0 | 0 |
| core-rdp | DisplayControlHandlerTest | 5 | 0 | 0 | 0 |
| core-rdp | LibFreeRdpJniContractTest | 13 | 0 | 0 | 0 |
| core-rdp | MockRdpEngineParityTest | 6 | 0 | 0 | 0 |
| core-rdp | MockRdpEngineTest | 10 | 0 | 0 | 0 |
| core-rdp | NativeFreeRdpEngineArgsTest | 8 | 0 | 0 | 0 |
| core-rdp | NativeFreeRdpEngineLifecycleTest | 22 | 0 | 0 | 0 |
| core-rdp | NativeFreeRdpEngineStressTest | 7 | 0 | 0 | 0 |
| core-rdp | ProtocolStressTest | 14 | 0 | 0 | 0 |
| core-rdp | RdpPointerFlagsTest | 4 | 0 | 0 | 0 |
| **core-rdp subtotal** | | **95** | **0** | **0** | **0** |
| feature-mouse | CoordinateTransformerTest | 8 | 0 | 0 | 0 |
| feature-mouse | FloatingMouseOverlayTest | 7 | 0 | 0 | 0 |
| feature-mouse | GestureDisambiguationTest | 7 | 0 | 0 | 0 |
| feature-mouse | MouseControllerTest | 10 | 0 | 0 | 0 |
| **feature-mouse subtotal** | | **32** | **0** | **0** | **0** |
| feature-session | KeystoreCredentialStoreTest | 17 | 0 | 0 | 0 |
| feature-session | ModifierStateMachineTest | 20 | 0 | 0 | 0 |
| feature-session | ProfileRepositoryTest | 17 | 0 | 0 | 0 |
| feature-session | QuickActionToolbarTest | 14 | 0 | 0 | 0 |
| feature-session | ScancodeTranslatorTest | 14 | 0 | 0 | 0 |
| **feature-session subtotal** | | **82** | **0** | **0** | **0** |
| feature-telemetry | AdaptivePresetSwitcherTest | 8 | 0 | 0 | 0 |
| feature-telemetry | AutoReconnectManagerTest | 15 | 0 | 0 | 0 |
| feature-telemetry | DynamicLayoutListenerTest | 9 | 0 | 0 | 0 |
| feature-telemetry | FramePacerTest | 16 | 0 | 0 | 0 |
| feature-telemetry | FreeRdpFlagMappingTest | 10 | 0 | 0 | 0 |
| feature-telemetry | LowLatencySocketConfigTest | 17 | 0 | 0 | 0 |
| feature-telemetry | NetworkStateMonitorTest | 6 | 0 | 0 | 0 |
| feature-telemetry | PerformancePresetTest | 6 | 0 | 0 | 0 |
| feature-telemetry | RdpThreadIsolationTest | 8 | 0 | 0 | 0 |
| feature-telemetry | TelemetryCollectorTest | 23 | 0 | 0 | 0 |
| **feature-telemetry subtotal** | | **118** | **0** | **0** | **0** |
| **PROJECT TOTAL (E = F)** | **44 suites** | **487** | **0** | **0** | **0** |

Handoff-claim cross-check (all satisfied, none reduced): core-rdp 95/95 ✅ (native_finish),
feature-mouse 32/32 ✅, feature-session 82/82 ✅ (session_logic), feature-telemetry 118/118 ✅
(latency_perf), app 74 e2e ✅ (e2e_tests) + 86 unit ✅ (app_finish) = 160 (brief's "155" stale).

---

## 4. APK evidence (final)

| Field | Value |
|---|---|
| Path | `C:\Users\Administrator\teamwork_projects\android_rdp_client\app\build\outputs\apk\debug\app-debug.apk` |
| Size | **17,188,899 bytes** (> 0 ✅) |
| mtime | **2026-09-23 04:07:39.081** (newer than both official gate runs' starts ✅) |
| Built by | `.\gradlew.bat assembleDebug --rerun-tasks` — all 153 tasks executed, exit 0, source hash pinned pristine before+after |
| Determinism | Run F's forced `packageDebug --rerun` (04:05:42) produced the **same 17,188,899 bytes** from pristine-derived intermediates; the definitive rebuild reproduced it exactly |
| Merged manifest | `app\build\intermediates\merged_manifest\debug\processDebugMainManifest\AndroidManifest.xml`: `android.permission.INTERNET` = **true**, `com.freerdp.client.App` = **true** |
| Native libs | `libandroidx.graphics.path.so` only (AndroidX) — FreeRDP `.so` absence is the documented, graceful-degraded state (native_finish §3; 1001 UX pinned by tests). `mergeDebugNativeLibs NO-SOURCE` as expected |

APK size lineage (why earlier sizes differ): 17,336,851 (runs A/B, pre-interference intermediates) →
17,376,022 (C/D/E repackage lineage, intermediates touched during the interference window) →
**17,188,899 (every intermediate re-derived from hash-pinned pristine sources — final, trusted)**.
Earlier APKs are superseded; only the final one above is the deliverable.

---

## 5. Fixes I made (file + why)

**Source/build fixes: NONE required.** Every module was green on pristine sources from my first
executing run; every apparent failure was root-caused to concurrent-agent interference and did not
reproduce (§7). Per the brief I did not churn working code and did not touch any test/assertion.

Files I created (all in my ownership or scratchpad — repo sources untouched):

| File | Why |
|---|---|
| `<SCRATCHPAD>\gate_run.ps1` | Gate automation: forced test execution, race-minimized XML capture with staleness + exact-count validation, APK freshness enforcement, pristine source-hash pinning (evidence tooling; session-scoped, not committed) |
| `.agents\integrator_w2\handoff.md` | This document |

Diagnostic reads only (no edits): `.agents\challenger_w2\restore_audit.ps1` (executed — read-only
hash audit, `bad=0`), `.agents\challenger_w2\backup\` listing, all seven wave-1 handoffs,
`gradle.properties`, `settings.gradle.kts`, reducer/test sources.

---

## 6. Cheap-gap audit results

| Check | Method | Result / judgment |
|---|---|---|
| `gradle.properties` CI-sanity | read | **Sane, left unchanged**: `org.gradle.java.home` = required JDK 21 path (so plain `.\gradlew.bat` runs use the right JVM even if `JAVA_HOME` unset), `-Xmx2048m -Dfile.encoding=UTF-8`, `android.useAndroidX=true`, `android.nonTransitiveRClass=true`, `kotlin.code.style=official`. No secrets, no absolute-path leaks beyond the JDK pin (machine-specific by design). Deliberately did NOT add `parallel`/`configuration-cache` — concurrent-agent Gradle contention was already this run's dominant failure mode; enabling parallelism would churn a working config |
| TODO/FIXME in main sources | grep `*/src/main/**` + root `build.gradle.kts` + `*.properties` | **0 matches** — nothing to clean |
| Debug prints | grep `System.out/err.print`, `printStackTrace()`, `println(`, `Log.d/v(` in `*/src/main/**` | **0 matches** — nothing to clean |
| Root/settings gradle | read | consistent; `FAIL_ON_PROJECT_REPOS`, 5 modules included; no action |
| README/PROJECT.md status freshness | read | **STALE — exact edits listed in §8** (docs not edited by me, per ownership rules) |

---

## 7. Interference forensics (why runs B, B2 failed and how E/F are certified)

You run concurrently with `reviewer_w2` (read-only Gradle runs) and `challenger_w2` (temporarily
swaps mutant files with backup+restore). Both operate in the SAME project directory, so their Gradle
runs and file swaps land inside my gate windows. Observed incidents, each root-caused:

1. **Background attempt #1**: exit 0 with completely empty output log → known silent capture failure
   (app_finish risk 1). Re-ran foreground; never trusted the empty log.
2. **Run 1 XML pollution (03:46:16)**: feature-session results rewritten 8 s *after* my run exited
   with 5 `ModifierStateMachineTest` failures, green again at 03:48:04 → challenger mutant experiment,
   not my run (my Gradle exited 0; Gradle fails the build on any test failure).
3. **Run B telemetry meltdown**: 42× `NoClassDefFoundError`/`ClassNotFoundException` + executor
   `java.io.EOFException` while 369/369 other-module tests passed → another run wiped/rewrote
   `feature-telemetry/build` classpath mid-execution (latency_perf R6 documented this exact
   corruption + the fix of just re-running). Re-run per brief: next attempt telemetry was 118/118.
4. **Run B2 app failure**: `failureWhileIdleIsIgnored` assertion + `NoSuchFileException:
   in-progress-results-generic.bin` → mutant active during my compile AND result file deleted by a
   concurrent test run. Verified afterward: `SessionPhase.kt` (mtime 01:37:49) still contains
   `is SessionPhase.Idle -> this` for `ConnectionFailed`; test file untouched since 02:08:20; both
   byte-identical to challenger's pristine backup. No stuck mutant — no fix needed.
5. **Run C**: capture raced a concurrent run's `clean`-like wipe of `feature-session` results
   (39/44 suites at capture). Gradle exit 0 proves the task itself succeeded.
6. **Run D**: after-hash caught a transient telemetry mutant 1 s after assemble → discarded D's APK
   from the trusted lineage (it was rebuilt later, twice).
7. **Certification method for E/F**: content-hash (SHA-256 over path+bytes of every `*.kt/*.java/*.xml`
   under all five `src` trees) pinned to the reference established by challenger's own
   `restore_audit.ps1` (`bad=0` = all 11 mutable files == pristine backups, no MUTANT markers),
   checked immediately before tests AND after APK packaging; exact 44-suite/487-test expectation;
   per-XML mtime ≥ run-start. E and F both passed every check, back to back, ~2 min apart.
8. Final XML snapshot after all of the above: 44 suites / 487 / 0 / 0 / 0 still on disk.

**No failure ever reproduced on pristine sources.** Nothing was fixed in any module because nothing
was broken in any module.

---

## 8. Doc updates needed (DO NOT let acceptance trust current status text — I may not edit these)

`README.md`:
1. Module table (line ~23): `:feature-telemetry` "**verification in progress — latest test run FAILING**"
   → "**verified — 118/118 tests green** (latency_perf handoff; re-verified by integrator_w2 wave-2
   gate 2026-09-23 04:03/04:05 local)".
2. Status table line ~93 (`M4` row): replace the whole "LATEST RUN FAILING / 25/33; 8 failures" cell →
   "**code-complete, VERIFIED — 118/118 green**, independently re-executed twice in the wave-2 full
   project gate (487/487 total)".
3. Add/refresh the gate row(s): full-project `testDebugUnitTest` = **487/487, 0 failures, twice
   (04:02:38 & 04:04:55 local)**; `assembleDebug` = BUILD SUCCESSFUL, APK
   `app/build/outputs/apk/debug/app-debug.apk` 17,188,899 B @ 2026-09-23 04:07:39; note FreeRDP
   `.so` still absent by documented infeasibility (graceful 1001 UX).

`PROJECT.md` milestone table (lines ~96–99):
4. **M2 row**: "wave-2 independent re-run pending" → "wave-2 independent re-run **DONE — 32/32 green**
   (integrator_w2, 2 runs)".
5. **M3 row**: "wave-2 independent re-run pending" → "**DONE — 82/82 green** (integrator_w2, 2 runs)";
   test count 41 → 82 already correct in table? (row still says 41/41 green — update to 82/82).
6. **M4 row**: "LATEST RUN FAILING … fixes in flight" → "**VERIFIED — 118/118 green**, wave-2 gate".
7. **M5 row**: "IN PROGRESS — final gates not yet run" → "**GATES RUN & GREEN** — full-project
   testDebugUnitTest 487/487 twice; assembleDebug APK 17,188,899 B (2026-09-23 04:07); native `.so`
   packaging remains INFEASIBLE-on-this-machine with verified graceful UX (native_finish §3);
   FreeRdpFlagMapping→core-rdp consumption remains a documented follow-up (dependency cycle)".

`ORIGINAL_REQUEST.md` / `docs/ACCEPTANCE.md` (acceptance agent owns the ticking):
8. AC-1 now has fresh APK evidence post-dating all source edits (final APK 04:07:39; last real source
   edit ≤ 03:39; sources hash-pinned pristine) — re-evaluate for ticking with the §4 table.
9. M4-failure citations in the evidence ledger are stale (they quote `baseline_build.log` 8/33) —
   replace with the 118/118 re-verification + gate-run XMLs.
10. AC-2 remains **unticked** (no FreeRDP `.so` — correctly documented as infeasible + graceful path).
11. Anywhere "M4 FAILING" appears in `docs/ACCEPTANCE.md` appendix snapshots — annotate "superseded by
    2026-09-23 wave-2 gate".

---

## 9. Open follow-ups — decisions (fix-now vs document)

| Follow-up | Decision | Rationale |
|---|---|---|
| `FreeRdpFlagMapping` → `:core-rdp` consumption (dependency cycle) | **DOCUMENT (not fixed now)** | Both clean options from native_finish Q1 are contract-touching: (a) move mapping + its 10 tests into `:core-rdp` and flip telemetry's dependency, or (b) add `extraCliArgs: List<String>` to `RdpConnectionConfig` and fill it from `:app`. Either edits `PROJECT.md` §Interface Contracts territory and two module owners' code while every gate is green — not "cheap+safe" mid-gate. Current state is behaviorally close (core-rdp emits its own per-preset flags; delta = `/compression-level`, `/gfx:*`, `/audio-mode`, perf extras). **Owner hand-off required at acceptance; pick option (a)** (keeps data single-sourced, no cycle). Beware `/gfx:AVC444|AVC420` needs `WITH_GFX_H264` (risk R6) → fall back `/gfx:progressive` |
| `SO_SNDBUF`/`SO_RCVBUF` tuning on native fd | **DOCUMENT (future)** | Needs JNI fd export in `client/Android/android_freerdp.c` — impossible until the first real `.so` build (no NDK/sources on this machine). `TCP_NODELAY` already confirmed ON in FreeRDP by default (native_finish Q2). Accept FreeRDP defaults for v1 |
| Native `.so` packaging | **DOCUMENT (already documented)** | `core-rdp/scripts/package-native-libs.ps1` + native_finish §3 exact steps stand; verified `mergeDebugNativeLibs NO-SOURCE`, only AndroidX `.so` in APK; 1001 graceful UX pinned by tests |

---

## 10. Residual risks

1. **Concurrent-agent interference is ongoing.** If acceptance re-runs the gate later, a one-off
   failure (esp. `NoClassDefFoundError`/`EOFException`/`NoSuchFileException`, or a single assertion in
   a module whose handoff claims green) is almost certainly transient — re-run once, and verify with
   a pristine hash (`6e268c87…` over `*/src/**/*.{kt,java,xml}`) + `restore_audit.ps1 bad=0`.
   If `reviewer_w2`/`challenger_w2` are still active, XMLs under `build/test-results` may be
   overwritten by THEIR runs after mine — Gradle exit codes in §2 are the primary evidence.
2. **APK lineage:** sizes 17,336,851 / 17,376,022 seen earlier were built from intermediates touched
   during interference. Only **17,188,899 @ 04:07:39** is certified (full forced rebuild, pristine
   hashes both sides, reproduced twice). If anyone rebuilds casually afterwards they'll get a
   UP-TO-DATE or fresh package of the same pristine sources — byte-stable at 17,188,899 was observed
   twice, but APK byte-reproducibility was not formally proven (mtime/zip-metadata may vary on
   rebuild — size+content classes stable).
3. **Undetectable flip-flop window:** a mutant applied and reverted entirely *between* my before/after
   hash captures would escape pinning. Risk bounded by (a) E and F both green with pristine endpoints,
   (b) challenger's own `restore_audit.ps1 bad=0` after my runs, (c) full 487-test coverage executed
   under `--rerun-tasks` compile-from-scratch in the same windows.
4. **Stress/concurrency suites** (`ProtocolStressTest`, disconnect hammers, pacer/telemetry
   real-thread tests) are the documented flake sources (native_finish R5, latency_perf R5) — green in
   every one of my 6 executing runs (A, C, D, E, F + run 1), but heaviest-CI loads could still flake;
   same re-run remedy.
5. **Robolectric NATIVE graphics dependency** (`robolectric-nativeruntime.dll`): present on this host;
   a host without it fails `SessionGraphicsTest` loudly with `UnsatisfiedLinkError` (app_finish risk 2).
6. **`pumpAll` 600 s horizon** (app_finish risk 3) and coroutines-test 1.9.0 `advanceUntilIdle`
   semantics — future tests must keep using `pumpAll()`, documented inline in `TestSupport.kt`.
7. **FreeRDP version drift / JNI contract** (native_finish R1–R3) unchanged — first on-device run may
   surface JNI drift no JVM test can see.
8. **Docs are stale until acceptance applies §8** — README/PROJECT.md still say "M4 FAILING";
   anyone reading them without this handoff will get the wrong status.

---

## 11. Assumptions made (no follow-up questions possible)

- "Run the full gate twice" is satisfied by runs **E** and **F** (both headline commands + forced
  test re-execution + fresh APK + full tables); earlier attempts A/C/D are disclosed in §2 rather than
  hidden, because their tables/APKs were corrupted by concurrent agents, not by the code.
- Background attempt #1 (exit 0, empty log) is treated as *unusable*, not as a pass or a failure.
- app's real count is 160 (from XML, executed twice), not the brief's "155" — reported as measured;
  no counts reduced anywhere.
- Reading `.agents\challenger_w2\*` (never writing) was permitted and necessary for ground-truth
  certification; their mutant experiments' transient failures are attributed to them, with evidence.
- Cost/benefit judgment on the mapping/socket follow-ups (§9) favors documentation over mid-gate
  contract churn; acceptance can still schedule option (a) as a post-gate task.
