package app.simmo.domain

import org.junit.Assert.assertEquals
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
}
