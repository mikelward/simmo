# Simmo privacy policy

**Effective date: July 20, 2026**

Simmo picks the right SIM (or calling app) for your outgoing calls based on rules
you set. The short version of this policy: **everything Simmo does with your calls
happens on your device. The numbers you dial and your contacts are never collected
or transmitted. Data leaves your device only with your involvement: optional crash
reporting and usage statistics, controlled by the "Make Simmo better" switch, and a
debug log that goes nowhere unless you choose to share it.**

## What Simmo collects

- The numbers you dial and your contacts are **never** collected or shared with
  anyone, including us. (A debug log you choose to share includes phone numbers —
  dialed numbers and a SIM's own number — only in redacted form, and never your
  contacts — see "Sharing debug logs".)
- Simmo contains no third-party ad networks and no ad tracking, and requests
  that the advertising ID never be available to the app.
- If the "Make Simmo better" switch is on, Simmo collects crash reports and
  anonymous usage statistics — see the next section.
- Simmo can build a **debug log** that you may choose to share to report a
  problem — see "Sharing debug logs". It is never sent anywhere on its own.

## Crash reporting and usage statistics ("Make Simmo better")

Simmo uses Firebase Crashlytics and Google Analytics for Firebase to help us fix
crashes and understand which features are used:

- **Crash reports**: a technical trace of where the app crashed, plus device
  information such as model, operating system version, and free memory at the
  time of the crash. A crash report also carries a short, **redacted** log of
  Simmo's recent routing decisions and errors — the same masked form as the
  shareable debug log below, with every phone number redacted — so we can see
  what led up to the crash. A decision in that log may name the SIM it used by
  its display label (the name you see for it on the SIMs screen); it never
  includes a phone number.
- **Usage statistics**: standard app events such as first open, which screens
  are viewed, and session length, tied to a random per-install identifier.

Neither ever includes the **full** numbers you dial, your contacts, or a full
account identifier, and ad personalization is turned off.

Both are controlled by the **"Make Simmo better"** switch, shown during setup
and available anytime in Simmo's settings: it is on by default, and turning it
off stops both crash reporting and usage statistics immediately. On a fresh
install, nothing is collected before your choice is read; after that, each app
start applies the most recent choice you made. This data is processed by
Google's Firebase services on our behalf; see
[Firebase's privacy documentation](https://firebase.google.com/support/privacy)
for how Firebase handles it.

## Dialed numbers

To decide which SIM a call should use, Simmo looks at the number you dialed and
works out the destination country. This happens entirely on your device, in memory,
using an offline phone-number library. The complete number is **never written to
storage** and exists only in memory for the moment the call is being placed, and
Simmo never sends it off your device on its own. (A **redacted** partial number
may appear in the debug log described under "Sharing debug logs" — never the
complete number. A redacted copy of that log is kept in Simmo's private storage
so it survives an unexpected exit — a crash, or the system closing the app; the
prior run's copy is included if you share a report, then removed. It is never
backed up. It leaves your device only when you share that log — or, if you leave
crash reporting on, as part of a crash report's redacted breadcrumbs (see "Crash
reporting"); never as a complete number either way.)

One exception to keep in mind: if a rule (or your choice in Simmo's chooser) hands
a call off to another calling app, such as Google Voice, Simmo passes the dialed
number to that app on your device so it can place the call — that is the feature
doing its job. What the receiving app does with the number is governed by that
app's own privacy policy.

## What Simmo stores on your device

Your rules, your settings, and a registry of the SIMs your device has used (their
names and carriers, so rules can refer to them, plus each SIM's home country and
its own phone number — when your device reports them — so you can tell your SIMs
apart on the SIMs screen). Simmo never transmits your rules or this registry
anywhere — with one narrow exception: if you leave crash reporting on, a crash
report may name the SIM used for a recent routing decision by its display label
(see "Crash reporting"). It never transmits a SIM's phone number.

Like most Android apps, this app data is included in Android's standard backup and
device-to-device transfer, so if you have device backup turned on, Android copies
it to your backup (for example, your Google Account) along with your other app
data. That is handled by Android under your device's backup settings, not by
Simmo. Because the complete dialed number is never written to storage at all —
the debug log holds only redacted partials, and the copy of it Simmo keeps to
survive an unexpected exit (see "Sharing debug logs") lives in Simmo's private
cache, which is excluded from backup — no call data can ever appear in a backup.

## Sharing debug logs

Simmo's Settings screen has a **Share debug logs** action to help diagnose a
problem — for example, a call that didn't route the way you expected. When you
tap it, Simmo builds a short text report and opens Android's share sheet so you
can send it wherever you choose (and also copies it to your clipboard). Nothing
is sent anywhere unless you take that action and pick a destination.

The report contains your device and app-build details, whether Simmo holds the
call-redirection role, your current settings, your calling rules, your country
groups, and the names, carriers, home countries, and own numbers of the SIMs
Simmo has seen (so a rule like "in Mongolia, use Verizon" can actually be
diagnosed). It also includes a short log of Simmo's recent routing decisions and
any errors. A redacted copy of this log is kept in Simmo's private storage so it
survives an unexpected exit (a crash, or the system closing the background app),
which is what makes that kind of problem diagnosable; the prior run's copy is
included once when you share a report, then removed (and it is cleared with the
app's cache anyway). It is never included in a backup. Every phone number in the
report — a dialed number in the log, or a SIM's own number — appears **only in
redacted form**, a few leading and trailing characters with the rest masked, so a
complete number is never written to the report or shared. You can review the
report in the share sheet or paste it from the clipboard before sending it.

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

We may also add links to useful travel products, such as buying a travel eSIM,
and some of those may be affiliate links (meaning we can earn a commission on
purchases). Opening such a link would never send the numbers you dial, your
contacts, or your rules to anyone. If a link ever shares any other data from
your device, this policy will be updated first to say exactly what and why.

## Changes to this policy

Any changes will be posted at this page with an updated effective date.

## Contact

Questions about this policy: mikel@mikelward.com
