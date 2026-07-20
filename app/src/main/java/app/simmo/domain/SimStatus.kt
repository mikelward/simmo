package app.simmo.domain

/**
 * A SIM's status in the current country, for the SIMs screen dashboard: which
 * SIM the phone is set to use now (**primary** — the word Android's own SIM
 * settings uses, "Your primary SIMs → Calls / Mobile data") versus which one
 * Simmo's rules would use here (**preferred**), for calling and for data.
 *
 * Pure and derived from the same rule books the engines read, so a status chip
 * can never disagree with what actually happens on a call or a roaming arrival.
 * "Primary" is read from the platform (the default voice/data subscription);
 * "preferred" is computed by mirroring the decision engines below.
 */
data class SimStatus(
    /** Android's default voice SIM: what an un-ruled call goes out on. */
    val callingPrimary: Boolean = false,
    /** The SIM your calling rules would place a call to this country on. */
    val callingPreferred: Boolean = false,
    /** The SIM carrying data now: Android's default (or active) data SIM. */
    val dataPrimary: Boolean = false,
    /** The SIM your data rules want carrying data here. */
    val dataPreferred: Boolean = false,
) {
    /** True when no role applies — the row shows no status chips. */
    val isEmpty: Boolean
        get() = !callingPrimary && !callingPreferred && !dataPrimary && !dataPreferred
}

/**
 * The current-country status of every active SIM, keyed by subscription id, for
 * the SIMs dashboard. [callingActiveSims] are the call-capable subscriptions
 * (calling primary/preferred can only be one of them); [dataSnapshot] carries
 * the data side — the current network country, every active subscription
 * (data-only travel eSIMs included), and which one carries data now.
 *
 * The **primary** roles are the user-selected defaults ([defaultCallSubscriptionId],
 * [defaultDataSubscriptionId]) — what Android's own "Your primary SIMs" shows —
 * NOT the SIM transiently carrying data ([DataSnapshot.dataSubscriptionId]),
 * which automatic data switching can move without the user changing anything
 * (Codex on PR #79). The **preferred** roles still read the active data sub,
 * since they mirror what the rules would actually do to the live arrangement.
 *
 * A subscription absent from the returned map has no role in this country; the
 * caller renders no chips for it. Inactive/registered-only SIMs never appear —
 * they are neither a live default nor a resolvable rule target.
 */
fun simStatuses(
    callingBook: CallingRuleBook,
    callingActiveSims: List<ActiveSim>,
    defaultCallSubscriptionId: Int,
    defaultDataSubscriptionId: Int,
    dataBook: DataRuleBook,
    dataSnapshot: DataSnapshot,
): Map<Int, SimStatus> {
    val country = dataSnapshot.networkCountry.trim().uppercase().ifEmpty { null }
    val callingPreferred = preferredCallingSubId(callingBook, callingActiveSims, country, dataSnapshot.customGroups)
    val dataPreferred = preferredDataSubId(dataBook, dataSnapshot)

    val ids = buildSet {
        callingActiveSims.forEach { add(it.subscriptionId) }
        dataSnapshot.activeSims.forEach { add(it.subscriptionId) }
    }
    return ids.associateWith { id ->
        SimStatus(
            callingPrimary = id == defaultCallSubscriptionId,
            callingPreferred = id == callingPreferred,
            dataPrimary = id == defaultDataSubscriptionId,
            dataPreferred = id == dataPreferred,
        )
    }.filterValues { !it.isEmpty }
}

/**
 * The subscription id a call to [destination] would be placed on by the calling
 * rules — the "preferred calling SIM" for the current country. Mirrors
 * [DecisionEngine]'s first-applicable-rule pass, but only for the actions that
 * resolve to a specific SIM: a rule whose SIM can't act right now (disabled,
 * ambiguous, or a matching-country rule with no unique match) is skipped, and
 * the first rule that wins with a non-SIM action (Ask, hand-off, no change) or
 * running out of rules means there is no single preferred SIM (null).
 *
 * Best-effort by design (it is a status hint, not the call path): a hand-off
 * rule is treated as "no preferred SIM" even though the live engine would skip
 * an unreachable one — a hand-off to your *current* country is a rare rule, and
 * the dashboard errs toward showing no chip rather than a wrong one.
 */
internal fun preferredCallingSubId(
    book: CallingRuleBook,
    activeSims: List<ActiveSim>,
    destination: String?,
    customGroups: Map<String, List<String>>,
): Int? {
    for (rule in book.rules) {
        if (!rule.enabled || rule.pendingRemoval) continue
        if (!rule.matcher.matchesRegion(destination, customGroups)) continue
        when (val action = rule.action) {
            is RuleAction.UseSim -> when (val resolved = resolveSim(action.sim, activeSims)) {
                is SimResolution.Active -> return resolved.sim.subscriptionId
                SimResolution.Inactive, is SimResolution.Ambiguous -> Unit
            }

            RuleAction.UseMatchingCountrySim -> {
                val matching = destination?.let { region ->
                    activeSims.filter { it.countryIso.equals(region, ignoreCase = true) }
                }
                if (matching?.size == 1) return matching.single().subscriptionId
            }

            // A rule that wins with a non-SIM action names no single SIM.
            is RuleAction.HandOff, RuleAction.Ask, RuleAction.SystemDefault -> return null
        }
    }
    return null
}

/**
 * The subscription id a `UseSimForData` rule wants carrying data here — the
 * "preferred data SIM" for the current country. Mirrors [evaluateDataRules]'s
 * control flow exactly (a scope-covering RoamingOk or an AlwaysWarn decides the
 * arrival and names no SIM; a skipped rule falls through), so the dashboard's
 * data-preferred chip always agrees with the triage card and the roaming
 * warning. Returns the wanted SIM even when it already carries data (a
 * satisfied rule), so the same SIM can read as both primary and preferred.
 */
internal fun preferredDataSubId(book: DataRuleBook, snapshot: DataSnapshot): Int? {
    val country = snapshot.networkCountry.trim().uppercase()
    if (country.isEmpty()) return null
    val dataSim = snapshot.activeSims.firstOrNull { it.subscriptionId == snapshot.dataSubscriptionId }
        ?: return null
    for (rule in book.rules) {
        if (!rule.enabled || rule.pendingRemoval) continue
        if (!rule.matcher.matchesRegion(country, snapshot.customGroups)) continue
        when (val expectation = rule.expectation) {
            is DataExpectation.UseSimForData -> when (val resolved = resolveSim(expectation.sim, snapshot.activeSims)) {
                is SimResolution.Active -> return resolved.sim.subscriptionId
                SimResolution.Inactive, is SimResolution.Ambiguous -> Unit
            }

            // RoamingOk expresses "roaming is fine", not a SIM preference: when
            // its scope covers the data SIM it decides the arrival (Silent), so
            // no lower rule can name a SIM; a scope miss falls through.
            is DataExpectation.RoamingOk ->
                if (expectation.scope.covers(dataSim, rule.matcher, snapshot)) return null

            // AlwaysWarn pins the default and names no SIM.
            DataExpectation.AlwaysWarn -> return null
        }
    }
    return null
}
