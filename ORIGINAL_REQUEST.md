# Original User Request

## 2026-10-07T17:05:29Z

Build an Android Remote Desktop Protocol (RDP) client application that provides an exact replica of AVNC's UI, UX, and client functionality adapted for Microsoft RDP connections, delivering a compiled and installable Android APK.

Working directory: C:\Users\Administrator\teamwork_projects\android_rdp_client
Integrity mode: development

Reference:
- UI/UX and Android client architecture reference: https://github.com/Vicky8106/avnc
- Underlying RDP protocol engine: FreeRDP / aFreeRDP native C/Java bindings

## Requirements

### R1. UI and UX Parity with AVNC
Replicate the complete interface, navigation, and interaction model of AVNC (https://github.com/Vicky8106/avnc), including the connection profile list/bookmarks, connection configuration dialogs, in-session controls, and input modes (direct touch, trackpad/mouse pointer emulation, virtual keyboard, and viewport scaling/panning), adapted for RDP parameters (domain, NLA credentials, gateway, audio redirection, color depth, and resolution).

### R2. RDP Protocol Integration & Session Management
Integrate FreeRDP / aFreeRDP bindings to handle RDP session lifecycles: socket connection negotiation, Network Level Authentication (NLA) / TLS security, desktop framebuffer rendering onto an Android SurfaceView, and real-time mapping of Android touch/keyboard events to RDP input PDUs.

### R3. Automated Build Pipeline and APK Deliverable
Configure the project with a fully working Gradle build system and CMake/NDK toolchain that compiles the native libraries and Android application code, outputting a complete, installable debug APK deliverable.

## Acceptance Criteria

### Build & Deliverable Verification
- [ ] Automated build (`gradlew assembleDebug`) executes successfully with exit code 0.
- [ ] An installable APK artifact is generated at `app/build/outputs/apk/debug/` (or designated output path).
- [ ] The compiled APK contains necessary native `.so` binaries (e.g. `libfreerdp*.so` or core native engines) for supported ABIs.

### Core Parity & Automated Checks
- [ ] Connection profile storage (CRUD) handles RDP connection configurations with domain, username, password, host, and port.
- [ ] Automated test suite or headless smoke test verifies session parameter parsing, input state mapping, and core activity initialization without crashing.


## 2026-10-07T18:06:28Z

Resume and complete the Android Remote Desktop Protocol (RDP) client application project in C:\Users\Administrator\teamwork_projects\android_rdp_client.

Context: Phase 0 (survey & architecture) and Milestone 1 (toolchain provisioning, Gradle build system, and initial APK compilation) are already complete in the workspace. The debug APK has been verified at app/build/outputs/apk/debug/app-debug.apk.

Please continue execution of the project according to the specifications in PROJECT.md:
- Milestone 2: Connection Profiles & AVNC Parity Home UI (Room DB ServerProfile entity, DAO, repository, LiveData, HomeActivity bookmark list, search, sort, Quick Connect & Advanced ProfileEditor).
- Milestone 3: FreeRDP Native Integration & Protocol Lifecycle (JNI C bindings, LibFreeRDP facade, session lifecycle, scancode & touch input translators).
- Milestone 4: In-Session Remote Desktop UI, Framebuffer Rendering & Input Controls (RdpSessionActivity, FrameView GLSurfaceView/SurfaceView, collapsible toolbar drawer, VirtualKeysCompose, VirtualMouseCompose pill, touch/mouse dispatchers).
- Milestone 5 & 6: Automated E2E Test Suite (Robolectric/JUnit headless tests) & Final APK Packaging Verification.

Working directory: C:\Users\Administrator\teamwork_projects\android_rdp_client
Integrity mode: development

Reference:
- UI/UX and Android client architecture reference: https://github.com/Vicky8106/avnc
- Underlying RDP protocol engine: FreeRDP / aFreeRDP native C/Java bindings
