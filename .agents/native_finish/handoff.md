# Handoff: native_finish (`:core-rdp`)

**Status: DONE** — `:core-rdp:testDebugUnitTest` **95/95 green on two consecutive forced runs**; predecessor's leftovers verified (one stale KDoc fixed, one already-applied test fix confirmed); native packaging verdict = **INFEASIBLE on this machine (no NDK, no sources, no prebuilt .so) — graceful path verified, exact build steps recorded below**; NativeFreeRdpEngine audit complete; all 3 cross-module questions answered.
**Date:** 2026-09-23
**Agent:** `native_finish` (continuing `native_core`, which errored mid-debug and wrote no handoff)

---

## 1. Test proof (twice, on the final state)

```powershell
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot"
cd C:\Users\Administrator\teamwork_projects\android_rdp_client
.\gradlew.bat :core-rdp:testDebugUnitTest --rerun --console=plain
```

| Run | When | Result | Evidence |
|---|---|---|---|
| 1 | 03:24:51 (local) | **BUILD SUCCESSFUL, 95 tests, 0 failed** | fresh XMLs, exit 0 |
| 2 | 03:25:12 (local) | **BUILD SUCCESSFUL, 95 tests, 0 failed** (`1 executed` task) | fresh XMLs, exit 0 |

(An earlier plain run was all `UP-TO-DATE`, so determinism was proven with per-task `--rerun` — no init scripts, no exclusions. Two pre-edit forced runs were also green, so 4 green runs total today.)

| Test class | Tests | Failed |
|---|---:|---:|
| ClipboardHandlerTest | 6 | 0 |
| DisplayControlHandlerTest | 5 | 0 |
| LibFreeRdpJniContractTest | 13 | 0 |
| MockRdpEngineParityTest | 6 | 0 |
| MockRdpEngineTest | 10 | 0 |
| NativeFreeRdpEngineArgsTest | 8 | 0 |
| NativeFreeRdpEngineLifecycleTest | 22 | 0 |
| NativeFreeRdpEngineStressTest | 7 | 0 |
| ProtocolStressTest | 14 | 0 |
| RdpPointerFlagsTest | 4 | 0 |
| **TOTAL** | **95** | **0** |

Operational notes: `shell_command` runs **cmd.exe** on Windows — `$env:JAVA_HOME=…` PowerShell syntax fails there ("filename, directory syntax is incorrect"). Use the `powershell` tool for Gradle commands. Also: Windows PowerShell 5.1 parses BOM-less UTF-8 via the ANSI codepage on this machine, and non-ASCII bytes (em dashes) can surface as curly quotes that **terminate PowerShell strings early** — keep `.ps1` files pure ASCII (my first draft of the packaging script failed to parse for exactly this reason; rewritten and re-validated, 0 parse errors).

## 2. Predecessor issues found & resolved

1. **No handoff** → this document.
2. **Its last words** ("The FakeNative records `start:$inst` not `start`, so `indexOf("start")` is -1"): the fix is **already in the file** — `NativeFreeRdpEngineLifecycleTest.connectSucceedsWhenNativeSignalsSuccessFromWorkerCallback` now uses `fake.calls.indexOfFirst { it.startsWith("start") }` (line 196) with a combined ordering assertion. No `indexOf("start")` remnant anywhere (grep-verified). No test was failing when I started; no assertion needed weakening or changing.
3. **`FreeRdpNative.kt` in `src/main` — VERDICT: KEEP (legitimate injectable seam, not a test double).** It declares `interface FreeRdpNative` + `object JniFreeRdpNative : FreeRdpNative` which delegates every call to the real `LibFreeRDP` static JNI bridge, guarded by `isNativeLoaded()`/`UnsatisfiedLinkError`. `NativeFreeRdpEngine` takes `private val native: FreeRdpNative = JniFreeRdpNative` as a constructor default, so **production always runs the JNI path**; tests inject their fakes (`FakeNative` lives inside `NativeFreeRdpEngineLifecycleTest.kt`, i.e. in `src/test`). This is exactly the "interface + JNI impl + JVM-test impl" pattern mission point 2 allows.
4. **`MockRdpEngine.kt` in `src/main` — also legitimate**: it is the product's **Demo engine** instantiated by production code at `app/src/main/java/com/freerdp/client/di/AppContainer.kt:84` (native-missing → demo fallback per app_product handoff). Not a test double despite its KDoc first line; no move warranted.
5. **Stale KDoc fixed**: `LibFreeRDP.setNativeCallbacks` javadoc referenced `NativeFreeRdpEngine#doDisconnect()` — no such method; now says `#teardownSession()` (comment-only edit, recompiled + retested).
6. Predecessor test files all present and coherent: `LibFreeRdpJniContractTest` (13), `MockRdpEngineParityTest` (6), `NativeFreeRdpEngineLifecycleTest` (22) — all green, no weakened assertions found (spec encodings for parity/args/lifecycle/stress are intact; I read every `:core-rdp` test).

