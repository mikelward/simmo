package app.simmo.store

import androidx.datastore.core.DataStore
import app.simmo.domain.ActiveSim
import app.simmo.domain.PhoneAccountRef
import app.simmo.domain.RegisteredSim
import app.simmo.domain.RuleAction
import app.simmo.domain.SimRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Holder logic against an in-memory [DataStore]: the update plumbing and the
 * eager in-memory state. The on-disk format is covered by
 * [SimmoStateSerializationTest]; the real file plumbing is androidx's.
 */
class SimmoStateHolderTest {

    private class FakeDataStore(initial: SimmoState) : DataStore<SimmoState> {
        private val flow = MutableStateFlow(initial)
        override val data: Flow<SimmoState> = flow
        override suspend fun updateData(transform: suspend (t: SimmoState) -> SimmoState): SimmoState {
            // Plain read-transform-set is race-free under single-threaded test use.
            val next = transform(flow.value)
            flow.value = next
            return next
        }
    }

    @Test
    fun `state starts null until the first load lands`() = runTest {
        val holder = SimmoStateHolder(FakeDataStore(SimmoState()), backgroundScope)
        assertNull(holder.current)
    }

    @Test
    fun `rule updates and sim capture land in the in-memory state`() = runTest {
        val store = FakeDataStore(SimmoState())
        val holder = SimmoStateHolder(store, backgroundScope)

        val rule = RuleAction.UseSim(SimRef(1, "Telstra", "Telstra personal"))
        holder.updateRules { it.copy(countryRules = mapOf("AU" to rule)) }
        holder.recordSeenSims(
            listOf(ActiveSim(1, "Telstra", "Telstra personal", PhoneAccountRef("a1"))),
            nowMillis = 700L,
        )

        val state = holder.state.filterNotNull().first()
        assertEquals(rule, state.rules.countryRules["AU"])
        assertEquals(
            listOf(RegisteredSim(1, "Telstra", "Telstra personal", 700L)),
            state.simRegistry,
        )
    }
}
