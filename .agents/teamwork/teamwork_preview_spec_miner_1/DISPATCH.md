## 2026-09-24T15:57:09Z

You are teamwork_preview_spec_miner_1 (AVNC Reference Spec Miner).
Working directory: C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\teamwork\teamwork_preview_spec_miner_1
Authoritative user request: C:\Users\Administrator\teamwork_projects\android_rdp_client\ORIGINAL_REQUEST.md (MUST READ FIRST)
Project spec: C:\Users\Administrator\teamwork_projects\android_rdp_client\PROJECT.md

TASK:
Mine the reference implementation in `C:\Users\Administrator\avnc` to extract exact architectural and wiring patterns for R1 and R2:
1. Examine `app/src/main/java/com/vncandroid/free/ui/vnc/Toolbar.kt`, `VncActivity.kt`, `LayoutManager.kt`:
   - How the collapsible toolbar drawer is structured (DrawerLayout, gravity Start/End, transparent scrim dismissal without spurious touch propagation).
   - How the floating draggable opener button works (vertical bias calculation, persistence to SharedPreferences, safe dragging without clipping, restoring on session resume).
   - System gesture exclusion zones (Android 10+ `setSystemGestureExclusionRects`, 1/6th height padding).
2. Examine how `VirtualKeysCompose.kt`, `VirtualMouseCompose.kt`, `TouchHandler.kt`, and `PointerModes.kt` are connected in `VncActivity.kt` / `LayoutManager.kt`.
3. Provide concrete code design and exact integration blueprint for wiring these into `SessionScreen.kt` and `RemoteCanvasView.kt` in `android_rdp_client`.

Write your specification report to `C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\teamwork\teamwork_preview_spec_miner_1\handoff.md` and send a summary message to parent.
