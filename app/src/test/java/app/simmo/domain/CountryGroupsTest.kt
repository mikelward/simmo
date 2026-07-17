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
    fun `members are real dialable regions, uppercase, without duplicates`() {
        val supported = PhoneNumberUtil.getInstance().supportedRegions
        for (member in euEea) {
            assertEquals(member, member.uppercase(), member)
            assertTrue("$member not a libphonenumber region", member in supported)
        }
        assertEquals(euEea.size, euEea.distinct().size)
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
