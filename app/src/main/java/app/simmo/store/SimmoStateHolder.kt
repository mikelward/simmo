package app.simmo.store

import android.util.Log
import androidx.datastore.core.DataStore
import app.simmo.domain.ActiveSim
import app.simmo.domain.CustomGroup
import app.simmo.domain.DataRuleBook
import app.simmo.domain.RegisteredSim
import app.simmo.domain.withGroupSaved
import app.simmo.domain.withPendingGroupRemovalsPurged
import app.simmo.domain.CallingRuleBook
import app.simmo.domain.newRuleId
import app.simmo.domain.SimRef
import app.simmo.domain.recordSeen
import app.simmo.domain.withNewSimNotified
import app.simmo.domain.withRulePromptCleared
import app.simmo.domain.withoutSim
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
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
            // A transient read failure must not kill the eager collector: the
            // exception would land in the scope's handler, this StateFlow
            // would stay null for the whole process, and every call would
            // proceed ruleless (Codex on PR #52). Corruption never gets here —
            // the store's corruption handler replaces the file before the flow
            // emits — so what's retried is genuine I/O, with capped backoff.
            .retryWhen { cause, attempt ->
                Log.e("SimmoStateHolder", "State read failed; retrying", cause)
                delay((1_000L shl attempt.coerceAtMost(6L).toInt()).coerceAtMost(60_000L))
                true
            }
            .map { it.withInstallValidated(installId) }
            .stateIn(scope, SharingStarted.Eagerly, initialValue = null)

    val current: SimmoState? get() = state.value

    // Every write transform validates first for the same reason the read side
    // does: a mutation racing the launch-time adoption write must not operate
    // on restored state whose old-device subscription IDs could collide with
    // this device's (e.g. recordSeen ID-overwriting a restored registry row).

    suspend fun updateRules(transform: (CallingRuleBook) -> CallingRuleBook) {
        store.updateData {
            val valid = it.withInstallValidated(installId)
            valid.copy(rules = transform(valid.rules).withMintedIds())
        }
    }

    suspend fun updateDataRules(transform: (DataRuleBook) -> DataRuleBook) {
        store.updateData {
            val valid = it.withInstallValidated(installId)
            valid.copy(dataRules = transform(valid.dataRules).withMintedIds())
        }
    }

    /**
     * Like [updateGroupsAndRules] for the data list: groups built in the
     * picker while making this data rule commit in the same transaction, so
     * the state never holds a rule pointing at an unsaved group.
     */
    suspend fun updateGroupsAndDataRules(
        pendingGroups: List<CustomGroup>,
        transform: (DataRuleBook) -> DataRuleBook,
    ) {
        store.updateData {
            val valid = it.withInstallValidated(installId)
            val merged = pendingGroups.fold(valid.customGroups) { acc, group -> acc.withGroupSaved(group) }
            valid.copy(customGroups = merged, dataRules = transform(valid.dataRules).withMintedIds())
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
    suspend fun recordSeenSims(
        active: List<ActiveSim>,
        nowMillis: Long,
        callCapableIds: Set<Int> = active.mapTo(HashSet()) { it.subscriptionId },
    ): SimCapture {
        var wasEmpty = false
        val updated = store.updateData {
            val valid = it.withInstallValidated(installId)
            wasEmpty = valid.simRegistry.isEmpty()
            valid.copy(simRegistry = valid.simRegistry.recordSeen(active, nowMillis, callCapableIds))
        }
        return SimCapture(updated.simRegistry, firstCapture = wasEmpty)
    }

    /**
     * Atomically claims the roaming watch's once-per-arrival mark (SPEC "Data
     * rules"): true only for the caller whose transaction actually changed
     * the mark. Overlapping refreshes — startup, the first subscription
     * callback, a wake broadcast — may all evaluate the same arrival; the
     * single winner posts the warning, everyone else is deduped here rather
     * than by a racy read-then-write.
     *
     * The claim also refuses an arrival the user dismissed for the trip
     * ([dismissDataArrival]). This is checked INSIDE the transaction, not
     * against [current], because `current` is published by an asynchronous
     * `stateIn` collector and can still read pre-dismiss after the dismiss
     * write has committed — a watch check queued behind a dismiss would
     * otherwise claim and re-post the warning the dismiss just cancelled
     * (Codex on PR #64). The transaction sees the freshest committed state, so
     * a committed dismiss reliably blocks the post.
     */
    suspend fun claimDataWatchMark(key: String): Boolean {
        var claimed = false
        store.updateData {
            val valid = it.withInstallValidated(installId)
            claimed = valid.dataWatchMark != key && key !in valid.dataDismissMarks
            if (claimed) valid.copy(dataWatchMark = key) else valid
        }
        return claimed
    }

    /**
     * Clears the arrival mark once its arrival is over (the user moved
     * country or the data SIM changed — see `isMarkStale`), so a later
     * return to the same place may warn once again. Compare-and-swap on
     * [observedKey]: a concurrent refresh may have already claimed a *new*
     * arrival, and unconditionally deleting that claim would let the next
     * refresh re-claim it and post a duplicate (Codex on PR #55). Returns
     * whether THIS clear removed the mark — only the winner may also cancel
     * the posted notification; a loser's cancel would tear down the fresh
     * arrival's warning.
     */
    suspend fun clearDataWatchMark(observedKey: String): Boolean {
        var cleared = false
        store.updateData {
            val valid = it.withInstallValidated(installId)
            cleared = valid.dataWatchMark == observedKey
            if (cleared) valid.copy(dataWatchMark = null) else valid
        }
        return cleared
    }

    /**
     * Retires the arrival mark unconditionally — used when the per-trip dismiss
     * branch cancels the posted warning and must release whatever mark owned
     * it, which may be a *different* arrival's than the one dismissed (Codex on
     * PR #64). Unlike the compare-and-swap [clearDataWatchMark] this is only
     * called from inside `dataWatchMutex`, where no concurrent claim can race
     * it, so it doesn't need to guard against deleting a fresh claim.
     */
    suspend fun clearDataWatchMark() {
        store.updateData {
            val valid = it.withInstallValidated(installId)
            if (valid.dataWatchMark == null) valid else valid.copy(dataWatchMark = null)
        }
    }

    /**
     * Records that the user chose "Ignore for this trip" on the triage card
     * (SPEC "Data rules" → Triage): adds [key] to the per-trip dismiss set so
     * the matching arrival's card and notification stay quiet until the arrival
     * ends. Additive, not a replace, so a second dismiss on the same trip (a
     * different problem shape) doesn't drop the first. Adding into the
     * committed set inside the transaction re-adds a duplicate as a no-op.
     */
    suspend fun dismissDataArrival(key: String) {
        store.updateData {
            val valid = it.withInstallValidated(installId)
            valid.copy(dataDismissMarks = valid.dataDismissMarks + key)
        }
    }

    /**
     * Drops every per-trip dismiss key that [isStale] accepts — once an
     * arrival is over (country or data SIM changed — see `isMarkStale`), so a
     * *return* to a dismissed place warns once again. [isStale] is evaluated
     * against the COMMITTED set INSIDE the transaction, not the caller's
     * lagging [current] snapshot: a key committed just before a country/SIM
     * change would otherwise be absent from the caller's set and never swept,
     * so a return would find it still current and suppress the new trip (Codex
     * on PR #64). Filtering rather than removing a fixed list also preserves a
     * concurrent dismiss of another arrival on the same trip. No write when
     * nothing is stale, so DataStore skips the disk touch.
     */
    suspend fun clearStaleDataDismissMarks(isStale: (String) -> Boolean) {
        store.updateData {
            val valid = it.withInstallValidated(installId)
            val kept = valid.dataDismissMarks.filterNot(isStale).toSet()
            if (kept.size == valid.dataDismissMarks.size) valid else valid.copy(dataDismissMarks = kept)
        }
    }

    /**
     * The committed state, read through the write path so it reflects every
     * prior commit: [current] is published by an asynchronous `stateIn`
     * collector and can still expose a just-committed dismiss/clear/mark's
     * prior value. The roaming watch must see committed state — a stale dismiss
     * set or watch mark would suppress or re-post a warning (Codex on PR #64).
     * The identity transform returns the same instance once the install is
     * adopted, so DataStore skips the disk write — this is a serialized read,
     * not a mutation.
     */
    suspend fun committedState(): SimmoState =
        store.updateData { it.withInstallValidated(installId) }

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
     * Purge every soft-deleted rule, data rule, and custom group at once — the
     * point a delete becomes final, when the rules screen is left. One atomic
     * write so a leave can't finalize some lists but not others.
     */
    suspend fun purgePendingRemovals() {
        store.updateData {
            val valid = it.withInstallValidated(installId)
            valid.copy(
                rules = valid.rules.withPendingRemovalsPurged(),
                dataRules = valid.dataRules.withPendingRemovalsPurged(),
                customGroups = valid.customGroups.withPendingGroupRemovalsPurged(),
            )
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
        transform: (CallingRuleBook) -> CallingRuleBook,
    ) {
        store.updateData {
            val valid = it.withInstallValidated(installId)
            val merged = pendingGroups.fold(valid.customGroups) { acc, group -> acc.withGroupSaved(group) }
            valid.copy(customGroups = merged, rules = transform(valid.rules).withMintedIds())
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

    // Mint an id for any rule written without one, at the single persistence
    // boundary every rule write passes through. Creation sites (the editor, the
    // new-SIM prompt, the chooser's "remember", any future one) can add a rule
    // without threading id-minting through themselves and never leave a blank
    // id in stored state — which the id-keyed editor would be unable to address
    // (Codex on PR #60). Cheap no-op once every rule has an id; a duplicate
    // already carries a fresh id, so it is left untouched.
    private fun CallingRuleBook.withMintedIds(): CallingRuleBook =
        if (rules.none { it.id.isBlank() }) this
        else copy(rules = rules.map { if (it.id.isBlank()) it.copy(id = newRuleId()) else it })

    private fun DataRuleBook.withMintedIds(): DataRuleBook =
        if (rules.none { it.id.isBlank() }) this
        else copy(rules = rules.map { if (it.id.isBlank()) it.copy(id = newRuleId()) else it })
}
