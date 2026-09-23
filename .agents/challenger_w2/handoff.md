# challenger_w2 Handoff — Adversarial QA / Integrity Audit (wave 2)

**Agent:** `challenger_w2`  **Date:** 2026-09-23 04:05 (local)  **Role:** break the wave-1 claims, produce evidence, fix nothing.

---

## VERDICT: **CHALLENGE-PASS** (with 2 test-fidelity gaps, 1 count discrepancy, 1 medium security finding)

The product's headline claims survive adversarial attack: no cheating was found (no hardcoded
results, no dummy facades, no smuggled ignores), every module count is backed by machine-generated
XML, and **12 of 14 deliberately-introduced bugs were killed by the existing suites (85.7% kill
rate)**. Two realistic mutants survived — both are *test-fidelity gaps* (the code is currently
correct, but the suite cannot prove it), not fraud. One headline number (`app 155`) is arithmetically
wrong (reality: **160**), under-claiming rather than padding.

| Attack | Result |
|---|---|
| 1. Mutation testing | **14 attempted / 12 killed / 2 survived → 85.7% kill rate** |
| 2. Integrity hunt | **1 count discrepancy (app 155 vs 160), 0 cheats** (no `@Ignore`, no `assertTrue(true)`, no empty test bodies, no `assumeTrue` smuggling, 0 skipped everywhere, assert density ≥ ~1.4/test) |
| 3. Security review | **1 MEDIUM, 2 LOW, positives documented** — no plaintext password in profiles/logs/intent extras; TOFU is fail-closed |
| 4. Resilience | 10 untested failure paths listed (§5) |
| 5. Product gaps | HUD honest, 1001 hint correct, one-tap connect real; 4 gaps listed (§6) |

---

## 1. Mutation testing (primary evidence)

**Method (repo-safe, per mission protocol):** byte-exact backup into
`.agents/challenger_w2/backup/` → SHA-256 recorded → apply one small realistic bug → run the single
test class with `--rerun` → restore from backup → SHA-256 re-verified equal → (for mutated modules)
re-run the **full module suite** so no red mutant XML is left on disk. Verified compiled mutant was
actually loaded before trusting a "survived" result (class-file mtime 3:47:55 < test XML 3:48:04 for
M4). The repo is **not a git repository**, so the backups are the only rollback — they all verified.

| # | File | Mutant | Test class run | Outcome |
|---|---|---|---|---|
| M1 | `feature-session/.../modifier/ModifierStateMachine.kt:134` | latch emits no key-down | ModifierStateMachineTest | **KILLED** 5/20 failed |
| M2 | `feature-session/.../data/AtomicFileProfileRepository.kt:188` | last-good `.bak` never written | ProfileRepositoryTest | **KILLED** 2/17 failed |
| M3 | `feature-session/.../security/KeystoreCredentialStore.kt:139` | AES-GCM IV never randomized (nonce reuse) | KeystoreCredentialStoreTest | **SURVIVED** 17/17 green |
| M4 | `feature-session/.../toolbar/QuickActionToolbarFSM.kt:150` | timer generation guard removed | QuickActionToolbarTest | **SURVIVED** 14/14 green |
| M5 | `feature-telemetry/.../pacer/FramePacer.kt:177` | `isStale` → false (stale frames blit) | FramePacerTest | **KILLED** 3/16 failed |
| M6 | `feature-telemetry/.../reconnect/AutoReconnectManagerImpl.kt:331` | backoff exponent fixed to 0 (constant delay) | AutoReconnectManagerTest | **KILLED** 2/15 failed |
| M7 | `app/.../session/SessionPhase.kt:80` | Idle-guard removed (stray failure corrupts Idle) | SessionPhaseReducerTest | **KILLED** 1/10 failed |
| M8 | `app/.../session/SessionPhase.kt:49` | error-1001 friendly hint dropped | SessionPhaseReducerTest | **KILLED** 2/10 failed |
| M9 | `feature-mouse/.../GestureDisambiguationEngine.kt:183` | multiTouchLatch no longer suppresses final tap | GestureDisambiguationTest | **KILLED** 1/7 failed |
| M10 | `core-rdp/.../engine/NativeFreeRdpEngine.kt:617` | cert verdict polarity inverted (accept↔reject) | NativeFreeRdpEngineLifecycleTest | **KILLED** 1/22 failed |
| M11 | `feature-session/.../keyboard/ScancodeTranslator.kt:321` | Ctrl+C LIFO broken (Ctrl released before C) | ScancodeTranslatorTest | **KILLED** 2/14 failed |
| M12 | `feature-mouse/.../CoordinateTransformer.kt:150` | screen→desktop clamp removed | CoordinateTransformerTest | **KILLED** 1/8 failed |
| M13 | `feature-session/.../modifier/ModifierStateMachine.kt:215` | latch never auto-clears after consumed key | ModifierStateMachineTest | **KILLED** 5/20 failed |
| M14 | `feature-telemetry/.../pacer/FramePacer.kt:154` | VSYNC same-period coalescing removed | FramePacerTest | **KILLED** 1/16 failed |

