package app.simmo.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.simmo.R
import app.simmo.domain.CustomGroup
import app.simmo.domain.DataExpectation
import app.simmo.domain.DataRule
import app.simmo.domain.DataSimScope
import app.simmo.domain.RuleMatcher
import app.simmo.domain.SimRef
import app.simmo.domain.destinationMatcher
import app.simmo.domain.groupIds
import app.simmo.domain.regionCodes
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What the data editor is producing; the data-side sibling of [EditorTarget],
 * persisted in saved state the same way.
 */
@Serializable
sealed interface DataEditorTarget {
    @Serializable
    @SerialName("new")
    data object New : DataEditorTarget

    /** Editing an existing data rule by its stable [DataRule.id]; see [EditorTarget.Existing]. */
    @Serializable
    @SerialName("existing")
    data class Existing(val id: String, val rule: DataRule) : DataEditorTarget
}

@Composable
fun DataRuleEditorScreen(
    viewModel: RulesViewModel,
    target: DataEditorTarget,
    onDone: () -> Unit,
) {
    val simOptions by viewModel.dataSimOptions.collectAsStateWithLifecycle()
    val countryOptions by viewModel.countryOptions.collectAsStateWithLifecycle()
    val suggestedCountries by viewModel.suggestedCountries.collectAsStateWithLifecycle()
    val groupOptions by viewModel.groupOptions.collectAsStateWithLifecycle()
    // Contacts back only the picker's "Suggested" bucket here — data rules
    // have no hand-off actions, so none of the calling editor's notification
    // plumbing applies.
    val context = LocalContext.current
    var contactsGranted by remember {
        mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CONTACTS,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED,
        )
    }
    val contactsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        contactsGranted = granted
        if (granted) viewModel.onContactsAccessGranted()
    }
    DataRuleEditorContent(
        target = target,
        simOptions = simOptions,
        countryOptions = countryOptions,
        suggestedCountries = suggestedCountries,
        contactsGranted = contactsGranted,
        groupOptions = groupOptions,
        onRequestContactsAccess = { contactsLauncher.launch(Manifest.permission.READ_CONTACTS) },
        onSave = { pendingGroups, rule ->
            when (target) {
                DataEditorTarget.New -> viewModel.addDataRule(rule, pendingGroups)
                is DataEditorTarget.Existing ->
                    viewModel.replaceDataRule(target.id, rule, pendingGroups)
            }
            onDone()
        },
        onDelete = (target as? DataEditorTarget.Existing)?.let { existing ->
            { viewModel.removeDataRule(existing.id); onDone() }
        },
        onCancel = onDone,
    )
}

