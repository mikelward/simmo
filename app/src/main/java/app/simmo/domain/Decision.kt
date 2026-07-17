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
 * or enable flow re-places a call, the new call matches a live token and passes
 * through unmodified. The platform layer removes the consumed token.
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

    /** Cancel the carrier call and forward the number to the app's dial intent. */
    data class ForwardToApp(val packageName: String) : Verdict

    /** Cancel the carrier call and open Simmo's chooser. */
    data class OpenChooser(val mode: ChooserMode) : Verdict
}

enum class ProceedReason {
    EMERGENCY,
    PASS_TOKEN,
    ALREADY_ON_TARGET,

    /** The verdict needed UI but the call context forbids it; never drop the call. */
    NON_INTERACTIVE_DEGRADE,
}

sealed interface ChooserMode {
    /** No rule matched (or the rule says Ask): pick a target for this call. */
    data object Ask : ChooserMode

    /** The rule's SIM is not active: explain and deep-link to SIM settings. */
    data class EnableSim(val wanted: SimRef) : ChooserMode

    /** Carrier re-binding was ambiguous: pick which active SIM the rule means. */
    data class PickAmong(val wanted: SimRef, val candidates: List<ActiveSim>) : ChooserMode
}

/**
 * The pure decision function: `(call, snapshot, now) → verdict`. All product
 * routing logic lives here so it is testable without Android.
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

        val action = snapshot.rules.actionFor((country as? CountryVerdict.Country)?.regionCode)
        return when (action) {
            RuleAction.Ask -> interactiveOnly(call) { Verdict.OpenChooser(ChooserMode.Ask) }

            // A hand-off target that is no longer reachable (app uninstalled,
            // account disabled) is never routed to blindly: fall back to the
            // chooser, or proceed when UI is forbidden — never a failed call.
            is RuleAction.HandOff.ViaPhoneAccount ->
                if (action.account in snapshot.handOffAccounts) {
                    redirectOrPassThrough(call, action.account)
                } else {
                    interactiveOnly(call) { Verdict.OpenChooser(ChooserMode.Ask) }
                }

            is RuleAction.HandOff.ViaDialIntent ->
                interactiveOnly(call) {
                    if (action.packageName in snapshot.handOffApps) {
                        Verdict.ForwardToApp(action.packageName)
                    } else {
                        Verdict.OpenChooser(ChooserMode.Ask)
                    }
                }

            is RuleAction.UseSim -> when (val resolved = resolveSim(action.sim, snapshot.activeSims)) {
                is SimResolution.Active ->
                    redirectOrPassThrough(call, resolved.sim.phoneAccount)

                SimResolution.Inactive ->
                    interactiveOnly(call) { Verdict.OpenChooser(ChooserMode.EnableSim(action.sim)) }

                is SimResolution.Ambiguous ->
                    interactiveOnly(call) {
                        Verdict.OpenChooser(ChooserMode.PickAmong(action.sim, resolved.candidates))
                    }
            }
        }
    }

    private fun redirectOrPassThrough(call: PlacedCall, target: PhoneAccountRef): Verdict =
        if (call.currentAccount == target) {
            Verdict.Proceed(ProceedReason.ALREADY_ON_TARGET)
        } else {
            Verdict.RedirectToAccount(target)
        }

    /** SPEC "The chooser": anything needing UI degrades to Proceed when UI is forbidden. */
    private inline fun interactiveOnly(call: PlacedCall, verdict: () -> Verdict): Verdict =
        if (call.interactive) verdict() else Verdict.Proceed(ProceedReason.NON_INTERACTIVE_DEGRADE)

    private fun PassToken.matches(call: PlacedCall, nowMillis: Long): Boolean =
        dialedNumber == call.dialedNumber &&
            account == call.currentAccount &&
            expiresAtMillis > nowMillis
}
