package app.simmo.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.simmo.R
import app.simmo.domain.SimRef

/**
 * The home screen: the ordered rule list, first match wins (SPEC "Rules").
 * Tapping a row edits it; dragging a row's handle reorders it; the add button
 * prepends a new rule. Rules whose SIM is disabled render greyed out and are
 * skipped during evaluation.
 */
@Composable
fun RulesScreen(
    viewModel: RulesViewModel,
    onAddRule: () -> Unit,
    onEditRule: (Int) -> Unit,
    onOpenGroups: () -> Unit = {},
) {
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val newSimPrompts by viewModel.newSimPrompts.collectAsStateWithLifecycle()
    RulesScreenContent(
        rows = rows,
        newSimPrompts = newSimPrompts,
        onAddRule = onAddRule,
        onEditRule = onEditRule,
        onDuplicateRule = viewModel::duplicateRule,
        onDeleteRule = viewModel::removeRule,
        onSetRuleEnabled = viewModel::setRuleEnabled,
        onMoveRule = viewModel::moveRule,
        onAddRuleForSim = viewModel::openNewRuleForSim,
        onDismissNewSimPrompt = viewModel::dismissNewSimPrompt,
        onOpenSimRegistry = viewModel::openSimRegistry,
        onOpenGroups = onOpenGroups,
    )
}

@Composable
internal fun RulesScreenContent(
    rows: List<RuleRowUi>,
    newSimPrompts: List<NewSimPromptUi> = emptyList(),
    onAddRule: () -> Unit = {},
    onEditRule: (Int) -> Unit = {},
    onDuplicateRule: (Int) -> Unit = {},
    onDeleteRule: (Int) -> Unit = {},
    onSetRuleEnabled: (Int, Boolean) -> Unit = { _, _ -> },
    onMoveRule: (Int, Int) -> Unit = { _, _ -> },
    onAddRuleForSim: (NewSimPromptUi) -> Unit = {},
    onDismissNewSimPrompt: (SimRef) -> Unit = {},
    onOpenSimRegistry: () -> Unit = {},
    onOpenGroups: () -> Unit = {},
) {
    val listState = rememberLazyListState()
    val currentRows by rememberUpdatedState(rows)
    // The order shown while a drag is live (and until the committed order
    // flows back), so a drag never waits on a disk write per crossing and the
    // drop doesn't visibly snap back while the write round-trips.
    var workingRows by remember { mutableStateOf<List<RuleRowUi>?>(null) }
    val dragState = remember(listState) {
        DragReorderState(
            listState = listState,
            onDragMove = { from, to -> workingRows = (workingRows ?: currentRows).movedItem(from, to) },
            onDrop = { from, to -> onMoveRule(from, to) },
            onCancel = { workingRows = null },
        )
    }
    // Adopt every fresh upstream order once no drag is live: the committed
    // reorder catching up, and any unrelated change (SIM state, edits).
    LaunchedEffect(rows) {
        if (dragState.draggingIndex == null) workingRows = null
    }
    val displayRows = workingRows ?: rows

    Surface(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize()) {
            // The activity is edge-to-edge; keep the list clear of the status
            // bar and display cutout.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(16.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.rules_title),
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onOpenGroups) {
                        Icon(
                            imageVector = Icons.Filled.List,
                            contentDescription = stringResource(R.string.groups_open),
                        )
                    }
                    IconButton(onClick = onOpenSimRegistry) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.sim_registry_open),
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.rules_explainer),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                // Outside the LazyColumn so rule indices stay list-relative
                // for editing and dragging.
                newSimPrompts.forEach { prompt ->
                    NewSimPromptCard(
                        prompt = prompt,
                        onAddRule = { onAddRuleForSim(prompt) },
                        onDismiss = { onDismissNewSimPrompt(prompt.ref) },
                    )
                }
                LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(displayRows) { index, row ->
                        val dragging = dragState.draggingIndex == index
                        RuleRow(
                            row = row,
                            dragState = dragState,
                            index = index,
                            onClick = { onEditRule(index) },
                            onDuplicate = { onDuplicateRule(index) },
                            onDelete = { onDeleteRule(index) },
                            onSetEnabled = { enabled -> onSetRuleEnabled(index, enabled) },
                            modifier = Modifier
                                // Draw the dragged row above its neighbors. The
                                // translation is read inside the layer block so
                                // each drag frame costs a redraw, not a recompose.
                                .zIndex(if (dragging) 1f else 0f)
                                .graphicsLayer {
                                    translationY = if (dragging) dragState.draggingOffset else 0f
                                },
                        )
                    }
                }
            }
            FloatingActionButton(
                onClick = onAddRule,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .safeDrawingPadding()
                    .padding(16.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.rules_add),
                )
            }
        }
    }
}

