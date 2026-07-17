package app.simmo.store

import androidx.datastore.core.DataStore
import app.simmo.domain.ActiveSim
import app.simmo.domain.RuleBook
import app.simmo.domain.SimRef
import app.simmo.domain.recordSeen
import app.simmo.domain.withRulePromptCleared
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

    /** Registry capture on every subscription change (SPEC "Disabled-SIM assist"). */
    suspend fun recordSeenSims(active: List<ActiveSim>, nowMillis: Long) {
        store.updateData {
            val valid = it.withInstallValidated(installId)
            valid.copy(simRegistry = valid.simRegistry.recordSeen(active, nowMillis))
        }
    }

    /** The new-SIM prompt for [ref] was answered — added a rule or dismissed. */
    suspend fun markSimRulePromptAnswered(ref: SimRef) {
        store.updateData {
            val valid = it.withInstallValidated(installId)
            valid.copy(simRegistry = valid.simRegistry.withRulePromptCleared(ref))
        }
    }
}
