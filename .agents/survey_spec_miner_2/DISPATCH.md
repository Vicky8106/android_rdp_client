# Survey Task Dispatch — Spec Miner 2: RDP Engine & Mobile Gestures Specification

## Identity
- Archetype: teamwork_preview_spec_miner
- Role: RDP Engine & Gestures Spec Miner
- Working Directory: C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\survey_spec_miner_2
- Parent Conversation ID: 279701df-502c-4614-ba7b-407470f48f9a

## Objective
Extract detailed, exhaustive requirements and specifications from `ORIGINAL_REQUEST.md` and industry reference implementations for:
1. R1: FreeRDP core bindings, connection sequence, NLA / TLS, dynamic resolution resizing, clipboard synchronization, native interface isolation.
2. R2: Floating mouse overlay state machine & gesture system:
   - Floating overlay states (collapsed, expanded, repositionable, orientation persistence across portrait/landscape).
   - Mouse events: left click, right click, wheel/scroll, double click, click-and-drag, long press, touchpad mode, optional cursor.
   - Touch gestures: pan-without-clicking, pinch-to-zoom without spurious touch-to-click.
   - Screen boundary clamping / coordinate transformation between screen coordinates and remote RDP desktop coordinates.
3. Acceptance criteria and edge cases for these subsystems.

## Scope Boundaries
- Do NOT implement application source code.
- Analyze requirements, specify data structures, state machines, event models, and edge cases.
- Output report to `C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\survey_spec_miner_2\report.md`.

## Mandatory Reading
- C:\Users\Administrator\teamwork_projects\android_rdp_client\ORIGINAL_REQUEST.md

## 2026-09-22T15:10:41Z
You are survey_spec_miner_2 (RDP Engine & Gestures Spec Miner).
Your working directory is C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\survey_spec_miner_2.
Your dispatch details are at C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\survey_spec_miner_2\DISPATCH.md.

MANDATORY READING: Read C:\Users\Administrator\teamwork_projects\android_rdp_client\ORIGINAL_REQUEST.md completely before starting.

Task:
1. Extract and mine all specifications for R1 (FreeRDP core bindings, NLA, TLS, dynamic resolution resizing, clipboard synchronization, native interface isolation).
2. Extract and mine all specifications for R2 (Floating mouse overlay & gesture system: repositionable, collapsible, left/right click, wheel/scroll, double click, click-and-drag, long-press, touchpad mode, optional cursor, pan-without-clicking, pinch-to-zoom without spurious clicks, orientation adaptation and coordinate boundary persistence).
3. Document exact event models, state transitions, coordinate math, boundary checks, and edge cases.
4. Write your findings to C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\survey_spec_miner_2\report.md and write a handoff.md.
5. Notify parent via send_message when complete.