/**
 * The "add rules for this new SIM?" nudge (SPEC "On SIM change"). Answering
 * either way retires the prompt; "Add rule" opens the editor preset to the
 * SIM, and the saved rule is suggested above any paused rules.
 */
@Composable
private fun NewSimPromptCard(
    prompt: NewSimPromptUi,
    onAddRule: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.new_sim_prompt_title, prompt.label),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.new_sim_prompt_body),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.new_sim_prompt_dismiss)) }
                Button(onClick = onAddRule) { Text(stringResource(R.string.new_sim_prompt_add)) }
            }
        }
    }
}

@Composable
private fun RuleRow(
    row: RuleRowUi,
    dragState: DragReorderState,
    index: Int,
    onClick: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onSetEnabled: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Rows are identified by position (rules have no stable IDs), so the
    // gesture node must re-read its row's *current* display index at drag
    // start rather than baking one into the pointerInput key — restarting
    // the pointer coroutine mid-drag would cancel the drag.
    val currentIndex by rememberUpdatedState(index)
    var menuOpen by remember { mutableStateOf(false) }
    // Greyed when the rule can't act — either the user turned it off or an
    // automatic pause (disabled SIM, ambiguous re-bind) applies. Applied to the
    // handle and content, not the whole row, so the actions menu button stays
    // legible enough to re-enable a disabled rule.
    val contentAlpha = if (!row.enabled || row.pause != null) 0.4f else 1f
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Sized like the editor's radio buttons for a 48dp touch target.
        Box(
            modifier = Modifier
                .size(48.dp)
                .alpha(contentAlpha)
                .pointerInputDragHandle(dragState) { currentIndex },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Menu,
                contentDescription = stringResource(R.string.rules_drag_handle),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                // Tap edits; long-press opens the same menu as the ⋮ button.
                // Reorder lives on the handle, so long-press here never contends.
                .combinedClickable(onClick = onClick, onLongClick = { menuOpen = true })
                .alpha(contentAlpha),
        ) {
            Text(
                text = row.matcherCountryLabel ?: stringResource(R.string.rule_matcher_any),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = when (val a = row.action) {
                    is ActionUi.UseSim -> stringResource(R.string.rule_action_use_sim, a.simName)
                    ActionUi.MatchingCountrySim -> stringResource(R.string.rule_action_matching_sim)
                    is ActionUi.HandOffApp -> stringResource(R.string.rule_action_hand_off, a.target)
                    ActionUi.Ask -> stringResource(R.string.rule_action_ask)
                    ActionUi.SystemDefault -> stringResource(R.string.rule_action_system_default)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            // The user-off state reads first: it overrides why the rule can't
            // act (an off rule wouldn't act even with its SIM enabled).
            when {
                !row.enabled -> Text(
                    text = stringResource(R.string.rule_disabled),
                    style = MaterialTheme.typography.labelMedium,
                )
                row.pause == RulePause.SIM_DISABLED -> Text(
                    text = stringResource(R.string.rule_sim_disabled),
                    style = MaterialTheme.typography.labelMedium,
                )
                row.pause == RulePause.SIM_AMBIGUOUS -> Text(
                    text = stringResource(R.string.rule_sim_ambiguous),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        // The row's actions. The menu is not dimmed with the rule — it must
        // stay legible to re-enable a disabled rule.
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.rule_menu_more),
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.rule_menu_edit)) },
                    onClick = { menuOpen = false; onClick() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.rule_menu_duplicate)) },
                    onClick = { menuOpen = false; onDuplicate() },
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                if (row.enabled) R.string.rule_menu_disable else R.string.rule_menu_enable,
                            ),
                        )
                    },
                    onClick = { menuOpen = false; onSetEnabled(!row.enabled) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.rule_menu_delete)) },
                    onClick = { menuOpen = false; onDelete() },
                )
            }
        }
    }
}

/** The drag gesture for a row's handle, reporting the row's live display index. */
private fun Modifier.pointerInputDragHandle(
    dragState: DragReorderState,
    indexProvider: () -> Int,
): Modifier = pointerInput(dragState) {
    detectDragGestures(
        onDragStart = { dragState.startDrag(indexProvider()) },
        onDrag = { change, amount ->
            change.consume()
            dragState.drag(amount.y)
        },
        onDragEnd = { dragState.endDrag() },
        onDragCancel = { dragState.cancelDrag() },
    )
}
