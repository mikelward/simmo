package app.simmo.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.simmo.R
import app.simmo.domain.SimRef

/**
 * The home screen: two ordered rule lists side by side — Calling and Data
 * (SPEC "Product behavior" terminology) — each first match wins. Tapping a
 * row edits it; dragging a row's handle reorders it; the add button prepends
 * a new rule to the visible list. Rules that can't act render greyed out and
 * are skipped during evaluation.
 */
@Composable
fun RulesScreen(
    viewModel: RulesViewModel,
    onAddRule: () -> Unit,
    onEditRule: (String) -> Unit,
    onBack: () -> Unit = {},
) {
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val dataRows by viewModel.dataRows.collectAsStateWithLifecycle()
    val triage by viewModel.triage.collectAsStateWithLifecycle()
    val newSimPrompts by viewModel.newSimPrompts.collectAsStateWithLifecycle()
    val tab by viewModel.rulesTab.collectAsStateWithLifecycle()
    val pendingRemovals by viewModel.hasPendingRemovals.collectAsStateWithLifecycle()
    val context = LocalContext.current
    RulesScreenContent(
        rows = rows,
        dataRows = dataRows,
        triage = triage,
        tab = tab,
        onSelectTab = viewModel::selectRulesTab,
        newSimPrompts = newSimPrompts,
        pendingRemovals = pendingRemovals,
        onApply = viewModel::purgePendingRemovals,
        onBack = onBack,
        onAddRule = onAddRule,
        onEditRule = onEditRule,
        onDuplicateRule = viewModel::duplicateRule,
        onDeleteRule = viewModel::removeRule,
        onUndoDeleteRule = viewModel::undoRuleRemoval,
        onSetRuleEnabled = viewModel::setRuleEnabled,
        onMoveRule = viewModel::moveRule,
        onAddDataRule = viewModel::openNewDataRule,
        onEditDataRule = viewModel::openEditDataRule,
        onDuplicateDataRule = viewModel::duplicateDataRule,
        onDeleteDataRule = viewModel::removeDataRule,
        onUndoDeleteDataRule = viewModel::undoDataRuleRemoval,
        onSetDataRuleEnabled = viewModel::setDataRuleEnabled,
        onMoveDataRule = viewModel::moveDataRule,
        onTriageUseInCountry = { country, sim -> viewModel.confirmDataRoamingOk(country, sim) },
        onTriageWiden = { country, sim, groupId -> viewModel.confirmDataRoamingOk(country, sim, groupId) },
        onTriageIgnoreForTrip = viewModel::dismissDataArrival,
        onTriageOpenSimSettings = { context.openSimSettings() },
        onAddRuleForSim = viewModel::openNewRuleForSim,
        onDismissNewSimPrompt = viewModel::dismissNewSimPrompt,
    )
}

