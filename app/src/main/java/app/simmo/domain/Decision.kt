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
 */
data class PassToken(
    val dialedNumber: String,
    val account: PhoneAccountRef,
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
    /** Enabled call-capable phone accounts of third-party (hand-off) apps. */
    val handOffAccounts: Set<PhoneAccountRef> = emptySet(),
    /** Installed apps that can receive a dial-intent hand-off. */
    val handOffApps: Set<String> = emptySet(),
    /**
     * Dialed number → contact, for app-to-app (per-contact) hand-off. Built off
     * the decision path; the engine only reads it. Empty without READ_CONTACTS.
     */
    val contacts: ContactNumberIndex = ContactNumberIndex.EMPTY,
)

/** The one answer per call; total, and never a silent drop (SPEC invariants). */
sealed interface Verdict {
    data class Proceed(
        val reason: ProceedReason,
        /** Set when [reason] is [ProceedReason.PASS_TOKEN]; the platform layer removes it. */
        val consumedToken: PassToken? = null,
    ) : Verdict

    /** Redirect to a SIM's or a VoIP app's phone account. */
    data class RedirectToAccount(val account: PhoneAccountRef) : Verdict

    /**
     * Cancel the carrier call and forward the dialed number to a calling app by
     * launching its number-carrying deep link [uri], scoped to [packageName]
     * (e.g. Google Voice, Teams). Only produced when the number normalized to
     * E.164 and the target app is installed.
     */
    data class ForwardToApp(val packageName: String, val uri: String) : Verdict

    /**
     * Cancel the carrier call and place it to a contact via an app's per-contact
     * call intent: `ACTION_VIEW` the [dataRowId] `ContactsContract.Data` row with
     * its [mimeType], scoped to [packageName] (e.g. WhatsApp). The MIME type is
     * what picks the app's call action out of the contact row — without it the
     * intent resolves to the generic contact viewer instead of the call. Only
     * produced when the dialed number resolved to a contact reachable on that app.
     */
    data class ForwardToContactApp(
        val packageName: String,
        val mimeType: String,
        val dataRowId: Long,
    ) : Verdict

    /**
     * Cancel the carrier call and open Simmo's chooser. [skippedInactiveSims]
     * carries the SIMs that higher-priority rules wanted but which are
     * currently disabled, so the chooser can offer enabling them
     * (SPEC "Disabled-SIM assist").
     */
    data class OpenChooser(val skippedInactiveSims: List<SimRef> = emptyList()) : Verdict
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
        val skippedInactiveSims = mutableListOf<SimRef>()

        for (rule in snapshot.rules.rules) {
            if (!rule.matcher.matches(destination)) continue
            when (val action = rule.action) {
                is RuleAction.UseSim -> when (val resolved = resolveSim(action.sim, snapshot.activeSims)) {
                    is SimResolution.Active ->
                        return redirectOrPassThrough(call, resolved.sim.phoneAccount)
                    // Disabled or ambiguous: skip, but remember disabled SIMs
                    // so the chooser can offer enabling them.
                    SimResolution.Inactive -> skippedInactiveSims += action.sim
                    is SimResolution.Ambiguous -> Unit
                }

                RuleAction.UseMatchingCountrySim -> {
                    val matching = destination?.let { region ->
                        snapshot.activeSims.filter { it.countryIso.equals(region, ignoreCase = true) }
                    }
                    if (matching?.size == 1) {
                        return redirectOrPassThrough(call, matching.single().phoneAccount)
                    }
                }

                is RuleAction.HandOff.ViaPhoneAccount ->
                    if (action.account in snapshot.handOffAccounts) {
                        return redirectOrPassThrough(call, action.account)
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
                        normalizeToE164(call.dialedNumber, snapshot.defaultRegion)?.let { e164 ->
                            return Verdict.ForwardToApp(action.app.packageName, action.app.launchUri(e164))
                        }
                    }

                is RuleAction.HandOff.ViaContactApp ->
                    // Applies only when the dialed number is a contact reachable
                    // on the app (a call-action row in the warm index), and only
                    // interactively (cancel-and-forward shows the app's UI).
                    // Otherwise skip — a non-contact number falls through to the
                    // lower rules rather than stranding the call.
                    if (call.interactive) {
                        snapshot.contacts.lookup(call.dialedNumber, snapshot.defaultRegion)
                            ?.callActions?.get(action.app)
                            ?.let { dataRowId ->
                                return Verdict.ForwardToContactApp(
                                    action.app.packageName,
                                    action.app.dataMimeType,
                                    dataRowId,
                                )
                            }
                    }

                RuleAction.Ask ->
                    // Applicable only when the chooser has at least one target
                    // to offer. With no active SIMs (READ_PHONE_STATE revoked,
                    // degraded telephony read), canceling would strand the
                    // call behind a chooser that can only cancel — skip
                    // instead, degrading toward "proceed unmodified".
                    if (call.interactive && snapshot.activeSims.isNotEmpty()) {
                        return Verdict.OpenChooser(skippedInactiveSims.toList())
                    }

                RuleAction.SystemDefault ->
                    return Verdict.Proceed(ProceedReason.SYSTEM_DEFAULT)
            }
        }
        return Verdict.Proceed(ProceedReason.NO_APPLICABLE_RULE)
    }

    private fun RuleMatcher.matches(destination: String?): Boolean = when (this) {
        RuleMatcher.AnyDestination -> true
        is RuleMatcher.Country -> destination != null && regionCode.equals(destination, ignoreCase = true)
        is RuleMatcher.Countries -> destination != null &&
            (
                regionCodes.any { it.equals(destination, ignoreCase = true) } ||
                    // Group membership resolves from the in-memory table at
                    // decision time, so one stored "EU/EEA" entry tracks
                    // membership across app updates.
                    groupIds.any { id ->
                        CountryGroups.members(id).any { it.equals(destination, ignoreCase = true) }
                    }
                )
    }

    private fun redirectOrPassThrough(call: PlacedCall, target: PhoneAccountRef): Verdict =
        if (call.currentAccount == target) {
            Verdict.Proceed(ProceedReason.ALREADY_ON_TARGET)
        } else {
            Verdict.RedirectToAccount(target)
        }

    private fun PassToken.matches(call: PlacedCall, nowMillis: Long): Boolean =
        dialedNumber == call.dialedNumber &&
            account == call.currentAccount &&
            expiresAtMillis > nowMillis
}
