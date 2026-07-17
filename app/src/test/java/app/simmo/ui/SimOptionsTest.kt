package app.simmo.ui

import app.simmo.domain.ActiveSim
import app.simmo.domain.PhoneAccountRef
import app.simmo.domain.RegisteredSim
import app.simmo.domain.SimRef
import org.junit.Assert.assertEquals
import org.junit.Test

class SimOptionsTest {

    @Test
    fun `active sims sort before disabled ones`() {
        val active = ActiveSim(1, "Telstra", "Telstra AU", PhoneAccountRef("a1"), "au")
        val disabled = RegisteredSim(7, "Vodafone", "Voda AU", 100L)
        val options = buildSimOptions(listOf(disabled), listOf(active))
        assertEquals(listOf("Telstra AU", "Voda AU"), options.map { it.label })
        assertEquals(listOf(true, false), options.map { it.active })
    }

    @Test
    fun `restored disabled sims with the sentinel id are not collapsed`() {
        // After a device transfer every stored id is invalidated to -1; distinct
        // carrier/display identities must remain separately selectable.
        val invalid = SimRef.INVALID_SUBSCRIPTION_ID
        val registry = listOf(
            RegisteredSim(invalid, "Telstra", "Telstra personal", 100L),
            RegisteredSim(invalid, "Vodafone", "Voda AU", 200L),
        )
        val options = buildSimOptions(registry, activeSims = emptyList())
        assertEquals(setOf("Telstra personal", "Voda AU"), options.map { it.label }.toSet())
    }

    @Test
    fun `an active sim is not duplicated by its registry row`() {
        val active = ActiveSim(1, "Telstra", "Telstra AU", PhoneAccountRef("a1"), "au")
        val registry = listOf(RegisteredSim(1, "Telstra", "Telstra AU", 100L))
        assertEquals(1, buildSimOptions(registry, listOf(active)).size)
    }
}
