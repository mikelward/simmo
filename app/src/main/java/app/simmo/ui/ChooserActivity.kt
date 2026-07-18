package app.simmo.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.simmo.SimmoApp
import app.simmo.domain.CountryVerdict
import app.simmo.domain.HeldCall
import app.simmo.domain.NumberCorrection
import app.simmo.domain.Rule
import app.simmo.domain.RuleAction
import app.simmo.domain.SimRef
import app.simmo.domain.countryMatcher
import app.simmo.retryUntilDone
import app.simmo.telecom.replaceCallOrOpenDialer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
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
        val skippedSims = decodeSkippedSims(intent.getStringExtra(EXTRA_SKIPPED_SIMS))
        val numberCorrection = decodeNumberCorrection(intent.getStringExtra(EXTRA_NUMBER_CORRECTION))
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
                // Country detection is warm when launched mid-call-attempt,
                // but the held-call and local-number notifications can
                // cold-start the process minutes later (Codex on PR #44):
                // detect off the main thread and let the label pop in, never
                // load parser metadata during composition. Costs the label
                // one frame on warm launches; the number renders instantly.
                val country by produceState<CountryVerdict>(CountryVerdict.Undetermined, dialedNumber) {
                    value = withContext(Dispatchers.Default) { app.detectCountry(dialedNumber) }
                }
                // Rebuilt from the live SIM flow: when the user jumps to SIM
                // settings, enables the disabled SIM, and comes back, the
                // SIM's call button appears and its note clears — and on a
                // cold notification start, the targets fill in the same way
                // as soon as the startup telephony refresh lands.
                val sims by app.assembler.simsAndAccounts().collectAsStateWithLifecycle()
                val state = remember(sims, country) {
                    buildChooserUiState(
                        dialedNumber = dialedNumber,
                        country = country,
                        activeSims = sims.activeSims,
                        skippedInactiveSims = skippedSims,
                        numberCorrection = numberCorrection,
                        callingAccounts = sims.callingAccounts,
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
                        placeCall(choice.handle, choice.target, choice.rememberRule, state.rememberRegion)
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
                    onPlace = { target, rememberRule, chosenNumber ->
                        // A confirmed number correction places the chosen
                        // number; the as-dialed pick keeps the original URI.
                        val placeHandle = if (chosenNumber == dialedNumber) {
                            handle
                        } else {
                            Uri.fromParts("tel", chosenNumber, null)
                        }
                        if (hasCallPermission()) {
                            placeCall(placeHandle, target, rememberRule, state.rememberRegion)
                            finish()
                        } else {
                            pending = PendingChoice(placeHandle, target, rememberRule)
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

    private data class PendingChoice(
        val handle: Uri,
        val target: ChooserTargetUi,
        val rememberRule: Boolean,
    )

    private fun hasCallPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED

    private fun needsNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED

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
            // A SIM target remembers a use-SIM rule; a calling-account target
            // remembers the same account redirect the chooser just used.
            val action = target.sim?.let { RuleAction.UseSim(it) }
                ?: RuleAction.HandOff.ViaPhoneAccount(target.account, target.label)
            // On appScope: the write must outlive this finishing activity.
            // And retried: this is a durable user intent, so a transient
            // write failure must keep trying in the background rather than
            // fall into the scope handler's log and silently drop the rule
            // (Codex on PR #52).
            app.appScope.launch {
                retryUntilDone("Remember rule") {
                    app.stateHolders().filterNotNull().first().updateRules {
                        it.withRuleAdded(Rule(countryMatcher(listOf(rememberRegion)), action))
                    }
                }
            }
        }
        // A failure (the SIM or calling account vanished between the chooser
        // opening and the tap, or CALL_PHONE revoked concurrently) can't be
        // undone — the original call is already canceled — so it falls back to
        // the dialer with the number, same as the delayed-call countdown
        // (Codex on PR #39).
        app.replaceCallOrOpenDialer(this, handle, target.account)
    }

    companion object {
        private const val TAG = "SimmoChooser"
        private const val EXTRA_SKIPPED_SIMS = "app.simmo.extra.SKIPPED_SIMS"
        private const val EXTRA_NUMBER_CORRECTION = "app.simmo.extra.NUMBER_CORRECTION"

        private val extrasJson = Json { ignoreUnknownKeys = true }

        /** Launch intent for [handle]; from the service, add NEW_TASK. */
        fun launchIntent(
            context: Context,
            handle: Uri,
            skippedInactiveSims: List<SimRef>,
            numberCorrection: NumberCorrection? = null,
        ): Intent =
            Intent(context, ChooserActivity::class.java)
                .setData(handle)
                .putExtra(
                    EXTRA_SKIPPED_SIMS,
                    extrasJson.encodeToString(ListSerializer(SimRef.serializer()), skippedInactiveSims),
                )
                .apply {
                    numberCorrection?.let {
                        putExtra(EXTRA_NUMBER_CORRECTION, extrasJson.encodeToString(it))
                    }
                }

        private fun decodeSkippedSims(encoded: String?): List<SimRef> =
            encoded?.let {
                runCatching {
                    extrasJson.decodeFromString(ListSerializer(SimRef.serializer()), it)
                }.getOrNull()
            }.orEmpty()

        private fun decodeNumberCorrection(encoded: String?): NumberCorrection? =
            encoded?.let {
                runCatching { extrasJson.decodeFromString<NumberCorrection>(it) }.getOrNull()
            }
    }
}
