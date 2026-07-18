# Simmo privacy policy

**Effective date: July 18, 2026**

Simmo picks the right SIM (or calling app) for your outgoing calls based on rules
you set. The short version of this policy: **Simmo collects nothing, transmits
nothing, and has no internet access.**

## No data collection

- Simmo does not request the internet permission, so the app cannot send anything
  off your device — you can verify this from the app's manifest.
- Simmo contains no analytics, no advertising, and no third-party SDKs.
- Nothing about you, your contacts, or your calls is collected or shared with
  anyone, including us.

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
names and carriers, so rules can refer to them). Simmo itself never transmits this
data anywhere — without internet access, it has no way to.

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

## Emergency calls

Emergency calls are never redirected, altered, or interfered with in any way.

## Possible future changes

We are considering adding crash reporting (for example, Firebase Crashlytics) and
app usage analytics in a future version, to help diagnose crashes and understand
how features are used. The analytics under consideration would cover things like
which SIM (by the name you gave it) is used to call which destination country, and
how often calls complete or fail — never contact names, contact numbers, or the
phone numbers you dial. The statements above describe the app as it is today. If
crash reporting or analytics ship, this policy will be updated at the same time to
describe exactly what is collected and why, and the effective date will change.

## Changes to this policy

Any changes will be posted at this page with an updated effective date.

## Contact

Questions about this policy: mikel@mikelward.com
