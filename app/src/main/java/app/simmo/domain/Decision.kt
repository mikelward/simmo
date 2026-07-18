package app.simmo.domain

/** An outgoing call as presented to the redirection service. */
data class PlacedCall(
    val dialedNumber: String,
    /** The account the platform would place the call on, if known. */
    val currentAccount: PhoneAccountRef?,
    /** False for e.g. Bluetooth / Android Auto calls, where no UI may be shown. */
    val interactive: Boolean,
)

/**
 * Loop guard for re-placed calls (SPEC "Redirect-loop guard"): when the chooser
 * re-places a call, the new call matches a live token and passes through
 * unmodified. The platform layer removes the consumed token.
 *
 * [account] pins the token to the account the re-place lands on; a null account
 * matches any account, for the recovery dialer that can't pin a SIM (its
 * `ACTION_DIAL` retry may go out on whichever SIM the user or default picks).
 */
data class PassToken(
    val dialedNumber: String,
    val account: PhoneAccountRef?,
    val expiresAtMillis: Long,
)

/**
 * Everything the decision reads, assembled off the decision path (AGENTS.md
 * "Fast decision path"). No live lookups happen during [DecisionEngine.decide].
 *
 * Hand-off rules can outlive their target (the app uninstalled, its phone
 * account disabled), so the snapshot also carries which targets are currently
 * reachable; the engine never routes to a target that isn't listed. SIM
 * accounts come from [activeSims] and need no separate listing.
 */
data class DecisionSnapshot(
    val rules: RuleBook,
    val activeSims: List<ActiveSim>,
    val defaultRegion: String,
    val passTokens: List<PassToken> = emptyList(),
    /** User-defined group id → member regions (uppercased); built off-path. */
    val customGroups: Map<String, List<String>> = emptyMap(),
    /**
     * Enabled call-capable phone accounts that aren't SIM subscriptions — SIP
     * providers and other calling apps registered with Telecom — keyed by ref
     * with their display labels. The keys are the targets a
     * [RuleAction.HandOff.ViaPhoneAccount] rule may redirect to; the labels
     * name them for the "Calling using" toast and the delay countdown.
     */
    val handOffAccounts: Map<PhoneAccountRef, String> = emptyMap(),
    /** Installed apps that can receive a dial-intent hand-off. */
    val handOffApps: Set<String> = emptySet(),
    /**
     * Dialed number → contact, for app-to-app (per-contact) hand-off. Built off
     * the decision path; the engine only reads it. Empty without READ_CONTACTS.
     */
    val contacts: ContactNumberIndex = ContactNumberIndex.EMPTY,
    /**
     * Settings "Show which SIM is used": announce rule-picked SIMs with a
     * "Calling using <SIM>" toast (SPEC "Call feedback and delay").
     */
    val announceCalls: Boolean = false,
    /**
     * Settings "Delay before calling": seconds of cancelable countdown before
     * a rule-picked SIM redirect is placed; 0 disables it (SPEC "Call
     * feedback and delay").
     */
    val callDelaySeconds: Int = 0,
    /**
     * Settings "Use contacts' local numbers" (SPEC "Hands-free and Android
     * Auto safeguards"): when a dialed number is overseas but its contact
     * also has a number local to the default region, route the local number
     * instead — confirmed via the chooser interactively, silently (and only
     * when unambiguous) otherwise.
     */
    val correctContactNumbers: Boolean = false,
)

/** The one answer per call; total, and never a silent drop (SPEC invariants). */
sealed interface Verdict {
    data class Proceed(
        val reason: ProceedReason,
        /** Set when [reason] is [ProceedReason.PASS_TOKEN]; the platform layer removes it. */
        val consumedToken: PassToken? = null,
        /**
         * The target to name in a "Calling using <target>" toast — set only
         * for [ProceedReason.ALREADY_ON_TARGET] (a rule picked the SIM or
         * calling account the call was already on) while
         * [DecisionSnapshot.announceCalls] is on. A pass token never
         * announces: the user just chose that target by hand.
         */
        val announceTarget: String? = null,
    ) : Verdict

    /**
     * Redirect to a SIM's or another calling account's phone account.
     * [announceTarget] names the target — the SIM, or the calling account's
     * label — for the "Calling using <target>" toast when
     * [DecisionSnapshot.announceCalls] is on. [newNumber] is set (E.164) when
     * same-contact number correction also rewrote the number — the redirect
     * carries both changes at once.
     */
    data class RedirectToAccount(
        val account: PhoneAccountRef,
        val announceTarget: String? = null,
        val newNumber: String? = null,
    ) : Verdict

