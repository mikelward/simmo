# Simmo

Simmo picks the right SIM (or calling app) for every outgoing call, automatically,
based on the country you are calling. Android's built-in dual-SIM support stops at
"ask every time" or "always use SIM X"; Simmo adds a rules engine on top: *Australian
numbers go out on Telstra, US numbers on T-Mobile, everything else asks* — including
hand-offs to another calling app (e.g. Google Voice) and help re-enabling a currently
disabled eSIM profile when a rule needs it.

Primary target: Pixel phones on current Android (Android 17 at the time of writing),
where several eSIM profiles can be installed at once and swapped in Settings.

## Product behavior

### Rules

A rule maps a **destination** to an **action**:

- **Destination**: a country (ISO region, e.g. `AU`, `US`), derived from the dialed
  number. One rule per country, plus a single **fallback** rule that applies when no
  country rule matches (including numbers whose country cannot be determined).
- **Action**, one of:
  1. **Call with a specific SIM** — identified by the SIM's stable identity (see "SIM
     identity" below), e.g. "Telstra", never by physical slot.
  2. **Hand off to another calling app** — e.g. Google Voice. Preferred mechanism is
     redirecting the call to that app's registered phone account; if the app doesn't
     expose one, Simmo cancels the carrier call and forwards the number to the app via
     an explicit dial/view intent (see "Hand-off to another app").
  3. **Ask** — show Simmo's chooser for this call (the fallback rule defaults to Ask).

Rule evaluation is deterministic and total: exact-country rule if one exists, else the
fallback rule. If the selected action resolves to the SIM the call was already going to
be placed on, Simmo passes the call through unmodified — no redirect churn, no UI.

### Country detection

The dialed number is parsed offline with libphonenumber:

- Numbers in international format (`+61...`) map directly to a region.
- National-format numbers are interpreted against a **default region**, taken from the
  current network/SIM country and overridable in settings. A national-format number is
  by definition a call within the default region, so it matches that country's rule.
- Countries that share a calling code — the +1 North American Numbering Plan group
  (US, Canada, Caribbean) — are distinguished by area code where the parser's metadata
  can, falling back to *undetermined* (and therefore the fallback rule) where it can't
  (see TODO for supplementary mappings under evaluation).
- Short codes, USSD (`*#...`), and numbers that don't parse map to *undetermined* and
  match only the fallback rule.
- **Emergency calls are never touched.** The platform does not consult call-redirection
  services for emergency numbers; Simmo additionally never redirects any number the
  platform flags as emergency, as defense in depth.

### The chooser (Ask flow)

When a rule (or the fallback) says Ask, Simmo cancels the in-flight call and shows a
full-screen chooser: the dialed number, its detected country/flag, and the available
targets — each active SIM, each configured hand-off app, plus "remember this choice for
<country>" to create a rule inline. Confirming re-places the call on the chosen target.
Canceling abandons the call.

If a call arrives when interactive UI is not allowed (e.g. placed from a Bluetooth
headset or Android Auto — the platform tells us per call), Simmo applies any rule it can
apply *silently* (SIM redirect); anything that would need UI (Ask, disabled SIM,
intent-based hand-off) instead lets the call proceed unmodified. A call is never
silently dropped — the sole, opt-in exception is the hands-free call guard (see
"Hands-free and Android Auto safeguards"), which always posts a notification for
what it blocks.

### Disabled-SIM assist

Rules may name a SIM that is currently disabled (an installed eSIM profile that isn't
active). A regular app can neither enumerate inactive profiles nor enable one — both
require carrier privileges or system permissions — so Simmo:

1. Maintains a **SIM registry**: every subscription Simmo has ever seen active, with its
   stable identity and last-seen time. Rules can reference any registered SIM, active or
   not. Registry entries can be renamed and deleted in settings.
2. When a matched rule targets an inactive SIM, the chooser opens in "enable" mode: it
   explains that the rule wants (e.g.) Telstra, offers a one-tap jump to the system SIM
   management screen, and offers the active SIMs / hand-off apps as alternatives.
3. Simmo listens for subscription changes; when the wanted SIM becomes active it offers
   to place the held call (one tap — the call is never auto-placed, since enabling a SIM
   can take a moment and the user may have moved on).

On devices with Multiple Enabled Profiles (recent Pixels), enabling one profile may not
require disabling another; where the platform does swap profiles, that trade is made by
the user inside system Settings, never by Simmo.

### Hands-free and Android Auto safeguards

A stated goal of the project: **no accidental international calls from hands-free
contexts.** Android and Android Auto have been observed picking a contact's overseas
number even when the contact also has a local one; the resulting call is expensive and
hard to notice from the driver's seat. Simmo sees every outgoing number *after* the
upstream contact-number choice but *before* the call dials, so it can still intervene.
Two mechanisms, independently toggleable:

