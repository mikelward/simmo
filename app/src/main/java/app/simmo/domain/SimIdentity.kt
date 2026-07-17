package app.simmo.domain

import kotlinx.serialization.Serializable

/**
 * Opaque handle to a Telecom phone account. The platform layer maps this to a
 * `PhoneAccountHandle`; the domain only compares it for equality.
 */
@Serializable
data class PhoneAccountRef(val id: String)

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
     * True until the user has answered the "add rules for this new SIM?"
     * prompt (SPEC "On SIM change"). Defaults to false so registry entries
     * stored before this field existed never prompt retroactively.
     */
    val needsRulePrompt: Boolean = false,
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
 */
fun List<RegisteredSim>.recordSeen(active: List<ActiveSim>, nowMillis: Long): List<RegisteredSim> {
    val activeById = active.associateBy { it.subscriptionId }
    val claimed = mutableSetOf<Int>()
    // Refreshes and re-binds copy the entry so per-entry state that isn't
    // identity (the pending rule prompt) survives; only genuinely new rows
    // start a prompt.
    val refreshed = map { entry ->
        activeById[entry.subscriptionId]?.let { sim ->
            claimed += sim.subscriptionId
            entry.copy(carrierName = sim.carrierName, displayName = sim.displayName, lastSeenEpochMillis = nowMillis)
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
        entry.copy(
            subscriptionId = match.subscriptionId,
            carrierName = match.carrierName,
            displayName = match.displayName,
            lastSeenEpochMillis = nowMillis,
        )
    }
    val new = active.filter { it.subscriptionId !in claimed }.map {
        RegisteredSim(it.subscriptionId, it.carrierName, it.displayName, nowMillis, needsRulePrompt = true)
    }
    return rebound + new
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
 * Deletes [ref]'s registry entry (the SIMs screen; SPEC "Disabled-SIM
 * assist"). Rules that still store the SIM keep working by name — they just
 * stay paused until a matching SIM appears again.
 */
fun List<RegisteredSim>.withoutSim(ref: SimRef): List<RegisteredSim> =
    filterNot { it.matchesRef(ref) }