## 3. Native packaging VERDICT

**INFEASIBLE to package real `.so` files on this machine today. Nothing was fabricated; `app/src/main/jniLibs` was left absent.**

Evidence (each searched, not assumed):
- **This repo**: no `*.so` anywhere except pre-existing `libandroidx.graphics.path.so` under `app/build/intermediates/…merged_native_libs…` (AndroidX artifact, unrelated). No `jniLibs`, no `freerdp*.aar` (only this project's own module AARs in `build/outputs/aar/`).
- **NDK/CMake**: `C:\Android\Sdk` contains only `.temp, build-tools, cmdline-tools, licenses, platform-tools, platforms` — **no `ndk/`, no `cmake/`, no `prefab/`** → native compilation is impossible as installed.
- **`C:\Users\Administrator\avnc`**: `extern/libvncserver` + `extern/vcpkg` submodules populated; `extern/vcpkg/ports/freerdp/portfile.cmake` exists but `extern/vcpkg/installed/` does **not** (recipe only, nothing built). Zero `.so` in the tree; no `.cxx` build dir; `release\VNC-android-free.apk` contains only `libnative-vnc.so` + `libandroidx.graphics.path.so` (VNC, not FreeRDP).
- **Targeted binary search** (`libfreerdp*`, `libwinpr*`, `freerdp*.aar`): Downloads, Desktop, Documents, `.gradle/caches`, ProgramData, Public, tools, Program Files(x86) → **no hits**.
- **No FreeRDP source tree** anywhere common (`C:\src`, `C:\freerdp`, `C:\vcpkg`, … all absent).
- No NDK + no sources ⇒ the mission's "wire a realistic build" branch does not apply; a multi-hour from-scratch toolchain bootstrap is explicitly out of the default Gradle path.

**Graceful path — VERIFIED end-to-end (this is the product behavior until real `.so` exist):**
- `LibFreeRDP` static loader catches `Throwable` → `isNativeLoaded()==false`, `getLoadError()` names `winpr3` — pinned by `LibFreeRdpJniContractTest.loaderDegradesGracefullyOnJvmWithoutNativeLibraries`.
- `NativeFreeRdpEngine.connect()` short-circuits **before any native call** → `RdpConnectionState.Failed(1001, msg, nativeLibraryAvailable=false, loadError=…)`, returns false, fires `onConnectionFailure(1001, …)` — pinned by `nativeNotLoadedSurfacesTypedFailureWithLoadError` (0 native calls asserted) and `NativeFreeRdpEngineArgsTest.testSafeFailureWhenNativeLibraryNotLoaded`.
- **Surfaced to UI** (read-only evidence): `SessionViewModel.onConnectionFailure` → `postPhase(SessionEvent.ConnectionFailed)` (`SessionViewModel.kt:267–269`) → reducer `SessionPhase.Failed(friendlyHint = friendlyHintFor(1001))` (`SessionPhase.kt:38,48,80–85`, `NATIVE_UNAVAILABLE = 1001`) → rendered at `SessionScreen.kt:397` (`failure.friendlyHint`). App-side pins: `SessionViewModelTest.kt:85–93`, `SessionPhaseReducerTest.kt:36–48`.

**EXACT steps to package real natives when tooling is available** (commands verified against upstream `docs/README.android` on FreeRDP master — the upstream Java package is `com.freerdp.freerdpcore.services.LibFreeRDP`, identical to ours, so the JNI symbol contract matches):

```powershell
# 1. Install toolchain (SDK has cmdline-tools; NDK/CMake currently missing)
C:\Android\Sdk\cmdline-tools\latest\bin\sdkmanager.bat --install "ndk;29.0.13113456" "cmake;3.22.1"

# 2. Get FreeRDP 3.x sources (pin a release tag in production)
git clone --recursive https://github.com/FreeRDP/FreeRDP.git C:\src\FreeRDP

# 3. Configure + build per ABI from the FreeRDP project root (Ninja + NDK toolchain;
#    the superbuild auto-fetches OpenSSL/OpenH264/FFmpeg/Opus/cJSON/… — needs internet)
$env:ANDROID_NDK = "C:\Android\Sdk\ndk\29.0.13113456"
cmake -S client/Android/Studio/freeRDPCore/src/main/cpp `
      -B build/android-arm64 `
      --toolchain $env:ANDROID_NDK\build\cmake\android.toolchain.cmake `
      -DANDROID_ABI=arm64-v8a `
      -DANDROID_PLATFORM=android-26 `          # = this app's minSdk (upstream sample uses 23)
      -DCMAKE_BUILD_TYPE=Release `
      -GNinja
cmake --build build/android-arm64
# Repeat with -DANDROID_ABI=armeabi-v7a|x86_64|x86 for other ABIs.

# 4. Collect from the build tree: libwinpr3.so, libfreerdp3.so, libfreerdp-client3.so,
#    and the JNI glue .so (upstream CMake target from client/Android — confirm the exact
#    output name in the build tree; LibFreeRDP loads it as "freerdp-android").

# 5. Package + verify with the helper shipped in this module:
.\core-rdp\scripts\package-native-libs.ps1 -SourceRoot <dir-with-.so> -Abis arm64-v8a
#    → copies ONLY genuine ELF files into app/src/main/jniLibs/<abi>/ (refuses non-ELF),
#      runs :app:assembleDebug, then asserts all four libs exist under
#      app/build/intermediates/merged_native_libs/debug/mergeDebugNativeLibs/out/lib/<abi>/.
```

Notes: verify the FreeRDP build has `WITH_GFX_H264` (superbuild fetches OpenH264, so `/gfx:AVC444|AVC420` is likely available — risk R6). `:app:assembleDebug` was **not** run by me (nothing to package; app_finish owns `app/src/main` and was building concurrently).

## 4. `NativeFreeRdpEngine` audit (mission point 4) — all verified

- **5-step leak-free teardown** (`teardownSession`, always under `lifecycleLock`): ① cancel display-control work ② `disconnectSession` abort (only if worker started) ③ bounded `sessionEndWaitMs` wait on the JNI end-signal deferred (latch completes *before* the callback takes the lock → no deadlock) ④ `freeInstance` under `nativeLock` **write** lock with `compareAndSet` (dispatchers hold the read lock → pointer never observed freed) ⑤ identity-checked callback unregistration + echo-state/framebuffer clear. State/listener notification deliberately happens *after* teardown in the caller → deterministic observer ordering. Pinned by `disconnectExecutesFiveStepTeardownInOrderAndNotifiesOnce`, `disconnectIsIdempotentAndFreesExactlyOnce`, `connectDoesNotEmitPhantomDisconnectEvent`, `parseFailureFreesInstanceWithoutStartingWorker`, `connectTimesOutWhenWorkerNeverSignals`, and `concurrentConnectSerializedByLifecycleMutex` (alloc/free accounting: `new == frees` after disconnect → no leak under 4-way races). Mid-session failure/server-drop intentionally defer the free to the next lifecycle call (JNI thread still unwinding) — pinned by `reconnectAfterServerDrop…`/`midSessionFailure…`.
- **`buildFreeRdpArgs`**: `/gdi:sw` always (Android GFX requirement), IPv6-bracketed `/v:`, `/u:` `/d:` `/p:` `/size:` `/bpp:`; **NLA>TLS>RDP precedence** (`enableNla` → `/sec:nla` + full cred triple; else `enableTls` → `/sec:tls`; else `/sec:rdp`); **`/cert:ignore`** (canonical 2.x/3.x form — deprecated `/cert-ignore` would fail `freerdp_parse_arguments` with `allowUnknown=FALSE`, and the test explicitly asserts absence of the old spelling); explicit `+/-clipboard`, `+/-disp`, `+dynamic-resolution`; all five presets emit their flag sets. **vs product TOFU**: with default `ignoreCertificate=false` no cert arg is emitted → FreeRDP invokes `OnVerifyCertificateEx` → app's blocking TOFU dialog decides; `ignoreCertificate=true` → silent accept (dialog skipped) — consistent with app_product's trusted-store "silent re-accept". Full matrix pinned by `NativeFreeRdpEngineArgsTest` (8) + `NativeFreeRdpEngineStressTest.testNullAndBoundaryConfigs…`.
- **Clipboard round-trip**: local send → `ClipboardHandler` (SHA-256 echo state) → `sendClipboardText` native; remote → `onRemoteClipboardChanged` → handler suppresses echo of our own last send, dispatches genuinely-new text as UTF-16LE `CF_UNICODETEXT`; echo state cleared on teardown. Pinned native + mock parity (`clipboardRoundTripSuppressesEchoAndDispatchesNewText`, `MockRdpEngineParityTest`, `ProtocolStressTest` 1 MB/surrogate/1000-cycle tests).
- **Resolution round-trip**: `updateResolution` → debounced `DisplayControlHandler` → `sendMonitorLayout` (real mm passthrough) **and** `onResolutionChanged` even with no session (mock parity); server-driven resize via `OnSettingsChanged`/`OnGraphicsResize` → framebuffer recreate + listener. Physical-mm assertions pinned.
- **Listener thread-safety**: `@Volatile eventListener`/`activeConfig`; callbacks arrive on JNI-attached threads (documented contract); `isCurrent(inst)` stale-pointer filter; `userDisconnecting` suppresses double-notification when the glue echoes our own abort synchronously; static `LibFreeRDP` dispatch is null-safe (contract test fires every `On*` with no listener). Pinned by `staleCallbacksForOldInstances…`, `instanceZeroCallbacks…`, `testEventListenerConcurrencySafety`, `testConcurrentDisconnectHammer`, input/disconnect race (32 threads × 200 ops) — zero exceptions.
- **Session metrics**: sliding-1 s frame window + cumulative `frameCount` under `frameLock`, injected `frameTimeSource` for determinism, `updateMetrics` snapshot-replace preserves/sets fields (RTT injection survives frame updates). Pinned by `frameMetricsUpdateFromGraphicsCallbackCadence` + `updateMetricsReplacesSnapshotLikeMockSetMetrics`.
- **Cert polarity**: `1 = accept & persist`, `0 = reject`; fail-closed on missing listener *and* stale instance (see Q3 below).

## 5. Cross-module questions — answers

### Q1 (latency_perf §4.1) — Consume `FreeRdpFlagMapping.cliArgs` for presets?
**Not feasible inside `:core-rdp` today — direction is wrong and would create a cycle.** `feature-telemetry/build.gradle.kts:32` = `implementation(project(":core-rdp"))`. Consuming `com.freerdp.feature.telemetry.preset.FreeRdpFlagMapping` from core-rdp inverts that edge → Gradle cycle. **Documented as follow-up** (no hack attempted). Current state is closer than the request implies: `buildFreeRdpArgs` already emits per-preset flag sets natively for core-rdp's **five** presets (telemetry's mapping covers four; names differ `LOW_LATENCY/BALANCED` vs `BALANCED_MOBILE`). **Delta to close in the follow-up**: mapping additionally carries `/compression-level`, `/gfx:AVC444|AVC420|progressive`, `/audio-mode`, static `-fonts`/perf extras — core-rdp emits none of these yet (adding `/gfx:*` requires confirming `WITH_GFX_H264`, risk R6). Clean options: (a) move `FreeRdpFlagMapping` (+ its test) down into `:core-rdp` and have telemetry depend on it (keeps data single-sourced, no cycle), or (b) add an `extraCliArgs: List<String>` passthrough on `RdpConnectionConfig` that the app fills from the mapping. Both touch feature-telemetry/app ownership → **owner hand-off required**. Confirmed invariant kept: no `/fps` and no `/auto-reconnect*` is/are ever emitted (telemetry owns reconnects).

