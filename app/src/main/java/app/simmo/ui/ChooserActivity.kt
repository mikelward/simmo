package app.simmo.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.telecom.TelecomManager
import android.telephony.euicc.EuiccManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.simmo.SimmoApp
import app.simmo.domain.HeldCall
import app.simmo.domain.PassToken
import app.simmo.domain.Rule
import app.simmo.domain.RuleAction
import app.simmo.domain.SimRef
import app.simmo.domain.countryMatcher
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The Ask chooser (SPEC "The chooser (Ask flow)"). The redirection service
 * cancels the in-flight call and launches this; confirming re-places the call
 * on the chosen SIM via TelecomManager.placeCall, minting a pass token first
 * so the re-placed call sails through the service (SPEC "Redirect-loop
 * guard"). Everything shown is read from the warm in-memory snapshot in
 * onCreate — no I/O before the first frame.
 */
class ChooserActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as SimmoApp
        val handle = intent?.data
        if (handle == null) {
            finish()
            return
        }
        val dialedNumber = handle.schemeSpecificPart.orEmpty()
        val country = app.detectCountry(dialedNumber)
        val skippedSims = decodeSkippedSims(intent.getStringExtra(EXTRA_SKIPPED_SIMS))
        if (skippedSims.isNotEmpty()) {
            // A rule wanted a disabled SIM: park the call so that if the user
            // wanders off to enable it, the "SIM is now active — place the
            // call?" notification can offer this call back (SPEC
            // "Disabled-SIM assist" step 3). Cleared when any call is placed.
            app.heldCalls.park(
                HeldCall(
                    handleUri = handle.toString(),
                    wantedSims = skippedSims,
                    parkedAtMillis = System.currentTimeMillis(),
                ),
            )
        }
        setContent {
            MaterialTheme {
                // Rebuilt from the live SIM flow (its current value is warm,
                // so the first frame is still instant): when the user jumps
                // to SIM settings, enables the disabled SIM, and comes back,
                // the SIM's call button appears and its note clears.
                val sims by app.assembler.simsAndAccounts().collectAsStateWithLifecycle()
                val state = remember(sims) {
                    buildChooserUiState(
                        dialedNumber = dialedNumber,
                        country = country,
                        activeSims = sims.activeSims,
                        skippedInactiveSims = skippedSims,
                    )
                }
                // A tap made before CALL_PHONE is granted parks the choice,
                // asks, and completes it on grant (SPEC: CALL_PHONE is
                // requested on first use). Plain remember: if the process
                // dies mid-permission-dialog the canceled call is long gone,
                // so there is nothing worth restoring.
                var pending by remember { mutableStateOf<PendingChoice?>(null) }
                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { granted ->
                    val choice = pending
                    pending = null
                    if (granted && choice != null) {
                        placeCall(handle, choice.target, choice.rememberRule, state.rememberRegion)
                        finish()
                    }
                }
                // The SIM-settings jump is the moment the held-call offer
                // becomes relevant, so it's where POST_NOTIFICATIONS is asked
                // for (once); settings open either way.
                val notificationLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { openSimSettings() }
                ChooserContent(
                    state = state,
                    onPlace = { target, rememberRule ->
                        if (hasCallPermission()) {
                            placeCall(handle, target, rememberRule, state.rememberRegion)
                            finish()
                        } else {
                            pending = PendingChoice(target, rememberRule)
                            permissionLauncher.launch(Manifest.permission.CALL_PHONE)
                        }
                    },
                    onOpenSimSettings = {
                        if (needsNotificationPermission()) {
                            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            openSimSettings()
                        }
                    },
                    // The carrier call was canceled before this screen opened;
                    // canceling here just abandons the call, per SPEC.
                    onCancel = { finish() },
                )
            }
        }
    }

    private data class PendingChoice(val target: ChooserTargetUi, val rememberRule: Boolean)

    private fun hasCallPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED

    private fun needsNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED

    /**
     * The enable assist's jump (SPEC "Disabled-SIM assist"): apps cannot
     * switch eSIM profiles themselves (carrier privileges), so the best we
     * can do is land the user on the system's embedded-SIM management
     * screen, falling back to the generic network settings where no eUICC
     * screen exists. Which deep link is best per Android version is on the
     * device-QA list (TODO.md Phase 4).
     */
    private fun openSimSettings() {
        val candidates = listOf(
            Intent(EuiccManager.ACTION_MANAGE_EMBEDDED_SUBSCRIPTIONS),
            Intent(Settings.ACTION_WIRELESS_SETTINGS),
        )
        for (candidate in candidates) {
            try {
                startActivity(candidate)
                return
            } catch (_: ActivityNotFoundException) {
                // Try the next, more generic screen.
            }
        }
        Log.e(TAG, "No SIM settings screen available on this device")
    }

    private fun placeCall(
        handle: Uri,
        target: ChooserTargetUi,
        rememberRule: Boolean,
        rememberRegion: String?,
    ) {
        val app = application as SimmoApp
        // The user resolved this call attempt; a later SIM activation must
        // not re-offer it.
        app.heldCalls.clear()
        if (rememberRule && rememberRegion != null) {
            // On appScope: the write must outlive this finishing activity.
            app.appScope.launch {
                app.stateHolders().filterNotNull().first().updateRules {
                    it.withRuleAdded(
                        Rule(countryMatcher(listOf(rememberRegion)), RuleAction.UseSim(target.sim)),
                    )
                }
            }
        }
        // Token before placing: the new call consults the service immediately,
        // and must pass through unmodified instead of looping (SPEC
        // "Redirect-loop guard").
        app.passTokens.add(
            PassToken(
                dialedNumber = handle.schemeSpecificPart.orEmpty(),
                account = target.account,
                expiresAtMillis = System.currentTimeMillis() + PASS_TOKEN_TTL_MILLIS,
            ),
        )
        val accountHandle = app.assembler.handleFor(target.account)
        if (accountHandle == null) {
            // The SIM vanished between the chooser opening and the tap; the
            // original call is already canceled, so just don't re-place onto
            // a dead handle.
            Log.e(TAG, "Chosen phone account no longer available; not re-placing")
            return
        }
        val extras = Bundle().apply {
            putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, accountHandle)
        }
        try {
            getSystemService(TelecomManager::class.java).placeCall(handle, extras)
        } catch (e: SecurityException) {
            // CALL_PHONE was checked above but can be revoked concurrently.
            Log.e(TAG, "Re-placing the call failed", e)
        }
    }

    companion object {
        private const val TAG = "SimmoChooser"
        private const val EXTRA_SKIPPED_SIMS = "app.simmo.extra.SKIPPED_SIMS"

        /** How long the re-placed call has to reach the service. */
        private const val PASS_TOKEN_TTL_MILLIS = 30_000L

        private val extrasJson = Json { ignoreUnknownKeys = true }

        /** Launch intent for [handle]; from the service, add NEW_TASK. */
        fun launchIntent(context: Context, handle: Uri, skippedInactiveSims: List<SimRef>): Intent =
            Intent(context, ChooserActivity::class.java)
                .setData(handle)
                .putExtra(
                    EXTRA_SKIPPED_SIMS,
                    extrasJson.encodeToString(ListSerializer(SimRef.serializer()), skippedInactiveSims),
                )

        private fun decodeSkippedSims(encoded: String?): List<SimRef> =
            encoded?.let {
                runCatching {
                    extrasJson.decodeFromString(ListSerializer(SimRef.serializer()), it)
                }.getOrNull()
            }.orEmpty()
    }
}
