# TODO

Phased plan toward the product in `SPEC.md`. Each phase should land as its own PR (or
small stack), fully unit-tested, with `./gradlew test` and `./gradlew lint` green.

## Phase 0 — Repo scaffold (this PR)

- [x] Gradle + AGP + Compose skeleton (`:app`, `app.simmo`), buildable in CI.
- [x] CI workflow: build, unit tests (with failure comments on PRs), lint.
- [x] Claude Code web session hook to provision the Android SDK.
- [x] `SPEC.md`, `TODO.md`, `AGENTS.md`.

## Phase 1 — Domain: rules, SIM identity, country detection

- [x] Add libphonenumber; `DialedNumber` parser → `CountryVerdict` (region / undetermined /
      emergency-flagged), covering international format, national format against a default
      region, short codes, USSD.
- [x] Tease apart the +1 NANP countries (US, Canada, Caribbean). libphonenumber resolves
      region by area code (`getRegionCodeForNumber`) — verify its coverage with a test
      table of US/CA/Caribbean area codes; where it returns undetermined, evaluate a
      supplementary hardcoded area-code map and, later, the contact's country details as
      tiebreakers.
- [x] `SimIdentity` model + registry re-binding logic (subscription ID primary, carrier +
      display name fallback; ambiguity → unresolved, per SPEC "SIM identity").
- [x] Rule model (country → action; single fallback rule) and the pure decision function
      `(call, snapshot) → verdict` with table-driven unit tests: rule hit, fallback, already-
      on-target pass-through, inactive-SIM target, non-interactive degradation, pass-token
      consumption.
- [x] DataStore persistence for rules + SIM registry (typed serializer via
      kotlinx-serialization JSON — same guarantees as Proto without the codegen);
      state holder that loads off the main thread and stays subscribed.
