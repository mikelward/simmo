package app.simmo.ui

import app.simmo.domain.ActiveSim
import app.simmo.domain.PhoneAccountRef
import app.simmo.domain.Rule
import app.simmo.domain.RuleAction
import app.simmo.domain.RuleMatcher
import app.simmo.domain.SimRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        assertNull(row.pause)
        assertEquals(ActionUi.UseSim("Telstra AU"), row.action)
    }

    @Test
    fun `rule with a disabled sim is paused as disabled`() {
        val row = Rule(
            RuleMatcher.Country("AU"),
            RuleAction.UseSim(SimRef(7, "Vodafone", "Voda AU")),
        ).toRow(listOf(telstra))
        assertEquals(RulePause.SIM_DISABLED, row.pause)
    }

    @Test
    fun `rule with an ambiguous re-binding is paused as needing re-linking`() {
        // Same carrier active but the stored display name no longer matches:
        // skipped because it can't re-bind unambiguously, not because the SIM
        // is disabled — the two states point at different recoveries.
        val row = Rule(
            RuleMatcher.Country("AU"),
            RuleAction.UseSim(SimRef(99, "Telstra", "Telstra old")),
        ).toRow(listOf(telstra))
        assertEquals(RulePause.SIM_AMBIGUOUS, row.pause)
    }

    @Test
    fun `any-destination defaults have no country label`() {
        val row = Rule(RuleMatcher.AnyDestination, RuleAction.SystemDefault).toRow(emptyList())
        assertEquals(null, row.matcherCountryLabel)
        assertNull(row.pause)
    }
}
