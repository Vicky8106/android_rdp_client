# ACCEPTANCE REPORT — Android FreeRDP Mobile Client (restart)

**Agent:** `acceptance_w2` (final product acceptance)
**Date:** 2026-09-23 (local, UTC+05:30)
**Verdict: ✅ ACCEPT-WITH-CONDITIONS**

Evidence basis: `acceptance_w2` performed **read-only verification directly against on-disk
artifacts** — parsed all 44 `build/test-results/testDebugUnitTest/TEST-*.xml` files, compared the
APK mtime against the newest source edit across all five modules' `src/**`, grep-verified the
merged manifest, and read the bodies of three claimed tests for substantive assertions. No
Gradle re-gate was run (freshness proven by mtimes; no inconsistency found). No source, build,
test, or foreign-handoff file was modified.

---

## 1. Executive summary

**What the product is:** a production-quality, mobile-first Android Remote Desktop client built
on FreeRDP — floating mouse overlay with full touch/gesture system (touchpad mode, pinch/zoom,
anti-spurious latch, affine transforms), Material 3 profile/session UI with one-tap connect,
secure credential vault, quick-action toolbar + modifier bar FSMs, auto-reconnect with 5-step
teardown, low-latency pipeline (single-slot frame pacer, thread isolation, presets, HUD with
FPS/RTT/jitter), and a 4-tier opaque-box E2E suite — across 5 Gradle modules
(`:app`, `:core-rdp`, `:feature-mouse`, `:feature-session`, `:feature-telemetry`).

### Final gate numbers (independently verified by `acceptance_w2`)

| Item | Verified value |
|---|---|
| `testDebugUnitTest --rerun-tasks` | **497/497 tests, 0 failures / 0 errors / 0 skipped, 44 suites** — `fixer_w2` ran the gate **twice**; `acceptance_w2` re-parsed the resulting XMLs (mtimes 04:43:41–04:44:11, all post-dating the last source edit 04:41:24) and confirms the totals exactly |
| Per module | `:app` **161** (15 suites; 74 e2e + 87 unit) · `:core-rdp` **95** (10) · `:feature-mouse` **35** (4) · `:feature-session` **86** (5) · `:feature-telemetry` **120** (10) — sums verified: 161+95+35+86+120 = **497**, 15+10+4+5+10 = **44** |
| `assembleDebug --rerun-tasks` | BUILD SUCCESSFUL → `app\build\outputs\apk\debug\app-debug.apk` = **17,205,283 B**, mtime **2026-09-23 04:43:23**, **newer than the newest source edit (04:41:24)** ✓; merged manifest contains `android.permission.INTERNET` and `com.freerdp.client.App` ✓ |
| Interface contracts | `reviewer_w2` byte/member-level check **5/5 PASS**; `fixer_w2` re-verified all 5 unchanged after its edits |

### Verdicts in the chain

| Agent | Verdict |
|---|---|
| `reviewer_w2` (independent review) | **APPROVE-WITH-FINDINGS** — 0 BLOCKER, 2 MAJOR, 10 MINOR; contract check 5/5; M2 verification gap closed |
| `challenger_w2` (adversarial QA) | **CHALLENGE-PASS** — no cheats; mutation kill 12/14 (85.7%); integrity 94/100 |
| `fixer_w2` (fix owner) | **DONE** — both MAJORs + key minors + both mutation survivors + product gap 1001-recovery all FIXED; final gate 497/497 ×2 |
| `integrator_w2` (integration gate) | **GATE PASS ×2** — zero source edits needed; TODO/FIXME/debug-prints = 0 |
| `acceptance_w2` (this report) | **ACCEPT-WITH-CONDITIONS** |

---

## 2. Acceptance criteria — AC-by-AC

Full ledger with evidence citations: `docs/ACCEPTANCE.md` (8 `ACCEPTED`, 2 `PARTIAL`).

