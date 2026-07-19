package app.simmo.store

import app.simmo.domain.DataExpectation
import app.simmo.domain.DataRule
import app.simmo.domain.DataRuleBook
import app.simmo.domain.DataSimScope
import app.simmo.domain.PhoneAccountRef
import app.simmo.domain.RegisteredSim
import app.simmo.domain.CallingRule
import app.simmo.domain.RuleAction
import app.simmo.domain.CallingRuleBook
import app.simmo.domain.RuleMatcher
import app.simmo.domain.SimRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SimmoStateValidationTest {

    private val state = SimmoState(
        rules = CallingRuleBook(
            rules = listOf(
                CallingRule(RuleMatcher.Country("AU"), RuleAction.UseSim(SimRef(1, "Telstra", "Telstra personal"))),
                CallingRule(RuleMatcher.Country("US"), RuleAction.HandOff.ViaPhoneAccount(PhoneAccountRef("acct-gv"))),
                CallingRule(RuleMatcher.Country("NZ"), RuleAction.Ask),
                CallingRule(RuleMatcher.AnyDestination, RuleAction.UseSim(SimRef(2, "T-Mobile", "T-Mobile US"))),
            ),
        ),
        simRegistry = listOf(
            RegisteredSim(1, "Telstra", "Telstra personal", 100L),
            RegisteredSim(2, "T-Mobile", "T-Mobile US", 200L),
        ),
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
                DataRule(RuleMatcher.Country("TR"), DataExpectation.AlwaysWarn),
            ) + DataRuleBook.defaultDataRules(),
        ),
        defaultRegionOverride = "AU",
        dataWatchMark = "roaming:2:AU",
        dataDismissMarks = setOf("roaming:2:NZ"),
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
        // The arrival mark embeds a subscription id from the old device; a
        // stale one could suppress the first genuine warning on the new
        // phone, so adoption clears it. The per-trip dismiss mark embeds one
        // too, and clears for the same reason.
        assertEquals(null, migrated.dataWatchMark)
        assertEquals(emptySet<String>(), migrated.dataDismissMarks)
    }

    @Test
    fun `mismatched install id also invalidates SIM refs inside data rules`() {
        // Data rules store SimRefs too (use-SIM expectation, scoped RoamingOk);
        // a restored subscription id must never exact-match a stranger's SIM.
        val migrated = state.withInstallValidated("new-phone")
        val invalid = SimRef.INVALID_SUBSCRIPTION_ID
        assertEquals(
            DataExpectation.UseSimForData(SimRef(invalid, "Telstra", "Telstra personal")),
            migrated.dataRules.rules[0].expectation,
        )
        assertEquals(
            DataExpectation.RoamingOk(
                DataSimScope.Sims(listOf(SimRef(invalid, "T-Mobile", "T-Mobile US"))),
            ),
            migrated.dataRules.rules[1].expectation,
        )
        // AlwaysWarn and the preseeded homed-in-matched rule carry no ids.
        assertEquals(state.dataRules.rules[2], migrated.dataRules.rules[2])
        assertEquals(state.dataRules.rules[3], migrated.dataRules.rules[3])
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
