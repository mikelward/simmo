package app.simmo.ui

import app.simmo.domain.ActiveSim
import app.simmo.domain.PhoneAccountRef
import app.simmo.domain.RegisteredSim
import app.simmo.domain.SimRef
import org.junit.Assert.assertEquals
import org.junit.Test

class SimRegistryRowsTest {

    private val telstraActive = ActiveSim(1, "Telstra", "Telstra personal", PhoneAccountRef("a1"))

    private fun rows(registry: List<RegisteredSim>, active: List<ActiveSim>) =
        buildRegistryRows(registry, active, formatLastSeen = { "day $it" })

    @Test
    fun `active sims lead, stale rows sink to the bottom by last seen`() {
        val registry = listOf(
            RegisteredSim(7, "Vodafone", "Voda AU", 100L),
            RegisteredSim(1, "Telstra", "Telstra personal", 900L),
            RegisteredSim(8, "Optus", "Optus travel", 500L),
        )
        assertEquals(
            listOf(
                RegistrySimRowUi(SimRef(1, "Telstra", "Telstra personal"), "Telstra personal", "Telstra", active = true, lastSeenLabel = "day 900"),
                RegistrySimRowUi(SimRef(8, "Optus", "Optus travel"), "Optus travel", "Optus", active = false, lastSeenLabel = "day 500"),
                RegistrySimRowUi(SimRef(7, "Vodafone", "Voda AU"), "Voda AU", "Vodafone", active = false, lastSeenLabel = "day 100"),
            ),
            rows(registry, listOf(telstraActive)),
        )
    }

    @Test
    fun `name falls back to the carrier and drops a redundant carrier line`() {
        val registry = listOf(
            // Blank display name: the carrier is the name, so no second line.
            RegisteredSim(2, "T-Mobile", "", 100L),
            // Display name equal to the carrier (case/space aside): same.
            RegisteredSim(3, "Optus", " optus ", 100L),
        )
        val built = rows(registry, emptyList())
        assertEquals("T-Mobile", built[0].name)
        assertEquals(null, built[0].carrier)
        assertEquals(null, built[1].carrier)
    }
}
