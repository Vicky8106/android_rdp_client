# BRIEFING — 2026-09-24T11:42:00Z

## Mission
Investigate and define the exact protocol mapping, scancode conversions, timing requirements, and coordinate transformation bridge between the AVNC input model and the FreeRDP native protocol layer in android_rdp_client.

## 🔒 My Identity
- Archetype: Spec Miner
- Roles: Protocol, input mapping, coordinate transformation, and timing specification discovery
- Working directory: C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\teamwork\teamwork_preview_spec_miner_survey_3
- Original parent: f0fc1f73-b43f-468a-ab50-e5ec45aedb66
- Milestone: Survey & Protocol Bridge Specification

## 🔒 Key Constraints
- DO NOT modify any code or write source files.
- Read-only investigation.
- Comprehensive findings output: C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\teamwork\teamwork_preview_spec_miner_survey_3\survey_protocol_bridge.md
- handoff.md following the 5-component Handoff Protocol.

## Current Parent
- Conversation ID: f0fc1f73-b43f-468a-ab50-e5ec45aedb66
- Updated: not yet

## Task Summary
- **What to build**: Comprehensive protocol bridge spec between AVNC input model and FreeRDP native layer.
- **Success criteria**:
  - Complete RDP scancode translation table for all virtual keys and soft keyboard keys.
  - FreeRDP pointer event flag mapping specification.
  - Coordinate transformation formulas for direct touch vs touchpad mode.
  - Timing specification for BMC hold logic.
- **Interface contracts**: FreeRDP JNI input layer vs AVNC touch/key models.
- **Code layout**: Survey report in working directory.

## Key Decisions Made
- Investigated both `android_rdp_client` (target) and `C:\Users\Administrator\avnc` (reference) exhaustively.
- Disassembled `libfreerdp-android.so` and `libwinpr3.so` with `llvm-objdump` to verify exact native JNI parameter expectations.
- Discovered that `freerdp_send_key_event(inst, keycode, down)` routes through WinPR `GetVirtualScanCodeFromVirtualKeyCode(keycode, 4)`, which expects Windows Virtual-Key (`VK_...`) codes rather than raw Set 1 scancodes.
- Formulated the exact non-blocking coroutine queue architecture and BMC hold parameters (50ms hold, 25ms pacing, 300ms initial repeat timeout, 50ms repeat delay).
- Formulated complete bidirectional coordinate transformations with safe-area bounds, letterbox/pillarbox centering, and edge-tap coercion.

## Artifact Index
- DISPATCH.md — Assignment instructions
- progress.md — Liveness heartbeat and step tracking
- survey_protocol_bridge.md — Complete survey and bridge specification
- handoff.md — Formal 5-component handoff report
