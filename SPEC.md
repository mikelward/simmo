# Simmo

Simmo picks the right SIM (or calling app) for every outgoing call, automatically,
based on the country you are calling. Android's built-in dual-SIM support stops at
"ask every time" or "always use SIM X"; Simmo adds a rules engine on top: *Australian
numbers go out on Telstra, US numbers on T-Mobile, everything else follows sensible
defaults* — including
hand-offs to another calling app (e.g. Google Voice) and help re-enabling a currently
disabled eSIM profile when a rule needs it.

Simmo's second, smaller pillar is **mobile data while traveling**: data rules record
which SIM should carry data in which country — and where roaming is fine — and when
the phone disagrees, Simmo warns and guides the fix (it cannot make the change
itself; see "Data rules").

Primary target: Pixel phones on current Android (Android 17 at the time of writing),
where several eSIM profiles can be installed at once and swapped in Settings.

## Product behavior

Simmo keeps two rule lists that share one grammar — ordered, first applicable rule
wins, the same country picker and groups — but differ on the axis they match and the
power behind them. **Calling rules** match the *destination* of an outgoing call
("when calling Australia…") and are **enforced**: Simmo redirects the call itself.
**Data rules** match the *current country* ("when in Australia…") and are
**watched**: Simmo cannot switch data, so it checks reality against the rule and
warns with a guided fix. The third place is Android's own **System settings**, where
every switch that actually changes phone state lives; Simmo reaches it by buttons
labeled exactly that — one consistent term, so it is always obvious what Simmo does
itself versus what only Android can do. One deliberate exception (maintainer,
2026-07-19): the data triage card labels its jump **"Change SIM"** rather than
"System settings," because on that card naming the actual goal reads clearer than
the generic term — the card is already framed as a data-SIM problem, so the jump's
purpose isn't ambiguous.

### Calling rules

