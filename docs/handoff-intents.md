# Hand-off call intents

Reference for the **"Hand off to another app"** action (SPEC → *Hand-off to another
app*; `TODO.md` Phase 5). It records, per app, the exact intent/URL Simmo would launch to
hand a **dialed phone number** to that app, what actually happens, what it requires, and
how to verify it on a real device.

This is a *low-level implementation reference*, not a product decision — it lives here,
not in `SPEC.md`, and is expected to drift as apps change their deep links.

## How hand-off works (and why it constrains the list)

Simmo is the system call-redirection app. It cannot make a third-party app place a call
in the background. Two mechanisms exist (SPEC):

1. **Phone-account redirect** — only for apps that register a call-capable Telecom phone
   account. None of the MVP targets do (Google Voice included — verified it does not
   expose a usable call phone account), so this mechanism is **not used** here.
2. **Cancel-and-forward** — Simmo `cancelCall()`s the carrier call and launches the
   target app at the dialed number. This is what the apps below use.

Consequences that shape everything:

- **Interactive only.** Cancel-and-forward needs a foreground UI, so these rules are
  **skipped hands-free / in Android Auto** (the silent rules still apply).
- **Never drop a call.** Because we cancel *before* the app launches, a launch that fails
  would strand the user. So the target intent must be **pre-vetted to resolve**
  (reachability discovery, off the decision path, cached in the snapshot); if it does not
  resolve at call time, Simmo **proceeds unmodified** instead of cancelling.
- **Resolves ≠ ready.** Intent resolution only proves the app can *receive* the intent,
  not that it can *place the call*: Google Voice needs a linked number, Teams needs a
  Teams Phone plan, Viber Out needs credit. An installed-but-unprovisioned target would
  pass discovery, get `cancelCall()`'d, and then dump the user on a setup / no-plan screen
  with the carrier call already gone. So eligibility must also require **readiness /
  provisioning where it's detectable**; where it isn't, the target is **unsafe for
  automatic cancel-and-forward** — don't auto-cancel for it (offer only behind an explicit
  confirmation, or gate it until device QA proves the account can place the dialed call).
- **PSTN vs app-to-app.** Only apps that can dial an arbitrary **phone number** are useful
  here. Most "calling" apps (WhatsApp, Signal, Telegram, Messenger, Line, WeChat) are
  **app-to-app** — they can only call *their own users*, which the dial flow can't assume —
  so they are out of scope for the number-hand-off MVP.
- **"Opens" not "calls."** Auto-dial vs pre-fill differs per app and version. Editor copy
  should say *"opens <app> with the number"*, never *"calls with <app>"*.

Numbers below are **E.164** (`+<countrycode><national>`). The dialed number reaches the
service only as the raw `tel:` scheme-specific part, which may be **national format**
(e.g. `0412 345 678`) — so the E.164 form has to be produced by the **same already-warm
libphonenumber parse that country detection uses** (not a fresh metadata load on the fast
path) and carried alongside the verdict to the launch template. Do **not** normalize
lazily during redirection. Notation: `«E164»` = e.g. `+61412345678`; `«digits»` = same
without `+`; `«E164enc»` = URL-encoded for a query value (`+` → `%2B`, e.g.
`%2B61412345678`).

---

## MVP targets

### 1. Google Voice — `com.google.android.apps.googlevoice`