**Score: 14 attempted, 12 killed, 2 survived → kill rate 85.7% (12/14).**
All 7 attack-priority components from the brief were mutated (3-state latch ×2, multiTouchLatch,
FramePacer single-slot/stale ×2, backoff, atomicity, keystore, reducer ×2) + 4 extras.

### Survivors — why they matter

- **M3 (IV nonce reuse) — SECURITY-CADENCE GAP.** `KeystoreCredentialStoreTest` (17 tests) never
  asserts IV uniqueness/randomness (grep for `IV|nonce|Random` in the test: no hits). Reusing an
  AES-GCM nonce under one key is a textbook critical bug and the suite would ship it silently.
  *Claim attacked:* session_logic §2.2 "genuine AES-256-GCM" — genuine cipher, unguarded nonce.
- **M4 (toolbar generation guard) — UNFALSIFIABLE CLAIM.** session_logic handoff says the race is
  "real race closed, **not just tested**". Empirically the *tested* half is false:
  `testStaleTimerCannotCollapseReExpandedToolbar` (QuickActionToolbarTest.kt:325) stages staleness
  via `fsm.collapse()`, which cancels job #1 through `cancelAutoCollapseTimer()` — so job
  **cancellation**, not the generation check, is what the test actually exercises. Removing
  `generation == timerGeneration` entirely leaves all 14 tests green. The guard may well be correct
  production defense; the suite simply cannot fail without it (the only real-world window — cancel
  arriving *after* `delay()` completed — is not stageable with the current API).

### Restoration proof (all hashes SHA-256, byte-exact vs pre-mutation backups)

```
OK  FramePacer.kt                     E960AEDF499242CADC2151656218A8B5EE417DC810F33D3014E649AA85B915CF
OK  ModifierStateMachine.kt           CC4924FC62179A1BF1C4800211BF0C1D0EB2DDE880943E97214D9F0593B1671E
OK  AtomicFileProfileRepository.kt    B4C19203F1B0B70C6B41A6A57B94694BF46F5551FF5197440ED2A6A8777FB95A
OK  KeystoreCredentialStore.kt        9C4BBCB067EB1C1AD17D6DD2B0C926C6AB1314FC3DD404D02FD6B305F59FDA4F
OK  QuickActionToolbarFSM.kt          5FC10DB573EA70574553E38B751CC17504F18B7786625ECB47B9FC5BCABDDF3D
OK  ScancodeTranslator.kt             891DDCFD191098E6D604A52369FEF1D006E3DDE01453E32835D5534ECA3C826F
OK  AutoReconnectManagerImpl.kt       1E17E53465ADBD5F5B49CB0794AF86446A5A99A4F7B284BF130B7814FE4A904F
OK  SessionPhase.kt                   BE8A3E230791025C8739FD44BC856D4BE779F03F06A104F431B7991E4C2A7B59
OK  GestureDisambiguationEngine.kt    8F5CBA33DCCD355F1EDB29FFA19D2D033928A17998EA010D398214FE6FA8658C
OK  CoordinateTransformer.kt          D3FA32788FC48204FCA11ED74ADE066AB78908BF4AE5E5B45F0EE2083B23847F
OK  NativeFreeRdpEngine.kt            1F3AFAE27AC5359E76A991EF40E5A28F9B254964F0523A7BD6C63A1839E0D4A5
bad=0   (grep 'MUTANT' across all 11 files: 0 hits; restore_audit.ps1 reproduces this)
```

