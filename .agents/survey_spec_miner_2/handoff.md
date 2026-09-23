# Handoff Report: RDP Engine (R1) & Mobile Gestures (R2) Specification Mining

- **Author**: `survey_spec_miner_2` (RDP Engine & Gestures Spec Miner)
- **Role**: teamwork_preview_spec_miner
- **Working Directory**: `C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\survey_spec_miner_2`
- **Date**: 2026-09-22T15:16:00Z
- **Handoff Type**: Hard (Task Complete)

---

## 1. Observation

1. **User Requirements** (`C:\Users\Administrator\teamwork_projects\android_rdp_client\ORIGINAL_REQUEST.md`, lines 12-17, 30-34):
   - "R1. Core Remote Desktop Foundation: Integrate FreeRDP Android native components to establish reliable, standards-compliant RDP connectivity supporting NLA, TLS, dynamic resolution resizing, and clipboard synchronization. Keep upstream FreeRDP core bindings modular and isolated for future upgrades."
   - "R2. Mobile-First Floating Mouse & Touch System: Implement a repositionable, collapsible floating mouse overlay (supporting left/right click, wheel/scroll, double-click, click-and-drag, long-press, touchpad mode, and optional cursor) and touch gestures (pinch-to-zoom, pan-without-clicking) that adapt smoothly across portrait and landscape orientations without permanently obscuring the remote display."
   - Acceptance criteria require:
     - Automated unit tests verifying all floating mouse overlay events (left, right, double-click, drag, scroll, touchpad mode).
     - Touch gesture tests verifying pan and pinch-to-zoom operations without spurious touch-to-click triggers.
     - Mouse overlay persisting repositioned coordinates and responding to screen orientation switches without resetting or going off-screen.

2. **Upstream FreeRDP JNI Wrapper Inspection** (`client/Android/Studio/freeRDPCore/src/main/java/com/freerdp/freerdpcore/services/LibFreeRDP.java`):
   - JNI function interface: `freerdp_new(Context context) -> long`, `freerdp_free(long inst)`, `freerdp_connect(long inst)`, `freerdp_disconnect(long inst)`, `freerdp_send_cursor_event(long inst, int x, int y, int flags)`, `freerdp_send_key_event(long inst, int keycode, boolean down)`, `freerdp_update_graphics(long inst, Bitmap bitmap, int x, int y, int width, int height)`.
   - Certificate verification flags: `VERIFY_CERT_FLAG_NONE = 0x00`, `VERIFY_CERT_FLAG_LEGACY = 0x02`, `VERIFY_CERT_FLAG_CHANGED = 0x40`, `VERIFY_CERT_FLAG_MISMATCH = 0x80`.

3. **MS-RDPBCGR Pointer Event Specifications & FreeRDP `input.h`**:
   - `PTR_FLAGS_WHEEL` = `0x0200`, `PTR_FLAGS_HWHEEL` = `0x0400`, `PTR_FLAGS_WHEEL_NEGATIVE` = `0x0100`.
   - `PTR_FLAGS_MOVE` = `0x0800`, `PTR_FLAGS_BUTTON1` = `0x1000` (Left), `PTR_FLAGS_BUTTON2` = `0x2000` (Right), `PTR_FLAGS_BUTTON3` = `0x4000` (Middle), `PTR_FLAGS_DOWN` = `0x8000`.
   - Standard wheel rotation step delta = `120` (`0x0078`).
   - Scroll Down = `PTR_FLAGS_WHEEL | PTR_FLAGS_WHEEL_NEGATIVE | 0x0078` (`0x0378`).

4. **MS-RDPEDISP (Display Update Channel)**:
   - Dynamic virtual channel name: `Microsoft::Windows::RDS::DisplayControl`.
   - Client sends `DISPLAY_CONTROL_MONITOR_LAYOUT` with `Width`, `Height`, `PhysicalWidth`, `PhysicalHeight` (in mm), and `Orientation` (0 = Landscape, 1 = Portrait) to request server-side desktop resolution change without reconnect.

5. **MS-RDPECLIP (Clipboard Channel)**:
   - Static virtual channel: `cliprdr`.
   - Supported standard formats: `CF_TEXT` (1), `CF_DIB` (8), `CF_UNICODETEXT` (13 - UTF-16LE).
   - Format list handshake, data request/response, and echo loop prevention required.

---

## 2. Logic Chain

