package app.simmo.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.simmo.R
import app.simmo.domain.SimRef

/**
 * One row of the SIMs screen (SPEC "Disabled-SIM assist"): every SIM Simmo
 * has ever seen active, with its live status. Prepared off the main thread —
 * including the last-seen date text — so composition just renders.
 */
data class RegistrySimRowUi(
    val ref: SimRef,
    /** e.g. "Telstra personal", falling back to the carrier name. */
    val name: String,
    /** The carrier, when it adds anything beyond [name]; null otherwise. */
    val carrier: String?,
    /** "number · country" (either half alone when the other is unknown); null when both are. */
    val detail: String?,
    val active: Boolean,
    /** Formatted last-seen date; only shown for inactive SIMs. */
    val lastSeenLabel: String,
    /** Android's default voice SIM — "primary for calling" in the current country. */
    val callingPrimary: Boolean = false,
    /** The SIM Simmo's calling rules would use for a call to the current country. */
    val callingPreferred: Boolean = false,
    /** Android's default (user-selected) data SIM — "primary for data". */
    val dataPrimary: Boolean = false,
    /** The SIM carrying data now while it isn't the primary (auto data switch). */
    val dataTemporary: Boolean = false,
    /** The SIM Simmo's data rules want carrying data here. */
    val dataPreferred: Boolean = false,
)

/**
 * The SIM registry screen: rename lands later (TODO.md — a nickname, so the
 * identity fields stay untouched); today it shows every registered SIM with
 * active/last-seen state and deletes stale entries. Active SIMs can't be
 * deleted — the next telephony refresh would just re-register them.
 */
@Composable
fun SimRegistryScreen(
    viewModel: RulesViewModel,
    onBack: () -> Unit,
    onOpenSimSettings: () -> Unit,
    onEditRules: () -> Unit,
) {
    val page by viewModel.simsPage.collectAsStateWithLifecycle()
    // The rows' own-number line needs READ_PHONE_NUMBERS (split from
    // READ_PHONE_STATE in API 30). Only requested when the phone grant is
    // already held: same permission group, so the request is granted silently
    // — no dialog interrupting the screen — and a refresh fills the numbers in.
    val context = LocalContext.current
    val numbersLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.onPhoneNumbersGranted()
    }
    LaunchedEffect(Unit) {
        val granted = { permission: String ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
        if (granted(Manifest.permission.READ_PHONE_STATE) &&
            !granted(Manifest.permission.READ_PHONE_NUMBERS)
        ) {
            numbersLauncher.launch(Manifest.permission.READ_PHONE_NUMBERS)
        }
    }
    SimRegistryContent(
        rows = page.rows,
        currentCountry = page.currentCountry,
        onDelete = viewModel::deleteRegisteredSim,
        onBack = onBack,
        onOpenSimSettings = onOpenSimSettings,
        onEditRules = onEditRules,
    )
}

@Composable
internal fun SimRegistryContent(
    rows: List<RegistrySimRowUi>,
    currentCountry: String? = null,
    onDelete: (SimRef) -> Unit = {},
    onBack: () -> Unit = {},
    onOpenSimSettings: () -> Unit = {},
    onEditRules: () -> Unit = {},
) {
    BackHandler(onBack = onBack)
    // The row awaiting delete confirmation; saveable so a rotation mid-confirm
    // keeps the dialog up instead of silently dismissing it.
    var pendingDelete by rememberSaveable(stateSaver = PendingDeleteSaver) {
        mutableStateOf<RegistrySimRowUi?>(null)
    }
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(16.dp),
        ) {
            Text(
                text = stringResource(R.string.sim_registry_title),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = if (currentCountry != null) 4.dp else 16.dp),
            )
            // Where the user is now — the context the primary/preferred status
            // chips below are relative to. Hidden when the country is unknown.
            currentCountry?.let {
                Text(
                    text = stringResource(R.string.sims_current_country, it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
            }
            Text(
                text = stringResource(R.string.sim_registry_explainer),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 16.dp),
            )
            // The two top actions: jump to the rules list, or to Android's SIM
            // settings — enabling/disabling a SIM and changing the primary
            // call/data SIM are Settings' job (apps can't do it themselves), so
            // "Change SIMs" is the one label every jump out uses.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(onClick = onEditRules, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.sims_edit_rules))
                }
                OutlinedButton(onClick = onOpenSimSettings, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.change_sims))
                }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Deliberately no item keys: after a restore invalidates every
                // subscription ID, same-named rows differing only in last-seen
                // can share the whole identity triple, and duplicate keys
                // would crash the list before the user could clean them up
                // (Codex on PR #19). Rows hold no state, so positional
                // identity loses nothing.
                items(rows) { row ->
                    RegistryRow(row, onDeleteRequest = { pendingDelete = row })
                }
            }
        }
    }
    pendingDelete?.let { row ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.sim_registry_delete_title, row.name)) },
            text = { Text(stringResource(R.string.sim_registry_delete_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        onDelete(row.ref)
                    },
                ) {
                    Text(stringResource(R.string.editor_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.editor_cancel))
                }
            },
        )
    }
}

@Composable
private fun RegistryRow(row: RegistrySimRowUi, onDeleteRequest: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = row.name, style = MaterialTheme.typography.titleMedium)
            row.carrier?.let {
                Text(text = it, style = MaterialTheme.typography.bodyMedium)
            }
            row.detail?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = if (row.active) stringResource(R.string.sim_registry_active)
                else stringResource(R.string.sim_registry_last_seen, row.lastSeenLabel),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SimStatusChips(row)
        }
        if (!row.active) {
            IconButton(onClick = onDeleteRequest) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.sim_registry_delete_action, row.name),
                )
            }
        }
    }
}

/**
 * The current-country status chips under a SIM row: which role, if any, this
 * SIM holds right now. **Primary** is what the phone is set to use (Android's
 * own word — "Your primary SIMs"); **preferred** is what Simmo's rules would
 * use here. Nothing renders when the SIM holds no role, so an ordinary SIM
 * stays uncluttered.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SimStatusChips(row: RegistrySimRowUi) {
    val chips = buildList {
        if (row.callingPrimary) add(R.string.sim_status_calling_primary)
        if (row.callingPreferred) add(R.string.sim_status_calling_preferred)
        if (row.dataPrimary) add(R.string.sim_status_data_primary)
        if (row.dataTemporary) add(R.string.sim_status_data_temporary)
        if (row.dataPreferred) add(R.string.sim_status_data_preferred)
    }
    if (chips.isEmpty()) return
    FlowRow(
        modifier = Modifier.padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        chips.forEach { StatusChip(stringResource(it)) }
    }
}

@Composable
private fun StatusChip(label: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/** Persists the pending-delete row across recreation (it's tiny and stable). */
private val PendingDeleteSaver = Saver<RegistrySimRowUi?, List<String>>(
    save = { row ->
        row?.let {
            listOf(
                it.ref.subscriptionId.toString(), it.ref.carrierName, it.ref.displayName,
                it.name, it.carrier.orEmpty(), it.detail.orEmpty(), it.active.toString(),
                it.lastSeenLabel,
            )
        } ?: emptyList()
    },
    restore = { parts ->
        parts.takeIf { it.size == 8 }?.let {
            RegistrySimRowUi(
                ref = SimRef(it[0].toInt(), it[1], it[2]),
                name = it[3],
                carrier = it[4].ifEmpty { null },
                detail = it[5].ifEmpty { null },
                active = it[6].toBoolean(),
                lastSeenLabel = it[7],
            )
        }
    },
)