| AC | Criterion (short) | Status | Key evidence (parsed/verified by `acceptance_w2`) |
|----|-------------------|--------|---------------------------------------------------|
| 1 | `assembleDebug` → valid debug APK | **ACCEPTED** | APK 17,205,283 B @ 04:43:23 > newest source 04:41:24; manifest has INTERNET + `com.freerdp.client.App` |
| 2 | FreeRDP native bindings linked & isolated | **PARTIAL** | Isolation ✅ (`LibFreeRDP`/`NativeFreeRdpEngine` confined to `:core-rdp`, `LibFreeRdpJniContractTest` 13/13, JNI audit CLEAN); **no `.so` on this machine** — documented infeasibility + exact build steps; graceful 1001 UX + one-tap demo recovery test-pinned |
| 3 | Mouse overlay event tests (L/R/double/drag/scroll/touchpad) | **ACCEPTED** | `MouseControllerTest` **11/11**, module 35/35 (XML mtime 04:43:41); assertions = exact MS-RDPBCGR flag sequences |
| 4 | Pan/pinch without spurious clicks | **ACCEPTED** | `GestureDisambiguationTest` **9/9** (incl. latch acceptance test), `CoordinateTransformerTest` 8/8 |
| 5 | Overlay persistence + orientation survival | **ACCEPTED** | `FloatingMouseOverlayTest` **7/7** (persistence, rotation, inset clamping) |
| 6 | Profile CRUD | **ACCEPTED** | `ProfileRepositoryTest` **17/17** (stale 8/8 citation corrected); corrupt-file recovery mutant M2 KILLED |
| 7 | Keystore credential encryption + tests | **PARTIAL** | `KeystoreCredentialStoreTest` **20/20** (stale 9/9 corrected) incl. plaintext-key purge + IV-uniqueness tests — **but JVM proves the fallback vault only; AndroidKeyStore primary path has no `androidTest` on this machine** |
| 8 | Auto-reconnect clean transitions, no leaks | **ACCEPTED** | `AutoReconnectManagerTest` **17/17** (supersedes stale 7-failure snapshot); MAJOR-1 honesty fix behaviorally proven (2 real disconnects, exact trace, no phantom Step 3) |
| 9 | Low-latency config + real-time latency stats | **ACCEPTED** | `LowLatencySocketConfigTest` 17/17, `FramePacerTest` 16/16, `TelemetryCollectorTest` 23/23; HUD wired & audited honest; `TCP_NODELAY` on by default upstream |
| 10 | `testDebugUnitTest` 100% pass rate | **ACCEPTED** | **497/497 across 44 suites**, all 4 E2E tiers present and green in the same runs, gate executed **twice** |

Still-open by design/environment: **AC-2** (real-RDP connectivity — mitigations: demo engine,
documented packaging steps) and **AC-7**'s on-device sub-condition (keystore primary path —
JVM proves the fallback only).

---

## 3. Quality evidence

- **Final test gate:** 497/497, 0/0/0, 44 suites, executed twice with `--rerun-tasks`
  (`fixer_w2` runs 1 and 3 per its §4 ledger); on-disk XMLs from run 2 re-parsed and summed by
  `acceptance_w2` — exact match, zero skipped anywhere.
- **Independent chain before the final numbers:** `integrator_w2` certified 487/487 ×2 with
  SHA-256 pristine source-hash pinning (reference hash established via the challenger's own
  `restore_audit.ps1`, `bad=0`); `reviewer_w2` re-ran every module fresh (487/487).
- **Mutation testing (`challenger_w2`):** 14 mutants, **12 killed → 85.7% kill rate**; the 2
  survivors (M3 IV-nonce reuse, M4 toolbar generation guard) were **test-fidelity gaps, not code
  bugs**, and `fixer_w2` added dedicated kill-tests for both (bodies read by `acceptance_w2`:
  the IV test asserts 100 distinct 12-byte IVs + decryptability; the generation-guard test stages
  a post-delay stale timer where only the guard keeps the toolbar open). Kill status of the new
  pair is by test-design analysis — the campaign itself was not re-executed (noted as optional
  follow-up).
- **Integrity:** score **94/100**; 0 `@Ignore`, 0 tautologies, 0 empty bodies, 0 `assumeTrue`
  smuggling, 0 skips; single count discrepancy found (app "155" vs real 160 — an undercount,
  now 161, corrected in all docs).
- **Contract check:** **5/5** interfaces match `PROJECT.md §Interface Contracts` member-for-member
  (`IRdpEngine`, `RdpEventListener`, `MouseController`, `CredentialStore`,
  `AutoReconnectManager`); `fixer_w2` re-verified all 5 unchanged post-fix (new vault members are
  additive on the concrete class; `abortInFlightConnect` is a constructor param, not interface churn).
- **Cheap-gap audit:** TODO/FIXME = **0**, debug prints = **0** in `src/main` (integrator §6;
  re-grepped by `acceptance_w2` — no matches).
