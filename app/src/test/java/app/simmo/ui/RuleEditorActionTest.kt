package app.simmo.ui

import app.simmo.domain.RuleAction
import app.simmo.domain.SimRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuleEditorActionTest {

    private val telstra = SimRef(1, "Telstra", "Telstra AU")

    @Test
    fun `supported actions map to their editor control`() {
        assertEquals(ActionChoice.USE_SIM, ActionChoice.of(RuleAction.UseSim(telstra)))
        assertEquals(ActionChoice.MATCHING_SIM, ActionChoice.of(RuleAction.UseMatchingCountrySim))
        assertEquals(ActionChoice.SYSTEM_DEFAULT, ActionChoice.of(RuleAction.SystemDefault))
    }

    @Test
    fun `a new rule defaults to the matching-country SIM`() {
        assertEquals(ActionChoice.MATCHING_SIM, ActionChoice.of(null))
    }

    @Test
    fun `actions the editor cannot represent have no control`() {
        assertNull(ActionChoice.of(RuleAction.Ask))
        assertNull(ActionChoice.of(RuleAction.HandOff.ViaDialIntent("com.google.android.apps.voice")))
    }

    @Test
    fun `saving with a chosen control uses that action`() {
        assertEquals(
            RuleAction.UseMatchingCountrySim,
            resolveEditorAction(ActionChoice.MATCHING_SIM, simRef = null, keepAction = RuleAction.Ask),
        )
        assertEquals(
            RuleAction.UseSim(telstra),
            resolveEditorAction(ActionChoice.USE_SIM, simRef = telstra, keepAction = null),
        )
    }

    @Test
    fun `saving without changing an unsupported action preserves it`() {
        assertEquals(RuleAction.Ask, resolveEditorAction(choice = null, simRef = null, keepAction = RuleAction.Ask))
        val handOff = RuleAction.HandOff.ViaDialIntent("com.google.android.apps.voice")
        assertEquals(handOff, resolveEditorAction(choice = null, simRef = null, keepAction = handOff))
    }
}
