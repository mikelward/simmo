package app.simmo.store

import androidx.datastore.core.DataStore
import app.simmo.domain.ActiveSim
import app.simmo.domain.CustomGroup
import app.simmo.domain.DataExpectation
import app.simmo.domain.DataRule
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
import org.junit.Assert.assertTrue
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
    fun `the data watch mark is claimed exactly once per arrival`() = runTest {
        val store = FakeDataStore(SimmoState(installId = "install-1"))
        val holder = SimmoStateHolder(store, backgroundScope, installId = "install-1")
        // Only the claim that changes the mark wins — overlapping refreshes
        // evaluating the same arrival must not each post a warning.
        assertEquals(true, holder.claimDataWatchMark("roaming:2:AU"))
        assertEquals(false, holder.claimDataWatchMark("roaming:2:AU"))
        assertEquals("roaming:2:AU", store.data.first().dataWatchMark)
        // A new arrival replaces the mark and wins again.
        assertEquals(true, holder.claimDataWatchMark("roaming:2:NZ"))
        // Clearing is compare-and-swap: a clear decided against the OLD mark
        // must not delete an arrival a concurrent refresh just claimed — and
        // must report the loss, so the caller doesn't cancel the fresh
        // arrival's notification either.
        assertEquals(false, holder.clearDataWatchMark("roaming:2:AU"))
        assertEquals("roaming:2:NZ", store.data.first().dataWatchMark)
        // Clearing the mark actually observed re-arms a later return trip,
        // and the winner is told so it also takes the posted warning down.
        assertEquals(true, holder.clearDataWatchMark("roaming:2:NZ"))
        assertEquals(null, store.data.first().dataWatchMark)
        assertEquals(true, holder.claimDataWatchMark("roaming:2:AU"))
    }

    @Test
    fun `data-rule edits write through, picker groups in the same transaction`() = runTest {
        val store = FakeDataStore(SimmoState(installId = "install-1"))
        val holder = SimmoStateHolder(store, backgroundScope, installId = "install-1")
        val rule = DataRule(RuleMatcher.Country("AU"), DataExpectation.AlwaysWarn)
        holder.updateDataRules { it.withRuleAdded(rule) }
        // The write boundary mints an id for a rule added without one; the rest
        // round-trips verbatim.
        val stored = store.data.first().dataRules.rules.first()
        assertEquals(rule, stored.copy(id = ""))
        assertTrue(stored.id.isNotBlank())
        // A group built in the picker commits with the rule that references
        // it — one transaction, so the state never holds a dangling group id.
        val group = CustomGroup("g1", "Trip", listOf("AU", "NZ"))
        val grouped = DataRule(
            RuleMatcher.Countries(groupIds = listOf("g1")),
            DataExpectation.AlwaysWarn,
        )
        holder.updateGroupsAndDataRules(listOf(group)) { it.withRuleAdded(grouped) }
        val state = store.data.first()
        assertEquals(grouped, state.dataRules.rules.first().copy(id = ""))
        assertEquals(listOf(group), state.customGroups)
    }

    @Test
    fun `a rule added through the write boundary always gets an id`() = runTest {
        // The chooser's "remember" persists directly via updateRules, bypassing
        // the view-model's creation path — the boundary must still mint an id so
        // the rule stays editable by id (Codex on PR #60).
        val store = FakeDataStore(SimmoState(rules = RuleBook(emptyList()), installId = "install-1"))
        val holder = SimmoStateHolder(store, backgroundScope, installId = "install-1")
        holder.updateRules { it.withRuleAdded(Rule(RuleMatcher.Country("US"), RuleAction.Ask)) }
        assertTrue(store.data.first().rules.rules.single().id.isNotBlank())
    }

    @Test
    fun `purge drops every soft-deleted rule, data rule, and group in one write`() = runTest {
        val store = FakeDataStore(
            SimmoState(
                rules = RuleBook(
                    listOf(
                        Rule(RuleMatcher.Country("AU"), RuleAction.Ask, id = "keep-r"),
                        Rule(RuleMatcher.Country("US"), RuleAction.Ask, id = "gone-r", pendingRemoval = true),
                    ),
                ),
                dataRules = app.simmo.domain.DataRuleBook(
                    listOf(
                        DataRule(RuleMatcher.Country("AU"), DataExpectation.AlwaysWarn, id = "keep-d"),
                        DataRule(RuleMatcher.Country("US"), DataExpectation.AlwaysWarn, id = "gone-d", pendingRemoval = true),
                    ),
                ),
                customGroups = listOf(
                    CustomGroup("keep-g", "Keep", listOf("AU")),
                    CustomGroup("gone-g", "Gone", listOf("US"), pendingRemoval = true),
                ),
                installId = "install-1",
            ),
        )
        val holder = SimmoStateHolder(store, backgroundScope, installId = "install-1")
        holder.purgePendingRemovals()
        val state = store.data.first()
        assertEquals(listOf("keep-r"), state.rules.rules.map { it.id })
        assertEquals(listOf("keep-d"), state.dataRules.rules.map { it.id })
        assertEquals(listOf("keep-g"), state.customGroups.map { it.id })
    }

    @Test
    fun `first capture is reported from the stored state, not the async flow`() = runTest {
        // Codex P2 on PR #21: an existing user's cold-start capture can run
        // before the eager load publishes; the fresh-install signal must come
        // from inside the write, or a real new-SIM notification is swallowed.
        val existing = SimmoState(
            simRegistry = listOf(RegisteredSim(1, "Telstra", "Telstra personal", 100L)),
            installId = "install-1",
        )
        val holder = SimmoStateHolder(FakeDataStore(existing), backgroundScope, installId = "install-1")
        val newSim = ActiveSim(2, "Optus", "Optus travel", PhoneAccountRef("a2"))
        // Even with holder.current still unpublished, this is NOT a first capture.
        val capture = holder.recordSeenSims(listOf(newSim), nowMillis = 900L)
        assertEquals(false, capture.firstCapture)
        assertEquals(2, capture.registry.size)

        val fresh = SimmoStateHolder(FakeDataStore(SimmoState()), backgroundScope, installId = "install-1")
        assertEquals(true, fresh.recordSeenSims(listOf(newSim), nowMillis = 900L).firstCapture)
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
                RegisteredSim(1, "Telstra", "Telstra personal", 900L, needsRulePrompt = true),
            ),
            state.simRegistry,
        )
    }

    @Test
    fun `analytics opt-in defaults on and an opt-out persists`() = runTest {
        val store = FakeDataStore(SimmoState(installId = "install-1"))
        val holder = SimmoStateHolder(store, backgroundScope, installId = "install-1")
        assertEquals(true, holder.state.filterNotNull().first().analyticsOptIn)

        holder.setAnalyticsOptIn(false)
        assertEquals(false, store.data.first().analyticsOptIn)
    }

    @Test
    fun `call feedback settings persist and the delay is clamped`() = runTest {
        val store = FakeDataStore(SimmoState(installId = "install-1"))
        val holder = SimmoStateHolder(store, backgroundScope, installId = "install-1")

        holder.setShowCallToast(true)
        assertEquals(true, store.data.first().showCallToast)

        holder.setCallDelaySeconds(5)
        assertEquals(5, store.data.first().callDelaySeconds)

        // Out-of-range writes clamp instead of storing an absurd countdown.
        holder.setCallDelaySeconds(999)
        assertEquals(SimmoState.MAX_CALL_DELAY_SECONDS, store.data.first().callDelaySeconds)
        holder.setCallDelaySeconds(-1)
        assertEquals(0, store.data.first().callDelaySeconds)

        holder.setCorrectContactNumbers(true)
        assertEquals(true, store.data.first().correctContactNumbers)

        holder.setGuardOverseasHandsFree(true)
        assertEquals(true, store.data.first().guardOverseasHandsFree)
        holder.setGuardDisabledSimHandsFree(true)
        assertEquals(true, store.data.first().guardDisabledSimHandsFree)
    }

    @Test
    fun `a picker-created group and its rule commit in one write`() = runTest {
        // Codex on PR #35: the group a rule references must be persisted in the
        // same transaction as the rule, so the state can never hold a rule
        // pointing at a group that isn't there.
        var writes = 0
        val start = SimmoState(rules = RuleBook(emptyList()), installId = "install-1")
        val store = object : DataStore<SimmoState> {
            private val flow = MutableStateFlow(start)
            override val data: Flow<SimmoState> = flow
            override suspend fun updateData(transform: suspend (t: SimmoState) -> SimmoState): SimmoState {
                writes++
                return transform(flow.value).also { flow.value = it }
            }
        }
        val holder = SimmoStateHolder(store, backgroundScope, installId = "install-1")
        holder.state.filterNotNull().first() // let the startup adoption write settle
        val writesBefore = writes

        val group = app.simmo.domain.CustomGroup("custom:z", "Zone 1", listOf("GB", "FR"))
        val rule = Rule(RuleMatcher.Countries(groupIds = listOf("custom:z")), RuleAction.SystemDefault)
        holder.updateGroupsAndRules(listOf(group)) { it.withRuleAdded(rule) }

        val state = holder.state.filterNotNull().first { it.rules.rules.isNotEmpty() }
        assertEquals(listOf(group), state.customGroups)
        // The boundary mints an id for the added rule; the rest is verbatim.
        val storedRule = state.rules.rules.single()
        assertEquals(rule, storedRule.copy(id = ""))
        assertTrue(storedRule.id.isNotBlank())
        assertEquals(1, writes - writesBefore) // one atomic transaction, not two
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
            listOf(RegisteredSim(1, "Telstra", "Telstra personal", 700L, needsRulePrompt = true)),
            state.simRegistry,
        )
    }
}
