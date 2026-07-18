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
    /**
     * The hands-free call guard's "Block overseas calls" toggle (SPEC
     * "Hands-free and Android Auto safeguards"): in a non-interactive
     * context, cancel a call whose destination isn't the default region and
     * offer it back by notification instead of routing it.
     */
    val guardOverseasHandsFree: Boolean = false,
    /**
     * The guard's "Block calls needing a disabled SIM" toggle: cancel a
     * non-interactive call when a matching rule was skipped because its SIM
     * is disabled, rather than let a lower rule quietly place it another way.
     */
    val guardDisabledSimHandsFree: Boolean = false,
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

    /**
     * The opt-in hands-free call guard fired (SPEC "Hands-free and Android
     * Auto safeguards"): cancel the call. The ONLY sanctioned exception to
     * "a call is never silently dropped" — the platform layer must pair
     * every block with a notification (or its toast fallback) whose tap
     * reopens the call in the chooser. [destination] (uppercase region) is
     * set for [GuardBlockReason.OVERSEAS]; [wantedSims] carries the disabled
     * SIMs the matching rules wanted for [GuardBlockReason.DISABLED_SIM], so
     * the chooser offers the enable assist; [numberCorrection] rides along
     * when one was pending, so the redial chooser still offers the contact's
     * local number(s); [correctedNumber] is the local number a silent
     * correction would have placed — the redial should offer that call.
     */
    data class BlockCall(
        val reason: GuardBlockReason,
        val destination: String? = null,
        val wantedSims: List<SimRef> = emptyList(),
        val numberCorrection: NumberCorrection? = null,
        val correctedNumber: String? = null,
    ) : Verdict
}

