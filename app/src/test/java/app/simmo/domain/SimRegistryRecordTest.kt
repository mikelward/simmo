package app.simmo.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class SimRegistryRecordTest {

    private val telstraActive = ActiveSim(1, "Telstra", "Telstra personal", PhoneAccountRef("a1"))
    private val tmobileActive = ActiveSim(2, "T-Mobile", "T-Mobile US", PhoneAccountRef("a2"))

    @Test
    fun `newly seen sims are added to the registry`() {
        assertEquals(
            listOf(
                RegisteredSim(1, "Telstra", "Telstra personal", 500L),
                RegisteredSim(2, "T-Mobile", "T-Mobile US", 500L),
            ),
            emptyList<RegisteredSim>().recordSeen(listOf(telstraActive, tmobileActive), nowMillis = 500L),
        )
    }

    @Test
    fun `known sims refresh names and last-seen`() {
        val registry = listOf(RegisteredSim(1, "Old Carrier", "Old name", 100L))
        assertEquals(
            listOf(RegisteredSim(1, "Telstra", "Telstra personal", 900L)),
            registry.recordSeen(listOf(telstraActive), nowMillis = 900L),
        )
    }

    @Test
    fun `sims not currently active are kept for disabled-sim rules`() {
        val disabled = RegisteredSim(7, "Vodafone", "Voda AU", 100L)
        assertEquals(
            listOf(disabled, RegisteredSim(1, "Telstra", "Telstra personal", 900L)),
            listOf(disabled).recordSeen(listOf(telstraActive), nowMillis = 900L),
        )
    }

    @Test
    fun `no active sims leaves the registry untouched`() {
        val registry = listOf(RegisteredSim(1, "Telstra", "Telstra personal", 100L))
        assertEquals(registry, registry.recordSeen(emptyList(), nowMillis = 900L))
    }
}
