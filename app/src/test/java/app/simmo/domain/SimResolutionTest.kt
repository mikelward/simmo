package app.simmo.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class SimResolutionTest {

    private val telstra = ActiveSim(1, "Telstra", "Telstra personal", PhoneAccountRef("acct-1"))
    private val tmobile = ActiveSim(2, "T-Mobile", "T-Mobile US", PhoneAccountRef("acct-2"))

    private fun ActiveSim.ref() = SimRef(subscriptionId, carrierName, displayName)

    @Test
    fun `subscription id match wins`() {
        assertEquals(
            SimResolution.Active(telstra),
            resolveSim(telstra.ref(), listOf(telstra, tmobile)),
        )
    }

    @Test
    fun `subscription id match wins even when the stored names are stale`() {
        // Carrier rebranded and the SIM was renamed, but the profile (and its
        // subscription id) survived.
        assertEquals(
            SimResolution.Active(telstra),
            resolveSim(SimRef(1, "Old Carrier", "Old name"), listOf(telstra, tmobile)),
        )
    }

    @Test
    fun `stale subscription id re-binds by carrier and display name`() {
        // Profile was deleted and re-downloaded: new subscription id, same names.
        assertEquals(
            SimResolution.Active(telstra),
            resolveSim(SimRef(99, "Telstra", "Telstra personal"), listOf(telstra, tmobile)),
        )
    }

    @Test
    fun `name re-binding ignores case and whitespace`() {
        assertEquals(
            SimResolution.Active(telstra),
            resolveSim(SimRef(99, "  telstra ", "TELSTRA Personal "), listOf(telstra, tmobile)),
        )
    }

    @Test
    fun `carrier-only match is ambiguous, never silently bound`() {
        // The intended profile is gone; a *different* Telstra SIM being active
        // must not silently take over the rule (wrong plan, wrong bill).
        assertEquals(
            SimResolution.Ambiguous(listOf(telstra)),
            resolveSim(SimRef(99, "Telstra", "Telstra work"), listOf(telstra, tmobile)),
        )
    }

    @Test
    fun `no carrier match means inactive`() {
        assertEquals(
            SimResolution.Inactive,
            resolveSim(SimRef(99, "Vodafone", "Voda AU"), listOf(telstra, tmobile)),
        )
        assertEquals(SimResolution.Inactive, resolveSim(telstra.ref(), emptyList()))
    }

    @Test
    fun `two active sims matching carrier and name are ambiguous`() {
        val telstra2 = ActiveSim(3, "Telstra", "Telstra personal", PhoneAccountRef("acct-3"))
        assertEquals(
            SimResolution.Ambiguous(listOf(telstra, telstra2)),
            resolveSim(SimRef(99, "Telstra", "Telstra personal"), listOf(telstra, telstra2, tmobile)),
        )
    }

    @Test
    fun `unique carrier plus name match wins over a same-carrier sibling`() {
        val telstra2 = ActiveSim(3, "Telstra", "Telstra work", PhoneAccountRef("acct-3"))
        assertEquals(
            SimResolution.Active(telstra2),
            resolveSim(SimRef(99, "Telstra", "Telstra work"), listOf(telstra, telstra2)),
        )
    }

    @Test
    fun `subscription id match beats name ambiguity`() {
        val telstra2 = ActiveSim(3, "Telstra", "Telstra personal", PhoneAccountRef("acct-3"))
        assertEquals(
            SimResolution.Active(telstra2),
            resolveSim(telstra2.ref(), listOf(telstra, telstra2)),
        )
    }
}
