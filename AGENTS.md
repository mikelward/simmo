# Simmo

Android app that picks the right SIM (or calling app) for every outgoing call via
per-country rules (Kotlin, Compose, single `:app` module). Product and architecture
decisions live in `SPEC.md`; the phased plan lives in `TODO.md`. This repo mirrors the
engineering conventions of the sibling Type Launcher repo (`mikelward/typelauncher`);
when a convention is underspecified here, that repo's `AGENTS.md` is the tiebreaker.

## Project documentation

- Keep `SPEC.md` up to date when changing product behavior, architecture, persistence,
  permissions, navigation, or testing strategy.
- **`SPEC.md` records product, functionality, and architecture decisions — not low-level
  implementation detail.** It captures *what* Simmo does and *why* a design was chosen
  (the rule/action model, the SIM identity + re-binding scheme, the decision deadline
  and snapshot design), so a reader can understand and QA the product from the spec.
  Ask "would this still be true and worth stating if the implementation were
  rewritten?" — if not, leave it in the code and its comments.
- Keep `TODO.md` current: check items off as they land, add newly discovered work to the
  right phase.

## Engineering quality bar

Every change must hold the line on **correctness, a fast decision path, and jank-free
UI**:

- **Correctness**: the change matches `SPEC.md` and the user's stated intent, handles
  the obvious edge cases (no rules, one SIM, missing role/permission, undetermined
  country, process death, configuration change), and preserves existing invariants. Two
  stay hard: **emergency calls are never touched**, and the service **never misses the
  Telecom deadline** — a timeout silently drops the call (see *Fast decision path*).
  Beyond those, **avoid stranding a call** — degrade to "proceed unmodified" wherever you
  can, and prefer to skip a rule over cancelling into nothing. But zero-strand is *not* an
  absolute that blocks shipping useful options: a best-effort action that can occasionally
  leave the user without a placed call (e.g. handing off to an app that turns out to be
  unprovisioned) is acceptable **when it genuinely tries to avoid stranding and surfaces
  the failure to the user** rather than failing silently. Don't withhold a useful option
  to guarantee a strand can never happen; do make the avoidance real and the failure
  visible. New behavior is covered by a unit test; when fixing a bug, add a test that
  fails before the fix and passes after.
- **Fast decision path**: the platform gives the call-redirection service a hard ~5 s
  deadline, and a missed deadline means **Telecom cancels the call** — a timeout drops
  the user's call. So the service must always respond explicitly (worst case "proceed
  unmodified") on every path including cold start and errors, and must answer from the
  in-memory snapshot **without blocking**: the code between `onPlaceCall` and `respond()`
  does no *synchronous* disk, `PackageManager`/telephony IPC, parser-table load, or
  main-thread work — the response must never *wait* on any of that. **Background work off
  the decision coroutine does not count against the deadline and is fine** — e.g. logging
  may write to disk asynchronously on a worker thread; what matters is that `respond()`
  isn't held up. (The realistic risk is a *blocking* op stalling the ~5 s window, not a
  fire-and-forget one; a single async append is sub-millisecond and never on the path.)
  Any change touching the service or snapshot must state (in the PR) what the decision
  path now reads and why it's already in memory. Degrade to "proceed unmodified," never
  to a blocked call.
- **Jank-free UI**: the chooser appears mid-call-attempt — it must render its first
  frame instantly from snapshot state (no loading spinner while a call hangs). Usual
  Compose discipline applies: `remember`/`derivedStateOf`/stable types, no I/O in
  composition.

When you cannot verify one of these locally (no emulator/SIM in the sandbox), say so
explicitly in the chat update — "verified by unit test; telecom behavior needs a device
check" — rather than implying all were checked. Telecom behavior on a real dual-SIM
device is the pillar most often still owed.

## Spacing

