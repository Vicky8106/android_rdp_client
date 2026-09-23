# Scope: E2E Testing Track Orchestrator

## Test Architecture & Scope
Design, construct, and publish a comprehensive, opaque-box, requirement-driven end-to-end test suite derived strictly from user requirements and public interfaces in `ORIGINAL_REQUEST.md` and `PROJECT.md`.

### Methodology:
4-tier systematic approach:
- **Tier 1 - Feature Coverage (>=5 per feature)**: Isolated happy-path tests verifying each feature from `ORIGINAL_REQUEST.md`.
- **Tier 2 - Boundary & Corner Cases (>=5 per feature)**: Boundary conditions, empty inputs, max sizes, negative coordinates, zero-delays, rapid orientation toggles, network disconnect during handshake.
- **Tier 3 - Cross-Feature Combinations (pairwise)**: Feature interaction tests (e.g., zoom + mouse drag, reconnect + orientation flip, keystore encryption + auto-reconnect, modifier latch + click-drag).
- **Tier 4 - Real-World Application Scenarios (>=5 scenarios)**: End-to-end user workflows (e.g., full session connect -> modifier shortcut -> gesture navigation -> network drop auto-reconnect -> disconnect).

### Deliverables:
1. `C:\Users\Administrator\teamwork_projects\android_rdp_client\TEST_INFRA.md`: Test philosophy, architecture, runner command, feature inventory matrix, and scenario list.
2. Production test suite located under `app/src/test/java/com/freerdp/client/e2e/` (using JUnit 4 / Robolectric).
3. `C:\Users\Administrator\teamwork_projects\android_rdp_client\TEST_READY.md`: Signal that the test suite is complete with coverage breakdown.

### Interface Compatibility:
Use the public interfaces defined in `PROJECT.md § Interface Contracts`:
- `IRdpEngine`
- `MouseController`
- `CredentialStore`
- `AutoReconnectManager`

### Rules:
- Iterate via standard loop or test writer subagents.
- Deliver `TEST_READY.md` upon 100% test suite completion.
- Write handoff and notify parent.
