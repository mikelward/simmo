package app.simmo.store

import androidx.datastore.core.DataStore
import app.simmo.domain.ActiveSim
import app.simmo.domain.RuleBook
import app.simmo.domain.recordSeen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
    installId: String,
) {
    init {
        // Restore guard: state written by a different install gets its
        // subscription IDs invalidated before anything routes with it.
        scope.launch {
            store.updateData { it.withInstallValidated(installId) }
        }
    }

    val state: StateFlow<SimmoState?> =
        store.data.stateIn(scope, SharingStarted.Eagerly, initialValue = null)

    val current: SimmoState? get() = state.value

    suspend fun updateRules(transform: (RuleBook) -> RuleBook) {
        store.updateData { it.copy(rules = transform(it.rules)) }
    }

    /** Registry capture on every subscription change (SPEC "Disabled-SIM assist"). */
    suspend fun recordSeenSims(active: List<ActiveSim>, nowMillis: Long) {
        store.updateData { it.copy(simRegistry = it.simRegistry.recordSeen(active, nowMillis)) }
    }
}
