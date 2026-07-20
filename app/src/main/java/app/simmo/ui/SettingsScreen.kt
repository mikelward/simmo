package app.simmo.ui

import android.Manifest
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.simmo.BuildConfig
import app.simmo.DebugReport
import app.simmo.R
import app.simmo.store.SimmoState
import kotlin.math.roundToInt

/** The Settings screen's options, mirroring persisted state. */
data class SettingsUi(
    val showCallToast: Boolean = false,
    /** Seconds of cancelable countdown before a rule-picked call; 0 = off. */
    val callDelaySeconds: Int = 0,
    /** Same-contact number correction (SPEC "Hands-free and Android Auto safeguards"). */
    val correctContactNumbers: Boolean = false,
    /** The hands-free guard's "Block overseas calls" toggle. */
    val guardOverseasHandsFree: Boolean = false,
    /** The guard's "Block calls needing a disabled SIM" toggle. */
    val guardDisabledSimHandsFree: Boolean = false,
    /** The "Make Simmo better" telemetry choice (SPEC "Permissions and privacy"). */
    val analyticsOptIn: Boolean = true,
)

/**
 * App-level options, reached from the SIMs home's gear. Leads with entries into
 * the Rules list and Country groups, then the toggles (the default-region
 * override is the next candidate, per SPEC "Country detection").
 */
@Composable
fun SettingsScreen(
    viewModel: RulesViewModel,
    contactsGranted: Boolean,
    onContactsGranted: () -> Unit,
    onOpenRules: () -> Unit,
    onOpenGroups: () -> Unit,
    onOpenLicenses: () -> Unit,
    onBack: () -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // A compile-time constant — no PackageManager IPC on the composition path
    // (Codex on PR #68).
    val versionName = BuildConfig.VERSION_NAME
    val privacyUrl = stringResource(R.string.settings_privacy_url)
    val contactsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) onContactsGranted()
    }
    SettingsContent(
        settings = settings,
        contactsGranted = contactsGranted,
        versionName = versionName,
        onOpenPrivacyPolicy = {
            // No browser (or a stripped device) shouldn't crash Settings.
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, privacyUrl.toUri())) }
        },
        onShowCallToastChange = viewModel::setShowCallToast,
        onCallDelayChange = viewModel::setCallDelaySeconds,
        onCorrectContactNumbersChange = { enabled ->
            viewModel.setCorrectContactNumbers(enabled)
            // The correction reads the warm contact index, so ask for the
            // permission the moment the feature is switched on (SPEC:
            // READ_CONTACTS is requested when a feature needs it). A denial
            // leaves the toggle on but inert; the hint row keeps offering it.
            if (enabled && !contactsGranted) {
                contactsLauncher.launch(Manifest.permission.READ_CONTACTS)
            }
        },
        onRequestContacts = { contactsLauncher.launch(Manifest.permission.READ_CONTACTS) },
        onGuardOverseasChange = viewModel::setGuardOverseasHandsFree,
        onGuardDisabledSimChange = viewModel::setGuardDisabledSimHandsFree,
        onAnalyticsOptInChange = viewModel::setAnalyticsOptIn,
        onOpenRules = onOpenRules,
        onOpenGroups = onOpenGroups,
        onOpenLicenses = onOpenLicenses,
        onShareDebugLog = { DebugReport.share(context) },
        onBack = onBack,
    )
}

@Composable
internal fun SettingsContent(
    settings: SettingsUi = SettingsUi(),
    contactsGranted: Boolean = true,
    versionName: String = "",
    onOpenPrivacyPolicy: () -> Unit = {},
    onShowCallToastChange: (Boolean) -> Unit = {},
    onCallDelayChange: (Int) -> Unit = {},
    onCorrectContactNumbersChange: (Boolean) -> Unit = {},
    onRequestContacts: () -> Unit = {},
    onGuardOverseasChange: (Boolean) -> Unit = {},
    onGuardDisabledSimChange: (Boolean) -> Unit = {},
    onAnalyticsOptInChange: (Boolean) -> Unit = {},
    onOpenRules: () -> Unit = {},
    onOpenGroups: () -> Unit = {},
    onOpenLicenses: () -> Unit = {},
    onShareDebugLog: () -> Unit = {},
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
            // The title with a Back button at the end — a visible exit beside
            // the system back gesture, like the rules and Country groups
            // sub-screens.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.settings_title),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
            }
            // The rules list, reachable here as well as from the home's "Edit
            // rules" button.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenRules)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_rules),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.settings_rules_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // Country groups management lives here — most groups are built inline
            // from the rule editor's picker, so this is the edit/delete surface.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenGroups)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.groups_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.settings_groups_description),
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
            // Same-contact number correction (SPEC "Hands-free and Android
            // Auto safeguards"); the whole row toggles, like the rows above.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = settings.correctContactNumbers,
                        role = Role.Switch,
                        onValueChange = onCorrectContactNumbersChange,
                    )
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_local_numbers_label),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.settings_local_numbers_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.correctContactNumbers,
                    onCheckedChange = null,
                )
            }
            if (settings.correctContactNumbers && !contactsGranted) {
                // On but inert: the correction reads the warm contact index,
                // which is empty without READ_CONTACTS. Keep offering the
                // grant instead of silently doing nothing.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.settings_local_numbers_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onRequestContacts) {
                        Text(stringResource(R.string.settings_local_numbers_allow))
                    }
                }
            }
            // The hands-free call guard (SPEC "Hands-free and Android Auto
            // safeguards"): two independent blocks under one heading, both
            // strictly opt-in — the only feature allowed to stop a call.
            Text(
                text = stringResource(R.string.settings_guard_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                text = stringResource(R.string.settings_guard_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = settings.guardOverseasHandsFree,
                        role = Role.Switch,
                        onValueChange = onGuardOverseasChange,
                    )
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.settings_guard_overseas_label),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = settings.guardOverseasHandsFree,
                    onCheckedChange = null,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = settings.guardDisabledSimHandsFree,
                        role = Role.Switch,
                        onValueChange = onGuardDisabledSimChange,
                    )
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.settings_guard_disabled_sim_label),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = settings.guardDisabledSimHandsFree,
                    onCheckedChange = null,
                )
            }
            // The onboarding "Make Simmo better" choice, changeable after
            // setup — the privacy policy says this switch controls collection,
            // so a set-up user must be able to reach it.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = settings.analyticsOptIn,
                        role = Role.Switch,
                        onValueChange = onAnalyticsOptInChange,
                    )
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.analytics_opt_in_label),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.analytics_opt_in_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.analyticsOptIn,
                    onCheckedChange = null,
                )
            }
            // At the foot of the page: the privacy policy link (opens in the
            // browser), the open-source licenses screen, then the app version.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenPrivacyPolicy)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.settings_privacy_policy),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenLicenses)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.settings_licenses),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
            }
            // Shares the recent routing decisions and errors (SimmoDebugLog)
            // plus build/device info so an unexpected call can be diagnosed —
            // the redirection service runs in the background, so this is the
            // only way to read what it decided.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onShareDebugLog)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_share_logs),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.settings_share_logs_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = stringResource(R.string.settings_version, versionName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }
}
