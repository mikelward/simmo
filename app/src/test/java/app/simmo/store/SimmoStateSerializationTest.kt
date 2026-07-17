package app.simmo.store

import androidx.datastore.core.CorruptionException
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
                Rule(RuleMatcher.Country("US"), RuleAction.HandOff.ViaPhoneAccount(PhoneAccountRef("acct-gv"))),
                Rule(RuleMatcher.Country("GB"), RuleAction.HandOff.ViaDialIntent("com.example.voip")),
                Rule(RuleMatcher.Country("NZ"), RuleAction.Ask),
                Rule(RuleMatcher.Countries(listOf("FR", "DE")), RuleAction.Ask),
                Rule(RuleMatcher.AnyDestination, RuleAction.UseMatchingCountrySim),
                Rule(RuleMatcher.AnyDestination, RuleAction.SystemDefault),
            ),
        ),
        simRegistry = listOf(
            RegisteredSim(1, "Telstra", "Telstra personal", 1_000L),
            RegisteredSim(2, "T-Mobile", "T-Mobile US", 2_000L),
        ),
        defaultRegionOverride = "AU",
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
    fun `corrupt bytes surface as CorruptionException, not a crash`() {
        assertThrows(CorruptionException::class.java) {
            kotlinx.coroutines.test.runTest {
                SimmoStateSerializer.readFrom(ByteArrayInputStream("not json".encodeToByteArray()))
            }
        }
    }
}
