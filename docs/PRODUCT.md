# Product Management — Android FreeRDP Mobile Client

**Owner**: `product_ux` · **Snapshot**: 2026-09-22T19:50Z · **Status**: living document for v1.0 scope.

---

## 1. Problem statement

Windows desktops and servers remain the backbone of enterprise operations, but the people who
manage them are increasingly *mobile*: on-call administrators, developers who leave their desk,
presenters standing next to a projector, travelers working from airports and hotels. Existing
mobile RDP clients fail them in three specific ways:

1. **They are desktop-shaped.** A mouse-and-keyboard interaction model shrunk onto a 6-inch screen:
   tiny context menus, hover-dependent affordances, and no way to right-click or reach `Ctrl+Alt+Del`
   with a thumb.
2. **They feel laggy.** Default rendering pipelines are built for bandwidth, not for *input-to-display
   latency*, so every click feels rubber-banded over cellular — and nothing on screen tells the user
   *why* it feels slow.
3. **They are fragile and insecure.** A subway tunnel or a Wi-Fi handoff kills the session and the
   user's work; credentials get stored in plaintext profiles; certificate warnings are either
   ignored or blocking.

This product solves that: a **mobile-first RDP client on FreeRDP** where the remote desktop is
driven one-handed through a floating mouse overlay and a disambiguated gesture system, sessions
survive network churn via an auto-reconnect state machine, performance is adaptive and *observable*
(live FPS/RTT/jitter telemetry with four presets), and credentials are secure by default via the
Android Keystore.

---

## 2. Target personas

### P1 — "Maya", one-handed mobile admin/dev on call *(primary)*
- **Context**: phone in one hand, server room / taxi / couch;半夜 pager alerts; cellular or flaky Wi-Fi.
- **Needs**: terminal and IDE access, `Ctrl`/`Alt`/`Win`/`F-keys` without a physical keyboard,
  reliable reconnect after network drops, low enough latency to type and see results,
  quick switching between saved jump-host profiles.
- **Frustrations**: UI that requires two hands, sessions that die silently, no visibility into lag.
- **Success =** connects in ≤2 taps from app open, works one-handed, recovers from a drop without
  losing context.

### P2 — "Priya", presenter
- **Context**: phone/tablet driving slides on a projector or TV, landscape, standing, audience watching.
- **Needs**: full-screen session with zero chrome, pinch-zoom and pan to emphasize content,
  scroll/right-click for context menus, confidence the overlay won't cover the slide being presented.
- **Frustrations**: persistent toolbars covering content, accidental clicks, orientation changes that
  reset the view.
- **Success =** present for 30 minutes with no visual obstruction and no accidental input.

### P3 — "Tomás", traveler
- **Context**: hotel/captive-portal Wi-Fi, roaming between networks, battery and data constrained.
- **Needs**: data-saver and battery-saver presets, auto-reconnect across network switches,
  clear security posture (cert handling he understands, credentials never in plaintext),
  telemetry that explains "why is this slow".
- **Frustrations**: apps that drain battery rendering at 60fps while scrolled away, opaque cert errors.
- **Success =** completes a session on a 3G-grade link using Data Saver, with the session surviving
  a Wi-Fi → cellular handoff.

---

## 3. User journey (first run → disconnect)