| | |
|---|---|
| **Launch** | `ACTION_VIEW`, `https://voice.google.com/calls?a=nc,«E164enc»`, `setPackage("com.google.android.apps.googlevoice")` |
| **What happens** | Opens Google Voice to a *new call* (`a=nc`) to the number. The user confirms, then GV uses its **callback** model: it rings the user's linked/forwarding number and bridges the two parties. Not a direct dial — an extra tap and a callback. |
| **Requires** | GV installed and set up with a linked number. Free. Cannot dial emergency numbers (GV blocks this — Simmo never hands emergency calls off anyway). |
| **Notes** | The `+` **must** be URL-encoded as `%2B` (it's a query value; a literal `+` decodes to a space — Google's documented form uses `%2B`), i.e. `a=nc,%2B61412345678`. Some references use `/u/0/calls…` (account index `0`); prefer the index-less form and let GV resolve the active account. If app-linking isn't set up the `https` URL may open the browser — `setPackage` biases to the app; if it doesn't resolve, treat GV as unreachable (see the fallback rule) rather than stranding the call. |
| **Confidence** | Medium — deep link is community-documented; the app-vs-browser open and callback behavior **need a device check**. |

### 2. Microsoft Teams — `com.microsoft.teams` (Skype's successor; Skype retired May 2025)

| | |
|---|---|
| **Launch** | `ACTION_VIEW`, `msteams://teams.microsoft.com/l/call/0/0?users=4:«E164enc»` (fall back to `https://teams.microsoft.com/l/call/0/0?users=4:«E164enc»`) — i.e. `4:%2B61412345678` |
| **What happens** | Opens Teams to call the PSTN number. `4:` is the PSTN MRI prefix. |
| **Requires** | Teams installed **and a paid Teams Phone calling plan** for PSTN. Without a plan, only Teams-user (app-to-app) calls work, so a raw number won't connect. |
| **Notes** | The `msteams://` scheme reportedly mishandles a literal `+`; URL-encode it as `%2B`. The `https` form opens a browser interstitial first on some devices. |
| **Confidence** | Grounded for the link format; **PSTN success is license-gated** — device-test with and without a calling plan. |

### 3. Viber — `com.viber.voip`

| | |
|---|---|
| **Launch** | `ACTION_VIEW`, `viber://keypad?number=«digits»`, `setPackage("com.viber.voip")` |
| **What happens** | Opens Viber's **keypad pre-filled** with the number; the user taps to call. Not auto-dial. |
| **Requires** | Viber installed. Viber-to-Viber is free; calling a non-Viber **phone number needs paid Viber Out credit**. |
| **Notes** | Use `viber://keypad?number=` (the documented dial form). **Not** `viber://add?number=`, which is for *adding/messaging* a contact and would land the user on the wrong surface after the carrier call is cancelled. An older pattern targets `com.viber.voip.WelcomeActivity` with a `tel:` URI, but component-targeting is fragile across versions. |
| **Confidence** | Medium — needs a device check for the pre-fill and Viber Out path. |

---

## Out of scope for the number-hand-off MVP

- **App-to-app only** (callee must be a user of the app; no arbitrary-number call). Out of
  the *number*-hand-off MVP, but:
  - **WhatsApp — worth revisiting** (`TODO.md` Phase 5): when the dialed number belongs to
    a saved WhatsApp contact, its voice-call intent works — `ACTION_VIEW` on
    `content://com.android.contacts/data/<rowId>` with MIME
    `vnd.android.cursor.item/vnd.com.whatsapp.voip.call`. Gated on `READ_CONTACTS` + a
    number→contact reverse lookup (shareable with the same-contact-number-correction
    index). Many users' contacts are on WhatsApp, so it's worth having despite the
    contact-only limit; only offer it when the number resolves to a WhatsApp contact.
  - **Signal, Telegram, Messenger, Line, WeChat** — no comparable public by-number or
    by-contact call intent found, so they stay *open-app-only* and are not offered.
- **TextNow** — free US/CA numbers, but no documented launch-to-number deep link found;
  revisit only if there's demand (needs device probing).

## Generic fallback (any app)

If a target has no known deep link, try `ACTION_VIEW` / `ACTION_DIAL` `tel:«E164»` with
`setPackage(app)`. If that doesn't resolve, the target is **not a valid hand-off** — a
bare launcher intent (`getLaunchIntentForPackage`) carries no number and would drop the
user at the app's home screen *after* the carrier call is already cancelled, stranding the
call. So: an app is a hand-off target only if it has an intent that **carries the dialed
number and resolves**; launcher-only apps are treated as **unreachable** — not offered,
and never cancelled for (proceed unmodified). Editor copy for valid targets: *"opens
<app>"*.

