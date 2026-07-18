package app.simmo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.simmo.R
import app.simmo.store.SimmoState
import kotlin.math.roundToInt

/** The Settings screen's call options, mirroring persisted state. */
data class CallSettingsUi(
    val showCallToast: Boolean = false,
    /** Seconds of cancelable countdown before a rule-picked call; 0 = off. */
    val callDelaySeconds: Int = 0,
)

/**
 * App-level options, reached from the rules list. Also the way to the SIM
 * registry — the natural home as more options land (the default-region
 * override is the next candidate, per SPEC "Country detection").
 */
@Composable
fun SettingsScreen(
    viewModel: RulesViewModel,
    onOpenSims: () -> Unit,
    onBack: () -> Unit,
) {
    val settings by viewModel.callSettings.collectAsStateWithLifecycle()
    SettingsContent(
        settings = settings,
        onShowCallToastChange = viewModel::setShowCallToast,
        onCallDelayChange = viewModel::setCallDelaySeconds,
        onOpenSims = onOpenSims,
        onBack = onBack,
    )
}

@Composable
internal fun SettingsContent(
    settings: CallSettingsUi = CallSettingsUi(),
    onShowCallToastChange: (Boolean) -> Unit = {},
    onCallDelayChange: (Int) -> Unit = {},
    onOpenSims: () -> Unit = {},
    onBack: () -> Unit = {},
) {
    BackHandler(onBack = onBack)
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 16.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenSims)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.sim_registry_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.settings_sims_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // The row itself is the toggle (the Switch's own handler is null)
            // so TalkBack reads label, description, and state as one switch —
            // same pattern as onboarding's analytics row.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = settings.showCallToast,
                        role = Role.Switch,
                        onValueChange = onShowCallToastChange,
                    )
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_call_toast_label),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.settings_call_toast_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.showCallToast,
                    onCheckedChange = null,
                )
            }
            // Dragging edits a local draft; the value is persisted once on
            // release so a swipe doesn't write per crossed step.
            var delayDraft by remember(settings.callDelaySeconds) {
                mutableFloatStateOf(settings.callDelaySeconds.toFloat())
            }
            val draftSeconds = delayDraft.roundToInt()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.settings_call_delay_label),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = if (draftSeconds == 0) {
                            stringResource(R.string.settings_call_delay_off)
                        } else {
                            pluralStringResource(
                                R.plurals.settings_call_delay_value,
                                draftSeconds,
                                draftSeconds,
                            )
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Text(
                    text = stringResource(R.string.settings_call_delay_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = delayDraft,
                    onValueChange = { delayDraft = it },
                    onValueChangeFinished = { onCallDelayChange(delayDraft.roundToInt()) },
                    valueRange = 0f..SimmoState.MAX_CALL_DELAY_SECONDS.toFloat(),
                    steps = SimmoState.MAX_CALL_DELAY_SECONDS - 1,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
