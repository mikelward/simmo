package app.simmo.ui

import app.simmo.domain.ActiveSim
import app.simmo.domain.PhoneAccountRef
import app.simmo.domain.Rule
import app.simmo.domain.RuleAction
import app.simmo.domain.RuleMatcher
import app.simmo.domain.SimRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleRowMappingTest {

    private val telstra = ActiveSim(1, "Telstra", "Telstra AU", PhoneAccountRef("a1"), "au")

    @Test
    fun `country matcher renders calling code and country name`() {
        assertEquals("+61 Australia", countryLabel("AU"))
        assertEquals("+1 United States", countryLabel("US"))
        // Lower-case regions from stored rules normalize.
        assertEquals("+64 New Zealand", countryLabel("nz"))
    }

    @Test
    fun `rule with an active sim is enabled`() {
        val row = Rule(
            RuleMatcher.Country("AU"),
            RuleAction.UseSim(SimRef(1, "Telstra", "Telstra AU")),
        ).toRow(listOf(telstra))
        assertTrue(row.enabled)
        assertEquals(ActionUi.UseSim("Telstra AU"), row.action)
    }

    @Test
    fun `rule with a disabled sim is greyed`() {
        val row = Rule(
            RuleMatcher.Country("AU"),
            RuleAction.UseSim(SimRef(7, "Vodafone", "Voda AU")),
        ).toRow(listOf(telstra))
        assertFalse(row.enabled)
    }

    @Test
    fun `any-destination defaults have no country label`() {
        val row = Rule(RuleMatcher.AnyDestination, RuleAction.SystemDefault).toRow(emptyList())
        assertEquals(null, row.matcherCountryLabel)
        assertTrue(row.enabled)
    }
}