- **Same-contact number correction** (if feasible): when the dialed number is overseas
  but the contact it belongs to also has a number local to the default region, redirect
  the call to the local number. Needs optional `READ_CONTACTS` (a reverse lookup kept in
  the warm snapshot — the decision path never queries the contacts provider live), and
  is constrained by the non-interactive rules below: where a confirmation can't be
  shown, correction only happens when the mapping is unambiguous. Whether upstream
  (choosing the right number at contact level, before Simmo is ever consulted) is also
  fixable is an open question.
- **Hands-free call guard** (opt-in): when the call is placed from a non-interactive
  context (Bluetooth, Android Auto), block calls that are overseas relative to the
  default region, and/or calls whose matching rule requires a currently disabled SIM.
  Blocking cancels the call and posts a notification explaining what was stopped and
  offering one-tap redial (with the chooser) once the user can look at a screen. This
  guard is the **only** exception to the "a call is never silently dropped" invariant,
  it is off by default, and the notification keeps it non-silent.

### SIM identity

Rules must survive re-slotting, profile re-installs, and factory moves, so a SIM is
identified by (in matching priority order):

1. **Subscription ID** — stable per profile on a device; primary key while it's valid.
2. **Carrier + display name** — human-meaningful fallback: if the subscription ID is
   gone (profile deleted and re-downloaded), a registry entry or rule re-binds to the
   active subscription whose carrier *and* display name both match (e.g. "Telstra" /
   "Telstra personal"). A carrier-only match is never bound silently — a different
   same-carrier SIM could bill the wrong plan — it goes to the chooser to re-learn.

Physical slot index is never part of rule identity. The ICCID is not readable without
carrier privileges, so it cannot be used. When a rule can't be re-bound automatically
(e.g. two active SIMs from the same carrier), the chooser asks and re-learns.

## How it hooks into Android

Simmo is the system's **call redirection app**: it holds `ROLE_CALL_REDIRECTION`
(requested via `RoleManager` during onboarding) and implements
`android.telecom.CallRedirectionService`. The platform then consults Simmo for every
outgoing carrier call, whatever app placed it (dialer, contacts, links), and Simmo
answers with exactly one of: proceed unmodified, redirect (new number and/or new phone
account — this is what re-targets a different SIM), or cancel. This role exists
precisely for apps that rewrite or re-route outgoing calls; it does not require Simmo
to be the dialer, and only one app on the device can hold it at a time.

Key platform constraints the design honors:

- **The decision deadline.** The platform requires a response within ~5 seconds — and
  a missed deadline means **Telecom cancels the call**, not "the call proceeds without
  us." A timeout is a dropped call, so Simmo never relies on it: every path — rule
  hit, no match, missing or half-loaded snapshot, cold start, internal error — ends in
  an explicit answer (worst case "proceed unmodified") delivered well inside the
  deadline. The decision path therefore reads only from an in-memory snapshot (rules,
  SIM registry, phone accounts) kept warm off the main thread; the service never does
  I/O, parsing table loads, or IPC on the decision path.
- **Redirect-loop guard.** The Ask and hand-off flows cancel a call and later re-place
  it, which routes the new call back through Simmo. Re-placed calls carry a short-lived
  in-memory pass token (number + chosen account + expiry); a call matching a live token
  passes through unmodified and consumes the token.
- **Re-placing calls** uses `TelecomManager.placeCall` with the chosen phone account as
  an extra, which needs `CALL_PHONE`.
- **Reading SIM/account state** (`SubscriptionManager` active subscriptions,
  `TelecomManager` call-capable phone accounts) needs `READ_PHONE_STATE`.
- **Enabling SIMs is Settings' job.** `EuiccManager.switchToSubscription` and inactive
  profile enumeration require carrier privileges, which Simmo does not and cannot have.
  The assist flow deep-links to the system's embedded-SIM management screen
  (`EuiccManager.ACTION_MANAGE_EMBEDDED_SUBSCRIPTIONS`, with a generic SIM settings
  fallback) and detects the result via subscription-change callbacks.

### Hand-off to another app

Two mechanisms, chosen per target app at rule-creation time based on what the app
supports:

1. **Phone-account redirect** (preferred): if the target app registers a call-capable
   phone account the user has enabled (VoIP apps that integrate with Telecom do), Simmo
   redirects the call to that account — same mechanism as a SIM redirect, works even in
   non-interactive contexts.
2. **Cancel-and-forward**: otherwise, Simmo cancels the carrier call and launches the
   target app with the dialed number (its dial intent). The receiving app takes over;
   whether it auto-dials or pre-fills is its behavior, not ours. Requires interactive
   context.

