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
     * This situation's once-per-arrival key ([DataVerdict.arrivalKey]) — the
     * same key the roaming-watch notification dedupes on. The card carries it
     * so the "Ignore for this trip" action can record a dismiss for exactly
     * this arrival, matched against the live card before it writes.
     */
    val arrivalKey: String

    /**
     * The data SIM is roaming with data roaming on and no rule allows it — the
     * "should I make a rule?" moment. [localSims] are the other active SIMs
     * homed here (switch-to candidates); [widenGroupIds] are the shipped or
     * custom groups that contain [country], offered as one-tap ways to widen a
     * roaming-OK rule beyond the single country.
     */
    data class Roaming(
        override val dataSim: ActiveSim,
        override val country: String,
        override val arrivalKey: String,
        val localSims: List<ActiveSim> = emptyList(),
        val widenGroupIds: List<String> = emptyList(),
    ) : DataTriage

    /**
     * No mobile data at all (data roaming off on a non-local SIM): a
     * connectivity problem, not a billing one, so the only honest resolution
     * is Change SIMs — there is no rule to make. [switchTo] names active
     * local SIMs; [enableFirst] disabled local profiles to enable first.
     */
    data class NoData(
        override val dataSim: ActiveSim,
        override val country: String,
        override val arrivalKey: String,
        val switchTo: List<ActiveSim> = emptyList(),
        val enableFirst: List<RegisteredSim> = emptyList(),
    ) : DataTriage

    /**
     * A rule wants [wantedSim] carrying data here, but [dataSim] is: the
     * resolution is Change SIMs (switch the data SIM), not a new rule. Raised by
     * [DataExpectation.UseSimForData] (a named SIM) or
     * [DataExpectation.UseLocalSimForData] (the unique local SIM).
     */
    data class WrongSim(
        override val dataSim: ActiveSim,
        override val country: String,
        override val arrivalKey: String,
        val wantedSim: ActiveSim,
    ) : DataTriage
}

/**
 * The current triage situation, or null when there is nothing to triage — a
 * rule already covers it, the data SIM is home, the country is unknown (the
 * same cases [evaluateDataRules] resolves to [DataVerdict.Silent]), or the
 * user dismissed this exact arrival for the trip.
 *
 * [dismissedKeys] are the persisted "Ignore for this trip" marks: when the
 * current arrival's key is among them the card stays hidden until the arrival
 * ends and the key clears (same country/SIM-change staleness the notification
 * mark uses), so a deliberate dismiss quiets the card without recording a rule.
 */
fun triageFor(
    book: DataRuleBook,
    snapshot: DataSnapshot,
    dismissedKeys: Set<String> = emptySet(),
): DataTriage? {
    val verdict = evaluateDataRules(book, snapshot)
    val key = verdict.arrivalKey() ?: return null
    if (key in dismissedKeys) return null
    return when (verdict) {
        // Unreachable: a non-null arrivalKey means the verdict isn't Silent.
        DataVerdict.Silent -> null
        is DataVerdict.RoamingWarning -> DataTriage.Roaming(
            dataSim = verdict.dataSim,
            country = verdict.country,
            arrivalKey = key,
            localSims = verdict.localSims,
            widenGroupIds = groupsContaining(verdict.country, snapshot.customGroups),
        )
        is DataVerdict.NoDataNudge -> DataTriage.NoData(
            dataSim = verdict.dataSim,
            country = verdict.country,
            arrivalKey = key,
            switchTo = verdict.switchTo,
            enableFirst = verdict.enableFirst,
        )
        is DataVerdict.WrongDataSim -> DataTriage.WrongSim(
            dataSim = verdict.dataSim,
            country = verdict.country,
            arrivalKey = key,
            wantedSim = verdict.wantedSim,
        )
    }
}

/**
 * The "Use in ⟨place⟩" rule (SPEC "Data rules" → Triage): roaming is expected
 * here on the SIM now carrying data. Scoped to that SIM (the current one, per
 * SPEC) and matched on the current [country], or on [groupId] when the user
 * widens it to a group that contains the country. Assigned an id at write time
 * like any new rule. (Distinct from "Ignore for this trip," which records no
 * rule at all — see the dismiss mark.)
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
 * The user-configurable groups that contain [country], in definition order.
 * [customGroups] is id → member regions (upper-case), as carried by [DataSnapshot].
 */
private fun groupsContaining(country: String, customGroups: Map<String, List<String>>): List<String> {
    val region = country.trim().uppercase()
    return customGroups.filterValues { region in it.map { m -> m.uppercase() } }.keys.toList()
}
