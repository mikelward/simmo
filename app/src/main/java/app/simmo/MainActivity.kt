package app.simmo

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import app.simmo.ui.DataRuleEditorScreen
import app.simmo.ui.EditorTarget
import app.simmo.ui.GroupsScreen
import app.simmo.ui.LicensesScreen
import app.simmo.ui.RuleEditorScreen
import app.simmo.ui.RulesScreen
import app.simmo.ui.RulesViewModel
import app.simmo.ui.SettingsScreen
import app.simmo.ui.SimRegistryScreen
import app.simmo.ui.openSimSettings

class MainActivity : ComponentActivity() {

    /**
     * Set when the Quick Settings tile asked for the SIMs screen; consumed by
     * composition once the app is past onboarding. Only a fresh launch reads
     * the creation intent — on recreation the intent is redelivered but the
     * user may have navigated elsewhere since, so the pending flag itself is
     * carried in the saved state instead: a tile tap that lands on onboarding
     * must survive rotation or process death there, where the ViewModel that
     * would hold the route doesn't exist yet (Codex on PR #22).
     */
    private var manageSimsRequested by mutableStateOf(false)

    /**
     * Set when the data-watch notification asked for the data rules screen
     * (its Rules action and body tap); same lifecycle rules as
     * [manageSimsRequested] — a recreation must not resurrect a consumed
     * request, and a tap that lands on onboarding survives there.
     */
    private var dataRulesRequested by mutableStateOf(false)