The rule editor only offers apps that are actually present and reachable by one of
these mechanisms, and shows which mechanism will be used. Reachability is also
enforced at call time: the decision snapshot carries the currently reachable hand-off
targets, and a rule whose target has since been uninstalled or disabled is never
routed to blindly — the call falls back to the chooser (or proceeds unmodified when
UI is forbidden).

## Permissions and privacy

- `ROLE_CALL_REDIRECTION` (role, not permission) — the interception hook.
- `READ_PHONE_STATE` — enumerate subscriptions and phone accounts.
- `CALL_PHONE` — re-place calls after Ask / enable flows.
- `READ_CONTACTS` (optional, requested only when enabling same-contact number
  correction) — reverse lookup for the hands-free safeguards; a denial just disables
  that feature.
- `POST_NOTIFICATIONS` — the "your SIM is now active, place the call?" nudge.
- **No `INTERNET` permission.** Everything (parsing, rules, registry) is on-device;
  dialed numbers never leave the phone. This is a hard product commitment: the app
  works fully offline and is auditable as such from its manifest.
- **Backups are on** (maintainer decision): rules, the SIM registry, and settings are
  included in Android backups and device-to-device transfers via explicit extraction
  rules scoped to exactly Simmo's own state files, so a phone upgrade keeps the rule
  set. The privacy line above is unaffected — dialed numbers are never persisted at
  all, so no call data can ever reach a backup.

## Architecture

Single `:app` Kotlin module, Compose UI (Material 3), mirroring the conventions of the
sibling Type Launcher project:

- **Domain layer** (pure Kotlin, no Android deps): rule model, SIM identity + re-binding
  logic, country derivation (libphonenumber), the decision function
  `(placed call, snapshot) → verdict`. Fully unit-tested; this is where all product
  logic lives.
- **Platform layer**: the `CallRedirectionService`, subscription/phone-account readers,
  subscription-change listener, role/permission plumbing. Thin adapters over the domain,
  kept too small to hide logic.
- **UI layer**: onboarding (role + permissions + default region), rules list + editor,
  SIM registry, and the chooser activity. State via ViewModel + StateFlow; persistence
  via DataStore. Screenshot tests (Robolectric + Roborazzi) cover each screen
  state, per the CI conventions in `AGENTS.md`.

Snapshot lifecycle: rules and registry load from DataStore into memory at process start
and stay subscribed; subscription/account state refreshes on change broadcasts. The
redirection service only ever reads the snapshot.

## Testing strategy

- **Unit tests** carry the product logic: rule evaluation, country parsing edge cases
  (national format, short codes, undetermined), SIM re-binding, loop-guard tokens,
  non-interactive degradation. The decision function is a pure function and is tested
  as a table.
- **Screenshot tests** cover each screen and state (empty rules, chooser, enable mode).
- **Device QA is irreducible for telecom behavior**: emulators have no real SIMs, no
  eSIM profiles, and CI has no emulator at all. A manual test matrix (documented in
  `docs/qa-matrix.md` as flows land) covers: dual-eSIM Pixel, rule hit/miss, disabled
  SIM enable round-trip, Bluetooth-placed call, hand-off to Google Voice, emergency
  numbers untouched.

## Non-goals (for now)

- Incoming calls (choosing which SIM *receives* is a carrier/platform matter).
- SMS/MMS routing.
- Being a dialer or in-call UI; Simmo never draws in-call.
- Per-contact or time-based rules (country-only until a real need shows up).
- Cost/tariff awareness; Simmo routes by rule, it does not estimate prices.

## Open questions

- Ask-flow ergonomics: cancel-then-re-place shows the dialer's call UI twice in quick
  succession on some OEM dialers; measure how disruptive this is on Pixel and whether
  the platform's confirm-first redirect variant (a system-drawn confirmation) is a
  better Ask UX than a custom chooser for the simple two-SIM case.
- Whether carrier names are stable enough across MVNO/roaming rebrands for the
  re-binding fallback, or whether the registry should also pin country + number prefix
  of the profile.
- Whether to offer per-rule "and remember for this number" overrides when a user
  diverges from a country rule for one number repeatedly.
- Quick Settings tile shape: a shortcut into Simmo's rules, a quick "switch data /
  calling SIM" toggle (which for a non-privileged app can at best deep-link into the
  system SIM screens), or both as separate tiles.
- Hands-free safeguards: whether the upstream wrong-number choice (Android / Android
  Auto picking a contact's overseas entry over their local one) is fixable at the
  contact level before Simmo is consulted; and how best to identify "driving" for the
  call guard — the platform's per-call interactive-UI flag vs. car-mode signals.
