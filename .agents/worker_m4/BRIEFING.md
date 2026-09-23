# BRIEFING — 2026-09-22T19:06:00Z

## Mission
Implement Milestone 4 in :feature-telemetry: Low-Latency Pipeline, Socket Buffering, Atomic Frame Pacing, Adaptive Performance Presets, Real-Time Diagnostic Telemetry, and Auto-Reconnect State Machine with comprehensive unit tests.

## 🔒 My Identity
- Archetype: teamwork_preview_worker
- Roles: implementer, qa, specialist
- Working directory: C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\worker_m4
- Original parent: 279701df-502c-4614-ba7b-407470f48f9a
- Milestone: Milestone 4 (Adaptive Low-Latency Performance, Telemetry & Auto-Reconnect)

## 🔒 Key Constraints
- Exclusive write ownership: feature-telemetry/** (and tests within it)
- .agents/ holds only metadata (plans, progress, handoffs, briefing). Never put source/tests there.
- Integrity mandate: DO NOT hardcode test results or create dummy/facade implementations.
- Verify using: $env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot"; .\gradlew.bat :feature-telemetry:testDebugUnitTest
- Deliver handoff.md and notify parent via send_message.

## Current Parent
- Conversation ID: 279701df-502c-4614-ba7b-407470f48f9a
- Updated: not yet

## Task Summary
- **What to build**:
  1. AutoReconnectManager implementing AutoReconnectManager from PROJECT.md with exponential backoff + full jitter, network callback listener, and 5-step leak-free session teardown.
  2. LowLatencySocketConfig configuring TCP_NODELAY, 128KB rx / 64KB tx buffers, and low-delay IP_TOS.
  3. FramePacer with atomic single-slot frame reference (drops stale frames on blitter delay, eliminates input lag).
  4. Bandwidth- and battery-aware PerformancePreset (Ultra-Low Latency, Balanced Mobile, Data Saver, Battery Saver) with auto-adaptation.
  5. Real-Time TelemetryCollector with 1,000-sample circular ring buffer tracking RTT latency, FPS, frame delivery jitter, bandwidth, and p95/p99 percentiles.
  6. Dynamic orientation and multi-window/foldable layout listener and adaptive render resizing.
  7. Automated unit tests in feature-telemetry/src/test/java/com/freerdp/feature/telemetry/ (AutoReconnectManagerTest, FramePacerTest, TelemetryCollectorTest, PerformancePresetTest).
- **Success criteria**: 100% tests pass on `:feature-telemetry:testDebugUnitTest`. Zero leaks, clean teardown.
- **Interface contracts**: PROJECT.md Section 5 (AutoReconnectManager) and Section 1 (IRdpEngine).
- **Code layout**: feature-telemetry/src/main/java/com/freerdp/feature/telemetry/

## Key Decisions Made
- [TBD]

## Artifact Index
- DISPATCH.md — Assignment instructions
- BRIEFING.md — Situational awareness
- progress.md — Liveness heartbeat

## Change Tracker
- **Files modified**: None yet
- **Build status**: Pending baseline check
- **Pending issues**: None

## Quality Status
- **Build/test result**: Pending baseline test run
- **Lint status**: 0 violations
- **Tests added/modified**: None yet

## Loaded Skills
- None
