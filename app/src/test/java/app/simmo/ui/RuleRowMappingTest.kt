package app.simmo.ui

import app.simmo.domain.ActiveSim
import app.simmo.domain.PhoneAccountRef
import app.simmo.domain.CallingRule
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
    fun `country display name drops the calling code for sorting`() {
        assertEquals("Australia", countryDisplayName("AU"))
        assertEquals("United States", countryDisplayName("us"))
    }

    @Test
    fun `country display name falls back to the raw region when it is not a valid code`() {
        // A malformed region must degrade to the raw code, never throw.
        assertEquals("ZZZ", countryDisplayName("ZZZ"))
        assertEquals("", countryDisplayName(""))
    }

    @Test
    fun `rule with an active sim is enabled`() {
        val row = CallingRule(
            RuleMatcher.Country("AU"),
            RuleAction.UseSim(SimRef(1, "Telstra", "Telstra AU")),
        ).toRow(listOf(telstra))
        assertNull(row.pause)
        assertEquals(ActionUi.UseSim("Telstra AU"), row.action)
    }

    @Test
    fun `rule with a disabled sim is paused as disabled`() {
        val row = CallingRule(
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
        val row = CallingRule(
            RuleMatcher.Country("AU"),
            RuleAction.UseSim(SimRef(99, "Telstra", "Telstra old")),
        ).toRow(listOf(telstra))
        assertEquals(RulePause.SIM_AMBIGUOUS, row.pause)
    }

    @Test
    fun `rule with a registered calling account shows its label and runs`() {
        val sip = PhoneAccountRef("sip-1")
        val row = CallingRule(
            RuleMatcher.Country("AU"),
            RuleAction.HandOff.ViaPhoneAccount(sip, "SIP work"),
        ).toRow(emptyList(), availableAccounts = setOf(sip))
        assertNull(row.pause)
        assertEquals(ActionUi.HandOffApp("SIP work"), row.action)
    }

    @Test
    fun `rule whose calling account is gone is paused`() {
        val row = CallingRule(
            RuleMatcher.Country("AU"),
            RuleAction.HandOff.ViaPhoneAccount(PhoneAccountRef("sip-1"), "SIP work"),
        ).toRow(emptyList())
        assertEquals(RulePause.ACCOUNT_UNAVAILABLE, row.pause)
        // The stored label still names the target while it's gone.
        assertEquals(ActionUi.HandOffApp("SIP work"), row.action)
    }

    @Test
    fun `account rule stored before labels falls back to the account id`() {
        val row = CallingRule(
            RuleMatcher.Country("AU"),
            RuleAction.HandOff.ViaPhoneAccount(PhoneAccountRef("acct-gv")),
        ).toRow(emptyList())
        assertEquals(ActionUi.HandOffApp("acct-gv"), row.action)
    }

    @Test
    fun `multi-country matcher joins its country labels`() {
        val row = CallingRule(
            RuleMatcher.Countries(listOf("AU", "NZ")),
            RuleAction.SystemDefault,
        ).toRow(emptyList())
        assertEquals("+61 Australia, +64 New Zealand", row.matcherCountryLabel)
    }

    @Test
    fun `group matcher renders its label ahead of hand-picked countries`() {
        val row = CallingRule(
            RuleMatcher.Countries(listOf("GB"), listOf("eu_eea")),
            RuleAction.SystemDefault,
        ).toRow(emptyList(), groupLabel = { "EU/EEA" })
        assertEquals("EU/EEA, +44 United Kingdom", row.matcherCountryLabel)
    }

    @Test
    fun `an unknown stored group id still shows rather than vanishing`() {
        val row = CallingRule(
            RuleMatcher.Countries(groupIds = listOf("from_the_future")),
            RuleAction.SystemDefault,
        ).toRow(emptyList(), groupLabel = { it })
        assertEquals("from_the_future", row.matcherCountryLabel)
    }

    @Test
    fun `any-destination defaults have no country label`() {
        val row = CallingRule(RuleMatcher.AnyDestination, RuleAction.SystemDefault).toRow(emptyList())
        assertEquals(null, row.matcherCountryLabel)
        assertNull(row.pause)
    }

    @Test
    fun `the rule's enabled flag carries into the row`() {
        val on = CallingRule(RuleMatcher.AnyDestination, RuleAction.SystemDefault).toRow(emptyList())
        assertEquals(true, on.enabled)
        val off = CallingRule(RuleMatcher.AnyDestination, RuleAction.SystemDefault, enabled = false)
            .toRow(emptyList())
        assertEquals(false, off.enabled)
    }
}