Stick to a 4dp grid for every padding/margin/spacing value (`4`, `8`, `12`, `16`, `24`,
...); reuse values already used by sibling composables; symmetry by default, and any
asymmetry gets a one-sentence justification in the PR. Flag off-grid or inconsistent
spacing you notice even outside the diff (file, line, proposed fix) without silently
fixing it in the same commit.

## Git workflow

- Always start work from the latest `origin/main`: `git fetch origin main` and rebase
  the working branch onto it before the first commit, even when the branch already
  exists. Resolve conflicts rather than abandoning the rebase, and never push commits
  on an out-of-date base when a fast-forward rebase onto `origin/main` was possible.
- **Structure the branch as a sequence of logical commits, rebasing and squashing as
  needed.** Each commit is one coherent change (a feature step, a fix, a refactor) that
  stands on its own — buildable and green by itself. The repo rebase-merges, so every
  commit lands on `main` individually with its own subject, blame lines, and bisect
  step. Squash fixups into the commit they amend, split unrelated changes into separate
  commits, and reorder so the history reads as steps toward the change, not the order
  the work happened to occur in.
- Clean up the unmerged commit history before requesting review and again before merge
  (`git rebase -i origin/main`): iteration leaves `fix CI` / `address review` / `wip`
  churn, and nothing squashes it for you — a messy branch ships a messy `main`. After
  rewriting, force-push with `git push --force-with-lease` (never bare `--force`). Ask
  before rewriting commits that have been individually reviewed.

## Commit messages

- Write every subject for end users, sentence case, plain English, no internal symbol
  names, ≤ ~70 characters; engineering detail goes in the body. This repo uses Type
  Launcher's release pipeline: every release-worthy commit subject in a push to `main`
  ships as a bullet in the Firebase and Play "What's new" list (see the `deploy` job).
- Because the repo rebase-merges, the PR title never lands on `main` — each commit's
  own subject does. Title **every** commit on the branch by these rules, not just the
  PR.
- Keep non-user-facing commits out of release notes with a subject prefix, used
  precisely (the prefix is a promise the commit has **no user-visible effect**):
  - `ci:` — CI / workflow plumbing.
  - `docs:` — documentation only (`docs/PRIVACY.md` is the exception — it backs the
    hosted privacy policy, so it is user-facing).
  - `internal:` — build config, dependency upgrades, other plumbing.
  - `refactor:` — behavior-preserving code changes.
  - `test:` / `tests:` — test-only changes.

## Working with PRs

- Use the `mcp__github__*` MCP tools for all GitHub operations; the `gh` CLI is not
  available in the sandbox.
- **Merge when green + Codex thumbs up, then continue.** Once a PR's CI is green and
  Codex has finished its pass with no unaddressed suggestions (its "no suggestions"
  outcome is a 👍 reaction; suggestion threads count as addressed once fixed or
  answered), rebase-merge the PR without waiting for a further go-ahead, then pick up
  the next `TODO.md` item.
- Never leave a review comment thread silently dismissed: reply on the thread or
  resolve it. When a comment is a false positive, say why on the thread.
- Link every open PR in the stack (one URL per line) whenever you push, summarize CI,
  or invite review.
- Refresh the PR title and body on every push so they describe the full, latest state
  of the branch — re-read `git diff origin/main...HEAD` and patch whatever drifted.
- Keep watching merged PRs for late review comments — bots and humans routinely comment
  after merge. Handle each per the reply-or-resolve rule; stop once every post-merge
  comment is handled or after ~24h of silence, not the moment the merge lands.
- Skip echo events silently. Replies posted via `mcp__github__*` come back moments
  later as webhook events authored by the same identity; if the body matches a comment
  you just posted, it's your own echo — continue without comment. Anything you didn't
  just author still gets the usual reply-or-resolve handling.
- On CI failure: check for the failing-tests PR comment first; no comment means the
  failure is earlier than tests (compile, lint, resource merge) — the `*-test-reports`
  artifacts also only contain JUnit XMLs when tests actually ran, so an empty one is
  the same signal. The PR `build` job builds `refs/pull/<N>/merge` — your branch
  *merged with main* — so reproduce with `git merge origin/main --no-commit` before
  bisecting your own commits. Check whether the failure is pre-existing on the base
  commit before debugging.

