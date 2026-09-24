# Progress — teamwork_preview_spec_miner_survey_3

Last visited: 2026-09-24T11:41:00Z
Status: Complete

## Plan
1. [x] Setup working environment (DISPATCH.md, BRIEFING.md, progress.md)
2. [x] Probe FreeRDP Input API & Protocol Contracts in android_rdp_client (JNI, session interfaces, mouse/kbd flags)
3. [x] Probe AVNC input architecture in C:\Users\Administrator\avnc (KeyHandler, Keyboard, TouchHandler, PointerModes, VirtualKeysCompose)
4. [x] Analyze ScancodeTranslator.kt in android_rdp_client vs AVNC keysyms vs RDP scancode specs (modifiers, Fn keys, navigation/editing, soft keyboard)
5. [x] Analyze coordinate transformation pipeline (RemoteCanvasView, resolution scale/fit, touch to remote desktop, direct touch vs touchpad mode, letterboxing/pillarboxing)
6. [x] Analyze BMC key hold timing (why BMC hold is needed, AVNC implementation, timing values, coroutine/queue architecture)
7. [x] Compile comprehensive `survey_protocol_bridge.md`
8. [ ] Generate `handoff.md` and report to parent
