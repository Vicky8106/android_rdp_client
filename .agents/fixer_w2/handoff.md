# fixer_w2 Handoff — Wave-2 Fix Owner (Android FreeRDP Mobile Client)

**Agent:** `fixer_w2` (sole code-owning agent after wave-2 review)
**Date:** 2026-09-23 (local)
**Status: DONE — full gate GREEN twice after all edits: 497/497 tests, 0 failures / 0 errors / 0 skipped across 44 suites; APK rebuilt fresh (17,205,283 B).**
Sources read first: `reviewer_w2/handoff.md` (APPROVE-WITH-FINDINGS), `challenger_w2/handoff.md` (CHALLENGE-PASS), `integrator_w2/handoff.md` (gate baseline 487/487).

**Every item on the fix list is complete.** No test was removed, renamed-away, or weakened; assertions were strengthened or added. `e2e/**` (74 tests), docs (`*.md` outside this file), and reviewer/challenger/integrator handoffs were **not touched**.

---

## 1. Finding-by-finding status

| # | Finding | Source | Status | Files |
|---|---|---|---|---|
| MAJOR-1 | Teardown steps 2/3 recorded without performing the action (fake audit trail) | reviewer_w2 | **FIXED** — step 2 now invokes a real abort (injectable `abortInFlightConnect`, default = `engine.disconnect()` — the only transport-abort `IRdpEngine` exposes); step 3 records only when a join/drain action exists **and completed**; tests assert behavior (invocation, order, once) | `AutoReconnectManagerImpl.kt`, `AutoReconnectManagerTest.kt` |
| MAJOR-2 / S1 | Fallback vault stored AES-GCM master key base64-plaintext beside ciphertext (`vault_master_key_b64`) | reviewer_w2 + challenger S1 | **FIXED** — AndroidKeyStore non-exportable key when the platform provider exists; terminal (JVM) fallback = never-persisted in-memory key + degradation marker entry + `vaultDegraded: StateFlow<Boolean>` + residual-risk KDoc; legacy plaintext key actively purged; `CredentialStore` contract byte-for-byte intact | `KeystoreCredentialStore.kt`, `KeystoreCredentialStoreTest.kt` |
| M3 (survivor) | IV/nonce reuse would ship silently — no uniqueness test | challenger_w2 | **KILL-COVERAGE ADDED** — 100-encryption IV uniqueness test (fails if IV randomization removed) | `KeystoreCredentialStoreTest.kt` |
| M4 (survivor) | "Stale timer" test exercised job cancellation, not the generation guard | challenger_w2 | **FIXED** — new test stages a timer that already completed `delay()` racing a collapse+re-expand; only `generation == timerGeneration` prevents the collapse (removing the guard fails the test) | `QuickActionToolbarFSM.kt` (staging seam), `QuickActionToolbarTest.kt` |
| F3 (minor) | Exiting touchpad mode mid-drag double-transforms button-up | reviewer_w2 | **FIXED** — safety release now emits button-up at the virtual cursor (desktop basis = button-down basis); regression test fails on old code under a non-identity transform | `MouseController.kt`, `MouseControllerTest.kt` |
| F4 (minor) | `onPanEnd` dropped on the multi-touch-latch release path | reviewer_w2 | **FIXED** — latch branch dispatches `onPanEnd()` iff a pan was active; latch tap-suppression untouched; 2 new tests (pan-end-after-latch; no-pan latch lift emits no panEnd) | `GestureDisambiguationEngine.kt`, `GestureDisambiguationTest.kt` |
| F6 (minor) | `testLongPressTriggersRightClickAndIsAbortedOnMove` asserted neither claim | reviewer_w2 | **FIXED** — renamed to `testLongPressFiresOnceAbortedByMoveAndSuppressesTapOnRelease` and strengthened: fires-exactly-once, **move-beyond-slop aborts the pending long press** (asserted), release-after-long-press emits no tap. Right-click dispatch is not observable from `feature-mouse` (mapping lives app-side in `RemoteCanvasView.onLongPress → handleRightClick`, cited in a comment) — that part of the old name was unclaimable at this layer, so it was renamed rather than faked | `GestureDisambiguationTest.kt` |
| Product gap 1 | First-run dead-end at error 1001 until the demo switch is found | challenger_w2 | **FIXED** — failure card gains a prominent one-tap **"Enable demo engine & retry"** button when `code == 1001 && !vm.isDemo`: persists `AppSettingsRepository.demoEngine=true`, releases the native-engine session VM, and rebuilds/re-enters the session on the demo engine so the retried connect actually succeeds; existing friendly hint kept | `AppContainer.kt`, `AppNavHost.kt`, `SessionScreen.kt`, `AppContainerTest.kt` |