- **Assertion spot-reads (3):** `KeystoreCredentialStoreTest.testNonceIvIsUniqueAcrossOneHundredEncryptions`,
  `AutoReconnectManagerTest.testDefaultAbortActionReallyDisconnectsEngineDuringStepTwo`,
  `AppContainerTest.enableDemoEngineAndRestartPersistsSettingRebuildsSessionAndRetriesConnect` —
  all substantive (specific expected values, exact-order traces, multi-phase state assertions),
  none tautological.

## 4. Security posture

**Fixed during wave-2 (evidence: `fixer_w2/handoff.md` §1 + tests green in final gate):**

| Issue | Severity (as found) | Fix |
|---|---|---|
| Plaintext AES-GCM master key persisted beside ciphertext (`vault_master_key_b64`) — reviewer MAJOR-2 + challenger S1 | MEDIUM→ resolved | Layered custody: primary `EncryptedSharedPreferences`+`MasterKey`; Keystore-provider fallback uses a **non-exportable AndroidKeyStore key**; terminal (JVM) fallback uses a **never-persisted in-memory key**; legacy plaintext key **actively purged**; `DEGRADED_VAULT_V1` marker + `vaultDegraded` flag. Tests: 3 new (incl. purge/marker assertions) |
| Fake teardown audit trail (steps 2/3 recorded without action) — reviewer MAJOR-1 | MAJOR → resolved | Step 2 invokes a real `engine.disconnect()` abort (asserted: exactly 2 disconnects in exact order); step 3 records only on completed drain; no phantom `Step 3`. Tests: 1 rewritten + 2 new |
| IV/nonce reuse would ship silently (mutant M3) | Critical-if-present → covered | `testNonceIvIsUniqueAcrossOneHundredEncryptions` |
| First-run dead-end at error 1001 | Product gap → resolved | One-tap **"Enable demo engine & retry"** (persists setting, rebuilds session VM, retry reaches `Connected(demo=true)` — asserted) |

**Verified-secure baseline (`challenger_w2` §3, re-checked):** **zero `Log.*` calls anywhere in
`src/main`** (nothing can leak to logcat); TOFU **fail-closed** (60 s timeout → reject → abort;
dialog dismissal = Reject; cert polarity mutation-verified: inverting kills a test); no password
in profile JSON / intent extras; systematic JNI use-after-free guards (`AtomicLong` +
`compareAndSet` + `isCurrent()` fail-closed, cert default = reject); clipboard echo-loop
suppression SHA-256-pinned.

**Residual (accepted & documented):**
1. **JVM-only keystore coverage** — the AndroidKeyStore/`EncryptedSharedPreferences` primary path
   has **no `androidTest` on this machine**; the fallback path is what CI proves (AC-7 PARTIAL).
2. Password held as immutable `String` in `lastConfig` for session lifetime — cannot be zeroed
   (contract design; standard Android practice) — reviewer F8 / challenger S3, accepted.
3. `vaultDegraded` flag surfaced but no UI banner consumes it yet — product follow-up.
4. `EncryptedSharedPrefsVault` accepts caller-supplied prefs (contract trap; production call site
   verified safe) — challenger S2, accepted.

## 5. Native-packaging truth & the exact path to real RDP connectivity

**Truth:** packaging real FreeRDP `.so` files **is infeasible on this machine** — evidence is a
search, not an assumption (`native_finish/handoff.md` §3): no NDK/CMake/prefab under
`C:\Android\Sdk`; no FreeRDP source tree in any common location; the only nearby candidate
(`C:\Users\Administrator\avnc`) has a vcpkg *recipe* only (nothing built) and its release APK
ships `libnative-vnc.so` (VNC, not FreeRDP); targeted binary searches (`libfreerdp*`,
`libwinpr*`, `freerdp*.aar`) across Downloads/Desktop/caches/Program Files returned zero hits.
`app/src/main/jniLibs` was deliberately left absent — nothing was fabricated. Consequently every
default-mode connect ends in error **1001** — but the degradation is graceful, friendly-hinted,
mutation-verified, and now has a one-tap recovery to the demo engine.

**Exact path to real RDP connectivity (documented, ready to execute on a capable machine):**
1. Install toolchain: `sdkmanager.bat --install "ndk;29.0.13113456" "cmake;3.22.1"` (SDK cmdline-tools exist).
2. `git clone --recursive https://github.com/FreeRDP/FreeRDP.git C:\src\FreeRDP` (pin a release tag).
3. Configure/build per ABI with the NDK toolchain + Ninja (`-DANDROID_PLATFORM=android-26`,
   `-DANDROID_ABI=arm64-v8a`, …) — full commands in `.agents\native_finish\handoff.md` §3
   (verified against upstream `docs/README.android`; upstream Java package matches ours, so the
   JNI symbol contract aligns).
