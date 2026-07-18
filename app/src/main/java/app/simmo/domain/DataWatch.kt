package app.simmo.domain

/**
 * Everything the roaming watch reads, assembled off the decision path like
 * [DecisionSnapshot]. The watch never runs on the call-decision path at all —
 * it evaluates on telephony refreshes and wake-ups (SPEC "Data-roaming
 * visibility"), and every field comes from reads `READ_PHONE_STATE` covers.
 */
data class DataSnapshot(
    /** Current network country (ISO region, any case); blank when unknown. */
    val networkCountry: String,
    val activeSims: List<ActiveSim>,
    /**
     * The subscription actually carrying data right now — the platform's
     * active data subscription, or the default data subscription when no
     * temporary override is in effect. The platform layer coalesces the two;
     * the watch only judges the one that is live.
     */
    val dataSubscriptionId: Int,
    /** Subscriptions whose current network is roaming (the carrier-defined flag). */
    val roamingSubscriptionIds: Set<Int> = emptySet(),
    /** Subscriptions whose per-SIM "data roaming" setting is on. */
    val dataRoamingEnabledSubscriptionIds: Set<Int> = emptySet(),
    /** User-defined group id → member regions, as in [DecisionSnapshot.customGroups]. */
    val customGroups: Map<String, List<String>> = emptyMap(),
    /** The SIM registry, for naming a disabled local profile in the no-data nudge. */
    val registeredSims: List<RegisteredSim> = emptyList(),
)

/**
 * What the roaming watch concludes. Surfacing is the platform layer's job —
 * the "Using data roaming" notification and the triage card — as is the
 * once-per-SIM-and-country arrival dedupe (SPEC "Data rules").
 */
sealed interface DataVerdict {

    /** Nothing to say: no data SIM, unknown country, home network, or a rule said OK. */
    data object Silent : DataVerdict

    /**
     * The data SIM is set up to use roaming data here and no rule allows it.
     * [localSims] are the other active SIMs homed in [country] — the
     * switch-to candidates the triage card names.
     */
    data class RoamingWarning(
        val dataSim: ActiveSim,
        val country: String,
        val localSims: List<ActiveSim> = emptyList(),
    ) : DataVerdict

    /**
     * A [DataExpectation.UseSimForData] rule wants [wantedSim] carrying data
     * here, but [dataSim] is — the arrival mismatch, raised before roaming
     * data flows rather than after.
     */
    data class WrongDataSim(
        val dataSim: ActiveSim,
        val wantedSim: ActiveSim,
        val country: String,
    ) : DataVerdict

    /**
     * No mobile data at all: data roaming is off and the data SIM isn't
     * local. Fires rule-less — it is a connectivity problem, not a billing
     * one — and only when there is a SIM to offer (SPEC "Data rules"):
     * [switchTo] names active local SIMs, [enableFirst] registered but
     * currently disabled local profiles (the registry keeps last-known home
     * countries precisely so these can be recognized).
     */
    data class NoDataNudge(
        val dataSim: ActiveSim,
        val country: String,
        val switchTo: List<ActiveSim> = emptyList(),
        val enableFirst: List<RegisteredSim> = emptyList(),
    ) : DataVerdict
}

/**
 * The pure roaming-watch evaluation: `(rules, snapshot) → verdict` (SPEC
 * "Data rules"). Rules are evaluated in order; the first applicable rule
 * decides. A rule that cannot act — disabled by the user, wanted SIM
 * unresolvable, roaming-OK scope not covering the data SIM — is skipped and
 * evaluation continues.
 *
 * When no rule applies, the defaults: a SIM on its home network never warns;
 * a roaming data SIM with data roaming on warns; a non-local data SIM with
 * data roaming off has no data at all and nudges toward a local SIM when one
 * exists to offer.
 */
