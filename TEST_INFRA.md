# Test Infrastructure & Specification Document

**Project**: Mobile-First Android RDP Client (`android_rdp_client`)  
**Document**: `TEST_INFRA.md`  
**Test Suite Root**: `app/src/test/java/com/freerdp/client/e2e/`  
**Target Runner**: `./gradlew testDebugUnitTest`  
**Date**: 2026-09-22  

---

## 1. Testing Philosophy & Guiding Principles

The testing architecture for the Android FreeRDP Mobile Client is constructed upon the following core tenets:

1. **Opaque-Box & Requirement-Driven**:
   Tests are written strictly against the requirements defined in `ORIGINAL_REQUEST.md` and public interface contracts defined in `PROJECT.md § Interface Contracts`. The tests treat components as black boxes, asserting externally observable behavior, state transitions, emitted protocol flags, and data flows rather than inspecting private implementation details.

2. **Deterministic & Headless Execution**:
   Testing interactive remote desktop systems on mobile often suffers from flakiness due to hardware variability, graphics driver differences, or external network latency. This test suite achieves 100% determinism by:
   - Abstracting native C/JNI FreeRDP dependencies behind `IRdpEngine` using the deterministic `MockRdpEngine` test double.
   - Employing Robolectric for Android SDK integration (shadowing Android Keystore, DisplayMetrics, and MotionEvents) on the host JVM.
   - Operating coroutine schedulers via `kotlinx-coroutines-test` (`StandardTestDispatcher` and `TestScope`) to advance virtual time synchronously without arbitrary `Thread.sleep`.

3. **Progressive Testability & Interface Compatibility**:
   All tests conform strictly to the public contracts in `PROJECT.md`:
   - `IRdpEngine` & `RdpEventListener` (`com.freerdp.core.engine`)
   - `MouseController` (`com.freerdp.feature.mouse`)
   - `CredentialStore` (`com.freerdp.feature.session.security`)
   - `AutoReconnectManager` (`com.freerdp.feature.telemetry.reconnect`)
   Tests are structured to execute cleanly as each module milestone completes.

4. **Strict Isolation & Independence**:
   Every test case creates its own fresh instance of test doubles, state machines, and repositories. No state leaks across test boundaries, guaranteeing order independence and parallel test execution safety.

5. **Authoritative Expected Outputs**:
   Every expected output is derived from authoritative protocol standards:
   - Pointer and button flags: **[MS-RDPBCGR]** §2.2.8.1.1.3.1.1
   - Dynamic resolution PDUs: **[MS-RDPEDISP]** §2.2.2.2
   - Clipboard synchronization: **[MS-RDPECLIP]** §3.1.5.2 & §3.1.5.3
   - CredSSP/NLA authentication: **[MS-CSSP]** §3.1.5
   - Windows Scan Code Set 1: Standard PC keyboard scancode specifications

---

## 2. Four-Tier Test Architecture

The E2E test suite is organized into four hierarchical tiers:

```
+-------------------------------------------------------------------------------+
|                       TIER 4: REAL-WORLD SCENARIOS                             |
|  >= 5 realistic end-to-end user workflows (login, coding, navigation, drop)    |
+---------------------------------------+---------------------------------------+
                                        |
+---------------------------------------v---------------------------------------+
|                    TIER 3: CROSS-FEATURE COMBINATIONS                         |
|  Pairwise integration tests verifying cross-subsystem state synchronization   |
+---------------------------------------+---------------------------------------+
                                        |
+---------------------------------------v---------------------------------------+
|                    TIER 2: BOUNDARY & CORNER CASES                            |
|  >= 5 boundary tests per feature (zero dims, max buffers, rapid flips, errors) |
+---------------------------------------+---------------------------------------+
                                        |
+---------------------------------------v---------------------------------------+
|                    TIER 1: CORE FEATURE COVERAGE                              |
|  >= 5 isolated happy-path tests per core feature (R1, R2, R3, R4)             |
+-------------------------------------------------------------------------------+
```

### Tier 1: Core Feature Coverage (`Tier1FeatureCoverageTest.kt`)
- **Scope**: Primary behaviors and happy-path operations for all core features (R1 through R4).
- **Target**: Minimum of 5 test cases per requirement category (minimum 20 tests total).
- **Focus**: Interface contracts, protocol event generation, encryption roundtrips, state machine transitions, and gesture recognition.

### Tier 2: Boundary & Corner Cases (`Tier2BoundaryCornerTest.kt`)
- **Scope**: Extremes, invalid inputs, edge conditions, boundary clamping, and stress states.
- **Target**: Minimum of 5 boundary test cases per requirement category (minimum 20 tests total).
- **Focus**:
  - Empty hostnames, port 0, port 65535, 1MB max clipboard payloads.
  - Zero and negative screen coordinates, viewport boundaries, sub-pixel scaling.
  - Rapid orientation flips, 250ms debounce coalescing, odd dimension alignment (`& ~3`).
  - Maximum reconnect retries exhaustion, zero-delay backoff, corrupt credentials.