## Asking questions

- Ask questions as plain chat messages. Claude specifically: never use the
  `AskUserQuestion` tool. Chat keeps the question, its context, and the answer in one
  readable thread.
- After asking, stop and wait for the answer. Don't proceed on an assumed answer, pick
  a "recommended" option yourself, or keep working on the part the question affects.
- Acknowledge every answer explicitly before acting on it, so it's clear the answer was
  received and how it was understood.
- Whenever you change direction — because of an answer, something discovered in the
  code, a failing check, or any other reason — say so immediately in chat: what changed,
  and why. Never let a plan silently drift from what was last stated.

## Language and spelling

Use US English everywhere people read English: user-facing strings, commit subjects and
bodies, PR titles/descriptions, comments, KDoc, identifiers (`color`, `behavior`,
`canceled`, `dialog`). Platform/third-party API spellings stay as the framework spells
them. This is about US-vs-UK spelling, not about adding locales.

## Concise copy

Keep user-facing text short. A label, action, or title should carry only the words the
user needs — drop framing verbs and prefixes the surrounding UI already implies. Under a
"Use" list of call actions, "Google Voice" beats "Hand off to Google Voice"; the section
already says these are what to use. Prefer the shortest phrasing that stays unambiguous;
when a longer form is genuinely needed for clarity, say why in the PR. This applies to
strings, dialog/button text, and screen titles. (It's the same instinct as the ≤70-char
commit-subject rule — say it in fewer words.)

## Translations

English first, translations in a second PR — never the same PR. Propose new English
copy in chat and get explicit approval before translating. New base strings land with a
per-string `tools:ignore="MissingTranslation"` and a `<!-- TODO: translate -->` comment;
the follow-up translation PR fans the approved copy out to every locale and removes
both. Escape apostrophes (`\'`) in any locale's string resources.

## Remote build environments (Cursor Cloud and Claude Code on the web)

- **JDK 21** is pre-installed. **Android SDK** lives at `/opt/android-sdk`
  (`ANDROID_HOME`). On Claude Code on the web the SDK is *not* pre-installed; the
  `SessionStart` hook at `.claude/hooks/session-start.sh` provisions it (cmdline-tools,
  `platforms;android-36`, platform-tools, licenses) at session start. If
  `/opt/android-sdk` is empty mid-session, run
  `CLAUDE_CODE_REMOTE=true .claude/hooks/session-start.sh` rather than hand-installing.
- The Gradle wrapper auto-downloads Gradle on first run; AGP auto-installs the
  compileSdk minor platform on the first build.
- Key commands: `./gradlew assembleDebug` (build), `./gradlew test` (unit tests),
  `./gradlew lint` (lint), `./gradlew clean`.
- **No emulator practicality**: KVM is unavailable in the remote environments, and no
  emulator has SIM hardware anyway — telecom flows need a real device. Say so when
  reporting verification status.

## Testing expectations

- Code changes must include or update unit tests; product logic belongs in the pure
  domain layer where it is testable without Android.
- UI changes must include or update Robolectric + Roborazzi screenshot tests, wired
  into `.github/workflows/android-ci.yml`. The screenshot job records against an
  explicit `--tests` allow-list (one step per screenshot class), so a new
  `*ScreenshotTest` class that isn't added there never records in CI even when it
  passes locally — add a `Run … screenshot tests` step alongside the test class.
- The screenshot job auto-commits recording drift back to same-repo PR branches
  (`ci: refresh recorded screenshots`) and auto-posts a before/after diff comment on
  the PR. After CI runs on a UI-touching push, `git pull` before pushing again, and
  expect the refresh commit rather than hand-committing recorded PNGs.
- Run `./gradlew test` and `./gradlew lint` before pushing when the environment can;
  otherwise say clearly what was verified by inspection only.
