# Firebase App Distribution

CI distributes internal builds to testers through Firebase App Distribution. The
upload is wired into the `deploy` job in `.github/workflows/android-ci.yml` using
`wzieba/Firebase-Distribution-Github-Action`. There is no Gradle plugin involved.

## What gets uploaded

The action uploads the **debug** APK that `assembleDebug` produced
(`app/build/outputs/apk/debug/app-debug.apk`). Distributing the debug APK avoids
needing the release keystore for tester builds; debug-signed builds remain
installable for internal testers.

## Stable debug signing key

Android refuses to install an APK as an update of an existing one if the signing
key has changed. By default every CI runner generates a fresh
`~/.android/debug.keystore`, so successive builds would force testers to
uninstall before re-installing. To keep the signature stable, CI materializes a
debug keystore from a secret and points `app/build.gradle.kts`'s
`signingConfigs.debug` at it via the `DEBUG_KEYSTORE_FILE` environment variable.
The keystore is **not** checked in, and local builds without the env vars set
fall through to AGP's auto-generated debug keystore so day-to-day development
keeps working.

To generate the keystore once:

```sh
KEYSTORE_PASSWORD=$(openssl rand -hex 24)
keytool -genkeypair \
  -keystore debug.keystore \
  -alias androiddebugkey \
  -storetype PKCS12 \
  -storepass "$KEYSTORE_PASSWORD" \
  -keypass "$KEYSTORE_PASSWORD" \
  -dname "CN=Simmo Debug, O=Simmo, C=US" \
  -validity 36500 \
  -keyalg RSA \
  -keysize 2048
base64 -w0 debug.keystore > debug.keystore.b64
echo "Password: $KEYSTORE_PASSWORD"
```

Save the base64 contents and password into the secrets below and **delete the
local keystore file** (`*.keystore` is gitignored, but don't tempt fate). PKCS12
stores share a single password between the store and the key, so
`DEBUG_KEYSTORE_PASSWORD` and `DEBUG_KEY_PASSWORD` should both be set to the
same value.

## When the upload runs

The `deploy` job only runs on a push to `main` (or a manual `workflow_dispatch`
targeting `main`) after the build and screenshot jobs pass, and the Firebase
step additionally requires:

- `FIREBASE_APP_ID` and `FIREBASE_SERVICE_ACCOUNT_JSON` non-empty (skipped
  quietly otherwise, so forks and fresh clones still get a green run);
- `DEBUG_KEYSTORE_BASE64` non-empty — an APK signed with the runner's
  throwaway debug key would force testers to uninstall before installing
  (signature mismatch), so distribution skips rather than ship one;
- at least one release-worthy commit in the push — a push of only
  `ci:`/`docs:`/`internal:`/`refactor:`/`test:`-prefixed or docs/dotfile
  housekeeping commits skips distribution so testers' phones don't buzz for
  "ci: bump actions/checkout". A manual `workflow_dispatch` overrides the
  skip and publishes the current tip with a generic note.

## Required secrets

Add these in repo Settings → Environments → `production` (the `deploy` job runs
in that environment; restrict its deployment branches to `main`):

| Secret | Description |
| --- | --- |
| `FIREBASE_APP_ID` | Firebase App ID for the Android app, e.g. `1:1234567890:android:abcdef`. |
| `FIREBASE_SERVICE_ACCOUNT_JSON` | Full JSON of a service account with the `Firebase App Distribution Admin` role. |
| `GOOGLE_SERVICES_JSON` | Contents of `app/google-services.json`, so Crashlytics/Analytics ship in distributed builds (see `SETUP.md`). |
| `DEBUG_KEYSTORE_BASE64` | Base64-encoded PKCS12 keystore bytes (`base64 -w0 debug.keystore`). |
| `DEBUG_KEYSTORE_PASSWORD` | Random hex string set when the keystore was generated. |
| `DEBUG_KEY_PASSWORD` | Same value as `DEBUG_KEYSTORE_PASSWORD` (PKCS12 convention). |
| `DEBUG_KEY_ALIAS` | Key alias inside the keystore. Use `androiddebugkey` to match AGP's default. |

## Tester group

The tester group is hard-coded as `testers` in the workflow. A group with this
name must exist in the Firebase App Distribution console. To rename it, update
both the console and the `groups:` value in `.github/workflows/android-ci.yml`
together.

## Release notes

Release notes are the bulleted list of release-worthy commit subjects in the
push (see "Build release notes" in the workflow), followed by the run number and
SHA:

```
• <commit subject>
• <commit subject>
---
Build: <run-number> · <sha>
```

The first ~60 characters appear in the tester device's push notification, so
keep commit subjects informative — the same string ships to Play as "What's
new".

## Manual upload from a workstation

Install the [Firebase CLI](https://firebase.google.com/docs/cli) and run:

```sh
./gradlew assembleDebug
firebase appdistribution:distribute app/build/outputs/apk/debug/app-debug.apk \
  --app "$FIREBASE_APP_ID" \
  --groups testers \
  --release-notes "Local upload from $(git rev-parse --short HEAD)"
```