Post-restoration green re-runs (fresh XML, rewriting any mutant-red XML my runs produced):

| Suite | Result | XML mtime |
|---|---|---|
| `:feature-session:testDebugUnitTest` (full) | **82/82** | 4:00:16 |
| `:feature-mouse:testDebugUnitTest` (full) | **32/32** | 4:00:59 |
| `:feature-telemetry:testDebugUnitTest` (full) | **118/118** | 4:01:05 |
| `:core-rdp:testDebugUnitTest` (full) | **95/95** | 4:01:09 |
| `:app` full run (independent, concurrent agent) | **160/160** | 3:58:02 |

No source, test, doc or gradle file was permanently modified by this agent. Files written by me:
`.agents/challenger_w2/**` only (this handoff, `backup/*.orig`, `restore_audit.ps1`).

---

## 2. Count verification (claim vs XML reality)

Parsed every `*/build/test-results/testDebugUnitTest/TEST-*.xml` (`tests/failures/errors/skipped`
per class). All runs dated 2026-09-23, all **0 skipped** anywhere.

| Claim | XML reality | Verdict |
|---|---|---|
| core-rdp **95/95** | 95 (6+5+13+6+10+8+22+7+14+4), 0 fail | ✅ exact (handoff per-class table also exact) |
| feature-mouse **32/32** (legacy, "unverified") | 32 (8+7+7+10), 0 fail — now with fresh mtimes (03:42 & my 04:00 runs) | ✅ **claim now independently verified** |
| feature-session **82/82** | 82 (17+20+17+14+14), 0 fail | ✅ exact; per-class table exact |
| feature-telemetry **118/118** | 118 (8+15+9+16+10+17+6+6+8+23), 0 fail | ✅ exact; per-class table exact |
| e2e **74/74** | 74 (35+26+8+5), 0 fail | ✅ exact |
| app **155/155** | **160** (e2e 74 + unit 86), 0 fail | ❌ **count discrepancy** — see I-1 |
| app APK | `app-debug.apk` exists, 17,376,022 B @ 3:58 (integrator rebuild; handoff cited 17,336,851 B @ 3:39) | ✅ fresh APK present |

### Integrity findings (file:line where applicable)

- **I-1 (accuracy, underclaim):** `.agents/app_finish/handoff.md` §1 table sums to **160**, but its
  TOTAL row and the wave-1 claim say **155** (same file §5 also says "unit 86" and "grand total 155";
  74+86=160). Reality confirmed twice: XML parse and an independent concurrent full `:app` run
  (3:58, `tests=160 fail=0`). Arithmetic error in the headline number, not padding — actual coverage
  is *better* than claimed. The per-suite rows are all individually correct.
- **I-2 (disclosed, not hidden):** e2e handoff §4 headline command runs with
  `-I <scratchpad>\e2e_exclude_probe.init.gradle` + `--tests com.freerdp.client.e2e.*` — an init
  script that pattern-excluded the then-broken `CoroutineDiagnosticsTest`. It was openly documented
  with a removal instruction; `app_finish` later ran the full suite with **no** `-I`, no filters
  (its claim verified: 155→160 tests all green in plain XML). Not a cheat; recorded because the
  e2e "headline gate" as literally written is a filtered run.
