package app.simmo.domain

/**
 * Opaque handle to a Telecom phone account. The platform layer maps this to a
 * `PhoneAccountHandle`; the domain only compares it for equality.
 */
data class PhoneAccountRef(val id: String)

/**
 * The stable reference a rule stores to a SIM (SPEC "SIM identity"): the
 * subscription ID is the primary key; carrier + display name together are the
 * human-meaningful re-binding fallback. Never a slot index.
 */
data class SimRef(
    val subscriptionId: Int,
    val carrierName: String,
    val displayName: String,
)

/** A subscription currently active on the device. */
data class ActiveSim(
    val subscriptionId: Int,
    val carrierName: String,
    val displayName: String,
    val phoneAccount: PhoneAccountRef,
)

/**
 * A subscription Simmo has seen active at some point — the SIM registry that
 * lets rules target currently disabled profiles (SPEC "Disabled-SIM assist").
 */
data class RegisteredSim(
    val subscriptionId: Int,
    val carrierName: String,
    val displayName: String,
    val lastSeenEpochMillis: Long,
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
