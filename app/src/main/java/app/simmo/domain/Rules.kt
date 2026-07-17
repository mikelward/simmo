package app.simmo.domain

/** What a rule does with a matching call (SPEC "Rules"). */
sealed interface RuleAction {
    /** Place the call on a specific SIM, resolved via [resolveSim]. */
    data class UseSim(val sim: SimRef) : RuleAction

    /** Hand the call off to another calling app (SPEC "Hand-off to another app"). */
    sealed interface HandOff : RuleAction {
        /** The app registered a call-capable phone account; works non-interactively. */
        data class ViaPhoneAccount(val account: PhoneAccountRef) : HandOff

        /** Cancel and forward the number via the app's dial intent; needs interactive UI. */
        data class ViaDialIntent(val packageName: String) : HandOff
    }

    /** Show Simmo's chooser for this call. */
    data object Ask : RuleAction
}

/**
 * The complete rule set: at most one rule per ISO region, plus the fallback
 * for unmatched or undetermined destinations. Evaluation is total.
 */
data class RuleBook(
    val countryRules: Map<String, RuleAction> = emptyMap(),
    val fallback: RuleAction = RuleAction.Ask,
) {
    fun actionFor(regionCode: String?): RuleAction =
        regionCode?.let { countryRules[it] } ?: fallback
}
