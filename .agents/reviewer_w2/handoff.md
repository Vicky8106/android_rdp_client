# reviewer_w2 Handoff — Independent Verification (Android FreeRDP Mobile Client, restart wave 2)

**Agent:** `reviewer_w2` (independent code reviewer — review & verify only; **no source, docs, or gradle files were modified**; write scope respected: `.agents/reviewer_w2/**` only)
**Date:** 2026-09-23, local 03:42–03:55
**Verdict: APPROVE-WITH-FINDINGS** (0 BLOCKER, 2 MAJOR, 10 MINOR — all findings are documentation/evidence-quality issues or edge-case defects in non-primary paths; every module passes 100% on fresh independent execution against the current source state)

---

## 1. Verdict & summary

| | |
|---|---|
| **Verdict** | **APPROVE-WITH-FINDINGS** |
| Fresh total (5 modules, all `--rerun`, all mine) | **487 / 487 tests, 0 failures, 0 errors, 0 skipped** |
| Contract check (PROJECT.md §Interface Contracts, 5 interfaces) | **PASS — all 5 match member-for-member** (cosmetic doc drift only) |
| M2 gap (`feature-mouse`, never re-verified since worker_m2) | **CLOSED — 32/32 fresh, twice (03:42:42 and 03:53:20); code reviewed line-by-line; genuinely implemented, no tautologies** |
| Blocking issues | None |
| Why not full APPROVE | 2 MAJOR findings: (MAJOR-1) the celebrated "5-step leak-free teardown" in `AutoReconnectManagerImpl` records steps 2/3 as executed when they perform no action (fake audit trail asserted by tests); (MAJOR-2) `KeystoreCredentialStore` silently downgrades to a vault whose AES key sits in plaintext prefs beside the ciphertext if `EncryptedSharedPreferences` init ever fails on a real device |

---

## 2. Fresh test counts (my own runs, `--rerun`, PowerShell + JAVA_HOME as specified)

All runs: `.\gradlew.bat :<module>:testDebugUnitTest --rerun --console=plain`, counts parsed from `build/test-results/testDebugUnitTest/TEST-*.xml`.

| Module | Tests | Failures | Errors | Skipped | XML mtime (my run) | Newest source mtime (covered) | Claimed by wave-1 | Match? |
|---|---:|---:|---:|---:|---|---|---|---|
| `:feature-mouse` (M2 gap) | **32** | 0 | 0 | 0 | 03:42:42, re-verified 03:53:20 | 00:42:27 (`CoordinateTransformer.kt`) | 32/32 (never re-verified) | ✅ |
| `:core-rdp` | **95** | 0 | 0 | 0 | 03:43:28 | 03:24:16 (`LibFreeRDP.java`) | 95/95 | ✅ |
| `:feature-session` | **82** | 0 | 0 | 0 | 03:43:35 (run 1), 03:48:04 (final re-run) | 01:42:19 at final run | 82/82 | ✅ |
| `:feature-telemetry` | **118** | 0 | 0 | 0 | 03:43:40, final re-run 03:51:56 | 02:06:05 at final run | 118/118 | ✅ |
| `:app` (incl. e2e 74) | **160** | 0 | 0 | 0 | 03:43:56 | 03:39:00 (`CoroutineDiagnosticsTest.kt`) | **155** (app_finish) | ⚠️ count differs — see F10 |
| **TOTAL** | **487** | **0** | **0** | **0** | | | claimed 482 | |

`:app` breakdown (my XML parse): e2e **74** (Tier1 35 + Tier2 26 + Tier3 8 + Tier4 5) + unit **86** (SessionViewModelTest 22, SessionPhaseReducer 10, AppContainer 7, Navigation 7, ProfileValidation 7, ProfilesVM 7, ProfileEditorVM 7, CoroutineDiag 5, SessionGraphics 5, SettingsPersistence 5, ClipboardSync 4) = **160**.

