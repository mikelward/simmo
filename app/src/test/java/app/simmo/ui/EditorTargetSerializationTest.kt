package app.simmo.ui

import app.simmo.domain.Rule
import app.simmo.domain.RuleAction
import app.simmo.domain.RuleMatcher
import app.simmo.domain.SimRef
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The editor route is persisted as JSON in SavedStateHandle so it survives
 * process death; these guard that round-trip for each target shape.
 */
class EditorTargetSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun roundTrip(target: EditorTarget): EditorTarget =
        json.decodeFromString(json.encodeToString(target))

    @Test
    fun `a new-rule target round-trips`() {
        val target: EditorTarget = EditorTarget.New
        assertEquals(target, roundTrip(target))
    }

    @Test
    fun `an existing-rule target keeps its index and rule`() {
        val target: EditorTarget = EditorTarget.Existing(
            3,
            Rule(RuleMatcher.Country("AU"), RuleAction.UseSim(SimRef(1, "Telstra", "Telstra AU"))),
        )
        assertEquals(target, roundTrip(target))
    }

    @Test
    fun `an existing target preserves an unsupported action`() {
        val target: EditorTarget = EditorTarget.Existing(0, Rule(RuleMatcher.AnyDestination, RuleAction.Ask))
        assertEquals(target, roundTrip(target))
    }
}