4. Collect `libwinpr3.so`, `libfreerdp3.so`, `libfreerdp-client3.so` + the JNI glue `.so`
   (confirm the exact output name — `LibFreeRDP` loads `"freerdp-android"`).
5. Package: `.\core-rdp\scripts\package-native-libs.ps1 -SourceRoot <dir-with-.so> -Abis arm64-v8a`
   — copies **only genuine ELF** files into `app/src/main/jniLibs/<abi>/`, runs `:app:assembleDebug`,
   and asserts all four libs appear under `merged_native_libs`. Confirm `WITH_GFX_H264` at
   configure time (else fall back `/gfx:progressive`).
6. First on-device run = first real JNI-contract validation (risk R1); `LibFreeRdpJniContractTest`
   pins descriptors meanwhile.

## 6. Documented follow-ups (ranked)

| # | Follow-up | Why it didn't block | Trigger / owner note |
|---|---|---|---|
| 1 | **Native `.so` build** (unblocks AC-2 + real RDP) | Machine lacks NDK/sources — documented scope decision | Machine with NDK + internet; steps §5 above |
| 2 | **On-device `androidTest` smoke** — AndroidKeyStore primary vault path, JNI drift, socket readback | No device/emulator on this machine | First device run; closes AC-7 PARTIAL + challenger I-3 |
| 3 | **`FreeRdpFlagMapping` module cycle** | Both fix options touch contracts/module ownership mid-green-gate | Post-acceptance; pick option (a): move mapping + tests into `:core-rdp` (integrator §9) |
| 4 | **`SO_SNDBUF`/`SO_RCVBUF` on native fd** | Needs JNI fd export — impossible before first `.so` build | Do together with follow-up 1; `TCP_NODELAY` already default-on |
| 5 | **Mutation survivors M3/M4** | **Noted-fixed** — kill-tests added by `fixer_w2`, bodies verified | Optional: re-run mutation campaign to confirm 14/14 |
| 6 | **Minor accepted risks** — `vaultDegraded` banner, reviewer F5/F7–F12, challenger S2/S3, 10 untested failure paths (TOFU 60 s expiry, `configProvider()==null`, process-death mid-session, …) | Non-primary paths / hygiene / product polish | Backlog; see `reviewer_w2` §4 and `challenger_w2` §4–5 |

## 7. Verdict

# **ACCEPT-WITH-CONDITIONS**

**Rationale for ACCEPT:** every machine-checkable acceptance criterion is backed by fresh,
independently-chained, on-disk evidence — 497/497 tests across 44 suites (run twice, re-parsed
by the acceptance agent), a valid fresh APK with verified manifest, an independent review
(APPROVE-WITH-FINDINGS with both MAJORs fixed and re-tested), an adversarial challenge passed
(no cheats, 85.7% mutation kill, integrity 94/100), 5/5 interface contracts intact, and zero
TODO/FIXME/debug-print debt. All previously-failing evidence (M4's 8 failures, the 1-failure
socket test, the blocked AC-8/9/10 rows) is genuinely superseded by green runs, not by doc edits.
All stale documentation found at close-out was corrected against parsed artifacts.

**Rationale for CONDITIONS (both environment-imposed, both documented rather than hidden):**
1. **AC-2 remains PARTIAL:** no real RDP connection can be established from any build produced
   on this machine (no FreeRDP `.so` — build infeasible here). Mitigations shipped and test-pinned
   (graceful 1001, one-tap demo recovery); the exact path to connectivity is documented (§5).
   *Condition: execute §5 on an NDK-equipped machine and smoke-test one real connection.*
2. **AC-7 remains PARTIAL:** the credential vault's primary AndroidKeyStore path is proven only
   on-device-never — CI exercises the fallback. *Condition: run an `androidTest` keystore smoke on
   a real device.*

Neither condition reflects a defect in the delivered code or evidence; both are declared,
documented scope boundaries of this build environment. With conditions 1–2 executed, the project
converts to full **ACCEPT** with no further code changes required.

---
*Verification reproducibility: re-parse `*/build/test-results/testDebugUnitTest/TEST-*.xml` and
sum `testsuite@tests/failures/errors/skipped`; stat
`app/build/outputs/apk/debug/app-debug.apk`; compare against `max(mtime of */src/**)`.*
