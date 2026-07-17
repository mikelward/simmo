package app.simmo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.simmo.R
import app.simmo.domain.Rule
import app.simmo.domain.RuleAction
import app.simmo.domain.RuleMatcher
import app.simmo.domain.SimRef
import app.simmo.domain.countryMatcher
import app.simmo.domain.regionCodes
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What the editor is producing — a new rule or an edit to an existing one.
 * Serializable so the open editor route can be persisted in saved state and
 * survive process death, not just configuration change.
 */
@Serializable
sealed interface EditorTarget {
    @Serializable
    @SerialName("new")
    data object New : EditorTarget

    @Serializable
    @SerialName("existing")
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
    // rememberSaveable so an in-progress edit survives rotation / recreation.
    val initial = (target as? EditorTarget.Existing)?.rule
    val initialRegions = initial?.matcher?.regionCodes().orEmpty()
    var matchesAny by rememberSaveable { mutableStateOf(initialRegions.isEmpty()) }
    // The rule's countries, in the order added; kept when toggling to "any"
    // so switching back doesn't lose them (they're dropped only on save).
    var regions by rememberSaveable(stateSaver = RegionsSaver) {
        mutableStateOf(initialRegions)
    }
    // An existing rule whose action the editor can't yet represent (Ask, or a
    // hand-off): kept verbatim so saving an edit to its country never silently
    // rewrites the action. Null for new rules and editor-representable ones.
    val keepAction = initial?.action?.takeIf { it is RuleAction.Ask || it is RuleAction.HandOff }
    // Null means "keep the unsupported original action" (only reachable when
    // keepAction is non-null); a concrete choice replaces it.
    var actionChoice by rememberSaveable {
        mutableStateOf(ActionChoice.of(initial?.action))
    }
    // Holds the raw stored/tapped ref; the *displayed* selection is resolved
    // from it against the current options below, so a SIM whose id was
    // invalidated or reissued after a restore still highlights the right row.
    var simRef by rememberSaveable(stateSaver = SimRefSaver) {
        mutableStateOf((initial?.action as? RuleAction.UseSim)?.sim)
    }
    val selectedSimRef = resolveSelectedSim(simRef, simOptions)

    // The country picker is a full-screen sub-step of the editor rather than a
    // separate navigation route, so the editor's draft above stays composed
    // (and thus retained) while it's open. Both flags are rememberSaveable so a
    // rotation or process death mid-search reopens the picker where it was.
    var showCountryPicker by rememberSaveable { mutableStateOf(false) }
    var countryQuery by rememberSaveable { mutableStateOf("") }
    if (showCountryPicker) {
        CountryPickerContent(
            options = countryOptions,
            query = countryQuery,
            onQueryChange = { countryQuery = it },
            selectedRegions = regions.map { it.uppercase() }.toSet(),
            onSelect = { picked ->
                if (regions.none { it.equals(picked, ignoreCase = true) }) {
                    regions = regions + picked
                }
                matchesAny = false
                countryQuery = ""
                showCountryPicker = false
            },
            onBack = {
                countryQuery = ""
                showCountryPicker = false
            },
        )
        return
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(16.dp),
        ) {
            // Everything above the button bar scrolls in one list, so no
            // section can starve another or push the buttons off-screen.
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Text(
                        text = stringResource(
                            if (target is EditorTarget.Existing) R.string.editor_title_edit
                            else R.string.editor_title_new,
                        ),
                        style = MaterialTheme.typography.headlineMedium,
                    )
                }

                item {
                    Text(stringResource(R.string.editor_when_label), style = MaterialTheme.typography.titleMedium)
                }
                item {
                    ChoiceRow(
                        selected = matchesAny,
                        text = stringResource(R.string.rule_matcher_any),
                        onSelect = { matchesAny = true },
                    )
                }
                // Each chosen country is a removable entry; the add row below
                // opens the searchable picker rather than listing ~240
                // countries inline. Entries stay visible (dimmed) while "Any
                // country" is selected, same as the old single-country label
                // did, so switching back is one tap with nothing lost.
                items(regions, key = { "region:$it" }) { entry ->
                    val label = countryOptions
                        .firstOrNull { it.regionCode.equals(entry, ignoreCase = true) }?.label
                        ?: entry
                    SelectedCountryRow(
                        label = label,
                        dimmed = matchesAny,
                        onSelect = { matchesAny = false },
                        onRemove = { regions = regions.filterNot { it == entry } },
                    )
                }
                item {
                    AddCountryRow(onClick = { showCountryPicker = true })
                }

