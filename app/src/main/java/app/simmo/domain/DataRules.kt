package app.simmo.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A data rule (SPEC "Data rules"): matches *where the user is* — the current
 * network country — where a calling rule matches who they're calling. The
 * matcher type is shared with calling rules so the same picker, groups, and
 * stored forms serve both lists; [RuleMatcher.AnyDestination] reads as
 * "anywhere" here.
 *
 * Data rules are watched, not enforced: Simmo cannot change the data SIM or a
 * SIM's data-roaming setting, so evaluation produces warnings and nudges
 * ([DataVerdict]), never a state change.
 */
@Serializable
data class DataRule(
    val matcher: RuleMatcher,
    val expectation: DataExpectation,
    /** User toggle, same semantics as [Rule.enabled]. */
    val enabled: Boolean = true,
)

/**
 * What should be true of mobile data where the rule matches (SPEC "Data
 * rules"). Persisted — the [SerialName] discriminators are the storage format
 * and must stay stable.
 */
@Serializable
sealed interface DataExpectation {

    /**
     * This SIM should carry data here: any other data SIM warrants a
     * [DataVerdict.WrongDataSim] nudge on arrival — before roaming data
     * flows, not after. A wanted SIM that is currently disabled or ambiguous
     * makes the rule skip, the same skip semantics as calling rules.
     */
    @Serializable
    @SerialName("useSimForData")
    data class UseSimForData(val sim: SimRef) : DataExpectation

    /**
     * Data roaming here is expected — paid for, or free — so no warning, when
     * [scope] covers the SIM actually carrying data. A scope miss skips the
     * rule instead of silencing the watch: "in EU/EEA, roaming OK on
     * Vodafone" stays quiet for the Vodafone SIM but still warns when the US
     * SIM ends up carrying data there.
     */
    @Serializable
    @SerialName("roamingOk")
    data class RoamingOk(val scope: DataSimScope = DataSimScope.AnySim) : DataExpectation

    /**
     * Always warn here: the guard shape, placed above a broader [RoamingOk]
     * the way "Caribbean +1 → Ask" sits above a US calling rule. It stops
     * evaluation so no later rule can silence the default behavior — which
     * still never warns about a SIM on its home network.
     */
    @Serializable
    @SerialName("warn")
    data object AlwaysWarn : DataExpectation
}

/** Which SIMs a [DataExpectation.RoamingOk] covers. Persisted. */
@Serializable
sealed interface DataSimScope {

    /** Whichever SIM is carrying data. */
    @Serializable
    @SerialName("anySim")
    data object AnySim : DataSimScope

    /**
     * SIMs whose home country is among the rule's matched countries — the
     * data-side sibling of the calling side's matching-country action, and
     * the scope the preseeded EU/EEA default needs: an EU-homed SIM roams
     * free in the EU by regulation, a US-homed SIM does not.
     */
    @Serializable
    @SerialName("homedInMatched")
    data object HomedInMatchedCountries : DataSimScope

    /** Specific SIMs, resolved by the usual identity ladder ([resolveSim]). */
    @Serializable
    @SerialName("sims")
    data class Sims(val sims: List<SimRef>) : DataSimScope
}

/**
 * The ordered data-rule list, first match wins (SPEC "Data rules"). A fresh
 * install starts with [defaultDataRules] — an ordinary rule the user can
 * edit, reorder, or delete.
 */
@Serializable
data class DataRuleBook(
    val rules: List<DataRule> = defaultDataRules(),
) {
    // The same editing surface as [RuleBook]: list + editor share the code
    // paths (SPEC "Data rules"), so the book edits mirror the calling ones.

    fun withRuleAdded(rule: DataRule): DataRuleBook =
        // New rules land above the preseeded default's natural home: the top.
        copy(rules = listOf(rule) + rules)

    /** Insert at [index] (clamped), for placements other than the top. */
    fun withRuleInserted(index: Int, rule: DataRule): DataRuleBook {
        val at = index.coerceIn(0, rules.size)
        return copy(rules = rules.take(at) + rule + rules.drop(at))
    }

    fun withRuleReplaced(index: Int, rule: DataRule): DataRuleBook =
        copy(rules = rules.mapIndexed { i, existing -> if (i == index) rule else existing })

    fun withRuleRemoved(index: Int): DataRuleBook =
        copy(rules = rules.filterIndexed { i, _ -> i != index })

    /** Reorder for drag-and-drop; out-of-range indices are a no-op. */
    fun withRuleMoved(fromIndex: Int, toIndex: Int): DataRuleBook {
        if (fromIndex == toIndex) return this
        if (fromIndex !in rules.indices || toIndex !in rules.indices) return this
        val reordered = rules.toMutableList()
        val rule = reordered.removeAt(fromIndex)
        reordered.add(toIndex, rule)
        return copy(rules = reordered)
    }

    companion object {
        /**
         * The preseeded default (maintainer, 2026-07): *when in EU/EEA →
         * roaming OK on SIMs homed in EU/EEA* — regulation-backed
         * roam-like-at-home, so an EU user's first trip inside the zone
         * stays silent instead of warning about roaming that is free by law.
         */
        fun defaultDataRules(): List<DataRule> = listOf(
            DataRule(
                matcher = RuleMatcher.Countries(groupIds = listOf(CountryGroups.EU_EEA)),
                expectation = DataExpectation.RoamingOk(DataSimScope.HomedInMatchedCountries),
            ),
        )
    }
}