    /**
     * Same-contact number correction without a SIM change: redirect the call
     * to [newNumberE164] on whatever account the platform was already using
     * (SPEC "Hands-free and Android Auto safeguards"). Produced only where no
     * confirmation can be shown — interactive corrections go through the
     * chooser instead.
     */
    data class RedirectNumber(val newNumberE164: String) : Verdict

    /**
     * Cancel the carrier call and show the delayed-call countdown instead of
     * redirecting silently (settings "Delay before calling"): the user gets
     * [delaySeconds] to cancel — or confirm — before the call is re-placed on
     * [account], the rule-picked SIM or calling account named [targetLabel].
     * Only produced in interactive contexts; the response to Telecom itself
     * is never delayed (the deadline invariant stands).
     */
    data class DelayedRedirect(
        val account: PhoneAccountRef,
        val targetLabel: String,
        val delaySeconds: Int,
    ) : Verdict

    /**
     * Cancel the carrier call and forward the dialed number to a calling app by
     * launching its number-carrying deep link [uri], scoped to [packageName]
     * (e.g. Google Voice, Teams). [appLabel] names it for a hand-off-failed
     * notification — and, when [announce] is set
     * ([DecisionSnapshot.announceCalls]), for a "Calling using <app>" toast
     * once the launch has been sent. Only produced when the number normalized
     * to E.164 and the target app is installed.
     */
    data class ForwardToApp(
        val packageName: String,
        val uri: String,
        val appLabel: String,
        val announce: Boolean = false,
    ) : Verdict

    /**
     * Cancel the carrier call and place it to a contact via an app's per-contact
     * call intent: `ACTION_VIEW` the [dataRowId] `ContactsContract.Data` row with
     * its [mimeType], scoped to [packageName] (e.g. WhatsApp). The MIME type is
     * what picks the app's call action out of the contact row — without it the
     * intent resolves to the generic contact viewer instead of the call. Only
     * produced when the dialed number resolved to a contact reachable on that
     * app. [announce] as on [ForwardToApp].
     */
    data class ForwardToContactApp(
        val packageName: String,
        val mimeType: String,
        val dataRowId: Long,
        val appLabel: String,
        val announce: Boolean = false,
    ) : Verdict

    /**
     * Cancel the carrier call and open Simmo's chooser. [skippedInactiveSims]
     * carries the SIMs that higher-priority rules wanted but which are
     * currently disabled, so the chooser can offer enabling them
     * (SPEC "Disabled-SIM assist"). [numberCorrection] is set when the
     * chooser opened to confirm a same-contact number correction — it offers
     * the contact's local number(s) beside the number as dialed; rule
     * evaluation didn't run for such a verdict, so [skippedInactiveSims] is
     * empty there.
     */
    data class OpenChooser(
        val skippedInactiveSims: List<SimRef> = emptyList(),
        val numberCorrection: NumberCorrection? = null,
    ) : Verdict
}

enum class ProceedReason {
    EMERGENCY,
    PASS_TOKEN,
    /** The winning rule's target is the account the call was already on. */
    ALREADY_ON_TARGET,
    /** A "no change" rule won: the system places the call as it intended. */
    SYSTEM_DEFAULT,
    /** Evaluation exhausted the list with nothing applicable; never drop. */
    NO_APPLICABLE_RULE,
    /** The in-memory snapshot isn't loaded yet (cold start); never wait, never drop. */
    SNAPSHOT_UNAVAILABLE,
    /** Decision code failed; degrade to the untouched call, never drop. */
    INTERNAL_ERROR,
}

/**
 * The pure decision function: `(call, snapshot, now) → verdict`. All product
 * routing logic lives here so it is testable without Android.
 *
 * Rules are evaluated in order; the first applicable rule wins. A rule that
 * cannot act right now — disabled/unresolvable SIM, unreachable hand-off
 * target, UI needed in a non-interactive context, no unambiguous country
 * match — is skipped and evaluation continues (SPEC "Rules"). If nothing
 * applies, the call proceeds unmodified.
 */
class DecisionEngine(private val countryDetector: CountryDetector) {