                item {
                    Text(stringResource(R.string.editor_do_label), style = MaterialTheme.typography.titleMedium)
                }
                if (keepAction != null) {
                    // The rule's current action can't be edited here yet (no
                    // chooser for Ask; hand-off lands in a later phase). Show it
                    // as a preselected row so the user can see and keep it; the
                    // action is only rewritten if they pick a control below.
                    item {
                        ChoiceRow(
                            selected = actionChoice == null,
                            text = when (val a = keepAction) {
                                RuleAction.Ask -> stringResource(R.string.rule_action_ask)
                                is RuleAction.HandOff.ViaPhoneAccount ->
                                    stringResource(R.string.rule_action_hand_off, a.account.id)
                                is RuleAction.HandOff.ViaDialIntent ->
                                    stringResource(R.string.rule_action_hand_off, a.packageName)
                                else -> ""
                            },
                            onSelect = { actionChoice = null },
                        )
                    }
                }
                // Each SIM is a direct action choice, not nested under a
                // separate "A specific SIM" mode row — so exactly one radio in
                // this group is ever filled, never a mode + its selection both.
                items(simOptions, key = { "${it.ref.subscriptionId}|${it.ref.carrierName}|${it.ref.displayName}" }) { option ->
                    ChoiceRow(
                        selected = actionChoice == ActionChoice.USE_SIM && option.ref == selectedSimRef,
                        text = if (option.active) option.label
                        else stringResource(R.string.editor_sim_disabled_suffix, option.label),
                        onSelect = {
                            actionChoice = ActionChoice.USE_SIM
                            simRef = option.ref
                        },
                    )
                }
                item {
                    ChoiceRow(
                        selected = actionChoice == ActionChoice.MATCHING_SIM,
                        text = stringResource(R.string.rule_action_matching_sim),
                        onSelect = { actionChoice = ActionChoice.MATCHING_SIM },
                    )
                }
                // "Ask" is deliberately absent as a *new* choice until the
                // chooser exists — a saved Ask rule would otherwise silently
                // behave as "No change" (see TODO.md Phase 3 chooser). An
                // existing Ask rule is still preserved via the keep-action row
                // above.
                item {
                    ChoiceRow(
                        selected = actionChoice == ActionChoice.SYSTEM_DEFAULT,
                        text = stringResource(R.string.rule_action_system_default),
                        onSelect = { actionChoice = ActionChoice.SYSTEM_DEFAULT },
                    )
                }
            }

            // Fixed button bar: always visible, never scrolled away.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
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
                            else countryMatcher(regions)
                        onSave(EditorDraft(matcher, resolveEditorAction(actionChoice, selectedSimRef, keepAction)))
                    },
                    enabled = isValid(matchesAny, regions, actionChoice, selectedSimRef, simOptions),
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

/**
 * One country already on the rule: tappable to re-select the countries branch,
 * with a remove affordance. Indented to align with the radio rows' labels
 * (48dp radio + 8dp gap); dimmed with the same alpha the rules list uses for
 * paused rules while "Any country" is the selected branch.
 */
@Composable
private fun SelectedCountryRow(
    label: String,
    dimmed: Boolean,
    onSelect: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (dimmed) 0.4f else 1f)
            .padding(start = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onSelect),
        )
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Filled.Clear,
                contentDescription = stringResource(R.string.editor_remove_country, label),
            )
        }
    }
}

/** Opens the searchable country picker; each pick adds one country to the rule. */
@Composable
private fun AddCountryRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Sized like the radio buttons so the label text columns line up.
        Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Icon(imageVector = Icons.Filled.Add, contentDescription = null)
        }
        Text(stringResource(R.string.editor_add_country), style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * Full-screen searchable country picker (a sub-step of the editor). Filters the
 * ~240-country list by name, dial code, ISO code, or alias as the user types;
 * picking one or pressing back returns to the editor.
 */
@Composable
internal fun CountryPickerContent(
    options: List<CountryOptionUi>,
    query: String,
    onQueryChange: (String) -> Unit,
    /** Uppercase ISO regions already on the rule; shown pre-selected. */
    selectedRegions: Set<String>,
    onSelect: (String) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val ranked = remember(options, query) { rankCountries(options, query) }
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.editor_country_search_hint)) },
            )
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(ranked, key = { it.regionCode }) { option ->
                    ChoiceRow(
                        selected = option.regionCode.uppercase() in selectedRegions,
                        text = option.label,
                        onSelect = { onSelect(option.regionCode) },
                    )
                }
            }
        }
    }
}