fun evaluateDataRules(book: DataRuleBook, snapshot: DataSnapshot): DataVerdict {
    val country = snapshot.networkCountry.trim().uppercase()
    if (country.isEmpty()) return DataVerdict.Silent
    val dataSim = snapshot.activeSims
        .firstOrNull { it.subscriptionId == snapshot.dataSubscriptionId }
        ?: return DataVerdict.Silent

    for (rule in book.rules) {
        if (!rule.enabled) continue
        if (!rule.matcher.matchesRegion(country, snapshot.customGroups)) continue
        when (val expectation = rule.expectation) {
            is DataExpectation.UseSimForData ->
                when (val resolved = resolveSim(expectation.sim, snapshot.activeSims)) {
                    is SimResolution.Active ->
                        return if (resolved.sim.subscriptionId == dataSim.subscriptionId) {
                            DataVerdict.Silent
                        } else {
                            DataVerdict.WrongDataSim(dataSim, resolved.sim, country)
                        }
                    // Disabled or ambiguous: skip, like a calling rule whose
                    // SIM can't act right now.
                    SimResolution.Inactive, is SimResolution.Ambiguous -> Unit
                }

            is DataExpectation.RoamingOk ->
                if (expectation.scope.covers(dataSim, rule.matcher, snapshot)) {
                    return DataVerdict.Silent
                }
            // A scope miss falls through: the US SIM roaming in the EU must
            // still reach the default warning below.

            // "Always warn" pins the outcome to the defaults below — no
            // later RoamingOk can silence them. The defaults still keep the
            // home-network and no-data shapes honest, so the guard never
            // fabricates a roaming warning where no roaming data can flow.
            DataExpectation.AlwaysWarn -> return defaultVerdict(dataSim, country, snapshot)
        }
    }
    return defaultVerdict(dataSim, country, snapshot)
}

/** The no-rule behavior; also what [DataExpectation.AlwaysWarn] pins. */
private fun defaultVerdict(dataSim: ActiveSim, country: String, snapshot: DataSnapshot): DataVerdict {
    // Home network never warns; the platform's roaming flag is the authority
    // (carrier config decides what counts as roaming, e.g. domestic roaming).
    if (dataSim.subscriptionId !in snapshot.roamingSubscriptionIds) return DataVerdict.Silent
    val localSims = snapshot.activeSims.filter {
        it.subscriptionId != dataSim.subscriptionId && it.countryIso.equals(country, ignoreCase = true)
    }
    if (dataSim.subscriptionId !in snapshot.dataRoamingEnabledSubscriptionIds) {
        // Roaming network but data roaming off: no roaming data can flow, so
        // the user simply has no mobile data here. Nudge toward a local SIM —
        // active, or a disabled registered profile to enable first — and stay
        // silent when there is nothing to offer.
        val activeIds = snapshot.activeSims.map { it.subscriptionId }.toSet()
        val enableFirst = snapshot.registeredSims.filter {
            it.subscriptionId !in activeIds && it.countryIso.equals(country, ignoreCase = true)
        }
        return if (localSims.isNotEmpty() || enableFirst.isNotEmpty()) {
            DataVerdict.NoDataNudge(dataSim, country, localSims, enableFirst)
        } else {
            DataVerdict.Silent
        }
    }
    return DataVerdict.RoamingWarning(dataSim, country, localSims)
}

private fun DataSimScope.covers(
    dataSim: ActiveSim,
    matcher: RuleMatcher,
    snapshot: DataSnapshot,
): Boolean = when (this) {
    DataSimScope.AnySim -> true
    DataSimScope.HomedInMatchedCountries ->
        dataSim.countryIso.isNotBlank() &&
            matcher.matchesRegion(dataSim.countryIso.trim().uppercase(), snapshot.customGroups)
    is DataSimScope.Sims -> sims.any { ref ->
        (resolveSim(ref, snapshot.activeSims) as? SimResolution.Active)
            ?.sim?.subscriptionId == dataSim.subscriptionId
    }
}
