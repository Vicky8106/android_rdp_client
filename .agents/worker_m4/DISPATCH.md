# Dispatch: Worker M4 — Adaptive Low-Latency Performance, Telemetry & Auto-Reconnect

## Identity
- Archetype: teamwork_preview_worker
- Role: Low-Latency Pipeline & Telemetry Implementer
- Working Directory: C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\worker_m4
- Parent Conversation ID: 279701df-502c-4614-ba7b-407470f48f9a

## Objective
Implement Milestone 4: 5-Thread Isolation Architecture, Low-Latency Socket Buffering, Atomic Single-Slot Frame Pacing, Performance Presets, Real-Time Diagnostic Telemetry, and Auto-Reconnect State Machine in module `:feature-telemetry`.

## Exclusive Write Ownership
- `feature-telemetry/**`

## Mandatory Reading
- C:\Users\Administrator\teamwork_projects\android_rdp_client\ORIGINAL_REQUEST.md
- C:\Users\Administrator\teamwork_projects\android_rdp_client\PROJECT.md
- C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\survey_explorer_3\report.md

## MANDATORY INTEGRITY WARNING
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A teamwork_preview_auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

## Requirements & Scope
1. **Auto-Reconnect Finite State Machine**:
   - `AutoReconnectManager` contract from `PROJECT.md`:
     `val reconnectState: StateFlow<ReconnectState>`
     `fun onNetworkLost()`
     `fun onNetworkAvailable()`
     `fun onSessionDropped(reason: String)`
     `fun onUserPause()`
     `fun onUserResume()`
     `fun cancelReconnect()`
   - States: `Idle`, `WaitingForNetwork`, `Reconnecting(attempt, nextDelayMs)`, `Connected`, `Suspended(userPaused)`, `Failed(exhausted)`.
   - Exponential Backoff with Full Jitter:
     $T_{backoff} = \text{random}(0, \min(30_000, 1_000 \cdot 2^{attempt}))$.
   - Fast-path recovery on network reconnect.
   - 5-step leak-free teardown: cancel coroutines, abort native connect, wait for render loop join, deallocate native context, trigger reconnect.
2. **Low-Latency Socket Buffering & Network Configuration**:
   - `LowLatencySocketConfig`: Configures socket options (`TCP_NODELAY = true`, `SO_RCVBUF = 131072` (128KB), `SO_SNDBUF = 65536` (64KB), `IP_TOS = 0x10` low-delay).
   - FastPath PDU prioritization.
3. **Atomic Single-Slot Frame Pacer & Thread Isolation**:
   - `FramePacer`: Single-slot atomic reference (`AtomicReference<Bitmap?>`). Drops stale decoded frames when the display blitter is busy, ensuring the renderer always displays the latest frame on VSYNC with zero frame queue latency.
   - Dedicated thread dispatchers: Socket I/O dispatcher, Frame decoding dispatcher, Render blitter loop.
4. **Bandwidth- and Battery-Aware Performance Presets**:
   - `PerformancePreset` enum:
     - `ULTRA_LOW_LATENCY`: 60 FPS, 32-bit color, no compression, sound disabled, maximum responsiveness.
     - `BALANCED_MOBILE`: 30 FPS, 16-bit color, FastPath RFX, sound enabled.
     - `DATA_SAVER`: 15 FPS, 8-bit color, high RLE compression, wallpaper/themes disabled.
     - `BATTERY_SAVER`: 15 FPS, dynamic frame throttling when idle.
   - Auto-detection: adjusts preset automatically based on network type (Wi-Fi vs Cellular) and battery level (Power Save mode).
5. **Real-Time Diagnostic Telemetry Engine**:
   - `TelemetryCollector`: Tracks RTT latency, instantaneous FPS, frame delivery jitter, network bandwidth (Kbps), dropped frames, and connection state.
   - 1,000-sample circular ring buffer (`CircularTelemetryBuffer`) calculating running averages, percentiles (p50, p95, p99), and min/max.
   - Diagnostic HUD overlay data model for real-time UI rendering.
6. **Dynamic Orientation & Multi-Window / Foldable Support**:
   - Window size and configuration change listener.
   - Triggers dynamic resolution resize via `IRdpEngine.updateResolution` without tearing down native session.
7. **Automated Unit Tests**:
   Under `feature-telemetry/src/test/java/com/freerdp/feature/telemetry/`:
   - `AutoReconnectManagerTest.kt`: Tests network drop, fast-path network recovery, exponential backoff calculation with jitter, pause/resume lifecycle, and clean cancellation without leaking.
   - `FramePacerTest.kt`: Tests single-slot atomic frame replacement, verifying that stale frames are dropped when blit is slow and the newest frame is always delivered.
   - `TelemetryCollectorTest.kt`: Tests ring buffer rollover, FPS calculation, RTT measurement, and p95 latency calculation.
   - `PerformancePresetTest.kt`: Tests preset configurations, CLI arguments generation, and battery/network adaptation rules.
8. **Verification**:
   Execute:
   `set JAVA_HOME=C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot`
   `.\gradlew.bat :feature-telemetry:testDebugUnitTest`
   Ensure 100% test pass rate.


## 2026-09-22T19:05:19Z
<USER_REQUEST>
You are worker_m4 (Low-Latency Pipeline & Telemetry Implementer).
Working Directory: C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\worker_m4.
Dispatch details: C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\worker_m4\DISPATCH.md.

MANDATORY READING: Read C:\Users\Administrator\teamwork_projects\android_rdp_client\ORIGINAL_REQUEST.md completely before starting.
Also read C:\Users\Administrator\teamwork_projects\android_rdp_client\PROJECT.md and C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\survey_explorer_3\report.md.

MANDATORY INTEGRITY WARNING:
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A teamwork_preview_auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

Task:
Implement Milestone 4 in module :feature-telemetry:
1. AutoReconnectManager implementing AutoReconnectManager from PROJECT.md with exponential backoff + full jitter, network callback listener, and 5-step leak-free session teardown.
2. LowLatencySocketConfig configuring TCP_NODELAY, 128KB rx / 64KB tx buffers, and low-delay IP_TOS.
3. FramePacer with atomic single-slot frame reference (drops stale frames on blitter delay, eliminates input lag).
4. Bandwidth- and battery-aware PerformancePreset (Ultra-Low Latency, Balanced Mobile, Data Saver, Battery Saver) with auto-adaptation.
5. Real-Time TelemetryCollector with 1,000-sample circular ring buffer tracking RTT latency, FPS, frame delivery jitter, bandwidth, and p95/p99 percentiles.
6. Dynamic orientation and multi-window/foldable layout listener and adaptive render resizing.
7. Automated unit tests in feature-telemetry/src/test/java/com/freerdp/feature/telemetry/ (AutoReconnectManagerTest, FramePacerTest, TelemetryCollectorTest, PerformancePresetTest).
8. Verify by running:
   $env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot"
   .\gradlew.bat :feature-telemetry:testDebugUnitTest
9. Write handoff.md in your working directory and notify parent via send_message when complete.
</USER_REQUEST>
