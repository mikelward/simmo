package app.simmo.domain

import kotlinx.serialization.Serializable

/**
 * A calling app Simmo can hand a dialed *phone number* off to (SPEC "Hand-off to
 * another app"; `docs/handoff-intents.md`). Unlike [ContactCallApp] — which places
 * a call to a saved *contact* — these dial an arbitrary number by cancelling the
 * carrier call and launching the app's number-carrying deep link.
 *
 * [packageName] scopes the launch, [label] is the user-facing name, and [launchUri]
 * builds the app's deep link for an E.164 number. Serializable because a rule's
 * action stores which app. Extend as more dial-intent targets are verified.
 *
 * "Resolves ≠ ready": these targets can be installed but unprovisioned (Google
 * Voice needs a linked number, Teams needs a Teams Phone plan), which isn't
 * detectable from the intent — so the launch may land on a setup screen. The
 * service resolves the intent before cancelling (never strands an *uninstalled*
 * target); the unprovisioned case is owed a device-QA pass (docs/handoff-intents.md).
 */
@Serializable
enum class DialHandoffApp(val packageName: String, val label: String) {
    GOOGLE_VOICE("com.google.android.apps.googlevoice", "Google Voice"),
    TEAMS("com.microsoft.teams", "Microsoft Teams"),
    ;

    /**
     * The deep link that opens this app at [e164] (`+<cc><national>`). The `+` is
     * encoded as `%2B` in every template — it sits in a query/path value where a
     * literal `+` decodes to a space (docs/handoff-intents.md).
     */
    fun launchUri(e164: String): String {
        val digits = e164.removePrefix("+")
        return when (this) {
            // New-call deep link (a=nc); Google Voice then confirms and bridges
            // the call through its callback model.
            GOOGLE_VOICE -> "https://voice.google.com/calls?a=nc,%2B$digits"
            // 4: is the PSTN MRI prefix; connecting the number needs a Teams
            // Phone calling plan (app-to-app Teams calls work without one).
            TEAMS -> "msteams://teams.microsoft.com/l/call/0/0?users=4:%2B$digits"
        }
    }
}
