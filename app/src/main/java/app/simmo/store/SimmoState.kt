package app.simmo.store

import app.simmo.domain.CustomGroup
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
    /** User-defined country groups a rule can match by id (SPEC "Calling rules"). */
    val customGroups: List<CustomGroup> = emptyList(),
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
    /**
     * Settings "Show which SIM is used" (SPEC "Call feedback and delay"):
     * announce rule-picked SIMs with a "Calling using <SIM>" toast. Off by
     * default; state written before the field existed decodes as off too.
     */
    val showCallToast: Boolean = false,
    /**
     * Settings "Delay before calling" (SPEC "Call feedback and delay"):
     * seconds of cancelable countdown before a rule-picked call is placed.
     * 0 disables the delay — the default, and what pre-field state decodes
     * as. Readers clamp to [MAX_CALL_DELAY_SECONDS].
     */
    val callDelaySeconds: Int = 0,
    /**
     * Settings "Use contacts' local numbers" (SPEC "Hands-free and Android
     * Auto safeguards"): when a dialed number is overseas but its contact
     * also has a local number, route the local number instead. Off by
     * default (and for pre-field state); inert without READ_CONTACTS — the
     * warm contact index is simply empty then.
     */
    val correctContactNumbers: Boolean = false,
    /**
     * The hands-free call guard's "Block overseas calls" toggle (SPEC
     * "Hands-free and Android Auto safeguards"). Off by default and for
     * pre-field state — blocking calls is strictly opt-in.
     */
    val guardOverseasHandsFree: Boolean = false,
    /** The guard's "Block calls needing a disabled SIM" toggle; likewise off. */
    val guardDisabledSimHandsFree: Boolean = false,
) {
    companion object {
        /** Ceiling for [callDelaySeconds]; also the settings slider's range. */
        const val MAX_CALL_DELAY_SECONDS = 10
    }
}

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
