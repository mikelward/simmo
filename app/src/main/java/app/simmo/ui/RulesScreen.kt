package app.simmo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.simmo.R

/**
 * The home screen: the ordered rule list, first match wins (SPEC "Rules").
 * Editing and drag-to-reorder land next; rules whose SIM is disabled render
 * greyed out and are skipped during evaluation.
 */
@Composable
fun RulesScreen(viewModel: RulesViewModel) {
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    RulesScreenContent(rows)
}

@Composable
internal fun RulesScreenContent(rows: List<RuleRowUi>) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
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
                itemsIndexed(rows) { _, row -> RuleRow(row) }
            }
        }
    }
}

@Composable
private fun RuleRow(row: RuleRowUi) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (row.enabled) 1f else 0.4f),
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
        if (!row.enabled) {
            Text(
                text = stringResource(R.string.rule_sim_disabled),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}