    /**
     * Set when the new-SIM notification asked for the calling rules list (where
     * its "add a rule for this SIM" prompt shows); same lifecycle rules as
     * [dataRulesRequested].
     */
    private var callingRulesRequested by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        manageSimsRequested = pendingManageSimsRequest(savedInstanceState, intent?.action)
        dataRulesRequested = pendingRequest(
            savedInstanceState,
            STATE_DATA_RULES_REQUESTED,
            intent?.action,
            ACTION_DATA_RULES,
        )
        callingRulesRequested = pendingRequest(
            savedInstanceState,
            STATE_CALLING_RULES_REQUESTED,
            intent?.action,
            ACTION_CALLING_RULES,
        )
        setContent {
            MaterialTheme {
                var phoneGranted by remember { mutableStateOf(isPhonePermissionGranted()) }
                var contactsGranted by remember { mutableStateOf(isContactsPermissionGranted()) }
                // rememberSaveable, not remember: a rotation (or process death
                // with saved state) mid-onboarding must restore the in-progress
                // false rather than recompute from the grants — otherwise
                // granting the required rows and rotating would skip straight
                // past the optional rows, making Continue not actually the only
                // exit (Codex on PR #32). A fresh launch has no saved state and
                // computes from the grants as before, so returning users still
                // land on the rules list directly.
                var ready by rememberSaveable {
                    mutableStateOf(isRedirectionRoleHeld() && isPhonePermissionGranted())
                }
                // Grants can change while we're backgrounded (revoked in
                // Settings, or granted there mid-onboarding): re-check on
                // every resume so the screen never shows a stale state. A
                // grant made in Settings bypasses the permission launcher, so
                // the refreshes must also run on that transition.
                OnResume {
                    val nowGranted = isPhonePermissionGranted()
                    // A resume only ever revokes readiness (role or permission
                    // lost → back to onboarding). It never grants it: while
                    // onboarding is showing, the user leaves via its Skip/Continue
                    // button, not the moment the required grants land — the
                    // permission dialogs themselves trigger resumes, and
                    // auto-advancing would yank the optional rows away.
                    ready = ready && isRedirectionRoleHeld() && nowGranted
                    // Refresh the telephony cache on every granted resume, not
                    // just permission transitions: calling accounts (SIP
                    // providers, VoIP apps) can be enabled or disabled in system
                    // settings with no subscription-change event — their
                    // register/unregister broadcast goes only to the default
                    // dialer — so returning to Simmo is the reliable moment to
                    // pick the change up (Codex on PR #39). A revocation must
                    // refresh too: the read comes back empty, clearing the
                    // snapshot so the service never routes on stale handles.
                    if (nowGranted || nowGranted != phoneGranted) {
                        (application as SimmoApp).refreshTelephony()
                    }
                    phoneGranted = nowGranted
                    // READ_CONTACTS can likewise change in Settings (granted
                    // after "Don't ask again", or revoked) while the process
                    // survives, bypassing the in-app launcher. On grant, rebuild
                    // the warm index so saved WhatsApp hand-off rules start
                    // matching at once; on revocation, clear it so they stop
                    // matching cached rows instead of waiting for an unrelated
                    // refresh.
                    val contactsNowGranted = isContactsPermissionGranted()
                    if (contactsNowGranted != contactsGranted) {
                        val app = application as SimmoApp
                        if (contactsNowGranted) app.refreshContacts() else app.clearContacts()
                    }
                    // Register the change observer on every granted resume, not
                    // only on a transition: a grant made in system Settings is
                    // invisible to a freshly recreated activity (both state vars
                    // initialize already-granted, so no transition fires), and
                    // the telephony-refresh fallback is itself gated on
                    // READ_PHONE_STATE — so a contacts-only grant while phone
                    // access is denied would otherwise strand the observer
                    // (Codex on PR #74). Idempotent, so repeated resumes are free.
                    if (contactsNowGranted) {
                        (application as SimmoApp).registerContactsObserver()
                    }
                    contactsGranted = contactsNowGranted
                }
                if (ready) {
                    val vm = viewModel<RulesViewModel>()
                    val activity = LocalActivity.current
                    // Leaving the app (or screen-off) finalizes any soft-deleted
                    // rules/groups (SPEC "Calling rules"): the Undo affordance
                    // lives only while the user is looking at the list. A crash
                    // doesn't run ON_STOP, so a soft-delete survives it and stays
                    // undoable. A configuration change (rotation, locale, dark
                    // mode) also stops the activity but isn't a leave — the
                    // recreated screen restores the same tombstones, so don't
                    // purge them then (Codex on PR #61).
                    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
                        if (activity?.isChangingConfigurations != true) vm.purgePendingRemovals()
                    }
                    // Keep the SIMs screen's data chips (and the Data-tab triage
                    // card) live while the app is foregrounded: an automatic-data
                    // switch refreshes telephony. Foreground-only — registered on
                    // start, dropped on stop — never a resident callback (SPEC
                    // "SIMs screen"); elsewhere the chips refresh on resume.
                    LifecycleStartEffect(Unit) {
                        (application as SimmoApp).startActiveDataWatch()
                        onStopOrDispose { (application as SimmoApp).stopActiveDataWatch() }
                    }
                    // Routes live in the ViewModel so a rotation mid-edit (or
                    // mid-registry-browse) keeps the user where they were.
                    val target by vm.editorTarget.collectAsStateWithLifecycle()
                    val rulesOpen by vm.rulesOpen.collectAsStateWithLifecycle()
                    val groupsOpen by vm.groupsOpen.collectAsStateWithLifecycle()
                    val settingsOpen by vm.settingsOpen.collectAsStateWithLifecycle()
                    val licensesOpen by vm.licensesOpen.collectAsStateWithLifecycle()
                    // Same deal as the chooser's SIM-settings jump: it's the
                    // moment the held-call offer becomes relevant — this
                    // registry can sit directly above a chooser holding a
                    // parked call (Codex on PR #22) — so POST_NOTIFICATIONS
                    // is asked for here too; settings open either way.
                    val notificationsLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission(),
                    ) { openSimSettings() }
                    val openSimSettingsAskingNotifications = {
                        if (isNotificationsGranted()) {
                            openSimSettings()
                        } else {
                            notificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                    // The tile's request waits here until onboarding is done: a
                    // tap before the grants exist still lands on the SIMs home
                    // (now the app's home screen) once they're in place. Popping
                    // any open sub-screen reveals it.
                    LaunchedEffect(manageSimsRequested) {
                        if (manageSimsRequested) {
                            manageSimsRequested = false
                            vm.goHome()
                        }
                    }
                    // The data-watch notification's request likewise waits out
                    // onboarding, then lands on the data rules list.
                    LaunchedEffect(dataRulesRequested) {
                        if (dataRulesRequested) {
                            dataRulesRequested = false
                            vm.openDataRules()
                        }
                    }
                    // The new-SIM notification lands on the rules Calling list,
                    // where its "add a rule for this SIM" prompt card also shows.
                    LaunchedEffect(callingRulesRequested) {
                        if (callingRulesRequested) {
                            callingRulesRequested = false
                            vm.openCallingRules()
                        }
                    }
                    val editing = target
                    val dataEditing by vm.dataEditorTarget.collectAsStateWithLifecycle()
                    val editingData = dataEditing
                    when {
                        // The editor wins: it can only be open from the rules
                        // list, whose state is preserved beneath it.
                        editing != null -> {
                            BackHandler { vm.closeEditor() }
                            RuleEditorScreen(
                                viewModel = vm,
                                target = editing,
                                onDone = vm::closeEditor,
                            )
                        }

                        editingData != null -> {
                            BackHandler { vm.closeDataEditor() }
                            DataRuleEditorScreen(
                                viewModel = vm,
                                target = editingData,
                                onDone = vm::closeDataEditor,
                            )
                        }

                        // A Settings sub-screen: both the header Back button and
                        // the system back gesture land back on Settings (which
                        // stays open beneath it), never finishing the activity.
                        groupsOpen -> GroupsScreen(
                            viewModel = vm,
                            onBack = vm::closeGroups,
                        )

                        // Above Settings, like the groups screen: opened from it,
                        // so Back lands back on Settings.
                        licensesOpen -> LicensesScreen(onBack = vm::closeLicenses)

                        settingsOpen -> SettingsScreen(
                            viewModel = vm,
                            contactsGranted = contactsGranted,
                            // Same handler as onboarding's contacts row: sync
                            // the router's copy and build the warm index the
                            // correction reads.
                            onContactsGranted = {
                                contactsGranted = isContactsPermissionGranted()
                                (application as SimmoApp).refreshContacts()
                            },
                            onOpenRules = vm::openRules,
                            onOpenGroups = vm::openGroups,
                            onOpenLicenses = vm::openLicenses,
                            onBack = vm::closeSettings,
                        )

                        // The rules list, a sub-screen of the SIMs home now:
                        // Back (header and gesture) returns home; Apply still
                        // commits pending deletes in place. Leaving the app (from
                        // the home) is what purges, as before.
                        rulesOpen -> {
                            BackHandler { vm.closeRules() }
                            RulesScreen(
                                viewModel = vm,
                                onAddRule = vm::openNewRule,
                                onEditRule = vm::openEditRule,
                                onBack = vm::closeRules,
                            )
                        }

                        // The home screen (SPEC "SIMs screen"): back finishes the
                        // activity (leaving purges any pending deletes on ON_STOP).
                        else -> SimRegistryScreen(
                            viewModel = vm,
                            onBack = { activity?.finish() },
                            onOpenSimSettings = openSimSettingsAskingNotifications,
                            onEditRules = vm::openRules,
                            onOpenSettings = vm::openSettings,
                        )
                    }
                } else {
                    // Mirrors the persisted opt-in once the eager load lands;
                    // until then the stored default (opted in) is shown.
                    var analyticsOptIn by remember { mutableStateOf(true) }
                    LaunchedEffect(Unit) {
                        val app = application as SimmoApp
                        app.stateHolders().filterNotNull().first().state
                            .filterNotNull()
                            .collect { analyticsOptIn = it.analyticsOptIn }
                    }
                    OnboardingScreen(
                        isRoleHeld = ::isRedirectionRoleHeld,
                        isPhonePermissionGranted = ::isPhonePermissionGranted,
                        isNotificationsGranted = ::isNotificationsGranted,
                        isCallPermissionGranted = ::isCallPermissionGranted,
                        isContactsPermissionGranted = ::isContactsPermissionGranted,
                        onContactsPermissionGranted = {
                            // Sync the router's copy and build the warm index
                            // now — the WhatsApp hand-off and the picker's
                            // suggested countries read it.
                            contactsGranted = isContactsPermissionGranted()
                            (application as SimmoApp).refreshContacts()
                        },
                        requestRoleIntent = {
                            getSystemService(RoleManager::class.java)
                                .createRequestRoleIntent(RoleManager.ROLE_CALL_REDIRECTION)
                        },
                        onPhonePermissionGranted = {
                            // Keep the router's copy in sync so a later
                            // revocation in Settings is seen as a transition,
                            // and refresh: the startup telephony read ran
                            // before the grant and cached nothing.
                            phoneGranted = isPhonePermissionGranted()
                            (application as SimmoApp).refreshTelephony()
                        },
                        onAllGranted = { ready = true },
                        analyticsOptIn = analyticsOptIn,
                        onAnalyticsOptInChange = { enabled ->
                            analyticsOptIn = enabled
                            (application as SimmoApp).setAnalyticsOptIn(enabled)
                        },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // The tile (or notification) tapped while this activity is
        // foreground: SINGLE_TOP routes the relaunch here instead of
        // stacking a second instance.
        if (intent.action == ACTION_MANAGE_SIMS) {
            manageSimsRequested = true
        }
        if (intent.action == ACTION_DATA_RULES) {
            dataRulesRequested = true
        }
        if (intent.action == ACTION_CALLING_RULES) {
            callingRulesRequested = true
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_MANAGE_SIMS_REQUESTED, manageSimsRequested)
        outState.putBoolean(STATE_DATA_RULES_REQUESTED, dataRulesRequested)
        outState.putBoolean(STATE_CALLING_RULES_REQUESTED, callingRulesRequested)
    }

    private fun isRedirectionRoleHeld(): Boolean =
        getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_CALL_REDIRECTION)

    private fun isPhonePermissionGranted(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED

    private fun isNotificationsGranted(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun isContactsPermissionGranted(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    private fun isCallPermissionGranted(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        /** Sent by the Quick Settings tile: open straight onto the SIMs screen. */
        const val ACTION_MANAGE_SIMS = "app.simmo.action.MANAGE_SIMS"

        /** Sent by the data-watch notification: land on the data rules list. */
        const val ACTION_DATA_RULES = "app.simmo.action.DATA_RULES"

        /** Sent by the new-SIM notification: land on the calling rules list. */
        const val ACTION_CALLING_RULES = "app.simmo.action.CALLING_RULES"

        private const val STATE_MANAGE_SIMS_REQUESTED = "manage_sims_requested"
        private const val STATE_DATA_RULES_REQUESTED = "data_rules_requested"
        private const val STATE_CALLING_RULES_REQUESTED = "calling_rules_requested"

        /**
         * Whether a tile request is pending at creation. A fresh launch reads
         * the intent; a recreation reads the saved flag only — the redelivered
         * intent must not resurrect a request that was already consumed.
         */
        internal fun pendingManageSimsRequest(saved: Bundle?, intentAction: String?): Boolean =
            pendingRequest(saved, STATE_MANAGE_SIMS_REQUESTED, intentAction, ACTION_MANAGE_SIMS)

        /** The same consumed-request rules, for any launch action. */
        internal fun pendingRequest(
            saved: Bundle?,
            stateKey: String,
            intentAction: String?,
            action: String,
        ): Boolean = saved?.getBoolean(stateKey, false) ?: (intentAction == action)
    }
}

/**
 * Phase 2 onboarding: the two grants Simmo cannot work without, plus the
 * optional ones (failure notices, one-tap redial, contact-app hand-off) that
 * never gate readiness. Once the required grants are held, the user leaves via
 * the Skip/Continue buttons — never automatically, which would yank the optional rows
 * away mid-read (the permission dialogs themselves trigger resumes). The rules
 * list replaces this as the home screen in Phase 3.
 */
@Composable
internal fun OnboardingScreen(
    isRoleHeld: () -> Boolean,
    isPhonePermissionGranted: () -> Boolean,
    requestRoleIntent: () -> android.content.Intent,
    onPhonePermissionGranted: () -> Unit,
    onAllGranted: () -> Unit = {},
    isNotificationsGranted: () -> Boolean = { true },
    isCallPermissionGranted: () -> Boolean = { true },
    isContactsPermissionGranted: () -> Boolean = { true },
    onContactsPermissionGranted: () -> Unit = {},
    analyticsOptIn: Boolean = true,
    onAnalyticsOptInChange: (Boolean) -> Unit = {},
) {
    var roleHeld by remember { mutableStateOf(isRoleHeld()) }
    var phoneGranted by remember { mutableStateOf(isPhonePermissionGranted()) }
    var notificationsGranted by remember { mutableStateOf(isNotificationsGranted()) }
    var callGranted by remember { mutableStateOf(isCallPermissionGranted()) }
    var contactsGranted by remember { mutableStateOf(isContactsPermissionGranted()) }
    OnResume {
        roleHeld = isRoleHeld()
        notificationsGranted = isNotificationsGranted()
        callGranted = isCallPermissionGranted()
        contactsGranted = isContactsPermissionGranted()
        val nowGranted = isPhonePermissionGranted()
        // Any transition made in Settings (grant or revoke) must refresh the
        // telephony cache, same as the launcher path.
        if (nowGranted != phoneGranted) onPhonePermissionGranted()
        phoneGranted = nowGranted
    }

    val roleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        roleHeld = isRoleHeld()
    }
    // READ_PHONE_NUMBERS rides along with READ_PHONE_STATE (same Phone
    // permission group — one system dialog): it unlocks other apps' calling-
    // account labels ("SIP – work"). Readiness keys off READ_PHONE_STATE
    // alone; without the extra grant, account labels degrade to app names.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val granted = grants[Manifest.permission.READ_PHONE_STATE] == true
        phoneGranted = granted
        if (granted) onPhonePermissionGranted()
    }
    val notificationsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notificationsGranted = granted
    }
    val callLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        callGranted = granted
    }
    val contactsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        contactsGranted = granted
        if (granted) onContactsPermissionGranted()
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // Scrollable so the exit buttons stay reachable on
                // compact screens and large font/display scales (Codex on
                // PR #32); centering still applies when everything fits.
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                text = stringResource(R.string.onboarding_intro),
                style = MaterialTheme.typography.bodyMedium,
            )
            // Seeing the SIMs comes before routing between them — the reading
            // order mirrors what Simmo actually does.
            GrantRow(
                granted = phoneGranted,
                label = stringResource(R.string.onboarding_phone_permission_label),
                buttonText = stringResource(R.string.onboarding_phone_permission_button),
                onRequest = {
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.READ_PHONE_STATE,
                            Manifest.permission.READ_PHONE_NUMBERS,
                        ),
                    )
                },
            )
            GrantRow(
                granted = roleHeld,
                label = stringResource(R.string.onboarding_role_label),
                buttonText = stringResource(R.string.onboarding_role_button),
                onRequest = { roleLauncher.launch(requestRoleIntent()) },
            )
            // Optional: the number→contact reverse lookup behind the WhatsApp
            // hand-off action (and the country picker's suggested bucket).
            // Right after the calling rows — it reads as another calling
            // ability, not plumbing.
            GrantRow(
                granted = contactsGranted,
                label = stringResource(R.string.onboarding_contacts_permission_label),
                buttonText = stringResource(R.string.onboarding_contacts_permission_button),
                onRequest = { contactsLauncher.launch(Manifest.permission.READ_CONTACTS) },
            )
            // Optional: the failure notices and their shortcuts — "couldn't
            // open <app>" with Redial, and the SIM-assist nudges.
            GrantRow(
                granted = notificationsGranted,
                label = stringResource(R.string.onboarding_notifications_label),
                buttonText = stringResource(R.string.onboarding_notifications_button),
                onRequest = {
                    notificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                },
            )
            // Optional: lets a "Couldn't open <app>" notification's Redial re-place
            // the call in one tap (ACTION_CALL); without it Redial opens the dialer.
            GrantRow(
                granted = callGranted,
                label = stringResource(R.string.onboarding_call_permission_label),
                buttonText = stringResource(R.string.onboarding_call_permission_button),
                onRequest = { callLauncher.launch(Manifest.permission.CALL_PHONE) },
            )
            // The row itself is the toggle (and the Switch's own handler is
            // null), so TalkBack reads label, description, and state as one
            // switch instead of an unlabeled control (Codex on PR #27).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = analyticsOptIn,
                        role = Role.Switch,
                        onValueChange = onAnalyticsOptInChange,
                    ),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.analytics_opt_in_label),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = stringResource(R.string.analytics_opt_in_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = analyticsOptIn,
                    onCheckedChange = null,
                )
            }
            if (roleHeld && phoneGranted) {
                // Two exits once the required grants are held: Skip leaves
                // with whatever is granted, Continue is the affirmative one
                // and stays disabled until every optional grant and the
                // analytics opt-in are on.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    OutlinedButton(onClick = onAllGranted) {
                        Text(stringResource(R.string.onboarding_skip))
                    }
                    Button(
                        onClick = onAllGranted,
                        enabled = notificationsGranted && callGranted &&
                            contactsGranted && analyticsOptIn,
                    ) {
                        Text(stringResource(R.string.onboarding_continue))
                    }
                }
            }
        }
    }
}

/** Runs [block] on every ON_RESUME of the current lifecycle. */
@Composable
private fun OnResume(block: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) block()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

@Composable
private fun GrantRow(
    granted: Boolean,
    label: String,
    buttonText: String,
    onRequest: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        if (granted) {
            Text(
                text = stringResource(R.string.onboarding_granted),
                style = MaterialTheme.typography.labelLarge,
            )
        } else {
            Button(onClick = onRequest) { Text(buttonText) }
        }
    }
}
