package app.simmo.domain

import com.google.i18n.phonenumbers.PhoneNumberUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CountryGroupsTest {

    private val euEea = CountryGroups.members(CountryGroups.EU_EEA)

    @Test
    fun `eu eea covers the union, the eea efta states, and eu territories`() {
        // Spot checks across the buckets rather than restating the list.
        for (member in listOf("FR", "DE", "MT", "HR", "IS", "LI", "NO", "AX", "GP", "RE", "SJ")) {
            assertTrue(member, member in euEea)
        }
        assertEquals(38, euEea.size)
    }

    @Test
    fun `the usual suspects are out`() {
        // In neither the EU nor the EEA, whatever individual carriers bundle:
        // Switzerland, the UK, the EU-roaming-area candidates, microstates,
        // and the non-EU territories of member states.
        for (excluded in listOf("CH", "GB", "UA", "MD", "RS", "AD", "MC", "SM", "VA", "GI", "FO", "GL", "AW", "CW", "BQ", "BL")) {
            assertFalse(excluded, excluded in euEea)
        }
    }

    @Test
    fun `usa-and-territories group carries every territory, explicitly named`() {
        // All five territories, under a name that says so — territory
        // inclusion must never be silent, because some prepaid tiers bill
        // the Pacific ones internationally (Codex on PR #17). States-only is
        // the plain United States country entry, not a group. CA/MX
        // inclusion is a plan tier and lives in the North America group.
        val usa = CountryGroups.members(CountryGroups.USA_TERRITORIES)
        assertEquals(listOf("US", "PR", "VI", "GU", "AS", "MP"), usa)
        assertFalse("CA" in usa)
        assertFalse("MX" in usa)
    }

    @Test
    fun `north america is the usa-and-territories group plus canada and mexico`() {
        assertEquals(
            CountryGroups.members(CountryGroups.USA_TERRITORIES) + listOf("CA", "MX"),
            CountryGroups.members(CountryGroups.NORTH_AMERICA),
        )
    }

    @Test
    fun `caribbean group is the plus-one world minus the usa group`() {
        val caribbean = CountryGroups.members(CountryGroups.CARIBBEAN_NANP)
        assertEquals(18, caribbean.size)
        for (member in listOf("JM", "DO", "BS", "TC", "TT", "SX")) {
            assertTrue(member, member in caribbean)
        }
        // No overlap with the domestic sets: the guard group never swallows
        // the countries a USA or North America rule should keep matching.
        val northAmerica = CountryGroups.members(CountryGroups.NORTH_AMERICA)
        assertTrue(caribbean.intersect(northAmerica.toSet()).isEmpty())
    }

    @Test
    fun `members are real dialable regions, uppercase, without duplicates`() {
        val supported = PhoneNumberUtil.getInstance().supportedRegions
        for (id in CountryGroups.allIds()) {
            val members = CountryGroups.members(id)
            for (member in members) {
                assertEquals(member, member.uppercase(), member)
                assertTrue("$member not a libphonenumber region", member in supported)
            }
            assertEquals(members.size, members.distinct().size)
        }
    }

    @Test
    fun `unknown group ids resolve to no members, never an error`() {
        assertEquals(emptyList<String>(), CountryGroups.members("from_the_future"))
    }

    @Test
    fun `every offered group id resolves to members`() {
        for (id in CountryGroups.allIds()) {
            assertTrue(id, CountryGroups.members(id).isNotEmpty())
        }
    }
}
