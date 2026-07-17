package app.simmo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.simmo.R
import app.simmo.domain.Rule
import app.simmo.domain.RuleAction
import app.simmo.domain.RuleMatcher
import app.simmo.domain.SimRef

/** What the editor is producing — a new rule or an edit to an existing one. */
sealed interface EditorTarget {
    data object New : EditorTarget
    data class Existing(val index: Int, val rule: Rule) : EditorTarget
}

/** The editor's current choices; drives which action needs a SIM selection. */
data class EditorDraft(
    val matcher: RuleMatcher,
    val action: RuleAction,
)

@Composable
fun RuleEditorScreen(
    viewModel: RulesViewModel,
    target: EditorTarget,
    onDone: () -> Unit,
) {
    val simOptions by viewModel.simOptions.collectAsStateWithLifecycle()
    val countryOptions by viewModel.countryOptions.collectAsStateWithLifecycle()
    RuleEditorContent(
        target = target,
        simOptions = simOptions,
        countryOptions = countryOptions,
        onSave = { draft ->
            val rule = Rule(draft.matcher, draft.action)
            when (target) {
                EditorTarget.New -> viewModel.addRule(rule)
                is EditorTarget.Existing -> viewModel.replaceRule(target.index, rule)
            }
            onDone()
        },
        onDelete = (target as? EditorTarget.Existing)?.let { existing ->
            { viewModel.removeRule(existing.index); onDone() }
        },
        onCancel = onDone,
    )
}

@Composable
internal fun RuleEditorContent(
    target: EditorTarget,
    simOptions: List<SimOptionUi>,
    countryOptions: List<CountryOptionUi>,
    onSave: (EditorDraft) -> Unit,
    onDelete: (() -> Unit)?,
    onCancel: () -> Unit,
) {
    val initial = (target as? EditorTarget.Existing)?.rule
    var matchesAny by remember { mutableStateOf(initial?.matcher is RuleMatcher.AnyDestination || initial == null) }
    var region by remember {
        mutableStateOf((initial?.matcher as? RuleMatcher.Country)?.regionCode)
    }
    var actionChoice by remember { mutableStateOf(ActionChoice.of(initial?.action)) }
    var simRef by remember {
        mutableStateOf((initial?.action as? RuleAction.UseSim)?.sim ?: simOptions.firstOrNull()?.ref)
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(
                    if (target is EditorTarget.Existing) R.string.editor_title_edit else R.string.editor_title_new,
                ),
                style = MaterialTheme.typography.headlineMedium,
            )

            Text(stringResource(R.string.editor_when_label), style = MaterialTheme.typography.titleMedium)
            ChoiceRow(
                selected = matchesAny,
                text = stringResource(R.string.rule_matcher_any),
                onSelect = { matchesAny = true },
            )
            ChoiceRow(
                selected = !matchesAny,
                text = stringResource(R.string.editor_when_country),
                onSelect = { matchesAny = false },
            )
            if (!matchesAny) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(countryOptions) { option ->
                        ChoiceRow(
                            selected = region.equals(option.regionCode, ignoreCase = true),
                            text = option.label,
                            onSelect = { region = option.regionCode },
                        )
                    }
                }
            }

            Text(stringResource(R.string.editor_do_label), style = MaterialTheme.typography.titleMedium)
            ChoiceRow(
                selected = actionChoice == ActionChoice.USE_SIM,
                text = stringResource(R.string.editor_action_use_sim),
                onSelect = { actionChoice = ActionChoice.USE_SIM },
            )
            if (actionChoice == ActionChoice.USE_SIM) {
                for (option in simOptions) {
                    ChoiceRow(
                        selected = simRef == option.ref,
                        text = if (option.active) option.label
                        else stringResource(R.string.editor_sim_disabled_suffix, option.label),
                        onSelect = { simRef = option.ref },
                    )
                }
            }
            ChoiceRow(
                selected = actionChoice == ActionChoice.MATCHING_SIM,
                text = stringResource(R.string.rule_action_matching_sim),
                onSelect = { actionChoice = ActionChoice.MATCHING_SIM },
            )
            ChoiceRow(
                selected = actionChoice == ActionChoice.ASK,
                text = stringResource(R.string.rule_action_ask),
                onSelect = { actionChoice = ActionChoice.ASK },
            )
            ChoiceRow(
                selected = actionChoice == ActionChoice.SYSTEM_DEFAULT,
                text = stringResource(R.string.rule_action_system_default),
                onSelect = { actionChoice = ActionChoice.SYSTEM_DEFAULT },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (onDelete != null) {
                    OutlinedButton(onClick = onDelete) { Text(stringResource(R.string.editor_delete)) }
                }
                OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.editor_cancel)) }
                Button(
                    onClick = {
                        val matcher =
                            if (matchesAny) RuleMatcher.AnyDestination
                            else RuleMatcher.Country(region!!)
                        onSave(EditorDraft(matcher, actionChoice.toAction(simRef)))
                    },
                    enabled = isValid(matchesAny, region, actionChoice, simRef),
                ) {
                    Text(stringResource(R.string.editor_save))
                }
            }
        }
    }
}

@Composable
private fun ChoiceRow(selected: Boolean, text: String, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

private enum class ActionChoice {
    USE_SIM,
    MATCHING_SIM,
    ASK,
    SYSTEM_DEFAULT,
    ;

    fun toAction(simRef: SimRef?): RuleAction = when (this) {
        USE_SIM -> RuleAction.UseSim(simRef!!)
        MATCHING_SIM -> RuleAction.UseMatchingCountrySim
        ASK -> RuleAction.Ask
        SYSTEM_DEFAULT -> RuleAction.SystemDefault
    }

    companion object {
        fun of(action: RuleAction?): ActionChoice = when (action) {
            is RuleAction.UseSim -> USE_SIM
            RuleAction.UseMatchingCountrySim -> MATCHING_SIM
            RuleAction.Ask -> ASK
            RuleAction.SystemDefault -> SYSTEM_DEFAULT
            // Hand-off editing lands with Phase 5; default new rules to Ask.
            is RuleAction.HandOff, null -> ASK
        }
    }
}

private fun isValid(
    matchesAny: Boolean,
    region: String?,
    action: ActionChoice,
    simRef: SimRef?,
): Boolean {
    if (!matchesAny && region == null) return false
    if (action == ActionChoice.USE_SIM && simRef == null) return false
    return true
}
