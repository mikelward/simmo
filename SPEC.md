# Simmo

Simmo picks the right SIM (or calling app) for every outgoing call, automatically,
based on the country you are calling. Android's built-in dual-SIM support stops at
"ask every time" or "always use SIM X"; Simmo adds a rules engine on top: *Australian
numbers go out on Telstra, US numbers on T-Mobile, everything else follows sensible
defaults* — including
hand-offs to another calling app (e.g. Google Voice) and help re-enabling a currently
disabled eSIM profile when a rule needs it.

Primary target: Pixel phones on current Android (Android 17 at the time of writing),
where several eSIM profiles can be installed at once and swapped in Settings.

## Product behavior

### Rules

Rules are an **ordered list**, evaluated top to bottom for every outgoing call; the
first *applicable* rule decides the call and evaluation stops. The user drags to
reorder. A rule pairs a **matcher** with an **action**:

- **Matcher**: one or more destination countries (ISO regions, shown with their
  calling codes — "+61 Australia"), or **any destination** (used by the preseeded
  defaults below). A multi-country rule matches when the destination is any of its
  countries, so "France, Germany, Italy → local eSIM" is one rule, not three.
  A rule can also carry a **country group** as a single entry. Shipped groups:
  - **EU/EEA** — the EU-27 and EEA EFTA states (Iceland, Liechtenstein, Norway)
    plus the EU territories with their own calling codes (Åland, Guadeloupe,
    Martinique, French Guiana, Réunion, Mayotte, Saint-Martin) and Svalbard.
  - **USA + territories** — the states plus Puerto Rico, US Virgin Islands,
    Guam, American Samoa, and the Northern Marianas, which a bare "US" country
    rule misses because each territory is its own dialing region within +1.
    Territory inclusion is explicit in the name because it isn't uniformly
    free: PR/USVI are domestic on every plan, but some prepaid tiers bill the
    Pacific territories internationally — a user whose plan excludes them
    picks the plain "United States" country entry (the 50 states + D.C.) and
    hand-adds PR/USVI instead. Searching "USA" shows the group and the country
    row side by side, which is the states-only vs. everything choice.
  - **North America** — the USA + territories group plus Canada and Mexico, for
    the plan tiers that include CA/MX (kept separate because many prepaid/MVNO
    tiers are domestic-only).
  - **Caribbean +1** — the non-US NANP countries (Jamaica, Dominican Republic,
    Bahamas, …) that dial like a domestic +1 call but bill internationally. Its
    purpose is the guard shape "Caribbean +1 → Ask" placed above a US rule, so
    look-alike domestic calls get confirmed instead of silently billed.

  A group is stored by id and resolved to members at decision time, so membership
  tracks app updates instead of freezing dozens of entries into the rule;
  countries a label excludes but a particular plan covers (UK, Switzerland, …)
  are added beside the group as ordinary entries. Groups are chosen to be
  label-faithful, stable sets — carrier-specific zone lists ("Tier 1" countries)
  differ per carrier and belong to a future custom-groups feature, not shipped
  data. The picker offers groups above the country list, found by their aliases
  ("EU", "EEA", "Europe", "European Union"; "USA", "America"; "North America",
  "NA"; "Caribbean", "West Indies") — and also whenever the search matches a
  member country, so typing "France" or "Jamaica" suggests the relevant group
  too.
  Because the +1 group is split by area code, a US rule does not catch Canadian or
  Caribbean numbers. Countries are added one at a time from a searchable picker
  rather than a flat ~240-row list (each shown in the editor as a removable entry):
  it fuzzy-matches by name, dial code, and ISO code (plus common aliases
  like UK/USA), ranked so exact and prefix matches lead, with uppercase input matched as
  an acronym (e.g. "US" → United States). On a blank query the picker leads with a
  "Suggested" bucket — the countries the user's own contacts have numbers in, the ones with
  the most contacts first (counted per contact, not per number) — so the destinations that
  matter to this user are one tap away instead of a scroll
  into the alphabetical list. It needs `READ_CONTACTS`; when that isn't granted the picker
  shows a tappable "suggest from your contacts" affordance in the bucket's place that
  requests it (the same permission the WhatsApp hand-off uses — one grant, one warm index
  serving both), so the feature is discoverable on its own rather than only after a
  contact-app hand-off happens to grant contacts. The rollup is computed off the main
  thread from the warm contact index. Emergency and undetermined-country handling
  is unchanged by multi-country matchers: emergency numbers are never touched, and
  undetermined destinations still match only any-destination rules.