| # | Stage | User action | System behavior / states | Principle applied |
|---|-------|-------------|--------------------------|-------------------|
| 1 | **First run** | Opens app | Profile list in **empty state**: illustration + "Add your first profile" CTA; no modal walls of permission requests; onboarding copy explains security model in one screen | Secure-by-default; no dead-ends |
| 2 | **Add profile** | `+` → profile editor | Validates hostname/port inline; password entered in a **secure field** and written only through `CredentialStore` (Keystore/EncryptedSharedPreferences); never echoed back in plaintext | Secure-by-default credentials |
| 3 | **Connect** | One tap on profile row (or Connect in editor) | **Connecting state** (spinner + host + Cancel) → first connection to a host shows **cert trust dialog** (host + SHA-256 fingerprint, Accept-and-remember / Cancel) → **Connected**: canvas fills, quick toolbar auto-expands then collapses after 4 s | Latency feedback; never obscure desktop |
| 4 | **Work** | Tap = click, double-tap = double-click, long-press = right-click, drag = pan, pinch = zoom; floating overlay for precision clicks; modifier bar for Ctrl/Alt/Win/Esc/F-keys | Every input produces visual/haptic feedback **within 100 ms**; HUD shows FPS/RTT/jitter; overlays collapse on inactivity | One-handed reach; latency feedback |
| 5 | **Network drop** | (happens to them) | Session enters **degraded**: reconnect chip appears top-center — "Waiting for network…" → "Reconnecting (attempt 2 of 5) in 3 s" (exponential backoff + jitter). Canvas dims slightly but stays visible; no crash, no lost native session | Graceful degradation; never obscure |
| 6 | **Recover** | (automatic) or tap chip → Retry now | Backoff succeeds → chip flashes "Reconnected", session resumes, resolution re-synced (debounced dynamic resolution), telemetry ring buffer records the transition | Observability |
| 7 | **Disconnect** | Toolbar → Disconnect (medium haptic confirm) | Clean 5-step native teardown, credentials stay encrypted at rest, return to profile list with last-session metrics summary available in HUD history | Secure-by-default; leak-free |

