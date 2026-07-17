package app.simmo.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeldCallTest {

    private val voda = SimRef(7, "Vodafone", "Voda AU")
    private val held = HeldCall("tel:+61412345678", listOf(voda), parkedAtMillis = 1_000L)

    @Test
    fun `expires only after the ttl`() {
        assertFalse(held.expired(1_000L + HeldCall.TTL_MILLIS))
        assertTrue(held.expired(1_001L + HeldCall.TTL_MILLIS))
    }

    @Test
    fun `activation is the wanted sim coming active`() {
        val nowActive = ActiveSim(7, "Vodafone", "Voda AU", PhoneAccountRef("a7"))
        assertEquals(nowActive, held.activatedWantedSim(listOf(nowActive)))
        // A different active SIM is not the one the rule wanted.
        val other = ActiveSim(1, "Telstra", "Telstra AU", PhoneAccountRef("a1"))
        assertNull(held.activatedWantedSim(listOf(other)))
        assertNull(held.activatedWantedSim(emptyList()))
    }

    @Test
    fun `activation re-binds by name but never through ambiguity`() {
        // Re-downloaded profile under a fresh id: the name ladder finds it.
        val redownloaded = ActiveSim(9, "Vodafone", "Voda AU", PhoneAccountRef("a9"))
        assertEquals(redownloaded, held.activatedWantedSim(listOf(redownloaded)))
        // Two same-named candidates: ambiguous, so no offer — a notification
        // must not gamble on which SIM the rule meant.
        val twin = ActiveSim(10, "Vodafone", "Voda AU", PhoneAccountRef("a10"))
        assertNull(held.activatedWantedSim(listOf(redownloaded, twin)))
    }
}
