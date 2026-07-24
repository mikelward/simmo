package app.simmo.domain

import java.util.UUID
import kotlinx.serialization.Serializable

/**
 * A user-configurable country group (SPEC "Calling rules" → country groups): a
 * named set of countries a rule can match as a single entry. This represents
 * both Simmo's preseeded groups and groups the user creates.
 *
 * [id] is stable; newly created groups carry [ID_PREFIX], while preseeded groups
 * retain their well-known ids so existing rules keep referring to them.
 * [regionCodes] are the member countries (ISO regions, uppercased). Persisted;
 * membership is resolved on the decision path from the in-memory snapshot, the
 * same as built-in groups resolve from their static table — never from I/O.
 */
@Serializable
data class CustomGroup(
    val id: String,
    val name: String,
    val regionCodes: List<String> = emptyList(),
    /**
     * A soft-deleted group awaiting purge: shown struck-through in the Groups
     * list with an Undo affordance. Unlike a soft-deleted rule, it stays
     * **resolvable** — referencing rules keep matching its countries until it's
     * actually purged (on leaving the screen), so a mistap doesn't break those
     * rules during the undo window; only the deliberate leave commits the loss.
     * Defaults false.
     */
    val pendingRemoval: Boolean = false,
) {
    companion object {
        /** Prefix that keeps a newly created id disjoint from every preseeded id. */
        const val ID_PREFIX = "custom:"

        /**
         * A fresh, globally-unique id for a new group. A random UUID — never a
         * sequence derived from the current groups — so a deleted group's id can
         * never be handed to a later group, which would silently re-point any
         * rule that still referenced the deleted id at the new group's countries.
         */
        fun newId(): String = "$ID_PREFIX${UUID.randomUUID()}"
    }
}

/** Adds [group] or replaces the existing one with the same id, order preserved. */
fun List<CustomGroup>.withGroupSaved(group: CustomGroup): List<CustomGroup> =
    if (any { it.id == group.id }) map { if (it.id == group.id) group else it } else this + group

/** Soft-deletes the group with [id] (struck-through, still resolvable) pending purge. */
fun List<CustomGroup>.withGroupMarkedForRemoval(id: String): List<CustomGroup> =
    map { if (it.id == id) it.copy(pendingRemoval = true) else it }

/** Undoes a soft-delete: clears [CustomGroup.pendingRemoval] on the group with [id]. */
fun List<CustomGroup>.withGroupRemovalUndone(id: String): List<CustomGroup> =
    map { if (it.id == id) it.copy(pendingRemoval = false) else it }

/** Purges every soft-deleted group — the point at which referencing rules stop matching them. */
fun List<CustomGroup>.withPendingGroupRemovalsPurged(): List<CustomGroup> = filterNot { it.pendingRemoval }

/**
 * Member regions of [groupId] from the persisted in-memory group snapshot.
 * Empty for an unknown or deleted id, so a rule's other countries still match
 * and the decision path never errors.
 */
fun groupMembers(groupId: String, customGroups: Map<String, List<String>>): List<String> =
    customGroups[groupId].orEmpty()
