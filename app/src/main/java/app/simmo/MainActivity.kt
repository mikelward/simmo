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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.activity.compose.BackHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.simmo.ui.EditorTarget
import app.simmo.ui.RuleEditorScreen
import app.simmo.ui.RulesScreen
import app.simmo.ui.RulesViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                var phoneGranted by remember { mutableStateOf(isPhonePermissionGranted()) }
                var ready by remember {
                    mutableStateOf(isRedirectionRoleHeld() && isPhonePermissionGranted())
                }
                // Grants can change while we're backgrounded (revoked in
                // Settings, or granted there mid-onboarding): re-check on
                // every resume so the screen never shows a stale state. A
                // grant made in Settings bypasses the permission launcher, so
                // the telephony refresh must also run on that transition.
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
                    ready = isRedirectionRoleHeld() && nowGranted
                }
                if (ready) {
                    val vm = viewModel<RulesViewModel>()
                    // Editor route lives in the ViewModel so a rotation mid-edit
                    // keeps the user on the editor instead of the list.
                    val target by vm.editorTarget.collectAsStateWithLifecycle()
                    val editing = target
                    if (editing == null) {
                        RulesScreen(
                            viewModel = vm,
                            onAddRule = vm::openNewRule,
                            onEditRule = vm::openEditRule,
                        )
                    } else {
                        BackHandler { vm.closeEditor() }
                        RuleEditorScreen(
                            viewModel = vm,
                            target = editing,
                            onDone = vm::closeEditor,
                        )
                    }
                } else {
                    OnboardingScreen(
                        isRoleHeld = ::isRedirectionRoleHeld,
                        isPhonePermissionGranted = ::isPhonePermissionGranted,
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
}

/**
 * Phase 2 onboarding: the two grants Simmo cannot work without. The rules list
 * replaces this as the home screen in Phase 3.
 */
@Composable
internal fun OnboardingScreen(
    isRoleHeld: () -> Boolean,
    isPhonePermissionGranted: () -> Boolean,
    requestRoleIntent: () -> android.content.Intent,
    onPhonePermissionGranted: () -> Unit,
    onAllGranted: () -> Unit = {},
) {
    var roleHeld by remember { mutableStateOf(isRoleHeld()) }
    var phoneGranted by remember { mutableStateOf(isPhonePermissionGranted()) }
    OnResume {
        roleHeld = isRoleHeld()
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
