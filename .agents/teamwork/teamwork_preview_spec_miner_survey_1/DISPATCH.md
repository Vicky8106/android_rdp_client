## 2026-09-24T11:25:10Z
Objective:
Investigate and document all UX patterns, input handling architectures, and state machines from the reference AVNC codebase (C:\Users\Administrator\avnc) that must be ported to the Android RDP client.

Scope to investigate:
1. Collapsible In-Session Toolbar & Navigation:
   - Examine C:\Users\Administrator\avnc\app\src\main\java\com\vncandroid\free\ui\vnc\Toolbar.kt
   - Examine C:\Users\Administrator\avnc\app\src\main\java\com\vncandroid\free\ui\vnc\VncActivity.kt
   - Examine C:\Users\Administrator\avnc\app\src\main\java\com\vncandroid\free\ui\vnc\LayoutManager.kt
   - Identify: Toolbar state (expanded, collapsed, floating, docked), toggle triggers, animations, auto-hide behaviors, actions (soft keyboard toggle, input mode switch, virtual keys bar toggle, zoom/fit modes, disconnect).
2. RealVNC-Style Virtual Keys Bar:
   - Examine C:\Users\Administrator\avnc\app\src\main\java\com\vncandroid\free\ui\vnc\VirtualKeysCompose.kt
   - Examine C:\Users\Administrator\avnc\app\src\main\java\com\vncandroid\free\ui\vnc\VirtualKeys.kt
   - Identify: Layout structure (Fn bar F1-F12, sticky modifier keys Ctrl, Alt, Shift, Super/Win, Esc, Tab, Delete, inverted-T arrow keys), modifier state machine (sticky vs pressed vs locked), Compose UI components and layout constraints.
3. Keyboard Input Handling & BMC Timing:
   - Examine C:\Users\Administrator\avnc\app\src\main\java\com\vncandroid\free\ui\vnc\input\KeyHandler.kt
   - Examine C:\Users\Administrator\avnc\app\src\main\java\com\vncandroid\free\ui\vnc\util\Keyboard.kt
   - Identify: Key down/up dispatching, BMC (Baseboard Management Controller) key-press hold timing for soft keyboard Enter, Backspace, Space, Tab, and special keys, coroutine/thread timing mechanism.
4. Touch, Touchpad & Virtual Mouse Controls:
   - Examine C:\Users\Administrator\avnc\app\src\main\java\com\vncandroid\free\ui\vnc\input\TouchHandler.kt
   - Examine C:\Users\Administrator\avnc\app\src\main\java\com\vncandroid\free\ui\vnc\input\PointerModes.kt
   - Examine C:\Users\Administrator\avnc\app\src\main\java\com\vncandroid\free\ui\vnc\VirtualMouseCompose.kt
   - Identify: Direct Touch mode (tap-to-click, 2-finger scroll/pan, pinch-to-zoom), Touchpad mode (relative movement, acceleration algorithms, left/middle/right click, drag lock, scrolling controls), coordinate transformations.

Scope boundaries:
- DO NOT modify any code or write source files.
- Read-only investigation.

Output Requirements:
- Write your comprehensive findings to: C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\teamwork\teamwork_preview_spec_miner_survey_1\survey_avnc_spec.md
- Write handoff.md in your working directory following the Handoff Protocol.
- Send a message to parent when done.

Completion Criteria:
- Complete breakdown of data structures, state machines, math/acceleration formulas, timing constants, and UI layouts with code references.
