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
import app.simmo.domain.Rule
import app.simmo.domain.RuleAction
import app.simmo.domain.RuleBook
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

    @Test
    fun `a missed correction degrades to nothing on snapshot or engine failure`() {
        // Same degradation contract as decide: a broken snapshot provider or
        // a throwing engine must mean "no offer", never an exception.
        assertNull(RedirectionCoordinator(engine, { null }, { 0L }).missedCorrection(call))
        assertNull(RedirectionCoordinator(engine, { error("boom") }, { 0L }).missedCorrection(call))
        val throwingEngine = DecisionEngine { _, _ -> error("detector blew up") }
        assertNull(RedirectionCoordinator(throwingEngine, { snapshot() }, { 0L }).missedCorrection(call))
    }

    @Test
    fun `a missed correction passes through from the engine`() {
        // A shared overseas line with the setting on and no UI allowed: the
        // engine reports the correction it couldn't confirm.
        val gbCall = PlacedCall("+442071234567", currentAccount = null, interactive = false)
        val contacts = buildContactNumberIndex(
            numbers = listOf(
                RawContactNumber("mum", "Mum", "+442071234567"),
                RawContactNumber("mum", "Mum", "+61412345678"),
                RawContactNumber("aunt", "Aunt Vi", "+442071234567"),
            ),
            callActions = emptyList(),
            defaultRegion = "AU",
        )
        val coordinator = RedirectionCoordinator(
            engine,
            { snapshot().copy(contacts = contacts, correctContactNumbers = true) },
            { 0L },
        )
        assertEquals(
            NumberCorrection(
                listOf(CorrectionCandidate("Mum", "+61412345678")),
                sharedLine = true,
            ),
            coordinator.missedCorrection(gbCall),
        )
    }
}
