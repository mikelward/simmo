package app.simmo.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What a rule matches (SPEC "Rules"). Persisted — the [SerialName]
 * discriminators are the storage format and must stay stable.
 */
@Serializable
sealed interface RuleMatcher {
    /** A destination country (ISO region; the UI shows its calling code too). */
    @Serializable
    @SerialName("country")
    data class Country(val regionCode: String) : RuleMatcher

    /** Any destination, including undetermined ones — used by the defaults. */
    @Serializable
    @SerialName("any")
    data object AnyDestination : RuleMatcher
}

/** What a rule does with a matching call (SPEC "Rules"). Persisted. */
@Serializable
sealed interface RuleAction {
    /** Place the call on a specific SIM, resolved via [resolveSim]. */
    @Serializable
    @SerialName("useSim")
    data class UseSim(val sim: SimRef) : RuleAction

    /**
     * Place the call on the SIM whose home country matches the destination;
     * applies only when exactly one active SIM matches.
     */
    @Serializable
    @SerialName("matchingCountrySim")
    data object UseMatchingCountrySim : RuleAction

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

    /** No change: the call proceeds exactly as the system would have placed it. */
    @Serializable
    @SerialName("systemDefault")
    data object SystemDefault : RuleAction
}

@Serializable
data class Rule(
    val matcher: RuleMatcher,
    val action: RuleAction,
)

/**
 * The complete rule set: an ordered list, evaluated top to bottom; the first
 * *applicable* rule decides the call (SPEC "Rules"). Rules that cannot act —
 * disabled SIM, unreachable hand-off target, UI needed in a non-interactive
 * context, ambiguous country match — are skipped and evaluation continues.
 * A fresh install starts with [defaultRules]; they are ordinary rules the
 * user can reorder or delete.
 */
@Serializable
data class RuleBook(
    val rules: List<Rule> = defaultRules(),
) {
    fun withRuleAdded(rule: Rule): RuleBook =
        // New rules land above the preseeded defaults' natural home: the top.
        copy(rules = listOf(rule) + rules)

    fun withRuleReplaced(index: Int, rule: Rule): RuleBook =
        copy(rules = rules.mapIndexed { i, existing -> if (i == index) rule else existing })

    fun withRuleRemoved(index: Int): RuleBook =
        copy(rules = rules.filterIndexed { i, _ -> i != index })

    /** Reorder for drag-and-drop; out-of-range indices are a no-op. */
    fun withRuleMoved(fromIndex: Int, toIndex: Int): RuleBook {
        if (fromIndex == toIndex) return this
        if (fromIndex !in rules.indices || toIndex !in rules.indices) return this
        val reordered = rules.toMutableList()
        val rule = reordered.removeAt(fromIndex)
        reordered.add(toIndex, rule)
        return copy(rules = reordered)
    }

    companion object {
        /**
         * The preseeded low-priority defaults: use the SIM whose home country
         * matches the destination, else leave the call to the system.
         */
        fun defaultRules(): List<Rule> = listOf(
            Rule(RuleMatcher.AnyDestination, RuleAction.UseMatchingCountrySim),
            Rule(RuleMatcher.AnyDestination, RuleAction.SystemDefault),
        )
    }
}