Not chased (per DO-NOT list, left as documented follow-ups): `FreeRdpFlagMapping` cycle, `SO_SNDBUF`, `.so` packaging, reviewer minors F5/F7–F12, challenger S2/S3, resilience items §5.

---

## 2. Fix descriptions (design decisions + why)

### MAJOR-1 — honest 5-step teardown (`AutoReconnectManagerImpl.kt`)

- New constructor param **`abortInFlightConnect: (suspend () -> Unit)? = null`** (after `renderLoopDrainAction`; all call sites use named args — verified).
- **Step 2** (`teardownStep2_abortNativeConnect`, now suspend): invokes the caller-registered abort when present; otherwise the **default real abort** = `engine.disconnect()` on `ioDispatcher`. `IRdpEngine` (contract, PROJECT.md §4) has *no* dedicated connect-cancel API — `disconnect()` is the only transport-abort it exposes, is idempotent by contract (stress-tested in `:core-rdp`), and step 4 repeats it as part of deallocation. Exceptions suppressed (mirrors step 4; `CancellationException` rethrown). The step is recorded **only after the abort action has been invoked** — never speculatively.
  - Why a *real default* instead of a no-op: e2e **Tier4 scenario4** (uneditable) asserts history `== [1,2,3,4,5]` with only `renderLoopDrainAction` registered — a null/skip-by-default step 2 would break it. A default wired to a genuine engine abort satisfies both the brief ("wired to what a real teardown aborts") and the frozen e2e contract.
- **Step 3** (`teardownStep3_waitForRenderLoopJoin`): returns without recording when `renderLoopDrainAction == null`; records only when `withTimeoutOrNull(1500) { drain() }` **completes** (timeout → no record; drain throws → no record; teardown continues).
- `execute5StepTeardown` KDoc now documents the audit-honesty contract.

### MAJOR-2 / S1 — vault key custody (`KeystoreCredentialStore.kt`)

Layered custody (full threat model in class KDoc):
1. **Primary:** `EncryptedSharedPreferences` + AndroidKeyStore `MasterKey` (unchanged).
2. **Fallback, Keystore provider available:** non-exportable AES-256 key generated in `AndroidKeyStore` (alias `<masterKeyAlias>_fallback_`, `KeyGenParameterSpec` GCM/NoPadding, 256-bit) — *even though EncryptedSharedPreferences itself failed*; only ciphertext reaches the prefs file. (Keystore keys generate their IV internally on encrypt; decrypt receives the stored IV.)
3. **Terminal fallback (JVM/Robolectric — no AndroidKeyStore provider):** process-local **in-memory** key, **never written anywhere**. The legacy `vault_master_key_b64` entry is **purged** on vault init (never read); the backing store is stamped with an unmistakable marker entry `vault_degraded_mode = "DEGRADED_VAULT_V1: …"`; `vaultDegraded: StateFlow<Boolean>` on the store flips `true` the moment the fallback engages (log-free — repo has zero `Log.*` by contract).
- Marker is a **separate prefs entry**, not a value prefix: secret values remain pure `base64(iv‖ciphertext)`, so raw-entry parsing — including e2e **Tier2 tamper tests** (`Base64.decode(raw)` on `secret_*`) — is unaffected. This was a hard constraint from uneditable e2e.
- Residual risk documented in KDoc (§5 below).
- **Contract:** `CredentialStore` interface untouched (4 methods byte-for-byte); all additive members are on the concrete class.

### M4 — toolbar generation guard (`QuickActionToolbarFSM.kt`)

