package app.simmo.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomGroupTest {

    @Test
    fun `groupMembers resolves a preseeded id from the snapshot`() {
        val snapshot = mapOf(CountryGroups.EU_EEA to listOf("FR", "DE"))
        assertEquals(
            listOf("FR", "DE"),
            groupMembers(CountryGroups.EU_EEA, customGroups = snapshot),
        )
    }

    @Test
    fun `a preseeded id has no static fallback after deletion`() {
        assertEquals(emptyList<String>(), groupMembers(CountryGroups.EU_EEA, customGroups = emptyMap()))
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

    @Test
    fun `matchesDefault ignores member order but sees name and membership changes`() {
        val default = CountryGroups.preseededGroup(CountryGroups.EU_EEA)!!
        // Same name and members in a different order still counts as default.
        assertTrue(default.copy(regionCodes = default.regionCodes.reversed()).matchesDefault(default))
        // A rename or a membership change does not.
        assertFalse(default.copy(name = "My Europe").matchesDefault(default))
        assertFalse(default.copy(regionCodes = default.regionCodes + "GB").matchesDefault(default))
    }

    @Test
    fun `restoring one shipped group resets an edited copy in place`() {
        val eu = CountryGroups.EU_EEA
        val groups = listOf(
            CustomGroup(eu, "My Europe", listOf("FR")),
            CustomGroup("custom:1", "Zone", listOf("AU")),
        )
        val restored = groups.withDefaultGroupRestored(eu)
        // Position preserved; the seed is back to its shipped name and members.
        assertEquals(listOf(eu, "custom:1"), restored.map { it.id })
        assertEquals(CountryGroups.preseededGroup(eu), restored.first())
        // The user's own group is untouched.
        assertEquals(groups[1], restored[1])
    }

    @Test
    fun `restoring one shipped group re-adds a deleted one in shipped order`() {
        // North America deleted; EU/EEA kept, plus a user group. Restoring NA
        // puts it back between EU/EEA and USA + territories' canonical slots —
        // i.e. after every earlier shipped group, before user groups.
        val groups = listOf(
            CountryGroups.preseededGroup(CountryGroups.EU_EEA)!!,
            CountryGroups.preseededGroup(CountryGroups.USA_TERRITORIES)!!,
            CustomGroup("custom:1", "Zone", listOf("AU")),
        )
        val restored = groups.withDefaultGroupRestored(CountryGroups.NORTH_AMERICA)
        assertEquals(
            listOf(
                CountryGroups.EU_EEA,
                CountryGroups.USA_TERRITORIES,
                CountryGroups.NORTH_AMERICA,
                "custom:1",
            ),
            restored.map { it.id },
        )
        assertEquals(CountryGroups.preseededGroup(CountryGroups.NORTH_AMERICA), restored[2])
    }

    @Test
    fun `restoring an unknown id is a no-op`() {
        val groups = listOf(CustomGroup("custom:1", "Zone", listOf("AU")))
        assertEquals(groups, groups.withDefaultGroupRestored("custom:1"))
        assertEquals(groups, groups.withDefaultGroupRestored("custom:404"))
    }

    @Test
    fun `restoring all shipped groups resets edits, re-adds deletions, keeps user groups`() {
        val user = CustomGroup("custom:1", "Zone", listOf("AU"))
        val groups = listOf(
            // Edited seed and a user group; the other three seeds are deleted.
            CustomGroup(CountryGroups.EU_EEA, "My Europe", listOf("FR")),
            user,
        )
        val restored = groups.withDefaultGroupsRestored()
        // All four shipped groups back in shipped order, then the user's group.
        assertEquals(CountryGroups.preseededGroups() + user, restored)
    }
}
