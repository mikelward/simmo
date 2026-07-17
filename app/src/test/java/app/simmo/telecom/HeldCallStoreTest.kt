package app.simmo.telecom

import app.simmo.domain.HeldCall
import app.simmo.domain.SimRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeldCallStoreTest {

    private val call = HeldCall("tel:+61412345678", listOf(SimRef(7, "Vodafone", "Voda AU")), 1_000L)

    @Test
    fun `holds the newest call until cleared`() {
        val store = HeldCallStore()
        assertNull(store.current(2_000L))
        store.park(call)
        assertEquals(call, store.current(2_000L))
        val newer = call.copy(handleUri = "tel:+61400000000", parkedAtMillis = 3_000L)
        store.park(newer)
        assertEquals(newer, store.current(3_500L))
        store.clear()
        assertNull(store.current(3_500L))
    }

    @Test
    fun `expired calls drop out instead of resurfacing`() {
        val store = HeldCallStore()
        store.park(call)
        assertNull(store.current(1_001L + HeldCall.TTL_MILLIS))
        // And they stay gone even for an earlier-clock query afterwards.
        assertNull(store.current(2_000L))
    }
}