- **Action**, one of:
  1. **Call with a specific SIM** — identified by the SIM's stable identity (see "SIM
     identity" below), e.g. "Telstra", never by physical slot.
  2. **Call with the SIM whose home country matches the destination** — applies only
     when exactly one active SIM's home country equals the destination country.
  3. **Hand off to another calling app** — e.g. Google Voice (see "Hand-off to
     another app").
  4. **Ask** — show Simmo's chooser for this call.
  5. **No change** — the call proceeds exactly as the system would have placed it
     (the user's default calling app / default voice SIM); Simmo doesn't intervene.

**Applicability (skip semantics).** A rule that cannot act right now is skipped and
evaluation continues with the next rule:

- its SIM is currently disabled or can't be re-bound unambiguously (such rules show
  greyed out in the list and are kept for when the SIM is re-enabled);
- its hand-off target is no longer reachable;
- its action needs UI (Ask, dial-intent hand-off) in a non-interactive context;
- its action is Ask but the chooser would have no target to offer (no active
  SIMs — e.g. the phone-state permission was revoked) — canceling would strand
  the call behind a chooser that can only cancel;
- "matching country SIM" finds zero or several matching active SIMs.

If no rule applies, the call proceeds unmodified — a call is never dropped. If the
selected action resolves to the SIM the call was already going to be placed on, Simmo
passes the call through unmodified — no redirect churn, no UI.

**Preseeded defaults.** A fresh install seeds two low-priority rules at the bottom of
the list — ordinary rules, reorderable and deletable:

1. *Any destination → call with the SIM whose home country matches.*
2. *Any destination → no change.*

So out of the box Simmo only intervenes when a destination unambiguously matches one
SIM's home country; everything else behaves as if Simmo weren't installed. Users who
want prompting insert an Ask rule wherever it should take over.

**On SIM change.**

- Disabling a SIM greys out (and skips) the rules that target it; they are kept and
  apply again when the SIM is re-enabled. The enable-assist surfaces through the
  chooser: when evaluation skipped a disabled-SIM rule and ends at Ask, the chooser
  says which rule wanted which disabled SIM and offers the enable flow (see
  "Disabled-SIM assist").
- When a new SIM is first seen, Simmo prompts the user to add rules for it; newly
  added rules are suggested above any rules that reference disabled SIMs. The
  prompt is an in-app card and — when notifications are allowed — a one-time
  notification, because first activation is the only moment a newly installed
  eSIM becomes visible to apps at all. A first-ever capture (fresh install)
  never notifies about the SIMs the user already had.

### Country detection

The dialed number is parsed offline with libphonenumber:

- Numbers in international format (`+61...`) map directly to a region.
- National-format numbers are interpreted against a **default region**, taken from the
  current network/SIM country and overridable in settings. A national-format number is
  by definition a call within the default region, so it matches that country's rule.
- Countries that share a calling code — the +1 North American Numbering Plan group
  (US, Canada, Caribbean) — are distinguished by area code where the parser's metadata
  can, falling back to *undetermined* where it can't (see TODO for supplementary
  mappings under evaluation).
- Short codes, USSD (`*#...`), and numbers that don't parse map to *undetermined*;
  only any-destination rules match them.
- **Emergency calls are never touched.** The platform does not consult call-redirection
  services for emergency numbers; Simmo additionally never redirects any number the
  platform flags as emergency, as defense in depth.

### The chooser (Ask flow)

When the winning rule says Ask, Simmo cancels the in-flight call and shows a
full-screen chooser: the dialed number, its detected country/flag, and the available
targets — each active SIM, each configured hand-off app, plus "remember this choice for
<country>" to create a rule inline. Confirming re-places the call on the chosen target.
Canceling abandons the call. When evaluation skipped disabled-SIM rules on the way to
Ask, the chooser also surfaces them and offers the enable flow ("Disabled-SIM assist").

If a call arrives when interactive UI is not allowed (e.g. placed from a Bluetooth
headset or Android Auto — the platform tells us per call), rules that would need UI
(Ask, dial-intent hand-off) are skipped and the silent rules still apply. A call is
never silently dropped — the sole, opt-in exception is the hands-free call guard (see
"Hands-free and Android Auto safeguards"), which always posts a notification for
what it blocks.

### Disabled-SIM assist

Rules may name a SIM that is currently disabled (an installed eSIM profile that isn't
active). A regular app can neither enumerate inactive profiles nor enable one — both
require carrier privileges or system permissions — so Simmo:

1. Maintains a **SIM registry**: every subscription Simmo has ever seen active, with its
   stable identity and last-seen time. Rules can reference any registered SIM, active or
   not. Registry entries can be renamed and deleted in settings.
2. A rule targeting an inactive SIM is skipped during evaluation (see "Rules"), but
   when the chooser opens it explains that the rule wanted (e.g.) Telstra, offers a
   one-tap jump to the system SIM management screen, and offers the active SIMs /
   hand-off apps as alternatives.
