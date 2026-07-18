package app.simmo.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import app.simmo.SimmoApp
import app.simmo.domain.CountryVerdict
import app.simmo.domain.PhoneAccountRef
import app.simmo.domain.Verdict
import app.simmo.store.SimmoState
import app.simmo.telecom.replaceCallOrOpenDialer
import kotlinx.coroutines.delay

/**
 * The delay-before-calling countdown (SPEC "Call feedback and delay"). The
 * redirection service canceled the carrier call and launched this instead of
 * redirecting silently; when the countdown ends (or on "Call now") the call
 * is re-placed on the rule-picked SIM or calling account — the chooser's
 * cancel-and-re-place mechanism, pass token included — and Cancel (or back)
 * abandons it.
 * Everything shown comes from the launch intent and the warm snapshot: no
 * I/O before the first frame.
 */
class DelayedCallActivity : ComponentActivity() {

    /** Latch: the countdown and the "Call now" button can both try to place. */
    private var placed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as SimmoApp
        val handle = intent?.data
        val account = intent?.getStringExtra(EXTRA_ACCOUNT)?.let(::PhoneAccountRef)
        if (handle == null || account == null) {
            finish()
            return
        }
        val targetLabel = intent.getStringExtra(EXTRA_TARGET_LABEL).orEmpty()
        val delaySeconds = intent.getIntExtra(EXTRA_DELAY_SECONDS, 1)
            .coerceIn(1, SimmoState.MAX_CALL_DELAY_SECONDS)
        val dialedNumber = handle.schemeSpecificPart.orEmpty()
        // Warm parse — the service only produces a delayed verdict from a
        // loaded snapshot, so the metadata is already in memory (see
        // SimmoApp.detectCountry).
        val country = app.detectCountry(dialedNumber)
        setContent {
            MaterialTheme {
                // Saveable so a rotation resumes the countdown where it was
                // instead of restarting it. If the process dies the canceled
                // call is gone — same acceptance as the chooser.
                var remaining by rememberSaveable { mutableIntStateOf(delaySeconds) }
                // One-shot guard for the automatic place: after a recreation
                // mid-permission-dialog the countdown is already spent, and
                // re-running the auto attempt would stack a second dialog on
                // the pending one.
                var autoPlace by rememberSaveable { mutableStateOf(true) }
                // A tap made before CALL_PHONE is granted asks and places on
                // grant, like the chooser; a denial leaves the screen up —
                // "Call now" asks again, Cancel abandons the call.
                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { granted ->
                    if (granted) placeAndFinish(handle, account) else placed = false
                }
                fun attemptPlace() {
                    if (placed) return
                    placed = true
                    if (hasCallPermission()) {
                        placeAndFinish(handle, account)
                    } else {
                        permissionLauncher.launch(Manifest.permission.CALL_PHONE)
                    }
                }
                LaunchedEffect(Unit) {
                    while (remaining > 0) {
                        delay(1_000)
                        remaining--
                    }
                    if (autoPlace) {
                        autoPlace = false
                        attemptPlace()
                    }
                }
                DelayedCallContent(
                    state = DelayedCallUiState(
                        targetLabel = targetLabel,
                        dialedNumber = dialedNumber,
                        countryLabel = (country as? CountryVerdict.Country)
                            ?.regionCode?.let { countryLabel(it) },
                        remainingSeconds = remaining,
                    ),
                    onCallNow = {
                        remaining = 0
                        autoPlace = false
                        attemptPlace()
                    },
                    // The carrier call was canceled before this screen opened;
                    // canceling here just abandons the call, per SPEC.
                    onCancel = { finish() },
                )
            }
        }
    }

    private fun hasCallPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED

    private fun placeAndFinish(handle: Uri, account: PhoneAccountRef) {
        val app = application as SimmoApp
        // Placing any call retires a parked held-call offer, same as the
        // chooser — its stale tel: URI must not be re-offered later.
        app.heldCalls.clear()
        // On failure (the target's account gone, or CALL_PHONE revoked
        // mid-countdown) the shared recovery drops the user in the dialer
        // with the number rather than stranding the canceled call.
        app.replaceCallOrOpenDialer(this, handle, account)
        finish()
    }

    companion object {
        private const val EXTRA_ACCOUNT = "app.simmo.extra.DELAYED_ACCOUNT"
        private const val EXTRA_TARGET_LABEL = "app.simmo.extra.DELAYED_TARGET_LABEL"
        private const val EXTRA_DELAY_SECONDS = "app.simmo.extra.DELAYED_SECONDS"

        /** Launch intent for [verdict] on [handle]; from the service, add NEW_TASK. */
        fun launchIntent(context: Context, handle: Uri, verdict: Verdict.DelayedRedirect): Intent =
            Intent(context, DelayedCallActivity::class.java)
                .setData(handle)
                .putExtra(EXTRA_ACCOUNT, verdict.account.id)
                .putExtra(EXTRA_TARGET_LABEL, verdict.targetLabel)
                .putExtra(EXTRA_DELAY_SECONDS, verdict.delaySeconds)
    }
}
