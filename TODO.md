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
  - [x] Rows are benefit-led and ordered by the user's mental model ("See your
        SIMs" then "Call using the right SIM"; optional "Show errors and
        shortcuts", "Retry failed calls", "Call your contacts"), the screen
        scrolls so Continue stays reachable at large font scales, and onboarding no
        longer auto-advances when the required grants land — Skip (left) leaves
        with whatever is granted and Continue (right, disabled until every grant
        and the analytics opt-in are on) is the affirmative finish, so the
        optional rows get their moment.
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
- [x] Per-rule actions menu (its ⋮ button or a long-press on the row): edit, duplicate
      (copy inserted directly below), enable/disable, delete. Disabling adds a user
      toggle to the rule model (`Rule.enabled`, defaults true so existing state reads as
      on) that the engine skips over — kept in place and greyed with its own "Disabled"
      label, distinct from the automatic SIM-pause skips. Reorder stays on the drag
      handle, so long-press never contends with it.
- [x] Country picker search: a searchable full-screen subpage (reached from the editor's
      country row) that fuzzy-matches by name, dial code, ISO alpha-2/alpha-3, and aliases
      (UK/USA/America), ranked exact/prefix-first with capitals-match-capitals acronym
      matching.
- [x] Suggested countries at the top of the picker: a "Suggested" bucket (blank query
      only) surfaces the countries the user's contacts have numbers in, the ones with the
      most contacts first (counted per contact, not per number), above the full list so
      common destinations are one tap away. Contacts only
      (no call-log permission); the country-of-number rollup runs off the main thread
      from the warm contact index and re-derives when it refreshes. Empty without
      `READ_CONTACTS`.
  - [x] Contacts-permission affordance in the picker: when `READ_CONTACTS` isn't granted, a
        blank query shows a tappable "Suggest countries from your contacts" row (in the
        bucket's place) that requests the permission and, on grant, refreshes the warm
        index so the bucket fills — the same grant/index the WhatsApp hand-off uses. Makes
        the feature discoverable without first picking a contact-app hand-off.
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
- [x] Custom country groups: user-defined groups ("Vodafone Zone 1") created and
      managed on a Country groups screen (reached from the rules list) and referenced
      from rules like the built-ins — the answer to carrier "Tier 1"/zone lists, which
      differ per carrier so no shipped list can be right. Persisted as
      `SimmoState.customGroups`; membership resolves from the warm snapshot on the
      decision path, the same as built-in groups resolve from the static table (the
      two id spaces are disjoint). Custom groups are selectable in the rule editor's
      country picker; a deleted group leaves referencing rules matching none of it.
  - [x] Create a group from within the picker: a "New group" row in the rule editor's
        country picker (blank query) opens the same group editor as a nested sub-step,
        so a group can be built mid-rule; on save it's created and added to the rule
        being scoped, without losing the editor's draft.
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
- [x] SIMs screen rows show each SIM's own number and country (last-known,
      captured into the registry while the SIM was active; the number needs
      optional `READ_PHONE_NUMBERS`, requested silently on that screen).
- [x] Settings screen behind the rules list's gear: the home for app-level
      options (the default-region override is the next candidate), with the
      SIM registry reached through it instead of directly from the gear.
