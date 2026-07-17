package app.simmo.ui

import app.simmo.domain.ActiveSim
import app.simmo.domain.PhoneAccountRef
import app.simmo.domain.RegisteredSim
import app.simmo.domain.SimRef
import org.junit.Assert.assertEquals
import org.junit.Test

class NewSimPromptTest {

    private val telstraActive = ActiveSim(1, "Telstra", "Telstra AU", PhoneAccountRef("a1"))

    @Test
    fun `only unanswered prompts for active sims show`() {
        val registry = listOf(
            // Pending and active: prompts.
            RegisteredSim(1, "Telstra", "Telstra AU", 100L, needsRulePrompt = true),
            // Pending but no longer active: held back until it's active again —
            // a rule added now would only start out paused.
            RegisteredSim(2, "T-Mobile", "T-Mobile US", 100L, needsRulePrompt = true),
            // Active but already answered.
            RegisteredSim(3, "Optus", "Optus AU", 100L, needsRulePrompt = false),
        )
        val optusActive = ActiveSim(3, "Optus", "Optus AU", PhoneAccountRef("a3"))
        assertEquals(
            listOf(NewSimPromptUi(SimRef(1, "Telstra", "Telstra AU"), "Telstra AU")),
            buildNewSimPrompts(registry, listOf(telstraActive, optusActive)),
        )
    }

    @Test
    fun `prompt label falls back to the carrier name`() {
        val registry = listOf(RegisteredSim(1, "Telstra", "", 100L, needsRulePrompt = true))
        assertEquals(
            listOf(NewSimPromptUi(SimRef(1, "Telstra", ""), "Telstra")),
            buildNewSimPrompts(registry, listOf(telstraActive.copy(displayName = ""))),
        )
    }
}
