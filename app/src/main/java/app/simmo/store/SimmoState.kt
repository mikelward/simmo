package app.simmo.store

import app.simmo.domain.RegisteredSim
import app.simmo.domain.RuleAction
import app.simmo.domain.RuleBook
import app.simmo.domain.SimRef
import kotlinx.serialization.Serializable

/**
 * Everything Simmo persists (SPEC "Architecture"): the rules and the SIM
 * registry, plus the optional default-region override. Dialed numbers are
 * never part of this state. Included in Android backups / device transfers by
 * the maintainer's decision — see the backup rules in `res/xml/` and SPEC
 * "Permissions and privacy".
 */
@Serializable
data class SimmoState(
    val rules: RuleBook = RuleBook(),
    val simRegistry: List<RegisteredSim> = emptyList(),
    /** Overrides the network/SIM-derived default region when set (ISO code). */
    val defaultRegionOverride: String? = null,
    /**
     * Which install wrote this state — see [withInstallValidated]. Backed up
     * with the state; the marker it is compared against is not.
     */
    val installId: String? = null,
    /**
     * The "Make Simmo better" onboarding choice (SPEC "Permissions and
     * privacy"). No analytics ship yet — nothing is collected or sent today —
     * but the preference is recorded from install so a future analytics
     * addition honors a choice the user already made. Defaults to opted in;
     * state written before the field existed decodes as opted in too.
     */
    val analyticsOptIn: Boolean = true,
)

/**
 * Guards against restored state routing calls to the wrong SIM: subscription
 * IDs are per-device, so a rule restored onto another phone could exact-match
 * a completely different SIM that reuses the same integer. When the stored
 * [SimmoState.installId] doesn't match the device's (non-backed-up) install
 * marker, every stored subscription ID is invalidated so [SimRef] re-binding
 * falls back to carrier + display name — which is device-portable — and the
 * state adopts the current install. Idempotent once adopted.
 */
fun SimmoState.withInstallValidated(currentInstallId: String): SimmoState {
    if (installId == currentInstallId) return this
    return copy(
        rules = rules.copy(
            rules = rules.rules.map { it.copy(action = it.action.invalidateSimIds()) },
        ),
        simRegistry = simRegistry
            .map { it.copy(subscriptionId = SimRef.INVALID_SUBSCRIPTION_ID) }
            .distinct(),
        installId = currentInstallId,
    )
}

private fun RuleAction.invalidateSimIds(): RuleAction = when (this) {
    is RuleAction.UseSim -> RuleAction.UseSim(sim.copy(subscriptionId = SimRef.INVALID_SUBSCRIPTION_ID))
    // No other action carries a subscription ID; hand-off account handles are
    // validated against the live snapshot at call time already.
    is RuleAction.HandOff,
    RuleAction.Ask,
    RuleAction.UseMatchingCountrySim,
    RuleAction.SystemDefault,
    -> this
}