- [x] Call feedback settings (SPEC "Call feedback and delay"): an optional
      "Calling using <SIM or app>" toast when a rule routes a call
      (maintainer: hand-offs announce the app name too), and an optional
      1–10 s cancelable countdown screen before a rule-picked redirect is
      placed (cancel-and-re-place with a pass token; never delays the Telecom
      response; skipped in non-interactive contexts). Device QA owed: the
      toast from the redirection service on Android 12+ (shared with the
      hand-off failure toast item), and background-activity-launch rules for
      the countdown activity (shared with the chooser's BAL item).
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
- [x] Subscription-change watcher + held-call notification ("Telstra is now active —
      place your call?"): the chooser parks the call when a rule's wanted SIM is
      disabled; when that SIM activates, a notification reopens the chooser for it
      (never auto-placed; in-memory, 15-minute expiry, dropped once any call is
      placed, and never offered on an ambiguous re-binding). `POST_NOTIFICATIONS`
      is an optional onboarding row plus a contextual ask at the chooser's SIM
      settings jump. Device QA owed: end-to-end enable → notification → re-place
      round trip on a real dual-SIM device.
- [x] Helper notification on SIM changes (maintainer request): "New SIM: Optus
      travel. Add a rule…" posts once per SIM (persisted flag, so refreshes never
      re-nag), only while the SIM is active, deep-linking to the rules list's
      card. The first-ever capture (fresh install) is suppressed — no nagging
      about SIMs the user already had. Matters because installed-but-inactive
      eSIMs are invisible to apps: first activation is the one catchable moment.
- [ ] MEP behavior pass on a dual-eSIM Pixel; document what profile-swap looks like from
      Simmo's point of view in `docs/qa-matrix.md`.

## Phase 5 — Hand-off to other apps

Per-app launch intents, what's in/out of scope, and the on-device checklist live in
`docs/handoff-intents.md`. Grounded finding: the popular targets don't register a
call-capable phone account (Google Voice included), so the MVP is **cancel-and-forward**
to **Google Voice, Microsoft Teams, Viber, and Yolla**; app-to-app apps (WhatsApp, Signal, …)
can't dial an arbitrary number and are out of scope.

- [ ] Verify the MVP intents on a real device (the `docs/handoff-intents.md` checklist)
      before building — auto-dial vs pre-fill vs browser is unconfirmed in the sandbox.
- [x] WhatsApp (app-to-app) hand-off, end to end: a "Hand off to WhatsApp" rule action
      (`RuleAction.HandOff.ViaContactApp`) that, when the dialed number is a WhatsApp
      contact, cancels the carrier call and `ACTION_VIEW`s the contact's voip Data row
      (resolve-before-cancel so a missing target never strands the call); it skips
      otherwise and in non-interactive contexts. The warm `ContactNumberIndex` is wired
      into the snapshot, `READ_CONTACTS` is declared + requested when the action is
      chosen, and the editor offers WhatsApp only when it's installed (`<queries>` +
      PackageManager). Domain/decision/editor unit-tested.
  - [x] Number→contact reverse-lookup foundation: `ContactNumberIndex` + `ContactsReader`,
        pure + Robolectric tested.
  - [ ] **Device QA owed** (docs/handoff-intents.md checklist): confirm `ACTION_VIEW` on
        the voip Data row actually places a WhatsApp call, the exact `DATA1` column, the
        JID handling, and that background-activity-launch rules don't swallow the
        `startActivity` from the redirection service.
  - [ ] Extend to other app-to-app apps if/when a comparable by-contact call intent is
        found (Signal/Telegram had none last checked — see docs/handoff-intents.md).
