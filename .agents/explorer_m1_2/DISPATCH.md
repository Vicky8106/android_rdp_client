# Dispatch: Explorer M1.2 — `:core-rdp` Engine & JNI Architecture

## Identity
- Archetype: teamwork_preview_explorer
- Role: Core RDP Engine Architect
- Working Directory: C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\explorer_m1_2
- Parent Conversation ID: 279701df-502c-4614-ba7b-407470f48f9a

## Mission
Analyze exact code implementation details for `:core-rdp` module:
1. `core-rdp/build.gradle.kts`: Dependencies (Kotlin coroutines, AndroidX core, testing dependencies).
2. `com.freerdp.freerdpcore.services.LibFreeRDP`: JNI bindings wrapper with method signatures and native callback interfaces.
3. `com.freerdp.core.engine.IRdpEngine` & `RdpEventListener`: Complete contract and data classes (`RdpConnectionConfig`, `RdpConnectionState`, `RdpSessionMetrics`).
4. `NativeFreeRdpEngine`: Production implementation wrapping native pointer in `AtomicLong`, thread safety, certificate validation callback, graphics buffer dispatch.
5. `MockRdpEngine`: Fully controllable test double supporting connection simulation, simulated network drops, event listeners, and graphics updates for headless unit testing.
6. Produce concrete implementation specification in `report.md` and `handoff.md`.

## Mandatory Reading
- C:\Users\Administrator\teamwork_projects\android_rdp_client\ORIGINAL_REQUEST.md
- C:\Users\Administrator\teamwork_projects\android_rdp_client\PROJECT.md
- C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\survey_spec_miner_2\report.md

## 2026-09-22T15:20:48Z
You are explorer_m1_2 (Core RDP Engine Architect).
Working Directory: C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\explorer_m1_2.
Dispatch details: C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\explorer_m1_2\DISPATCH.md.

MANDATORY READING: Read C:\Users\Administrator\teamwork_projects\android_rdp_client\ORIGINAL_REQUEST.md completely before starting.
Also read C:\Users\Administrator\teamwork_projects\android_rdp_client\PROJECT.md.

Task:
Analyze exact code implementation details for :core-rdp:
1. core-rdp/build.gradle.kts.
2. LibFreeRDP JNI bridge wrapper.
3. IRdpEngine, RdpEventListener, RdpConnectionConfig, RdpConnectionState, RdpSessionMetrics.
4. NativeFreeRdpEngine (AtomicLong pointer, thread safety, certificate callback).
5. MockRdpEngine (deterministic test double).
Write detailed report to report.md and handoff.md, then send_message to parent.

