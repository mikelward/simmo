package app.simmo.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What a rule does with a matching call (SPEC "Rules"). Persisted — the
 * [SerialName] discriminators are the storage format and must stay stable.
 */
@Serializable
sealed interface RuleAction {
    /** Place the call on a specific SIM, resolved via [resolveSim]. */
    @Serializable
    @SerialName("useSim")
    data class UseSim(val sim: SimRef) : RuleAction

    /** Hand the call off to another calling app (SPEC "Hand-off to another app"). */
    @Serializable
    sealed interface HandOff : RuleAction {
        /** The app registered a call-capable phone account; works non-interactively. */
        @Serializable
        @SerialName("handOffAccount")
        data class ViaPhoneAccount(val account: PhoneAccountRef) : HandOff

        /** Cancel and forward the number via the app's dial intent; needs interactive UI. */
        @Serializable
        @SerialName("handOffIntent")
        data class ViaDialIntent(val packageName: String) : HandOff
    }

    /** Show Simmo's chooser for this call. */
    @Serializable
    @SerialName("ask")
    data object Ask : RuleAction
}

/**
 * The complete rule set: at most one rule per ISO region, plus the fallback
 * for unmatched or undetermined destinations. Evaluation is total.
 */
@Serializable
data class RuleBook(
    val countryRules: Map<String, RuleAction> = emptyMap(),
    val fallback: RuleAction = RuleAction.Ask,
) {
    fun actionFor(regionCode: String?): RuleAction =
        regionCode?.let { countryRules[it] } ?: fallback
}
