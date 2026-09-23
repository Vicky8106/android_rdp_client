# HANDOFF — e2e_tests (E2E Test-Suite Specialist)

**Date**: 2026-09-23
**Status**: **DONE** — full 4-tier suite implemented and verified; 74/74 tests pass.

---

## 1. Status Summary

| Gate | Required | Actual | Result |
|---|---|---|---|
| Compilation | zero errors in e2e sources | 0 | PASS |
| Execution (filtered headline command) | 100% pass | **74/74 (100%)** | PASS |
| Determinism | no Thread.sleep / no real network / no wall-clock timing | virtual-time + injected clocks only | PASS |
| app/build.gradle.kts edits | only if a test dependency was missing | **none required** (junit, mockk, coroutines-test 1.9.0, robolectric 4.14.1 already present) | no edit made |
| Write-scope compliance | only `app/src/test/java/com/freerdp/client/e2e/**` | only that dir touched (plus this handoff) | PASS |

## 2. Files Delivered

All under `app/src/test/java/com/freerdp/client/e2e/`:

| File | Lines | Tests |
|---|---|---|
| `Tier1FeatureCoverageTest.kt` | 1248 | 35 |
| `Tier2BoundaryCornerTest.kt` | 667 | 26 |
| `Tier3CrossFeatureTest.kt` | 639 | 8 |
| `Tier4RealWorldScenariosTest.kt` | 612 | 5 |
| **Total** | | **74** |

All four classes run under `@RunWith(RobolectricTestRunner::class)` `@Config(sdk = [34])`
(android-all-instrumented SDK 34 jar is present in the local `.m2` cache — no download needed),
with `MockRdpEngine` as the only engine double and `kotlinx-coroutines-test`
`StandardTestDispatcher`/`TestScope` virtual time throughout.

## 3. Per-Tier Counts (actual / required)

### Tier 1 — Core Feature Coverage: **35 / ≥20** (min 5 per category)
- R1 Core engine (9 ≥ 5): connect happy path + NLA/TLS flags, disconnect cleanup, certificate accept, certificate reject→403, connection failure code/message surfacing, MS-RDPBCGR pointer-flag protocol recording, dynamic-resolution echo, MS-RDPECLIP UTF-16LE round trip, session-metrics flow.
- R2 Mouse/gesture (9 ≥ 5): left-click DOWN/UP pair, right-click BUTTON2, double-click 4-event sequence, drag `0x9800` button-held moves, wheel/ hwheel spec rotations (`0x0278/0x0378/0x0478/0x0578`), touchpad relative cursor + absolute-mode contrast, pan-without-clicking, pinch span-ratio scale factor, affine screen↔desktop round trip.
- R3 Session/security (7 ≥ 5): profile CRUD via `AtomicFileProfileRepository`, persistence across instances, keystore round trip + `wipeSecret`, 3-state modifier latch cycle with exact scancodes, latched-Ctrl keystroke ordering + auto-release, Windows Set-1 scancode table (incl. extended flags), quick-action toolbar 4000 ms auto-collapse (3999/4000 boundary) + touch-timer reset.
- R4 Performance (10 ≥ 5): exponential backoff reconnect with deterministic jitter (exact 1000 ms at attempt 1, 999/1001 ms boundaries), backoff jitter bounds + negative-attempt rejection, single-slot `FramePacer` drop accounting, exact RTT percentiles (p95=290/p99=298 on [100,200,300]), 60 fps + jitter stddev, HUD quality thresholds, preset→FreeRDP CLI args, low-latency socket tuning, FastPath priority + starvation promotion, `DynamicLayoutListener` orientation resize + dedupe.

### Tier 2 — Boundary & Corner Cases: **26 / ≥20** (min 5 per category)
- Category A endpoints/payload (6): empty hostname end-to-end, port 0 preserved, port 65535 preserved, 1 MB clipboard encode (1,000,002 B with NUL), 1 MB through engine + echo suppression, empty/CF_DIB/NUL-only clipboard rejection.
- Category B coordinates/scale (7): zero→origin, negative→clamp, beyond→last pixel, sub-pixel 0.37 scale round trip (<0.38 px error) + min/max scale clamps, pan edge clamping (both axes + fit-centering), zero remote dims→1 px guard, overlay normalized [0,1] validation + safe-inset collapse.
- Category C orientation/debounce/alignment (7): six rapid flips coalesce to one PDU (last wins), 250 ms debounce exact boundary (0 @249, 1 @250), `& ~3` odd-dimension alignment, sub-minimum floor 640/480, non-positive DPI→0 mm, non-positive/duplicate layout rejection + orientation-change fires, debounced resize cancellation.
- Category D reconnect/credentials (6): reconnect exhaustion → `Failed(exhausted=true)`, zero backoff delay (pure fn + manager path), network loss during backoff → `WaitingForNetwork` hold + fast-path recovery, corrupt profile file quarantine/`.bak` recovery, tampered vault fails closed to null (invalid Base64 + flipped GCM byte), missing credential → null not error.