### 2.1 Concurrent-edit incidents (integrator active during my window — per protocol: re-ran once, timestamps recorded, only then judged)

1. **`:feature-session` transient FAILURE (03:46–03:47)** — run 2: `BUILD FAILED`, "82 tests completed, 5 failed", all in `ModifierStateMachineTest`. Coincided with integrator edits: `ModifierStateMachine.kt` mtime 03:45:28, `KeystoreCredentialStore.kt` mtime 03:47:20, `QuickActionToolbarFSM.kt` mtime 03:47:47. **Re-run once (started 03:47:57): BUILD SUCCESSFUL, 82/82 at 03:48:04, 0 failures.** Verdict: transient mid-edit state, NOT a real defect. (Note: an intermediate XML-directory read during this window showed another agent's partial results — test-results dirs were being rewritten concurrently by other agents throughout.)
2. **`:feature-telemetry` transient FAILURE (03:49–03:50)** — run: "118 tests completed, 3 failed", `FramePacerTest.testStaleFrameIsDroppedNotBlitted / testVsyncStaleFrameDroppedAtCallback / testStaleDropPreservesConservationInvariant`. Coincided with `FramePacer.kt` mtime 03:49:02 (edit later reverted; final newest telemetry src = 02:06:05). **Re-run once: blocked by known Gradle lock contention (`:feature-telemetry:bundleLibRuntimeToJarDebug` — `classes.jar being used by another process`, also documented by session_logic risk 3); re-run again: BUILD SUCCESSFUL, 118/118 at 03:51:56, 0 failures.** Verdict: transient concurrent-edit/lock issues, NOT a real defect in the final state.
3. **`:feature-mouse` source edited 03:52:30** (`GestureDisambiguationEngine.kt`) after my first green run, then reverted (final newest mouse src = 00:42:27). **Re-run: BUILD SUCCESSFUL 32/32 at 03:53:20.** Verdict: transient.

All three incidents were resolved green on re-run with sources stable at check time. Final-state counts in the table above post-date every source edit observed in my window (final freshness check at 03:53).

---

## 3. Contract review vs PROJECT.md §Interface Contracts — **PASS (5/5)**

Byte-level comparison of PROJECT.md lines 101–188 against the live sources:

| Interface | Source file | Result |
|---|---|---|
| `IRdpEngine` | `core-rdp/src/main/java/com/freerdp/core/engine/IRdpEngine.kt` | ✅ All 11 members identical (properties, `connect/disconnect` suspend, all 7 methods with exact parameter names/types/order). Doc block carries an unused `import android.graphics.Bitmap` not present in source — cosmetic doc drift only, zero signature impact. |
| `RdpEventListener` | `core-rdp/.../engine/RdpEventListener.kt` | ✅ Byte-identical (7 methods, incl. `onCertificateVerification(fingerprint, host): Boolean`). |
| `MouseController` | `feature-mouse/.../mouse/MouseController.kt` (lines 12–25) | ✅ Byte-identical (9 methods); implementation `DefaultMouseController` is additive below the interface. |
| `CredentialStore` | `feature-session/.../security/CredentialStore.kt` | ✅ All 4 contracted methods identical; 3 additive `Result`-based convenience defaults present (pre-existing, documented by session_logic) — additive-only, not a deviation. |
| `AutoReconnectManager` | `feature-telemetry/.../reconnect/AutoReconnectManager.kt` | ✅ Byte-identical (StateFlow property + 6 methods); file additionally defines `ReconnectState` states (additive; `Failed(exhausted, reason="")` etc.) — not a contract deviation. |

**Result: no signature deviations. Matches the "other agents claimed none" claim.**

---

## 4. Code review of wave-1 changes — findings

Scope of reading: all 5 `feature-mouse` main sources + all 4 of its test files (full read); all 5 contract files (full read); `KeystoreCredentialStore.kt` (full); `AutoReconnectManagerImpl.kt` (full); `SessionViewModel.kt` (TOFU/clipboard/lifecycle/password sections, lines ~200–620); `SessionScreen.kt` CertificateDialog; `AppSettingsRepository.kt` (TOFU store + persistence); `FloatingMouseOverlayView.kt` (full); cross-cutting greps for `TODO|dummy|placeholder|NotImplemented` across all `src/main` (only benign hits: `SessionScreen.kt:320,853` Compose `placeholder =` slot, `NativeFreeRdpEngine.kt:686` comment) and for credential/CharArray/scope patterns. NOT re-read line-by-line: the other ~450 tests' sources (e2e tier internals, telemetry test bodies) — execution-verified only; noted as review-scope limit.

### BLOCKER
None.

### MAJOR

**MAJOR-1 — Fake audit trail in the "5-step leak-free teardown" (dummy-behavior)**
`feature-telemetry/src/main/java/com/freerdp/feature/telemetry/reconnect/AutoReconnectManagerImpl.kt:136-138` — `teardownStep2_abortNativeConnect()` performs **no abort of anything**; it only appends `"Step 2: Aborted native connection attempt"` to `executedTeardownHistory` and invokes the audit listener, while its KDoc claims "Signals cancellation to the native transport layer". Same pattern at `:144-151`: `teardownStep3_waitForRenderLoopJoin()` records `"Render loop joined and surfaces flushed"` even when `renderLoopDrainAction == null` (no join occurred — null in most constructions; only `SessionViewModel` passes a pacer-reset drain). The step history is asserted by `AutoReconnectManagerTest` (`testFiveStepTeardownExecutedInOrder` etc.) and cited by latency_perf's handoff as proof of a "5-step leak-free teardown" — i.e., **tests verify that strings were recorded, not that steps 2/3 happened**. Actual leak-freedom rests on step 1 (job cancels) + step 4 (`engine.disconnect()`) + the app's explicit `reconnectManager.shutdown()` (`SessionViewModel.kt:614`), which I verified is wired. Impact: misleading audit/evidence, not a functional leak today. Fix direction (for owners, not me): make step 2 a real cancellation hook or re-word record/KDoc to "no-op (indirect via step 1)".

**MAJOR-2 — Silent security downgrade in credential vault (credential handling)**
`feature-session/src/main/java/com/freerdp/feature/session/security/KeystoreCredentialStore.kt:43-47` — if `MasterKey`/`EncryptedSharedPreferences.create` throws on a **real device** (Keystore corruption, hardware-backed key failure), the `by lazy` delegate silently falls back to `Aes256GcmFallbackVault`, which at `:121-134` stores the AES-256 key as **Base64 plaintext in the same SharedPreferences file as the ciphertext** (`vault_master_key_b64` beside `secret_*`). Ciphertext + key side-by-side = encryption theater; no log, no user signal, no fail-closed behavior — violating the product's own "secure-by-default credentials" principle (docs/PRODUCT.md). The fallback is legitimate for JVM/Robolectric (documented in the comment), but the *production* fallback branch should fail closed or surface a warning. Note: primary path (`EncryptedSharedPrefsVault`) reviewed and clean — `commit()` used intentionally, internal byte buffers zeroed in `finally` (`:76, :87, :152, :174`), never throws (`:89-92, :177-180`), `clearAll` scoped to `secret_*` in fallback (`:187-195`).

### MINOR

**F3 — Double-transformed button-up when exiting touchpad mode mid-drag (logic bug)**
`feature-mouse/src/main/java/com/freerdp/feature/mouse/MouseController.kt:155-161` — `setTouchpadMode(false)` while dragging calls `handleDragEnd(cursorX, cursorY)`; `cursorX/Y` are **remote-desktop** coordinates, but since `isTouchpadMode` was already set `false`, `handleDragEnd → resolveTargetCoordinates` takes the absolute branch and pushes them through `transformer.screenToDesktopInt(...)` again → `LEFT_BUTTON_UP` lands at a wrongly-mapped desktop position whenever scale/translation ≠ identity. The test that covers this path (`MouseControllerTest.testSafetyReleaseWhenExitingTouchpadModeDuringDrag`) asserts only the flag, and the 1:1 fixture (scale 1, trans 0) makes the coordinates identical — hiding the bug. Contrast `releaseButtons()` (`:204-209`), which correctly uses raw cursor coordinates.

**F4 — `onPanEnd` starvation after multi-touch (state leak for listeners)**
`feature-mouse/.../GestureDisambiguationEngine.kt:183-192` — the latched `ACTION_UP` branch sets `isPanning = false` (`:186`) and returns **without** `listener?.onPanEnd()`. A single-finger pan (`isPanning=true`) followed by a second finger → final lift takes the latch branch → consumers that track pan sessions (e.g. hysteresis/velocity reset) never see the end-of-pan for that gesture. Suppression of *taps* there is correct per spec; dropping `panEnd` is not.

**F5 — DISPATCH item 5 "Modifier expand" overlay button missing**
`.agents/worker_m2/DISPATCH.md` requirement 5 lists overlay buttons "…Touchpad toggle, **Modifier expand**". `FloatingMouseOverlayView.kt:56-63` builds LMB/RMB/Drag/Scroll↑/Scroll↓/Touchpad/Cursor/Collapse — no modifier-expand button. The capability exists app-side (`SessionScreen.kt` modifier bar, wired by app_finish), so product coverage is intact, but the feature-mouse-level requirement was relocated, not implemented. 

**F6 — Test name overclaims (evidence quality)**
`feature-mouse/src/test/.../GestureDisambiguationTest.kt` `testLongPressTriggersRightClickAndIsAbortedOnMove` — asserts only that the long-press callback fires once and that release after long-press emits no single tap. There is **no right-click assertion** (the engine never dispatches pointer events; long-press→right-click wiring lives app-side) and **no move-abort step** despite "IsAbortedOnMove" in the name. Assertions themselves are real (calls production `longPressRunnable` via `triggerPendingLongPress()`); the name promises more than the test verifies.

**F7 — Un-wiped credential read (hygiene)**
`app/src/main/java/com/freerdp/client/session/SessionViewModel.kt:505` — first `credentialStore.getSecret(profile.id)` result is used only for a null-check and never passed to `wipeSecret` (the second retrieval at `:512` is wiped at `:514`). Two full reads also decrypt twice.

**F8 — Plaintext password String retained for VM lifetime (accepted limitation, flagged)**
`SessionViewModel.kt:541` — `lastConfig` (needed for auto-reconnect) holds the password as `String`; `respondPassword`/Compose `TextField` are String-based, so it cannot be zeroed (`RdpConnectionConfig.password: String` by contract design). `CharArray` discipline is otherwise correct: editor wipes at `ProfileEditorViewModel.kt:206-208`, prompt-path wipes at `SessionViewModel.kt:533-535`, vault retrieval wiped at `:514`, `wipeSecret` helper exists. TOFU path reviewed clean (see §6). Flag as accepted-risk documentation item, not a fix.

**F9 — Hardcoded 1080×2400 pre-layout fallback**
`feature-mouse/.../FloatingMouseOverlayView.kt:294-295, 314-315, 332-333` — while unmeasured (`width/height == 0`), position math assumes a 1080×2400 screen; on any other device the first clamp/normalize is wrong until `onSizeChanged` fires. `FloatingMouseOverlayTest` depends on this fallback (its assertions compute against 1080×2400), so a device-diverse fixture would be needed to shake it out.

**F10 — app_finish headline count is wrong: 155 claimed vs 160 actual**
`.agents/app_finish/handoff.md` claims "**155 tests / 0 failures** … twice"; its own per-suite table rows sum to **160** (74 e2e + 86 unit), and my fresh XML parse confirms **160/160**. Arithmetic error in the headline claim; the green result itself is real (verified twice-over by my run).

**F11 — ORIGINAL_REQUEST AC-6/AC-7 evidence cites stale counts**
`ORIGINAL_REQUEST.md:50` cites `ProfileRepositoryTest 8/8`, `:52` cites `KeystoreCredentialStoreTest 9/9` (pre-hardening numbers). Current = **17/17 each** (session_logic expanded them; my fresh run confirms both green). The ticks remain justified by fresh evidence, but the cited numbers no longer match the artifacts they point at. (AC-8/9/10 remain unticked with "failing" annotations that are now stale too — telemetry is 118/118 as of my run; re-ticking is the acceptance agent's job, not mine.)

**F12 — Overlay LMB/RMB click-at-overlay-position semantics (design observation)**
`FloatingMouseOverlayView.kt:202-208` passes the *overlay's own* screen position as the click coordinate; in absolute mode `DefaultMouseController` maps that to whatever desktop point sits under the floating bubble (in touchpad mode it correctly uses the virtual cursor). Possibly intended ("click under the bubble"), but worth an explicit product confirmation since the remote cursor visibly jumps to the bubble's location on every overlay click.

### Not-found checks (explicit negatives)
- **No dummy/facade stubs** in main sources beyond MAJOR-1: grep for `TODO|NotImplemented|placeholder|dummy|stub` across all `src/main` returned only benign hits (listed in scope above). `MockRdpEngine` is a genuine product Demo engine instantiated by production `AppContainer.kt:94` (not a test double smuggled into main).
- **No tautologies found** in the fully-read feature-mouse tests: assertions compare engine-recorded events against independent constants (e.g. exact `0x0278/0x0378/0x0478/0x0578` wheel flags, computed clamp bounds `1919/1079`, math-derived expected translations), not against the mocks' own inputs.
- **No coroutine-scope leaks found beyond documented ones**: `SessionViewModel.terminate()` order verified — `reconnectManager.shutdown()` **before** `rootJob.cancel()` (`SessionViewModel.kt:614-615`), `onCleared()` also terminates; `AutoReconnectManagerImpl` uses component-owned `SupervisorJob` scope with explicit `shutdown()` + `isShutdown` guards on every entry point (`:194, 202, 227, 252, 258, 270`); its one soft spot is plain (non-`@Volatile`) `backoffJob/connectJob` fields written from coroutines — benign on the single-threaded test dispatchers and low-risk in production, noted for completeness.
- **SharedPreferences**: credential/settings paths use `commit()` (crash-safe, intentional per app_product §5.6 — I concur it's correct here); `OverlayCoordinates.saveToPreferences` uses `apply()` (`OverlayCoordinates.kt:77`) — acceptable for a non-critical UI position.
- **Compose/API misuse**: none spotted in the reviewed sections (named-arg `FramePacer(onFrameDroppedCallback=…)` pattern preserved at `SessionViewModel.kt:214` via constructor param; TOFU dialog uses `Modifier.touchTarget()` ≥48dp).

---

## 5. M2 assessment (the handoff gap) — `.agents/worker_m2/` has **no handoff.md**

Confirmed absent (`worker_m2/` contains only `BRIEFING.md`, `DISPATCH.md`, `progress.md`). Requirement 8 of DISPATCH ("Deliver handoff.md and notify parent") was **not met** — process finding, recorded here as the substitute record.

Assessment of feature-mouse against **DISPATCH items 1–7**:

| # | Requirement | Verdict | Evidence |
|---|---|---|---|
| 1 | MouseController: clicks/double/drag/scroll, touchpad mode, virtual cursor, exact MS-RDPBCGR flags via `RdpPointerFlags` → `IRdpEngine.sendPointerEvent` | ✅ Met | `DefaultMouseController` (MouseController.kt:31-235): down/up pairs, 4-event double-click, drag flag `MOVE\|BUTTON1\|DOWN` = `0x8000`-combination, wheel flags, touchpad relative mode with clamping + virtual cursor; 10 tests assert exact flag sequences on `MockRdpEngine.recordedPointerEvents`. "Long-press" (in the item-1 verb list) is delivered by the gesture engine's `onLongPress` callback, consumed app-side — see F6 caveat. |
| 2 | Gesture disambiguation + `multiTouchLatch` (>1 pointer → latched until ACTION_UP, suppresses spurious taps), touch-slop hysteresis | ✅ Met | `GestureDisambiguationEngine.kt:60-64, 96-99, 145, 163-166, 183-192`; dedicated acceptance test `testMultiTouchLatchEliminatesSpuriousClickOnSequentialFingerLift` walks DOWN→POINTER_DOWN→MOVE→POINTER_UP→UP and asserts `singleTap==0 && doubleTap==0` — the actual spec scenario, real assertion. Pan-slop hysteresis at `:120-126`. (F4: `panEnd` dropped on the latch path.) |
| 3 | `OverlayCoordinates` normalized persistence math + safe-inset clamping | ✅ Met | `OverlayCoordinates.kt:50-75` implements the spec formula (algebraically equivalent to the dispatch's normalized form: `normX = (clamped − left) / span`, span = `W − ow − right − left`); `require()` bounds in `init`; tested at 0/negative/extreme insets. |
| 4 | `CoordinateTransformer` affine bidirectional conversion, zoom scale, pan offset, clamping | ✅ Met | `CoordinateTransformer.kt` — focal-point-invariant `setScale` (`:62-74`), pan clamp (`:79-93`), `screenToDesktop(Int)`/`desktopToScreen` round trip (`:139-158`); 8 tests incl. exact spec example (zoom 1.5, trans (100,200), touch (250,500) → desktop (100,200)). |
| 5 | `FloatingMouseOverlayView` Collapsed/Expanded/Dragging + buttons: L, R, drag toggle, scroll, touchpad, **modifier expand** | ⚠️ Met except "Modifier expand" | 3 states implemented with visibility wiring (`:249-268`), drag-reposition with slop + edge snap (`:112-166`); buttons at `:56-63` lack modifier-expand (F5 — exists app-side instead). |
| 6 | 4 named test files under `feature-mouse/src/test/...` | ✅ Met | All 4 exist with the prescribed names; 32 tests total; assertions substantive (§4 negatives). |
| 7 | Run `:feature-mouse:testDebugUnitTest`, 100% pass | ✅ Met (now independently proven) | My two fresh `--rerun` runs: **32/32** at 03:42:42 and 03:53:20, 0 failures. |
| 8 | handoff.md + notify parent | ❌ **Not met** | Directory listing above. |

**PROJECT.md Feature Inventory 7–14 coverage:**
- **7 (Overlay FSM)** ✅ Collapsed/Expanded/Dragging, edge-snap, persistence.
- **8 (Complete mouse events: L/R/double/drag/long-press/wheel)** ✅ with wiring split: everything except long-press is dispatched in `feature-mouse`; long-press→right-click is a `GestureEventListener.onLongPress` callback consumed by `:app` (app_product claims long-press = right click on canvas) — coverage honest but distributed across modules.
- **9 (Touchpad mode)** ✅ relative cursor, tap-click at virtual cursor (tested), two-finger scroll via `onTwoFingerScroll` + `handleHorizontalScroll`.
- **10 (Optional virtual cursor)** ⚠️ state + position + visibility fully implemented and tested; *rendering* of the cursor at remote coordinates is not in this module (app-side overlay/canvas responsibility) — inventory wording "rendered" is satisfied elsewhere, not here.
- **11 (Pan & pinch-to-zoom)** ✅ tested (pinch scale >1 asserted, pan-without-click asserted).
- **12 (Anti-spurious latch)** ✅ the acceptance test exists and asserts the real scenario.
- **13 (Orientation adaptation & clamping)** ✅ normalized round trip across 1080×2400 → 2400×1080 tested; insets clamp tested.
- **14 (Affine transformation)** ✅ bidirectional + matrix + scale/translation, 8 tests.

**Honest M2 coverage verdict: ~8/8 requirements met in substance (items 5-modifier and long-press-wiring delivered app-side), zero fake implementations, zero tautological tests; the only hard miss is the missing handoff.md.**

---

## 6. product_ux claim verification

1. **"AC-3/4/5/6/7 ticks have real evidence (XML mtimes newer than sources)" — VERIFIED TRUE.**
   - At tick time: cited `TEST-*.xml` mtimes 2026-09-23 00:43 vs newest `:feature-mouse` source 00:42:27 — post-dates ✔.
   - At my re-verification: mouse XMLs 03:42/03:53 vs sources ≤00:42:27 ✔; session XMLs 03:48 vs sources ≤01:42:19 ✔; all 5 modules' newest XML post-date their newest source at final check 03:53 ✔.
   - Cited test names all exist and ran green in my fresh runs (spot-checked every listed `MouseControllerTest`, `GestureDisambiguationTest`, `FloatingMouseOverlayTest` name against my XML run). Assertions were spot-read and are substantive, matching their claim.
   - **Caveat (F11):** AC-6/AC-7 cite pre-expansion counts (8/8, 9/9) — stale numbers, but tick *conclusions* survive my fresh run (17/17 each).
2. **"PROJECT.md §Interface Contracts left byte-for-byte untouched" — CONTENT VERIFIED, BYTE-IDENTITY UNVERIFIABLE.** This tree has **no VCS** (`.git` absent) and no baseline copy of PROJECT.md exists in-repo, so a literal byte-for-byte diff against the pre-product_ux state is impossible for anyone. What I could verify: (a) PROJECT.md mtime 01:33:19 falls inside product_ux's documented edit window; (b) the current §Interface Contracts content matches all five live sources member-for-member (§3) — i.e., whatever edits happened, the section is *correct* today. I record the claim as "plausible, not independently provable in the absence of a baseline" rather than confirmed.
3. **Honest-status ledger** — reviewed PROJECT.md milestone rows + ORIGINAL_REQUEST evidence policy: they do label M4 as failing-at-snapshot, name the failing tests, and mark non-ticked ACs honestly; the "code-complete, verification in progress" vocabulary is defined strictly. After my fresh runs, M2/M3/M4 red/green annotations are now stale-but-conservative (all four modules are green as of 03:43–03:53) — updating them belongs to the acceptance/integrator wave, not to me.

---

## 7. Assumptions & scope notes

- "Fresh" = task executed (`> Task :<m>:testDebugUnitTest` present in log), not `UP-TO-DATE`, with no `-I` init scripts, no `--tests` filters, no exclusions — plain per-module `--rerun` only.
- Concurrent agents (challenger, integrator) kept rewriting sources and test-results during my window; every count above is paired with both XML mtime and newest-source mtime, and every transient failure was re-run to green per protocol with timestamps captured (§2.1). If sources change after 03:53 local, re-run before acting on these numbers.
- Review scope limits (stated honestly): full line-by-line reading covered `feature-mouse` (all files), all contract files, `KeystoreCredentialStore`, `AutoReconnectManagerImpl`, and the TOFU/credential/lifecycle slices of `:app`; telemetry internals beyond those read files and the bodies of the e2e/telemetry/unit tests were execution-verified but not individually re-read — residual risk accepted for a single-reviewer pass.
- Native `.so` packaging infeasibility (native_finish) and the `FreeRdpFlagMapping` dependency-cycle follow-up were read and accepted as documented; both are consistent with what I observed in-tree (no `jniLibs`, `feature-telemetry → :core-rdp` dependency direction) but were not re-proven by me (they require environment/toolchain inspection outside review scope).

**Fixes applied by me: NONE (review-only mandate honored).**
