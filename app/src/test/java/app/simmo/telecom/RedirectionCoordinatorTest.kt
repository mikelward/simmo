package app.simmo.telecom

import app.simmo.domain.ActiveSim
import app.simmo.domain.DecisionEngine
import app.simmo.domain.DecisionSnapshot
import app.simmo.domain.PhoneAccountRef
import app.simmo.domain.PhoneNumberCountryDetector
import app.simmo.domain.PlacedCall
import app.simmo.domain.ProceedReason
import app.simmo.domain.Rule
import app.simmo.domain.RuleAction
import app.simmo.domain.RuleBook
import app.simmo.domain.RuleMatcher
import app.simmo.domain.SimRef
import app.simmo.domain.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

class RedirectionCoordinatorTest {

    private val engine = DecisionEngine(PhoneNumberCountryDetector())
    private val telstra = ActiveSim(1, "Telstra", "Telstra AU", PhoneAccountRef("acct-1"), "au")
    private val call = PlacedCall("+61 412 345 678", currentAccount = null, interactive = true)

    private fun snapshot() = DecisionSnapshot(
        rules = RuleBook(
            listOf(Rule(RuleMatcher.Country("AU"), RuleAction.UseSim(SimRef(1, "Telstra", "Telstra AU")))),
        ),
        activeSims = listOf(telstra),
        defaultRegion = "AU",
    )

    @Test
    fun `missing snapshot degrades to proceed, never waits or throws`() {
        val coordinator = RedirectionCoordinator(engine, { null }, { 0L })
        assertEquals(
            Verdict.Proceed(ProceedReason.SNAPSHOT_UNAVAILABLE),
            coordinator.decide(call),
        )
    }

    @Test
    fun `snapshot provider failure degrades to proceed`() {
        val coordinator = RedirectionCoordinator(engine, { error("boom") }, { 0L })
        assertEquals(
            Verdict.Proceed(ProceedReason.SNAPSHOT_UNAVAILABLE),
            coordinator.decide(call),
        )
    }

    @Test
    fun `engine failure degrades to proceed`() {
        val throwingEngine = DecisionEngine { _, _ -> error("detector blew up") }
        val coordinator = RedirectionCoordinator(throwingEngine, { snapshot() }, { 0L })
        assertEquals(
            Verdict.Proceed(ProceedReason.INTERNAL_ERROR),
            coordinator.decide(call),
        )
    }

    @Test
    fun `healthy path passes the engine's verdict through`() {
        val coordinator = RedirectionCoordinator(engine, { snapshot() }, { 0L })
        assertEquals(
            Verdict.RedirectToAccount(telstra.phoneAccount),
            coordinator.decide(call),
        )
    }
}
