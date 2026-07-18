package app.simmo.domain

import kotlinx.serialization.Serializable

/**
 * Opaque handle to a Telecom phone account. The platform layer maps this to a
 * `PhoneAccountHandle`; the domain only compares it for equality.
 */
@Serializable
data class PhoneAccountRef(val id: String)

/**
 * A call-capable Telecom phone account that is not a SIM subscription — a SIP
 * provider or another calling app whose account the user enabled in the
 * system's calling-accounts settings (SPEC "Hand-off to another app").
 * Snapshot-only: a rule stores the ref (plus its own copy of the label) inside
 * [RuleAction.HandOff.ViaPhoneAccount]; [label] is the account's user-facing
 * name, read off the decision path.
 */
data class CallingAccount(
    val ref: PhoneAccountRef,
    val label: String,
)

/**
 * The stable reference a rule stores to a SIM (SPEC "SIM identity"): the
 * subscription ID is the primary key; carrier + display name together are the
 * human-meaningful re-binding fallback. Never a slot index.
 */
@Serializable
data class SimRef(
    val subscriptionId: Int,
    val carrierName: String,
    val displayName: String,
) {
    companion object {
        /**
         * Subscription IDs are only meaningful on the device that assigned
         * them. After a backup restore / device transfer the store invalidates
         * every stored ID to this sentinel (it matches no real subscription),
         * forcing [resolveSim]'s carrier + display-name re-binding.
         */
        const val INVALID_SUBSCRIPTION_ID: Int = -1
    }
}

/** A subscription currently active on the device. */
data class ActiveSim(
    val subscriptionId: Int,
    val carrierName: String,
    val displayName: String,
    val phoneAccount: PhoneAccountRef,
    /**
     * The SIM's home country (ISO region, any case), from the subscription
     * metadata; empty when the platform doesn't report one. Drives the
     * "use the SIM with the matching country" default rule.
     */
    val countryIso: String = "",
    /**
     * The SIM's own line number as the platform reports it (usually E.164);
     * empty when unknown — many profiles carry none, and reading it needs
     * READ_PHONE_NUMBERS.
     */
    val phoneNumber: String = "",
)

/**
 * A subscription Simmo has seen active at some point — the SIM registry that
 * lets rules target currently disabled profiles (SPEC "Disabled-SIM assist").
 */
@Serializable
data class RegisteredSim(
    val subscriptionId: Int,
    val carrierName: String,
    val displayName: String,
    val lastSeenEpochMillis: Long,
    /**
     * Last-known home country (ISO region) of the SIM, captured while it was
     * active so the SIMs screen can still show it for a disabled profile.
     * Empty when never reported.
     */
    val countryIso: String = "",
    /**
     * Last-known own line number of the SIM, captured while it was active
     * (see [ActiveSim.phoneNumber]). Empty when never reported.
     */
    val phoneNumber: String = "",
    /**
     * True until the user has answered the "add rules for this new SIM?"
     * prompt (SPEC "On SIM change"). Defaults to false so registry entries
     * stored before this field existed never prompt retroactively.
     */
    val needsRulePrompt: Boolean = false,
    /**
     * Whether the one-time "new SIM" notification for this entry has been
     * posted (or deliberately suppressed). Persisted so a subscription
     * refresh — they happen constantly — can never re-nag.
     */
    val newSimNotified: Boolean = false,
    /**
     * Whether this SIM has been seen with a call-capable phone account. The
     * registry records data-only subscriptions too (a travel eSIM without
     * calling — the roaming watch's no-data nudge must be able to name a
     * disabled local profile, Codex on PR #52), but only call-capable SIMs
     * are offered as calling-rule targets. Defaults true: every row written
     * before the field existed came from the call-capable snapshot.
     */
    val callCapable: Boolean = true,
    /**
     * Whether the add-a-calling-rule prompt has ever been offered for this
     * identity — separate from [callCapable], which tracks the *current*
     * profile's capability: a re-bind takes capability as fresh truth (the
     * adopted profile may genuinely be data-only), and this history is what
     * keeps a later capability promotion from re-asking a prompt the user
     * already answered (Codex on PR #52). Defaults true: rows written before
     * the field existed were always created with the prompt.
     */
    val rulePromptOffered: Boolean = true,
) {
    fun ref(): SimRef = SimRef(subscriptionId, carrierName, displayName)
}

sealed interface SimResolution {
    data class Active(val sim: ActiveSim) : SimResolution

    /** Known SIM, not currently active — triggers the enable flow. */
    data object Inactive : SimResolution

    /** Carrier-name re-binding matched more than one active SIM; the chooser re-learns. */
    data class Ambiguous(val candidates: List<ActiveSim>) : SimResolution
}