@Composable
internal fun DataRuleEditorContent(
    target: DataEditorTarget,
    simOptions: List<SimOptionUi>,
    countryOptions: List<CountryOptionUi>,
    /** Saves the rule and, in the same transaction, any groups built in the picker. */
    onSave: (pendingGroups: List<CustomGroup>, rule: DataRule) -> Unit,
    onDelete: (() -> Unit)?,
    onCancel: () -> Unit,
    suggestedCountries: List<CountryOptionUi> = emptyList(),
    contactsGranted: Boolean = true,
    groupOptions: List<CountryGroupOptionUi> = emptyList(),
    onRequestContactsAccess: () -> Unit = {},
) {
    val initial = (target as? DataEditorTarget.Existing)?.rule
    val initialRegions = initial?.matcher?.regionCodes().orEmpty()
    val initialGroups = initial?.matcher?.groupIds().orEmpty()
    var matchesAny by rememberSaveable {
        mutableStateOf(initial != null && initialRegions.isEmpty() && initialGroups.isEmpty())
    }
    var regions by rememberSaveable(stateSaver = RegionsSaver) { mutableStateOf(initialRegions) }
    var groups by rememberSaveable(stateSaver = RegionsSaver) { mutableStateOf(initialGroups) }
    // Groups built from the picker but not yet committed; persisted only with
    // the saved rule, same transaction, same no-orphan reasoning as the
    // calling editor.
    var pendingGroups by rememberSaveable(stateSaver = PendingGroupsSaver) {
        mutableStateOf(emptyList<CustomGroup>())
    }
    // An existing multi-SIM RoamingOk scope has no editor rows (the editor
    // offers single-SIM scopes); kept verbatim like the calling editor's
    // unknown hand-off action, so editing the countries never rewrites it.
    val keepExpectation = (initial?.expectation as? DataExpectation.RoamingOk)
        ?.takeIf { (it.scope as? DataSimScope.Sims)?.sims.orEmpty().size > 1 }
    var choice by rememberSaveable {
        mutableStateOf(if (keepExpectation != null) null else DataExpectationChoice.of(initial?.expectation))
    }
    var simRef by rememberSaveable(stateSaver = SimRefSaver) {
        mutableStateOf(initial?.expectation?.let(DataExpectationChoice::simOf))
    }
    val selectedSimRef = resolveSelectedSim(simRef, simOptions)

    var showCountryPicker by rememberSaveable { mutableStateOf(false) }
    var showGroupEditor by rememberSaveable { mutableStateOf(false) }
    var countryQuery by rememberSaveable { mutableStateOf("") }
    if (showGroupEditor) {
        GroupEditor(
            initial = null,
            countryOptions = countryOptions,
            onSave = { name, memberRegions ->
                val group = CustomGroup(CustomGroup.newId(), name.trim(), memberRegions.map { it.uppercase() })
                pendingGroups = pendingGroups + group
                if (group.id !in groups) groups = groups + group.id
                matchesAny = false
                showGroupEditor = false
            },
            onDelete = null,
            onCancel = { showGroupEditor = false },
        )
        return
    }
    if (showCountryPicker) {
        CountryPickerContent(
            options = countryOptions,
            suggested = suggestedCountries,
            contactsGranted = contactsGranted,
            onRequestContacts = onRequestContactsAccess,
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
            groups = groupOptions,
            selectedGroupIds = groups.toSet(),
            onSelectGroup = { picked ->
                if (picked !in groups) groups = groups + picked
                matchesAny = false
                countryQuery = ""
                showCountryPicker = false
            },
            onCreateGroup = {
                countryQuery = ""
                showCountryPicker = false
                showGroupEditor = true
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
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Text(
                        text = stringResource(
                            if (target is DataEditorTarget.Existing) R.string.data_editor_title_edit
                            else R.string.data_editor_title_new,
                        ),
                        style = MaterialTheme.typography.headlineMedium,
                    )
                }

                item {
                    Text(
                        stringResource(R.string.data_editor_when_label),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                item {
                    DataChoiceRow(
                        selected = matchesAny,
                        text = stringResource(R.string.data_rule_matcher_any),
                        onSelect = { matchesAny = true },
                    )
                }
                items(groups.size, key = { "group:${groups[it]}" }) { i ->
                    val entry = groups[i]
                    val label = groupOptions.firstOrNull { it.id == entry }?.label
                        ?: pendingGroups.firstOrNull { it.id == entry }?.name
                        ?: entry
                    SelectedCountryRow(
                        label = label,
                        checked = !matchesAny,
                        onSelect = { matchesAny = false },
                        onRemove = { groups = groups.filterNot { it == entry } },
                    )
                }
                items(regions.size, key = { "region:${regions[it]}" }) { i ->
                    val entry = regions[i]
                    SelectedCountryRow(
                        // Where the user is, so the plain country name — no
                        // dialing codes (same as the data list rows).
                        label = countryDisplayName(entry),
                        checked = !matchesAny,
                        onSelect = { matchesAny = false },
                        onRemove = { regions = regions.filterNot { it == entry } },
                    )
                }
                item {
                    AddCountryRow(onClick = { showCountryPicker = true })
                }

                item {
                    Text(
                        stringResource(R.string.data_editor_expect_label),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                if (keepExpectation != null) {
                    // The rule's multi-SIM scope can't be edited here; shown
                    // preselected so the user can see and keep it — rewritten
                    // only when a row below is picked.
                    item {
                        DataChoiceRow(
                            selected = choice == null,
                            text = stringResource(
                                R.string.data_rule_roaming_ok_sims,
                                (keepExpectation.scope as DataSimScope.Sims).sims
                                    .joinToString { it.displayName.ifBlank { it.carrierName } },
                            ),
                            onSelect = { choice = null },
                        )
                    }
                }
                // Each SIM is a direct choice, like the calling editor: one
                // radio in the group is ever filled.
                items(simOptions.size, key = { simKey("use", simOptions[it].ref) }) { i ->
                    val option = simOptions[i]
                    DataChoiceRow(
                        selected = choice == DataExpectationChoice.USE_SIM && option.ref == selectedSimRef,
                        text = stringResource(
                            R.string.data_rule_use_sim,
                            disabledSuffixed(option),
                        ),
                        onSelect = {
                            choice = DataExpectationChoice.USE_SIM
                            simRef = option.ref
                        },
                    )
                }
                item {
                    // "Use a local SIM" — no specific SIM to pick, the sibling
                    // of the calling matching-country action.
                    DataChoiceRow(
                        selected = choice == DataExpectationChoice.USE_LOCAL,
                        text = stringResource(R.string.data_rule_use_local),
                        onSelect = { choice = DataExpectationChoice.USE_LOCAL },
                    )
                }
                item {
                    DataChoiceRow(
                        selected = choice == DataExpectationChoice.ROAMING_OK_ANY,
                        text = stringResource(R.string.data_rule_roaming_ok_any),
                        onSelect = { choice = DataExpectationChoice.ROAMING_OK_ANY },
                    )
                }
                item {
                    DataChoiceRow(
                        selected = choice == DataExpectationChoice.ROAMING_OK_HOMED,
                        text = stringResource(R.string.data_rule_roaming_ok_homed),
                        onSelect = { choice = DataExpectationChoice.ROAMING_OK_HOMED },
                    )
                }
                items(simOptions.size, key = { simKey("roam", simOptions[it].ref) }) { i ->
                    val option = simOptions[i]
                    DataChoiceRow(
                        selected = choice == DataExpectationChoice.ROAMING_OK_SIM && option.ref == selectedSimRef,
                        text = stringResource(
                            R.string.data_rule_roaming_ok_sims,
                            disabledSuffixed(option),
                        ),
                        onSelect = {
                            choice = DataExpectationChoice.ROAMING_OK_SIM
                            simRef = option.ref
                        },
                    )
                }
                item {
                    DataChoiceRow(
                        selected = choice == DataExpectationChoice.WARN,
                        text = stringResource(R.string.data_rule_warn),
                        onSelect = { choice = DataExpectationChoice.WARN },
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (onDelete != null) {
                    // Immediate delete; the list's Undo bar is the safety net.
                    OutlinedButton(onClick = onDelete) {
                        Text(stringResource(R.string.editor_delete))
                    }
                }
                OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.editor_cancel)) }
                Button(
                    onClick = {
                        val matcher =
                            if (matchesAny) RuleMatcher.AnyDestination
                            else destinationMatcher(regions, groups)
                        val committedGroups =
                            if (matchesAny) emptyList()
                            else pendingGroups.filter { it.id in groups }
                        val expectation = choice?.toExpectation(selectedSimRef)
                            ?: keepExpectation
                            ?: return@Button
                        onSave(
                            committedGroups,
                            DataRule(
                                matcher = matcher,
                                expectation = expectation,
                                // Editing a paused-off rule must not silently
                                // re-enable it.
                                enabled = initial?.enabled ?: true,
                                // Keep the edited rule's id so the save replaces
                                // it in place; a new rule is assigned one on write.
                                id = initial?.id.orEmpty(),
                            ),
                        )
                    },
                    // A SIM-specific choice must point at a SIM actually
                    // offered here: resolveSelectedSim falls back to the
                    // stored ref when its SIM left the registry, and saving
                    // that invisible selection would silently keep an
                    // orphaned target — require re-linking instead, like the
                    // calling editor (Codex on PR #57).
                    enabled = (matchesAny || regions.isNotEmpty() || groups.isNotEmpty()) &&
                        (choice?.needsSim != true || simOptions.any { it.ref == selectedSimRef }) &&
                        (choice != null || keepExpectation != null),
                ) {
                    Text(stringResource(R.string.editor_save))
                }
            }
        }
    }
}

/** "<SIM>" or "<SIM> (disabled)", matching the calling editor's rows. */
@Composable
private fun disabledSuffixed(option: SimOptionUi): String =
    if (option.active) option.label
    else stringResource(R.string.editor_sim_disabled_suffix, option.label)

private fun simKey(prefix: String, ref: SimRef): String =
    "$prefix|${ref.subscriptionId}|${ref.carrierName}|${ref.displayName}"

@Composable
private fun DataChoiceRow(selected: Boolean, text: String, onSelect: () -> Unit) {
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

/** The editor's expectation choices; null keeps an unrepresentable original. */
internal enum class DataExpectationChoice {
    USE_SIM, USE_LOCAL, ROAMING_OK_ANY, ROAMING_OK_HOMED, ROAMING_OK_SIM, WARN;

    /** Whether this choice needs a SIM selected before Save enables. */
    val needsSim: Boolean get() = this == USE_SIM || this == ROAMING_OK_SIM

    fun toExpectation(sim: SimRef?): DataExpectation? = when (this) {
        USE_SIM -> sim?.let { DataExpectation.UseSimForData(it) }
        USE_LOCAL -> DataExpectation.UseLocalSimForData
        ROAMING_OK_ANY -> DataExpectation.RoamingOk(DataSimScope.AnySim)
        ROAMING_OK_HOMED -> DataExpectation.RoamingOk(DataSimScope.HomedInMatchedCountries)
        ROAMING_OK_SIM -> sim?.let { DataExpectation.RoamingOk(DataSimScope.Sims(listOf(it))) }
        WARN -> DataExpectation.AlwaysWarn
    }

    companion object {
        fun of(expectation: DataExpectation?): DataExpectationChoice? = when (expectation) {
            null -> null
            is DataExpectation.UseSimForData -> USE_SIM
            DataExpectation.UseLocalSimForData -> USE_LOCAL
            is DataExpectation.RoamingOk -> when (val scope = expectation.scope) {
                DataSimScope.AnySim -> ROAMING_OK_ANY
                DataSimScope.HomedInMatchedCountries -> ROAMING_OK_HOMED
                is DataSimScope.Sims -> if (scope.sims.size == 1) ROAMING_OK_SIM else null
            }
            DataExpectation.AlwaysWarn -> WARN
        }

        /** The SIM a stored expectation names, for preselecting its row. */
        fun simOf(expectation: DataExpectation): SimRef? = when (expectation) {
            is DataExpectation.UseSimForData -> expectation.sim
            is DataExpectation.RoamingOk ->
                (expectation.scope as? DataSimScope.Sims)?.sims?.singleOrNull()
            DataExpectation.UseLocalSimForData, DataExpectation.AlwaysWarn -> null
        }
    }
}
