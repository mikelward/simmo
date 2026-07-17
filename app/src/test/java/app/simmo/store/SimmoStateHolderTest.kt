package app.simmo.store

import androidx.datastore.core.DataStore
import app.simmo.domain.ActiveSim
import app.simmo.domain.PhoneAccountRef
import app.simmo.domain.RegisteredSim
import app.simmo.domain.Rule
import app.simmo.domain.RuleAction
import app.simmo.domain.RuleBook
import app.simmo.domain.RuleMatcher
import app.simmo.domain.SimRef
import kotlinx.coroutines.awaitCancellation
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
        val holder = SimmoStateHolder(FakeDataStore(SimmoState()), backgroundScope, installId = "install-1")
        assertNull(holder.current)
    }

    @Test
    fun `state from another install gets its subscription ids invalidated`() = runTest {
        val restored = SimmoState(
            rules = RuleBook(
                listOf(Rule(RuleMatcher.Country("AU"), RuleAction.UseSim(SimRef(1, "Telstra", "Telstra personal")))),
            ),
            simRegistry = listOf(RegisteredSim(1, "Telstra", "Telstra personal", 100L)),
            installId = "old-phone",
        )
        val holder = SimmoStateHolder(FakeDataStore(restored), backgroundScope, installId = "new-phone")

        val migrated = holder.state.filterNotNull().first { it.installId == "new-phone" }
        assertEquals(
            RuleAction.UseSim(SimRef(SimRef.INVALID_SUBSCRIPTION_ID, "Telstra", "Telstra personal")),
            migrated.rules.rules.single().action,
        )
        assertEquals(
            listOf(RegisteredSim(SimRef.INVALID_SUBSCRIPTION_ID, "Telstra", "Telstra personal", 100L)),
            migrated.simRegistry,
        )
    }

    @Test
    fun `stale ids are never published, even before the persistence write lands`() = runTest {
        // Codex P1 on PR #4: the eager collector must not expose restored
        // state ahead of the migration write. This store never completes
        // writes at all — published state must still come out validated.
        val restored = SimmoState(
            rules = RuleBook(
                listOf(Rule(RuleMatcher.Country("AU"), RuleAction.UseSim(SimRef(1, "Telstra", "Telstra personal")))),
            ),
            installId = "old-phone",
        )
        val neverPersisting = object : DataStore<SimmoState> {
            override val data: Flow<SimmoState> = MutableStateFlow(restored)
            override suspend fun updateData(
                transform: suspend (t: SimmoState) -> SimmoState,
            ): SimmoState = awaitCancellation()
        }
        val holder = SimmoStateHolder(neverPersisting, backgroundScope, installId = "new-phone")

        val published = holder.state.filterNotNull().first()
        assertEquals("new-phone", published.installId)
        assertEquals(
            RuleAction.UseSim(SimRef(SimRef.INVALID_SUBSCRIPTION_ID, "Telstra", "Telstra personal")),
            published.rules.rules.single().action,
        )
    }

    @Test
    fun `sim capture validates restored state so id collisions cannot overwrite rows`() = runTest {
        // Codex P2 on PR #4: on a new phone, an active SIM can reuse the same
        // subscription id as a restored registry row for a different carrier.
        // recordSeenSims must invalidate the restored row first, keeping it
        // (id sentinel) instead of ID-overwriting it.
        val restored = SimmoState(
            simRegistry = listOf(RegisteredSim(1, "Vodafone", "Voda AU", 100L)),
            installId = "old-phone",
        )
        val store = FakeDataStore(restored)
        val holder = SimmoStateHolder(store, backgroundScope, installId = "new-phone")
        holder.recordSeenSims(
            listOf(ActiveSim(1, "Telstra", "Telstra personal", PhoneAccountRef("a1"))),
            nowMillis = 900L,
        )
        val state = holder.state.filterNotNull().first { it.simRegistry.size == 2 }
        assertEquals(
            listOf(
                RegisteredSim(SimRef.INVALID_SUBSCRIPTION_ID, "Vodafone", "Voda AU", 100L),
                RegisteredSim(1, "Telstra", "Telstra personal", 900L),
            ),
            state.simRegistry,
        )
    }

    @Test
    fun `rule updates and sim capture land in the in-memory state`() = runTest {
        // Already adopted by this install so the restore guard stays inert here.
        val store = FakeDataStore(SimmoState(installId = "install-1"))
        val holder = SimmoStateHolder(store, backgroundScope, installId = "install-1")

        val rule = RuleAction.UseSim(SimRef(1, "Telstra", "Telstra personal"))
        holder.updateRules { it.copy(rules = listOf(Rule(RuleMatcher.Country("AU"), rule))) }
        holder.recordSeenSims(
            listOf(ActiveSim(1, "Telstra", "Telstra personal", PhoneAccountRef("a1"))),
            nowMillis = 700L,
        )

        val state = holder.state.filterNotNull().first()
        assertEquals(rule, state.rules.rules.single().action)
        assertEquals(
            listOf(RegisteredSim(1, "Telstra", "Telstra personal", 700L)),
            state.simRegistry,
        )
    }
}