- [x] Cancel-and-forward hand-off to Google Voice, Microsoft Teams, Viber, and Yolla, end to
      end: a "<app>" rule action (`RuleAction.HandOff.ViaDialIntent(DialHandoffApp)`)
      that normalizes the dialed number to E.164 off the fast path and launches the app's
      number-carrying deep link, resolving the intent **before** cancelling so an
      unreachable target proceeds unmodified. Interactive-only; a non-E.164 number (short
      code) skips the rule. The snapshot's `handOffApps` caches installed targets (off the
      decision path), the editor offers each only when its launch intent resolves (`<queries>` +
      PackageManager), and the deep-link templates are unit-tested.
  - [ ] **Device QA owed** (docs/handoff-intents.md checklist): confirm the Google Voice,
        Teams, and Viber deep links open the app (not a browser) at the number, auto-dial
        vs pre-fill, and the installed-but-unprovisioned (no linked number / no Teams
        Phone plan / no Viber Out or Yolla credit) behavior; that Yolla receives the
        generic `tel:` launch at all (no public deep link — if it doesn't resolve, a
        probed custom scheme is the follow-up); and that background-activity-launch
        rules don't swallow the `startActivity` from the redirection service.
  - [x] Surface hand-off launch failure: the service resolves the target **before**
        responding, so an unreachable target (uninstalled, deep link unhandled) proceeds
        unmodified and posts a "Couldn't open <app>" notification — the common case, never
        stranded. Otherwise it responds `cancelCall()` **first** (the hard deadline rules
        out gating the response on the launch), then launches; a launch that then throws
        can't be un-cancelled, so it surfaces the failure with a "Couldn't open <app>"
        notification (**Settings** to fix the app, **Redial** to retry) and, permission-free,
        drops the user in the dialer with the number. That recovery dialer mints a
        wildcard pass token so the redial proceeds on the carrier instead of re-entering
        the still-failing rule and looping. Applies to the dial-intent and WhatsApp
        hand-offs. (The undetectable case — the app opens to a setup screen because it
        isn't provisioned — still can't be surfaced reactively; a proactive editor hint
        is the follow-up.)
  - [x] Notifications-off coverage for the failure notice: picking a hand-off action
        in the editor asks for `POST_NOTIFICATIONS` once (WhatsApp chains it after the
        contacts ask — two permission dialogs can't be in flight together), a "Let
        Simmo tell you if there's a <app> problem" hint with an Allow button sits
        under the selected action while notifications are off (falling back to the
        app's notification settings when the request dialog can't show), and a
        notice that can't post (permission denied, notifications off, or the
        sim_assist channel blocked) degrades to a plain text toast — a failed
        hand-off is never silent.
  - [ ] Device QA owed for the toast fallback: confirm a plain text toast from the
        redirection service actually shows on Android 12+ (background *custom* toasts
        are blocked; text toasts should pass) — it is the notifications-off surfacing
        of the failure notice.
  - [ ] Proactive readiness hint in the editor when a hand-off app is picked ("requires
        <app> set up to place calls") — the only honest coverage of the undetectable
        installed-but-unprovisioned case. Natural home: the same under-action hint slot
        the notifications hint uses.
  - [ ] Background-activity-launch (BAL): a silently blocked `startActivity` from the
        service returns without throwing, so the cancel-first path can't detect it — the
        carrier call is already cancelled and both the app launch and the permission-free
        recovery dialer are blocked, stranding the call. Device-QA whether the redirection
        binding exempts us from BAL; if not, launch the hand-off via a full-screen-intent
        notification (the sanctioned background-launch path) — see docs/qa-matrix.md.
- [ ] Reachable-app discovery beyond "installed": resolve each candidate intent + detect
      readiness (linked number / calling plan) where possible, off the decision path.
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

- [x] `TileService` tile; shape decided (SPEC "Quick Settings tile"): a shortcut into
      Simmo's SIMs screen, which links on to the system SIM settings. A switch-SIM
      toggle is out: default-voice/data selection (`SubscriptionManager`
      `setDefaultDataSubId` and friends) needs `MODIFY_PHONE_STATE` on every current
      Android version.
- [ ] Device QA: tile tap from a locked device (activity should wait for unlock), and
      tile tap while the rule editor is open (editor closes, registry shows).

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
- [x] Play listing text: app title + short/full description, kept as fastlane
      metadata (`fastlane/metadata/android/en-US/`) so the future upload pipeline
      can push listing updates.
- [ ] Play listing graphics: store screenshots (device frames of rules list,
      chooser, enable flow), 512×512 icon, feature graphic.
- [x] Privacy policy (`docs/PRIVACY.md`) — short thanks to the no-INTERNET-permission
      commitment; served by stock branch-based GitHub Pages (Jekyll) at
      https://mikelward.github.io/simmo/PRIVACY.html once Pages is enabled.
- [x] Enable GitHub Pages: Settings → Pages → Deploy from a branch → `main`,
      `/docs` folder (done by the maintainer in the GitHub UI, 2026-07-17).
- [ ] Link the privacy policy from the Play listing (App content → Privacy policy)
      and an in-app About surface.
- [ ] Translations pass per the two-PR rule in `AGENTS.md` once English copy settles.

## Deferred / open (see also SPEC "Open questions")

- [ ] Permission-health surface on the rules list: one status banner when Simmo can't
      do its job or can't say why — redirection role lost to another app,
      `READ_PHONE_STATE` revoked, notifications off while hand-off rules exist. A
      single surface, not per-permission nags; the editor's hand-off hint covers the
      editing moment only, and a role/phone-state loss currently just drops the user
      back to onboarding with no explanation on the rules list itself.
- [x] Decide on crash reporting (Crashlytics) and usage analytics — decided and
      landed per SPEC "Permissions and privacy": Firebase Crashlytics + Analytics
      compiled in, dormant without a `google-services.json` at build time
      (`SETUP.md`), collection gated on the "Make Simmo better" opt-in. Only
      automatic telemetry so far; the candidate custom signals (SIM name ×
      destination country routing counts, call completion/failure rates — never
      contact names/numbers or dialed numbers) are still open, below.
- [ ] Custom analytics signals (SIM name × destination country routing counts,
      call completion/failure rates) — decide and implement off the decision path;
      nothing beyond Firebase's automatic events is logged today.
- [ ] Fill in the Play data safety form for crash reporting/analytics before the
      first Play release built with Firebase enabled.
- [ ] A post-onboarding surface to change the "Make Simmo better" opt-in — today
      the toggle exists only on the onboarding screen, so a user who finished
      setup has no in-app way to change their mind (Codex on PR #37). **Blocks
      the first release built with Firebase enabled**, same as the data safety
      form above: the privacy policy says the switch controls collection, so a
      shipped build must keep the switch reachable. Natural home: the Settings
      screen that landed with the calling options (a row there, next to the
      privacy-policy link Phase 8 owes).
- [ ] Evaluate confirm-first system redirect UX vs. custom chooser for the simple case.
- [ ] Carrier-name stability for re-binding (roaming/MVNO rebrand data needed).
- [ ] Per-number overrides.
- [ ] Bump compileSdk/targetSdk to Android 17 (API 37) once the CI SDK provisioning
      hook seeds that platform.
