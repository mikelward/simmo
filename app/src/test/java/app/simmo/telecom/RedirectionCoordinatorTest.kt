package app.simmo.telecom

import app.simmo.domain.ActiveSim
import app.simmo.domain.CorrectionCandidate
import app.simmo.domain.DecisionEngine
import app.simmo.domain.DecisionSnapshot
import app.simmo.domain.NumberCorrection
import app.simmo.domain.PhoneAccountRef
import app.simmo.domain.PhoneNumberCountryDetector
import app.simmo.domain.PlacedCall
import app.simmo.domain.ProceedReason
import app.simmo.domain.RawContactNumber
import app.simmo.domain.CallingRule
import app.simmo.domain.RuleAction
import app.simmo.domain.CallingRuleBook
import app.simmo.domain.RuleMatcher
import app.simmo.domain.SimRef
import app.simmo.domain.Verdict
import app.simmo.domain.buildContactNumberIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RedirectionCoordinatorTest {

    private val engine = DecisionEngine(PhoneNumberCountryDetector())
    private val telstra = ActiveSim(1, "Telstra", "Telstra AU", PhoneAccountRef("acct-1"), "au")
    private val call = PlacedCall("+61 412 345 678", currentAccount = null, interactive = true)

    private fun snapshot() = DecisionSnapshot(
        rules = CallingRuleBook(
            listOf(CallingRule(RuleMatcher.Country("AU"), RuleAction.UseSim(SimRef(1, "Telstra", "Telstra AU")))),
        ),
        activeSims = listOf(telstra),
        defaultRegion = "AU",
    )

    /** Mum and Aunt Vi share the GB line; only Mum has a local number. */
    private val sharedLineContacts = buildContactNumberIndex(
        numbers = listOf(
            RawContactNumber("mum", "Mum", "+442071234567"),
            RawContactNumber("mum", "Mum", "+61412345678"),
            RawContactNumber("aunt", "Aunt Vi", "+442071234567"),
        ),
        callActions = emptyList(),
        defaultRegion = "AU",
    )

    private val sharedLineCorrection = NumberCorrection(
        listOf(CorrectionCandidate("Mum", "+61412345678")),
        sharedLine = true,
    )

    @Test
    fun `missing snapshot degrades to proceed, never waits or throws`() {
        val coordinator = RedirectionCoordinator(engine, { null }, { 0L })
        assertEquals(
            RedirectionCoordinator.CallDecision(Verdict.Proceed(ProceedReason.SNAPSHOT_UNAVAILABLE)),
            coordinator.decide(call),
        )
    }

    @Test
    fun `snapshot provider failure degrades to proceed`() {
        val coordinator = RedirectionCoordinator(engine, { error("boom") }, { 0L })
        assertEquals(
            RedirectionCoordinator.CallDecision(Verdict.Proceed(ProceedReason.SNAPSHOT_UNAVAILABLE)),
            coordinator.decide(call),
        )
    }

    @Test
    fun `engine failure degrades to proceed with nothing to offer`() {
        val throwingEngine = DecisionEngine { _, _ -> error("detector blew up") }
        val coordinator = RedirectionCoordinator(throwingEngine, { snapshot() }, { 0L })
        assertEquals(
            RedirectionCoordinator.CallDecision(Verdict.Proceed(ProceedReason.INTERNAL_ERROR)),
            coordinator.decide(call),
        )
    }

    @Test
    fun `healthy path passes the engine's verdict through`() {
        val coordinator = RedirectionCoordinator(engine, { snapshot() }, { 0L })
        val decision = coordinator.decide(call)
        assertEquals(Verdict.RedirectToAccount(telstra.phoneAccount), decision.verdict)
        assertNull(decision.missedCorrection)
    }

    @Test
    fun `a missed correction rides the decision`() {
        // A shared overseas line with the setting on and no UI allowed: the
        // engine reports the correction it couldn't confirm, alongside the
        // verdict that let the call proceed as dialed.
        val gbCall = PlacedCall("+442071234567", currentAccount = null, interactive = false)
        val coordinator = RedirectionCoordinator(
            engine,
            { snapshot().copy(contacts = sharedLineContacts, correctContactNumbers = true) },
            { 0L },
        )
        val decision = coordinator.decide(gbCall)
        assertEquals(Verdict.Proceed(ProceedReason.NO_APPLICABLE_RULE), decision.verdict)
        assertEquals(sharedLineCorrection, decision.missedCorrection)
    }

    @Test
    fun `the missed correction is judged on the decision's own snapshot`() {
        // Cold start: the decision saw no chooser targets, so the interactive
        // ambiguous correction went out as dialed. A telephony refresh landing
        // right after must not reclassify it as chooser-confirmable against a
        // target set the decision never saw — swallowing the promised offer
        // (Codex on PR #44). One snapshot read serves both.
        var reads = 0
        val provider = {
            reads++
            val base = snapshot().copy(contacts = sharedLineContacts, correctContactNumbers = true)
            if (reads == 1) base.copy(activeSims = emptyList()) else base
        }
        val gbCall = PlacedCall("+442071234567", currentAccount = null, interactive = true)
        val decision = RedirectionCoordinator(engine, provider, { 0L }).decide(gbCall)
        assertEquals(Verdict.Proceed(ProceedReason.NO_APPLICABLE_RULE), decision.verdict)
        assertEquals(sharedLineCorrection, decision.missedCorrection)
        assertEquals(1, reads)
    }
}