/** Why the hands-free call guard stopped a call. */
enum class GuardBlockReason { OVERSEAS, DISABLED_SIM }

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
 * match — is skipped and evaluation continues (SPEC "Calling rules"). If nothing
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
        val outcome = correctionOutcome(call, destination, snapshot)
        when (outcome) {
            // Never rewrite a number silently where UI can ask: the chooser
            // confirms, offering the local number(s) beside the number as
            // dialed (TODO Phase 6) — including for a shared line, whose
            // candidates it labels per contact (maintainer: shared lines are
            // confirm-only). The user's pick there supersedes rule evaluation.
            is CorrectionOutcome.Confirm ->
                return Verdict.OpenChooser(numberCorrection = outcome.correction)

            is CorrectionOutcome.Apply -> {
                correctedNumber = outcome.number
                effectiveNumber = outcome.number
                effectiveDestination = snapshot.defaultRegion.trim().uppercase()
            }

            // Missed corrections are surfaced by [missedCorrection] after the
            // platform layer has responded; the call itself routes as dialed.
            is CorrectionOutcome.Missed, CorrectionOutcome.None -> Unit
        }

        // The hands-free call guard, overseas half (SPEC "Hands-free and
        // Android Auto safeguards"): where no UI can be shown, an overseas
        // call is cancelled outright rather than routed — the driver answers
        // the notification when they can look. Judged after correction, so a
        // silently localized call is no longer overseas and proceeds; a
        // pending (missed) correction rides on the block so the redial
        // chooser can still offer the local number. Gated on the chooser
        // having a redial target (Codex on PR #48): a degraded snapshot —
        // READ_PHONE_STATE revoked, failed telephony read — must degrade to
        // "proceed unmodified", never cancel into a chooser that can only
        // cancel. Also gated on a known home region (Codex again): a blank
        // defaultRegion — radio off, no network country — makes every
        // destination look overseas, so with nothing to compare against the
        // call proceeds unmodified.
        val homeRegion = snapshot.defaultRegion.trim()
        if (!call.interactive && snapshot.guardOverseasHandsFree && chooserHasTargets(snapshot) &&
            homeRegion.isNotEmpty() && effectiveDestination != null &&
            !effectiveDestination.equals(homeRegion, ignoreCase = true)
        ) {
            return Verdict.BlockCall(
                GuardBlockReason.OVERSEAS,
                destination = effectiveDestination.uppercase(),
                numberCorrection = (outcome as? CorrectionOutcome.Missed)?.correction,
            )
        }

        val skippedInactiveSims = mutableListOf<SimRef>()
        val verdict =
            evaluateRules(call, effectiveNumber, effectiveDestination, correctedNumber, snapshot, skippedInactiveSims)

        // The guard's disabled-SIM half: a matching rule wanted a SIM that is
        // currently disabled. The user opted to stop the call rather than let
        // a lower rule (or the system default) quietly place it another way
        // while they can't see the enable assist. The same chooser-target
        // gate applies, and it also covers the permission-degraded case
        // (Codex on PR #48): with nothing visible in telephony, "Inactive"
        // is not proof a SIM is disabled — the telephony read may simply have
        // failed — so never block on it. A pending correction rides on this
        // block too, so its chooser can still offer the local number(s).
        if (!call.interactive && snapshot.guardDisabledSimHandsFree &&
            chooserHasTargets(snapshot) && skippedInactiveSims.isNotEmpty()
        ) {
            return Verdict.BlockCall(
                GuardBlockReason.DISABLED_SIM,
                wantedSims = skippedInactiveSims.toList(),
                numberCorrection = (outcome as? CorrectionOutcome.Missed)?.correction,
                correctedNumber = correctedNumber,
            )
        }
        return verdict
    }

    /**
     * Whether the chooser would have anything to place a call on — an active
     * SIM or another enabled calling account. Every cancel-into-chooser path
     * (Ask, correction confirmation, the hands-free guard's block redial)
     * must check this: canceling with no target strands the call behind a
     * chooser that can only cancel. An all-empty read is also how a revoked
     * READ_PHONE_STATE looks, so this doubles as the degraded-snapshot gate.
     */
    private fun chooserHasTargets(snapshot: DecisionSnapshot): Boolean =
        snapshot.activeSims.isNotEmpty() || snapshot.handOffAccounts.isNotEmpty()

    /** The ordered first-applicable-rule-wins pass; fills [skippedInactiveSims]. */
    private fun evaluateRules(
        call: PlacedCall,
        effectiveNumber: String,
        effectiveDestination: String?,
        correctedNumber: String?,
        snapshot: DecisionSnapshot,
        skippedInactiveSims: MutableList<SimRef>,
    ): Verdict {
        for (rule in snapshot.rules.rules) {
            // A user-disabled rule is kept in the list but never acts.
            if (!rule.enabled) continue
            if (!rule.matcher.matchesRegion(effectiveDestination, snapshot.customGroups)) continue
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
                    // to offer; with none (READ_PHONE_STATE revoked, degraded
                    // telephony read), skip instead, degrading toward
                    // "proceed unmodified" — see [chooserHasTargets].
                    if (call.interactive && chooserHasTargets(snapshot)) {
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

    /** How same-contact correction applies to a call; one source of truth. */
    private sealed interface CorrectionOutcome {
        data object None : CorrectionOutcome

        /** Interactive with chooser targets: the chooser confirms. */
        data class Confirm(val correction: NumberCorrection) : CorrectionOutcome

        /** Unambiguous where no UI can show: silently correct to [number]. */
        data class Apply(val number: String) : CorrectionOutcome

        /** Ambiguous where no UI can show: route as dialed, offer [correction] after. */
        data class Missed(val correction: NumberCorrection) : CorrectionOutcome
    }

    private fun correctionOutcome(
        call: PlacedCall,
        destination: String?,
        snapshot: DecisionSnapshot,
    ): CorrectionOutcome {
        if (!snapshot.correctContactNumbers || destination == null ||
            destination.equals(snapshot.defaultRegion.trim(), ignoreCase = true)
        ) {
            return CorrectionOutcome.None
        }
        val correction = snapshot.contacts.localCorrectionFor(call.dialedNumber, snapshot.defaultRegion)
            ?: return CorrectionOutcome.None
        // Same target-availability gate as Ask: an enabled calling account
        // (SIP provider) is a real chooser target too, so an account-only
        // setup still gets the interactive confirmation instead of a silent
        // rewrite (Codex on PR #39).
        if (call.interactive && chooserHasTargets(snapshot)) {
            return CorrectionOutcome.Confirm(correction)
        }
        if (!correction.sharedLine && correction.candidates.size == 1) {
            return CorrectionOutcome.Apply(correction.candidates.single().number)
        }
        return CorrectionOutcome.Missed(correction)
    }

    /**
     * A correction that existed for [call] but could neither be confirmed
     * (no UI allowed, or no chooser target) nor applied silently (a shared
     * line, or several local numbers): the call routed as dialed, and the
     * platform layer offers the local number afterwards — by notification,
     * never by touching the in-flight call (SPEC "Hands-free and Android
     * Auto safeguards"). Null for emergency and pass-token calls, when the
     * chooser already confirmed, when the correction was applied, and when
     * none exists.
     */
    fun missedCorrection(call: PlacedCall, snapshot: DecisionSnapshot, nowMillis: Long): NumberCorrection? {
        val country = countryDetector.detect(call.dialedNumber, snapshot.defaultRegion)
        if (country == CountryVerdict.Emergency) return null
        if (snapshot.passTokens.any { it.matches(call, nowMillis) }) return null
        val destination = (country as? CountryVerdict.Country)?.regionCode
        return (correctionOutcome(call, destination, snapshot) as? CorrectionOutcome.Missed)?.correction
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