- Pure virtual time **cannot** stage this race: cancelling a job whose `delay()` resume is queued makes the coroutine resume with `CancellationException` — the post-delay code never runs, so the guard is never consulted (that is exactly why the old test passed with the guard removed). The window exists only *between* `delay()` returning and the `synchronized` decision, and there is no suspension point in it.
- Added constructor seam **`onTimerExpired: (() -> Unit)? = null`**, invoked after `delay()` and before the monitor. Production passes `null` (default). The new test uses it to issue `collapse()+expand()` while the stale timer is already past its delay — cancellation of the stale job is then powerless (no suspension point left), so **only `generation == timerGeneration` keeps the re-expanded toolbar open**. Guard removed ⇒ toolbar collapses ⇒ test fails ⇒ M4 killed. (Cross-thread monitor-staging was rejected as deadlock-prone against the test scheduler's internal lock.)

### feature-mouse

- **F3:** `setTouchpadMode(false)` mid-drag no longer routes the desktop-coordinate cursor through `resolveTargetCoordinates()` (which took the absolute branch after the flag flipped) — it emits `LEFT_BUTTON_UP` at `cursorX/Y` directly, same basis as button-down. New regression test uses `setTransform(2.0, 100, 50)`: correct up = `(660,490)`, old double-transform = `(280,220)` — asserted both so the failure mode is explicit.
- **F4:** latch `ACTION_UP` branch captures `wasPanning` before reset and dispatches `onPanEnd()` iff it was true. Taps stay suppressed; non-pan latch lifts emit nothing (new negative test).
- **F6:** see table above — real assertions implemented (fires-once + move-abort + no-tap); the untestable-at-this-layer "right-click" claim replaced by an accurate name with an inline pointer to the app-side mapping.

### App UX — one-tap 1001 recovery

- `AppContainer.enableDemoEngineAndRestart()` = `settings.setDemoEngine(true)` (persisted via existing `persist()`) + `releaseSessionViewModel()` (terminates the cached native-engine VM).
- `AppNavHost` Session branch: `sessionEpoch` counter; the action calls the container method then bumps the epoch. Epoch keys both `remember(profileId, epoch) { container.sessionViewModel(...) }` (rebuilds VM on the demo engine) and a `key(epoch) { SessionScreen(...) }` wrapper (fresh composition ⇒ its `LaunchedEffect { ensureStarted }` runs against the new VM ⇒ **connect retried**).
- `SessionScreen` failure card: new optional param `onEnableDemoEngineAndRetry`; button rendered only when `failure.code == SessionErrorCodes.NATIVE_UNAVAILABLE (1001) && !vm.isDemo && callback != null`, placed above the existing Retry; **existing hint text untouched**.
- Unit test (unit package): `AppContainerTest.enableDemoEngineAndRestartPersistsSettingRebuildsSessionAndRetriesConnect` — asserts (1) setting flipped in-memory **and** re-loadable from disk, (2) old VM `reconnectManager.isShutDown`, (3) fresh VM ≠ old, `isDemo==true`, engine factory called with `demo=true` (exactly `[false, true]`), (4) retried `ensureStarted` reaches `SessionPhase.Connected(demo=true)`.

---

## 3. Tests: every adaptation and addition (old → new)

**No test was deleted. No assertion weakened. 15 pre-existing reconnect tests: only ONE adapted (below); the other 14 are byte-identical and green.**

### feature-telemetry: 118 → **120** (AutoReconnectManagerTest 15 → 17)

| Test | Change |
|---|---|
| `testFiveStepTeardownExecutedInOrder` | **Adapted (same name, strengthened).** Old: asserted step-list `[1..5]` + `renderLoopDrained` + `Idle` from history strings only. New: keeps ALL of those **plus** behavioral proof — an injected abort action and drain action must each be invoked **exactly once** and interleaved exactly as `["step1","abort","step2","drain","step3","step4","step5"]`; history must carry ordered prefixes `Step 1…Step 5`; `abortInvocations == 1`. Documented in-test as the MAJOR-1 rewrite. |
| `testDefaultAbortActionReallyDisconnectsEngineDuringStepTwo` | **NEW.** Counts the engine's `onDisconnected` callback: default construction ⇒ **2 real disconnects** (step-2 abort + step-4 deallocate) at exactly `["step1","engineDisconnected","step2","engineDisconnected","step4","step5"]`; a fake step 2 would yield 1. Also asserts **no `Step 3` claim** exists when no drain action is registered. |
| `testStepThreeNotRecordedWhenRenderLoopJoinNeverCompletes` | **NEW.** Registered drain that never completes ⇒ the 1.5 s budget times out ⇒ steps `[1,2,4,5]` only, no `Step 3` claim, teardown still finishes and transitions to `Idle`. |
| other 14 | Unchanged (backoff ×6, network ×4, pause/resume, shutdown, cancel, zero-delay). |

Adaptation rationale for the one changed test: the old version's *only* subject was the recorded strings — exactly the fake-behavior assertion MAJOR-1 condemns. It was rewritten to assert actions-invoked-in-order-once **while keeping** every original assertion (ordering contract, drain invoked, Idle transition).

### feature-session: 82 → **86**

| Test | Change |
|---|---|
| KeystoreCredentialStoreTest 17 → **20** | 17 existing **unchanged** (round trips, tamper, plaintext, unicode, isolation…). **+3 NEW:** `testFallbackVaultRaisesDegradedFlagInsteadOfDowngradingSilently` (flag raised on fallback + vault still functional), `testFallbackVaultNeverPersistsPlaintextMasterKeyAndMarksDegradation` (`vault_master_key_b64` ABSENT; `DEGRADED_VAULT_V1` marker entry present; a planted legacy plaintext key is purged on init; round trips work), `testNonceIvIsUniqueAcrossOneHundredEncryptions` (**M3:** 100 saves ⇒ 100 distinct 12-byte IVs + decryptability). |
| QuickActionToolbarTest 14 → **15** | 14 existing **unchanged** (incl. the cancellation-path `testStaleTimerCannotCollapseReExpandedToolbar`, kept as-is). **+1 NEW:** `testGenerationGuardBlocksTimerThatCompletedDelayBeforeReExpand` — see §2/M4; asserts the expiry hook ran exactly once, the toolbar **stays EXPANDED** after the stale timer's decision point, then collapses exactly on the fresh timer's deadline. |
| ModifierStateMachine/ProfileRepository/ScancodeTranslator (51) | Unchanged. |

### feature-mouse: 32 → **35**

| Test | Change |
|---|---|
| MouseControllerTest 10 → **11** | 10 existing unchanged (incl. `testSafetyReleaseWhenExitingTouchpadModeDuringDrag`, which passes on the fix and does NOT mask it — identity fixture). **+1 NEW:** `testExitingTouchpadModeMidDragReleasesAtVirtualCursorWithSingleTransform` (fails on old code: expects `(660,490)`, old produced `(280,220)`). |
| GestureDisambiguationTest 7 → **9** | 6 unchanged; **1 renamed+strengthened** (`testLongPressTriggersRightClickAndIsAbortedOnMove` → `testLongPressFiresOnceAbortedByMoveAndSuppressesTapOnRelease`, old→new documented in-test, F6 rationale in §2); **+2 NEW:** `testPanEndFiresAfterLatchReleaseWhileLatchStillSuppressesTap` (panEnd==1 after latch release, taps still 0, latch resets) and `testLatchReleaseWithoutPanDoesNotEmitPanEnd` (no invented panEnd). |
| CoordinateTransformer (8), FloatingMouseOverlay (7) | Unchanged. |

### app: 160 → **161** (e2e 74 untouched; unit 86 → 87)

| Test | Change |
|---|---|
| e2e Tier1/2/3/4 (35/26/8/5 = **74**) | **ZERO edits** — verified green in both gate runs (constraints they impose on the fixes are analyzed in §2/MAJOR-1 and §2/MAJOR-2). |
| AppContainerTest 7 → **8** | 7 unchanged. **+1 NEW:** `enableDemoEngineAndRestartPersistsSettingRebuildsSessionAndRetriesConnect` (the 1001 one-tap action). |
| other unit suites (79) | Unchanged. |

**Module table (old → new):**

| Module | Old | New | Δ |
|---|---:|---:|---:|
| `:app` (74 e2e + 87 unit) | 160 | **161** | +1 |
| `:core-rdp` | 95 | **95** | 0 |
| `:feature-mouse` | 32 | **35** | +3 |
| `:feature-session` | 82 | **86** | +4 |
| `:feature-telemetry` | 118 | **120** | +2 |
| **TOTAL** | **487** | **497** | **+10** |

---

## 4. Exact gate commands + counts

Environment for every invocation (PowerShell):

```powershell
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot"
cd C:\Users\Administrator\teamwork_projects\android_rdp_client
```

| # | Command | Result |
|---|---|---|
| 1 | `.\gradlew.bat testDebugUnitTest --rerun-tasks --console=plain` (run 1) | **BUILD SUCCESSFUL, exit 0**, 145/145 tasks executed, **497/497, 0 failures/errors/skipped** |
| 2 | `.\gradlew.bat assembleDebug --rerun-tasks --console=plain` | **BUILD SUCCESSFUL**, 153/153 tasks executed → `app\build\outputs\apk\debug\app-debug.apk` **17,205,283 bytes @ 2026-09-23 04:43:23** (fresh, newer than run-1 start) |
| 3 | `.\gradlew.bat testDebugUnitTest --rerun-tasks --console=plain` (run 2, pristine full re-run after ALL edits + APK) | **BUILD SUCCESSFUL, exit 0**, 145/145 executed, **497/497 again** (identical table below) |

Counts parsed from `*/build/test-results/testDebugUnitTest/TEST-*.xml` after **both** runs (byte-identical):

| Module | Suites | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|---:|
| `:app` (Tier1 35 + Tier2 26 + Tier3 8 + Tier4 5 = 74 e2e; unit 87) | 15 | **161** | 0 | 0 | 0 |
| `:core-rdp` | 10 | **95** | 0 | 0 | 0 |
| `:feature-mouse` (Coord 8, Overlay 7, Gesture 9, Mouse 11) | 4 | **35** | 0 | 0 | 0 |
| `:feature-session` (Keystore 20, Modifier 20, Profile 17, QuickAction 15, Scancode 14) | 5 | **86** | 0 | 0 | 0 |
| `:feature-telemetry` (AutoReconnect 17, others 103) | 10 | **120** | 0 | 0 | 0 |
| **PROJECT TOTAL** | **44** | **497** | **0** | **0** | **0** |

Both runs were executed with no other agent editing code; no transient failures occurred on either run (first-run-build also green).

---

## 5. Residual risks & assumptions

1. **Double disconnect per full teardown (MAJOR-1 behavior delta):** step 2 (abort) + step 4 (deallocate) each call `engine.disconnect()`. Idempotent by engine contract; duplicated `onDisconnected` listener events already occur today (external drop + step 4). Every count-asserting e2e listener check (`Tier1 disconnectCount==1`) uses no manager and is unaffected — verified by reading all e2e assertions that mention `disconnectCount`/`executedTeardownHistory`; both full gates confirm.
2. **e2e-visible history shape changed only where honesty demanded:** with no drain action registered, history is `[1,2,4,5]` per teardown (no phantom `Step 3`). `Tier2` asserts `size >= 5` **total** (24 entries) and `Tier4` registers a drain — both green unedited. If a future consumer assumes `Step 3` always exists, it must register a drain.
3. **JVM terminal vault cannot survive process death** (in-memory key): reads fail closed to `null` → password re-prompt. This is *intended* fail-closed behavior replacing plaintext-at-rest; documented in KDoc. On real devices layer 2 (AndroidKeyStore) provides persistence — but like the primary path, it has **no androidTest coverage on this machine** (pre-existing risk R1 from session_logic, unchanged).
4. **Legacy ciphertexts** written under pre-hardening plaintext keys become unreadable after upgrade (key deliberately purged) → one-time re-prompt. Assumption: acceptable fail-closed migration; no test depends on cross-version decryption.
5. **`vaultDegraded` is surfaced but not yet rendered:** no UI warning banner consumes it (the brief asked for the flag/callback "so the app *can* warn"). Wiring a banner is a product follow-up.
6. **Compose wiring is compile-verified only:** the button → `AppNavHost` epoch → VM rebuild path has no Compose UI-test infrastructure in this repo; its behavior is covered by the container-level unit test (setting persisted, VM rebuilt on demo engine, connect retried to `Connected(demo=true)`). Assumption: `key(epoch)` recomposition semantics (standard Compose) correctly re-run `LaunchedEffect(Unit)`.
7. **`onTimerExpired` is a test seam in production code** (default `null`, documented). Alternative cross-thread staging was rejected as deadlock-prone against kotlinx test scheduler internals. M3/M4 kill status is by test-design analysis (each new test fails with the mutant applied); no mutation campaign was re-executed — that protocol belongs to the challenger role.
8. **`sessionEpoch` in `AppNavHost` uses plain `remember`:** leaving the session route and returning resets the epoch — harmless, since exit releases the VM anyway; the epoch only needs to survive within one composed Session branch.

---

## 6. Scope compliance

- **Edited (14 files):** the 6 main sources + 6 test files listed in §1, plus `app` main (`AppContainer.kt`, `AppNavHost.kt`, `SessionScreen.kt`) and `app` unit test (`AppContainerTest.kt`).
- **NOT edited:** `e2e/**` (74 green), `README.md`/`PROJECT.md`/`docs/**`, `ORIGINAL_REQUEST.md`, `.agents/{reviewer_w2,challenger_w2,integrator_w2}/**`, gradle files, all other modules' sources/tests.
- **Documented follow-ups untouched:** `FreeRdpFlagMapping` dependency cycle, `SO_SNDBUF`, native `.so` packaging — still documented, not chased.
- `PROJECT.md` §Interface Contracts: all 5 contract interfaces unchanged (`CredentialStore` verified member-for-member; new members are additive on the concrete `KeystoreCredentialStore`; `AutoReconnectManager` interface unchanged — `abortInFlightConnect` is an `AutoReconnectManagerImpl` constructor param).
