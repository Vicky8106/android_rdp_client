# Android FreeRDP Mobile Client

A production-quality, **mobile-first Android Remote Desktop client** built on the FreeRDP engine,
optimized for one-handed phone and tablet use: a repositionable floating mouse overlay with a full
touch/gesture system, a Material 3 profile & session interface, and an adaptive low-latency
streaming pipeline with live FPS/RTT/jitter telemetry.

**Requirements**: see `ORIGINAL_REQUEST.md` (R1–R4 + acceptance criteria).
**Architecture, milestones & authoritative interface contracts**: see `PROJECT.md`.
**Test specification (4-tier E2E)**: see `TEST_INFRA.md`.
**Product/UX documentation**: see `docs/PRODUCT.md`, `docs/UX_SPEC.md`, `docs/ACCEPTANCE.md`.

---

## Module map

| Module | Responsibility | Status (final, 2026-09-23) |
|--------|----------------|----------------------------|
| `:app` | **All Compose Material 3 product UI**: profile list/editor, settings, session screen (canvas, HUD, dialogs). `AppContainer` DI, `MainActivity` | **DONE — 161/161 tests green** (74 e2e + 87 unit, 15 suites) |
| `:core-rdp` | RDP engine layer: `IRdpEngine` / `RdpEventListener` contracts, `NativeFreeRdpEngine` (JNI wrapper over `LibFreeRDP`), `MockRdpEngine`, MS-RDPEDISP / MS-RDPECLIP / MS-RDPBCGR protocol handlers | **M1 DONE — gate passed; 95/95 tests green** (10 suites) |
| `:feature-mouse` | Floating mouse overlay FSM & View, gesture disambiguation engine (tap/double/long-press/pan/pinch, anti-spurious latch), affine coordinate transformer, overlay coordinate persistence | **DONE — 35/35 tests green** (4 suites; wave-2 verified) |
| `:feature-session` | Session **logic only** (no Compose UI): profile CRUD repository, Keystore credential vault, quick-action toolbar FSM, 3-state modifier bar FSM, scancode translator | **DONE — 86/86 tests green** (5 suites) |
| `:feature-telemetry` | Auto-reconnect FSM (backoff + jitter), low-latency socket config, single-slot frame pacer, thread isolation, 4 performance presets, telemetry ring buffer, dynamic-layout listener | **DONE — 120/120 tests green** (10 suites) |

---

## Build & test commands (Windows PowerShell)

Android SDK at `C:\Android\Sdk` (wired via `local.properties` → `sdk.dir`).
Gradle 9.5.0 (wrapper) · AGP 9.2.1 · JDK 21 — **`JAVA_HOME` must be set first**:

```powershell
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot"

# Full debug build → app\build\outputs\apk\debug\app-debug.apk
.\gradlew.bat assembleDebug

# All unit + Robolectric tests across every module
.\gradlew.bat testDebugUnitTest

# Per-module test runs
.\gradlew.bat :core-rdp:testDebugUnitTest
.\gradlew.bat :feature-mouse:testDebugUnitTest
.\gradlew.bat :feature-session:testDebugUnitTest
.\gradlew.bat :feature-telemetry:testDebugUnitTest

# Force re-execution (used by independent auditors)
.\gradlew.bat :core-rdp:testDebugUnitTest --rerun-tasks
```

HTML reports: `<module>\build\reports\tests\testDebugUnitTest\index.html`.
Machine-readable results: `<module>\build\test-results\testDebugUnitTest\TEST-*.xml`.

### The E2E suite

The 4-tier end-to-end suite is **specified in `TEST_INFRA.md`** and lives at:

```
app/src/test/java/com/freerdp/client/e2e/
├── Tier1FeatureCoverageTest.kt
├── Tier2BoundaryCornerTest.kt
├── Tier3CrossFeatureTest.kt
└── Tier4RealWorldScenariosTest.kt
```

Run per tier:

