package app.simmo.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The triage situation the data rules screen leads with (SPEC "Data rules" →
 * Triage): it mirrors [evaluateDataRules] and adds the group-widen candidates
 * and the "This is OK" rule shape.
 */
class DataTriageTest {

    private val telstra =
        ActiveSim(1, "Telstra", "Telstra AU", PhoneAccountRef("acct-telstra"), countryIso = "au")
    private val tmobile =
        ActiveSim(2, "T-Mobile", "T-Mobile US", PhoneAccountRef("acct-tmobile"), countryIso = "us")

    private fun snapshot(
        networkCountry: String,
        dataSim: ActiveSim,
        activeSims: List<ActiveSim> = listOf(telstra, tmobile),
        roaming: Set<Int> = setOf(dataSim.subscriptionId),
        dataRoamingEnabled: Set<Int> = setOf(dataSim.subscriptionId),
        customGroups: Map<String, List<String>> = emptyMap(),
    ) = DataSnapshot(
        networkCountry = networkCountry,
        activeSims = activeSims,
        dataSubscriptionId = dataSim.subscriptionId,
        roamingSubscriptionIds = roaming,
        dataRoamingEnabledSubscriptionIds = dataRoamingEnabled,
        customGroups = customGroups,
    )

    @Test
    fun `a covered or home situation has no card`() {
        // Home network: Silent → no triage.
        assertNull(triageFor(DataRuleBook(emptyList()), snapshot("US", tmobile, roaming = emptySet())))
        // A rule that allows the roaming silences the card too.
        val allowed = DataRuleBook(
            listOf(DataRule(RuleMatcher.Country("AU"), DataExpectation.RoamingOk(DataSimScope.AnySim))),
        )
        assertNull(triageFor(allowed, snapshot("AU", tmobile)))
    }

    @Test
    fun `roaming surfaces the SIM, country, locals, and no widen groups off-group`() {
        val triage = triageFor(DataRuleBook(emptyList()), snapshot("AU", tmobile))
        assertEquals(
            DataTriage.Roaming(
                tmobile,
                "AU",
                arrivalKey = "roaming:2:AU",
                localSims = listOf(telstra),
                widenGroupIds = emptyList(),
            ),
            triage,
        )
    }

    @Test
    fun `dismissing this arrival hides the card until a different one arrives`() {
        val book = DataRuleBook(emptyList())
        val roaming = triageFor(book, snapshot("AU", tmobile)) as DataTriage.Roaming
        // The card's own arrival key, dismissed, silences exactly this arrival.
        assertNull(triageFor(book, snapshot("AU", tmobile), dismissedKeys = setOf(roaming.arrivalKey)))
        // A different country is a different arrival: the stale dismiss doesn't
        // suppress it (the notification mark clears on the same staleness).
        assertEquals(
            "roaming:2:FR",
            (triageFor(
                book,
                snapshot("FR", tmobile, activeSims = listOf(tmobile)),
                dismissedKeys = setOf(roaming.arrivalKey),
            ) as DataTriage.Roaming).arrivalKey,
        )
    }

    @Test
    fun `roaming inside a shipped group offers it as a widen candidate`() {
        // Roaming in France on the US SIM: EU/EEA contains FR, so it's a widen option.
        val triage = triageFor(
            DataRuleBook(emptyList()),
            snapshot("FR", tmobile, activeSims = listOf(tmobile)),
        ) as DataTriage.Roaming
        assertEquals(listOf(CountryGroups.EU_EEA), triage.widenGroupIds)
        assertEquals("roaming:2:FR", triage.arrivalKey)
    }

    @Test
    fun `a custom group containing the country is a widen candidate too`() {
        val triage = triageFor(
            DataRuleBook(emptyList()),
            snapshot("AU", tmobile, customGroups = mapOf("trip" to listOf("AU", "NZ"))),
        ) as DataTriage.Roaming
        assertEquals(listOf("trip"), triage.widenGroupIds)
    }

    @Test
    fun `no data and wrong SIM map to their own situations`() {
        // Data roaming off on a non-local SIM: no mobile data, a local SIM to switch to.
        val noData = triageFor(
            DataRuleBook(emptyList()),
            snapshot("AU", tmobile, dataRoamingEnabled = emptySet()),
        )
        assertEquals(
            DataTriage.NoData(tmobile, "AU", arrivalKey = "noDataSwitch:2:1:AU", switchTo = listOf(telstra)),
            noData,
        )

        // A use-SIM rule wants Telstra, but T-Mobile carries data.
        val wantTelstra = DataRuleBook(
            listOf(
                DataRule(
                    RuleMatcher.Country("AU"),
                    DataExpectation.UseSimForData(SimRef(1, "Telstra", "Telstra AU")),
                ),
            ),
        )
        assertEquals(
            DataTriage.WrongSim(tmobile, "AU", arrivalKey = "wrongSim:2:1:AU", wantedSim = telstra),
            triageFor(wantTelstra, snapshot("AU", tmobile)),
        )
    }

    @Test
    fun `this-is-OK builds a SIM-scoped roaming rule for the country or a group`() {
        val ref = SimRef(2, "T-Mobile", "T-Mobile US")
        assertEquals(
            DataRule(
                RuleMatcher.Country("AU"),
                DataExpectation.RoamingOk(DataSimScope.Sims(listOf(ref))),
            ),
            roamingOkRule("AU", groupId = null, dataSim = ref),
        )
        assertEquals(
            DataRule(
                RuleMatcher.Countries(groupIds = listOf(CountryGroups.EU_EEA)),
                DataExpectation.RoamingOk(DataSimScope.Sims(listOf(ref))),
            ),
            roamingOkRule("FR", groupId = CountryGroups.EU_EEA, dataSim = ref),
        )
    }
}
