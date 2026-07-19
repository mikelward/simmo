package app.simmo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.simmo.R
import app.simmo.domain.CustomGroup

/**
 * The Country groups screen (SPEC "Calling rules" → custom groups): create, edit, and
 * delete user-defined groups like "Vodafone Zone 1" that rules then match by a
 * single entry. Reached from the rules list; the groups become selectable in
 * the rule editor's country picker.
 */
@Composable
fun GroupsScreen(
    viewModel: RulesViewModel,
    onBack: () -> Unit,
) {
    val groups by viewModel.customGroups.collectAsStateWithLifecycle()
    val countryOptions by viewModel.countryOptions.collectAsStateWithLifecycle()
    GroupsContent(
        groups = groups,
        countryOptions = countryOptions,
        onSaveGroup = viewModel::saveCustomGroup,
        onDeleteGroup = viewModel::deleteCustomGroup,
        onUndoDeleteGroup = viewModel::undoGroupRemoval,
        onBack = onBack,
    )
}

@Composable
internal fun GroupsContent(
    groups: List<CustomGroup>,
    countryOptions: List<CountryOptionUi>,
    onSaveGroup: (id: String?, name: String, regionCodes: List<String>) -> Unit = { _, _, _ -> },
    onDeleteGroup: (String) -> Unit = {},
    onUndoDeleteGroup: (String) -> Unit = {},
    onBack: () -> Unit = {},
) {
    // null = the list; "" = a new group; else the id being edited. A plain
    // String survives recreation without a custom saver.
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    if (editingId != null) {
        val initial = groups.firstOrNull { it.id == editingId }
        // Re-key on the id so switching which group is edited starts the
        // editor's fields fresh from that group.
        key(editingId) {
            GroupEditor(
                initial = initial,
                countryOptions = countryOptions,
                onSave = { name, regions ->
                    onSaveGroup(initial?.id, name, regions)
                    editingId = null
                },
                onDelete = initial?.let { { onDeleteGroup(it.id); editingId = null } },
                onCancel = { editingId = null },
            )
        }
        return
    }

    BackHandler(onBack = onBack)
    Surface(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.groups_title),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                Text(
                    text = stringResource(R.string.groups_explainer),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                if (groups.isEmpty()) {
                    Text(
                        text = stringResource(R.string.groups_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(groups, key = { it.id }) { group ->
                        GroupRow(
                            group = group,
                            onClick = { editingId = group.id },
                            onUndoDelete = { onUndoDeleteGroup(group.id) },
                        )
                    }
                }
            }
            FloatingActionButton(
                onClick = { editingId = "" },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .safeDrawingPadding()
                    .padding(16.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.groups_add),
                )
            }
        }
    }
}

@Composable
private fun GroupRow(group: CustomGroup, onClick: () -> Unit, onUndoDelete: () -> Unit) {
    // A soft-deleted group is struck-through and inert (no tap-to-edit); it
    // still resolves for referencing rules until the screen is left, and only
    // its Undo acts on it.
    val strike = if (group.pendingRemoval) TextDecoration.LineThrough else null
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .then(if (group.pendingRemoval) Modifier else Modifier.clickable(onClick = onClick))
                .alpha(if (group.pendingRemoval) 0.4f else 1f),
        ) {
            Text(text = group.name, style = MaterialTheme.typography.titleMedium, textDecoration = strike)
            Text(
                text = group.regionCodes.joinToString { countryDisplayName(it) },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textDecoration = strike,
            )
        }
        if (group.pendingRemoval) {
            TextButton(onClick = onUndoDelete) { Text(stringResource(R.string.action_undo)) }
        }
    }
}

/**
 * The create/edit form for one custom group: a name and the member countries,
 * added one at a time from the same searchable picker rules use. A full-screen
 * sub-step (not a dialog), so its text field composes without the popup-window
 * loop the Robolectric tests hit. Reused by the rule editor's picker so a group
 * can be created mid-rule (there [initial] is null and [onDelete] absent).
 */
@Composable
internal fun GroupEditor(
    initial: CustomGroup?,
    countryOptions: List<CountryOptionUi>,
    onSave: (name: String, regionCodes: List<String>) -> Unit,
    onDelete: (() -> Unit)?,
    onCancel: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) }
    var regions by rememberSaveable(stateSaver = GroupRegionsSaver) {
        mutableStateOf(initial?.regionCodes.orEmpty())
    }
    var showPicker by rememberSaveable { mutableStateOf(false) }
    var pickerQuery by rememberSaveable { mutableStateOf("") }
    if (showPicker) {
        CountryPickerContent(
            options = countryOptions,
            query = pickerQuery,
            onQueryChange = { pickerQuery = it },
            selectedRegions = regions.map { it.uppercase() }.toSet(),
            onSelect = { picked ->
                if (regions.none { it.equals(picked, ignoreCase = true) }) regions = regions + picked
                pickerQuery = ""
                showPicker = false
            },
            onBack = { pickerQuery = ""; showPicker = false },
        )
        return
    }

    BackHandler(onBack = onCancel)
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(16.dp),
        ) {
            Text(
                text = stringResource(
                    if (initial == null) R.string.groups_editor_title_new else R.string.groups_editor_title_edit,
                ),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 16.dp),
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.groups_name_label)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
            )
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(regions, key = { it }) { entry ->
                    val label = countryOptions
                        .firstOrNull { it.regionCode.equals(entry, ignoreCase = true) }?.label ?: entry
                    SelectedCountryRow(
                        label = label,
                        dimmed = false,
                        onSelect = {},
                        onRemove = { regions = regions.filterNot { it == entry } },
                    )
                }
                item { AddCountryRow(onClick = { showPicker = true }) }
            }
            androidx.compose.foundation.layout.Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (onDelete != null) {
                    // Immediate soft-delete; the list shows the group struck-
                    // through with an Undo until the screen is left (purge).
                    OutlinedButton(onClick = onDelete) {
                        Text(stringResource(R.string.editor_delete))
                    }
                }
                OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.editor_cancel)) }
                Button(
                    onClick = { onSave(name, regions) },
                    enabled = name.isNotBlank() && regions.isNotEmpty(),
                ) {
                    Text(stringResource(R.string.editor_save))
                }
            }
        }
    }
}

/** Persists the group's chosen countries across recreation. */
private val GroupRegionsSaver: Saver<List<String>, Any> = listSaver(
    save = { it },
    restore = { it },
)