### Tier 3: Cross-Feature Combinations (`Tier3CrossFeatureTest.kt`)
- **Scope**: Pairwise feature combinations verifying that subsystems cooperate without mutual interference or race conditions.
- **Target**: Exhaustive pairwise interaction matrix.
- **Combinations Tested**:
  1. *Pinch-to-Zoom + Mouse Click-and-Drag*: Ensuring affine zoom transforms drag coordinates accurately while anti-spurious latch suppresses phantom clicks.
  2. *Keystore Encryption + Connection Profile Launch*: Loading AES-256 encrypted credentials from vault and establishing authenticated NLA session.
  3. *Auto-Reconnect Backoff + Dynamic Orientation Resizing*: Rotating device while network recovery is pending; ensuring resolution PDU updates cleanly on reconnect.
  4. *Modifier Key Latching + Mouse Click-Drag*: Latching `Ctrl` or `Shift` and performing mouse range selection in remote desktop.
  5. *Performance Preset Switching + Low-Latency Socket Buffers*: Dynamic transition from Ultra-Low Latency to Data Saver altering buffer sizes, FPS pacing, and codec options.
  6. *Dynamic Resolution Resizing + Clipboard Synchronization*: Synchronizing clipboard during active display layout renegotiation.
  7. *Touchpad Relative Cursor + Modifier Key Shortcut*: Navigating relative trackpad cursor while sending Windows scancode macro combinations.
  8. *Network Reconnect + Telemetry HUD Metrics*: Verifying telemetry ring buffer correctly records connection state transition from Connected -> Paused -> Connected.

### Tier 4: Real-World Scenarios (`Tier4RealWorldScenariosTest.kt`)
- **Scope**: Multi-step, end-to-end user workflows simulating production daily usage.
- **Target**: Minimum of 5 comprehensive realistic scenarios.
- **Scenarios Tested**:
  1. `SCENARIO-1`: **Enterprise Workstation Login & Task Execution** (NLA handshake -> Desktop canvas ready -> Quick-action toolbar toggle -> Modifier shortcut -> Mouse click).
  2. `SCENARIO-2`: **Multi-Slide Presentation Navigation** (Fullscreen session -> 2-finger pinch-zoom -> Viewport pan -> Right-click context menu -> Wheel scroll through slides).
  3. `SCENARIO-3`: **Remote IDE & Terminal Coding Session** (Latching Ctrl/Alt -> Terminal scancodes -> Text clipboard synchronization -> Drag lock code selection).
  4. `SCENARIO-4`: **Intermittent Cellular Connection & Seamless Recovery** (Active session streaming -> Network drop -> Exponential backoff with jitter -> Fast-path reconnect -> Zero session leak).
  5. `SCENARIO-5`: **Foldable / Multi-Window Split Resizing & High-Load Frame Pacing** (Device unfolded -> MS-RDPEDISP layout update -> Rapid 60fps frame burst -> Atomic single-slot frame dropper eliminating lag).

---

## 3. Feature Inventory & Coverage Matrix