### Q2 (latency_perf §4.2) — Socket tuning on the native fd; TCP_NODELAY default?
**ANSWERED from FreeRDP source (`libfreerdp/core/tcp.c`, master): FreeRDP sets `TCP_NODELAY` on by default.** `freerdp_tcp_default_connect()` calls `freerdp_tcp_set_nodelay(…)` unconditionally for every non-IPC, non-external socket, which does `setsockopt(sockfd, IPPROTO_TCP, TCP_NODELAY, …)` on `SOCK_STREAM` fds. In the same path FreeRDP also: enforces an **`SO_RCVBUF` floor of 32 KB** (raises smaller buffers; leaves ≥32 KB alone — kernel doubling ⇒ ≥64 KB effective), and applies **`SO_KEEPALIVE` + `TCP_KEEPIDLE/KEEPCNT/TCP_KEEPINTVL` + `TCP_USER_TIMEOUT`** from the `FreeRDP_TcpKeepAlive*` / `FreeRDP_TcpAckTimeout` settings. It does **NOT** set `SO_SNDBUF`, and does **not** raise `SO_RCVBUF` to latency_perf's desired 128 KB. → latency option **(b) is confirmed** for TCP_NODELAY: nothing to do. **Residual gap**: buffer sizes have no CLI flag and no Java surface exposes the fd; applying them needs either a small JNI addition to `client/Android/android_freerdp.c` (export the transport fd / a `set_socket_options` entry — do it together with the first real `.so` build) or accepting FreeRDP's defaults. Recommendation: accept defaults for v1 (NODELAY is the latency-critical one and it's on), revisit buffers when the native build exists. `LowLatencySocketConfig` remains relevant only for its own `java.net` sockets, not FreeRDP's fd.

### Q3 (app_product) — `onVerifyCertificateEx` bridged to `RdpEventListener.onCertificateVerification`?
**Already wired in `:core-rdp` — no fix needed; app_product's note is stale.** Chain: native glue → `LibFreeRDP.OnVerifyCertificateEx|OnVerifyChangedCertificateEx|OnVerifyCertificate(Legacy)|OnVerifyChangedCertificate(Legacy)` → `NativeFreeRdpEngine.verifyCertificate(inst, host, fingerprint)` → **`eventListener.onCertificateVerification(fingerprint, host)`** → mapped to FreeRDP `accept_certificate` polarity (`libfreerdp/crypto/tls.c`: **1 = accept & persist to known_hosts, 0 = reject**), fail-closed (no listener → 0, stale instance → 0 without consulting the listener). Pinned by `certificateVerificationMapsBooleanToNativePolarity` (accept→1+finger/host delivered, reject→0, stale→0, listenerless→0), `LibFreeRdpJniContractTest.certificateResultIsForwardedVerbatim` (static layer forwards 2/0 verbatim) and `staticCallbacksAreNullSafe…` (no listener → reject), plus mock parity. Reachability caveat: needs a real `.so` delivering the callback **and** `ignoreCertificate=false` (the default) — when `ignoreCertificate=true`, `buildFreeRdpArgs` emits `/cert:ignore` and FreeRDP skips the callback entirely (silent accept), which matches the product's trusted-store semantics.

## 6. Files changed (by me)

| File | Change |
|---|---|
| `core-rdp/src/main/java/com/freerdp/freerdpcore/services/LibFreeRDP.java` | KDoc only: `#doDisconnect()` → `#teardownSession()` (method was renamed by the lifecycle rework; reference was dangling) |
| `core-rdp/scripts/package-native-libs.ps1` | **New** — packaging + verification helper (copies only genuine ELF `.so`, refuses non-ELF, runs `:app:assembleDebug`, asserts the four libs in `merged_native_libs`) |
| `.agents/native_finish/handoff.md` | This document |

Predecessor files **verified, kept unchanged**: `core-rdp/src/main/.../engine/FreeRdpNative.kt` (seam verdict §2.3), `LibFreeRdpJniContractTest.kt`, `MockRdpEngineParityTest.kt`, `NativeFreeRdpEngineLifecycleTest.kt` and its edits to existing tests. No `app/`, `feature-*`, or `*.md` (docs) touched; `app/src/main/jniLibs` deliberately left non-existent.

## 7. Risks

- **R1 — No native session is possible on this machine yet.** Everything native is covered by JVM fakes + the contract test; first on-device run may still surface JNI drift that reflection can't (arg spellings against the actual `freerdp_parse_arguments` build, callback descriptors vs the exact `.so`). The contract test pins descriptors, but only loading a real `.so` proves it.
- **R2 — FreeRDP version drift**: contract comments were verified against **master** (`client/Android/android_freerdp.c` listing in `LibFreeRDP` KDoc); pin a release tag when building.
- **R3 — Unverifiable legacy name**: the KDoc's stable-2.0 list mentions `OnResolveChangedCertificate` while we declare `OnVerifyChangedCertificate` (pinned by the contract test). Upstream 2.x paths 404'd and GitHub code search needs auth — **could not verify**; affects only optional FreeRDP-2.x compatibility, not the 3.x path (`OnVerifyChangedCertificateEx`). Open question for whoever next touches the JNI contract.
- **R4 — Shared Gradle** (latency R6): `app_finish` builds concurrently; plain runs waited out lock contention fine, `--rerun` per-task worked; no init-script/exclusion hacks used, per mission.
- **R5 — Stress tests use real threads** (disconnect hammer 64, input race 32×200, listener swap) with bounded latches/timeouts — most likely flake source under heavy CI load; green in all 4 runs today.
- **R6 — `/gfx:AVC444|AVC420`** (if the mapping follow-up lands) requires `WITH_GFX_H264` in the FreeRDP build; superbuild fetches OpenH264 but it must be confirmed at configure time — else fall back to `/gfx:progressive`.
- **R7 — JNI glue build knob**: upstream README's standalone command builds `client/Android/Studio/freeRDPCore/src/main/cpp`; the exact output filename for the "freerdp-android" library must be confirmed from the build tree before running the packaging script (script warns if any of the four is missing).

**Final: DONE — :core-rdp 95/95 tests green twice (forced rerun), packaging verdict INFEASIBLE-locally with verified graceful path + exact upstream build/package steps, all 3 cross-module questions answered (cycle-blocked follow-up / TCP_NODELAY-confirmed-on / cert-bridging-already-done).**

Sources:
- [FreeRDP libfreerdp/core/tcp.c (master)](https://github.com/FreeRDP/FreeRDP/blob/master/libfreerdp/core/tcp.c)
- [FreeRDP docs/README.android (master)](https://github.com/FreeRDP/FreeRDP/blob/master/docs/README.android)
- [FreeRDP wiki: Compilation](https://github.com/FreeRDP/FreeRDP/wiki/Compilation)
- [Android NDK CMake guide](https://developer.android.com/ndk/guides/cmake)
