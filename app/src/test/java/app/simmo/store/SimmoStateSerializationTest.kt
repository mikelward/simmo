package app.simmo.store

import androidx.datastore.core.CorruptionException
import app.simmo.domain.CustomGroup
import app.simmo.domain.DataExpectation
import app.simmo.domain.DataRule
import app.simmo.domain.DataRuleBook
import app.simmo.domain.DataSimScope
import app.simmo.domain.DialHandoffApp
import app.simmo.domain.PhoneAccountRef
import app.simmo.domain.RegisteredSim
import app.simmo.domain.Rule
import app.simmo.domain.RuleAction
import app.simmo.domain.RuleBook
import app.simmo.domain.RuleMatcher
import app.simmo.domain.SimRef
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SimmoStateSerializationTest {

    private val fullState = SimmoState(
        rules = RuleBook(
            rules = listOf(
                Rule(RuleMatcher.Country("AU"), RuleAction.UseSim(SimRef(1, "Telstra", "Telstra personal"))),
                Rule(
                    RuleMatcher.Country("US"),
                    // Non-default label so the round trip proves it is written.
                    RuleAction.HandOff.ViaPhoneAccount(PhoneAccountRef("acct-sip"), "SIP work"),
                ),
                Rule(RuleMatcher.Country("GB"), RuleAction.HandOff.ViaDialIntent(DialHandoffApp.GOOGLE_VOICE)),
                Rule(RuleMatcher.Country("NZ"), RuleAction.Ask),
                Rule(RuleMatcher.Countries(listOf("FR", "DE")), RuleAction.Ask),
                Rule(
                    RuleMatcher.Countries(listOf("GB"), listOf("eu_eea")),
                    RuleAction.UseSim(SimRef(1, "Telstra", "Telstra personal")),
                ),
                Rule(RuleMatcher.AnyDestination, RuleAction.UseMatchingCountrySim),
                Rule(RuleMatcher.AnyDestination, RuleAction.SystemDefault),
            ),
        ),
        simRegistry = listOf(
            // Non-default country + number so the round trip proves they're written.
            RegisteredSim(1, "Telstra", "Telstra personal", 1_000L, countryIso = "au", phoneNumber = "+61412345678"),
            RegisteredSim(2, "T-Mobile", "T-Mobile US", 2_000L),
            // Non-default flags so the round trip proves they are written.
            RegisteredSim(
                5, "Orange", "Orange Holiday", 3_000L,
                countryIso = "fr", callCapable = false, rulePromptOffered = false,
            ),
        ),
        customGroups = listOf(
            CustomGroup("custom:1", "Vodafone Zone 1", listOf("GB", "FR", "DE")),
        ),
        defaultRegionOverride = "AU",
        // Non-default so the round trip proves the fields are actually written.
        dataWatchMark = "roaming:2:AU",
        analyticsOptIn = false,
        showCallToast = true,
        callDelaySeconds = 5,
        correctContactNumbers = true,
        guardOverseasHandsFree = true,
        guardDisabledSimHandsFree = true,
        // Every expectation and scope shape, so the round trip proves the
        // whole data-rule storage format.
        dataRules = DataRuleBook(
            listOf(
                DataRule(
                    RuleMatcher.Country("AU"),
                    DataExpectation.UseSimForData(SimRef(1, "Telstra", "Telstra personal")),
                ),
                DataRule(
                    RuleMatcher.Country("FR"),
                    DataExpectation.RoamingOk(
                        DataSimScope.Sims(listOf(SimRef(2, "T-Mobile", "T-Mobile US"))),
                    ),
                ),
                DataRule(RuleMatcher.Country("TR"), DataExpectation.AlwaysWarn, enabled = false),
            ) + DataRuleBook.defaultDataRules(),
        ),
    )

    private suspend fun roundTrip(state: SimmoState): SimmoState {
        val out = ByteArrayOutputStream()
        SimmoStateSerializer.writeTo(state, out)
        return SimmoStateSerializer.readFrom(ByteArrayInputStream(out.toByteArray()))
    }

    @Test
    fun `every rule action type survives a round trip`() = runTest {
        assertEquals(fullState, roundTrip(fullState))
    }

    @Test
    fun `default state survives a round trip`() = runTest {
        assertEquals(SimmoState(), roundTrip(SimmoState()))
    }

    @Test
    fun `unknown keys from a newer version are ignored`() = runTest {
        // Forward compatibility: an older app must be able to read state
        // written by a newer one that added fields.
        val json = Json.encodeToString(SimmoState.serializer(), fullState)
            .removeSuffix("}") + ""","futureFeature":{"enabled":true}}"""
        val read = SimmoStateSerializer.readFrom(ByteArrayInputStream(json.encodeToByteArray()))
        assertEquals(fullState, read)
    }

    @Test
    fun `legacy single-country matchers still decode`() = runTest {
        // The exact bytes a pre-multi-country version wrote: matcher type
        // "country" with a single regionCode. Changing that decode path would
        // wipe every existing user's rules via the corruption handler.
        val json = """
            {"rules":{"rules":[
              {"matcher":{"type":"country","regionCode":"AU"},
               "action":{"type":"systemDefault"}}
            ]},"simRegistry":[],"defaultRegionOverride":null,"installId":null}
        """.trimIndent()
        val read = SimmoStateSerializer.readFrom(ByteArrayInputStream(json.encodeToByteArray()))
        assertEquals(
            listOf(Rule(RuleMatcher.Country("AU"), RuleAction.SystemDefault)),
            read.rules.rules,
        )
    }

    @Test
    fun `group-less countries matchers from the previous version still decode`() = runTest {
        // Bytes as written before groupIds existed on the "countries" form.
        val json = """
            {"rules":{"rules":[
              {"matcher":{"type":"countries","regionCodes":["FR","DE"]},
               "action":{"type":"systemDefault"}}
            ]},"simRegistry":[],"defaultRegionOverride":null,"installId":null}
        """.trimIndent()
        val read = SimmoStateSerializer.readFrom(ByteArrayInputStream(json.encodeToByteArray()))
        assertEquals(
            listOf(Rule(RuleMatcher.Countries(listOf("FR", "DE")), RuleAction.SystemDefault)),
            read.rules.rules,
        )
    }

    @Test
    fun `a phone-account rule written before labels decodes with a blank one`() = runTest {
        // Bytes as written before ViaPhoneAccount carried a label: the rule
        // must decode (blank label, id shown as fallback), not corrupt state.
        val json = """
            {"rules":{"rules":[
              {"matcher":{"type":"country","regionCode":"US"},
               "action":{"type":"handOffAccount","account":{"id":"acct-sip"}}}
            ]},"simRegistry":[],"defaultRegionOverride":null,"installId":null}
        """.trimIndent()
        val read = SimmoStateSerializer.readFrom(ByteArrayInputStream(json.encodeToByteArray()))
        assertEquals(
            RuleAction.HandOff.ViaPhoneAccount(PhoneAccountRef("acct-sip")),
            read.rules.rules.single().action,
        )
    }

    @Test
    fun `a disabled rule survives a round trip`() = runTest {
        val state = SimmoState(
            rules = RuleBook(
                listOf(Rule(RuleMatcher.Country("AU"), RuleAction.SystemDefault, enabled = false)),
            ),
        )
        assertEquals(state, roundTrip(state))
        assertEquals(false, roundTrip(state).rules.rules.single().enabled)
    }

    @Test
    fun `a rule written before the enabled flag decodes as enabled`() = runTest {
        // Existing users' rules (no enabled field) must come up on, not off.
        val json = """
            {"rules":{"rules":[
              {"matcher":{"type":"country","regionCode":"AU"},
               "action":{"type":"systemDefault"}}
            ]},"simRegistry":[],"defaultRegionOverride":null,"installId":null}
        """.trimIndent()
        val read = SimmoStateSerializer.readFrom(ByteArrayInputStream(json.encodeToByteArray()))
        assertEquals(true, read.rules.rules.single().enabled)
    }

    @Test
    fun `state written before custom groups decodes with none`() = runTest {
        // Existing users (no customGroups field) come up with an empty list,
        // not a decode failure that would wipe their rules.
        val json = """
            {"rules":{"rules":[]},"simRegistry":[],"defaultRegionOverride":null,"installId":null}
        """.trimIndent()
        val read = SimmoStateSerializer.readFrom(ByteArrayInputStream(json.encodeToByteArray()))
        assertEquals(emptyList<CustomGroup>(), read.customGroups)
    }

    @Test
    fun `registry entries written before country and number decode with neither`() = runTest {
        // Bytes as written before RegisteredSim carried countryIso/phoneNumber:
        // existing registries must decode with the empty defaults, not fail.
        val json = """
            {"rules":{"rules":[]},"simRegistry":[
              {"subscriptionId":1,"carrierName":"Telstra","displayName":"Telstra personal",
               "lastSeenEpochMillis":1000}
            ],"defaultRegionOverride":null,"installId":null}
        """.trimIndent()
        val read = SimmoStateSerializer.readFrom(ByteArrayInputStream(json.encodeToByteArray()))
        assertEquals(
            RegisteredSim(1, "Telstra", "Telstra personal", 1_000L),
            read.simRegistry.single(),
        )
    }

    @Test
    fun `state written before the analytics preference decodes as opted in`() = runTest {
        // Bytes as written before analyticsOptIn existed: existing users must
        // come up opted in, matching the default a fresh install gets.
        val json = """
            {"rules":{"rules":[]},"simRegistry":[],"defaultRegionOverride":null,"installId":null}
        """.trimIndent()
        val read = SimmoStateSerializer.readFrom(ByteArrayInputStream(json.encodeToByteArray()))
        assertEquals(true, read.analyticsOptIn)
    }

    @Test
    fun `state written before the call feedback settings decodes with them off`() = runTest {
        // Bytes as written before showCallToast/callDelaySeconds existed:
        // existing users must come up with both off, matching a fresh install.
        val json = """
            {"rules":{"rules":[]},"simRegistry":[],"defaultRegionOverride":null,"installId":null}
        """.trimIndent()
        val read = SimmoStateSerializer.readFrom(ByteArrayInputStream(json.encodeToByteArray()))
        assertEquals(false, read.showCallToast)
        assertEquals(0, read.callDelaySeconds)
        assertEquals(false, read.correctContactNumbers)
        // The guard blocks calls; it must never come up on by surprise.
        assertEquals(false, read.guardOverseasHandsFree)
        assertEquals(false, read.guardDisabledSimHandsFree)
    }

    @Test
    fun `state written before data rules decodes to the preseeded book`() = runTest {
        // Existing users (no dataRules field) get the EU/EEA roam-like-home
        // preseed, exactly like a fresh install (SPEC "Data rules").
        val json = """
            {"rules":{"rules":[]},"simRegistry":[],"defaultRegionOverride":null,"installId":null}
        """.trimIndent()
        val read = SimmoStateSerializer.readFrom(ByteArrayInputStream(json.encodeToByteArray()))
        assertEquals(DataRuleBook.defaultDataRules(), read.dataRules.rules)
        // And no watch mark: the first real arrival may warn.
        assertEquals(null, read.dataWatchMark)
    }

    @Test
    fun `corrupt bytes surface as CorruptionException, not a crash`() {
        assertThrows(CorruptionException::class.java) {
            kotlinx.coroutines.test.runTest {
                SimmoStateSerializer.readFrom(ByteArrayInputStream("not json".encodeToByteArray()))
            }
        }
    }
}