### Tier 3 — Cross-Feature Combinations: **8 / 8** (complete matrix)
1. zoom + drag latch (focal-point invariance + phantom-click suppression + affine drag coords)
2. keystore → NLA launch (vault → `toConnectionConfig` NLA/TLS flags → cert callback → Connected → CharArray wipe)
3. rotate-during-reconnect (rotation PDU recorded exactly once mid-backoff, survives recovery)
4. modifier latch + drag (Ctrl latched across 4 pointer PDUs, channels isolated, Ctrl+V consumption)
5. preset switch → socket buffers (WIFI→ULTRA vs METERED→DATA_SAVER fps/codec/depth, buffers stay 128/64 KB low-latency)
6. resolution change + clipboard (debounced PDU lands once; outbound/echo counts unaffected; genuine remote paste still works)
7. touchpad + scancode macro (clamped relative cursor, Ctrl+Alt+Del exact 6-event order, click uses virtual cursor)
8. reconnect + telemetry (exact transition list Idle→Connected→Suspended→Reconnecting(0,0)→Connected; RTT ring survives pause/resume: avg=43, p95=43.9)

### Tier 4 — Real-World Scenarios: **5 / 5**
- SCENARIO-1 enterprise login: profile+vault→NLA handshake (cert TOFU callback)→resolution→toolbar toggle→Ctrl+S exact scancode chain→icon click→4 s auto-collapse→vault wipe.
- SCENARIO-2 presentation: `resetToFit` (scale 0.5625)→pinch ×2 zoom (1.125)→clamped pan→right-click→3× wheel-down slides; exactly 5 pointer PDUs, 0 phantom taps.
- SCENARIO-3 remote IDE: latched Ctrl+C chain, typed `ls`+Enter Set-1 sequence, clipboard sync + echo suppression, drag-lock selection flags, Shift+X latch consumption, Win+R macro exact tail.
- SCENARIO-4 cellular drop: handoff drop→`Reconnecting(1,1000)` with exact jitter→5-step teardown history [1..5]→999/1001 ms backoff boundary→recovery Connected (successCount=2), telemetry avg survives (30.0), attempt counter reset on second cycle (history [1..5,1..5])→clean Idle shutdown.
- SCENARIO-5 foldable+60 fps: unfold PDU 2208×1668×175 mm×132 mm @320 dpi→refold→60-frame burst → 59 atomic drops, VSYNC blit of frame #60 via injected clock, dropRatio 59/60, telemetry 60 fps/0 jitter/59 drops, second micro-burst invariants, session stays Connected.

## 4. Exact Commands + Results

Environment (PowerShell):
```powershell
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot"
cd C:\Users\Administrator\teamwork_projects\android_rdp_client
```

**Headline acceptance command (pass/total = 74/74):**
```powershell
.\gradlew.bat -I "<SCRATCHPAD>\e2e_exclude_probe.init.gradle" :app:testDebugUnitTest --tests "com.freerdp.client.e2e.*" --console=plain
# BUILD SUCCESSFUL
# TEST-com.freerdp.client.e2e.Tier1FeatureCoverageTest  tests=35 failures=0 errors=0
# TEST-com.freerdp.client.e2e.Tier2BoundaryCornerTest   tests=26 failures=0 errors=0
# TEST-com.freerdp.client.e2e.Tier3CrossFeatureTest     tests= 8 failures=0 errors=0
# TEST-com.freerdp.client.e2e.Tier4RealWorldScenariosTest tests=5 failures=0 errors=0
# TOTAL 74/74, 0 failures, 0 errors, 0 skipped
```
(`<SCRATCHPAD>` = `C:\Users\ADMINI~1\AppData\Local\Temp\2\commandcode\C--Users-Administrator\644d9440-54d9-4a61-b7a9-44a38c01e091\scratchpad` — session-scoped; see §6.)

**Full :app suite (other-tests regression check):**
```powershell
.\gradlew.bat -I "<SCRATCHPAD>\e2e_exclude_probe.init.gradle" :app:testDebugUnitTest --console=plain --rerun-tasks
# 153 tests completed, 30 failed
#  E2E  (mine):  tests=74  failures=0   ← unchanged, green inside the full run
#  UNIT (other agent): tests=79  failures=30
```
Compile-only: `.\gradlew.bat :app:compileDebugUnitTestKotlin --console=plain` → BUILD SUCCESSFUL (with init script).

**History**: first execution was 64/74 (10 failures); every failure was root-caused from
`--info` traces and fixed in my sources; final state is 74/74 across two consecutive runs.

## 5. Coverage Gaps vs TEST_INFRA Matrix (F01–F24)

- All 24 features are exercised at ≥1 tier (matrix claim "Covered ×4 tiers" is satisfied
  for every row except the following honest qualifications):
