package app.simmo.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class SimResolutionTest {

    private val telstra = ActiveSim(1, "Telstra", "Telstra AU", PhoneAccountRef("acct-1"))
    private val tmobile = ActiveSim(2, "T-Mobile", "T-Mobile US", PhoneAccountRef("acct-2"))

    @Test
    fun `subscription id match wins`() {
        assertEquals(
            SimResolution.Active(telstra),
            resolveSim(SimRef(1, "Telstra"), listOf(telstra, tmobile)),
        )
    }

    @Test
    fun `subscription id match wins even when the stored carrier name is stale`() {
        // Carrier rebranded but the profile (and its subscription id) survived.
        assertEquals(
            SimResolution.Active(telstra),
            resolveSim(SimRef(1, "Old Carrier Name"), listOf(telstra, tmobile)),
        )
    }

    @Test
    fun `stale subscription id re-binds by carrier name`() {
        // Profile was deleted and re-downloaded: new subscription id, same carrier.
        assertEquals(
            SimResolution.Active(telstra),
            resolveSim(SimRef(99, "Telstra"), listOf(telstra, tmobile)),
        )
    }

    @Test
    fun `carrier re-binding ignores case and whitespace`() {
        assertEquals(
            SimResolution.Active(telstra),
            resolveSim(SimRef(99, "  telstra "), listOf(telstra, tmobile)),
        )
    }

    @Test
    fun `no match means inactive`() {
        assertEquals(
            SimResolution.Inactive,
            resolveSim(SimRef(99, "Vodafone"), listOf(telstra, tmobile)),
        )
        assertEquals(SimResolution.Inactive, resolveSim(SimRef(1, "Telstra"), emptyList()))
    }

    @Test
    fun `two active sims from the same carrier are ambiguous`() {
        val telstra2 = ActiveSim(3, "Telstra", "Telstra work", PhoneAccountRef("acct-3"))
        assertEquals(
            SimResolution.Ambiguous(listOf(telstra, telstra2)),
            resolveSim(SimRef(99, "Telstra"), listOf(telstra, telstra2, tmobile)),
        )
    }

    @Test
    fun `subscription id match beats carrier ambiguity`() {
        val telstra2 = ActiveSim(3, "Telstra", "Telstra work", PhoneAccountRef("acct-3"))
        assertEquals(
            SimResolution.Active(telstra2),
            resolveSim(SimRef(3, "Telstra"), listOf(telstra, telstra2)),
        )
    }
}
