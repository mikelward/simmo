package app.simmo.ui

import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import app.simmo.SimmoApp
import app.simmo.domain.DataExpectation
import app.simmo.domain.DataRule
import app.simmo.domain.Rule
import app.simmo.domain.RuleAction
import app.simmo.domain.RuleMatcher
import app.simmo.domain.SimRef
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The undo offer is held in the ViewModel and persisted into [SavedStateHandle],
 * so it survives both configuration change and process death (the delete is
 * already committed — a killed process must still be able to undo it). Any other
 * list edit clears it before its index can go stale.
 *
 * These drive the persistence and invalidation paths directly (pre-seeding saved
 * state, and relying on the synchronous clear), avoiding the DataStore-under-
 * runBlocking deadlock that a full seed-then-delete integration test hits.
 */
@RunWith(RobolectricTestRunner::class)
class DeletionUndoViewModelTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val au = Rule(RuleMatcher.Country("AU"), RuleAction.UseSim(SimRef(1, "Telstra", "Telstra AU")), id = "r-au")
    private val dataRule = DataRule(RuleMatcher.Country("FR"), DataExpectation.AlwaysWarn, id = "d-fr")

    private fun savedStateWith(pending: PendingUndo): SavedStateHandle =
        SavedStateHandle().apply { this["pending_undo"] = json.encodeToString(pending) }

    @Test
    fun `a pending calling-rule undo is restored from saved state after process death`() {
        val app = ApplicationProvider.getApplicationContext<SimmoApp>()
        val pending = PendingUndo(3, Undoable.RuleDeletion(au, afterId = null, beforeId = null))

        val vm = RulesViewModel(app, savedStateWith(pending))

        assertEquals(pending, vm.pendingUndo.value)
    }

    @Test
    fun `a pending data-rule undo is restored from saved state too`() {
        val app = ApplicationProvider.getApplicationContext<SimmoApp>()
        val pending = PendingUndo(5, Undoable.DataRuleDeletion(dataRule, afterId = null, beforeId = null))

        val vm = RulesViewModel(app, savedStateWith(pending))

        assertEquals(pending, vm.pendingUndo.value)
    }

    @Test
    fun `dismissing the restored offer clears it`() {
        val app = ApplicationProvider.getApplicationContext<SimmoApp>()
        val pending = PendingUndo(3, Undoable.RuleDeletion(au, afterId = null, beforeId = null))
        val vm = RulesViewModel(app, savedStateWith(pending))

        vm.dismissUndo(pending)

        assertNull(vm.pendingUndo.value)
    }

    @Test
    fun `dismissing a different offer id is a no-op`() {
        val app = ApplicationProvider.getApplicationContext<SimmoApp>()
        val pending = PendingUndo(3, Undoable.RuleDeletion(au, afterId = null, beforeId = null))
        val vm = RulesViewModel(app, savedStateWith(pending))

        // A stale bar from a superseded offer must not clear the current one.
        vm.dismissUndo(PendingUndo(2, Undoable.RuleDeletion(au, afterId = null, beforeId = null)))

        assertEquals(pending, vm.pendingUndo.value)
    }

    @Test
    fun `another list edit clears the pending undo before it can go stale`() {
        val app = ApplicationProvider.getApplicationContext<SimmoApp>()
        val pending = PendingUndo(3, Undoable.RuleDeletion(au, afterId = null, beforeId = null))
        val vm = RulesViewModel(app, savedStateWith(pending))
        assertNotNull(vm.pendingUndo.value)

        // duplicateRule routes through the same edit() path every list mutation
        // uses; the clear is synchronous, before the (async) persisted write.
        vm.duplicateRule("r-au")

        assertNull(vm.pendingUndo.value)
    }
}