- [x] Decide backup policy before persistence lands (flagged by Codex on PR #1):
      decided — backups ON, scoped by explicit extraction rules to Simmo's own state
      files; recorded in SPEC "Permissions and privacy".

## Phase 1b — Ordered rules redesign (maintainer direction)

- [x] Rework the rule model: ordered list, first applicable rule wins, matcher
      (country / any destination) + action; skip semantics for disabled SIMs,
      unreachable hand-off targets, UI-needing actions in non-interactive contexts,
      and ambiguous matches.
- [x] Preseeded default rules: any destination → SIM with matching home country
      (unique match only); any destination → no change (system default).
- [x] Chooser payload carries the disabled-SIM rules skipped during evaluation, so
      the enable-assist surfaces there instead of blocking rule hits.
- [x] Prompt to add rules when a new SIM is first seen; suggest placement above
      rules that reference disabled SIMs (UI landed with Phase 3).

## Phase 2 — Telecom integration

- [x] `CallRedirectionService` implementation wired to the decision function; manifest
      service declaration with `BIND_CALL_REDIRECTION_SERVICE`.
- [x] Response watchdog: a missed deadline makes Telecom cancel the call, so the service
      must send an explicit response (worst case `placeCallUnmodified`) well before the
      platform's 5 s timeout on every path — including cold start and exceptions in
      decision code (flagged by Codex on PR #1).
- [x] Onboarding: request `ROLE_CALL_REDIRECTION` via `RoleManager` and
      `READ_PHONE_STATE` (default-region override UI comes with Phase 3 settings);
      readers degrade to empty state while grants are missing.
- [x] Subscription + phone-account snapshot readers and change listeners; SIM registry
      capture of newly seen subscriptions, including each SIM's home country
      (`SubscriptionInfo.countryIso`) for the matching-country default rule.
- [x] Re-place path: `TelecomManager.placeCall` with chosen account, pass-token loop
      guard (`PassTokenStore` landed with the service), `CALL_PHONE` request on first
      use — landed with the Phase 3 chooser, which is what re-places calls.
- [ ] Verify on device: 5s deadline comfortably met from cold process; document measured
      cold-decision time. Confirm emergency numbers never reach the service.

## Phase 3 — UI

- [x] Rules list as the home screen once grants are held: ordered rules with country +
      action labels, preseeded defaults visible, disabled-SIM rules greyed out.
- [x] Rule editor: add via the list's button, edit by tapping a row, delete; country
      picker (any destination or a specific country) and action picker (specific SIM
      from the registry, matching-country SIM, Ask, no change). Hand-off actions wait
      for Phase 5's reachable-app discovery.
- [x] Country picker search: a searchable full-screen subpage (reached from the editor's
      country row) that fuzzy-matches by name, dial code, ISO alpha-2/alpha-3, and aliases
      (UK/USA/America), ranked exact/prefix-first with capitals-match-capitals acronym
      matching.
- [ ] Suggested countries at the top of the picker: surface the countries drawn from the
      user's contacts' numbers (and/or recent calls) as a "Suggested" bucket above the
      full list, so the common destinations are one tap away. Needs `READ_CONTACTS` and a
      cheap country-of-number rollup off the main thread.
- [x] Multiple countries per rule: let one rule match a set of countries rather than a
      single one. The "A specific country" radio becomes a "+ Add a country or code"
      affordance that adds each picked country to the rule, shown as removable ✕ entries;
      the matcher model changes from `RuleMatcher.Country(regionCode)` to a set of regions
      and the decision engine matches when the destination is in the set (SPEC update, and
      keep emergency/undetermined-country handling unchanged).
- [x] Country groups: one "EU/EEA" entry per rule (maintainer decision: a
      first-class group matcher, not expansion — expansion was judged too
      cluttered). Membership = EU-27 + EEA EFTA + EU territories with their own
      codes + Svalbard, resolved at decision time so it tracks app updates; UK,
      Switzerland, and the EU-roaming-area candidates (UA/MD, Western Balkans)
      stay out of the label-faithful set and are added per-rule when a plan
      covers them. Picker surfaces groups on "EU"/"EEA"/"Europe"/"European
      Union" and on any member-country search. Revisit membership at least
      yearly (last reviewed 2026-07; Montenegro/Albania may join the EU roaming
      area soon — a group is only added/changed when the *label* set changes).
- [x] NANP groups: "USA + territories" (US + PR/VI/GU/AS/MP; territory inclusion
      is explicit in the name because some prepaid tiers bill the Pacific
      territories internationally — flagged by Codex — and states-only remains
      the plain "United States" country entry), "North America" (that group +
      Canada + Mexico, the postpaid plan tier; separate because many
      prepaid/MVNO tiers are domestic-only), and "Caribbean +1" (the 18 non-US
      NANP countries that dial like domestic calls but bill internationally),
      enabling the guard rule "Caribbean +1 → Ask" placed above a USA rule.
- [ ] Custom country groups: user-defined groups ("My plan's included countries")
      created in the picker and referenced from rules like the built-ins — the
      answer to carrier "Tier 1"/zone lists, which differ per carrier (and often
      split landline vs. mobile) so no shipped list can be right. Also the home
      for carrier-footprint sets (Vodafone/Three/T-Mobile markets) rejected as
      built-ins. Membership must resolve from the warm snapshot on the decision
      path, same as built-in groups resolve from the static table.
- [ ] Per-contact rule overrides: consider letting a rule (or a quick action) target a
      specific contact — e.g. "always call Mum on Telstra" — layered above the
      country rules. Decide where it lives in the ordered rule model and how it reads on
      the decision path from the warm snapshot.
- [x] Drag to reorder the rule list (handle-initiated drag over a working copy;
      single domain commit on drop). Auto-scroll while dragging near the list edges
      is still pending — long lists need a device check anyway.
- [x] New-SIM prompt: nudge to create rules for a newly seen SIM, inserted above rules
      referencing disabled SIMs.
- [x] Chooser activity (Ask flow): number + detected country, targets, "remember for
      <country>", cancel. Re-places on confirm. Ask is offered in the rule editor
      again. Device QA still owed: background-activity-launch rules may block the
      service's startActivity on some Android versions (fallback would be a
      full-screen-intent notification), and the double call-UI flash from
      cancel-then-re-place needs measuring (SPEC open question).
- [x] SIM registry screen: every registered SIM with active/last-seen state,
      reached from the rules list; stale entries can be deleted (active ones
      can't — the next telephony refresh would just re-register them).
- [ ] Rename SIMs on the registry screen (maintainer: deferred). Must be a
      *nickname* layered over the stored identity, never an edit of
      displayName — carrier + display name is the re-binding ladder (SPEC "SIM
      identity"), so a rename that touched it would break rule re-binding. The
      nickname then needs surfacing everywhere SIMs are named: rules list
      action labels, editor SIM options, chooser targets, new-SIM prompt.
- [x] Screenshot tests (Robolectric + Roborazzi) wired into CI with the explicit
      `--tests` allow-list pattern from Type Launcher (rules list + onboarding landed;
      each new screen adds its class and CI step).

## Phase 4 — Disabled-SIM assist

- [x] Chooser "enable" jump for inactive-SIM rule hits: a "SIM settings" button under
      the skipped-SIM note deep-links to `EuiccManager.ACTION_MANAGE_EMBEDDED_SUBSCRIPTIONS`
      (generic wireless-settings fallback), and the chooser's targets/notes rebuild live
      from the SIM flow so the re-enabled SIM's call button appears on return. Direct
      in-app toggling is impossible without carrier privileges (SPEC "Enabling SIMs is
      Settings' job"). Still to verify on device: the best deep link per Android version.
- [ ] Subscription-change watcher + held-call notification ("Telstra is now active —
      place the call?"); `POST_NOTIFICATIONS` request.
- [ ] Helper notification on SIM changes (maintainer request): when the active
      SIMs change — especially when a SIM Simmo has never seen becomes active —
      post a notification ("New SIM: Optus travel. Add a rule for it?") that
      deep-links to the rules list / new-SIM prompt. Today the nudge is only the
      in-app card, so it waits until the user next opens Simmo; the notification
      matters extra because installed-but-never-activated eSIMs are invisible to
      apps, making first activation the one moment Simmo can catch a new SIM.
      Rides the same `POST_NOTIFICATIONS` plumbing as the held-call item.
- [ ] MEP behavior pass on a dual-eSIM Pixel; document what profile-swap looks like from
      Simmo's point of view in `docs/qa-matrix.md`.

## Phase 5 — Hand-off to other apps

Per-app launch intents, what's in/out of scope, and the on-device checklist live in
`docs/handoff-intents.md`. Grounded finding: the popular targets don't register a
call-capable phone account (Google Voice included), so the MVP is **cancel-and-forward**
to **Google Voice, Microsoft Teams, and Viber**; app-to-app apps (WhatsApp, Signal, …)
can't dial an arbitrary number and are out of scope.

- [ ] Verify the MVP intents on a real device (the `docs/handoff-intents.md` checklist)
      before building — auto-dial vs pre-fill vs browser is unconfirmed in the sandbox.
- [ ] Consider WhatsApp (and other app-to-app apps) hand-off: when the dialed number
      belongs to a contact reachable on the app, launch its by-contact call intent (e.g.
      WhatsApp's `vnd.com.whatsapp.voip.call` contacts-data row). Needs `READ_CONTACTS` +
      a number→contact reverse lookup (shareable with the same-contact-number-correction
      index) and is only offered when the number is actually a user of that app. Many
      users' contacts are on WhatsApp, so it's worth having despite the contact-only limit.
  - [x] Number→contact reverse-lookup foundation: `ContactNumberIndex` (dialed number →
        contact + per-app `ContactsContract.Data` call-action row ids, keyed by E.164) and
        the `ContactsReader` that builds it off the main thread, degrading to empty without
        `READ_CONTACTS`. Pure + Robolectric tested. Still to wire: request `READ_CONTACTS`,
        keep the index warm in the snapshot, and the WhatsApp launch (device-verified).
- [ ] Reachable-app discovery off the decision path: resolve each candidate intent and
      cache the vetted template + mechanism label in the warm snapshot (`handOffApps`).
- [ ] Cancel-and-forward action: rule editor offers only reachable apps with honest copy
      ("opens <app>"), and the service cancels **only** when the intent resolves — else
      proceeds unmodified, never stranding the call.
- [ ] Phone-account redirect: keep for any VoIP app that does register a Telecom account,
      but no MVP target needs it.
- [ ] Google Voice verified end-to-end as the reference target.

## Phase 6 — Hands-free and Android Auto safeguards

Goal: no accidental international calls when hands-free (see SPEC "Hands-free and
Android Auto safeguards").

- [ ] Investigate the upstream wrong-number problem: when/why Android and Android Auto
      pick a contact's overseas number over their local one, and whether anything can
      steer the choice before the call is placed (contact primary/default number,
      per-account number ordering). Document findings; this may be unfixable upstream.
- [ ] Same-contact number correction: optional `READ_CONTACTS` reverse-lookup index in
      the warm snapshot; when a dialed overseas number belongs to a contact with a
      local-region number, redirect to the local number (unambiguous mappings only in
      non-interactive contexts; confirm via chooser otherwise).
- [ ] Opt-in hands-free call guard: in non-interactive contexts, block overseas calls
      and/or calls whose rule needs a disabled SIM; cancel + notification with one-tap
      redial through the chooser. Decide the "driving" signal (per-call interactive-UI
      flag vs. car-mode signals) per the SPEC open question.
- [ ] Device QA on Android Auto: verify the redirection service is consulted for
      Auto-placed calls, what the interactive-UI flag reports there, and that guard
      notifications are readable post-drive.

## Phase 7 — Quick Settings tile

- [ ] `TileService` tile(s); decide shape per SPEC open question: shortcut to Simmo's
      rules, quick switch of data / calling SIM (for a non-privileged app: deep link
      into the system SIM screens; check what `TelephonyManager` /
      `SubscriptionManager` allow for default-voice/data selection per Android
      version), or both as separate tiles.

## Phase 8 — Release plumbing

- [x] CI screenshot job (mirroring Type Launcher's `connected-tests` job: per-class
      `--tests` record steps, snapshot refresh auto-commit, screenshot-diff PR
      comment).
- [ ] versionCode derived from git commit count; report versionCode after merges, per
      Type Launcher's convention.
- [ ] Debug keystore secret so CI builds install as updates on tester devices.
- [ ] Firebase App Distribution job for tester builds.
- [ ] Release keystore + Play internal track upload; one-time manual seed upload of
      the signed AAB.
- [ ] Release-notes-from-commit-subjects pipeline (`whatsnew-en-US`, 500-char cap,
      non-user-facing prefixes skipped) — adopt from typelauncher's deploy job.
- [ ] Play listing assets: store screenshots (device frames of rules list, chooser,
      enable flow), feature graphic, short/full description.
- [ ] Privacy policy (`PRIVACY.md`) — the no-INTERNET-permission commitment makes this
      short. Host it and link from Play + an in-app About surface.
- [ ] Translations pass per the two-PR rule in `AGENTS.md` once English copy settles.

## Deferred / open (see also SPEC "Open questions")

- [ ] Evaluate confirm-first system redirect UX vs. custom chooser for the simple case.
- [ ] Carrier-name stability for re-binding (roaming/MVNO rebrand data needed).
- [ ] Per-number overrides.
- [ ] Bump compileSdk/targetSdk to Android 17 (API 37) once the CI SDK provisioning
      hook seeds that platform.
