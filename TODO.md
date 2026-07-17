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
- [ ] Prompt to add rules when a new SIM is first seen; suggest placement above
      rules that reference disabled SIMs (UI lands with Phase 3).

## Phase 2 — Telecom integration

- [ ] `CallRedirectionService` implementation wired to the decision function; manifest
      service declaration with `BIND_CALL_REDIRECTION_SERVICE`.
- [ ] Response watchdog: a missed deadline makes Telecom cancel the call, so the service
      must send an explicit response (worst case `placeCallUnmodified`) well before the
      platform's 5 s timeout on every path — including cold start and exceptions in
      decision code (flagged by Codex on PR #1).
- [ ] Onboarding: request `ROLE_CALL_REDIRECTION` via `RoleManager`, `READ_PHONE_STATE`,
      default-region setup; degraded states when role/permission is missing.
- [ ] Subscription + phone-account snapshot readers and change listeners; SIM registry
      capture of newly seen subscriptions, including each SIM's home country
      (`SubscriptionInfo.countryIso`) for the matching-country default rule.
- [ ] Re-place path: `TelecomManager.placeCall` with chosen account, pass-token loop
      guard, `CALL_PHONE` request on first use.
- [ ] Verify on device: 5s deadline comfortably met from cold process; document measured
      cold-decision time. Confirm emergency numbers never reach the service.

## Phase 3 — UI

- [ ] Rules list + rule editor (country picker, action picker limited to registered SIMs
      and reachable hand-off apps); drag to reorder; rules for disabled SIMs greyed out.
- [ ] New-SIM prompt: nudge to create rules for a newly seen SIM, inserted above rules
      referencing disabled SIMs.
- [ ] Chooser activity (Ask flow): number + detected country, targets, "remember for
      <country>", cancel. Re-places on confirm.
- [ ] SIM registry screen (rename, delete, last-seen).
- [ ] Screenshot tests (Robolectric + Roborazzi) per screen state, wired into CI with the
      explicit `--tests` allow-list pattern from Type Launcher (add the screenshot job to
      `.github/workflows/android-ci.yml` when the first screenshot test lands).

## Phase 4 — Disabled-SIM assist

- [ ] Chooser "enable" mode for inactive-SIM rule hits; deep link to
      `EuiccManager.ACTION_MANAGE_EMBEDDED_SUBSCRIPTIONS` with generic SIM-settings
      fallback (verify the best deep link per Android version on device).
- [ ] Subscription-change watcher + held-call notification ("Telstra is now active —
      place the call?"); `POST_NOTIFICATIONS` request.
- [ ] MEP behavior pass on a dual-eSIM Pixel; document what profile-swap looks like from
      Simmo's point of view in `docs/qa-matrix.md`.

## Phase 5 — Hand-off to other apps

- [ ] Detect call-capable phone accounts from third-party apps; phone-account redirect
      action end-to-end.
- [ ] Cancel-and-forward fallback (dial intent) for apps without a phone account; rule
      editor only offers reachable apps and labels the mechanism (per SPEC).
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

- [ ] CI screenshot job (mirroring Type Launcher's `connected-tests` job: per-class
      `--tests` record steps, snapshot refresh auto-commit, screenshot-diff PR
      comment) — lands with or after the first Phase 3 screenshot test.
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
