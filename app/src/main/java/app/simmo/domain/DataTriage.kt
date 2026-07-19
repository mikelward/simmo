package app.simmo.domain

/**
 * The live data situation the triage card surfaces (SPEC "Data rules" →
 * Triage): what the data rules screen leads with while a situation exists —
 * which SIM is carrying data, where, and what the honest resolution is. It is
 * the in-app face of the same evaluation the roaming-watch notification uses
 * ([evaluateDataRules]), so the two never disagree; unlike the notification it
 * is recomputed live and is not gated by the once-per-arrival mark, so it
 * shows every time the screen is open. A rule that already covers the
 * situation makes the verdict [DataVerdict.Silent], and there is then no card.
 */
sealed interface DataTriage {
    val dataSim: ActiveSim
    val country: String

    /**
     * The data SIM is roaming with data roaming on and no rule allows it — the
     * "should I make a rule?" moment. [localSims] are the other active SIMs
     * homed here (switch-to candidates); [widenGroupIds] are the shipped or
     * custom groups that contain [country], offered as one-tap ways to widen a
     * "This is OK" rule beyond the single country.
     */
    data class Roaming(
        override val dataSim: ActiveSim,
        override val country: String,
        val localSims: List<ActiveSim> = emptyList(),
        val widenGroupIds: List<String> = emptyList(),
    ) : DataTriage

    /**
     * No mobile data at all (data roaming off on a non-local SIM): a
     * connectivity problem, not a billing one, so the only honest resolution
     * is System settings — there is no rule to make. [switchTo] names active
     * local SIMs; [enableFirst] disabled local profiles to enable first.
     */
    data class NoData(
        override val dataSim: ActiveSim,
        override val country: String,
        val switchTo: List<ActiveSim> = emptyList(),
        val enableFirst: List<RegisteredSim> = emptyList(),
    ) : DataTriage

    /**
     * A [DataExpectation.UseSimForData] rule wants [wantedSim] carrying data
     * here, but [dataSim] is: the resolution is System settings (switch the
     * data SIM), not a new rule.
     */
    data class WrongSim(
        override val dataSim: ActiveSim,
        override val country: String,
        val wantedSim: ActiveSim,
    ) : DataTriage
}

/**
 * The current triage situation, or null when there is nothing to triage — a
 * rule already covers it, the data SIM is home, or the country is unknown
 * (the same cases [evaluateDataRules] resolves to [DataVerdict.Silent]).
 */
fun triageFor(book: DataRuleBook, snapshot: DataSnapshot): DataTriage? =
    when (val verdict = evaluateDataRules(book, snapshot)) {
        DataVerdict.Silent -> null
        is DataVerdict.RoamingWarning -> DataTriage.Roaming(
            dataSim = verdict.dataSim,
            country = verdict.country,
            localSims = verdict.localSims,
            widenGroupIds = groupsContaining(verdict.country, snapshot.customGroups),
        )
        is DataVerdict.NoDataNudge -> DataTriage.NoData(
            dataSim = verdict.dataSim,
            country = verdict.country,
            switchTo = verdict.switchTo,
            enableFirst = verdict.enableFirst,
        )
        is DataVerdict.WrongDataSim -> DataTriage.WrongSim(
            dataSim = verdict.dataSim,
            country = verdict.country,
            wantedSim = verdict.wantedSim,
        )
    }

/**
 * The "This is OK" rule (SPEC): roaming is expected here on the SIM now
 * carrying data. Scoped to that SIM (the current one, per SPEC) and matched on
 * the current [country], or on [groupId] when the user widens it to a group
 * that contains the country. Assigned an id at write time like any new rule.
 */
fun roamingOkRule(country: String, groupId: String?, dataSim: SimRef): DataRule {
    val matcher =
        if (groupId != null) destinationMatcher(emptyList(), listOf(groupId))
        else destinationMatcher(listOf(country), emptyList())
    return DataRule(
        matcher = matcher,
        expectation = DataExpectation.RoamingOk(DataSimScope.Sims(listOf(dataSim))),
    )
}

/**
 * The shipped and custom groups that contain [country], for the widen
 * suggestions — shipped first (stable order), then custom in definition
 * order. [customGroups] is id → member regions (upper-case), as carried by
 * [DataSnapshot].
 */
private fun groupsContaining(country: String, customGroups: Map<String, List<String>>): List<String> {
    val region = country.trim().uppercase()
    val shipped = CountryGroups.allIds().filter { region in CountryGroups.members(it).map { m -> m.uppercase() } }
    val custom = customGroups.filterValues { region in it.map { m -> m.uppercase() } }.keys
    return shipped + custom
}
