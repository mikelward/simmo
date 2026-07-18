package app.simmo.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What a rule matches (SPEC "Calling rules"). Persisted — the [SerialName]
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
 * Whether this matcher matches [region] (ISO code, any case; null for an
 * undetermined destination — only [RuleMatcher.AnyDestination] matches then).
 * Group membership resolves at evaluation time from the in-memory tables —
 * built-ins from the static table, the user's custom groups from
 * [customGroups] — so one stored group entry tracks membership across app
 * updates and edits alike. Shared by the calling-rule engine (matching the
 * call's destination) and the roaming watch (matching the current network
 * country).
 */
fun RuleMatcher.matchesRegion(region: String?, customGroups: Map<String, List<String>>): Boolean =
    when (this) {
        RuleMatcher.AnyDestination -> true
        is RuleMatcher.Country -> region != null && regionCode.equals(region, ignoreCase = true)
        is RuleMatcher.Countries -> region != null &&
            (
                regionCodes.any { it.equals(region, ignoreCase = true) } ||
                    groupIds.any { id ->
                        groupMembers(id, customGroups).any { it.equals(region, ignoreCase = true) }
                    }
                )
    }

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

/** What a rule does with a matching call (SPEC "Calling rules"). Persisted. */
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
        /**
         * Redirect to another call-capable phone account the user enabled — a
         * SIP provider or another calling app registered with Telecom. Same
         * redirect mechanism as a SIM, so it works non-interactively too.
         * [label] is the account's display name captured when the rule was
         * created (the rule must render even while the account is gone);
         * blank for rules stored before the field existed.
         */
        @Serializable
        @SerialName("handOffAccount")
        data class ViaPhoneAccount(
            val account: PhoneAccountRef,
            val label: String = "",
        ) : HandOff

        /**
         * Cancel the carrier call and forward the dialed number to a calling
         * app's number-carrying deep link (e.g. Google Voice, Teams). Needs an
         * interactive context; skipped hands-free. The number is normalized to
         * E.164 off the fast path and only fired when the target app is installed.
         */
        @Serializable
        @SerialName("handOffIntent")
        data class ViaDialIntent(val app: DialHandoffApp) : HandOff

        /**
         * Cancel and place the call to a *contact* via the app's per-contact
         * call intent (e.g. WhatsApp), when the dialed number belongs to a
         * contact reachable on that app. Applies only when the dialed number is
         * such a contact (resolved from the warm contact index) and only in an
         * interactive context; otherwise the rule is skipped.
         */
        @Serializable
        @SerialName("handOffContactApp")
        data class ViaContactApp(val app: ContactCallApp) : HandOff
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
    /**
     * User toggle: a disabled rule is kept in place (order, target, everything)
     * but skipped during evaluation, so it can be turned back on without being
     * rebuilt. Distinct from the *automatic* skips (disabled SIM, ambiguous
     * re-bind, …), which the rule can't control. Defaults true so state written
     * by older versions (no field) reads back as enabled.
     */
    val enabled: Boolean = true,
)

/**
 * The complete rule set: an ordered list, evaluated top to bottom; the first
 * *applicable* rule decides the call (SPEC "Calling rules"). Rules that cannot act —
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
