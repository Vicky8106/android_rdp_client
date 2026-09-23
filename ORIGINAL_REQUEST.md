# Original User Request

## Initial Request — 2026-09-22T15:08:29Z

Build a production-quality, mobile-first Android RDP client using FreeRDP Android as the core RDP engine, optimized for one-handed phone and tablet use with an adaptive floating mouse and gesture system. Crucially, the connection must be optimized for ultra-low latency, smooth real-time rendering, and minimal lag.

Working directory: C:\Users\Administrator\teamwork_projects\android_rdp_client
Integrity mode: development

## Requirements

### R1. Core Remote Desktop Foundation
Integrate FreeRDP Android native components to establish reliable, standards-compliant RDP connectivity supporting NLA, TLS, dynamic resolution resizing, and clipboard synchronization. Keep upstream FreeRDP core bindings modular and isolated for future upgrades.

### R2. Mobile-First Floating Mouse & Touch System
Implement a repositionable, collapsible floating mouse overlay (supporting left/right click, wheel/scroll, double-click, click-and-drag, long-press, touchpad mode, and optional cursor) and touch gestures (pinch-to-zoom, pan-without-clicking) that adapt smoothly across portrait and landscape orientations without permanently obscuring the remote display.

### R3. Mobile Productivity & Session Management
Provide a Material 3 interface featuring one-tap profile connections, auto-reconnect on network/sleep changes, secure Android Keystore credential storage, a collapsible quick-action toolbar, and dedicated mobile modifier keys (Ctrl, Alt, Esc, Win, Function keys).

### R4. Adaptive Low-Latency Performance & Telemetry
Implement low-latency socket buffering, background thread isolation for frame decoding/rendering, bandwidth- and battery-aware performance modes (Wi-Fi vs mobile data presets), dynamic orientation and multi-window/foldable support, and session diagnostic telemetry (latency, FPS, connection state). Ensure connection streaming minimizes input lag and frame stutter.

## Acceptance Criteria

> **Evidence policy (product_ux, snapshot 2026-09-22T19:50Z):** a box is checked only where
> concrete evidence was found **inside this repository** — a named passing test in a machine-generated
> `build/test-results/**/TEST-*.xml` artifact whose result post-dates the last source edit, or a
> verified APK artifact cited in an agent handoff. Boxes with no such evidence, or with *failing*
> evidence, remain unchecked. All ticks are provisional until wave-2 independent re-verification;
> `docs/ACCEPTANCE.md` is the authoritative fill-in ledger for the final acceptance agent (which
> must cite fresh artifacts, and un-tick any box its re-run overturns).
>
> **Final pass (`acceptance_w2`, 2026-09-23):** every box below was re-verified against on-disk
> artifacts — all 44 `TEST-*.xml` files re-parsed (497 tests / 0 failures / 0 errors / 0 skipped),
> APK freshness checked against the newest source edit, and cited tests spot-read for substantive
> assertions. Ticks now reflect the **final gate** (`fixer_w2`: 497/497 run twice; `reviewer_w2`
> APPROVE-WITH-FINDINGS with both MAJORs fixed; `challenger_w2` CHALLENGE-PASS). Per-criterion
> detail lives in `docs/ACCEPTANCE.md`.

### Build & Compilation
- [x] `./gradlew assembleDebug` completes with zero errors and outputs a valid debug APK.
  - **Evidence (fresh, re-verified):** `assembleDebug --rerun-tasks` BUILD SUCCESSFUL (153/153 tasks, exit 0) → `app/build/outputs/apk/debug/app-debug.apk`, **17,205,283 B, mtime 2026-09-23 04:43:23 local**. `acceptance_w2` directly verified: APK mtime **> newest source edit (04:41:24)**, and the merged manifest contains `android.permission.INTERNET` + `com.freerdp.client.App`. Supersedes both the M1-era APK (16,728,898 B) and integrator's pristine-pinned rebuild (17,188,899 B @ 04:07:39).
- [ ] FreeRDP native bindings/AAR dependency is correctly linked and isolated in a dedicated module/package.
  - **Evidence (partial — remains unticked):** isolation ✅ (`LibFreeRDP.java` + `NativeFreeRdpEngine` confined to `:core-rdp`, JNI audited CLEAN, `LibFreeRdpJniContractTest` 13/13), but **no FreeRDP `.so` exists on this machine** (no NDK/CMake/sources — documented scope decision in `.agents/native_finish/handoff.md` §3 with exact build steps + `core-rdp/scripts/package-native-libs.ps1`). Mitigations shipped: graceful error-1001 UX + one-tap "Enable demo engine & retry", both test-pinned.