@Composable
internal fun RulesScreenContent(
    rows: List<RuleRowUi>,
    dataRows: List<DataRuleRowUi> = emptyList(),
    triage: DataTriageUi? = null,
    tab: RulesTab = RulesTab.CALLING,
    onSelectTab: (RulesTab) -> Unit = {},
    newSimPrompts: List<NewSimPromptUi> = emptyList(),
    pendingRemovals: Boolean = false,
    onApply: () -> Unit = {},
    onBack: () -> Unit = {},
    onAddRule: () -> Unit = {},
    onEditRule: (String) -> Unit = {},
    onDuplicateRule: (String) -> Unit = {},
    onDeleteRule: (String) -> Unit = {},
    onUndoDeleteRule: (String) -> Unit = {},
    onSetRuleEnabled: (String, Boolean) -> Unit = { _, _ -> },
    onMoveRule: (Int, Int) -> Unit = { _, _ -> },
    onAddDataRule: () -> Unit = {},
    onEditDataRule: (String) -> Unit = {},
    onDuplicateDataRule: (String) -> Unit = {},
    onDeleteDataRule: (String) -> Unit = {},
    onUndoDeleteDataRule: (String) -> Unit = {},
    onSetDataRuleEnabled: (String, Boolean) -> Unit = { _, _ -> },
    onMoveDataRule: (Int, Int) -> Unit = { _, _ -> },
    onTriageUseInCountry: (country: String, sim: SimRef) -> Unit = { _, _ -> },
    onTriageWiden: (country: String, sim: SimRef, groupId: String) -> Unit = { _, _, _ -> },
    onTriageIgnoreForTrip: (arrivalKey: String) -> Unit = {},
    onTriageOpenSimSettings: () -> Unit = {},
    onAddRuleForSim: (NewSimPromptUi) -> Unit = {},
    onDismissNewSimPrompt: (SimRef) -> Unit = {},
) {
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
                        // A sub-screen of the SIMs home now; the Calling/Data
                        // tabs below name the two lists.
                        text = stringResource(R.string.rules_title),
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.weight(1f),
                    )
                    // While any deletion is pending — a rule, data rule, or group
                    // — the button is Apply, which flushes them all in place. With
                    // nothing pending it's Back, returning to the SIMs home — the
                    // same shape as the Country groups sub-screen (leaving the app
                    // is what purges, so Back needs no apply).
                    if (pendingRemovals) {
                        TextButton(onClick = onApply) { Text(stringResource(R.string.action_apply)) }
                    } else {
                        TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
                    }
                }
                SecondaryTabRow(
                    selectedTabIndex = tab.ordinal,
                    modifier = Modifier.padding(bottom = 16.dp),
                ) {
                    Tab(
                        selected = tab == RulesTab.CALLING,
                        onClick = { onSelectTab(RulesTab.CALLING) },
                        text = { Text(stringResource(R.string.rules_tab_calling)) },
                    )
                    Tab(
                        selected = tab == RulesTab.DATA,
                        onClick = { onSelectTab(RulesTab.DATA) },
                        text = { Text(stringResource(R.string.rules_tab_data)) },
                    )
                }
                // The triage card leads the Data tab while a live situation
                // exists (SPEC "Data rules" → Triage) — above the explainer, as
                // the thing to act on before reading the list.
                if (tab == RulesTab.DATA) {
                    triage?.let { situation ->
                        DataTriageCard(
                            triage = situation,
                            onUseInCountry = onTriageUseInCountry,
                            onWiden = onTriageWiden,
                            onIgnoreForTrip = onTriageIgnoreForTrip,
                            onOpenSimSettings = onTriageOpenSimSettings,
                        )
                    }
                }
                Text(
                    text = stringResource(
                        if (tab == RulesTab.CALLING) R.string.rules_explainer else R.string.data_rules_explainer,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                if (tab == RulesTab.CALLING) {
                    // Outside the LazyColumn so rule indices stay list-relative
                    // for editing and dragging.
                    newSimPrompts.forEach { prompt ->
                        NewSimPromptCard(
                            prompt = prompt,
                            onAddRule = { onAddRuleForSim(prompt) },
                            onDismiss = { onDismissNewSimPrompt(prompt.ref) },
                        )
                    }
                    ReorderableRuleList(rows = rows, onMove = onMoveRule) { index, row, dragState, modifier ->
                        RuleListRow(
                            title = row.matcherCountryLabel ?: stringResource(R.string.rule_matcher_any),
                            subtitle = callingActionLabel(row.action),
                            statusLabel = pauseLabel(row.enabled, row.pause),
                            enabled = row.enabled,
                            dragState = dragState,
                            index = index,
                            onClick = { onEditRule(row.id) },
                            onDuplicate = { onDuplicateRule(row.id) },
                            onDelete = { onDeleteRule(row.id) },
                            onSetEnabled = { enabled -> onSetRuleEnabled(row.id, enabled) },
                            pendingRemoval = row.pendingRemoval,
                            onUndoDelete = { onUndoDeleteRule(row.id) },
                            modifier = modifier,
                        )
                    }
                } else {
                    ReorderableRuleList(rows = dataRows, onMove = onMoveDataRule) { index, row, dragState, modifier ->
                        RuleListRow(
                            title = row.matcherCountryLabel ?: stringResource(R.string.data_rule_matcher_any),
                            subtitle = dataExpectationLabel(row.expectation),
                            statusLabel = pauseLabel(row.enabled, row.pause),
                            enabled = row.enabled,
                            dragState = dragState,
                            index = index,
                            onClick = { onEditDataRule(row.id) },
                            onDuplicate = { onDuplicateDataRule(row.id) },
                            onDelete = { onDeleteDataRule(row.id) },
                            onSetEnabled = { enabled -> onSetDataRuleEnabled(row.id, enabled) },
                            pendingRemoval = row.pendingRemoval,
                            onUndoDelete = { onUndoDeleteDataRule(row.id) },
                            modifier = modifier,
                        )
                    }
                }
            }
            FloatingActionButton(
                onClick = if (tab == RulesTab.CALLING) onAddRule else onAddDataRule,
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

@Composable
private fun callingActionLabel(action: ActionUi): String = when (action) {
    is ActionUi.UseSim -> stringResource(R.string.rule_action_use_sim, action.simName)
    ActionUi.MatchingCountrySim -> stringResource(R.string.rule_action_matching_sim)
    is ActionUi.HandOffApp -> stringResource(R.string.rule_action_hand_off, action.target)
    ActionUi.Ask -> stringResource(R.string.rule_action_ask)
    ActionUi.SystemDefault -> stringResource(R.string.rule_action_system_default)
}

@Composable
internal fun dataExpectationLabel(expectation: DataExpectationUi): String = when (expectation) {
    is DataExpectationUi.UseSimForData ->
        stringResource(R.string.data_rule_use_sim, expectation.simName)
    DataExpectationUi.UseLocalSimForData -> stringResource(R.string.data_rule_use_local)
    DataExpectationUi.RoamingOkAnySim -> stringResource(R.string.data_rule_roaming_ok_any)
    DataExpectationUi.RoamingOkHomedInMatched ->
        stringResource(R.string.data_rule_roaming_ok_homed)
    is DataExpectationUi.RoamingOkSims ->
        stringResource(R.string.data_rule_roaming_ok_sims, expectation.simNames)
    DataExpectationUi.AlwaysWarn -> stringResource(R.string.data_rule_warn)
}

/**
 * The triage card (SPEC "Data rules" → Triage): leads the Data tab while a
 * live data situation exists, with the resolutions one tap away. For a roaming
 * situation, **Use in ⟨country⟩** records a Roaming OK rule for here — scoped
 * to the SIM now carrying data — and each **Use in ⟨group⟩** records it for a
 * whole group that contains the country instead; **Change SIMs** jumps to
 * system settings to switch the data SIM (or enable a local one). The no-data
 * and wrong-SIM situations have no rule to make, so they offer only Change
 * SIM. The buttons wrap ([FlowRow]) so a country in a few groups doesn't grow
 * the card past the screen; the pathological many-custom-groups case is a
 * known bound (TODO), the rule list below scrolls regardless.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DataTriageCard(
    triage: DataTriageUi,
    onUseInCountry: (country: String, sim: SimRef) -> Unit,
    onWiden: (country: String, sim: SimRef, groupId: String) -> Unit,
    onIgnoreForTrip: (arrivalKey: String) -> Unit,
    onOpenSimSettings: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(triageTitle(triage), style = MaterialTheme.typography.titleMedium)
            Text(
                text = triageBody(triage),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // One uniform, bordered style for every choice. A roaming
                // situation can be accepted as a rule (for the country, or
                // widened to a group that contains it); every situation can be
                // ignored for the trip without a rule, or resolved by picking a
                // different SIM in system settings.
                if (triage.kind == DataTriageKind.ROAMING) {
                    // Pass the identity this card rendered, so the write acts on
                    // exactly the situation shown, never a later replacement.
                    OutlinedButton(onClick = { onUseInCountry(triage.country, triage.dataSimRef) }) {
                        Text(stringResource(R.string.triage_use, triage.countryLabel))
                    }
                    triage.widenGroups.forEach { group ->
                        OutlinedButton(onClick = { onWiden(triage.country, triage.dataSimRef, group.id) }) {
                            Text(stringResource(R.string.triage_use, group.label))
                        }
                    }
                }
                // The rule-free per-trip dismiss, on every situation — the only
                // opt-out the no-data case has (no expectation to record).
                OutlinedButton(onClick = { onIgnoreForTrip(triage.arrivalKey) }) {
                    Text(stringResource(R.string.triage_ignore_trip))
                }
                OutlinedButton(onClick = onOpenSimSettings) {
                    Text(stringResource(R.string.change_sims))
                }
            }
        }
    }
}

@Composable
private fun triageTitle(triage: DataTriageUi): String = when (triage.kind) {
    DataTriageKind.ROAMING -> stringResource(R.string.triage_roaming_title, triage.countryLabel)
    DataTriageKind.NO_DATA -> stringResource(R.string.triage_no_data_title, triage.countryLabel)
    DataTriageKind.WRONG_SIM -> stringResource(R.string.triage_wrong_sim_title, triage.countryLabel)
}

@Composable
private fun triageBody(triage: DataTriageUi): String = when (triage.kind) {
    // Name the local SIM the user should prefer, when there is one (SPEC:
    // "which active SIM is local").
    DataTriageKind.ROAMING -> triage.otherSimName
        ?.let { stringResource(R.string.triage_roaming_body_local, triage.dataSimName, it) }
        ?: stringResource(R.string.triage_roaming_body, triage.dataSimName)
    DataTriageKind.NO_DATA -> triage.otherSimName
        ?.let { stringResource(R.string.triage_no_data_body_switch, triage.dataSimName, it) }
        ?: stringResource(R.string.triage_no_data_body, triage.dataSimName)
    DataTriageKind.WRONG_SIM ->
        stringResource(R.string.triage_wrong_sim_body, triage.otherSimName.orEmpty(), triage.dataSimName)
}

/**
 * The greyed row's status line. The user-off state reads first: it overrides
 * why the rule can't act (an off rule wouldn't act even with its SIM enabled).
 */
@Composable
private fun pauseLabel(enabled: Boolean, pause: RulePause?): String? = when {
    !enabled -> stringResource(R.string.rule_disabled)
    pause == RulePause.SIM_DISABLED -> stringResource(R.string.rule_sim_disabled)
    pause == RulePause.SIM_AMBIGUOUS -> stringResource(R.string.rule_sim_ambiguous)
    pause == RulePause.ACCOUNT_UNAVAILABLE -> stringResource(R.string.rule_account_unavailable)
    else -> null
}

/**
 * The drag-to-reorder list host shared by both tabs: shows [rows] reordered
 * live while a drag is in flight (and until the committed order flows back),
 * so a drag never waits on a disk write per crossing and the drop doesn't
 * visibly snap back while the write round-trips.
 */
@Composable
private fun <T> ReorderableRuleList(
    rows: List<T>,
    onMove: (Int, Int) -> Unit,
    rowContent: @Composable (index: Int, item: T, dragState: DragReorderState, modifier: Modifier) -> Unit,
) {
    val listState = rememberLazyListState()
    val currentRows by rememberUpdatedState(rows)
    var workingRows by remember { mutableStateOf<List<T>?>(null) }
    val dragState = remember(listState) {
        DragReorderState(
            listState = listState,
            onDragMove = { from, to -> workingRows = (workingRows ?: currentRows).movedItem(from, to) },
            onDrop = { from, to -> onMove(from, to) },
            onCancel = { workingRows = null },
        )
    }
    // Adopt every fresh upstream order once no drag is live: the committed
    // reorder catching up, and any unrelated change (SIM state, edits).
    LaunchedEffect(rows) {
        if (dragState.draggingIndex == null) workingRows = null
    }
    val displayRows = workingRows ?: rows
    LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        itemsIndexed(displayRows) { index, row ->
            val dragging = dragState.draggingIndex == index
            rowContent(
                index,
                row,
                dragState,
                Modifier
                    // Draw the dragged row above its neighbors. The
                    // translation is read inside the layer block so each drag
                    // frame costs a redraw, not a recompose.
                    .zIndex(if (dragging) 1f else 0f)
                    .graphicsLayer {
                        translationY = if (dragging) dragState.draggingOffset else 0f
                    },
            )
        }
    }
}

/**
 * The "add rules for this new SIM?" nudge (SPEC "On SIM change"). Answering
 * either way retires the prompt; "Add rule" opens the editor preset to the
 * SIM, and the saved rule is suggested above any paused rules.
 */
@Composable
internal fun NewSimPromptCard(
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

/** One row of either rule list; the caller resolves all text beforehand. */
@Composable
private fun RuleListRow(
    title: String,
    subtitle: String,
    statusLabel: String?,
    enabled: Boolean,
    dragState: DragReorderState,
    index: Int,
    onClick: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onSetEnabled: (Boolean) -> Unit,
    pendingRemoval: Boolean,
    onUndoDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Rows are identified by position (rules have no stable IDs), so the
    // gesture node must re-read its row's *current* display index at drag
    // start rather than baking one into the pointerInput key — restarting
    // the pointer coroutine mid-drag would cancel the drag.
    val currentIndex by rememberUpdatedState(index)
    var menuOpen by remember { mutableStateOf(false) }
    // Greyed when the rule can't act — statusLabel carries why. A soft-deleted
    // rule is dimmed and struck-through, and its content is inert (no tap-to-
    // edit, no drag, no menu); only its Undo action is offered.
    val contentAlpha = if (pendingRemoval || statusLabel != null) 0.4f else 1f
    val strike = if (pendingRemoval) TextDecoration.LineThrough else null
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Sized like the editor's radio buttons for a 48dp touch target.
        Box(
            modifier = Modifier
                .size(48.dp)
                .alpha(contentAlpha)
                .then(if (pendingRemoval) Modifier else Modifier.pointerInputDragHandle(dragState) { currentIndex }),
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
                // A soft-deleted row is inert — only Undo acts on it.
                .then(
                    if (pendingRemoval) Modifier
                    else Modifier.combinedClickable(onClick = onClick, onLongClick = { menuOpen = true }),
                )
                .alpha(contentAlpha),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, textDecoration = strike)
            Text(text = subtitle, style = MaterialTheme.typography.bodyMedium, textDecoration = strike)
            statusLabel?.takeIf { !pendingRemoval }?.let {
                Text(text = it, style = MaterialTheme.typography.labelMedium)
            }
        }
        if (pendingRemoval) {
            // The undo affordance replaces the actions menu for a soft-deleted
            // row; the delete finalizes when the screen is left (purge).
            TextButton(onClick = onUndoDelete) { Text(stringResource(R.string.action_undo)) }
        } else {
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
                                    if (enabled) R.string.rule_menu_disable else R.string.rule_menu_enable,
                                ),
                            )
                        },
                        onClick = { menuOpen = false; onSetEnabled(!enabled) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.rule_menu_delete)) },
                        onClick = { menuOpen = false; onDelete() },
                    )
                }
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
