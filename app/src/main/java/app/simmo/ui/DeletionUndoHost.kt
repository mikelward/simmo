package app.simmo.ui

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.simmo.R
import app.simmo.domain.DataRule
import app.simmo.domain.Rule
import kotlinx.serialization.Serializable

/**
 * A deletion the UI can offer to undo. Carries the removed rule plus its two
 * neighbor ids when deleted — [afterId] (the rule above, null if it was at the
 * top) and [beforeId] (the rule below, null if it was at the bottom). Undo
 * re-inserts relative to whichever neighbor still survives, so it lands in the
 * same relative spot even when the list shifted meanwhile — including a rule
 * prepended at the top since (the chooser's "remember"), which a top rule with
 * only [afterId] == null would otherwise jump ahead of. The rule carries its
 * own stable id, so a restore re-inserts it at most once ([RuleBook.withRuleRestored]).
 * Serializable so the offer survives process death.
 */
@Serializable
sealed interface Undoable {
    /** Id of the rule this one sat below when deleted; null if it was at the top. */
    val afterId: String?

    /** Id of the rule this one sat above when deleted; null if it was at the bottom. */
    val beforeId: String?

    @Serializable
    data class RuleDeletion(
        val rule: Rule,
        override val afterId: String?,
        override val beforeId: String?,
    ) : Undoable

    @Serializable
    data class DataRuleDeletion(
        val rule: DataRule,
        override val afterId: String?,
        override val beforeId: String?,
    ) : Undoable
}

/**
 * The currently-offered undo, held in the ViewModel (not in the transient
 * snackbar) so a rotation mid-bar doesn't drop it and permanently commit the
 * delete. [id] is a monotonic token: a newer deletion supersedes the older bar,
 * so only the most recent delete is undoable — one bar at a time, and no two
 * pending undos whose reinsert indices could restore out of order.
 */
@Serializable
data class PendingUndo(val id: Long, val undoable: Undoable)

/**
 * The transient "Rule deleted — Undo" bar (SPEC "Calling rules"). Deleting a
 * rule takes effect at once; this bar is the safety net that replaced the
 * earlier confirm dialog. Hosted once above the rules list so a delete made in
 * the rule editor still offers Undo after the editor closes back to the list.
 * Driven by [pending] from the ViewModel: a new value supersedes the visible
 * bar, and the bar reappears after a configuration change because the state
 * outlives the composition.
 */
@Composable
internal fun DeletionUndoHost(
    pending: PendingUndo?,
    onUndo: (PendingUndo) -> Unit,
    onDismiss: (PendingUndo) -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val ruleDeleted = stringResource(R.string.rule_deleted)
    val undo = stringResource(R.string.action_undo)
    // Keyed on the id so a superseding deletion cancels the old bar (via
    // coroutine cancellation, which doesn't report Dismissed) and shows the new.
    LaunchedEffect(pending?.id) {
        val current = pending ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = ruleDeleted,
            actionLabel = undo,
            duration = SnackbarDuration.Short,
        )
        when (result) {
            SnackbarResult.ActionPerformed -> onUndo(current)
            SnackbarResult.Dismissed -> onDismiss(current)
        }
    }
    SnackbarHost(hostState = snackbarHostState, modifier = modifier)
}
