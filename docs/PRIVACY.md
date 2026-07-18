# Simmo privacy policy

**Effective date: July 18, 2026**

Simmo picks the right SIM (or calling app) for your outgoing calls based on rules
you set. The short version of this policy: **everything Simmo does with your calls
happens on your device. The numbers you dial and your contacts are never collected
or transmitted. The only data that can leave your device is optional crash
reporting and usage statistics, controlled by the "Make Simmo better" switch.**

## What Simmo collects

- The numbers you dial, your contacts, and your rules are **never** collected or
  shared with anyone, including us.
- Simmo contains no advertising, and requests that the advertising ID never be
  available to the app.
- If the "Make Simmo better" switch is on, Simmo collects crash reports and
  anonymous usage statistics — see the next section.

## Crash reporting and usage statistics ("Make Simmo better")

Simmo uses Firebase Crashlytics and Google Analytics for Firebase to help us fix
crashes and understand which features are used:

- **Crash reports**: a technical trace of where the app crashed, plus device
  information such as model, operating system version, and free memory at the
  time of the crash.
- **Usage statistics**: standard app events such as first open, which screens
  are viewed, and session length, tied to a random per-install identifier.

Neither ever includes the numbers you dial, your contacts, or the contents of
your rules, and ad personalization is turned off.

Both are controlled by the **"Make Simmo better"** switch shown during setup: it
is on by default, nothing is collected before your stored choice is read, and
turning it off stops both crash reporting and usage statistics. This data is
processed by Google's Firebase services on our behalf; see
[Firebase's privacy documentation](https://firebase.google.com/support/privacy)
for how Firebase handles it.

## Dialed numbers

To decide which SIM a call should use, Simmo looks at the number you dialed and
works out the destination country. This happens entirely on your device, in memory,
using an offline phone-number library. Dialed numbers are **never stored** — they
exist only for the moment the call is being placed — and Simmo never sends them off
your device.

One exception to keep in mind: if a rule (or your choice in Simmo's chooser) hands
a call off to another calling app, such as Google Voice, Simmo passes the dialed
number to that app on your device so it can place the call — that is the feature
doing its job. What the receiving app does with the number is governed by that
app's own privacy policy.

## What Simmo stores on your device

Your rules, your settings, and a registry of the SIMs your device has used (their
names and carriers, so rules can refer to them, plus each SIM's home country and
its own phone number — when your device reports them — so you can tell your SIMs
apart on the SIMs screen). Simmo never transmits this data anywhere.

Like most Android apps, this app data is included in Android's standard backup and
device-to-device transfer, so if you have device backup turned on, Android copies
it to your backup (for example, your Google Account) along with your other app
data. That is handled by Android under your device's backup settings, not by
Simmo. Because dialed numbers are never stored at all, no call data can ever
appear in a backup.

## Permissions

Only the first two are required. Everything else is optional: you can skip any
optional permission during setup (Skip proceeds without it) and grant it
later, either from system settings or when a feature asks for it in context.
Skipping an optional permission only disables the feature that uses it.

**Required — Simmo cannot route calls without these:**

- **Read phone status (`READ_PHONE_STATE`)** — lists your SIMs and calling
  accounts, so rules can name them and Simmo can recognize your SIMs.
- **Call redirection role** — lets Android ask Simmo about each outgoing call, so
  your rules can pick the SIM.

**Optional — each unlocks a specific feature:**

- **Contacts (`READ_CONTACTS`)** — used to recognize that a dialed number belongs
  to one of your contacts. Powers calling a contact through another app (such as
  WhatsApp) and the "suggested countries from your contacts" shortcut in the
  country picker. Contact data is read on your device only and never leaves it.
  Without this permission, those features simply don't activate.
- **Notifications (`POST_NOTIFICATIONS`)** — used for reminders and error
  notices: "your SIM is now active — place the call?", "new SIM — add a rule?",
  and "couldn't open Google Voice" with a Redial shortcut. Without it, error
  notices appear as a brief on-screen message instead, and the reminders don't
  appear.
- **Make calls (`CALL_PHONE`)** — used to place a call in one tap when you
  confirm a choice in Simmo's chooser or tap Redial on an error notice. Without
  it, Simmo opens your dialer with the number filled in and you place the call
  yourself.
- **Read phone numbers (`READ_PHONE_NUMBERS`)** — used to show each SIM's own
  phone number on the SIMs screen, so you can tell similar SIMs apart. The
  number is read and stored on your device only. Without it, the SIMs screen
  simply doesn't show numbers.

## Emergency calls

Emergency calls are never redirected, altered, or interfered with in any way.

## Possible future changes

We are considering adding usage statistics about routing itself in a future
version: which SIM (by the name you gave it) is used to call which destination
country, and how often calls complete or fail — never contact names, contact
numbers, or the phone numbers you dial. These would be covered by the same "Make
Simmo better" switch. If they ship, this policy will be updated at the same time
to describe exactly what is collected and why, and the effective date will change.

## Changes to this policy

Any changes will be posted at this page with an updated effective date.

## Contact

Questions about this policy: mikel@mikelward.com
