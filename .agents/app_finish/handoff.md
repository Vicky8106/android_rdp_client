# app_finish Handoff — Android FreeRDP Mobile Client (:app)

**Agent:** `app_finish` (continuation of `app_product`, which hit its turn limit mid-debug)
**Date:** 2026-09-23
**Status: DONE** — `.\gradlew.bat :app:testDebugUnitTest` BUILD SUCCESSFUL, **155 tests / 0 failures / 0 errors / 0 skipped, twice in a row** (second run forced with `--rerun-tasks`); `.\gradlew.bat :app:assembleDebug` BUILD SUCCESSFUL with a fresh APK. No `-I` init scripts, no filters, no exclusions.

---

## 1. Exact commands + final counts

Environment (PowerShell):

```powershell
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot"
cd C:\Users\Administrator\teamwork_projects\android_rdp_client
.\gradlew.bat :app:testDebugUnitTest --console=plain          # BUILD SUCCESSFUL (run 1, plain headline command)
.\gradlew.bat :app:testDebugUnitTest --console=plain --rerun-tasks
                                                              # BUILD SUCCESSFUL (run 2, full forced re-execution)
.\gradlew.bat :app:assembleDebug --console=plain              # BUILD SUCCESSFUL
```

Final per-suite results (`app/build/test-results/testDebugUnitTest/TEST-*.xml`, both runs):

| Suite | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| e2e.Tier1FeatureCoverageTest | 35 | 0 | 0 | 0 |
| e2e.Tier2BoundaryCornerTest | 26 | 0 | 0 | 0 |
| e2e.Tier3CrossFeatureTest | 8 | 0 | 0 | 0 |
| e2e.Tier4RealWorldScenariosTest | 5 | 0 | 0 | 0 |
| unit.SessionPhaseReducerTest | 10 | 0 | 0 | 0 |
| unit.NavigationModelTest | 7 | 0 | 0 | 0 |
| unit.ProfileValidationTest | 7 | 0 | 0 | 0 |
| unit.ProfilesViewModelTest | 7 | 0 | 0 | 0 |
| unit.ProfileEditorViewModelTest | 7 | 0 | 0 | 0 |
| unit.SessionViewModelTest | **22** (20 + 2 new) | 0 | 0 | 0 |
| unit.ClipboardSyncTest | 4 | 0 | 0 | 0 |
| unit.SessionGraphicsTest | 5 | 0 | 0 | 0 |
| unit.SettingsPersistenceTest | 5 | 0 | 0 | 0 |
| unit.AppContainerTest | 7 | 0 | 0 | 0 |
| unit.CoroutineDiagnosticsTest | 5 | 0 | 0 | 0 |
| **TOTAL** | **155** | **0** | **0** | **0** |