- **I-3 (disclosed assertion replacement):** latency_perf §5.2 replaced
  `LowLatencySocketConfigTest.testSocketConfigurationParameters`'s `trafficClass` readback
  (`expected:<16> but was:<0>`, impossible on Windows JDK) with strict-mock `verifyOrder` that all
  five options incl. `IP_TOS=16` are applied. Justified and disclosed; residual: readback equality
  is no longer asserted anywhere (only on-device would prove it).
- **I-4 (no test-tampering found):** repo-wide greps found **zero** `@Ignore`, `assumeTrue`,
  `assertTrue(true)`, `assertFalse(false)`, empty test bodies, `Thread.sleep`, real-socket usage in
  e2e, or catch-and-swallow in *test* code. Catch-swallow instances in *main* code are all
  justified (NetworkStateMonitor non-standard-env fallback `:65-67`, unregister-already-unregistered
  `:79-80`, teardown suppression `AutoReconnectManagerImpl:161-163`, corrupt-recovery cascades).
- **I-5 (claim overstated by mutation):** session_logic "generation guard … real race closed, not
  just tested" — the *tested* part is false (M4 survived). The `@Volatile`/double-checked-lock and
  "never throws" vault claims survive scrutiny at code level but see M3 for what tests can't see.
- **I-6 (baseline-history honesty):** telemetry baseline 8-fail/33 claim cross-checked against
  product_ux handoff §3 (names all 8 failures) — consistent; fix claimed as *code* (scope move),
  and indeed no assertion was deleted per file diff narrative; the one replacement is I-3.

**Integrity score: 94/100** — 5 of 6 headline counts exact, 1 wrong (I-1, undercount), 0 cheats,
0 skips, all deviations disclosed in their own handoffs.

---

## 3. Security attack (code review, severity-tagged)

### Findings

- **[MEDIUM] S1 — Vault fallback stores its own key next to its ciphertext.**
  `KeystoreCredentialStore.kt:121-133` (`Aes256GcmFallbackVault.getOrCreateSecretKey`) persists the
  AES-256 key **base64-plaintext** in `vault_master_key_b64` — in the *same* SharedPreferences file
  as the `secret_*` ciphertexts. Any adversary who can read the prefs file (root, backup extraction,
  debuggable build) gets key+ciphertext trivially: the fallback provides obfuscation, not
  confidentiality. Reached whenever `MasterKey.Builder`/`EncryptedSharedPreferences.create` throws —
  on JVM always, on device only when AndroidKeyStore is unavailable — but that is exactly the
  locked-down-device scenario the vault exists for. Tests cannot catch it (and M3 shows they can't
  catch nonce reuse either). *Recommendation (for a fix-owner, not me): derive from
  `AndroidKeyStore` non-exportable key even in fallback, or at minimum document the threat model.*
- **[LOW] S2 — `EncryptedSharedPrefsVault` on caller-supplied prefs writes secrets in clear base64.**
  `KeystoreCredentialStore.kt:35` (`sharedPreferences ?: EncryptedSharedPreferences…`) — if any
  future caller passes plain prefs, `saveSecret` (`:70-78`) stores base64(plaintext) under
  `secret_<id>` with **no** encryption. Production today does not do this
  (`AppContainer.kt:68` passes only `context`+filename — grep-verified the only main-source call
  site), so it is a contract trap rather than an exploitable hole. A test asserting
  "raw entry ≠ plaintext" passes on the fallback vault only because the fallback encrypts.
