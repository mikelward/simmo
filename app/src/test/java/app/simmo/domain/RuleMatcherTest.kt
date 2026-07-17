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
}
