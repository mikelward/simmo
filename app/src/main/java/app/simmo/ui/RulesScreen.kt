package app.simmo.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.simmo.R

/**
 * The home screen: the ordered rule list, first match wins (SPEC "Rules").
 * Tapping a row edits it; the add button prepends a new rule. Rules whose SIM
 * is disabled render greyed out and are skipped during evaluation.
 */
@Composable
fun RulesScreen(
    viewModel: RulesViewModel,
    onAddRule: () -> Unit,
    onEditRule: (Int) -> Unit,
) {
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    RulesScreenContent(rows, onAddRule, onEditRule)
}

@Composable
internal fun RulesScreenContent(
    rows: List<RuleRowUi>,
    onAddRule: () -> Unit = {},
    onEditRule: (Int) -> Unit = {},
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
                Text(
                    text = stringResource(R.string.rules_title),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                Text(
                    text = stringResource(R.string.rules_explainer),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(rows) { index, row -> RuleRow(row, onClick = { onEditRule(index) }) }
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

@Composable
private fun RuleRow(row: RuleRowUi, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .alpha(if (row.pause == null) 1f else 0.4f),
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
        when (row.pause) {
            null -> Unit
            RulePause.SIM_DISABLED -> Text(
                text = stringResource(R.string.rule_sim_disabled),
                style = MaterialTheme.typography.labelMedium,
            )
            RulePause.SIM_AMBIGUOUS -> Text(
                text = stringResource(R.string.rule_sim_ambiguous),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}
