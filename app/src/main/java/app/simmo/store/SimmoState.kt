package app.simmo.store

import app.simmo.domain.CustomGroup
import app.simmo.domain.DataExpectation
import app.simmo.domain.DataRuleBook
import app.simmo.domain.DataSimScope
import app.simmo.domain.RegisteredSim
import app.simmo.domain.RuleAction
import app.simmo.domain.CallingRuleBook
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
    val rules: CallingRuleBook = CallingRuleBook(),
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
    /**
     * The ordered data-rule list (SPEC "Data rules"). Fresh installs — and
     * state written before the field existed — decode to the preseeded
     * EU/EEA roam-like-home rule.
     */
    val dataRules: DataRuleBook = DataRuleBook(),
    /**
     * The roaming watch's last-surfaced arrival key (SPEC "Data rules":
     * "once per SIM-and-country arrival"). Telephony refreshes fire
     * constantly; only this persisted mark keeps the same arrival from
     * re-nagging across them and across process restarts. Null until the
     * first warning; a new key (another country, SIM, or problem) replaces
     * it.
     */
    val dataWatchMark: String? = null,
    /**
     * The arrivals the user chose to "Ignore for this trip" on the triage card
     * (SPEC "Data rules" → Triage): a rule-free, per-trip dismiss keyed on the
     * same [DataVerdict.arrivalKey]s the notification dedupes on. While an
     * arrival's key is in the set its card stays hidden and its warning stays
     * unposted; a key clears on the same country/SIM change that ends its
     * arrival (see `isMarkStale`), so the next trip warns again. It is a SET,
     * not one key, because several problem shapes can be dismissed on one trip
     * (dismiss the no-data nudge, then the roaming warning after enabling
     * roaming) and each must stay dismissed independently — they share a
     * country/SIM, so they go stale and clear together when the trip ends.
     * Separate from [dataWatchMark] on purpose: the notification claiming its
     * own mark on post must never pre-dismiss the card.
     */
    val dataDismissMarks: Set<String> = emptySet(),
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
        dataRules = dataRules.copy(
            rules = dataRules.rules.map { it.copy(expectation = it.expectation.invalidateSimIds()) },
        ),
        simRegistry = simRegistry
            .map { it.copy(subscriptionId = SimRef.INVALID_SUBSCRIPTION_ID) }
            .distinct(),
        // The arrival mark embeds a subscription id too: on the new phone
        // that integer can belong to a different SIM, and a stale mark would
        // suppress the first genuine warning after the restore (Codex on
        // PR #55). Clearing costs at most one repeat of an already-seen
        // warning — the safe direction.
        dataWatchMark = null,
        // The dismiss keys embed a subscription id too: keep them and a
        // restore could silence the first genuine warning on the new phone.
        dataDismissMarks = emptySet(),
        installId = currentInstallId,
    )
}

private fun DataExpectation.invalidateSimIds(): DataExpectation = when (this) {
    is DataExpectation.UseSimForData ->
        DataExpectation.UseSimForData(sim.copy(subscriptionId = SimRef.INVALID_SUBSCRIPTION_ID))
    is DataExpectation.RoamingOk -> when (scope) {
        is DataSimScope.Sims -> DataExpectation.RoamingOk(
            DataSimScope.Sims(scope.sims.map { it.copy(subscriptionId = SimRef.INVALID_SUBSCRIPTION_ID) }),
        )
        DataSimScope.AnySim, DataSimScope.HomedInMatchedCountries -> this
    }
    // No stored SimRef to invalidate — the local SIM is resolved at evaluation.
    DataExpectation.UseLocalSimForData, DataExpectation.AlwaysWarn -> this
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
