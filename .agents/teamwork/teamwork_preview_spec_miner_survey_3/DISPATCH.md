## 2026-09-24T11:25:00Z
<USER_REQUEST>
Your working directory is: C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\teamwork\teamwork_preview_spec_miner_survey_3
Your identity is: teamwork_preview_spec_miner_survey_3 (Spec Miner)
You MUST read C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\teamwork\ORIGINAL_REQUEST.md before starting work.

Objective:
Investigate and define the exact protocol mapping, scancode conversions, timing requirements, and coordinate transformation bridge between the AVNC input model and the FreeRDP native protocol layer in android_rdp_client.

Scope to investigate:
1. FreeRDP Input API & Protocol Contracts:
   - How does android_rdp_client interact with native FreeRDP for mouse events (move, down, up, buttons, wheel flags like PTRFLAGS_DOWN, PTRFLAGS_BUTTON1/2/3, PTRFLAGS_WHEEL)?
   - How does it interact for keyboard events (scancodes, extended scancodes KBD_EXTENDED, release flags KBD_FLAGS_RELEASE, Unicode input)?
   - Search for native JNI calls, session interfaces, or FreeRDP wrappers in android_rdp_client.
2. Scancode Mapping Table:
   - Map all RealVNC virtual keys:
     - Modifiers: Left/Right Ctrl, Alt, Shift, Super/Windows
     - Function keys: F1 through F12
     - Navigation/Editing: Esc, Tab, Delete, Backspace, Enter, Space, Up, Down, Left, Right arrows
   - Compare existing ScancodeTranslator.kt with standard RDP scancodes and AVNC keysyms.
3. Coordinate Transformations:
   - Remote desktop resolution vs device display resolution vs canvas zoom/pan.
   - How are coordinates converted from screen touch/cursor (X, Y) to remote desktop coordinates (remoteX, remoteY)?
   - Clamping boundaries and aspect ratio letterboxing/pillarboxing.
4. BMC Hold Timing & Soft Keyboard Event Dispatching:
   - Why BMC hold timing is necessary for soft keyboard inputs (Enter, Backspace, Space, Tab) and what timing values (e.g. 50ms hold) prevent missed keystrokes in RDP servers.
   - Recommended coroutine/queue architecture to ensure non-blocking, reliable transmission.

Scope boundaries:
- DO NOT modify any code or write source files.
- Read-only investigation.

Output Requirements:
- Write your comprehensive findings to: C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\teamwork\teamwork_preview_spec_miner_survey_3\survey_protocol_bridge.md
- Write handoff.md in your working directory following the Handoff Protocol.
- Send a message to parent when done.

Completion Criteria:
- Complete RDP scancode translation table for all virtual keys and soft keyboard keys.
- FreeRDP pointer event flag mapping specification.
- Coordinate transformation formulas for direct touch vs touchpad mode.
- Timing specification for BMC hold logic.
</USER_REQUEST>