Edge branch — **native library missing**: on a package/build where FreeRDP `.so` files are absent,
the app shows a **non-crashing, actionable banner** ("Native RDP engine unavailable — reinstall or
use a supported build") instead of `UnsatisfiedLinkError`; JVM tests exercise the same path safely
through `MockRdpEngine`. Graceful degradation, verified at the engine layer (M1 gate).

---

## 4. UX principles for mobile RDP

1. **One-handed reach.** Primary actions (connect, confirm, toolbar, modifier keys) live in the
   bottom thumb arc; every interactive target is ≥ **48 dp**; no gesture requires a second hand
   except the explicitly optional pinch-zoom.
2. **Latency feedback within 100 ms.** Any input event (tap, key, modifier latch, button) produces
   visual or haptic acknowledgment ≤ 100 ms — even when the network round-trip is slower. The
   telemetry HUD exposes RTT/FPS/jitter so the user always knows whether *they* or the *network*
   are the bottleneck. RTT above 150 ms triggers a subtle amber indicator.
3. **Never obscure the remote desktop permanently.** Every overlay is dismissible: quick toolbar
   auto-collapses after **4 s**, the floating mouse overlay drags out of the way and collapses to a
   handle, the HUD is toggleable, the modifier bar hides with the toolbar. The desktop is the
   protagonist; chrome is transient.
4. **Graceful degradation when the native lib is missing.** Absent `.so` files must never crash the
   app or a test run: detect, explain, degrade (actionable message; `MockRdpEngine` fallback in
   tests). Same philosophy for every subsystem: corrupt profile file → recover from backup, network
   gone → reconnect state machine, not a stack trace.
5. **Secure by default credentials.** Passwords go through `CredentialStore`
   (Android Keystore + EncryptedSharedPreferences, AES-256-GCM) with `CharArray` zeroing; they are
   masked in the editor, never written to logs or profile JSON, and never shown again in plaintext
   after save. Certificate validation defaults to *warn on mismatch* (TOFU-style trust dialog), not
   *accept all*.
6. **Predictable input semantics.** Touch disambiguation must match desktop RDP expectations:
   a movement beyond touch-slop is a pan and can **never** also fire a click (anti-spurious latch);
   modifier latching states are always visible; gesture conflicts are resolved in favor of the
   desktop-accurate interpretation.

---

## 5. Feature prioritization

### Must-have v1.0 (acceptance-blocking — maps to R1–R4)

| # | Feature | Rationale |
|---|---------|-----------|
| 1 | FreeRDP engine integration: NLA + TLS, cert verification callback | R1 — without it, nothing works |
| 2 | Connection profile CRUD + Keystore credential vault | R1/R3 — the unit of "one-tap connect" |
| 3 | One-tap connect from profile list (Material 3 UI) | R3 — core navigation model |
| 4 | Floating mouse overlay: L/R click, double-click, drag, wheel, long-press, touchpad mode, optional cursor | R2 — the product's headline interaction |
| 5 | Gestures: tap/double-tap/long-press, pan-without-clicking, pinch-to-zoom, anti-spurious latch, orientation persistence | R2 — one-handed operation |
| 6 | Quick-action toolbar with 4 s auto-collapse | R3 — transient chrome |
| 7 | Mobile modifier bar (Ctrl, Alt, Win, Esc, F1–F12) 3-state latch + scancode macros (Ctrl+Alt+Del, Alt+Tab) | R3 — keyboardless productivity |
| 8 | Auto-reconnect FSM on network drop / pause / resume (backoff + jitter, leak-free teardown) | R4 — sessions must survive real life |
| 9 | 4 performance presets (Ultra-Low Latency / Balanced / Data Saver / Battery Saver) + socket tuning + single-slot frame pacer | R4 — the latency promise |
| 10 | Telemetry HUD: FPS, RTT, jitter, connection state | R4 — observability is a feature |
| 11 | Dynamic resolution resize + text clipboard sync | R1 — correctness on rotation/foldables |
| 12 | Dark/light theming, ≥48 dp targets, contentDescriptions/TalkBack basics | Accessibility floor for store quality |
| 13 | Full 4-tier E2E suite green + `assembleDebug` APK | Acceptance gate (TEST_INFRA §5) |

### Backlog (post-v1.0 — explicitly NOT blocking v1.0 acceptance)

| Feature | Target release | Notes |
|---------|----------------|-------|
| Multi-monitor / session window selection | v1.2 | Needs MS-RDPEDISP multi-monitor layout extension |
| File transfer (drive / folder redirection) | v1.2 | MS-RDPEDRDY; larger UX surface (transfer queue UI) |
| RD Gateway (HTTP) support | v1.2 | Enterprise requirement; config fields + tunnel plumbing |
| USB device redirection | v1.3 | Native-side work; niche but requested by admins |
| Gamepad support | v1.3 | Input-mapping screen; low priority vs. touch parity |
| Mouse-mode polish (precision mode, scroll inertia, cursor trail options, haptic scroll ticks) | v1.1 | Quality delta on the headline feature |
| RDP file import / export of profiles | backlog | Convenience |
| Wake-on-LAN ping from profile list | backlog | Admin convenience |

---

## 6. Release plan

| Release | Theme | Exit criteria |
|---------|-------|---------------|
| **v1.0** | Acceptance complete, store-ready | All `ORIGINAL_REQUEST.md` acceptance boxes checked **with cited evidence** (`docs/ACCEPTANCE.md` filled); E2E Tiers 1–4 green + Tier-5 adversarial hardening passed; `assembleDebug` + `testDebugUnitTest` 100%; native `.so` packaged; UX spec §1–8 implemented (48 dp targets, HUD, cert dialog, reconnect chip) |
| **v1.1** | Polish & field hardening | Mouse-mode polish items; accessibility deep pass (TalkBack walkthrough of every screen); foldable/multi-window validation; preset tuning informed by v1.0 telemetry (RTT/jitter field data); crash-free session rate ≥ 99.5% in dogfood |
| **v1.2+** | Backlog expansion | Multi-monitor, file transfer, RD Gateway per §5 backlog order (re-prioritized by real user feedback) |

**Out of scope for all v1.x** (unless requirements change): iOS client, RDP *server* role,
VNC protocol support, audio redirection tuning beyond defaults.