Rules are an **ordered list**, evaluated top to bottom for every outgoing call; the
first *applicable* rule decides the call and evaluation stops. The user drags a rule's
handle to reorder, taps it to edit, and reaches a per-rule menu (its ⋮ button or a
long-press) to **edit, duplicate, enable/disable, or delete** it — duplicate drops a
copy directly below the original as the starting point for a variant, and delete takes
effect immediately rather than asking to confirm first. A deleted rule is not removed
outright: it stays in the list **struck through** and inert, offering an **Undo** in
place of its menu. The delete is committed (a *purge* — the struck-through entries are
dropped) when the user leaves the app, or on demand via the header's **Apply** button.
Apply takes the place of the **Done** button whenever *any* deletion is pending — a
calling rule, a data rule, or a custom group — and commits all three at once, so it
surfaces on both the rules and groups screens and flushes the struck-through entries in
place (the affected list compacts) without leaving. With nothing pending the button is
Done, which simply closes the UI (the redirection service keeps running). Until a purge,
the delete is fully reversible — any number of deletions can be undone. The same model
covers data rules and custom
groups, with one deliberate difference for groups: a struck-through group **still
resolves** for rules that reference it, so a mistaken group delete doesn't strand those
rules during the undo window; only the purge commits the loss (after which the rules match
none of the group's members — see below). A rule pairs a **matcher** with an **action**:

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
  are added beside the group as ordinary entries. The **shipped** groups are chosen
  to be label-faithful, stable sets; carrier-specific zone lists ("Tier 1" countries)
  differ per carrier, so those are **custom groups** the user builds instead — a named
  set of countries (e.g. "Vodafone Zone 1") created on the Country groups screen — or
  from a "New group" entry in the rule editor's picker, which builds the group mid-rule
  and adds it to the rule being scoped (that group is committed together with the rule
  when the rule is saved, so cancelling the rule leaves no orphan group) — and then
  selectable in a rule exactly like a built-in group. A custom group is stored
  under a stable id (disjoint from the built-in ids), and its membership resolves on
  the decision path from the same in-memory snapshot the built-ins resolve from — never
  from I/O. Deleting a group leaves rules that referenced it matching none of its
  members (their other countries still match), never an error. The picker offers groups
  above the country list, found by their aliases
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
  3. **Hand off to another calling app or account** — e.g. Google Voice, or a
     SIP provider's calling account (see "Hand-off to another app").
  4. **Ask** — show Simmo's chooser for this call.
  5. **No change** — the call proceeds exactly as the system would have placed it
     (the user's default calling app / default voice SIM); Simmo doesn't intervene.

**Applicability (skip semantics).** A rule that cannot act right now is skipped and
evaluation continues with the next rule:

- the user has **turned the rule off** — a disabled rule stays in place (its order,
  target, and settings intact) but never acts, so it can be switched back on without
  being rebuilt. This is distinct from the *automatic* skips below, which the rule
  can't control; a disabled rule shows greyed with its own "Disabled" label;
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
targets — each active SIM, each other enabled calling account (e.g. a SIP provider),
each configured hand-off app, plus "remember this choice for
<country>" to create a rule inline. Confirming re-places the call on the chosen target;
a re-place that fails (the target vanished since the chooser opened, `CALL_PHONE`
revoked) drops the user in the dialer with the number — the same recovery as the
delayed call — instead of stranding the already-canceled call.
Canceling abandons the call. When evaluation skipped disabled-SIM rules on the way to
Ask, the chooser also surfaces them and offers the enable flow ("Disabled-SIM assist").

If a call arrives when interactive UI is not allowed (e.g. placed from a Bluetooth
headset or Android Auto — the platform tells us per call), rules that would need UI
(Ask, dial-intent hand-off) are skipped and the silent rules still apply. A call is
never silently dropped — the sole, opt-in exception is the hands-free call guard (see
"Hands-free and Android Auto safeguards"), which always posts a notification for
what it blocks.

### Call feedback and delay

Global options on the Settings screen (reached from the rules list; also home of the
SIM registry and of the "Make Simmo better" telemetry switch — see "Permissions and
privacy"), all off by default:

- **Show which SIM or app is used**: when a rule routes a call, a brief toast names
  where it went — "Calling using Telstra" for a SIM or another calling account (on a
  redirect and when the call was already on that target; a calling account is named
  by its live account label), "Calling using Google Voice" for an app hand-off (shown
  once the app launch has been sent; a failed hand-off shows its failure notice
  instead, never both). Calls the user routed by hand in the chooser don't toast —
  they just tapped the target by name. The toast is posted after the service has
  answered Telecom, so it can never delay the decision.
- **Delay before calling** (1–10 s): when a rule redirects a call to a SIM or another
  calling account in an interactive context, Simmo cancels the call and shows a
  countdown screen instead — the target's name, the number and destination, Cancel,
  and Call now — then re-places the call on that target when the countdown ends (the
  chooser's cancel-and-re-place mechanism, pass token included). It exists to give the
  user a beat to abort a call about to go out on an unexpected target — an accidental
  international call, or a SIP account with no credit. The response to Telecom is
  never delayed (the deadline invariant stands); non-interactive calls (Bluetooth,
  Android Auto) and calls already on the rule's target skip the delay; a re-place
  that fails (the target vanished, `CALL_PHONE` revoked mid-countdown) drops the user
  in the dialer with the number rather than stranding the call, with a wildcard pass
  token so the redial doesn't re-enter the rule.

### Disabled-SIM assist

Rules may name a SIM that is currently disabled (an installed eSIM profile that isn't
active). A regular app can neither enumerate inactive profiles nor enable one — both
require carrier privileges or system permissions — so Simmo:

1. Maintains a **SIM registry**: every subscription Simmo has ever seen active, with its
   stable identity, last-seen time, and last-known home country and own line number
   (captured while the SIM was active, so they still show for a disabled profile; the
   number needs optional `READ_PHONE_NUMBERS`). Data-only subscriptions — travel
   eSIMs without calling — are registered too, so the roaming watch's no-data nudge
   can name a disabled local profile (see "Data rules"); they are never offered as
   calling-rule targets. Rules can reference any registered call-capable SIM,
   active or not. The SIMs screen shows each entry with its number and country; registry
   entries can be renamed and deleted in settings.
2. A rule targeting an inactive SIM is skipped during evaluation (see "Calling
   rules"), but
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

### Quick Settings tile

A "Manage SIMs" tile in Quick Settings jumps straight into Simmo's SIMs screen, which
in turn offers the same one-tap jump to the system SIM settings as the chooser. The
tile is a shortcut, not a toggle: enabling/disabling a SIM and changing the default
voice or data SIM all require carrier privileges or `MODIFY_PHONE_STATE`, which a
regular app cannot hold on any current Android version — so the fastest honest path
is tile → Simmo's SIM registry → system SIM settings. The tile is stateless and always
renders active. A tap before onboarding is complete lands on onboarding, and the SIMs
screen opens as soon as the grants are in place.

### Data rules

Calling rules decide each outgoing call; **data rules** watch mobile data as the
user travels. A data rule pairs a **location matcher** — one or more countries or a
country group, the same picker and groups as calling rules, but matched against
*where the user is* (the current network country), not who they're calling — with an
**expectation**, one of:

1. **Use <SIM> for data** — this SIM should carry data here. Simmo warns on arrival
   when a different SIM is the data SIM — before roaming data flows, not after — and
   guides the switch. (The stranded shape — no data at all because roaming is off on
   a non-local data SIM — is handled even without a rule; see the no-data nudge
   below.)
2. **Roaming OK** — data roaming here is expected (paid for, or free); no warning.
   Optionally scoped to specific SIMs, because roaming agreements are per plan: "in
   EU/EEA, roaming OK on Vodafone" stays quiet for the Vodafone SIM but still warns
   if the US SIM ends up carrying data there.
3. **Warn** — always warn here: the guard shape, placed above a broader Roaming OK
   rule the same way "Caribbean +1 → Ask" sits above a US calling rule.

Data rules are an ordered list, first match wins, with the same editing surface as
calling rules (drag to reorder, tap to edit, per-rule menu). The two lists live as
**Calling / Data tabs on the rules home** (decided at build time, 2026-07): one
screen, one set of affordances, the tab naming the list — matching the
Calling rules / Data rules / System settings terminology split. Data rows and the
data editor's matcher show plain country names, no dialing codes: the matcher is
where the user *is*, not who they're calling. When no rule matches,
the default is to **warn when the active data SIM is roaming** — once per
SIM-and-country arrival, never a repeat nag for the same trip; a SIM on its home
network never warns. The warning is about *state*, not current traffic (maintainer,
2026-07): it fires on arrival even while Wi-Fi is carrying the bytes, because the
roaming leak starts the moment Wi-Fi drops and the arrival is the one moment worth
catching — the per-arrival dedupe already caps the noise at one notification. When
the arrival ends — the country or the data SIM changes — an unattended warning is
withdrawn along with the dedupe mark: a present-tense "Using data roaming" never
outlives the trip it describes.

**Preseeded default.** A fresh install seeds one data rule (maintainer, 2026-07):
*When in EU/EEA → Roaming OK on SIMs homed in EU/EEA* — the regulation-backed
roam-like-at-home arrangement, label-faithful and stable the way the shipped
country groups are, so an EU user's first trip inside the zone stays silent instead
of warning about roaming that is free by law. It is an ordinary rule — visible,
reorderable, editable, deletable — and its SIM scope, "SIMs homed in the matched
countries," is the data-side sibling of the calling side's matching-country action.

**No-data nudge (rule-less).** When the data SIM is not local and its data roaming
is off, the user simply has *no mobile data* — a connectivity problem, not a
billing one — so Simmo nudges without requiring any rule (maintainer, 2026-07):
"No data here — Telstra is local," naming the SIM to switch to, or the SIM to
*enable first* when the local SIM is a registered-but-disabled profile (the SIM
registry keeps each SIM's last-known home country precisely so a disabled eSIM can
still be recognized as local). The nudge only fires when there is such a SIM to
offer, follows the same Settings-into-triage flow. Unlike the roaming case there is
no rule to record — "no mobile data here is fine" isn't a data-rule expectation — so
the card's only action is **Change SIM**; a rule-free per-trip dismiss (leaning on
the existing once-per-arrival suppression, not a negative rule) is the planned way to
quiet it deliberately (TODO).

**Watched, not enforced.** Changing the default data SIM or a SIM's data-roaming
toggle needs carrier privileges or `MODIFY_PHONE_STATE` (the same wall the Quick
Settings tile documents), so a data rule is an expectation Simmo checks reality
against — Simmo never flips anything, and its data surfaces never show controls that
pretend to. The warning is a notification — "Using data roaming", naming the SIM and
country — with two actions (maintainer, 2026-07): the doing-action, labeled by the
message's own verb (**Enable** when the fix is a disabled local profile, else
**Switch**), jumps to the system SIM settings where data switches actually flip;
**Rules** — and tapping the notification itself — opens Simmo's data rules screen,
where whether this arrangement is fine gets recorded.

**Triage.** The data rules screen leads with the live situation while one exists —
which SIM is carrying data, where, roaming or not, and which active SIM is local —
with both honest resolutions one tap away:

- **This is OK** creates a Roaming OK rule prefilled with the current country and
  SIM, with one-tap suggestions to widen it to the shipped or custom groups
  containing that country ("all of EU/EEA?"), so the rest of the trip — and the next
  one — stays quiet.
- **System settings** jumps to where the data SIM and roaming toggles actually live.
  Simmo sees the outcome via subscription callbacks and then offers to remember it
  as a "Use <SIM> for data" rule for this country.

Without notification permission nothing is lost but the push: the triage card still
shows the state whenever Simmo opens.

### Hands-free and Android Auto safeguards

A stated goal of the project: **no accidental international calls from hands-free
contexts.** Android and Android Auto have been observed picking a contact's overseas
number even when the contact also has a local one; the resulting call is expensive and
hard to notice from the driver's seat. Simmo sees every outgoing number *after* the
upstream contact-number choice but *before* the call dials, so it can still intervene.
Two mechanisms, independently toggleable:

- **Same-contact number correction** (shipped; the Settings toggle "Use contacts'
  local numbers", off by default): when the dialed number is overseas relative to the
  default region but the contact it belongs to also has a number local to that region,
  Simmo routes the local number instead. Interactively, the correction is never
  silent — the chooser opens to confirm, listing the contact's local number(s) (the
  first preselected) with "as dialed" one tap away; the SIM tapped there places the
  selected number. A number listed by **more than one contact** (a shared line) is
  confirm-only: the chooser labels each owner's local numbers by contact and
  preselects the number as dialed (a sole-owner correction preselects its local
  number instead) — whose number to call is the user's guess to make, so picking an
  owner is always a deliberate tap. Where no confirmation can be shown such a line
  is never corrected. Where no confirmation can be shown (Bluetooth,
  Android Auto — or no chooser target to offer), the correction applies silently and
  only when the mapping is unambiguous: one owning contact with exactly one local
  number. An **ambiguous** correction there — a shared line, or several local
  numbers — never touches the in-flight call (it proceeds as dialed), but is not
  dropped either: Simmo posts a notification ("Call Mum's local number?" — or
  "Shared number — call a local one?" when the line is shared, so no owner is
  presumed) that opens
  the confirmation chooser once the user can look at a screen; nothing is
  auto-placed, and the offer self-dismisses after a few minutes so a stale number
  isn't re-offered. Needs `POST_NOTIFICATIONS`; without it the ambiguous case simply
  proceeds (nothing failed, so no toast fallback). A silent
  correction uses Telecom's redirect (never cancel-and-re-place), carries any
  rule-chosen SIM in the same redirect, and the rules evaluate the *corrected*
  number — so a localized call matches the local rules, including the
  matching-country default. Needs optional `READ_CONTACTS` (requested when the
  toggle is switched on); each contact number's region is resolved when the warm
  index is built, so the decision path stays an in-memory lookup. Fixing the choice
  upstream instead — at contact level, before Simmo is consulted — was investigated
  and closed (`docs/upstream-wrong-number.md`): touch paths honor a stored contact
  default, but Assistant/Android Auto voice calls pick opaquely, don't reliably
  honor it, and expose no steering API, so correction at the redirection hook is
  the mitigation for every path. The one upstream lever — offering to set the
  contact's local number as their default, which fixes only the tap path and needs
  `WRITE_CONTACTS` — stays a backlog idea, never a silent contact edit.
- **Hands-free call guard** (shipped; the Settings "Hands-free guard" section, two
  independent toggles, both off by default): when the call is placed from a
  non-interactive context, block calls that are overseas relative to the default
  region, and/or calls whose matching rule was skipped because its SIM is disabled —
  the latter fires whatever a lower rule would have done, since the point is not to
  let the call quietly go out some other way. Blocking cancels the call and posts a
  notification saying what was stopped ("Blocked a call to United Kingdom" / "Blocked
  a call: Voda AU is disabled"); tapping opens the chooser for the call — with the
  enable assist for the disabled-SIM case, any pending same-contact correction's
  choices, and the corrected number when a silent correction was pending — once the
  user can look at a screen. Nothing is ever auto-placed, and the notification never
  self-dismisses: post-drive can be hours away and it is the only record the call
  didn't happen. The guard is the **only** exception to the "a call is never
  silently dropped" invariant, and it is never actually silent: where the
  notification can't post it degrades to a plain toast. Ordering: emergency and
  pass-token (re-placed) calls are never blocked; a silently corrected call is
  judged by its corrected — local — destination, so the correction and the guard
  compose instead of fighting; the overseas block outranks the disabled-SIM block;
  and the guard only fires when the chooser would have a redial target — with
  nothing visible in telephony (which is also exactly what a revoked
  `READ_PHONE_STATE` looks like, so an empty read proves no SIM disabled) the call
  proceeds unmodified rather than cancelling into a chooser that can only cancel.
  The "no UI may be shown" signal is the platform's per-call interactive flag —
  already on the decision path — with car-mode signals still an open question as a
  refinement.

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

### Data-roaming visibility (no foreground service)

Everything data rules need to *see* is readable with `READ_PHONE_STATE`, which
Simmo already holds: the default data subscription and the subscription actually
carrying data right now (they differ during temporary switches), whether that
subscription's network is roaming (the carrier-defined flag), each SIM's own
data-roaming setting, and each active SIM's home country against the current
network country — which is all "SIM X is local here" means. What Simmo cannot do is
change any of it (see "Data rules: watched, not enforced").

Simmo deliberately runs **no foreground service** to watch this. A persistent
notification is a bad trade for a warning whose useful latency is minutes, not
seconds — roaming cost accrues over hours of background data, not in the first
moments. Instead the check runs at moments the process is already, or can be,
awake:

1. **Existing wakes**: every outgoing call (the redirection service binds), app or
   tile open, subscription changes, and the network-country change broadcast — the
   telephony refresh they already trigger gains the roaming check. While the
   process lives, a service-state listener on the data subscription reports roaming
   transitions in real time.
2. **Manifest-registered receivers**, which wake a dead process (all three are
   exempt from the implicit-broadcast restrictions): `ACTION_TIMEZONE_CHANGED` —
   landing abroad means airplane mode off, the network sets the clock, Simmo
   wakes; this is the arrival moment — plus `ACTION_CARRIER_CONFIG_CHANGED` (SIM
   load/re-evaluation) and `ACTION_BOOT_COMPLETED` (to re-register the callback
   below).
3. **A `ConnectivityManager` callback delivered by `PendingIntent`**, which fires
   without the app running when a cellular network newly appears — the common
   shape of a same-timezone land border crossed while the process is dead, where
   the home connection tears down and the roaming network attaches; the receiver
   checks the roaming capability bit and skips verifiably-home fires. The
   `PendingIntent` path reports networks *appearing*, never an existing network's
   capabilities changing, so a handover that keeps the same network alive and
   merely flips it to roaming cannot wake a dead process — no public API can.
   That flip is covered while the process is resident (a live capability
   callback watches for exactly it) and otherwise caught by the next wake from
   layers 1–2. Registrations don't survive reboot, hence the boot receiver;
   exact firing behavior across carriers and handovers needs device QA. This layer is the one part of the
   watch that needs manifest additions: `ACCESS_NETWORK_STATE` for the callback
   registration and the capability check, and `RECEIVE_BOOT_COMPLETED` for the
   boot receiver that re-arms it — both normal install-time permissions
   (auto-granted, no dialog).

There is no broadcast for "roaming started" itself — service-state and
airplane-mode changes cannot be manifest-registered — which is why this lattice
exists. If every layer misses, the warning appears at the user's next interaction
that reaches Simmo, and the notification-less degradation (the triage card on
open) still stands.

### Hand-off to another app

Two mechanisms, chosen per target app at rule-creation time based on what the app
supports:

1. **Phone-account redirect** (preferred): if the target app registers a call-capable
   phone account the user has enabled (VoIP apps that integrate with Telecom do), Simmo
   redirects the call to that account — same mechanism as a SIM redirect, works even in
   non-interactive contexts. Every enabled call-capable account that isn't a SIM
   subscription — SIP providers and other calling apps registered in the system's
   calling-accounts settings — is read into the snapshot and offered directly: as a
   rule action beside the SIMs in the editor, and as a chooser target. Only accounts
   that can place `tel:` calls are offered (redirects keep the original
   telephone-number handle, which a `sip:`-only account can't take); an account whose
   scheme support isn't readable is offered best-effort. The account list refreshes
   with the rest of the telephony snapshot — on subscription changes and whenever the
   app returns to the foreground, since enabling or disabling a calling account in
   system settings fires no event a non-dialer app can hear. A rule stores
   the account's id (component + account id, stable across reboots) plus a copy of its
   display label so the rule stays readable while the account is gone; a rule whose
   account disappears (app uninstalled, account disabled) is paused — greyed in the
   list, skipped in evaluation — and applies again when the account returns. Account
   labels are read via `TelecomManager.getPhoneAccount` (needs `READ_PHONE_NUMBERS`,
   requested together with `READ_PHONE_STATE` — same permission group, one dialog),
   degrading to the providing app's name when that grant is missing.
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
   the number — a permission-free recovery. A failure notice that can't post at all
   (notifications denied or off, or its channel blocked) degrades to a plain text
   toast, so a failed hand-off is never silent in either direction. That recovery redial mints a short-lived
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
- `READ_PHONE_STATE` — enumerate subscriptions and phone accounts, and read the
  data-subscription and roaming state that data rules watch.
- `READ_PHONE_NUMBERS` (optional) — each SIM's own line number for the SIMs screen,
  and other apps' calling-account labels ("SIP – work") for the editor and chooser.
  Requested with `READ_PHONE_STATE` in onboarding (the two share Android's Phone
  permission group, so it's one dialog), and again from the SIMs screen when only
  the phone grant is held (granted silently, same group). A denial just leaves the
  number off the row and degrades account labels to app names.
- `CALL_PHONE` — re-place calls after Ask / enable flows.
- `ACCESS_NETWORK_STATE` + `RECEIVE_BOOT_COMPLETED` — the roaming watch's
  connectivity layer: registering the cellular network callback (and reading its
  roaming capability), and waking after a reboot to re-register it (see
  "Data-roaming visibility"). Both are normal install-time permissions —
  auto-granted, no dialog, nothing for onboarding to ask.
- `READ_CONTACTS` (optional, requested when a feature needs it — app-to-app hand-off, or
  same-contact number correction — and offered as an onboarding row) — the
  dialed-number→contact reverse lookup; a denial just disables those features.
- `POST_NOTIFICATIONS` (optional) — the "your SIM is now active, place the call?"
  nudge, the "couldn't open <app>" hand-off failure notice, and the "Using data
  roaming" warning (which degrades to the in-app triage card — see "Data rules").
  Requested
  contextually, like `READ_CONTACTS`: at the chooser's SIM-settings jump, and when
  a hand-off action is picked in the rule editor — which also shows an enable hint
  (with an Allow button) under the selected action while notifications are off,
  deep-linking to the app's notification settings when the request dialog can't
  show. Also an optional onboarding row ("Show errors and shortcuts"). A denial (or a
  blocked channel) never blocks a feature; the failure notice degrades to a toast (see
  "Hand-off to another app").
- **Crash reporting and usage analytics (Firebase), governed by the user's
  choice.** Decided (maintainer, 2026-07): Firebase Crashlytics and Analytics are
  compiled in — which adds the `INTERNET` permission — but a build only carries a
  Firebase config when it was made with the untracked `google-services.json`
  (`SETUP.md`); without one Firebase never initializes and nothing is collected.
  Core behavior is unchanged and stays on-device: parsing, rules, and the registry
  work fully offline, and dialed numbers, contact names, and contact numbers are
  never collected or transmitted (hand-off passes the number to the app the user
  chose, on-device). Collection is disabled in the manifest and follows the
  persisted "Make Simmo better" opt-in (default on, set during onboarding and
  changeable anytime on the Settings screen; persisted with settings, so it
  survives backup/restore) — honoring a choice made before the feature existed;
  both SDKs remember the applied value, so it also holds during early startup
  before the state loads, and an opt-out is additionally marked durably at tap
  time in its own device-local store (read at startup before the main state), so
  a crash that loses the main settings write can neither resurrect collection nor
  upload the tail-of-session crash report. Only automatic telemetry ships today (crash traces,
  first-open/screen/session events); the advertising ID is stripped and ad
  personalization disabled. Candidate future signals: SIM name × destination
  country routing counts and call completion/failure rates — never dialed numbers.
  `docs/PRIVACY.md` and the Play data safety form must describe the collection in
  any release built with Firebase enabled.
- **Onboarding asks for little and never rushes.** Required rows are
  `READ_PHONE_STATE` then the role — seeing the SIMs before routing between them, the
  user's mental model; `POST_NOTIFICATIONS`, `CALL_PHONE`, and `READ_CONTACTS` are
  optional rows with benefit-led labels. Onboarding never auto-advances the moment the
  required grants land (the permission dialogs themselves trigger resumes) — an
  explicit exit is the only one: Skip leaves with whatever is granted, and Continue
  (disabled until every optional grant and the analytics opt-in are on) is the
  affirmative finish — so the optional rows get their moment.
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
  `(placed call, snapshot) → verdict`, and the data-rule evaluation behind the
  roaming watch. Fully unit-tested; this is where all product logic lives.
- **Platform layer**: the `CallRedirectionService`, subscription/phone-account readers,
  subscription-change listener, role/permission plumbing. Thin adapters over the domain,
  kept too small to hide logic.
- **UI layer**: onboarding (role + permissions + default region), rules list + editor,
  settings (home of the SIM registry), and the chooser activity. State via ViewModel + StateFlow; persistence
  via DataStore. Screenshot tests (Robolectric + Roborazzi) cover each screen
  state, per the CI conventions in `AGENTS.md`.

Snapshot lifecycle: rules and registry load from DataStore into memory at process start
and stay subscribed; subscription/account state refreshes on change broadcasts. The
redirection service only ever reads the snapshot.

## Testing strategy

- **Unit tests** carry the product logic: rule evaluation (calling and data),
  country parsing edge cases (national format, short codes, undetermined), SIM
  re-binding, loop-guard tokens, non-interactive degradation. The decision function
  is a pure function and is tested as a table.
- **Screenshot tests** cover each screen and state (empty rules, chooser, enable mode).
- **Device QA is irreducible for telecom behavior**: emulators have no real SIMs, no
  eSIM profiles, and CI has no emulator at all. A manual test matrix (documented in
  `docs/qa-matrix.md` as flows land) covers: dual-eSIM Pixel, rule hit/miss, disabled
  SIM enable round-trip, Bluetooth-placed call, hand-off to Google Voice, emergency
  numbers untouched, and the roaming-arrival warning (airplane-mode cycle, land
  border).

## Non-goals (for now)

- Incoming calls (choosing which SIM *receives* is a carrier/platform matter).
- SMS/MMS routing.
- Being a dialer or in-call UI; Simmo never draws in-call.
- Per-contact or time-based rules (country-only until a real need shows up).
- Cost/tariff awareness; Simmo routes and warns by rule and state (roaming or not,
  local or not), it does not estimate prices.

## Open questions

- Ask-flow ergonomics: cancel-then-re-place shows the dialer's call UI twice in quick
  succession on some OEM dialers; measure how disruptive this is on Pixel and whether
  the platform's confirm-first redirect variant (a system-drawn confirmation) is a
  better Ask UX than a custom chooser for the simple two-SIM case.
- Whether carrier names are stable enough across MVNO/roaming rebrands for the
  re-binding fallback, or whether the registry should also pin country + number prefix
  of the profile. (The registry now records last-known country and own number for
  display on the SIMs screen; whether they join the re-binding identity ladder is the
  open part.)
- Whether to offer per-rule "and remember for this number" overrides when a user
  diverges from a country rule for one number repeatedly.
- Whether the trailing "no change" default should split into two distinct entries —
  explicitly routing to the default calling app vs. the default voice SIM — rather
  than one non-intervention rule.
- Hands-free safeguards: whether car-mode signals should supplement the per-call
  interactive-UI flag as the call guard's "driving" signal. (Upstream fixability of
  the wrong-number choice is settled — see "Hands-free and Android Auto safeguards"
  and `docs/upstream-wrong-number.md`.)