3. Simmo listens for subscription changes; when the wanted SIM becomes active it offers
   to place the held call via notification ("Telstra is now active — place your call?"),
   which reopens the chooser for that call. The call is never auto-placed, since
   enabling a SIM can take a moment and the user may have moved on; the held call is
   kept in memory only, expires after a few minutes, and is dropped as soon as any
   call is placed from the chooser. An ambiguous re-binding (two same-named active
   SIMs) never triggers the offer.

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
   target app at the dialed number via the app's number-carrying deep link (the MVP
   targets are **Google Voice**, **Microsoft Teams**, **Viber**, and **Yolla**). The number is normalized to
   E.164 off the fast path (the same warm parse country detection uses) before it is
   placed in the deep link. The launch intent is **resolved before Simmo responds to
   Telecom** (that check runs while the deadline watchdog is still armed): if the app
   can't take the hand-off — uninstalled, or its deep link unhandled — the call is left
   unmodified and Simmo posts a "couldn't open <app>" notification. Otherwise Simmo
   **cancels the call first and then launches** the app: the response can't be gated on
   the launch (the Telecom deadline is hard, and a blocked background launch doesn't
   report failure anyway), and cancelling first also rules out opening the app on top of
   a still-proceeding call. A launch that then fails can't be un-cancelled, so Simmo
   surfaces it (the same notification, with **Settings** to fix the app and **Redial** to
   retry) and, because notifications are optional, also drops the user in the dialer with
   the number — a permission-free recovery. That recovery redial mints a short-lived
   loop-guard pass token (see "Redirect-loop guard") so it proceeds on the carrier instead
   of re-entering the still-failing rule; unlike the chooser's account-pinned token, the
   dialer can't pin a SIM, so this token matches the number on any account. A number with
   no E.164 form (short code, undetermined) skips the rule. The
   receiving app takes over; whether it auto-dials or pre-fills is its behavior, not
   ours. Requires interactive context. (Readiness beyond "installed" — Google Voice's
   linked number, a Teams Phone plan, Viber Out or Yolla credit — isn't detectable from
   the intent, so an installed-but-unprovisioned target can still *open* to a setup
   screen; Simmo can't detect that. A silently blocked background launch likewise can't
   be detected; both are owed a device-QA pass, and the full-screen-intent launch is the
   fallback if the redirection binding isn't background-launch-exempt.)
3. **App-to-app (per-contact)**: for apps that call *contacts*, not arbitrary numbers
   (e.g. WhatsApp), Simmo hands off only when the dialed number belongs to a contact
   reachable on that app. It resolves the number against a warm contact index (built off
   the decision path from `READ_CONTACTS`, keyed by E.164) and, on a match, cancels the
   carrier call and opens the app's per-contact call — resolving the launch intent first,
   so an uninstalled target proceeds unmodified rather than stranding the call. A
   non-matching number (or a non-interactive context) skips the rule to the next.

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
- `READ_CONTACTS` (optional, requested when a feature needs it — app-to-app hand-off, or
  same-contact number correction) — the dialed-number→contact reverse lookup; a denial
  just disables those features.
- `POST_NOTIFICATIONS` — the "your SIM is now active, place the call?" nudge.
- **No `INTERNET` permission.** Everything (parsing, rules, registry) is on-device;
  dialed numbers never leave the phone (hand-off passes the number to the app the
  user chose, on-device). The app works fully offline and is auditable as such from
  its manifest. *Under consideration* (maintainer, 2026-07): crash reporting (e.g.
  Crashlytics) and usage analytics, which would add the `INTERNET` permission and
  revise this stance. Candidate analytics signals: SIM name × destination country
  routing counts and call completion/failure rates — never contact names, contact
  numbers, or dialed numbers themselves. `docs/PRIVACY.md` already flags this as a
  possible future change and must be updated in the same release as any such
  addition. Onboarding already records a "Make Simmo better" opt-in (default on,
  persisted with settings, so it survives backup/restore); nothing is collected
  today, and any analytics that ship must honor the stored choice — including one
  made before the feature existed.
- **Backups are on** (maintainer decision): rules, the SIM registry, and settings are
  included in Android backups and device-to-device transfers via explicit extraction
  rules scoped to exactly Simmo's own state files, so a phone upgrade keeps the rule
  set. The privacy line above is unaffected — dialed numbers are never persisted at
  all, so no call data can ever reach a backup. Restored state never routes by the
  old device's subscription IDs: a per-install marker (deliberately not backed up)
  detects the restore and invalidates stored IDs, so rules re-bind by carrier +
  display name per "SIM identity".

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
- Whether the trailing "no change" default should split into two distinct entries —
  explicitly routing to the default calling app vs. the default voice SIM — rather
  than one non-intervention rule.
- Hands-free safeguards: whether the upstream wrong-number choice (Android / Android
  Auto picking a contact's overseas entry over their local one) is fixable at the
  contact level before Simmo is consulted; and how best to identify "driving" for the
  call guard — the platform's per-call interactive-UI flag vs. car-mode signals.
