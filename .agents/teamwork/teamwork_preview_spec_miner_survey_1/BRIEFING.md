# BRIEFING — 2026-09-24T11:26:00Z

## Mission
Investigate and document all UX patterns, input handling architectures, and state machines from reference AVNC (C:\Users\Administrator\avnc) for porting into Android RDP Client.

## 🔒 My Identity
- Archetype: Spec Miner
- Roles: Specification Mining, Codebase Exploration, Architecture Analysis
- Working directory: C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\teamwork\teamwork_preview_spec_miner_survey_1
- Original parent: f0fc1f73-b43f-468a-ab50-e5ec45aedb66
- Milestone: avnc_ux_port_survey

## 🔒 Key Constraints
- Read-only investigation: DO NOT modify any code or write source files in either repository.
- Authoritative source is reference codebase: C:\Users\Administrator\avnc
- Output comprehensive findings in survey_avnc_spec.md
- Produce 5-component handoff.md
- Send message to parent on completion

## Current Parent
- Conversation ID: f0fc1f73-b43f-468a-ab50-e5ec45aedb66
- Updated: 2026-09-24T11:26:00Z

## Task Summary
- **What to build**: Comprehensive technical specification & survey of AVNC UX, toolbar, virtual keys, key handling with BMC hold timing, touch/pointer modes, and coordinate math.
- **Success criteria**: Complete breakdown of data structures, state machines, math/acceleration formulas, timing constants, UI layouts with code references.
- **Interface contracts**: C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\teamwork\ORIGINAL_REQUEST.md
- **Code layout**: .agents/teamwork/teamwork_preview_spec_miner_survey_1/ holds artifacts.

## Loaded Skills
- **Source**: C:\Users\Administrator\.agents\skills\unlazy\SKILL.md
- **Local copy**: C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\teamwork\teamwork_preview_spec_miner_survey_1\skills\unlazy\SKILL.md
- **Core methodology**: Enforces completion discipline for substantial autonomous work by writing acceptance gates before execution, decomposing work with the Depth Tree, running approved checks, and re-verifying evidence before reporting.

## Key Decisions Made
- Decompose survey into 4 major focus areas as requested:
  1. Collapsible In-Session Toolbar & Navigation (Toolbar.kt, VncActivity.kt, LayoutManager.kt)
  2. RealVNC-Style Virtual Keys Bar (VirtualKeysCompose.kt, VirtualKeys.kt)
  3. Keyboard Input Handling & BMC Timing (KeyHandler.kt, Keyboard.kt)
  4. Touch, Touchpad & Virtual Mouse Controls (TouchHandler.kt, PointerModes.kt, VirtualMouseCompose.kt)

## Artifact Index
- DISPATCH.md — Initial task dispatch
- BRIEFING.md — Working memory & state
- progress.md — Liveness & task execution progress
- survey_avnc_spec.md — Final comprehensive technical survey report
- handoff.md — 5-component handoff report