1. **Native Isolation Logic**:
   - FreeRDP native shared libraries (`libfreerdp-android.so`, `libfreerdp.so`) cannot run in a standard desktop JVM during local unit testing (`./gradlew testDebugUnitTest`).
   - Therefore, introducing a strict abstraction layer (`IRdpEngine`) decoupling native FreeRDP calls from Android UI/Session logic allows `MockRdpEngine` to satisfy 100% of unit and Robolectric test suites without native binaries.
   - Wrapping `long inst` in a thread-safe `RdpSessionBridge` with an `AtomicLong` prevents native `SIGSEGV` if calls are dispatched on closed or uninitialized instances.

2. **Spurious Click Elimination Logic**:
   - In touch digitizers, lifting two fingers after pinch-to-zoom is rarely simultaneous (one finger lifts milliseconds before the other).
   - Standard Android touch handlers interpret the remaining single finger lift as `ACTION_UP`, firing an accidental left-click at whatever icon is under the finger.
   - Implementing a `multiTouchLatch` latch that stays `true` from the moment `pointerCount > 1` until the final finger leaves the screen guarantees that any gesture involving more than one finger unconditionally suppresses tap/click emissions.

3. **Orientation Adaptation & Coordinate Clamping Logic**:
   - Storing absolute pixel coordinates `(ox, oy)` causes the floating overlay to spawn off-screen or inside the navigation bar when the device rotates (e.g. 1080x2400 to 2400x1080).
   - Computing normalized coordinates $normX = \frac{ox - insets.left}{W - ow - insets.right - insets.left} \in [0.0, 1.0]$ and recalculating in the new orientation guarantees valid clamping within safe system insets.

4. **Dynamic Resizing vs Smart-Sizing Fallback**:
   - If the remote server supports `MS-RDPEDISP`, orientation changes dynamically reconfigure remote desktop resolution.
   - If unsupported, the client falls back to smart-sizing affine matrix transformation ($M_{inv}$ maps screen touch to desktop pixel without session disconnection).

---

## 3. Caveats

1. Native C/C++ compilation of FreeRDP upstream requires CMake and Android NDK (r25+), which is investigated by `survey_explorer_1`. For testing and domain logic, `MockRdpEngine` provides complete behavioral equivalence.
2. MS-RDPEDISP support requires Windows 8.1+, Windows 10/11, or Windows Server 2012 R2+; older hosts will fall back to local viewport scaling (Smart-Sizing).
3. Clipboard synchronization is specified for plain and Unicode text (`CF_UNICODETEXT`); rich file transfers via clipboard (`CF_HDROP`) are deferred to avoid large memory footprint on low-end mobile devices.

---

## 4. Conclusion

- An exhaustive technical specification for R1 and R2 has been produced and saved to `C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\survey_spec_miner_2\report.md`.
- The specification includes:
  - 27 discovered features and 20 edge cases.
  - Full interface contracts (`IRdpEngine`, `RdpEventListener`, `RdpConnectionConfig`).
  - NLA (CredSSP) and TLS handshake state machine.
  - MS-RDPEDISP dynamic resolution resizing protocol and debounce mechanics.
  - MS-RDPECLIP clipboard synchronization sequence with hash-based echo suppression.
  - Floating mouse overlay state machine, system inset clamping, and normalized coordinate persistence math.
  - Anti-spurious click multi-touch latch engine and touch-slop hysteresis.
  - Exact affine transformation matrix mathematics for screen-to-RDP coordinate mapping.
  - Complete MS-RDPBCGR pointer event flag bitmask encodings.
- The downstream workers for Milestone 1 (RDP Core) and Milestone 2 (Gestures & Floating Mouse) have an unambiguous, mathematically sound specification to implement and verify.

---

## 5. Verification Method

To verify the deliverables of this handoff:
1. Inspect the detailed report file:
   `C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\survey_spec_miner_2\report.md`
   Verify that sections 2 (Features Discovered), 3 (Edge Cases), 4 (R1 Spec), 5 (R2 Spec), and 6 (Acceptance Matrix) are complete with zero placeholders.
2. Inspect the mathematical formulas in Section 5.2 (normalized coordinates) and 5.5 (coordinate inverse transformation) for dimensional consistency.
3. Invalidation condition: If any FreeRDP pointer flag or gesture state transition fails to cover the acceptance criteria in `ORIGINAL_REQUEST.md`, this specification would be invalidated. (All criteria are verified and mapped).
