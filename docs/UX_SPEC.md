# UX Specification — Android FreeRDP Mobile Client

**Owner**: `product_ux` · **Audience**: implementing agents (UI in `:app`, behavior logic in
`:feature-*`) · **Snapshot**: 2026-09-22T19:50Z · **Companion**: `docs/PRODUCT.md` (principles),
`PROJECT.md` (contracts).

Conventions: dimensions in **dp**; states are exhaustive (implement all); "logic owner" names the
module whose FSM/repository provides the behavior — the screen code in `:app` binds to it.
Framework: **Jetpack Compose, Material 3**, dark/light aware, edge-to-edge.

---

## 1. App shell & navigation

Routes: `profiles` (start) → `editor?profileId={id}` / `settings` / `session?profileId={id}`.

- Start destination is always **Profile List**. Settings reachable from list top-bar.
- `session` is exited via system back → confirmation dialog ("Disconnect and leave session?") —
  a live RDP session must never die from an accidental back-swipe.
- Theme: Material 3 dynamic color when available; explicit dark/light override in Settings.

---

## 2. Profile list (home)

**Layout**: top bar (app title, Settings icon), LazyColumn of profile rows, FAB "Add profile".

**Row**: profile label (headline), `host:port · username` (supporting), preset badge, pin icon for
pinned profiles. Min height 64 dp; entire row is one tap target (≥48 dp).

**States**:
| State | Presentation |
|-------|--------------|
| Loading | 3 skeleton rows (shimmer) while repository loads `Flow` — bounded, no infinite spinner if data cached |
| Empty | Centered illustration + headline "No profiles yet" + body "Add a server to connect in one tap" + filled TAP `Add your first profile` button |
| Populated | Rows sorted: pinned first, then most-recently-connected |
| Error | Inline error card "Couldn't load profiles" + `Retry`; corrupt-file recovery (repository restores from `.bak`) surfaces as a snackbar "Recovered profile list from backup", not an error |

