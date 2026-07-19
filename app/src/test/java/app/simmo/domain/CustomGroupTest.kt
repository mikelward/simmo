package app.simmo.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomGroupTest {

    @Test
    fun `groupMembers resolves built-in ids from the static table`() {
        assertEquals(
            CountryGroups.members(CountryGroups.EU_EEA),
            groupMembers(CountryGroups.EU_EEA, customGroups = emptyMap()),
        )
    }

    @Test
    fun `groupMembers resolves custom ids from the snapshot`() {
        val custom = mapOf("custom:1" to listOf("GB", "FR"))
        assertEquals(listOf("GB", "FR"), groupMembers("custom:1", custom))
    }

    @Test
    fun `an unknown id resolves to no members`() {
        assertEquals(emptyList<String>(), groupMembers("custom:404", customGroups = emptyMap()))
    }

    @Test
    fun `a new id carries the prefix that keeps it off the built-in id space`() {
        val id = CustomGroup.newId()
        assertTrue(id.startsWith(CustomGroup.ID_PREFIX))
        assertEquals(emptyList<String>(), CountryGroups.members(id))
    }

    @Test
    fun `each new id is unique, so a deleted group's id is never reused`() {
        val ids = (1..100).map { CustomGroup.newId() }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `marking a group soft-deletes it in place, undo clears it, purge drops it`() {
        val groups = listOf(
            CustomGroup("custom:1", "A", listOf("GB")),
            CustomGroup("custom:2", "B", listOf("FR")),
        )
        val marked = groups.withGroupMarkedForRemoval("custom:2")
        // The group stays at its position, just flagged — order preserved.
        assertEquals(listOf("custom:1", "custom:2"), marked.map { it.id })
        assertTrue(marked.single { it.id == "custom:2" }.pendingRemoval)
        assertFalse(marked.single { it.id == "custom:1" }.pendingRemoval)
        // Undo un-marks it in place.
        assertFalse(marked.withGroupRemovalUndone("custom:2").single { it.id == "custom:2" }.pendingRemoval)
        // Purge drops only the flagged group.
        assertEquals(listOf("custom:1"), marked.withPendingGroupRemovalsPurged().map { it.id })
    }

    @Test
    fun `a soft-deleted group still resolves its members until purge`() {
        // A tombstoned group must keep resolving so a rule referencing it doesn't
        // break during the undo window; only the purge commits the loss.
        val marked = listOf(CustomGroup("custom:1", "A", listOf("GB", "FR")))
            .withGroupMarkedForRemoval("custom:1")
        val snapshot = marked.associate { it.id to it.regionCodes }
        assertEquals(listOf("GB", "FR"), groupMembers("custom:1", snapshot))
    }
}