- **[LOW] S3 — password immutability defeats wiping on the connect path.**
  The vault-side discipline is real: retrieved `CharArray` wiped at `SessionViewModel.kt:513-514`,
  prompt chars wiped at `:535`, editor chars wiped at `ProfileEditorViewModel.kt:208`. But
  `respondPassword(password: String?)` (`:519`) and `RdpConnectionConfig.password: String`
  (`RdpConnectionConfig.kt:8`) hold the password as immutable `String`s, and `lastConfig`
  (`SessionViewModel.kt:541`) keeps one for the whole session — those copies can never be zeroed.
  Standard Android practice, but it contradicts any "CharArray zeroing completeness" reading of the
  claims. Also `ProfileEditorViewModel` state carries `password: String` (`:87`) in Compose state.
- **[INFO/POSITIVE] S4 — verified-secure behaviors:**
  - **No `Log.*` calls anywhere in `src/main`** (repo-wide grep: 0 hits) — nothing can leak to logcat.
  - **No password intent extras** (`putExtra`/`getStringExtra` with password: 0 hits).
  - **TOFU is fail-closed and aborts connect:** `SessionViewModel.kt:307-321` — trusted
    host+fingerprint short-circuits; otherwise blocks the IO connect thread with
    `certificateDecisionTimeoutMs = 60_000` (`:97`, `:313`), timeout → `false`, any `Throwable` →
    `false`; reject returns `false` → native polarity `0` = FreeRDP aborts (polarity itself
    mutation-verified: inverting it kills `certificateVerificationMapsBooleanToNativePolarity`,
    M10). Dialog dismissal = Reject (`SessionScreen.kt:926 onDismissRequest = onReject`), and
    "Trust & connect" vs "Reject" buttons are labeled with error-tinted Reject (`:946-952`).
    Revocation is persisted per host+fingerprint (`AppSettingsRepository.kt:117-127`).
    *Spoof-residual:* the dialog shows host+fingerprint from the same callback it grades — correct
    for TOFU, but there is no pinning-vs-previous-fingerprint warning (a changed cert for a trusted
    host re-prompts only because the pair won't match — OK — yet the copy doesn't say "this differs
    from last time", UX-level weakness).
  - **JNI use-after-free guards are systematic:** `nativeInstance` is `AtomicLong`
    (`NativeFreeRdpEngine.kt:61`), freed with `compareAndSet(inst, 0L)` (`:358`), and **every**
    callback path goes through `isCurrent(inst)` (`:475,480,507,525,536,615,623,635,641,675-677`)
    with fail-closed returns (cert → `0` = reject). Missing-listener also fail-closed.
  - **Clipboard echo-loop** suppressed both directions via SHA-256 echo-state
    (core-rdp `ClipboardHandler`, app `ClipboardBridge`), pinned native + parity + e2e tests.
  - **Profile JSON carries no password** (password never a `RdpProfile` field;
    `ProfileEditorViewModelTest` asserts absence from on-disk JSON).

---

## 4. Resilience assessment (thought experiments anchored to existing tests)

Tested well (mutation-backed): disconnect/connect lifecycle & idempotent free (core-rdp stress ×2),
backoff/pause/cancel/hold-during-offline/flap (AutoReconnectManagerTest, M6-verified), orientation
debounce boundaries 249/250 ms + 6-flip coalesce (app + e2e Tier2-C), corrupt/truncated profile
recovery (M2-verified), rapid multi-touch lifts (M9-verified), clock-skew/future frame timestamps.

### Untested failure paths (list)

1. **TOFU dialog 60-s timeout expiry** — no test advances 60 s and asserts reject→abort→retryable
   state (`SessionViewModelTest` covers trust/persist/silent-reaccept/reject only).
2. **`AutoReconnectManagerImpl` with `configProvider() == null`** — `Failed("No configuration
   provided")` branch (`AutoReconnectManagerImpl.kt:288-291`) has zero coverage in 15 tests.
3. **`engine.connect` throwing** (vs returning `false`) inside `connectJob` — no test; behavior
   under the component `SupervisorJob` (child dies, manager state stuck `Reconnecting`?) unverified.
4. **Process death during an active session** — `ProfilesViewModel` process-death restore is tested,
   but there is no path/test for "app killed mid-session → relaunch" (session VM is
   container-scoped; nothing persists a reconnect intent). Arguably acceptable v1 behavior, but
   untested and unspecified.
5. **Rapid Connect→Disconnect→Connect churn at app level** — generation counter + 250 ms settle are
   tested for single drop; a burst of user-driven exits/re-entries racing `processSettledDisconnect`
   is not.
6. **Orientation flip while `Connecting` / while cert dialog is up** — debounce tests all run in
   Connected; interaction of `DynamicLayoutListener` emissions with the blocking TOFU gate untested.
7. **On-device `EncryptedSharedPreferences` primary vault path** — no `androidTest` exists at all;
   only the JVM fallback vault is CI-proven (session_logic risk R1, confirmed).
8. **`NetworkStateMonitor.startMonitoring` when `registerDefaultNetworkCallback` throws** — catch at
   `NetworkStateMonitor.kt:65-67` silently degrades; no test asserts the degraded state is visible.
9. **Disk-full / `fd.sync()` IOException during profile save** — atomicity tests cover corruption &
   concurrency, not write failure propagation to the ViewModel error banner.
10. **Anything native** — zero `.so` on the machine (native_finish verdict), so every
    `NativeFreeRdpEngine` behavior is fake-backed; first on-device run is the first real test
    (contract test pins JNI descriptors, that's all).

---

## 5. Product-level gaps a user would hit

1. **No native engine is possible in any build produced here** — every default-mode connect ends in
   error **1001**. The hint text is *correct and actionable* (mutation-verified: deleting it kills
   2 tests) and there is **no silent fallback** to demo (`AppContainer.kt:92-96` builds
   `NativeFreeRdpEngine` unless the user flips the demo switch) — but the very first thing a new
   user does (tap Connect) fails until they discover Settings → Demo engine. UX: acceptable only
   with the banner/hint; packaging the `.so` remains AC-2 open.
2. **HUD is honest, not fake** — FPS comes from `telemetry.recordFrameDelivered()` fed by actual
   VSYNC blits (`SessionViewModel.kt:712`, `RemoteCanvasView`), RTT from engine metrics; demo mode
   shows 0 rather than synthetic numbers (app_product risk 5, confirmed in source). DEMO badge in
   the connection chip (`SessionScreen.kt:516`) — demo-vs-native is clearly labeled.
3. **One-tap connect is real:** `ProfileListScreen.kt:240` Connect button → `vm.connect(profileId)`
   → `loadAndConnect` (`SessionViewModel.kt:480-513`); zero extra taps when a secret is stored
   (password prompt only when the vault has nothing — correct).
4. **Minor:** the "Suggested right now" preset chip is computed but connectivity/battery broadcasts
   don't auto-apply presets (app_finish risk 4 — self-disclosed); TOFU re-prompt on cert *change*
   doesn't say the fingerprint changed (S4 residual).

---

## 6. Assumptions & caveats

- "Survived" verdicts were accepted only after confirming the mutant class file was recompiled
  before the test XML was written (M4 evidence shown in §1; same pattern for M3).
- Concurrent agents (reviewer, integrator) ran Gradle throughout; lock-contention failures
  (`classes.jar being used by another process`) were retried, never worked around with exclusions.
  The integrator rebuilt the APK at 3:58 and ran the full `:app` suite (160/160) — its green XML
  independently corroborates my count finding (I-1) and post-dates my final restoration (4:01
  suite re-runs for the other modules).
- Kill rate is measured per-mutant (12/14), not per-test-case; per-test-case sensitivity was
  27 failing tests across 12 killed mutants.
- Security findings are code-review judgments; no dynamic exploitation was attempted (no device).

**Bottom line: CHALLENGE-PASS — counts (mostly) real, no cheating, 85.7% mutation kill rate; fix
S1 (fallback key custody), close fidelity gaps M3/M4 (assert IV uniqueness; make the toolbar
generation guard testable), and correct the 155→160 headline number.**
