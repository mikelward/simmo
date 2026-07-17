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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                OnboardingScreen(
                    isRoleHeld = ::isRedirectionRoleHeld,
                    isPhonePermissionGranted = ::isPhonePermissionGranted,
                    requestRoleIntent = {
                        getSystemService(RoleManager::class.java)
                            .createRequestRoleIntent(RoleManager.ROLE_CALL_REDIRECTION)
                    },
                    onPhonePermissionGranted = {
                        // The startup telephony read ran before the grant and
                        // cached nothing; refresh so rules work immediately.
                        (application as SimmoApp).refreshTelephony()
                    },
                )
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
) {
    var roleHeld by remember { mutableStateOf(isRoleHeld()) }
    var phoneGranted by remember { mutableStateOf(isPhonePermissionGranted()) }

    val roleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { roleHeld = isRoleHeld() }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        phoneGranted = granted
        if (granted) onPhonePermissionGranted()
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            val appName = stringResource(R.string.app_name)
            val annotatedAppName = buildAnnotatedString {
                val moIndex = appName.indexOf("mo")
                if (moIndex != -1) {
                    append(appName.substring(0, moIndex))
                    withStyle(style = SpanStyle(color = Color(0xFFE91E63))) {
                        append(appName.substring(moIndex))
                    }
                } else {
                    append(appName)
                }
            }
            Text(
                text = annotatedAppName,
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