- e2e: **74/74** (owner's files untouched — `app/src/test/java/com/freerdp/client/e2e/**` never edited).
- unit: **86** (84 at baseline + 2 new toolbar tests).
- APK: `app/build/outputs/apk/debug/app-debug.apk`, 17,336,851 B, freshly rebuilt (2026-09-23 03:39).

**Baseline reality check:** the actual starting point of this agent was **158 tests / 34 failures** (not 153/30 — the earlier figures were stale). All 34 are root-caused below; accounting is exact: 27 pump + 4 diagnostic-probe + 1 test-bug + 2 graphics = 34.

---

## 2. Root cause of every baseline failure (test-bug vs code-bug + fix)

### 2.1 The compile blocker (fixed first)
**`unit/CoroutineDiagnosticsTest.kt` — missing `import kotlinx.coroutines.withContext`** (line 26 call). *Test-bug.* Added the import; this alone unblocked `:app:compileDebugUnitTestKotlin` for every agent.

### 2.2 The dominant root cause — 27 failures across 4 files (**test-infra bug, no production bug**)

Affected: `ProfilesViewModelTest` (all 7), `ProfileEditorViewModelTest` (5 of 6), `SessionViewModelTest` (14 of 20 → e.g. `…expected:<Connected(…)> but was:<Idle>`), `SessionGraphicsTest.rttMetricsFromEngineFlowIntoTelemetry` (1).

**Root cause (verified from library source, not guessed):** in kotlinx-coroutines-test **1.9.0**, `TestScope.advanceUntilIdle()` delegates to
`TestCoroutineScheduler.advanceUntilIdleOr { events.none(TestDispatchEvent<*>::isForeground) }` —
it **deliberately stops as soon as only background-tagged events remain**, and every coroutine
launched in `TestScope.backgroundScope` carries the `BackgroundWork` marker. The tests construct
their ViewModels with `scopeOverride = backgroundScope` (correct practice) and then pump with
`advanceUntilIdle()`, which executed **nothing**: no init collectors, no `loadAndConnect`, no
`save`/`delete` coroutines, no reconnect jobs → `Idle` phases, `isLoading=true`, empty lists,
`rttMs` never updated. The README still claims "advanceUntilIdle runs all enqueued tasks" — the
tests modelled the docs, not the shipped behaviour.

**Proof:** an instrumented probe (temporary, now replaced) dumped:
`runCurrent: ran=true · advanceTimeBy(1): ran=true · advanceUntilIdle: ran=false (job still active, clock unchanged) · yield: ran=true`,
plus foreground `launch` (non-backgroundScope) succeeding on `advanceUntilIdle`.

**Fix (test-side, strengthening not weakening):** added `TestScope.pumpAll(horizonMs=600_000)` to
`app/src/test/java/com/freerdp/client/unit/TestSupport.kt` = `advanceTimeBy(horizon)` + `runCurrent()`.
`advanceTimeBy` has **no** foreground filter — it drains every scheduled event including cascades,
i.e. exactly the "run all pending work" semantics these tests were written against. Every
`advanceUntilIdle()` call in the four failing files was replaced with `pumpAll()` (38+11+12+7
occurrences). **Zero assertions were changed, removed or relaxed**; explicit `advanceTimeBy(…)`/
`runCurrent()` boundary steps (toolbar 3900/4100 ms, debounce 249/250 ms) were left byte-identical
and still pass — pumpAll is only used at "complete this logical operation" points, never between
boundary assertions.

**Classification:** test-infra bug. Production code (ViewModels, SessionViewModel, repository
collectors) was correct throughout — it simply never got scheduled under the old pump.

### 2.3 `ProfileEditorViewModelTest.invalidFieldsBlockSaveWithInlineErrors` — 1 failure (**test-bug**)
Line 79 read `assertTrue("still pristine", state.saved)` while a validation-blocked save must leave
`saved == false` — the assertion demanded the *opposite* of its own message and of correct product
behaviour (it could never pass). Fixed to
`assertFalse("still pristine — a blocked save must never mark the form saved", state.saved)` —
a strictly stronger, message-consistent assertion. Production (`ProfileEditorViewModel.save()`
early-return on errors) was verified correct.

### 2.4 `SessionGraphicsTest` pixel assertions — 2 failures (**test-config bug, environment**)
`graphicsUpdateComposites…` expected RED got BLACK (−16777216); `rapidUpdatesDropStaleFrames…`
expected BLUE got BLACK. `Bitmap.eraseColor` worked but `Canvas.drawBitmap` was a **no-op** —
Robolectric 4.14.1's default (legacy) graphics shadows don't rasterize Canvas blits. Production
compositing (`SessionViewModel.compositeFrame` → `Canvas.drawBitmap(tile, src, dst, null)`) is
correct on-device. The `nativeruntime-dist-compat` jar ships `native/windows/x86_64/robolectric-nativeruntime.dll`,
so real rasterization is available on this host. **Fix:** `@GraphicsMode(GraphicsMode.Mode.NATIVE)`
on `SessionGraphicsTest` — the pixel assertions now verify *actual* Skia rasterization (stronger,
on-device-faithful). Verified green on this Windows host.

### 2.5 `CoroutineDiagnosticsTest` probes — 4 failures (**test-bug: wrong library model**)
The bring-up probes asserted `backgroundScope.launch` runs on `advanceUntilIdle()` — false by
design in 1.9.0 (§2.2). The file was rewritten to encode the **actual, source-verified contract**:
- `backgroundScopeLaunchIsSkippedByAdvanceUntilIdleThenRunsOnPumpAll` (renamed from
  `backgroundScopeLaunchRunsOnAdvanceUntilIdle`) — asserts BOTH the skip (assertFalse after
  advanceUntilIdle) and the run (assertTrue after pumpAll): strictly stronger than the original.
- `stateFlowCollectorSeesUpstreamChanges` / `nestedLaunchChainRuns` / `runBlockingSeedDoesNotPoisonLaterPumps`
  — same assertions as before, pumped with `pumpAll()` instead of the non-functional pump.
- `plainTestScopeReceiverLaunchRuns` — unchanged (foreground semantics, passes as written).
Not part of app_product §3's inventory; none deleted.

### 2.6 Full accounting of the 34 baseline failures

| File | Failing | Root cause | Fix |
|---|---:|---|---|
| SessionViewModelTest | 14 | advanceUntilIdle skips backgroundScope (test-infra) | `pumpAll()` |
| ProfilesViewModelTest | 7 | same | `pumpAll()` |
| ProfileEditorViewModelTest | 5 | same | `pumpAll()` |
| ProfileEditorViewModelTest | 1 | `assertTrue` inverted vs message/behaviour | → `assertFalse` |
| SessionGraphicsTest | 1 | same as §2.2 (rtt collector never ran) | `pumpAll()` |
| SessionGraphicsTest | 2 | legacy Robolectric graphics no-op Canvas | `@GraphicsMode(NATIVE)` |
| CoroutineDiagnosticsTest | 4 | probe asserted non-existent library semantics | rewritten to true contract |
| **Total** | **34** | | |

**No production bug was found behind any of the 34 failures.** Production changes in this session
are integrations from other agents' handoffs (§3), not fixes to these failures.

---

## 3. Wiring checklist — latency_perf handoff §4 (items assigned to `:app`)

| # | Item | Status | Where / how |
|---|---|---|---|
| 1 | `RdpThreadIsolation.close()`/`awaitTermination()` on session end | **N/A — verified, documented** | `:app` constructs **no** `RdpThreadIsolation` (grep-verified): its threading is dispatcher-injection (`connectDispatcher` → `Dispatchers.IO` for engine connect/TOFU-gate; `viewModelScope` for UI). There are no owned pools to close, so `close()`/`awaitTermination()` would be a facade. Making the composite/decode path async would break the synchronous pixel-verified `SessionGraphicsTest` contract and add real-thread nondeterminism under virtual time. Rationale recorded as assumption (§6). |
| 2 | decoder → `FramePacer.onFrameDecoded` | ✅ already wired | `SessionViewModel.engineListener.onGraphicsUpdate` → `compositeFrame(...)` → `framePacer.onFrameDecoded(currentFrame())` (`SessionViewModel.kt:288-291`) |
| 3 | Choreographer → `FramePacer.acquireFrameForVsync(...)` | ✅ **FIXED this session** | `RemoteCanvasView.vsyncCallback.doFrame` now calls `pacer.acquireFrameForVsync(frameTimeNanos)` (was `hasPendingFrame` + `acquireFrameForRendering()`, which bypassed VSYNC coalescing/monotonic-timestamp/stale-drop logic). `RemoteCanvasView.kt:125-142` |
| 4 | `NetworkStateMonitor.startMonitoring()/stopMonitoring()` on session start/stop | ✅ already wired | adapter `AndroidNetworkMonitor.start/stop → delegate.startMonitoring/stopMonitoring` (`session/NetworkMonitor.kt`); started in `SessionViewModel.loadAndConnect` (auto-reconnect profiles), stopped in `confirmExit` + `terminate`; asserted live by `networkCallbacksDriveWaitingAndFastPathRecovery` (`isActive == true`) |
| 5 | `AdaptivePresetSwitcher.evaluate(...)` — never `determinePreset` directly | ✅ **WIRED this session** | `AppContainer.presetSwitcher` (container-scoped, survives rotation) → injected into `SettingsViewModel` (`presetSwitcher: AdaptivePresetSwitcher = AdaptivePresetSwitcher()` default keeps the 2-arg test ctor valid) → `recommendedPreset()` now calls `presetSwitcher.evaluate(quality, battery)`. `determinePreset` call removed from `:app` main (grep-verified; only a warning comment remains). |
| 6 | `AutoReconnectManagerImpl.shutdown()` on session teardown (**REQUIRED leak guard**) | ✅ **WIRED this session** | `SessionViewModel.terminate()` calls `reconnectManager.shutdown()` before `rootJob.cancel()` (`SessionViewModel.kt:605-616`) — the manager's engine-state observer runs in a component-owned detached scope that `rootJob.cancel()` cannot reach. Reached in production via `AppNavHost.onExitConfirmed → container.releaseSessionViewModel() → vm.terminate()` and via `onCleared()`; `shutdown()` is idempotent. |
| 7 | `DynamicLayoutListener` default 250 ms debounce | ✅ verified | `DEFAULT_DEBOUNCE_DELAY_MS = 250`; `SessionViewModel(layoutDebounceMs = 250L)`; `AppContainer` doesn't override; boundary behaviour (0 @ 249 ms, 1 @ 250 ms, rapid-flip coalescing) asserted by `viewportChangesAreDebounced250msAndCoalescedToFinalDimensions` |
| 8 | Keep `FramePacer(onFrameDroppedCallback = { … })` named-arg style | ✅ verified | `AppContainer.kt:74` and `TestSupport.kt` (sessionHarness) both use the named argument; trailing-lambda call sites unaffected |

---

## 4. Integration items — session_logic handoff §4 (items assigned to `:app`)

1. **`MacroAction` 7 constants / exhaustive `when`:** grep-verified there is **no** exhaustive
   `when (macro)` anywhere in `app/src/main` (`onMacro` forwards to
   `ModifierStateMachine.triggerMacro`), so nothing could fail to compile or silently mis-handle
   `CTRL_C`/`CTRL_V`. Additionally wired the macros into the product UI: the modifier bar now has
   **Ctrl+C** and **Ctrl+V** `MacroButton`s next to Ctrl+Alt+Del / Alt+Tab
   (`SessionScreen.kt:667-670`).
2. **`QuickActionToolbarFSM.pin()/unpin()` — DONE:**
   - `SessionViewModel.toolbarPinned: StateFlow<Boolean>` + `toggleToolbarPin()` (`SessionViewModel.kt:628-634`).
   - SessionScreen: PushPin toggle in the expanded quick-action toolbar with state-dependent
     `contentDescription` ("Keep session controls open" / "Stop keeping session controls open").
   - Covered by a new test `toolbarPinKeepsToolbarOpenPastAutoCollapseTimeout` (pin survives 9 s
     > 4 s window; unpin re-arms; collapses at 4100 ms).
3. **`onSessionLost()` on session disconnect — DONE:** called (a) in
   `processSettledDisconnect` after the generation/exit guards (every settled disconnect collapses
   the toolbar deterministically, pin does not block it, pin preference preserved), (b) in
   `confirmExit`, (c) in `terminate`. Covered by new test
   `sessionLossCollapsesTheToolbarDeterministically` (pinned toolbar collapses after a drop).
4. `ModifierStateMachine.tryTransition` — noted; not required by any `:app` UI (fire-and-forget
   taps already surface state through `modifierStates`).

---

## 5. Test-inventory preservation vs `app_product/handoff.md` §3

All files, all cases survive; nothing deleted or weakened (auditor cross-check):

| File | §3 claim | Actual @Tests (baseline → now) | Assertions |
|---|---|---|---|
| SessionPhaseReducerTest | 10 | 10 → 10 | untouched |
| NavigationModelTest | 7 | 7 → 7 | untouched |
| ProfileValidationTest | 8 | **7 → 7** (handoff miscount; file untouched by any agent this session) | untouched |
| ProfilesViewModelTest | 7 | 7 → 7 | untouched; only pump swapped |
| ProfileEditorViewModelTest | 7 | 7 → 7 | 1 assertion corrected (`assertTrue`→`assertFalse`, inverted vs its own message — §2.3); rest untouched |
| SessionViewModelTest | 16 | 20 → **22** (+2 new pin/session-loss tests) | all pre-existing assertions untouched; only pump swapped |
| ClipboardSyncTest | 4 | 4 → 4 | untouched |
| SessionGraphicsTest | 5 | 5 → 5 | pixel assertions untouched and now *actually rasterized* (NATIVE graphics) |
| SettingsPersistenceTest | 6 | **5 → 5** (handoff miscount; file untouched) | untouched |
| AppContainerTest | 7 | 7 → 7 | untouched |
| CoroutineDiagnosticsTest | not in §3 | 5 → 5 | 1 renamed + corrected to true library contract; 4 kept/strengthened (§2.5) |
| **unit total** | 57 claimed | **84 → 86** | e2e 74 untouched ⇒ grand total **155** |

---

## 6. Decisions & assumptions (made without asking)

1. **Pump semantics:** fixed the tests' *scheduler pump*, not production — production behaviour was
   never observable under the old pump. `pumpAll` horizon 600 s ≫ every pending virtual delay
   (settle 250 ms, backoff ≤100 ms×3, toolbar 4 s, debounce 250 ms); absolute virtual-clock jumps
   are harmless (all timing in these tests is relative; e2e already uses `advanceTimeBy(600_000)`).
2. **`advanceUntilIdle` semantics** are documented inline in `TestSupport.pumpAll` and
   `CoroutineDiagnosticsTest` so the next agent does not "simplify" back to the broken pump.
3. **Graphics:** `@GraphicsMode(NATIVE)` scoped to `SessionGraphicsTest` only — deliberately NOT a
   global `robolectric.properties`/system-property change, to avoid perturbing the green e2e suite
   (whose files I must not edit).
4. **One test assertion corrected** (`invalidFields…` assertTrue→assertFalse) — justified in §2.3;
   everything else preserved verbatim.
5. **Baseline counts differ from the task brief** (158/34 actual vs 153/30 briefed) — briefed
   figures were stale; all 34 real failures fixed (§2.6).
6. **`RdpThreadIsolation` = N/A for `:app`** with rationale (§3 item 1). If a future agent wants
   true thread isolation in `:app`, the entry point is `AppContainer` owning one and routing
   `doConnect`/teardown through its dispatchers — but that trades away virtual-time determinism
   for paths the tests assert synchronously.

## 7. Risks

1. **Concurrent Gradle runs:** `native_core` (and others) run Gradle in parallel — plain background
   invocations occasionally failed silently (empty log, exit 1) under lock contention; foreground
   runs retried fine. All headline results above are from foreground runs.
2. **Robolectric NATIVE graphics is a native-lib dependency** (`robolectric-nativeruntime.dll`,
   present in `nativeruntime-dist-compat-1.0.16`): if a future host lacks the DLL/VC runtime,
   `SessionGraphicsTest` errors at load — symptom is an `UnsatisfiedLinkError`, not a silent
   assertion drift. Legacy mode would silently no-op `Canvas.drawBitmap` again (never use it here).
3. **`pumpAll` + future delayed work > 600 s** would need the horizon raised (fails loudly as a
   timeout-style assertion, not silently green).
4. **Broadcast-driven `AdaptivePresetSwitcher.evaluate`** is wired via the Settings chip only; a
   full connectivity/battery broadcast receiver feeding the switcher (and applying its active
   preset to sessions) remains a possible product enhancement — no checklist item demands it, and
   wiring an unconsumed result would be dead code.
5. e2e suite remains owned by `e2e_tests` — untouched; any future edit to `e2e/**` or to
   `SessionViewModel`'s public flow could interact with its exact-emission-list assertions.
