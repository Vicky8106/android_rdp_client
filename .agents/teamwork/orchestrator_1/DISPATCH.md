## 2026-09-24T11:23:19Z

You are the Project Orchestrator for the Android RDP Client project.

Your working directory is:
C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\teamwork\orchestrator_1

The project root is:
C:\Users\Administrator\teamwork_projects\android_rdp_client

The reference VNC codebase is:
C:\Users\Administrator\avnc

The authoritative user request is in:
C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\teamwork\ORIGINAL_REQUEST.md

Your mission:
Port the mature in-session user experience, virtual keys, touch/mouse pointer modes, and keyboard input handling from the local VNC repository (C:\Users\Administrator\avnc) into the Android RDP client (C:\Users\Administrator\teamwork_projects\android_rdp_client), resolving all interaction, streaming, and stability bugs.

Requirements:
1. R1. Collapsible In-Session Toolbar & Navigation:
   Adopt the avnc collapsible session toolbar and layout pattern in android_rdp_client. Provide direct, accessible controls for toggling the Android soft keyboard, switching mouse/input mode (Touchpad mode vs. Direct Touch), toggling the virtual keys bar, switching screen scale/fit modes, and executing clean session disconnection.
2. R2. RealVNC-Style Virtual Keys Bar & Soft Keyboard Timing:
   Port the Compose virtual keys system from avnc (VirtualKeysCompose.kt) with full RealVNC ergonomics: collapsible Fn strip (F1–F12), sticky modifier keys (Ctrl, Alt, Shift, Super/Windows), Esc, Tab, Delete, and an inverted-T arrow cluster. Integrate avnc's keyboard input handling (KeyHandler.kt), including BMC key-press hold timing for Android soft-keyboard Enter, Backspace, Space, Tab, and special keys, properly translated into RDP keyboard scancodes.
3. R3. Multi-Mode Touch, Touchpad & Virtual Mouse Controls:
   Port the input engine from avnc (TouchHandler.kt, PointerModes.kt, VirtualMouseCompose.kt) to support:
   - Direct Touch Mode: Direct tap-to-click at touch coordinates, two-finger scroll/pan, and pinch-to-zoom.
   - Touchpad / Mouse Pointer Mode: Relative cursor movement with acceleration, dedicated left, middle, and right click controls, drag lock, and smooth scrolling controls.
   Ensure all coordinate transformations and pointer events map accurately to the FreeRDP native protocol layer.
4. R4. Test Suite, Build Verification & Release Packaging:
   Update and expand unit and Robolectric tests across :feature-mouse, :feature-session, and :app to cover the ported virtual keys, scancode conversions, keyboard timing, and mouse modes. Maintain 100% test pass rate on .\gradlew.bat testDebugUnitTest and assemble a verified, functional debug APK under 100 MB in releases/app-debug.apk.

Acceptance Criteria:
- In-session toolbar smoothly expands and collapses without obscuring the desktop canvas unnecessarily.
- Toolbar controls for keyboard toggle, input mode switch (touchpad vs. direct touch), virtual keys bar toggle, zoom/fit, and disconnect function responsively.
- Virtual keys bar renders RealVNC-style layout (Fn bar, sticky Ctrl/Alt/Shift/Win modifiers, Esc, Tab, Delete, inverted-T arrow keys) and transmits correct RDP key events.
- Soft keyboard input (letters, numbers, Enter, Backspace, Space, Tab) dispatches with proper key hold timing and functions reliably in remote text fields.
- Direct Touch mode accurately executes clicks and drags at the touch point.
- Touchpad mode provides smooth cursor movement with acceleration, dedicated mouse buttons (left, right, middle), and scroll controls.
- .\gradlew.bat testDebugUnitTest passes 100% with 0 failures and 0 errors across all modules.
- .\gradlew.bat assembleDebug builds successfully and produces a signed APK in releases/app-debug.apk under 100 MB.
