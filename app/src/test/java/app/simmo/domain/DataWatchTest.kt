package app.simmo.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class DataWatchTest {

    private val telstra =
        ActiveSim(1, "Telstra", "Telstra AU", PhoneAccountRef("acct-telstra"), countryIso = "au")
    private val tmobile =
        ActiveSim(2, "T-Mobile", "T-Mobile US", PhoneAccountRef("acct-tmobile"), countryIso = "us")
    private val vodafoneDe =
        ActiveSim(3, "Vodafone", "Vodafone DE", PhoneAccountRef("acct-vodafone"), countryIso = "de")

    private fun ActiveSim.ref() = SimRef(subscriptionId, carrierName, displayName)

    private fun inCountry(region: String, expectation: DataExpectation) =
        DataRule(RuleMatcher.Country(region), expectation)

    private fun snapshot(
        networkCountry: String,
        dataSim: ActiveSim,
        activeSims: List<ActiveSim> = listOf(telstra, tmobile),
        roaming: Set<Int> = setOf(dataSim.subscriptionId),
        dataRoamingEnabled: Set<Int> = setOf(dataSim.subscriptionId),
        customGroups: Map<String, List<String>> = CountryGroups.preseededGroups()
            .associate { it.id to it.regionCodes },
        registeredSims: List<RegisteredSim> = emptyList(),
    ) = DataSnapshot(
        networkCountry = networkCountry,
        activeSims = activeSims,
        dataSubscriptionId = dataSim.subscriptionId,
        roamingSubscriptionIds = roaming,
        dataRoamingEnabledSubscriptionIds = dataRoamingEnabled,
        customGroups = customGroups,
        registeredSims = registeredSims,
    )

    private fun evaluate(rules: List<DataRule>, snapshot: DataSnapshot) =
        evaluateDataRules(DataRuleBook(rules), snapshot)

    // --- The no-rule defaults ---

    @Test
    fun `unknown country stays silent`() {
        assertEquals(
            DataVerdict.Silent,
            evaluate(emptyList(), snapshot(networkCountry = "", dataSim = tmobile)),
        )
    }

    @Test
    fun `no active data SIM stays silent`() {
        val snapshot = snapshot(networkCountry = "AU", dataSim = tmobile)
            .copy(dataSubscriptionId = 99)
        assertEquals(DataVerdict.Silent, evaluate(emptyList(), snapshot))
    }

    @Test
    fun `home network never warns`() {
        assertEquals(
            DataVerdict.Silent,
            evaluate(emptyList(), snapshot("US", dataSim = tmobile, roaming = emptySet())),
        )
    }

    @Test
    fun `roaming data SIM warns and names the local SIMs`() {
        assertEquals(
            DataVerdict.RoamingWarning(tmobile, "AU", localSims = listOf(telstra)),
            evaluate(emptyList(), snapshot("AU", dataSim = tmobile)),
        )
    }

    @Test
    fun `warning matches the network country case-insensitively`() {
        assertEquals(
            DataVerdict.RoamingWarning(tmobile, "AU", localSims = listOf(telstra)),
            evaluate(emptyList(), snapshot("au", dataSim = tmobile)),
        )
    }

    // --- No-data nudge (rule-less) ---

    @Test
    fun `data roaming off on a non-local SIM nudges toward the active local SIM`() {
        assertEquals(
            DataVerdict.NoDataNudge(tmobile, "AU", switchTo = listOf(telstra)),
            evaluate(emptyList(), snapshot("AU", dataSim = tmobile, dataRoamingEnabled = emptySet())),
        )
    }

    @Test
    fun `no-data nudge offers a disabled registered local profile to enable`() {
        val disabledTelstra = RegisteredSim(7, "Telstra", "Telstra travel", 0L, countryIso = "au")
        assertEquals(
            DataVerdict.NoDataNudge(tmobile, "AU", enableFirst = listOf(disabledTelstra)),
            evaluate(
                emptyList(),
                snapshot(
                    "AU",
                    dataSim = tmobile,
                    activeSims = listOf(tmobile),
                    dataRoamingEnabled = emptySet(),
                    registeredSims = listOf(disabledTelstra),
                ),
            ),
        )
    }

    @Test
    fun `no-data nudge stays silent with no SIM to offer`() {
        assertEquals(
            DataVerdict.Silent,
            evaluate(
                emptyList(),
                snapshot(
                    "AU",
                    dataSim = tmobile,
                    activeSims = listOf(tmobile),
                    dataRoamingEnabled = emptySet(),
                ),
            ),
        )
    }

    @Test
    fun `a registered profile that is still active is not an enable-first candidate`() {
        // The active data SIM's own registry row must not be offered as a
        // profile to enable; with nothing else local the nudge stays silent.
        val tmobileRow = RegisteredSim(2, "T-Mobile", "T-Mobile US", 0L, countryIso = "au")
        assertEquals(
            DataVerdict.Silent,
            evaluate(
                emptyList(),
                snapshot(
                    "AU",
                    dataSim = tmobile,
                    activeSims = listOf(tmobile),
                    dataRoamingEnabled = emptySet(),
                    registeredSims = listOf(tmobileRow),
                ),
            ),
        )
    }

    // --- Roaming OK ---

    @Test
    fun `roaming OK for any SIM silences the warning`() {
        val rules = listOf(inCountry("AU", DataExpectation.RoamingOk()))
        assertEquals(DataVerdict.Silent, evaluate(rules, snapshot("AU", dataSim = tmobile)))
    }

    @Test
    fun `roaming OK elsewhere still warns here`() {
        val rules = listOf(inCountry("NZ", DataExpectation.RoamingOk()))
        assertEquals(
            DataVerdict.RoamingWarning(tmobile, "AU", localSims = listOf(telstra)),
            evaluate(rules, snapshot("AU", dataSim = tmobile)),
        )
    }

    @Test
    fun `roaming OK scoped to specific SIMs covers only them`() {
        val rules = listOf(
            inCountry("AU", DataExpectation.RoamingOk(DataSimScope.Sims(listOf(vodafoneDe.ref())))),
        )
        val sims = listOf(telstra, tmobile, vodafoneDe)
        assertEquals(
            DataVerdict.Silent,
            evaluate(rules, snapshot("AU", dataSim = vodafoneDe, activeSims = sims)),
        )
        assertEquals(
            DataVerdict.RoamingWarning(tmobile, "AU", localSims = listOf(telstra)),
            evaluate(rules, snapshot("AU", dataSim = tmobile, activeSims = sims)),
        )
    }

    // --- The preseeded EU/EEA roam-like-at-home default ---

    @Test
    fun `preseed silences an EU SIM roaming inside the EU`() {
        val snapshot = snapshot("FR", dataSim = vodafoneDe, activeSims = listOf(vodafoneDe, tmobile))
        assertEquals(DataVerdict.Silent, evaluateDataRules(DataRuleBook(), snapshot))
    }

    @Test
    fun `preseed does not silence a US SIM roaming inside the EU`() {
        val snapshot = snapshot("FR", dataSim = tmobile, activeSims = listOf(vodafoneDe, tmobile))
        assertEquals(
            DataVerdict.RoamingWarning(tmobile, "FR"),
            evaluateDataRules(DataRuleBook(), snapshot),
        )
    }

    @Test
    fun `preseed does not apply outside the EU`() {
        val snapshot = snapshot("AU", dataSim = vodafoneDe, activeSims = listOf(vodafoneDe, telstra))
        assertEquals(
            DataVerdict.RoamingWarning(vodafoneDe, "AU", localSims = listOf(telstra)),
            evaluateDataRules(DataRuleBook(), snapshot),
        )
    }

    // --- Use SIM for data ---

    @Test
    fun `wanted SIM already carrying data stays silent`() {
        val rules = listOf(inCountry("AU", DataExpectation.UseSimForData(telstra.ref())))
        assertEquals(
            DataVerdict.Silent,
            evaluate(rules, snapshot("AU", dataSim = telstra, roaming = emptySet())),
        )
    }

    @Test
    fun `a different data SIM raises the mismatch before any roaming`() {
        // The arrival check is state-based: it fires even while the roaming
        // flag hasn't been raised for the data SIM yet.
        val rules = listOf(inCountry("AU", DataExpectation.UseSimForData(telstra.ref())))
        assertEquals(
            DataVerdict.WrongDataSim(tmobile, telstra, "AU"),
            evaluate(rules, snapshot("AU", dataSim = tmobile, roaming = emptySet())),
        )
    }

    @Test
    fun `use-SIM rule re-binds by carrier and display name`() {
        val restoredRef = SimRef(SimRef.INVALID_SUBSCRIPTION_ID, "Telstra", "Telstra AU")
        val rules = listOf(inCountry("AU", DataExpectation.UseSimForData(restoredRef)))
        assertEquals(
            DataVerdict.WrongDataSim(tmobile, telstra, "AU"),
            evaluate(rules, snapshot("AU", dataSim = tmobile)),
        )
    }

    @Test
    fun `use-SIM rule whose SIM is disabled skips to the default warning`() {
        val disabledRef = SimRef(9, "Optus", "Optus travel")
        val rules = listOf(inCountry("AU", DataExpectation.UseSimForData(disabledRef)))
        assertEquals(
            DataVerdict.RoamingWarning(tmobile, "AU", localSims = listOf(telstra)),
            evaluate(rules, snapshot("AU", dataSim = tmobile)),
        )
    }

    // --- Always warn, ordering, and enablement ---

    @Test
    fun `warn guard above a broader roaming OK still warns`() {
        val rules = listOf(
            inCountry("AU", DataExpectation.AlwaysWarn),
            DataRule(RuleMatcher.AnyDestination, DataExpectation.RoamingOk()),
        )
        assertEquals(
            DataVerdict.RoamingWarning(tmobile, "AU", localSims = listOf(telstra)),
            evaluate(rules, snapshot("AU", dataSim = tmobile)),
        )
    }

    @Test
    fun `warn guard never warns about a home-network SIM`() {
        val rules = listOf(inCountry("AU", DataExpectation.AlwaysWarn))
        assertEquals(
            DataVerdict.Silent,
            evaluate(rules, snapshot("AU", dataSim = telstra, roaming = emptySet())),
        )
    }

    @Test
    fun `warn guard keeps the no-data shape honest`() {
        // Roaming network but data roaming off: the guard must not fabricate
        // a "using data roaming" warning where no roaming data can flow.
        val rules = listOf(inCountry("AU", DataExpectation.AlwaysWarn))
        assertEquals(
            DataVerdict.NoDataNudge(tmobile, "AU", switchTo = listOf(telstra)),
            evaluate(rules, snapshot("AU", dataSim = tmobile, dataRoamingEnabled = emptySet())),
        )
    }

    @Test
    fun `first matching rule wins`() {
        val rules = listOf(
            inCountry("AU", DataExpectation.RoamingOk()),
            inCountry("AU", DataExpectation.AlwaysWarn),
        )
        assertEquals(DataVerdict.Silent, evaluate(rules, snapshot("AU", dataSim = tmobile)))
    }

    @Test
    fun `a user-disabled rule never acts`() {
        val rules = listOf(
            inCountry("AU", DataExpectation.RoamingOk()).copy(enabled = false),
        )
        assertEquals(
            DataVerdict.RoamingWarning(tmobile, "AU", localSims = listOf(telstra)),
            evaluate(rules, snapshot("AU", dataSim = tmobile)),
        )
    }

    @Test
    fun `a soft-deleted rule never acts`() {
        // Tombstoned (struck-through, awaiting purge on leave): inert here too, so
        // the default roaming warning falls through instead.
        val rules = listOf(
            inCountry("AU", DataExpectation.RoamingOk()).copy(pendingRemoval = true),
        )
        assertEquals(
            DataVerdict.RoamingWarning(tmobile, "AU", localSims = listOf(telstra)),
            evaluate(rules, snapshot("AU", dataSim = tmobile)),
        )
    }

    @Test
    fun `arrival keys dedupe repeats and distinguish real changes`() {
        val warning = DataVerdict.RoamingWarning(tmobile, "AU", localSims = listOf(telstra))
        // Same arrival → same key, however often the refresh re-evaluates.
        assertEquals(warning.arrivalKey(), DataVerdict.RoamingWarning(tmobile, "AU").arrivalKey())
        // Silence never produces a key — and never disturbs a stored mark.
        assertEquals(null, DataVerdict.Silent.arrivalKey())
        // Another country, another data SIM, or another shape of problem is
        // a new arrival: every key differs.
        val keys = listOf(
            warning.arrivalKey(),
            DataVerdict.RoamingWarning(tmobile, "NZ").arrivalKey(),
            DataVerdict.RoamingWarning(telstra, "AU").arrivalKey(),
            DataVerdict.WrongDataSim(tmobile, telstra, "AU").arrivalKey(),
            // Enable-first vs switch-ready are distinct arrivals: enabling
            // the profile must re-arm the follow-up Switch nudge.
            DataVerdict.NoDataNudge(tmobile, "AU").arrivalKey(),
            DataVerdict.NoDataNudge(tmobile, "AU", switchTo = listOf(telstra)).arrivalKey(),
            // And so is a different offered SIM: the notification must
            // refresh when the SIM it named is no longer the candidate.
            DataVerdict.NoDataNudge(tmobile, "AU", switchTo = listOf(vodafoneDe)).arrivalKey(),
        )
        assertEquals(keys.size, keys.toSet().size)
        // Every key shape parses for staleness the same way.
        keys.forEach { key ->
            assertEquals(false, isMarkStale(key, snapshot("", dataSim = tmobile)))
        }
    }

    @Test
    fun `a mark goes stale only when the arrival is genuinely over`() {
        val mark = DataVerdict.RoamingWarning(tmobile, "AU").arrivalKey()
        // Same place, same data SIM: a flapping roaming flag is not a new
        // arrival — the mark holds and nothing re-nags mid-trip.
        assertEquals(false, isMarkStale(mark, snapshot("AU", dataSim = tmobile)))
        assertEquals(false, isMarkStale(mark, snapshot("au", dataSim = tmobile)))
        // Unknown network country decides nothing either way.
        assertEquals(false, isMarkStale(mark, snapshot("", dataSim = tmobile)))
        // Moving country or switching the data SIM ends the arrival, so a
        // later return may warn once again.
        assertEquals(true, isMarkStale(mark, snapshot("NZ", dataSim = tmobile)))
        assertEquals(true, isMarkStale(mark, snapshot("AU", dataSim = telstra)))
        // No mark, nothing to stale; an unparseable mark is stale by definition.
        assertEquals(false, isMarkStale(null, snapshot("AU", dataSim = tmobile)))
        assertEquals(true, isMarkStale("what", snapshot("AU", dataSim = tmobile)))
    }

    @Test
    fun `data rules match through custom groups`() {
        val rules = listOf(
            DataRule(
                RuleMatcher.Countries(groupIds = listOf("custom-1")),
                DataExpectation.RoamingOk(),
            ),
        )
        assertEquals(
            DataVerdict.Silent,
            evaluate(
                rules,
                snapshot("AU", dataSim = tmobile, customGroups = mapOf("custom-1" to listOf("AU"))),
            ),
        )
    }
}