### Gesture & Mouse Control State Machine
- [x] Automated unit tests verify all floating mouse overlay events: left-click, right-click, double-click, drag, scroll, and touchpad mode.
  - **Evidence (re-verified final):** `MouseControllerTest` **11/11 passing** (XML mtime 2026-09-23 04:43:41 local, post-dates all `:feature-mouse` sources): the 10 originally-cited tests plus `testExitingTouchpadModeMidDragReleasesAtVirtualCursorWithSingleTransform` (fixer regression, asserts `(660,490)` vs old-bug `(280,220)`). Module suite total **35/35** green. Assertions substantive (MS-RDPBCGR flag sequences via `MockRdpEngine`).
- [x] Touch gesture tests verify pan and pinch-to-zoom operations without spurious touch-to-click triggers.
  - **Evidence (re-verified final):** `GestureDisambiguationTest` **9/9 passing**: `testPanWithoutClickingWhenMovementExceedsTouchSlop`, `testPinchToZoomGesture`, `testMultiTouchLatchEliminatesSpuriousClickOnSequentialFingerLift`, `testSingleTapDetection`, `testDoubleTapDetection`, `testLongPressFiresOnceAbortedByMoveAndSuppressesTapOnRelease` (renamed + strengthened per reviewer F6), `testActionCancelResetsState`, +2 new latch-path tests; plus `CoordinateTransformerTest` 8/8, `MouseControllerTest` 11/11.
- [x] Mouse overlay persists repositioned coordinates and responds to screen orientation switches without resetting or going off-screen.
  - **Evidence (re-verified final):** `FloatingMouseOverlayTest` **7/7 passing** (XML mtime 04:43:41): `testSharedPreferencesSaveAndRestore`, `testOrientationPersistenceAcrossScreenRotation`, `testSafeInsetClampingOnDrag`, `testOverlayStateTransitions`, `testInitialStateIsCollapsed`, `testEdgeSnappingBehavior`, `testControlButtonsDispatchToMouseController`.

### Session, Low-Latency & Security Management
- [x] Connection profile manager supports creating, reading, updating, and deleting server configurations.
  - **Evidence (count corrected):** `ProfileRepositoryTest` **17/17 passing** (XML mtime 2026-09-23 04:43:52 local; the earlier 8/8 citation was pre-expansion/stale): `testFullCrudLifecycle`, `testInitialProfilesListIsEmpty`, `testClearAllRemovesAllProfiles`, `testAtomicWriteSafety`, `testDuplicateProfile`, `testCorruptFileRecoveryRestoresFromBackup`, `testCorruptFileRecoveryReturnsEmptyGracefully`, `testToConnectionConfigBinding` + 9 expansion tests. Independent evidence: `reviewer_w2` fresh run green; challenger mutant M2 (`.bak` never written) **KILLED** (2/17 failed).
- [x] Credentials and sensitive server tokens are encrypted via Android Keystore / EncryptedSharedPreferences with automated unit test validation.
  - **Evidence (count corrected):** `KeystoreCredentialStoreTest` **20/20 passing** (XML mtime 04:43:52; the earlier 9/9 citation was pre-hardening/stale — actual current count re-checked on disk): round trips, tamper, plaintext-absence, unicode, charArray wipe, plus the hardening tests — `testFallbackVaultNeverPersistsPlaintextMasterKeyAndMarksDegradation` (plaintext master key purged, degraded marker), `testFallbackVaultRaisesDegradedFlagInsteadOfDowngradingSilently`, `testNonceIvIsUniqueAcrossOneHundredEncryptions` (100 distinct IVs asserted — body read by `acceptance_w2`). **Caveat (unchanged):** JVM tests exercise only the fallback vault; the AndroidKeyStore primary path has no `androidTest` on this machine — on-device confirmation remains open (`docs/ACCEPTANCE.md` AC-07 `PARTIAL`).
- [x] Auto-reconnect state machine transitions cleanly through network drop, pause/resume, and reconnect phases without crashing or leaking native RDP sessions.
  - **Evidence (fresh — supersedes the stale 7-failure snapshot):** `AutoReconnectManagerTest` **17/17 passing** (XML mtime 2026-09-23 04:44:11 local, 0 failures) in a feature-telemetry module total of **120/120**, final gate run twice at 497/497. Includes the MAJOR-1 honesty fix proven behaviorally: `testDefaultAbortActionReallyDisconnectsEngineDuringStepTwo` asserts 2 real `engine.disconnect()` calls in exact trace order and no phantom `Step 3` (body read by `acceptance_w2`). Challenger mutant M6 (backoff exponent) KILLED.
