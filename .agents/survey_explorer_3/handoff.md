# Handoff Report — survey_explorer_3 (Architecture & Testing Strategist)

## 1. Observation
1. **User Requirements (R3 & R4):**
   - In `C:\Users\Administrator\teamwork_projects\android_rdp_client\ORIGINAL_REQUEST.md`, lines 18–23 specify:
     ```markdown
     ### R3. Mobile Productivity & Session Management
     Provide a Material 3 interface featuring one-tap profile connections, auto-reconnect on network/sleep changes, secure Android Keystore credential storage, a collapsible quick-action toolbar, and dedicated mobile modifier keys (Ctrl, Alt, Esc, Win, Function keys).

     ### R4. Adaptive Low-Latency Performance & Telemetry
     Implement low-latency socket buffering, background thread isolation for frame decoding/rendering, bandwidth- and battery-aware performance modes (Wi-Fi vs mobile data presets), dynamic orientation and multi-window/foldable support, and session diagnostic telemetry (latency, FPS, connection state). Ensure connection streaming minimizes input lag and frame stutter.
     ```
2. **Acceptance Criteria & Test Standards:**
   - In `C:\Users\Administrator\teamwork_projects\android_rdp_client\ORIGINAL_REQUEST.md`, lines 26–43 dictate:
     - FreeRDP native bindings/AAR dependency isolated in dedicated module/package.
     - Connection profile manager supporting full CRUD operations.
     - Android Keystore / `EncryptedSharedPreferences` encryption with automated unit test validation.
     - Auto-reconnect state machine handling network drop, pause/resume, and reconnect phases without session leaks.
     - `./gradlew testDebugUnitTest` running all unit and Robolectric tests with 100% pass rate.
3. **Dispatch Objectives:**
   - In `C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\survey_explorer_3\DISPATCH.md`, lines 10–15 require analysis of R3 and R4, clean modular Android architecture and package breakdown, and comprehensive testing architecture (unit, Robolectric, gestures, session state machine, and opaque-box E2E test harness).
4. **Produced Deliverables:**
   - Authored comprehensive architectural blueprint and test strategy in `C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\survey_explorer_3\report.md` (Total 600+ lines covering all technical domains).

## 2. Logic Chain
1. **From Requirement R3 to Architectural Specifications:**
   - *Observation 1 & 2:* The system demands Material 3, one-tap profile connections, CRUD profile management, and secure Keystore credential storage.
   - *Deduction:* Remote desktop credentials (passwords, tokens) present high security risk if stored unencrypted. Relying on basic Android SharedPreferences or plain files violates R3 and acceptance criterion line 37. Therefore, Jetpack Security (`EncryptedSharedPreferences` backed by `MasterKey.Builder` using AES-256-GCM and `AndroidKeyStore`) must be isolated into a dedicated `CredentialStore` interface. Public profile metadata (`RdpProfile`) is decoupled from secret vaults to allow clean database querying and backup exclusion without secret exposure.
   - *Deduction:* Mobile virtual keyboards lack desktop modifier keys (`Ctrl`, `Alt`, `Win`, `Esc`, `Function Keys`). To satisfy line 19, an on-screen helper bar requires a formal 3-state latching machine (`INACTIVE` -> `LATCHED` for single-next-key -> `LOCKED` for multi-key hold) paired with standard Windows Scan Code Set 1 translations and macros (Ctrl+Alt+Del, Alt+Tab).