    fun decide(call: PlacedCall, snapshot: DecisionSnapshot, nowMillis: Long): Verdict {
        val country = countryDetector.detect(call.dialedNumber, snapshot.defaultRegion)
        if (country == CountryVerdict.Emergency) {
            return Verdict.Proceed(ProceedReason.EMERGENCY)
        }

        snapshot.passTokens.firstOrNull { it.matches(call, nowMillis) }?.let {
            return Verdict.Proceed(ProceedReason.PASS_TOKEN, consumedToken = it)
        }

        val destination = (country as? CountryVerdict.Country)?.regionCode

        // Same-contact number correction (SPEC "Hands-free and Android Auto
        // safeguards"), before rules: the corrected number is the call the
        // rules should route. Only for determined, non-local destinations.
        var effectiveNumber = call.dialedNumber
        var effectiveDestination = destination
        var correctedNumber: String? = null
        if (snapshot.correctContactNumbers && destination != null &&
            !destination.equals(snapshot.defaultRegion.trim(), ignoreCase = true)
        ) {
            val correction =
                snapshot.contacts.localCorrectionFor(call.dialedNumber, snapshot.defaultRegion)
            if (correction != null) {
                // Same target-availability gate as Ask: an enabled calling
                // account (SIP provider) is a real chooser target too, so an
                // account-only setup still gets the interactive confirmation
                // instead of a silent rewrite (Codex on PR #39).
                val chooserHasTargets =
                    snapshot.activeSims.isNotEmpty() || snapshot.handOffAccounts.isNotEmpty()
                if (call.interactive && chooserHasTargets) {
                    // Never rewrite a number silently where UI can ask: the
                    // chooser confirms, offering the local number(s) beside
                    // the number as dialed (TODO Phase 6) — including for a
                    // shared line, whose candidates it labels per contact
                    // (maintainer: shared lines are confirm-only). The
                    // user's pick there supersedes rule evaluation.
                    return Verdict.OpenChooser(numberCorrection = correction)
                }
                if (!correction.sharedLine && correction.candidates.size == 1) {
                    // No confirmation possible: correct only when unambiguous —
                    // one owning contact, one local number.
                    correctedNumber = correction.candidates.single().number
                    effectiveNumber = correctedNumber
                    effectiveDestination = snapshot.defaultRegion.trim().uppercase()
                }
            }
        }

        val skippedInactiveSims = mutableListOf<SimRef>()

        for (rule in snapshot.rules.rules) {
            // A user-disabled rule is kept in the list but never acts.
            if (!rule.enabled) continue
            if (!rule.matcher.matches(effectiveDestination, snapshot.customGroups)) continue
            when (val action = rule.action) {
                is RuleAction.UseSim -> when (val resolved = resolveSim(action.sim, snapshot.activeSims)) {
                    is SimResolution.Active ->
                        return routeToAccount(call, resolved.sim.phoneAccount, snapshot, correctedNumber)
                    // Disabled or ambiguous: skip, but remember disabled SIMs
                    // so the chooser can offer enabling them.
                    SimResolution.Inactive -> skippedInactiveSims += action.sim
                    is SimResolution.Ambiguous -> Unit
                }

                RuleAction.UseMatchingCountrySim -> {
                    val matching = effectiveDestination?.let { region ->
                        snapshot.activeSims.filter { it.countryIso.equals(region, ignoreCase = true) }
                    }
                    if (matching?.size == 1) {
                        return routeToAccount(call, matching.single().phoneAccount, snapshot, correctedNumber)
                    }
                }

                is RuleAction.HandOff.ViaPhoneAccount ->
                    if (action.account in snapshot.handOffAccounts) {
                        return routeToAccount(call, action.account, snapshot, correctedNumber)
                    }

                is RuleAction.HandOff.ViaDialIntent ->
                    // Cancel-and-forward to the app's deep link, but only in an
                    // interactive context, only when the app is installed, and
                    // only when the number normalizes to E.164 (its deep link
                    // needs a full number). A short code / undetermined number
                    // can't be forwarded, so skip to the next rule rather than
                    // strand the call. E.164 comes from the same warm parse the
                    // contact index uses — no fresh metadata load on the path.
                    if (call.interactive && action.app.packageName in snapshot.handOffApps) {
                        normalizeToE164(effectiveNumber, snapshot.defaultRegion)?.let { e164 ->
                            return Verdict.ForwardToApp(
                                action.app.packageName,
                                action.app.launchUri(e164),
                                action.app.label,
                                announce = snapshot.announceCalls,
                            )
                        }
                    }

                is RuleAction.HandOff.ViaContactApp ->
                    // Applies only when the dialed number is a contact reachable
                    // on the app (a call-action row in the warm index), and only
                    // interactively (cancel-and-forward shows the app's UI).
                    // Otherwise skip — a non-contact number falls through to the
                    // lower rules rather than stranding the call.
                    if (call.interactive) {
                        snapshot.contacts.lookup(effectiveNumber, snapshot.defaultRegion)
                            ?.callActions?.get(action.app)
                            ?.let { dataRowId ->
                                return Verdict.ForwardToContactApp(
                                    action.app.packageName,
                                    action.app.dataMimeType,
                                    dataRowId,
                                    action.app.label,
                                    announce = snapshot.announceCalls,
                                )
                            }
                    }

                RuleAction.Ask ->
                    // Applicable only when the chooser has at least one target
                    // to offer — an active SIM or another enabled calling
                    // account. With neither (READ_PHONE_STATE revoked,
                    // degraded telephony read), canceling would strand the
                    // call behind a chooser that can only cancel — skip
                    // instead, degrading toward "proceed unmodified".
                    if (call.interactive &&
                        (snapshot.activeSims.isNotEmpty() || snapshot.handOffAccounts.isNotEmpty())
                    ) {
                        return Verdict.OpenChooser(skippedInactiveSims.toList())
                    }

                RuleAction.SystemDefault ->
                    // "No change" is about the account; a corrected number
                    // still goes out (on the platform's own account).
                    return correctedNumber?.let { Verdict.RedirectNumber(it) }
                        ?: Verdict.Proceed(ProceedReason.SYSTEM_DEFAULT)
            }
        }
        return correctedNumber?.let { Verdict.RedirectNumber(it) }
            ?: Verdict.Proceed(ProceedReason.NO_APPLICABLE_RULE)
    }

