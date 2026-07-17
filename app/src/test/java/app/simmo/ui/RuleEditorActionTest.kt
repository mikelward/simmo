package app.simmo.ui

import app.simmo.domain.ContactCallApp
import app.simmo.domain.RuleAction
import app.simmo.domain.SimRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleEditorActionTest {

    private val telstra = SimRef(1, "Telstra", "Telstra AU")
    private val telstraOption = SimOptionUi(telstra, "Telstra AU", active = true)

    @Test
    fun `supported actions map to their editor control`() {
        assertEquals(ActionChoice.USE_SIM, ActionChoice.of(RuleAction.UseSim(telstra)))
        assertEquals(ActionChoice.MATCHING_SIM, ActionChoice.of(RuleAction.UseMatchingCountrySim))
        assertEquals(ActionChoice.ASK, ActionChoice.of(RuleAction.Ask))
        assertEquals(ActionChoice.SYSTEM_DEFAULT, ActionChoice.of(RuleAction.SystemDefault))
    }

    @Test
    fun `a new rule defaults to the matching-country SIM`() {
        assertEquals(ActionChoice.MATCHING_SIM, ActionChoice.of(null))
    }

    @Test
    fun `actions the editor cannot represent have no control`() {
        assertNull(ActionChoice.of(RuleAction.HandOff.ViaDialIntent("com.google.android.apps.voice")))
    }

    @Test
    fun `WhatsApp hand-off maps to and from its control`() {
        val whatsApp = RuleAction.HandOff.ViaContactApp(ContactCallApp.WHATSAPP)
        assertEquals(ActionChoice.HANDOFF_WHATSAPP, ActionChoice.of(whatsApp))
        assertEquals(whatsApp, resolveEditorAction(ActionChoice.HANDOFF_WHATSAPP, simRef = null, keepAction = null))
    }

    @Test
    fun `saving with a chosen control uses that action`() {
        assertEquals(
            RuleAction.UseMatchingCountrySim,
            resolveEditorAction(
                ActionChoice.MATCHING_SIM,
                simRef = null,
                keepAction = RuleAction.HandOff.ViaDialIntent("com.example.voip"),
            ),
        )
        assertEquals(
            RuleAction.UseSim(telstra),
            resolveEditorAction(ActionChoice.USE_SIM, simRef = telstra, keepAction = null),
        )
        assertEquals(
            RuleAction.Ask,
            resolveEditorAction(ActionChoice.ASK, simRef = null, keepAction = null),
        )
    }

    @Test
    fun `saving without changing an unsupported action preserves it`() {
        val handOff = RuleAction.HandOff.ViaDialIntent("com.google.android.apps.voice")
        assertEquals(handOff, resolveEditorAction(choice = null, simRef = null, keepAction = handOff))
    }

    @Test
    fun `a specific-SIM action needs a SIM that is actually offered`() {
        val options = listOf(telstraOption)
        // Resolvable SIM → valid.
        assertTrue(isValid(matchesAny = true, regions = emptyList(), groups = emptyList(), ActionChoice.USE_SIM, telstra, options))
        // Stored SIM that resolves to no row (renamed/removed) → invalid, forcing re-link.
        val stale = SimRef(SimRef.INVALID_SUBSCRIPTION_ID, "Vodafone", "Old name")
        assertFalse(isValid(matchesAny = true, regions = emptyList(), groups = emptyList(), ActionChoice.USE_SIM, stale, options))
        assertFalse(isValid(matchesAny = true, regions = emptyList(), groups = emptyList(), ActionChoice.USE_SIM, simRef = null, options))
    }

    @Test
    fun `non-SIM actions do not require a SIM`() {
        val noSims = emptyList<SimOptionUi>()
        assertTrue(isValid(matchesAny = true, regions = emptyList(), groups = emptyList(), ActionChoice.MATCHING_SIM, simRef = null, noSims))
        assertTrue(isValid(matchesAny = true, regions = emptyList(), groups = emptyList(), ActionChoice.SYSTEM_DEFAULT, simRef = null, noSims))
        // A preserved unsupported action (null choice) is valid too.
        assertTrue(isValid(matchesAny = true, regions = emptyList(), groups = emptyList(), action = null, simRef = null, noSims))
    }

    @Test
    fun `a country matcher needs a region or a group`() {
        val options = listOf(telstraOption)
        assertFalse(isValid(matchesAny = false, regions = emptyList(), groups = emptyList(), ActionChoice.MATCHING_SIM, simRef = null, options))
        assertTrue(isValid(matchesAny = false, regions = listOf("AU"), groups = emptyList(), ActionChoice.MATCHING_SIM, simRef = null, options))
        // A group alone scopes the rule just as well as a country does.
        assertTrue(isValid(matchesAny = false, regions = emptyList(), groups = listOf("eu_eea"), ActionChoice.MATCHING_SIM, simRef = null, options))
    }
}
