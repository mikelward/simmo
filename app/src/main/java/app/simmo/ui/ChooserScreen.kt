package app.simmo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.simmo.R
import app.simmo.domain.ActiveSim
import app.simmo.domain.CallingAccount
import app.simmo.domain.CountryVerdict
import app.simmo.domain.NumberCorrection
import app.simmo.domain.PhoneAccountRef
import app.simmo.domain.SimRef
import app.simmo.domain.SimResolution
import app.simmo.domain.resolveSim

/**
 * Everything the chooser renders, prepared up front from the warm in-memory
 * snapshot (the chooser appears mid-call-attempt and must paint its first
 * frame instantly — no loading state, and no libphonenumber work in
 * composition).
 */
data class ChooserUiState(
    val dialedNumber: String,
    /** "+61 Australia" when the destination is determined; null otherwise. */
    val countryLabel: String?,
    /** ISO region behind the "remember for <country>" rule; null when undetermined. */
    val rememberRegion: String?,
    /** Country name alone for the remember checkbox, e.g. "Australia". */
    val rememberCountryName: String?,
    val targets: List<ChooserTargetUi>,
    /** Disabled SIMs that skipped higher-priority rules wanted (SPEC "Disabled-SIM assist"). */
    val skippedSimNames: List<String>,
    /**
     * Same-contact number correction choices (SPEC "Hands-free and Android
     * Auto safeguards"): the contact's local number(s) first — the default
     * pick — then the number as dialed. Empty for a plain Ask chooser.
     */
    val numberChoices: List<NumberChoiceUi> = emptyList(),
)

/**
 * One tappable target the call can be re-placed on: an active SIM, or another
 * enabled calling account (SIP provider, VoIP app).
 */
data class ChooserTargetUi(
    /** Set for SIM targets; null for non-SIM calling accounts. */
    val sim: SimRef?,
    val account: PhoneAccountRef,
    val label: String,
)

/** One selectable number; [contactName] set for a contact's local number. */
data class NumberChoiceUi(
    val number: String,
    /** The contact the local number belongs to; null for "as dialed". */
    val contactName: String?,
)

internal fun buildChooserUiState(
    dialedNumber: String,
    country: CountryVerdict,
    activeSims: List<ActiveSim>,
    skippedInactiveSims: List<SimRef>,
    numberCorrection: NumberCorrection? = null,
    callingAccounts: List<CallingAccount> = emptyList(),
): ChooserUiState {
    val region = (country as? CountryVerdict.Country)?.regionCode
    // A correction confirmation must not teach a country rule: the user is
    // picking a number for one contact, and "Remember for GB" there would
    // reroute every GB call off the back of a one-off correction — worse,
    // the call usually placed is the local number, not a GB one (Codex on
    // PR #41). The destination label still shows; only remembering is off.
    val rememberRegion = region?.takeIf { numberCorrection == null }
    return ChooserUiState(
        dialedNumber = dialedNumber,
        countryLabel = region?.let { countryLabel(it) },
        rememberRegion = rememberRegion,
        rememberCountryName = rememberRegion?.let { countryDisplayName(it) },
        // SIMs first — the common case — then the other calling accounts.
        targets = activeSims.map { sim ->
            ChooserTargetUi(
                sim = SimRef(sim.subscriptionId, sim.carrierName, sim.displayName),
                account = sim.phoneAccount,
                label = sim.displayName.ifBlank { sim.carrierName },
            )
        } + callingAccounts.map { account ->
            ChooserTargetUi(sim = null, account = account.ref, label = account.label)
        },
        // Re-resolved against the *current* SIMs, not the ones at verdict
        // time: the user can jump to SIM settings from here, enable the SIM,
        // and come back — its note must clear as its call button appears.
        skippedSimNames = skippedInactiveSims
            .filter { resolveSim(it, activeSims) !is SimResolution.Active }
            .map { it.displayName.ifBlank { it.carrierName } },
        numberChoices = numberCorrection?.let { correction ->
            val locals = correction.candidates.map { NumberChoiceUi(it.number, it.contactName) }
            val asDialed = NumberChoiceUi(dialedNumber, contactName = null)
            // The first choice is the preselected one. A sole-owner correction
            // leads with the local number — it is why this chooser opened. A
            // shared line leads with "as dialed": whose local number the user
            // meant is not Simmo's to guess (maintainer decision), so picking
            // an owner is always a deliberate tap.
            if (correction.sharedLine) listOf(asDialed) + locals else locals + asDialed
        }.orEmpty(),
    )
}

/**
 * The Ask chooser (SPEC "The chooser (Ask flow)"): the dialed number and its
 * detected country, one button per active SIM (a tap re-places the call),
 * an optional "remember for <country>" rule, and cancel. The in-flight call
 * was already canceled before this opened, so cancel just closes.
 */
@Composable
internal fun ChooserContent(
    state: ChooserUiState,
    onPlace: (target: ChooserTargetUi, rememberRule: Boolean, chosenNumber: String) -> Unit,
    onOpenSimSettings: () -> Unit,
    onCancel: () -> Unit,
) {
    var rememberRule by rememberSaveable { mutableStateOf(false) }
    // The number the SIM buttons place: the contact's local number leads and
    // is preselected (it is why this chooser opened); "as dialed" stays one
    // tap away. Equal to the dialed number on a plain Ask chooser.
    var chosenNumber by rememberSaveable {
        mutableStateOf(state.numberChoices.firstOrNull()?.number ?: state.dialedNumber)
    }
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(16.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = stringResource(R.string.chooser_title),
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    text = state.dialedNumber,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(top = 16.dp),
                )
                state.countryLabel?.let { label ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (state.numberChoices.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    state.numberChoices.forEach { choice ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = chosenNumber == choice.number,
                                    role = Role.RadioButton,
                                    onClick = { chosenNumber = choice.number },
                                )
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            RadioButton(selected = chosenNumber == choice.number, onClick = null)
                            Column {
                                Text(
                                    text = choice.number,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Text(
                                    text = choice.contactName
                                        ?.let { stringResource(R.string.chooser_number_local, it) }
                                        ?: stringResource(R.string.chooser_number_as_dialed),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                state.skippedSimNames.forEach { name ->
                    Text(
                        text = stringResource(R.string.chooser_skipped_sim, name),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (state.skippedSimNames.isNotEmpty()) {
                    // Enabling a SIM needs the system Settings screen — apps
                    // can't switch eSIM profiles themselves (SPEC "Enabling
                    // SIMs is Settings' job"). The targets above update live
                    // when the SIM comes back, so returning here shows its
                    // call button and drops the note.
                    OutlinedButton(
                        onClick = onOpenSimSettings,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    ) {
                        Text(stringResource(R.string.system_settings))
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                state.targets.forEach { target ->
                    Button(
                        onClick = { onPlace(target, rememberRule, chosenNumber) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                    ) {
                        Text(stringResource(R.string.rule_action_use_sim, target.label))
                    }
                }
                if (state.targets.isEmpty()) {
                    Text(
                        text = stringResource(R.string.chooser_no_sims),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (state.rememberCountryName != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(value = rememberRule, onValueChange = { rememberRule = it })
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Checkbox(checked = rememberRule, onCheckedChange = null)
                        Text(
                            text = stringResource(R.string.chooser_remember, state.rememberCountryName),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                Text(stringResource(R.string.chooser_cancel))
            }
        }
    }
}