    private fun RuleMatcher.matches(
        destination: String?,
        customGroups: Map<String, List<String>>,
    ): Boolean = when (this) {
        RuleMatcher.AnyDestination -> true
        is RuleMatcher.Country -> destination != null && regionCode.equals(destination, ignoreCase = true)
        is RuleMatcher.Countries -> destination != null &&
            (
                regionCodes.any { it.equals(destination, ignoreCase = true) } ||
                    // Group membership resolves from the in-memory snapshot at
                    // decision time — the static table for built-ins, the user's
                    // custom groups for the rest — so one stored group entry
                    // tracks membership across app updates and edits alike.
                    groupIds.any { id ->
                        groupMembers(id, customGroups).any { it.equals(destination, ignoreCase = true) }
                    }
                )
    }

    /**
     * A rule resolved [target]; redirect there (or pass through when the call
     * is on it already), naming the target — an active SIM, or a calling
     * account's label from the snapshot — for the optional "Calling using"
     * toast and the delay countdown. Both apply to SIMs and calling accounts
     * alike: an unexpected SIP-account call costs money just like an
     * unexpected SIM would.
     */
    private fun routeToAccount(
        call: PlacedCall,
        target: PhoneAccountRef,
        snapshot: DecisionSnapshot,
        correctedNumber: String? = null,
    ): Verdict {
        val targetLabel = snapshot.activeSims
            .firstOrNull { it.phoneAccount == target }
            ?.let { it.displayName.ifBlank { it.carrierName } }
            ?: snapshot.handOffAccounts[target]
        val announce = if (snapshot.announceCalls) targetLabel else null
        if (correctedNumber == null && call.currentAccount == target) {
            // Nothing is being changed, so there is nothing to give the user
            // a chance to cancel — no delay, just the optional toast. (A
            // corrected number IS a change, so it never passes through here.)
            return Verdict.Proceed(ProceedReason.ALREADY_ON_TARGET, announceTarget = announce)
        }
        // The delay needs UI (its countdown screen), so non-interactive calls
        // (Bluetooth, Android Auto) redirect immediately rather than stranding
        // behind a screen that can't be shown. Corrected calls never delay:
        // interactive corrections already went through the chooser.
        if (correctedNumber == null && targetLabel != null &&
            snapshot.callDelaySeconds > 0 && call.interactive
        ) {
            return Verdict.DelayedRedirect(target, targetLabel, snapshot.callDelaySeconds)
        }
        return Verdict.RedirectToAccount(target, announceTarget = announce, newNumber = correctedNumber)
    }

    private fun PassToken.matches(call: PlacedCall, nowMillis: Long): Boolean =
        dialedNumber == call.dialedNumber &&
            (account == null || account == call.currentAccount) &&
            expiresAtMillis > nowMillis
}
