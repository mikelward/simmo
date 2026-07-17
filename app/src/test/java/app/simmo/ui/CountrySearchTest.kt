package app.simmo.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The country picker's fuzzy search + ranking. The two headline cases the
 * feature was asked for: "+44 / GB / United Kingdom / UK" all find the UK, and
 * "+1 / US / USA / America" all find the United States.
 */
class CountrySearchTest {

    private fun option(region: String, name: String, callingCode: Int) =
        CountryOptionUi(region, "+$callingCode $name", countrySearchTerms(region, name, callingCode))

    private val gb = option("GB", "United Kingdom", 44)
    private val us = option("US", "United States", 1)
    private val au = option("AU", "Australia", 61)
    private val fr = option("FR", "France", 33)

    @Test
    fun `united kingdom is found by dial code, iso codes, name and aliases`() {
        for (query in listOf("+44", "44", "GB", "gb", "GBR", "United Kingdom", "united kingdom", "Uk", "UK", "Britain")) {
            assertTrue("query=$query", gb.matches(query))
        }
    }

    @Test
    fun `united states is found by dial code, iso codes, name and aliases`() {
        for (query in listOf("+1", "1", "US", "us", "USA", "United States", "United States of America", "America")) {
            assertTrue("query=$query", us.matches(query))
        }
    }

    @Test
    fun `a blank query matches and ranks everything unchanged`() {
        assertTrue(gb.matches(""))
        assertTrue(gb.matches("   "))
        val all = listOf(gb, us, au, fr)
        assertEquals(all, rankCountries(all, ""))
    }

    @Test
    fun `a non-subsequence query does not match`() {
        assertFalse(gb.matches("France"))
        assertFalse(gb.matches("+61"))
        assertFalse(au.matches("United Kingdom"))
    }

    @Test
    fun `capitals only match capitals`() {
        val southAfrica = option("ZA", "South Africa", 27)
        val samoa = option("WS", "Samoa", 685)
        // "SA" is a capitals acronym match for South Africa but not Samoa,
        // whose name has no second capital and whose codes are WS/WSM.
        assertTrue(southAfrica.matches("SA"))
        assertFalse(samoa.matches("SA"))
        // Lowercase "sa" still matches Samoa by name.
        assertTrue(samoa.matches("sa"))
    }

    @Test
    fun `exact and prefix rank above an incidental interior match`() {
        // Lowercase "us" still fuzzy-matches Australia (a-U-S), but ranks it
        // below the United States rather than excluding it.
        assertTrue(au.matches("us"))
        val ranked = rankCountries(listOf(au, us), "us")
        assertEquals(listOf(us, au), ranked)
    }

    @Test
    fun `matching ignores case, spacing and accents`() {
        val ci = option("CI", "Côte d'Ivoire", 225)
        assertTrue(ci.matches("cote"))
        assertTrue(ci.matches("Côte"))
        assertTrue(ci.matches("ivoire"))
    }
}