```powershell
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot"
.\gradlew.bat testDebugUnitTest --tests "com.freerdp.client.e2e.Tier1FeatureCoverageTest"
.\gradlew.bat testDebugUnitTest --tests "com.freerdp.client.e2e.Tier2BoundaryCornerTest"
.\gradlew.bat testDebugUnitTest --tests "com.freerdp.client.e2e.Tier3CrossFeatureTest"
.\gradlew.bat testDebugUnitTest --tests "com.freerdp.client.e2e.Tier4RealWorldScenariosTest"
```

> **Suite exists and is green:** all four Tier classes are on disk and passed in the final gate
> (Tier1 35, Tier2 26, Tier3 8, Tier4 5 = **74 e2e tests**, 0 failures).

---

## Current status — final (2026-09-23)

**The restart project is closed out with an ACCEPT-WITH-CONDITIONS verdict** — see
`.agents/acceptance_w2/ACCEPTANCE_REPORT.md` for the full evidence report. All numbers below
were independently re-parsed from on-disk `TEST-*.xml` artifacts by `acceptance_w2`.

| Gate item | Final result |
|-----------|--------------|
| `testDebugUnitTest --rerun-tasks` | **497/497 tests, 0 failures / 0 errors / 0 skipped, 44 suites across 5 modules** — run **twice** after all fixes (`fixer_w2`) |
| `assembleDebug --rerun-tasks` | **BUILD SUCCESSFUL** → `app\build\outputs\apk\debug\app-debug.apk` — **17,205,283 B**, mtime **2026-09-23 04:43:23 local** (verified fresher than the newest source edit, 04:41:24) |
| Independent integration gate | `integrator_w2` **GATE PASS ×2** (487/487 at the time, pristine source-hash-pinned); zero source edits needed; TODO/FIXME/debug-prints = **0** |
| Code review | `reviewer_w2` **APPROVE-WITH-FINDINGS** (0 BLOCKER, 2 MAJOR, 10 MINOR; interface-contract check **5/5 PASS**) → both MAJORs + key minors **FIXED** by `fixer_w2` |
| Adversarial QA | `challenger_w2` **CHALLENGE-PASS** — mutation kill **12/14 (85.7%)**, integrity score **94/100**, 0 cheats; both survivors (M3 IV-uniqueness, M4 toolbar generation guard) now have dedicated kill-tests |
| Native FreeRDP `.so` | **INFEASIBLE on this machine** (no NDK/CMake/FreeRDP sources anywhere — searched, not assumed). Graceful error-1001 UX + one-tap **"Enable demo engine & retry"** shipped and test-pinned; exact build steps: `.agents\native_finish\handoff.md` §3 + `core-rdp\scripts\package-native-libs.ps1` |

### Per-module final test counts (parsed from `TEST-*.xml`)

| Module | Suites | Tests | Failures |
|--------|-------:|------:|---------:|
| `:app` | 15 | **161** (74 e2e + 87 unit) | 0 |
| `:core-rdp` | 10 | **95** | 0 |
| `:feature-mouse` | 4 | **35** | 0 |
| `:feature-session` | 5 | **86** | 0 |
| `:feature-telemetry` | 10 | **120** | 0 |
| **Total** | **44** | **497** | **0** |

The authoritative acceptance ledger — every acceptance criterion → module → proving test →
status (8 `ACCEPTED`, 2 `PARTIAL`) — lives in **`docs/ACCEPTANCE.md`**.

---

## Repository layout (docs)

| Path | Purpose |
|------|---------|
| `ORIGINAL_REQUEST.md` | Original requirements R1–R4 + acceptance checkboxes + evidence ledger |
| `PROJECT.md` | Architecture, feature inventory, milestones, **interface contracts (byte-for-byte authoritative — imported by implementation agents)** |
| `TEST_INFRA.md` | 4-tier E2E test specification, coverage matrix, pass/fail gates |
| `docs/PRODUCT.md` | Problem statement, personas, journeys, UX principles, prioritization, release plan |
| `docs/UX_SPEC.md` | Screen-by-screen UX spec the UI agents build against |
| `docs/ACCEPTANCE.md` | Acceptance traceability matrix (fill-in ledger for final acceptance) |
| `.agents/orchestrator_1/progress.md` | Phase/agent heartbeat log |