- **F01/F02 (JNI bridge / NativeFreeRdpEngine)**: exercised only through `MockRdpEngine` /
  `IRdpEngine` contracts — by design (TEST_INFRA §1.2 forbids native/JNI in tests).
  No test touches `NativeFreeRdpEngine` directly.
- **F17 toolbar inactivity**: virtual-time tested at the FSM contract level; the Compose
  toolbar UI itself lives in app/src/main (not imported — see §6).
- **F26/F27-equivalents in app layer** (`SessionViewModel`, `NetworkMonitor`,
  `ReconnectTuning`, `ClipboardBridge`, `HapticMouseController`): NOT covered by my suite —
  they are app/src/main classes owned by the concurrent app agent, which has its own
  `unit/**` tests for them. TEST_INFRA's opaque-box scope is the feature-module contracts,
  so no Tier-4 step imports `com.freerdp.client.*` app classes.
- **TEST_READY.md** (mentioned in `.agents/sub_orch_e2e/SCOPE.md`) was NOT written:
  project-root `*.md` files are outside my write scope (stated per instructions).

## 6. Cross-Module Requests

1. **BLOCKER (app/unit-test owner) — `app/src/test/java/com/freerdp/client/unit/CoroutineDiagnosticsTest.kt` is missing `import kotlinx.coroutines.withContext`.**
   This single line blocks `:app:compileDebugUnitTestKotlin` for every agent and every
   `:app` test run. The file has been idle since 02:16; it self-describes as a temporary
   bring-up probe — please add the import or delete the file.
   Because I must not edit `unit/**`, my verification runs used an **invocation-scoped
   Gradle init script** (`<SCRATCHPAD>\e2e_exclude_probe.init.gradle`, passed via `-I`)
   that pattern-excludes exactly that one file from *my* runs. It mutates no repository
   file; normal (init-script-free) runs compile it as-is. Once the import lands, run the
   headline command WITHOUT `-I`:
   `.\gradlew.bat :app:testDebugUnitTest --tests "com.freerdp.client.e2e.*" --console=plain` → 74/74.
2. **App/unit-test owner — 30 pre-existing failures in your suites** (present before any
   change of mine could matter; my files are additive e2e-only): `SessionViewModelTest`
   14/20, `ProfilesViewModelTest` 7/7, `ProfileEditorViewModelTest` 6/7,
   `SessionGraphicsTest` 3/5. Samples: `connectIsIdempotentAcrossRotationStyleReEntry
   expected:<Connected(...)> but was:<Idle>`; `rapidUpdatesDropStaleFrames...
   expected:<-16776961> but was:<-16777216>` (pixel colour). Your other 6 suites
   (AppContainer, ClipboardSync, NavigationModel, ProfileValidation, SessionPhaseReducer,
   SettingsPersistence) pass 49/49.
3. **Telemetry/session hardening agents** — I adapted to your in-flight APIs; please keep
   these surfaces stable or ping me: `FastPathPduPrioritizer.PduQueue(capacity,
   starvationThresholdNanos, nanoClock)` + nullable `enqueue`; `FramePacer(onFrameDropped
   Callback, maxFrameAgeNanos, vsyncPeriodNanos)` + injected-timestamp `onFrameDecoded/
   acquireFrameForRendering` overloads; `calculateBackoffWithJitter` now `require`s
   attempt ≥ 0; `KeystoreCredentialStore` fails closed to `null` (never throws);
   `LowLatencySocketConfig.configureSocket` returns `Result.failure(IllegalStateException)`
   for closed sockets; `recordRtt(-n)` records a clamped 0 sample;
   `DynamicLayoutListener.DEFAULT_DEBOUNCE_DELAY_MS` is now 250.
   `MockRdpEngine.certVerificationHost/Fingerprint` default to `mock.server.local/
   SHA256:MOCK_CERT` — tests must set them when asserting the cert-callback identity.

## 7. Risks

- **Concurrent API churn**: three modules were re-written mid-run (core-rdp JNI bridge,
  feature-telemetry FastPath/pacer, feature-session vault). My suite tracks current
  behaviour; a further breaking change will surface as a compile error in
  `compileDebugUnitTestKotlin` first (cheap to detect), not silent drift.
- **Exact emission-list assertions** (combo8 transition list, scenario4 teardown history)
  rely on `StandardTestDispatcher` FIFO delivery — deterministic for coroutines-test 1.9.0,
  but a coroutines-test upgrade could re-order conflated StateFlow emissions.
- **Robolectric pinned to SDK 34** for offline determinism; bumping requires the SDK-35
  android-all jar (already cached, so low risk).
- **Full `:app` red until the app owner fixes §6.1/§6.2** — my headline gate (e2e filter)
  is green independently once §6.1's one-line import lands (or with my `-I` script).
- The `-I` init script is session-scoped in the scratchpad and will be garbage-collected
  with the session; §6.1 explains how to run without it.
