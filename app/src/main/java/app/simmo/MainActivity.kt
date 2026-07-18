package app.simmo

import android.Manifest
import android.app.role.RoleManager
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.activity.compose.BackHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import app.simmo.ui.EditorTarget
import app.simmo.ui.RuleEditorScreen
import app.simmo.ui.RulesScreen
import app.simmo.ui.RulesViewModel
import app.simmo.ui.SimRegistryScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                var phoneGranted by remember { mutableStateOf(isPhonePermissionGranted()) }
                var contactsGranted by remember { mutableStateOf(isContactsPermissionGranted()) }
                var ready by remember {
                    mutableStateOf(isRedirectionRoleHeld() && isPhonePermissionGranted())
                }
                // Grants can change while we're backgrounded (revoked in
                // Settings, or granted there mid-onboarding): re-check on
                // every resume so the screen never shows a stale state. A
                // grant made in Settings bypasses the permission launcher, so
                // the refreshes must also run on that transition.
                OnResume {
                    val nowGranted = isPhonePermissionGranted()
                    // Any transition invalidates the cached SIM snapshot: a
                    // grant must populate it, a revocation must clear it (the
                    // refresh reads back empty without the permission) so the
                    // redirection service never routes on stale handles.
                    if (nowGranted != phoneGranted) {
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
                    contactsGranted = contactsNowGranted
                    ready = isRedirectionRoleHeld() && nowGranted
                }
                if (ready) {
                    val vm = viewModel<RulesViewModel>()
                    // Routes live in the ViewModel so a rotation mid-edit (or
                    // mid-registry-browse) keeps the user where they were.
                    val target by vm.editorTarget.collectAsStateWithLifecycle()
                    val registryOpen by vm.registryOpen.collectAsStateWithLifecycle()
                    val editing = target
                    when {
                        // The editor wins: it can only be open from the rules
                        // list, and registry state is preserved beneath it.
                        editing != null -> {
                            BackHandler { vm.closeEditor() }
                            RuleEditorScreen(
                                viewModel = vm,
                                target = editing,
                                onDone = vm::closeEditor,
                            )
                        }

                        registryOpen -> SimRegistryScreen(
                            viewModel = vm,
                            onBack = vm::closeSimRegistry,
                        )

                        else -> RulesScreen(
                            viewModel = vm,
                            onAddRule = vm::openNewRule,
                            onEditRule = vm::openEditRule,
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

    private fun isRedirectionRoleHeld(): Boolean =
        getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_CALL_REDIRECTION)

    private fun isPhonePermissionGranted(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED

    private fun isNotificationsGranted(): Boolean =
        android.os.Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun isContactsPermissionGranted(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    private fun isCallPermissionGranted(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED
}

/**
 * Phase 2 onboarding: the two grants Simmo cannot work without, plus the
 * optional notifications one (SIM-assist nudges) that never gates readiness.
 * The rules list replaces this as the home screen in Phase 3.
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
    analyticsOptIn: Boolean = true,
    onAnalyticsOptInChange: (Boolean) -> Unit = {},
) {
    var roleHeld by remember { mutableStateOf(isRoleHeld()) }
    var phoneGranted by remember { mutableStateOf(isPhonePermissionGranted()) }
    var notificationsGranted by remember { mutableStateOf(isNotificationsGranted()) }
    var callGranted by remember { mutableStateOf(isCallPermissionGranted()) }
    OnResume {
        roleHeld = isRoleHeld()
        notificationsGranted = isNotificationsGranted()
        callGranted = isCallPermissionGranted()
        val nowGranted = isPhonePermissionGranted()
        // Any transition made in Settings (grant or revoke) must refresh the
        // telephony cache, same as the launcher path.
        if (nowGranted != phoneGranted) onPhonePermissionGranted()
        phoneGranted = nowGranted
        if (roleHeld && phoneGranted) onAllGranted()
    }

    val roleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        roleHeld = isRoleHeld()
        if (roleHeld && phoneGranted) onAllGranted()
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        phoneGranted = granted
        if (granted) onPhonePermissionGranted()
        if (roleHeld && phoneGranted) onAllGranted()
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

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
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
            GrantRow(
                granted = roleHeld,
                label = stringResource(R.string.onboarding_role_label),
                buttonText = stringResource(R.string.onboarding_role_button),
                onRequest = { roleLauncher.launch(requestRoleIntent()) },
            )
            GrantRow(
                granted = phoneGranted,
                label = stringResource(R.string.onboarding_phone_permission_label),
                buttonText = stringResource(R.string.onboarding_phone_permission_button),
                onRequest = { permissionLauncher.launch(Manifest.permission.READ_PHONE_STATE) },
            )
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
                        text = stringResource(R.string.onboarding_analytics_label),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = stringResource(R.string.onboarding_analytics_description),
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
                Text(
                    text = stringResource(R.string.onboarding_ready),
                    style = MaterialTheme.typography.bodyMedium,
                )
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
