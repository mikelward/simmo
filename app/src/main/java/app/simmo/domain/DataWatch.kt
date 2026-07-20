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
        // Disabled, or soft-deleted awaiting purge: kept in the list, never acts.
        if (!rule.enabled || rule.pendingRemoval) continue
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

/**
 * The once-per-arrival dedupe key (SPEC "Data rules"): the same problem for
 * the same data SIM in the same country never warns twice — telephony
 * refreshes fire constantly — while any change (another country, another
 * data SIM, a different shape of problem) is a new arrival that may warn
 * again. Null for [DataVerdict.Silent]: nothing to post; the caller then
 * clears its stored mark only when [isMarkStale] says the marked arrival is
 * over, so a flapping read in the same place can't re-arm a warning
 * mid-trip. Persistence is the caller's job.
 */
fun DataVerdict.arrivalKey(): String? = when (this) {
    DataVerdict.Silent -> null
    is DataVerdict.RoamingWarning -> "roaming:${dataSim.subscriptionId}:$country"
    is DataVerdict.WrongDataSim ->
        "wrongSim:${dataSim.subscriptionId}:${wantedSim.subscriptionId}:$country"
    // Enable-first and switch-ready are different arrivals: after the user
    // enables the profile without switching data, the follow-up Switch nudge
    // must not be suppressed by the Enable nudge's key (Codex on PR #55).
    // The key also carries WHICH SIM the nudge offers — the first candidate,
    // the same one the notification names — so an offer whose SIM vanished
    // re-claims and the notification refreshes to the surviving alternative
    // instead of naming a SIM that's gone (Codex on PR #55). The shape lives
    // in the kind segment and the country stays last, so [isMarkStale]'s
    // kind:subscription:…:country parse holds for every key. (Two restored
    // disabled profiles can share the invalidated id and collide here; the
    // registry re-binds ids on sight, so the window is a refresh wide.)
    is DataVerdict.NoDataNudge -> {
        val offered = switchTo.firstOrNull()?.subscriptionId
            ?: enableFirst.firstOrNull()?.subscriptionId
        (if (switchTo.isEmpty()) "noDataEnable" else "noDataSwitch") +
            ":${dataSim.subscriptionId}:$offered:$country"
    }
}

/**
 * Whether a stored arrival mark describes an arrival that is over: the user
 * is in a different country than the mark recorded, or a different
 * subscription carries data. Then the mark must clear so a *return* to the
 * marked country later warns once again (SPEC: once per arrival, "cleared
 * when the country changes") — without this, the identical key would be
 * skipped forever. Silence in the SAME place is not staleness: a flapping
 * roaming flag mid-trip keeps its mark and never re-nags. An unknown
 * network country decides nothing; a mark this code can't parse is stale by
 * definition.
 */
fun isMarkStale(mark: String?, snapshot: DataSnapshot): Boolean {
    if (mark == null) return false
    val parts = mark.split(':')
    if (parts.size < 3) return true
    val markedSubscription = parts[1].toIntOrNull() ?: return true
    val markedCountry = parts.last()
    val country = snapshot.networkCountry.trim().uppercase()
    if (country.isEmpty()) return false
    return markedCountry != country || markedSubscription != snapshot.dataSubscriptionId
}

internal fun DataSimScope.covers(
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
