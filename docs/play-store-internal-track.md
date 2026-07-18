# Play Store internal testing track

CI uploads a signed release AAB to the Google Play **internal** testing track on
every push to `main` that contains a release-worthy commit. The upload is wired
into the `deploy` job in `.github/workflows/android-ci.yml` using
[`r0adkll/upload-google-play`](https://github.com/r0adkll/upload-google-play).
It runs in addition to (not instead of) the Firebase App Distribution step —
Firebase remains the faster, no-Play-review channel; the internal track is the
route to alpha/beta/production.

## What gets uploaded

`./gradlew bundleRelease` produces
`app/build/outputs/bundle/release/app-release.aab` and the action uploads it to
the `internal` track on the `app.simmo` listing. Play App Signing re-signs the
AAB with its managed app-signing key before delivery, so the upload key
generated below only authenticates to Play — it doesn't sign what testers run.

## When the upload runs

The upload steps only run when **all** of the following are true:

- The `deploy` job is running (push or manual `workflow_dispatch` on `main`,
  after the build and screenshot jobs pass).
- `RELEASE_KEYSTORE_BASE64` is non-empty (so the upload key materializes).
- `PLAY_SERVICE_ACCOUNT_JSON` is non-empty (so CI can authenticate to Play).
- The push contains at least one release-worthy commit (see "Release notes"
  below); housekeeping-only pushes skip the release. A manual
  `workflow_dispatch` overrides the skip.

Forks and fresh clones without these secrets still get a green run — the AAB
build and upload steps are skipped. The release `signingConfig` in
`app/build.gradle.kts` is also only attached when `RELEASE_KEYSTORE_FILE` is
set, so a local `./gradlew bundleRelease` without the env vars produces an
unsigned AAB rather than a build failure.

## One-time Play Console setup

### 1. Create the app on Play Console

https://play.google.com/console → "Create app":

- **App name**: `Simmo` (matches `fastlane/metadata/android/en-US/title.txt`)
- **Default language**: English (United States)
- **App or game**: App; **Free or paid**: Free
- **Package name**: `app.simmo` (must match `applicationId` in
  `app/build.gradle.kts`)

Complete the required declarations under "App content" — including the privacy
policy link (https://mikelward.github.io/simmo/PRIVACY.html) and, before the
first release built with Firebase enabled, the data safety form (see
`TODO.md`).

### 2. Seed the internal track with a manual upload

The first AAB for any new app must be uploaded through the Play Console UI; the
API can only create subsequent releases.

**Option A — let CI build it (recommended).** Add the four release-keystore
secrets from the table below (everything except `PLAY_SERVICE_ACCOUNT_JSON`) and
push to main (or dispatch the workflow). The `Build release AAB` step is
decoupled from the Play upload and always publishes the signed AAB as a
workflow artifact called `app-release-aab` — download it from the Actions UI
and upload it through Play Console.

**Option B — build locally:**

```sh
RELEASE_KEYSTORE_FILE=/path/to/release.keystore \
RELEASE_KEYSTORE_PASSWORD=<password> \
RELEASE_KEY_PASSWORD=<password> \
RELEASE_KEY_ALIAS=simmo \
./gradlew bundleRelease
```

Either way, upload `app-release.aab` via Play Console → Internal testing →
Create new release, and accept Play App Signing when prompted. Then add
`PLAY_SERVICE_ACCOUNT_JSON` so the next push to main uploads automatically.

### 3. Add internal testers

Play Console → Internal testing → Testers tab → "Create email list". Send the
opt-in URL to testers; they follow it once before the app appears in their Play
Store.

### 4. Enable the Google Play Android Developer API

Enable
https://console.cloud.google.com/apis/library/androidpublisher.googleapis.com
on a Cloud project of your choice.

### 5. Create the service account

In the **same** Cloud project: IAM → Service accounts → Create (no Cloud-level
roles needed) → Keys tab → Add key → JSON. The downloaded JSON becomes the
`PLAY_SERVICE_ACCOUNT_JSON` secret.

### 6. Grant the service account access in Play Console

Play Console → Users and permissions → Invite new users → the service account
email. On "App permissions", add Simmo and grant **Releases: Release to testing
tracks** — the minimum for an internal-track upload. Propagation can take a few
minutes.

## Generating the upload keystore

Keep this keystore safe — losing it means Play Console's key-reset flow before
new uploads work. It is only the upload credential; Play manages the app-signing
key.

```sh
KEYSTORE_PASSWORD=$(openssl rand -hex 24)
keytool -genkeypair \
  -keystore release.keystore \
  -alias simmo \
  -storetype PKCS12 \
  -storepass "$KEYSTORE_PASSWORD" \
  -keypass "$KEYSTORE_PASSWORD" \
  -dname "CN=Simmo Release, O=Simmo, C=US" \
  -validity 36500 \
  -keyalg RSA \
  -keysize 2048
base64 -w0 release.keystore > release.keystore.b64
echo "Password: $KEYSTORE_PASSWORD"
```

Save the base64 contents and password into the secrets below, then **delete the
local keystore file** — but if this is the very first AAB (step 2), keep it
around until the upload key is enrolled in Play App Signing.

## Required secrets

Add these in repo Settings → Environments → `production` (the `deploy` job runs
in that environment; restrict its deployment branches to `main`):

| Secret | Description |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | Base64-encoded PKCS12 keystore bytes (`base64 -w0 release.keystore`). |
| `RELEASE_KEYSTORE_PASSWORD` | Random hex string set when the keystore was generated. |
| `RELEASE_KEY_PASSWORD` | Same value as `RELEASE_KEYSTORE_PASSWORD` (PKCS12 convention). |
| `RELEASE_KEY_ALIAS` | Key alias inside the keystore. Use `simmo` to match the snippet above. |
| `PLAY_SERVICE_ACCOUNT_JSON` | Full JSON contents of the service account key from step 5. |

## Release notes

The "Build release notes" step collects the subject of every release-worthy
commit in the push — skipping `ci:` / `docs:` / `internal:` / `refactor:` /
`test:` / `tests:` prefixes and commits that only touch docs or dotfiles
(`docs/PRIVACY.md` excepted — it backs the hosted privacy policy) — into a
`• `-bulleted, oldest-first list written to `whatsnew-en-US`, truncated to
Play's 500-character per-language cap with a trailing `…` when it overflows.
The same list ships as the Firebase release notes, so both surfaces agree. See
"Commit messages" in `AGENTS.md`: every subject is end-user copy.

## versionCode

Play rejects an AAB whose `versionCode` is `<=` the highest already on any
track. `versionCode` derives from `git rev-list --count HEAD` (see
`app/build.gradle.kts`), which increases monotonically as long as `main` only
moves forward. CI checks out with `fetch-depth: 0` so the count isn't truncated
by a shallow clone.

## Troubleshooting

- **`The Android App Bundle was not signed.`** — the release `signingConfig`
  didn't attach. Confirm `RELEASE_KEYSTORE_BASE64` is set and the materialize
  step ran.
- **`APK specifies a version code that has already been used.`** — usually a
  shallow clone truncated `git rev-list --count HEAD`; check the checkout ran
  with `fetch-depth: 0`.
- **`The caller does not have permission`** — the service account lacks
  "Release to testing tracks" on the app, or the invite hasn't propagated.
- **`Package not found: app.simmo`** — the listing doesn't exist yet, or the
  first AAB hasn't been uploaded manually (step 2).
