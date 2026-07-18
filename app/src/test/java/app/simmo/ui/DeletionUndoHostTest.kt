package app.simmo.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.simmo.domain.DataExpectation
import app.simmo.domain.DataRule
import app.simmo.domain.Rule as DomainRule
import app.simmo.domain.RuleAction
import app.simmo.domain.RuleMatcher
import app.simmo.domain.SimRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The undo bar that replaced the delete-confirm dialog: it renders the pending
 * undo the ViewModel holds, hands back that exact payload on Undo, and reports a
 * dismissal otherwise. Driven through the host state so the test doesn't race
 * the snackbar's timed dismissal.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DeletionUndoHostTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val rule =
        DomainRule(RuleMatcher.Country("AU"), RuleAction.UseSim(SimRef(1, "Telstra", "Telstra AU")), id = "r-au")

    @Test
    fun aRuleDeletionShowsItsBarAndUndoesThatExactPayload() {
        val pending = PendingUndo(1, Undoable.RuleDeletion(rule, afterId = null, beforeId = null))
        val hostState = SnackbarHostState()
        var undone: PendingUndo? = null
        var dismissed: PendingUndo? = null
        composeRule.setContent {
            MaterialTheme {
                DeletionUndoHost(
                    pending = pending,
                    onUndo = { undone = it },
                    onDismiss = { dismissed = it },
                    snackbarHostState = hostState,
                )
            }
        }

        composeRule.waitUntil { hostState.currentSnackbarData != null }
        composeRule.runOnIdle {
            val data = hostState.currentSnackbarData!!
            assertEquals("Rule deleted", data.visuals.message)
            assertEquals("Undo", data.visuals.actionLabel)
            data.performAction() // the same result path as tapping Undo
        }

        composeRule.runOnIdle {
            assertSame(pending, undone)
            assertNull(dismissed)
        }
    }

    @Test
    fun aDataRuleDeletionShowsTheSameBar() {
        val dataRule = DataRule(RuleMatcher.Country("FR"), DataExpectation.AlwaysWarn, id = "d-fr")
        val pending = PendingUndo(1, Undoable.DataRuleDeletion(dataRule, afterId = null, beforeId = null))
        val hostState = SnackbarHostState()
        composeRule.setContent {
            MaterialTheme {
                DeletionUndoHost(pending = pending, onUndo = {}, onDismiss = {}, snackbarHostState = hostState)
            }
        }
        composeRule.waitUntil { hostState.currentSnackbarData != null }
        composeRule.runOnIdle { assertEquals("Rule deleted", hostState.currentSnackbarData!!.visuals.message) }
    }

    @Test
    fun dismissingTheBarReportsDismissalNotUndo() {
        val pending = PendingUndo(1, Undoable.RuleDeletion(rule, afterId = null, beforeId = null))
        val hostState = SnackbarHostState()
        var undone: PendingUndo? = null
        var dismissed: PendingUndo? = null
        composeRule.setContent {
            MaterialTheme {
                DeletionUndoHost(
                    pending = pending,
                    onUndo = { undone = it },
                    onDismiss = { dismissed = it },
                    snackbarHostState = hostState,
                )
            }
        }
        composeRule.waitUntil { hostState.currentSnackbarData != null }
        composeRule.runOnIdle { hostState.currentSnackbarData!!.dismiss() }

        composeRule.runOnIdle {
            assertSame(pending, dismissed)
            assertNull(undone)
        }
    }
}
