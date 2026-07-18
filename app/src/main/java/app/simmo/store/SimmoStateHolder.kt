package app.simmo.store

import androidx.datastore.core.DataStore
import app.simmo.domain.ActiveSim
import app.simmo.domain.CustomGroup
import app.simmo.domain.RegisteredSim
import app.simmo.domain.withGroupSaved
import app.simmo.domain.RuleBook
import app.simmo.domain.SimRef
import app.simmo.domain.recordSeen
import app.simmo.domain.withNewSimNotified
import app.simmo.domain.withRulePromptCleared
import app.simmo.domain.withoutSim
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Keeps the persisted state resident in memory for the decision path (AGENTS.md
 * "Fast decision path"): loading starts eagerly on [scope] (never the main
 * thread) at construction, and [current] never touches disk. A `null` value
 * means the first load hasn't finished — the redirection service must answer
 * "proceed unmodified" in that case, never wait.
 */
class SimmoStateHolder(
    private val store: DataStore<SimmoState>,
    scope: CoroutineScope,
    /** The device's install marker; see [InstallMarker] and [withInstallValidated]. */
    private val installId: String,
) {
    init {
        // Persist the install adoption so later loads are no-ops. Correctness
        // does not depend on when (or whether) this write lands: the published
        // flow below validates every value itself.
        scope.launch {
            store.updateData { it.withInstallValidated(installId) }
        }
    }

    /**
     * Every published value passes through [withInstallValidated], so state
     * restored from another device can never reach a reader — however briefly —
     * with its stale subscription IDs intact, regardless of how the eager
     * collector races the persistence write above.
     */
    val state: StateFlow<SimmoState?> =
        store.data
            .map { it.withInstallValidated(installId) }
            .stateIn(scope, SharingStarted.Eagerly, initialValue = null)

    val current: SimmoState? get() = state.value

    // Every write transform validates first for the same reason the read side
    // does: a mutation racing the launch-time adoption write must not operate
    // on restored state whose old-device subscription IDs could collide with
    // this device's (e.g. recordSeen ID-overwriting a restored registry row).

    suspend fun updateRules(transform: (RuleBook) -> RuleBook) {
        store.updateData {
            val valid = it.withInstallValidated(installId)
            valid.copy(rules = transform(valid.rules))
        }
    }

    /** What a registry capture found; see [recordSeenSims]. */
    data class SimCapture(
        val registry: List<RegisteredSim>,
        /**
         * True when this capture populated a previously empty registry —
         * the fresh-install case whose batch of "new" SIMs must not nag.
         * Read from the stored state inside the write transaction, never
         * from the asynchronously published [state] flow: on cold start an
         * existing user's capture can run before the first load publishes,
         * and mistaking that for a fresh install would silently swallow a
         * real new-SIM notification (Codex on PR #21).
         */
        val firstCapture: Boolean,
    )

    /**
     * Registry capture on every subscription change (SPEC "Disabled-SIM
     * assist"). Returns the updated registry — the published [state] flow
     * updates asynchronously, so callers reacting to the capture (the new-SIM
     * notification) must not re-read [current] and race it.
     */
    suspend fun recordSeenSims(active: List<ActiveSim>, nowMillis: Long): SimCapture {
        var wasEmpty = false
        val updated = store.updateData {
            val valid = it.withInstallValidated(installId)
            wasEmpty = valid.simRegistry.isEmpty()
            valid.copy(simRegistry = valid.simRegistry.recordSeen(active, nowMillis))
        }
        return SimCapture(updated.simRegistry, firstCapture = wasEmpty)
    }

    /** The new-SIM prompt for [ref] was answered — added a rule or dismissed. */
    suspend fun markSimRulePromptAnswered(ref: SimRef) {
        store.updateData {
            val valid = it.withInstallValidated(installId)
            valid.copy(simRegistry = valid.simRegistry.withRulePromptCleared(ref))
        }
    }

    /** Deletes [ref] from the registry (the SIMs screen). */
    suspend fun deleteRegisteredSim(ref: SimRef) {
        store.updateData {
            val valid = it.withInstallValidated(installId)
            valid.copy(simRegistry = valid.simRegistry.withoutSim(ref))
        }
    }

    /** Add / edit / delete a user-defined country group (the Groups screen). */
    suspend fun updateCustomGroups(transform: (List<CustomGroup>) -> List<CustomGroup>) {
        store.updateData {
            val valid = it.withInstallValidated(installId)
            valid.copy(customGroups = transform(valid.customGroups))
        }
    }

    /**
     * Saves groups the user built in the rule picker together with the rule that
     * uses them, in one transaction — so the persisted state can never contain a
     * rule referencing a group that isn't there, nor an orphan group from a rule
     * that didn't commit (Codex on PR #35). [pendingGroups] are added/replaced by
     * id before [transform] rewrites the rules.
     */
    suspend fun updateGroupsAndRules(
        pendingGroups: List<CustomGroup>,
        transform: (RuleBook) -> RuleBook,
    ) {
        store.updateData {
            val valid = it.withInstallValidated(installId)
            val merged = pendingGroups.fold(valid.customGroups) { acc, group -> acc.withGroupSaved(group) }
            valid.copy(customGroups = merged, rules = transform(valid.rules))
        }
    }

    /** The "Make Simmo better" onboarding toggle was set to [enabled]. */
    suspend fun setAnalyticsOptIn(enabled: Boolean) {
        store.updateData {
            val valid = it.withInstallValidated(installId)
            valid.copy(analyticsOptIn = enabled)
        }
    }

    /** The settings "Show which SIM is used" toggle was set to [enabled]. */
    suspend fun setShowCallToast(enabled: Boolean) {
        store.updateData {
            val valid = it.withInstallValidated(installId)
            valid.copy(showCallToast = enabled)
        }
    }

    /** The settings "Delay before calling" value, in seconds (0 = off). */
    suspend fun setCallDelaySeconds(seconds: Int) {
        store.updateData {
            val valid = it.withInstallValidated(installId)
            valid.copy(callDelaySeconds = seconds.coerceIn(0, SimmoState.MAX_CALL_DELAY_SECONDS))
        }
    }

    /** The settings "Use contacts' local numbers" toggle was set to [enabled]. */
    suspend fun setCorrectContactNumbers(enabled: Boolean) {
        store.updateData {
            val valid = it.withInstallValidated(installId)
            valid.copy(correctContactNumbers = enabled)
        }
    }

    /** The hands-free guard's "Block overseas calls" toggle was set to [enabled]. */
    suspend fun setGuardOverseasHandsFree(enabled: Boolean) {
        store.updateData {
            val valid = it.withInstallValidated(installId)
            valid.copy(guardOverseasHandsFree = enabled)
        }
    }

    /** The guard's "Block calls needing a disabled SIM" toggle was set to [enabled]. */
    suspend fun setGuardDisabledSimHandsFree(enabled: Boolean) {
        store.updateData {
            val valid = it.withInstallValidated(installId)
            valid.copy(guardDisabledSimHandsFree = enabled)
        }
    }

    /** The "new SIM" notification for [refs] was posted (or suppressed). */
    suspend fun markNewSimsNotified(refs: List<SimRef>) {
        store.updateData {
            val valid = it.withInstallValidated(installId)
            valid.copy(simRegistry = valid.simRegistry.withNewSimNotified(refs))
        }
    }
}
