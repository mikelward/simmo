# Setup

Building needs only JDK 17+ and an Android SDK (see `README.md`). Everything
below is optional, per-machine configuration.

## Firebase Crashlytics and Analytics

Firebase crash reporting and usage analytics are compiled into every build but
stay **dormant unless the build is made with a Firebase config file**. Fresh
clones and CI have no config, build cleanly, and produce an APK in which
Firebase never initializes and nothing is collected.

To enable it for your builds:

1. In the [Firebase console](https://console.firebase.google.com/), create (or
   open) the Simmo project and register an Android app with package name
   `app.simmo`. Enable Crashlytics under **Release & Monitor → Crashlytics**.
2. Download the app's `google-services.json` and place it at
   `app/google-services.json`. It is gitignored — never commit it.
3. Build as usual. When the file is present, `app/build.gradle.kts`
   automatically applies the Google services and Crashlytics Gradle plugins,
   which compile the config in and (for minified release builds) upload the R8
   mapping file so crash traces come back deobfuscated.

### What an enabled build collects — and who controls it

- Collection is **off in the manifest** and follows the in-app "Make Simmo
  better" opt-in (default on, set during onboarding, changeable anytime on the
  Settings screen). A tap applies to both
  Crashlytics and Analytics immediately and is marked durably on the spot; on a
  fresh install nothing is collected before the stored choice is read, and on
  later launches each process start applies the last choice made (the SDKs and
  Simmo's own marker both remember it, so an opt-out survives even a crash that
  loses the main settings write).
- Only automatic telemetry is collected: crash reports and standard Analytics
  events (first open, screen views, sessions). No custom events are logged;
  dialed numbers, contact names, and contact numbers are never sent (SPEC
  "Permissions and privacy").
- The advertising-ID permission Firebase Analytics would merge in is stripped
  in the manifest, and ad personalization signals are disabled.

### Before releasing an enabled build

`docs/PRIVACY.md` and the Play data safety form must accurately describe the
collection in any release built with Firebase enabled (see `TODO.md` Phase 8).