/**
 * Resolves a stored [SimRef] against the currently active SIMs, per SPEC "SIM
 * identity": exact subscription-ID match wins; otherwise re-bind by carrier +
 * display name (case- and whitespace-insensitive) when that pair is unambiguous.
 * A carrier-only match (the display name no longer lines up — the profile was
 * re-created under another name, or a *different* same-carrier SIM is active)
 * is never bound silently: it surfaces as [SimResolution.Ambiguous] so the
 * chooser re-learns which SIM the rule means, even with a single candidate —
 * silently picking a same-carrier sibling could bill the wrong plan.
 */
fun resolveSim(ref: SimRef, active: List<ActiveSim>): SimResolution {
    active.firstOrNull { it.subscriptionId == ref.subscriptionId }?.let {
        return SimResolution.Active(it)
    }
    val byCarrier = active.filter { it.carrierName.matchesIgnoringCaseAndSpace(ref.carrierName) }
    if (byCarrier.isEmpty()) return SimResolution.Inactive
    val byCarrierAndName = byCarrier.filter { it.displayName.matchesIgnoringCaseAndSpace(ref.displayName) }
    return when (byCarrierAndName.size) {
        1 -> SimResolution.Active(byCarrierAndName.single())
        else -> SimResolution.Ambiguous(byCarrier)
    }
}

private fun String.matchesIgnoringCaseAndSpace(other: String): Boolean =
    trim().equals(other.trim(), ignoreCase = true)

/**
 * Registry capture (SPEC "Disabled-SIM assist"): active SIMs refresh their
 * registry row by subscription ID; a row whose ID is no longer live (the
 * profile was deleted and re-downloaded, or a restore invalidated it) re-binds
 * by carrier + display name to an unclaimed active SIM when that match is
 * unique — same identity ladder as [resolveSim] — instead of leaving a
 * duplicate row behind. Rows for SIMs not currently active are kept so rules
 * can still target them; genuinely new SIMs append.
 *
 * [callCapableIds] marks which of [active] carry a call-capable phone account
 * (see [RegisteredSim.callCapable]); it defaults to all of them, which is what
 * every caller passing only the call snapshot means. A data-only SIM never
 * starts a rule prompt — it can't place calls, so "add a rule so your calls
 * know when to use it" would be wrong.
 */
fun List<RegisteredSim>.recordSeen(
    active: List<ActiveSim>,
    nowMillis: Long,
    callCapableIds: Set<Int> = active.mapTo(HashSet()) { it.subscriptionId },
): List<RegisteredSim> {
    val activeById = active.associateBy { it.subscriptionId }
    val claimed = mutableSetOf<Int>()
    // Refreshes and re-binds copy the entry so per-entry state that isn't
    // identity (the pending rule prompt) survives; only genuinely new rows
    // start a prompt.
    val refreshed = map { entry ->
        activeById[entry.subscriptionId]?.let { sim ->
            claimed += sim.subscriptionId
            entry.refreshedFrom(sim, nowMillis, callCapableIds)
        } ?: entry
    }
    val rebound = refreshed.map { entry ->
        if (entry.subscriptionId in activeById) return@map entry
        val match = active.singleOrNull { sim ->
            sim.subscriptionId !in claimed &&
                sim.carrierName.matchesIgnoringCaseAndSpace(entry.carrierName) &&
                sim.displayName.matchesIgnoringCaseAndSpace(entry.displayName)
        } ?: return@map entry
        claimed += match.subscriptionId
        // A re-bind adopts a different subscription — after a restore it can
        // be a different physical SIM that merely shares carrier + name — so
        // country and own number are taken verbatim, blanks included; the
        // last-known fallback must not resurrect the old profile's values
        // (Codex on PR #36).
        val nowCallCapable = match.subscriptionId in callCapableIds
        entry.copy(
            subscriptionId = match.subscriptionId,
            carrierName = match.carrierName,
            displayName = match.displayName,
            lastSeenEpochMillis = nowMillis,
            countryIso = match.countryIso,
            phoneNumber = match.phoneNumber,
            // A row whose prompt was never offered is owed it once the
            // adopted profile can call; [rulePromptOffered] — not the
            // capability flag — is what makes this safe against re-asking an
            // answered prompt after a rebind demoted the row (Codex on PR #52).
            needsRulePrompt = entry.needsRulePrompt || (nowCallCapable && !entry.rulePromptOffered),
            rulePromptOffered = entry.rulePromptOffered || nowCallCapable,
            // Verbatim like the fields above: the adopted subscription may be
            // a genuinely data-only profile, and a stale true here would keep
            // offering it as a calling target it can never be (Codex on
            // PR #52). The prompt history above survives independently.
            callCapable = nowCallCapable,
        )
    }
    val new = active.filter { it.subscriptionId !in claimed }.map {
        val callCapable = it.subscriptionId in callCapableIds
        RegisteredSim(
            it.subscriptionId, it.carrierName, it.displayName, nowMillis,
            countryIso = it.countryIso, phoneNumber = it.phoneNumber,
            needsRulePrompt = callCapable,
            callCapable = callCapable,
            rulePromptOffered = callCapable,
        )
    }
    return rebound + new
}

