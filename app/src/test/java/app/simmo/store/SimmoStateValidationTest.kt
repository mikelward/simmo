package app.simmo.store

import app.simmo.domain.PhoneAccountRef
import app.simmo.domain.RegisteredSim
import app.simmo.domain.Rule
import app.simmo.domain.RuleAction
import app.simmo.domain.RuleBook
import app.simmo.domain.RuleMatcher
import app.simmo.domain.SimRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SimmoStateValidationTest {

    private val state = SimmoState(
        rules = RuleBook(
            rules = listOf(
                Rule(RuleMatcher.Country("AU"), RuleAction.UseSim(SimRef(1, "Telstra", "Telstra personal"))),
                Rule(RuleMatcher.Country("US"), RuleAction.HandOff.ViaPhoneAccount(PhoneAccountRef("acct-gv"))),
                Rule(RuleMatcher.Country("NZ"), RuleAction.Ask),
                Rule(RuleMatcher.AnyDestination, RuleAction.UseSim(SimRef(2, "T-Mobile", "T-Mobile US"))),
            ),
        ),
        simRegistry = listOf(
            RegisteredSim(1, "Telstra", "Telstra personal", 100L),
            RegisteredSim(2, "T-Mobile", "T-Mobile US", 200L),
        ),
        defaultRegionOverride = "AU",
        installId = "old-phone",
    )

    @Test
    fun `matching install id leaves the state untouched`() {
        assertSame(state, state.withInstallValidated("old-phone"))
    }

    @Test
    fun `mismatched install id invalidates every stored subscription id`() {
        val migrated = state.withInstallValidated("new-phone")
        val invalid = SimRef.INVALID_SUBSCRIPTION_ID
        assertEquals(
            RuleAction.UseSim(SimRef(invalid, "Telstra", "Telstra personal")),
            migrated.rules.rules[0].action,
        )
        assertEquals(
            RuleAction.UseSim(SimRef(invalid, "T-Mobile", "T-Mobile US")),
            migrated.rules.rules[3].action,
        )
        // Hand-off and Ask rules carry no subscription ids and pass through;
        // matchers are untouched.
        assertEquals(state.rules.rules[1], migrated.rules.rules[1])
        assertEquals(state.rules.rules[2], migrated.rules.rules[2])
        assertEquals(state.rules.rules.map { it.matcher }, migrated.rules.rules.map { it.matcher })
        assertEquals(
            listOf(
                RegisteredSim(invalid, "Telstra", "Telstra personal", 100L),
                RegisteredSim(invalid, "T-Mobile", "T-Mobile US", 200L),
            ),
            migrated.simRegistry,
        )
        assertEquals("new-phone", migrated.installId)
        assertEquals("AU", migrated.defaultRegionOverride)
    }

    @Test
    fun `state without an install id is treated as foreign`() {
        // Pre-marker state (or a restore from a version before the field):
        // conservative invalidation costs nothing — name re-binding silently
        // resolves every ref whose names still match.
        val migrated = state.copy(installId = null).withInstallValidated("new-phone")
        assertEquals("new-phone", migrated.installId)
        assertEquals(
            RuleAction.UseSim(SimRef(SimRef.INVALID_SUBSCRIPTION_ID, "Telstra", "Telstra personal")),
            migrated.rules.rules[0].action,
        )
    }

    @Test
    fun `validation is idempotent`() {
        val once = state.withInstallValidated("new-phone")
        assertSame(once, once.withInstallValidated("new-phone"))
    }
}
