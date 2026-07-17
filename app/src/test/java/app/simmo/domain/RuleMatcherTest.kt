package app.simmo.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RuleMatcherTest {

    @Test
    fun `matchers expose their regions in display order`() {
        assertEquals(emptyList<String>(), RuleMatcher.AnyDestination.regionCodes())
        assertEquals(listOf("AU"), RuleMatcher.Country("AU").regionCodes())
        assertEquals(listOf("NZ", "AU"), RuleMatcher.Countries(listOf("NZ", "AU")).regionCodes())
    }

    @Test
    fun `a single country keeps the legacy stored form`() {
        // Existing one-country rules must keep round-tripping through the
        // original "country" discriminator so older app versions can still
        // read the state file.
        assertEquals(RuleMatcher.Country("AU"), countryMatcher(listOf("AU")))
    }

    @Test
    fun `several countries build a set matcher`() {
        assertEquals(
            RuleMatcher.Countries(listOf("AU", "NZ")),
            countryMatcher(listOf("AU", "NZ")),
        )
    }

    @Test
    fun `duplicate regions collapse, case-insensitively`() {
        assertEquals(RuleMatcher.Country("AU"), countryMatcher(listOf("AU", "au")))
        assertEquals(
            RuleMatcher.Countries(listOf("AU", "NZ")),
            countryMatcher(listOf("AU", "NZ", "au")),
        )
    }

    @Test
    fun `a country matcher cannot be empty`() {
        assertThrows(IllegalArgumentException::class.java) { countryMatcher(emptyList()) }
    }

    @Test
    fun `matchers expose their group ids`() {
        assertEquals(emptyList<String>(), RuleMatcher.AnyDestination.groupIds())
        assertEquals(emptyList<String>(), RuleMatcher.Country("AU").groupIds())
        assertEquals(
            listOf(CountryGroups.EU_EEA),
            RuleMatcher.Countries(groupIds = listOf(CountryGroups.EU_EEA)).groupIds(),
        )
    }

    @Test
    fun `destination matcher without groups keeps the country forms`() {
        assertEquals(RuleMatcher.Country("AU"), destinationMatcher(listOf("AU"), emptyList()))
        assertEquals(
            RuleMatcher.Countries(listOf("AU", "NZ")),
            destinationMatcher(listOf("AU", "NZ"), emptyList()),
        )
    }

    @Test
    fun `destination matcher with groups carries both, deduped`() {
        assertEquals(
            RuleMatcher.Countries(listOf("GB"), listOf(CountryGroups.EU_EEA)),
            destinationMatcher(listOf("GB", "gb"), listOf(CountryGroups.EU_EEA, CountryGroups.EU_EEA)),
        )
        // A group alone needs no countries.
        assertEquals(
            RuleMatcher.Countries(emptyList(), listOf(CountryGroups.EU_EEA)),
            destinationMatcher(emptyList(), listOf(CountryGroups.EU_EEA)),
        )
    }

    @Test
    fun `a destination matcher cannot be empty either`() {
        assertThrows(IllegalArgumentException::class.java) { destinationMatcher(emptyList(), emptyList()) }
    }
}