- [x] Network buffering, frame rate pacing, and rendering dispatch loop are configured to prioritize low-latency interactivity and report real-time ping/latency stats.
  - **Evidence (fresh — supersedes the stale 1-failure snapshot):** `LowLatencySocketConfigTest` **17/17**, `FramePacerTest` **16/16** (challenger mutants M5/M14 KILLED), `TelemetryCollectorTest` **23/23** — all 0 failures, XML mtime 04:44:11. HUD (FPS/RTT/jitter) is wired in `:app` `SessionScreen` and audited honest (no synthetic numbers) by `challenger_w2` §5.2; `TCP_NODELAY` confirmed default-on upstream (`native_finish` Q2).

### Automated Test Suite
- [x] `./gradlew testDebugUnitTest` runs all unit and Robolectric tests and passes with 100% success rate.
  - **Evidence (fresh):** `acceptance_w2` independently parsed all on-disk `TEST-*.xml`: **44 suites / 497 tests / 0 failures / 0 errors / 0 skipped** across all 5 modules (app 161 = 74 e2e + 87 unit; core-rdp 95; feature-mouse 35; feature-session 86; feature-telemetry 120). `fixer_w2` ran the full gate **twice** at 497/497 with `--rerun-tasks`; `integrator_w2` previously certified 487/487 ×2 with pristine source-hash pinning. All four E2E tiers present at `app/src/test/java/com/freerdp/client/e2e/` (Tier1 35, Tier2 26, Tier3 8, Tier4 5) and green in the same runs.

### Evidence ledger (criterion → module → proving tests)

| # | Criterion (short) | Owning module | Planned proving tests (TEST_INFRA) | Final status (2026-09-23) |
|---|-------------------|---------------|------------------------------------|---------------------------|
| AC-1 | assembleDebug → valid APK | all / `:app` | Compilation Gate (§5.1) | **ACCEPTED** — APK 17,205,283 B @ 04:43:23, fresher than all sources |
| AC-2 | FreeRDP bindings linked & isolated | `:core-rdp` (`IRdpEngine`) | Tier 1 F01/F02 + packaging gate | PARTIAL — isolation proven; `.so` packaging OPEN (documented infeasible-on-this-machine, build steps shipped) |
| AC-3 | Mouse overlay event tests | `:feature-mouse` (`MouseController`) | Tier 1 F07–F10; Tier 3 #1/#4/#7; Tier 4 SCENARIO-2 | **ACCEPTED** — `MouseControllerTest` 11/11, module 35/35 |
| AC-4 | Pan/pinch w/o spurious clicks | `:feature-mouse` (`GestureDisambiguationEngine`) | Tier 1 F11/F12/F14; Tier 3 #1; Tier 4 SCENARIO-2 | **ACCEPTED** — `GestureDisambiguationTest` 9/9 |
| AC-5 | Overlay persistence/orientation | `:feature-mouse` (`OverlayCoordinates`) | Tier 1 F13; Tier 2; Tier 3 #3; Tier 4 SCENARIO-5 | **ACCEPTED** — `FloatingMouseOverlayTest` 7/7 |
| AC-6 | Profile CRUD | `:feature-session` (`ProfileRepository`) | Tier 1 F15; Tier 3 #2; Tier 4 SCENARIO-1 | **ACCEPTED** — `ProfileRepositoryTest` **17/17** (was mis-cited 8/8) |
| AC-7 | Keystore credential encryption + tests | `:feature-session` (`CredentialStore`) | Tier 1 F16; Tier 3 #2; Tier 4 SCENARIO-1 | **PARTIAL/ACCEPTED-with-caveat** — `KeystoreCredentialStoreTest` **20/20** (was mis-cited 9/9); JVM proves fallback only, on-device primary path open |
| AC-8 | Auto-reconnect clean transitions | `:feature-telemetry` (`AutoReconnectManager`) | Tier 1 F20; Tier 2; Tier 3 #3/#8; Tier 4 SCENARIO-4 | **ACCEPTED** — `AutoReconnectManagerTest` 17/17 (stale 7-failure snapshot superseded) |
| AC-9 | Low-latency config + latency stats | `:feature-telemetry` (socket/pacer/telemetry) | Tier 1 F21–F24; Tier 3 #5; Tier 4 SCENARIO-5 | **ACCEPTED** — 17+16+23/56 green; HUD wired & audited honest |
| AC-10 | Full suite 100% | all | Tiers 1–4 + Execution Gate (§5) | **ACCEPTED** — 497/497, 44 suites, 4 tiers present, gate run ×2 |

Full detail: `docs/ACCEPTANCE.md`.