| Feature ID | Category | Description | Primary Interface | Tier 1 | Tier 2 | Tier 3 | Tier 4 |
| :--- | :--- | :--- | :--- | :---: | :---: | :---: | :---: |
| **F01** | R1: Core Engine | FreeRDP Module Isolation & JNI Bridge | `IRdpEngine` | Covered | Covered | Covered | Covered |
| **F02** | R1: Core Engine | Dual-Engine Pattern (`NativeFreeRdp` / `MockRdpEngine`) | `IRdpEngine` | Covered | Covered | Covered | Covered |
| **F03** | R1: Core Engine | TLS & NLA (CredSSP) Authentication | `RdpEventListener` | Covered | Covered | Covered | Covered |
| **F04** | R1: Core Engine | Dynamic Resolution Resizing (MS-RDPEDISP) | `IRdpEngine.updateResolution` | Covered | Covered | Covered | Covered |
| **F05** | R1: Core Engine | Bidirectional Clipboard Synchronization (MS-RDPECLIP) | `IRdpEngine.sendClipboardText` | Covered | Covered | Covered | Covered |
| **F06** | R1: Core Engine | Echo-Loop Suppression for Clipboard | `ClipboardSyncManager` | Covered | Covered | Covered | Covered |
| **F07** | R2: Mouse/Touch | Floating Mouse Overlay FSM & Docking | `FloatingMouseFSM` | Covered | Covered | Covered | Covered |
| **F08** | R2: Mouse/Touch | Complete Mouse Event Generation (L/R/Double/Drag/Scroll) | `MouseController` | Covered | Covered | Covered | Covered |
| **F09** | R2: Mouse/Touch | Touchpad Simulation Mode (Relative Cursor) | `MouseController.setTouchpadMode`| Covered | Covered | Covered | Covered |
| **F10** | R2: Mouse/Touch | Optional Virtual Cursor Crosshair | `MouseController.setCursorVisible`| Covered | Covered | Covered | Covered |
| **F11** | R2: Mouse/Touch | Multi-Touch Gestures (Pan & Pinch-to-Zoom) | `GestureDetector` | Covered | Covered | Covered | Covered |
| **F12** | R2: Mouse/Touch | Anti-Spurious Click Multi-Touch Latch | `GestureStateEngine` | Covered | Covered | Covered | Covered |
| **F13** | R2: Mouse/Touch | Normalized Orientation Clamping & Persistence | `OverlayCoordinates` | Covered | Covered | Covered | Covered |
| **F14** | R2: Mouse/Touch | Affine Matrix Viewport Coordinate Translation | `CoordinateTransformer` | Covered | Covered | Covered | Covered |
| **F15** | R3: Productivity | Material 3 Profile Management (CRUD) | `ProfileRepository` | Covered | Covered | Covered | Covered |
| **F16** | R3: Productivity | Android Keystore & EncryptedSharedPreferences Vault | `CredentialStore` | Covered | Covered | Covered | Covered |
| **F17** | R3: Productivity | Collapsible Quick-Action Toolbar & Inactivity Timer | `QuickActionToolbarFSM` | Covered | Covered | Covered | Covered |
| **F18** | R3: Productivity | Mobile Modifier Bar (3-State Latch: Inactive/Latched/Locked)| `ModifierStateMachine` | Covered | Covered | Covered | Covered |
| **F19** | R3: Productivity | Windows Scan Code Set 1 & Macro Sequences | `ScancodeTranslator` | Covered | Covered | Covered | Covered |
| **F20** | R4: Performance | Auto-Reconnect FSM (Exponential Backoff + Full Jitter) | `AutoReconnectManager` | Covered | Covered | Covered | Covered |
| **F21** | R4: Performance | Low-Latency Socket Buffering & TCP_NODELAY | `LowLatencySocketConfig` | Covered | Covered | Covered | Covered |
| **F22** | R4: Performance | Atomic Single-Slot Frame Pacing | `FramePacer` | Covered | Covered | Covered | Covered |
| **F23** | R4: Performance | Bandwidth & Battery Performance Modes | `PerformancePreset` | Covered | Covered | Covered | Covered |
| **F24** | R4: Performance | Real-Time Diagnostic Telemetry Ring Buffer | `TelemetryCollector` | Covered | Covered | Covered | Covered |

---

## 4. Test Execution & Runner Commands

The test suite is executable locally and on CI agents via the Gradle test runner.

### Standard Test Execution Command:
```bash
./gradlew testDebugUnitTest
```

### Running Individual Tiers:
- **Tier 1 (Core Feature Coverage)**:
  ```bash
  ./gradlew testDebugUnitTest --tests "com.freerdp.client.e2e.Tier1FeatureCoverageTest"
  ```
- **Tier 2 (Boundary & Corner Cases)**:
  ```bash
  ./gradlew testDebugUnitTest --tests "com.freerdp.client.e2e.Tier2BoundaryCornerTest"
  ```
- **Tier 3 (Cross-Feature Combinations)**:
  ```bash
  ./gradlew testDebugUnitTest --tests "com.freerdp.client.e2e.Tier3CrossFeatureTest"
  ```
- **Tier 4 (Real-World Scenarios)**:
  ```bash
  ./gradlew testDebugUnitTest --tests "com.freerdp.client.e2e.Tier4RealWorldScenariosTest"
  ```

### Continuous Testing / Info Logging:
```bash
./gradlew testDebugUnitTest --info --stacktrace
```

### Windows PowerShell Specific:
```powershell
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot"
.\gradlew.bat testDebugUnitTest
```

---

## 5. Pass/Fail Criteria & Quality Gates

1. **Compilation Gate**: Zero compile errors or unresolved imports across all test suites.
2. **Execution Gate**: 100% pass rate across all test cases. Zero failures, zero unexpected exceptions, zero flaky tests.
3. **Coverage Invariant**:
   - Tier 1: $\ge 5$ tests per feature category ($R1, R2, R3, R4$).
   - Tier 2: $\ge 5$ boundary tests per category.
   - Tier 3: Complete pairwise combinations covering all cross-module interactions.
   - Tier 4: $\ge 5$ realistic multi-step user scenarios.
4. **Memory & Native Safety**: No dangling native handles, zero `inst == 0` calls, clean memory clearing for `CharArray` passwords via `Arrays.fill`.
