package app.simmo.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SimStatusTest {

    private val telstra =
        ActiveSim(1, "Telstra", "Telstra AU", PhoneAccountRef("acct-telstra"), countryIso = "au")
    private val tmobile =
        ActiveSim(2, "T-Mobile", "T-Mobile US", PhoneAccountRef("acct-tmobile"), countryIso = "us")
    private val orangeData =
        ActiveSim(5, "Orange", "Orange Holiday", PhoneAccountRef("subscription:5"), countryIso = "fr")

    private fun ActiveSim.ref() = SimRef(subscriptionId, carrierName, displayName)

    private fun callingBook(vararg rules: CallingRule) = CallingRuleBook(rules.toList())

    private fun dataSnapshot(
        networkCountry: String = "AU",
        dataSim: ActiveSim = telstra,
        activeSims: List<ActiveSim> = listOf(telstra, tmobile),
    ) = DataSnapshot(
        networkCountry = networkCountry,
        activeSims = activeSims,
        dataSubscriptionId = dataSim.subscriptionId,
    )

    // --- Preferred calling SIM ---

    @Test
    fun `matching-country default prefers the local SIM`() {
        assertEquals(
            telstra.subscriptionId,
            preferredCallingSubId(
                CallingRuleBook(), // the preseeded matching-country + system-default rules
                listOf(telstra, tmobile),
                destination = "AU",
                customGroups = emptyMap(),
            ),
        )
    }

    @Test
    fun `an explicit use-SIM rule wins over the matching default`() {
        val book = callingBook(
            CallingRule(RuleMatcher.Country("AU"), RuleAction.UseSim(tmobile.ref())),
            CallingRule(RuleMatcher.AnyDestination, RuleAction.UseMatchingCountrySim),
        )
        assertEquals(
            tmobile.subscriptionId,
            preferredCallingSubId(book, listOf(telstra, tmobile), "AU", emptyMap()),
        )
    }

    @Test
    fun `a use-SIM rule whose SIM is inactive is skipped`() {
        val disabled = SimRef(9, "Optus", "Optus travel")
        val book = callingBook(
            CallingRule(RuleMatcher.Country("AU"), RuleAction.UseSim(disabled)),
            CallingRule(RuleMatcher.AnyDestination, RuleAction.UseMatchingCountrySim),
        )
        // Falls through to the matching-country default → the local SIM.
        assertEquals(
            telstra.subscriptionId,
            preferredCallingSubId(book, listOf(telstra, tmobile), "AU", emptyMap()),
        )
    }

    @Test
    fun `matching-country with no unique local SIM has no preferred`() {
        // Two SIMs both homed in AU: no unique match, so the default is skipped.
        val telstra2 = telstra.copy(subscriptionId = 3, displayName = "Telstra work")
        assertNull(
            preferredCallingSubId(
                CallingRuleBook(),
                listOf(telstra, telstra2),
                "AU",
                emptyMap(),
            ),
        )
    }

    @Test
    fun `an Ask rule means no single preferred calling SIM`() {
        val book = callingBook(
            CallingRule(RuleMatcher.Country("AU"), RuleAction.Ask),
            CallingRule(RuleMatcher.AnyDestination, RuleAction.UseMatchingCountrySim),
        )
        assertNull(preferredCallingSubId(book, listOf(telstra, tmobile), "AU", emptyMap()))
    }

    @Test
    fun `disabled and soft-deleted rules are skipped`() {
        val book = callingBook(
            CallingRule(RuleMatcher.Country("AU"), RuleAction.UseSim(tmobile.ref()), enabled = false),
            CallingRule(RuleMatcher.Country("AU"), RuleAction.UseSim(tmobile.ref()), pendingRemoval = true),
            CallingRule(RuleMatcher.AnyDestination, RuleAction.UseMatchingCountrySim),
        )
        assertEquals(
            telstra.subscriptionId,
            preferredCallingSubId(book, listOf(telstra, tmobile), "AU", emptyMap()),
        )
    }

    @Test
    fun `an unknown country has no preferred calling SIM`() {
        assertNull(preferredCallingSubId(CallingRuleBook(), listOf(telstra, tmobile), null, emptyMap()))
    }

    // --- Preferred data SIM ---

    @Test
    fun `a use-SIM-for-data rule names the preferred data SIM`() {
        val book = DataRuleBook(
            listOf(DataRule(RuleMatcher.Country("AU"), DataExpectation.UseSimForData(tmobile.ref()))),
        )
        // Preferred even though telstra currently carries data.
        assertEquals(tmobile.subscriptionId, preferredDataSubId(book, dataSnapshot()))
    }

    @Test
    fun `a use-local-SIM-for-data rule names the unique local SIM`() {
        val book = DataRuleBook(
            listOf(DataRule(RuleMatcher.Country("AU"), DataExpectation.UseLocalSimForData)),
        )
        // Telstra is the only AU-homed SIM; preferred even while it carries data.
        assertEquals(telstra.subscriptionId, preferredDataSubId(book, dataSnapshot()))
    }

    @Test
    fun `a use-local-SIM-for-data rule names no SIM without a unique local one`() {
        val telstra2 = telstra.copy(subscriptionId = 3, displayName = "Telstra work")
        val book = DataRuleBook(
            listOf(DataRule(RuleMatcher.Country("AU"), DataExpectation.UseLocalSimForData)),
        )
        // Two AU-homed SIMs → no unique local one, like the calling default.
        assertNull(preferredDataSubId(book, dataSnapshot(activeSims = listOf(telstra, telstra2))))
    }

    @Test
    fun `a covering roaming-OK rule names no preferred data SIM`() {
        val book = DataRuleBook(
            listOf(DataRule(RuleMatcher.Country("AU"), DataExpectation.RoamingOk(DataSimScope.AnySim))),
        )
        assertNull(preferredDataSubId(book, dataSnapshot()))
    }

    @Test
    fun `a roaming-OK scope miss falls through to a lower use-SIM rule`() {
        val book = DataRuleBook(
            listOf(
                // Scope is T-Mobile, but Telstra carries data → miss, fall through.
                DataRule(
                    RuleMatcher.Country("AU"),
                    DataExpectation.RoamingOk(DataSimScope.Sims(listOf(tmobile.ref()))),
                ),
                DataRule(RuleMatcher.Country("AU"), DataExpectation.UseSimForData(tmobile.ref())),
            ),
        )
        assertEquals(tmobile.subscriptionId, preferredDataSubId(book, dataSnapshot()))
    }

    @Test
    fun `an always-warn rule stops the search`() {
        val book = DataRuleBook(
            listOf(
                DataRule(RuleMatcher.Country("AU"), DataExpectation.AlwaysWarn),
                DataRule(RuleMatcher.Country("AU"), DataExpectation.UseSimForData(tmobile.ref())),
            ),
        )
        assertNull(preferredDataSubId(book, dataSnapshot()))
    }

    @Test
    fun `no data SIM has no preferred data SIM`() {
        val snapshot = dataSnapshot().copy(dataSubscriptionId = 99)
        val book = DataRuleBook(
            listOf(DataRule(RuleMatcher.Country("AU"), DataExpectation.UseSimForData(tmobile.ref()))),
        )
        assertNull(preferredDataSubId(book, snapshot))
    }

    // --- The combined status map ---

    @Test
    fun `status map marks primary and preferred roles per SIM`() {
        // Telstra is the default voice SIM and the local (preferred) calling
        // SIM; T-Mobile is the default data SIM; a rule prefers T-Mobile for data.
        val dataBook = DataRuleBook(
            listOf(DataRule(RuleMatcher.Country("AU"), DataExpectation.UseSimForData(tmobile.ref()))),
        )
        val statuses = simStatuses(
            callingBook = CallingRuleBook(),
            callingActiveSims = listOf(telstra, tmobile),
            defaultCallSubscriptionId = telstra.subscriptionId,
            defaultDataSubscriptionId = tmobile.subscriptionId,
            dataBook = dataBook,
            dataSnapshot = dataSnapshot(dataSim = tmobile),
        )
        assertEquals(
            SimStatus(callingPrimary = true, callingPreferred = true),
            statuses[telstra.subscriptionId],
        )
        assertEquals(
            SimStatus(dataPrimary = true, dataPreferred = true),
            statuses[tmobile.subscriptionId],
        )
    }

    @Test
    fun `data primary follows the default SIM while the active switch is temporary`() {
        // Automatic data switching moved the active data sub to T-Mobile, but
        // the user's chosen primary data SIM is still Telstra: Telstra stays
        // "primary" (Codex on PR #79) and T-Mobile gets the "temporary" role so
        // the override is visible.
        val statuses = simStatuses(
            callingBook = CallingRuleBook(),
            callingActiveSims = listOf(telstra, tmobile),
            defaultCallSubscriptionId = SimRef.INVALID_SUBSCRIPTION_ID,
            defaultDataSubscriptionId = telstra.subscriptionId,
            dataBook = DataRuleBook(emptyList()),
            // In France, so neither SIM is the local (preferred) calling SIM —
            // the data roles stand alone.
            dataSnapshot = dataSnapshot(networkCountry = "FR", dataSim = tmobile),
        )
        assertEquals(SimStatus(dataPrimary = true), statuses[telstra.subscriptionId])
        assertEquals(SimStatus(dataTemporary = true), statuses[tmobile.subscriptionId])
    }

    @Test
    fun `no temporary role when data is on the primary SIM`() {
        // Active data sub == default: the ordinary case, no temporary chip.
        val statuses = simStatuses(
            callingBook = CallingRuleBook(),
            callingActiveSims = listOf(telstra, tmobile),
            defaultCallSubscriptionId = SimRef.INVALID_SUBSCRIPTION_ID,
            defaultDataSubscriptionId = telstra.subscriptionId,
            dataBook = DataRuleBook(emptyList()),
            dataSnapshot = dataSnapshot(networkCountry = "FR", dataSim = telstra),
        )
        assertEquals(SimStatus(dataPrimary = true), statuses[telstra.subscriptionId])
        assertFalse(statuses.containsKey(tmobile.subscriptionId))
    }

    @Test
    fun `SIMs with no role are omitted from the status map`() {
        // In France with a data-only Orange eSIM carrying data; no calling rule
        // resolves (no FR calling SIM) and Telstra/T-Mobile hold no role there.
        val statuses = simStatuses(
            callingBook = CallingRuleBook(),
            callingActiveSims = listOf(telstra, tmobile),
            defaultCallSubscriptionId = SimRef.INVALID_SUBSCRIPTION_ID,
            defaultDataSubscriptionId = orangeData.subscriptionId,
            dataBook = DataRuleBook(emptyList()),
            dataSnapshot = dataSnapshot(
                networkCountry = "FR",
                dataSim = orangeData,
                activeSims = listOf(telstra, tmobile, orangeData),
            ),
        )
        assertTrue(statuses.containsKey(orangeData.subscriptionId))
        assertTrue(statuses.getValue(orangeData.subscriptionId).dataPrimary)
        assertFalse(statuses.containsKey(telstra.subscriptionId))
        assertFalse(statuses.containsKey(tmobile.subscriptionId))
    }
}
