package app.simmo.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What a rule matches (SPEC "Rules"). Persisted — the [SerialName]
 * discriminators are the storage format and must stay stable.
 */
@Serializable
sealed interface RuleMatcher {
    /**
     * A single destination country (ISO region). The original stored form,
     * still written for one-country rules so state stays readable by older
     * app versions; multi-country rules use [Countries].
     */
    @Serializable
    @SerialName("country")
    data class Country(val regionCode: String) : RuleMatcher

    /**
     * A set of destinations: individual countries (ISO regions) and/or
     * [CountryGroups] ids, any of which matches. A group is stored by id and
     * resolved to its member regions at decision time, so one "EU/EEA" entry
     * tracks membership across app updates — and countries can sit alongside
     * it (e.g. EU/EEA + UK when a plan covers the UK too).
     */
    @Serializable
    @SerialName("countries")
    data class Countries(
        val regionCodes: List<String> = emptyList(),
        val groupIds: List<String> = emptyList(),
    ) : RuleMatcher

    /** Any destination, including undetermined ones — used by the defaults. */
    @Serializable
    @SerialName("any")
    data object AnyDestination : RuleMatcher
}

/** The individually picked regions, in display order; group members are not expanded. */
fun RuleMatcher.regionCodes(): List<String> = when (this) {
    RuleMatcher.AnyDestination -> emptyList()
    is RuleMatcher.Country -> listOf(regionCode)
    is RuleMatcher.Countries -> regionCodes
}

/** The group ids a matcher targets; empty unless it is a [RuleMatcher.Countries]. */
fun RuleMatcher.groupIds(): List<String> = (this as? RuleMatcher.Countries)?.groupIds.orEmpty()

/**
 * The matcher over hand-picked [regionCodes] and/or [groupIds] (deduped,
 * order kept; at least one required). Group-less matchers keep the forms
 * [countryMatcher] chooses, including the legacy single-country one.
 */
fun destinationMatcher(regionCodes: List<String>, groupIds: List<String>): RuleMatcher {
    val groups = groupIds.distinct()
    if (groups.isEmpty()) return countryMatcher(regionCodes)
    return RuleMatcher.Countries(regionCodes.distinctBy { it.uppercase() }, groups)
}

/**
 * The country matcher over [regionCodes] (order kept, case-insensitive
 * duplicates dropped). A single country keeps the legacy [RuleMatcher.Country]
 * stored form — see its KDoc.
 */
fun countryMatcher(regionCodes: List<String>): RuleMatcher {
    val distinct = regionCodes.distinctBy { it.uppercase() }
    require(distinct.isNotEmpty()) { "A country matcher needs at least one region" }
    return distinct.singleOrNull()?.let { RuleMatcher.Country(it) }
        ?: RuleMatcher.Countries(distinct)
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

    /** Insert at [index] (clamped), for placements other than the top. */
    fun withRuleInserted(index: Int, rule: Rule): RuleBook {
        val at = index.coerceIn(0, rules.size)
        return copy(rules = rules.take(at) + rule + rules.drop(at))
    }

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

/**
 * Where a rule created from the new-SIM prompt is inserted (SPEC "On SIM
 * change"): above the first rule whose SIM can't act right now (disabled, or
 * needing re-linking), so the suggestion outranks paused rules without
 * jumping ahead of the rules that are actually working. With no paused rule
 * it goes to the top, same as an ordinary add.
 */
fun RuleBook.newSimRuleInsertionIndex(activeSims: List<ActiveSim>): Int {
    val firstPaused = rules.indexOfFirst { rule ->
        val action = rule.action
        action is RuleAction.UseSim && resolveSim(action.sim, activeSims) !is SimResolution.Active
    }
    return if (firstPaused >= 0) firstPaused else 0
}
