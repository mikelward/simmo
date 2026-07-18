# Why Android calls the wrong (overseas) number, and what can steer it

Findings for the Phase 6 investigation item: when a contact has both an overseas
number and a local one, when and why do Android and Android Auto pick the
overseas number, and can anything steer the choice before the call is placed?

Short answer: the only durable upstream lever is the contact's **default number**
(`IS_SUPER_PRIMARY` in the contacts provider), taps in Contacts/Dialer honor it,
but Google Assistant — the voice path that matters hands-free — does not reliably
honor it and exposes no API to influence its pick. So the problem is effectively
unfixable upstream for voice-placed calls, and Simmo's call-redirection hook (the
same-contact correction and the hands-free guard) is the right — and last —
place to intervene. Every claim below about live behavior still needs the
Android Auto device QA pass in `TODO.md`; the API facts are from current
platform documentation.

## How each entry point picks a number

**Contacts / Dialer (touch).** Tapping "Call" on a contact with several numbers
shows a disambiguation sheet. Ticking "Remember this choice" (or "Set default"
on the number's long-press menu) stores the choice in the contacts provider:
the `Data` row gets `IS_PRIMARY` and `IS_SUPER_PRIMARY` set, and later taps call
that number silently. This is the classic wrong-number trap: a default
remembered while visiting the contact's country keeps routing calls to the
overseas number after everyone flies home. There is no expiry and no UI hint
that a default exists beyond the sheet no longer appearing.

**Google Assistant / Android Auto voice.** "Call Mum" resolves the number
server-side. A spoken label wins ("call Mum's mobile"); otherwise Assistant
sometimes asks, but frequently just picks — and long-standing user reports say
it does not reliably use the contact's default number. The heuristics are
opaque and there is no public API (app action, slice, or otherwise) by which a
third-party app can influence which of a contact's numbers Assistant chooses.
This is the path the SPEC's hands-free scenario cares about, and it is the one
that cannot be steered.

**Android Auto touch UI.** Browsing to a contact lists their numbers, so the
pick is explicit. The recents list, though, redials whatever number the last
call used — one wrong voice call seeds future wrong redials.

**Historic usage ranking (dead).** Before Android 10 the platform ranked a
contact's numbers by usage (`TIMES_CONTACTED`, `DataUsageFeedback`), so calling
the local number often eventually made it the suggestion. Android 10 removed
contacts affinities: `DataUsageFeedback` writes are ignored and the usage
columns are periodically cleared. "Call it more and Android will learn" no
longer exists.

## What could steer the choice upstream

- **Set the contact default.** Any app holding `WRITE_CONTACTS` can set
  `IS_SUPER_PRIMARY` on the local number's `Data` row. That would fix the
  Contacts/Dialer tap path and the "remembered while abroad" trap. It would
  *not* dependably fix Assistant/Auto voice calls (see above), it is a heavy
  new permission for Simmo, and it silently edits the user's contact data —
  so if pursued at all it should be an explicit offer after a confirmed
  correction, never automatic. Filed as a backlog idea in `TODO.md`.
- **Usage feedback:** gone since Android 10 (above).
- **Data row ordering / sync accounts:** row order is unspecified and owned by
  each sync adapter; not a dependable lever.
- **Earlier Telecom hooks:** none exist. The call-redirection service is the
  first point a third party sees the outgoing call, and Simmo is already there.

## Conclusion

Prevention upstream is only possible for the touch paths, at the cost of
`WRITE_CONTACTS` and still leaving voice calls wrong. Correction at the
redirection service — shipped as the same-contact number correction and the
opt-in hands-free guard — covers every path, including voice, at the moment
the call is actually placed. The investigation therefore closes with "mitigate
at the hook, don't chase upstream", plus the optional default-number offer as
a backlog idea.

## References

- [`ContactsContract.CommonDataKinds.Phone`](https://developer.android.com/reference/android/provider/ContactsContract.CommonDataKinds.Phone)
  (`IS_PRIMARY` / `IS_SUPER_PRIMARY` semantics).
- [Contacts provider affinities removal](https://source.android.com/docs/core/permissions/contacts-affinities)
  and [Contacts provider guide](https://developer.android.com/identity/providers/contacts-provider)
  (`DataUsageFeedback` ignored, usage columns cleared, Android 10+).
- [Android Auto: make and receive calls](https://support.google.com/androidauto/answer/6348066)
  (spoken-label selection).
- [Assistant community: default number not honored](https://support.google.com/assistant/thread/316945)
  (user reports; needs device confirmation).