## Implementation notes (for the eventual feature)

- **Reachability discovery** (off the decision path): enumerate installed target apps,
  build each candidate **number-carrying** intent, and keep only those that
  `resolveActivity(...)`, carry the dialed number (a bare launcher intent does not count —
  see the fallback rule), **and are provisioned to place the call where that's
  detectable** (see "Resolves ≠ ready" — a target whose readiness can't be detected is
  offered only as non-automatic / behind confirmation, never auto-cancelled). Store the
  *vetted intent template* per reachable app in the warm snapshot (`handOffApps`),
  alongside the mechanism label and a readiness flag to show in the editor.
- **Decision path** only fires a pre-vetted intent; it never touches `PackageManager`.
- **Editor**: a "Hand off to <app>" action listing only reachable apps, with honest
  per-app copy ("opens Google Voice / Teams / Viber with the number").
- **Redirect-loop guard**: the re-placed call (if the app routes back through Telecom)
  carries the short-lived pass token so Simmo lets it through (SPEC → *Redirect-loop
  guard*).

---

## Device-test checklist

Cannot be verified in the sandbox (no telephony, no KVM) — run on a real Pixel with each
app installed and signed in. Record pass/fail and the *actual* behavior (auto-dial vs
pre-fill vs browser).

**Prerequisites**

- Pixel, Android 17, dual SIM (or at least one).
- Google Voice signed in with a linked number; Teams signed in (test both with and
  without a Teams Phone plan); Viber signed in (with and without Viber Out credit).

**Per-app**

| # | App | Steps | Expected | Result |
|---|-----|-------|----------|--------|
| 0 | Number format | Feed a **national-format** number (e.g. `0412 345 678`) | Simmo normalizes to E.164 off the fast path and every template below receives `+61412345678` (URL-encoded where needed), not the raw national string | |
| 1 | Google Voice | Launch `https://voice.google.com/calls?a=nc,%2B«digits»` with `setPackage` | Opens **GV app** (not browser) on a new call to the number; confirming starts the callback bridge. (A literal `+` here decodes to a space — must be `%2B`.) | |
| 2 | Google Voice | Same, but GV **not** set up / no linked number | GV shows a setup prompt; carrier call was already cancelled — confirm this is acceptable or that we vet "GV ready" before offering it | |
| 3 | Teams (with plan) | Launch `msteams://…/l/call/0/0?users=4:%2B«digits»` | Opens Teams and places/pre-fills the PSTN call | |
| 4 | Teams (no plan) | Same | Confirm it fails gracefully (no crash); decide whether to hide Teams when no plan is detectable | |
| 5 | Viber | Launch `viber://keypad?number=«digits»` with `setPackage` | Opens Viber **keypad** pre-filled with the number (not the add-contact surface) | |
| 6 | Viber Out | Same, then tap call to a non-Viber number | Placed via Viber Out credit (or prompts to buy) | |
| 7 | Fallback | Launch a target with only `tel:«E164»` + `setPackage` | Note which apps honor `tel:` vs ignore it | |
| 8 | Launcher-only | An app with no number-carrying intent (only a launcher) | Treated as **unreachable** — not offered, and the carrier call is **not** cancelled (never dropped at an app home screen) | |
| 9 | Resolve-then-cancel | Force an unresolvable intent | Simmo must **proceed unmodified**, never cancel-and-strand | |
| 10 | Hands-free | Trigger a hand-off rule while on a Bluetooth headset / Android Auto | Rule is **skipped** (interactive-only); a silent rule or pass-through applies | |
| 11 | Emergency | Confirm emergency numbers are never handed off | Untouched | |

Update the confidence column of each MVP app above once these are run.