/**
 * The entry updated with what an active sighting of the same subscription
 * reports (the exact-ID refresh path only — re-binds take fresh values
 * verbatim, see [recordSeen]). Country and own number keep the last-known
 * value when the fresh read is blank — the platform reports them
 * intermittently (and the number needs a separate permission), and for the
 * same subscription "last seen" beats "forgotten". The call-capability flag
 * is sticky-true for the same reason: the same subscription doesn't lose
 * calling, but a degraded Telecom read can briefly miss its account, and a
 * flap would hide the SIM from the rule editor.
 */
private fun RegisteredSim.refreshedFrom(
    sim: ActiveSim,
    nowMillis: Long,
    callCapableIds: Set<Int>,
): RegisteredSim {
    val nowCallCapable = callCapable || sim.subscriptionId in callCapableIds
    return copy(
        carrierName = sim.carrierName,
        displayName = sim.displayName,
        lastSeenEpochMillis = nowMillis,
        countryIso = sim.countryIso.ifBlank { countryIso },
        phoneNumber = sim.phoneNumber.ifBlank { phoneNumber },
        // A row first recorded during such a Telecom miss registered as
        // data-only with no rule prompt; when the SIM turns out call-capable
        // the prompt is owed after all (Codex on PR #52). [rulePromptOffered]
        // gates it, so an answered prompt is never re-asked — even when a
        // rebind demoted the row's capability in between.
        needsRulePrompt = needsRulePrompt || (nowCallCapable && !rulePromptOffered),
        rulePromptOffered = rulePromptOffered || nowCallCapable,
        callCapable = nowCallCapable,
    )
}

/**
 * Whether this registry entry is the SIM [ref] points at: exact (real)
 * subscription-ID match first, else the carrier + display-name identity —
 * the same ladder as [resolveSim].
 */
private fun RegisteredSim.matchesRef(ref: SimRef): Boolean =
    if (ref.subscriptionId != SimRef.INVALID_SUBSCRIPTION_ID && subscriptionId == ref.subscriptionId) {
        true
    } else {
        carrierName.matchesIgnoringCaseAndSpace(ref.carrierName) &&
            displayName.matchesIgnoringCaseAndSpace(ref.displayName)
    }

/** Marks the prompt for [ref]'s SIM answered (rule added or dismissed). */
fun List<RegisteredSim>.withRulePromptCleared(ref: SimRef): List<RegisteredSim> = map { entry ->
    if (entry.matchesRef(ref) && entry.needsRulePrompt) entry.copy(needsRulePrompt = false) else entry
}

/**
 * The registry entries whose "new SIM" notification should post now: the
 * prompt is unanswered, the notification hasn't fired, and the SIM is
 * actually active (a notification for a SIM that vanished again would only
 * confuse). Pure so the once-only rule is testable.
 */
fun List<RegisteredSim>.pendingNewSimNotifications(activeSims: List<ActiveSim>): List<RegisteredSim> {
    val activeIds = activeSims.map { it.subscriptionId }.toSet()
    return filter { it.needsRulePrompt && !it.newSimNotified && it.subscriptionId in activeIds }
}

/** Marks the "new SIM" notification for [refs] posted (or suppressed). */
fun List<RegisteredSim>.withNewSimNotified(refs: List<SimRef>): List<RegisteredSim> = map { entry ->
    if (!entry.newSimNotified && refs.any { entry.matchesRef(it) }) {
        entry.copy(newSimNotified = true)
    } else {
        entry
    }
}

/**
 * Deletes [ref]'s registry entry (the SIMs screen; SPEC "Disabled-SIM
 * assist"). An exact (real) subscription-ID match wins outright: with two
 * same-named rows under different real IDs, deleting one must not take its
 * sibling too (flagged by Codex on PR #19). The carrier + display-name
 * fallback only applies when no row carries the ref's ID — e.g. after a
 * restore invalidated it. Rules that still store the SIM keep working by
 * name — they just stay paused until a matching SIM appears again.
 */
fun List<RegisteredSim>.withoutSim(ref: SimRef): List<RegisteredSim> {
    if (ref.subscriptionId != SimRef.INVALID_SUBSCRIPTION_ID &&
        any { it.subscriptionId == ref.subscriptionId }
    ) {
        return filterNot { it.subscriptionId == ref.subscriptionId }
    }
    return filterNot {
        it.carrierName.matchesIgnoringCaseAndSpace(ref.carrierName) &&
            it.displayName.matchesIgnoringCaseAndSpace(ref.displayName)
    }
}
