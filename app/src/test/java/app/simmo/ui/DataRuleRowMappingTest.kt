package app.simmo.ui

import app.simmo.domain.ActiveSim
import app.simmo.domain.DataExpectation
import app.simmo.domain.DataRule
import app.simmo.domain.DataSimScope
import app.simmo.domain.PhoneAccountRef
import app.simmo.domain.RuleMatcher
import app.simmo.domain.SimRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Data rows read "where the user is" (SPEC "Data rules"): plain country
 * names, no dialing codes, and the same greying rules as calling rows.
 */
class DataRuleRowMappingTest {

    private val telstra = ActiveSim(1, "Telstra", "Telstra AU", PhoneAccountRef("a1"), "au")

    @Test
    fun `matcher label uses plain country names`() {
        val row = DataRule(
            RuleMatcher.Countries(regionCodes = listOf("AU", "nz")),
            DataExpectation.AlwaysWarn,
        ).toRow(emptyList())
        assertEquals("Australia, New Zealand", row.matcherCountryLabel)
        assertEquals(DataExpectationUi.AlwaysWarn, row.expectation)
    }

    @Test
    fun `anywhere matcher renders as null label`() {
        val row = DataRule(RuleMatcher.AnyDestination, DataExpectation.AlwaysWarn).toRow(emptyList())
        assertNull(row.matcherCountryLabel)
    }

    @Test
    fun `group labels lead and resolve through the callback`() {
        val row = DataRule(
            RuleMatcher.Countries(regionCodes = listOf("CH"), groupIds = listOf("eu_eea")),
            DataExpectation.RoamingOk(DataSimScope.HomedInMatchedCountries),
        ).toRow(emptyList(), groupLabel = { "EU/EEA" })
        assertEquals("EU/EEA, Switzerland", row.matcherCountryLabel)
        assertEquals(DataExpectationUi.RoamingOkHomedInMatched, row.expectation)
    }

    @Test
    fun `use-sim expectation resolves the SIM and pauses when it cannot act`() {
        val active = DataRule(
            RuleMatcher.Country("AU"),
            DataExpectation.UseSimForData(SimRef(1, "Telstra", "Telstra AU")),
        ).toRow(listOf(telstra))
        assertEquals(DataExpectationUi.UseSimForData("Telstra AU"), active.expectation)
        assertNull(active.pause)

        val disabled = DataRule(
            RuleMatcher.Country("AU"),
            DataExpectation.UseSimForData(SimRef(9, "Optus", "Optus travel")),
        ).toRow(listOf(telstra))
        assertEquals(RulePause.SIM_DISABLED, disabled.pause)
    }

    @Test
    fun `use-local expectation maps to its own label and never pauses`() {
        // No stored SIM, so it never greys — a no-unique-match is a runtime
        // skip, like the calling matching-country action.
        val row = DataRule(
            RuleMatcher.Country("AU"),
            DataExpectation.UseLocalSimForData,
        ).toRow(emptyList())
        assertEquals(DataExpectationUi.UseLocalSimForData, row.expectation)
        assertNull(row.pause)
    }

    @Test
    fun `roaming-ok scopes map to their own labels`() {
        val any = DataRule(RuleMatcher.Country("AU"), DataExpectation.RoamingOk()).toRow(emptyList())
        assertEquals(DataExpectationUi.RoamingOkAnySim, any.expectation)
        assertNull(any.pause)

        val sims = DataRule(
            RuleMatcher.Country("AU"),
            DataExpectation.RoamingOk(
                DataSimScope.Sims(
                    listOf(SimRef(1, "Telstra", "Telstra AU"), SimRef(2, "Vodafone", "")),
                ),
            ),
        ).toRow(emptyList())
        assertEquals(DataExpectationUi.RoamingOkSims("Telstra AU, Vodafone"), sims.expectation)
    }

    @Test
    fun `sim-scoped roaming rule pauses when no scoped SIM can act`() {
        // Evaluation skips a scope none of whose SIMs resolve, so the row
        // must grey like an unresolvable use-for-data rule.
        val rule = DataRule(
            RuleMatcher.Country("AU"),
            DataExpectation.RoamingOk(
                DataSimScope.Sims(listOf(SimRef(9, "Optus", "Optus travel"))),
            ),
        )
        assertEquals(RulePause.SIM_DISABLED, rule.toRow(emptyList()).pause)
        // One resolvable SIM is enough to keep the rule live.
        val partly = DataRule(
            RuleMatcher.Country("AU"),
            DataExpectation.RoamingOk(
                DataSimScope.Sims(
                    listOf(SimRef(9, "Optus", "Optus travel"), SimRef(1, "Telstra", "Telstra AU")),
                ),
            ),
        )
        assertNull(partly.toRow(listOf(telstra)).pause)
    }

    @Test
    fun `user-disabled rules carry the flag through`() {
        val row = DataRule(
            RuleMatcher.Country("AU"),
            DataExpectation.AlwaysWarn,
            enabled = false,
        ).toRow(emptyList())
        assertEquals(false, row.enabled)
    }
}
