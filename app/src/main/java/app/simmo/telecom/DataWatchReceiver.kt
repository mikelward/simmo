package app.simmo.telecom

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.CarrierConfigManager
import app.simmo.SimmoApp
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Wake-ups for the roaming watch (SPEC "Data-roaming visibility"): the
 * manifest-registered broadcasts that can start a dead process at the moments
 * that matter — `TIMEZONE_CHANGED` is the arrival moment (landing, airplane
 * mode off, the network sets the clock) and `CARRIER_CONFIG_CHANGED` fires on
 * SIM load/re-evaluation. Both are exempt from the implicit-broadcast
 * restrictions, and both come from the system, which reaches a non-exported
 * receiver — so the component is private, and the action check below is
 * defense in depth, not the security boundary.
 *
 * The broadcast is held open (`goAsync`) until the refresh and watch check
 * finish — after `onReceive` returns, nothing else keeps a receiver-started
 * process alive that long. The bound is on `join()`, not on the work: the
 * refresh's blocking telephony IPC can't be interrupted, but the broadcast
 * must finish inside the platform's deadline regardless (an unfinished
 * `PendingResult` is a receiver ANR) — so at the timeout the broadcast
 * finishes, the work continues on process time if the process survives, and
 * the next wake-up retries the check either way. The remaining lattice
 * layer — the `ConnectivityManager` PendingIntent callback with its boot
 * re-register — lands separately with its permissions (TODO Phase 9).
 */
class DataWatchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_TIMEZONE_CHANGED &&
            intent.action != CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED
        ) {
            return
        }
        val app = context.applicationContext as? SimmoApp ?: return
        val pending = goAsync()
        val work = app.appScope.launch { app.refreshTelephonyNow() }
        app.appScope.launch {
            withTimeoutOrNull(RECEIVER_TIMEOUT_MILLIS) { work.join() }
            pending.finish()
        }
    }

    private companion object {
        /** Under the ~10 s broadcast limit, with margin for finish() itself. */
        const val RECEIVER_TIMEOUT_MILLIS = 8_000L
    }
}
