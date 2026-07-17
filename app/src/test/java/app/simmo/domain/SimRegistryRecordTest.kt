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
    fun `restored entry with invalidated id re-adopts the matching active sim`() {
        // After a device transfer the registry entry carries the sentinel id;
        // seeing the same-named SIM active must update it, not duplicate it.
        val restored = RegisteredSim(SimRef.INVALID_SUBSCRIPTION_ID, "Telstra", "Telstra personal", 100L)
        assertEquals(
            listOf(RegisteredSim(1, "Telstra", "Telstra personal", 900L)),
            listOf(restored).recordSeen(listOf(telstraActive), nowMillis = 900L),
        )
    }

    @Test
    fun `re-downloaded profile with a new id re-binds instead of duplicating`() {
        // Same device: the profile was deleted and re-downloaded, so Android
        // assigned a fresh subscription id. The old row must re-bind, not stay
        // beside a duplicate new row.
        val stale = RegisteredSim(5, "Telstra", "Telstra personal", 100L)
        val redownloaded = ActiveSim(9, "Telstra", "Telstra personal", PhoneAccountRef("a9"))
        assertEquals(
            listOf(RegisteredSim(9, "Telstra", "Telstra personal", 900L)),
            listOf(stale).recordSeen(listOf(redownloaded), nowMillis = 900L),
        )
    }

    @Test
    fun `same-named stale rows adopt deterministically without duplicating`() {
        // Two identically named stale rows, one active candidate: the rows are
        // indistinguishable by identity, so the first adopts it (deterministic,
        // no duplicate row) and the other stays for a future SIM.
        val staleA = RegisteredSim(5, "Telstra", "Telstra personal", 100L)
        val staleB = RegisteredSim(6, "Telstra", "Telstra personal", 200L)
        val active = ActiveSim(9, "Telstra", "Telstra personal", PhoneAccountRef("a9"))
        assertEquals(
            listOf(RegisteredSim(9, "Telstra", "Telstra personal", 900L), staleB),
            listOf(staleA, staleB).recordSeen(listOf(active), nowMillis = 900L),
        )
    }

    @Test
    fun `restored entry with different names is kept, not adopted`() {
        val restored = RegisteredSim(SimRef.INVALID_SUBSCRIPTION_ID, "Vodafone", "Voda AU", 100L)
        assertEquals(
            listOf(restored, RegisteredSim(1, "Telstra", "Telstra personal", 900L)),
            listOf(restored).recordSeen(listOf(telstraActive), nowMillis = 900L),
        )
    }

    @Test
    fun `no active sims leaves the registry untouched`() {
        val registry = listOf(RegisteredSim(1, "Telstra", "Telstra personal", 100L))
        assertEquals(registry, registry.recordSeen(emptyList(), nowMillis = 900L))
    }
}