2. **From Requirement R4 to Low-Latency Pipeline & Auto-Reconnect State Machine:**
   - *Observation 1 & 2:* Mobile connectivity drops, pauses, and re-establishes across Wi-Fi and cellular networks, while interactive rendering requires minimal lag.
   - *Deduction:* Standard TCP socket buffering causes bufferbloat (delayed ACKs and stale queued frames). Setting `TCP_NODELAY`, tuning `SO_RCVBUF`/`SO_SNDBUF` to 128KB/64KB, and using FastPath RDP PDUs eliminates transport-induced latency.
   - *Deduction:* To prevent UI freezing and frame stutter, thread isolation requires 5 dedicated pipelines: Socket I/O (`RdpIoThread`), Packet Decoding (`RdpDecoderThread`), Surface Rendering (`RdpRenderThread`), Input Event Coroutine Channel, and the Android Main Thread.
   - *Deduction:* To satisfy acceptance criterion line 38, reconnection cannot be an ad-hoc loop; it must be governed by an explicit Finite State Machine with exponential backoff ($T_{backoff} = \text{random}(0, \min(30s, 1s \cdot 2^{attempt}))$), fast-path network recovery via `ConnectivityManager.NetworkCallback`, and a strict 5-step native teardown protocol (`freerdp_abort_connect` -> thread join -> context deallocation) to guarantee zero memory or socket leaks.

3. **From Acceptance Criteria to Multi-Tier Testing Architecture:**
   - *Observation 2:* The project requires 100% test pass rate in `./gradlew testDebugUnitTest` for Keystore encryption, gesture simulations, and session transitions without relying on live RDP servers or physical devices.
   - *Deduction:* Standard JVM tests fail on `KeyStore` operations because `keystore2` is missing. Robolectric with `@Config(sdk = [33])` and its built-in `ShadowKeyStore` provides full in-memory support for Jetpack Security `MasterKey` and `EncryptedSharedPreferences`.
   - *Deduction:* Physical gestures and session transitions are tested deterministically via:
     - Synthetic `MotionEventBuilder` simulating touch events to test tap vs pan vs pinch-zoom disambiguation and coordinate mapping.
     - Controllable `MockRdpEngine` test double simulating connection success, timeout, network failure, and packet recording without native C library dependencies.
     - 4-Tier Opaque-Box E2E test harness covering Build/Wiring (Tier 1), Profile/Keystore Security (Tier 2), Session Lifecycle/Reconnect (Tier 3), and Gestures/Modifiers (Tier 4).

## 3. Caveats
1. **Robolectric Keystore Behavior:** Robolectric's `ShadowKeyStore` emulates Android Keystore within JVM memory. While cryptographically identical for AES-GCM operations, it does not validate hardware-bound TEE or StrongBox hardware attestation (`KeyInfo.isInsideSecureHardware()`), which must be marked optional/fallback in production code.
2. **Build Environment & JNI Toolchain:** This investigation is strictly read-only and architectural. Actual native compilation of FreeRDP C/C++ libraries depends on findings from `survey_explorer_1` regarding NDK/CMake presence on this host machine. The architecture deliberately isolates native bindings behind `IRdpEngine` so that pure Kotlin layers and all unit/Robolectric tests build and pass regardless of host NDK availability.

## 4. Conclusion
The architectural and testing blueprint for R3 and R4 is complete, fully specified, and documented in `report.md`. The design provides:
1. Complete JNI isolation via `IRdpEngine` and lightweight `AppContainer` dependency injection.
2. Production-grade Keystore encryption via Jetpack Security with full memory hygiene (`CharArray` zeroing) and backup exclusion.
3. 5-thread low-latency rendering pipeline with atomic single-slot frame pacing and `TCP_NODELAY`.
4. Deterministic Auto-Reconnect FSM with exponential backoff and leak-free native teardown.
5. Multi-tier test strategy allowing 100% of unit, Robolectric, and E2E workflow tests to execute cleanly under `./gradlew testDebugUnitTest`.

## 5. Verification Method
1. **Artifact Inspection:**
   - Inspect `C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\survey_explorer_3\report.md` for full implementation details, mathematical formulas, state machine tables, and test code snippets.
2. **Acceptance Criteria Traceability:**
   - Cross-reference Section 6 of `report.md` against lines 26–43 of `C:\Users\Administrator\teamwork_projects\android_rdp_client\ORIGINAL_REQUEST.md`.
3. **Invalidation Conditions:**
   - The architectural design would be invalidated if:
     - Jetpack Security `EncryptedSharedPreferences` fails in Robolectric 4.10+ on SDK 33.
     - FreeRDP native core cannot be abstracted behind a standard asynchronous Kotlin interface.