internal enum class ActionChoice {
    USE_SIM,
    MATCHING_SIM,
    SYSTEM_DEFAULT,
    ;

    fun toAction(simRef: SimRef?): RuleAction = when (this) {
        USE_SIM -> RuleAction.UseSim(simRef!!)
        MATCHING_SIM -> RuleAction.UseMatchingCountrySim
        SYSTEM_DEFAULT -> RuleAction.SystemDefault
    }

    companion object {
        /**
         * The editor control for [action], or null when the editor can't
         * represent it (Ask, hand-off) and must preserve the original on save.
         * A new rule (null [action]) defaults to the matching-country SIM.
         */
        fun of(action: RuleAction?): ActionChoice? = when (action) {
            is RuleAction.UseSim -> USE_SIM
            RuleAction.UseMatchingCountrySim -> MATCHING_SIM
            RuleAction.SystemDefault -> SYSTEM_DEFAULT
            null -> MATCHING_SIM
            RuleAction.Ask, is RuleAction.HandOff -> null
        }
    }
}

/**
 * The action to save: the user's [choice] if they picked one, otherwise the
 * preserved [keepAction] for a rule whose action the editor can't yet edit.
 */
internal fun resolveEditorAction(
    choice: ActionChoice?,
    simRef: SimRef?,
    keepAction: RuleAction?,
): RuleAction =
    choice?.toAction(simRef)
        ?: keepAction
        ?: error("Rule editor produced no action")

/**
 * The SIM option that [ref] points at, resolved with the same identity ladder
 * as `resolveSim`: an exact (real) subscription-id match first, then the unique
 * carrier + display-name fallback. So a rule whose SIM id was invalidated to the
 * sentinel or reissued after a restore still highlights (and, on save, re-links
 * to) the rebound option, rather than opening with "A specific SIM" chosen but
 * no visible selection. Falls back to [ref] itself when nothing matches (or is
 * ambiguous), or the first option when [ref] is null, so the selection is never
 * silently emptied.
 */
internal fun resolveSelectedSim(ref: SimRef?, options: List<SimOptionUi>): SimRef? {
    if (ref == null) return options.firstOrNull()?.ref
    options.firstOrNull {
        ref.subscriptionId != SimRef.INVALID_SUBSCRIPTION_ID && it.ref.subscriptionId == ref.subscriptionId
    }?.let { return it.ref }
    val byIdentity = options.filter {
        it.ref.carrierName.trim().equals(ref.carrierName.trim(), ignoreCase = true) &&
            it.ref.displayName.trim().equals(ref.displayName.trim(), ignoreCase = true)
    }
    return byIdentity.singleOrNull()?.ref ?: ref
}

internal fun isValid(
    matchesAny: Boolean,
    regions: List<String>,
    action: ActionChoice?,
    simRef: SimRef?,
    simOptions: List<SimOptionUi>,
): Boolean {
    if (!matchesAny && regions.isEmpty()) return false
    // A specific-SIM action must point at a SIM actually offered here. When the
    // stored SIM can't be resolved to a row (e.g. renamed or removed after a
    // restore, so nothing is selected), require re-linking to a real SIM rather
    // than silently re-saving the paused rule.
    if (action == ActionChoice.USE_SIM && simOptions.none { it.ref == simRef }) return false
    return true
}

/** Persists the rule's chosen countries across recreation. */
private val RegionsSaver: Saver<List<String>, Any> = listSaver(
    save = { it },
    restore = { it },
)

/** Persists the selected SIM across recreation as its three identity fields. */
private val SimRefSaver: Saver<SimRef?, List<String>> = Saver(
    save = { it?.let { r -> listOf(r.subscriptionId.toString(), r.carrierName, r.displayName) } ?: emptyList() },
    restore = { parts ->
        parts.takeIf { it.size == 3 }?.let { SimRef(it[0].toInt(), it[1], it[2]) }
    },
)