**Interactions**:
- **One-tap connect**: single tap on a row starts the session route immediately using stored
  credentials (no confirm step — this is the product's core gesture). Profile with *no* stored
  password routes to editor-password-prompt first.
- Long-press row → contextual menu: Edit, Duplicate, Delete (destructive → confirm dialog).
- Delete removes secrets from `CredentialStore` in the same operation.

---

## 3. Profile editor

**Sections**: Identity → Security → Display → Performance → Redirection → Network.

| Field | Control | Validation |
|-------|---------|------------|
| Label | TextField | required, ≤ 64 chars |
| Hostname / IP | TextField | required; non-blank; no scheme prefix (`rdp://` rejected with inline error) |
| Port | Numeric TextField, default **3389** | integer 1–65535 |
| Username | TextField | optional (some hosts use device creds) |
| Domain | TextField | optional |
| **Password** | **Secure field**: masked by default, eye toggle reveals transiently, `PasswordVisualTransformation` | never pre-filled on edit (write-only); stored **only** via `CredentialStore` (Keystore/EncryptedSharedPreferences); label helper: "Saved encrypted in Android Keystore"; plaintext never persisted in profile JSON, never logged |
| Certificate handling | Segmented button: *Warn on mismatch (default)* / *Accept all (explicit opt-in)* | default is secure |
| NLA / TLS | Switches, default **on** | — |
| Clipboard sync | Switch, default on | — |
| Color depth / Scaling | Dropdowns | sane defaults (32-bit, fit-width) |
| Performance preset | 4 radio cards (see §4) | default Balanced |
| Auto-reconnect + max attempts | Switch + stepper | attempts 1–10 |

**Actions**: `Save` (enabled only when valid; disabled state still visible ≥48 dp), `Cancel`
(discards; clears any entered password from memory), overflow `Delete profile` (confirm dialog,
reachable only when editing).

**Inline validation timing**: on blur and on save — never before first interaction.

---

## 4. Settings

| Group | Items |
|-------|-------|
| **Performance** | Preset picker — 4 cards with one-line trade-offs: **Ultra-Low Latency** ("Smoothest input; uses more data & battery"), **Balanced** ("Best default on Wi-Fi"), **Data Saver** ("Lower quality on metered networks"), **Battery Saver** ("Reduces frame rate to save power"). Applied to *new* sessions; a running session offers "Apply on reconnect". |
| **Heads-up display** | Telemetry HUD toggle (default **on**); HUD position follows safe corner; "Always show on session start" |
| **Mouse & touchpad** | Floating overlay default state (expanded/collapsed), virtual cursor visibility, touchpad-mode default, long-press duration hint |
| **Haptics** | Toggle (default on) |
| **Appearance** | Theme: System / Light / Dark |
| **Security** | "Forget all stored secrets" (destructive, confirm) |
| **About** | Engine version, native-lib availability status (see §7 degradation) |

Settings are read live by the session route; no restart required.

---

## 5. Session screen

### 5.1 Phase model (single source of truth: `IRdpEngine.connectionState` + reconnect state)

```
CONNECTING ──(cert needed)──> CERT_DIALOG ──accept──> CONNECTING
CONNECTING ──success──> CONNECTED ⇄ DEGRADED(reconnecting)
CONNECTING ──failure──> FAILED ──retry──> CONNECTING      FAILED ──back──> profile list
CONNECTED ──user disconnect──> CLOSED ──> profile list
```

| Phase | UI |
|-------|----|
| **Connecting** | Canvas area covered by *transient* card: host label, indeterminate progress, "Connecting to `host`…", `Cancel` button (≥48 dp). Auto-escalates copy after 5 s ("Still connecting — check the host is reachable") |
| **Cert trust dialog** | Blocking dialog: title "Verify this host", host + port, **SHA-256 fingerprint in monospace, wrap allowed**, first-seen date if known; actions: `Accept & remember` (per-profile TOFU), `Cancel` (→ FAILED with "Certificate not trusted"). Never auto-accepts |
| **Connected** | Full-bleed canvas (§5.2); quick toolbar auto-expands on entry, collapses after 4 s; HUD chip visible if enabled |
| **Degraded / reconnecting** | Reconnect chip (§5.6); canvas keeps last frame at 60 % brightness — **never** replaced by a full-screen error |
| **Failed** | Non-fullscreen failure card over last frame (or empty canvas): message + error code, `Retry` (primary), `Back` (secondary). Auto-retry only when reconnect FSM owns the attempt — otherwise wait for the user |
| **Closed** | Brief "Session ended" snackbar on profile list |

### 5.2 Remote desktop canvas

- Aspect-fit by default; letterbox color = surface color (dark-safe).
- **Direct-touch gesture map** (from `:feature-mouse` disambiguation engine):
  - tap → left click; double-tap → double click; long-press → right click
  - movement > touch-slop → **pan** (viewport translate) — must **never** also emit a click
    (anti-spious latch, F12)
  - two-finger pinch → zoom 1×–8× with offset clamping; zoomed panning keeps content in bounds
- Rotation / fold: session survives; canvas re-layouts; **dynamic resolution update debounced
  250 ms** before sending MS-RDPEDISP layout.
- HUD chip anchors top-end; toolbar + modifier bar anchor bottom (thumb arc).

### 5.3 Floating mouse overlay (R2 centerpiece)

- **Collapsed**: 48 dp grab handle (mouse icon) — one target to expand.
- **Expanded**: left-click zone, right-click zone, wheel/scroll strip, double-click button,
  drag works from the button face, **touchpad mode toggle** (relative cursor + tap-to-click +
  two-finger scroll inside overlay), collapse button, optional virtual cursor indicator.
- **Reposition**: drag the overlay header; **edge snapping** with insets so it never sits under the
  status bar or gesture nav; position stored **normalized** (`OverlayCoordinates`) and restored
  identically after rotation — never resets, never lands off-screen (acceptance AC-5).
- Visibility toggled from toolbar; state persists across sessions.
- While overlay is expanded over content it must be semi-transparent (≤ 92 %) and movable —
  per the "never obscure permanently" principle it offers one-tap collapse from its own header.

### 5.4 Quick-action toolbar (bottom, `:feature-session` toolbar FSM)

Actions (left→right): **Disconnect** (destructive, confirm-hold or dialog), **On-screen keyboard**,
**Touchpad toggle**, **Mouse overlay toggle**, **Clipboard panel**, **Hide toolbar**.
Behavior: expands on session start and on tap of the 48 dp handle; **auto-collapses after
4000 ms**; *any* touch on the toolbar or a modifier press resets the timer; collapse/expand is a
visible slide animation (≤200 ms).

### 5.5 Modifier bar (3-state, `:feature-session` modifier FSM)

Keys: `Ctrl` `Alt` `Win` `Esc` `⇧` plus horizontally scrollable `F1–F12`. Min target 48×48 dp.
States (always visually distinct, not color-only):
| State | Look | Enter | Exit |
|-------|------|-------|------|
| **Inactive** | outlined tonal | — | single tap → Latched |
| **Latched** | filled primary | single tap | next non-modifier key press consumes & auto-releases; or tap again |
| **Locked** | filled + small dot badge | double tap | single tap → Inactive |
Macros (`Ctrl+Alt+Del`, `Alt+Tab`, `Alt+F4`) dispatch full scancode sequences and release latched
modifiers afterward. Haptic tick on every state transition (§7).

### 5.6 Reconnect chip (top-center, `AutoReconnectManager`)

Compact pill that **never covers the canvas center**: states `Waiting for network…` →
`Reconnecting · attempt 2 of 5 · in 3 s` (countdown, exponential backoff + jitter) → success flash
`Reconnected` (auto-dismiss 1.5 s) → on exhaustion `Couldn't reconnect` + `Retry now` / `Back`.
Tapping the chip during countdown = manual `Retry now`. Announced to TalkBack (§6).

### 5.7 Telemetry HUD (`:feature-telemetry` collector)

- **Chip** (default on): `60 FPS · 38 ms` — RTT shown in ms; amber (>150 ms) / red (>400 ms) tint
  (text color also changes — not color-only: prefix `▲` appears).
- **Expanded panel** (tap chip): FPS, RTT, jitter, frames dropped, bandwidth estimate, active
  preset, connection state; refresh ≤ 500 ms; monospace numerals.
- Updates must not themselves jank the render loop (subscribe to `StateFlow`, no per-frame
  allocation in composition).

### 5.8 Latency feedback contract

Every user input (tap, key, modifier latch, toolbar/overlay button) shows visual or haptic
acknowledgment **≤100 ms**, independent of network RTT — e.g. modifier state flips locally and
optimistically; canvas taps flash a subtle ripple at the transform point.

---

## 6. Accessibility

- **All touch targets ≥ 48 dp** (toolbar icons, modifier keys, overlay buttons, chip hit slop).
- `contentDescription` required for: every icon-only button (disconnect, keyboard, touchpad,
  overlay handle, HUD chip, pin, FAB), profile rows ("Connect to {label}, {host}"), overlay zones
  ("Left click", "Right click", "Scroll wheel").
- **TalkBack announcements**: `onConnectionSuccess` → "Connected to {host}";
  `onConnectionFailure` → "Connection failed: {message}"; reconnect chip state changes → announced
  politely; modifier latch → "Control, latched"/"Control, locked".
- Focus order follows visual/thumb-arc order; dialogs trap focus; destructive actions confirm.
- Text honors system font scaling (sp); never convey state by color alone (modifier states have
  shape/badge differences too).
- Reduced-motion: collapse/expand animations become instant when system animator scale is 0.

---

## 7. Haptics (respected by Settings toggle)

| Event | Effect |
|-------|--------|
| Modifier latch (tap) | `Light` tick |
| Modifier lock (double-tap) | `Medium` tick |
| Modifier auto-release after next key | `Light` tick |
| Toolbar auto-collapse fired | none (avoid nagging) |
| Connect success / disconnect | `Medium` |
| Reconnect success | `Medium` (double for "recovered" feel) |
| Long-press → right-click fired | `Light` |
| Destructive confirm accepted | `Heavy` |

---

## 8. Dark / light & degradation

- Session chrome (toolbar, modifier bar, overlay, HUD, chips) uses Material 3 surface roles in
  **both** themes; overlay gains a 1 dp outline in light theme so it stays visible over bright
  remote desktops; letterboxing follows theme surface (no pure-black burn-in streaks in dark).
- **Graceful degradation — native lib missing**: if `NativeFreeRdpEngine` detects absent
  `libfreerdp`/`libfreerdp-android` `.so` at session start, show a dismissible **banner** on the
  session screen: "Native RDP engine unavailable in this build" with `Details` (no stack trace) —
  never crash, never blank-screen. Profile list remains fully usable (edit/organize profiles).
- Robolectric/JVM tests exercise the same code paths through `MockRdpEngine` (M1 contract).
