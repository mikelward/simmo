package app.simmo.telecom

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.telephony.CarrierConfigManager
import androidx.core.content.IntentCompat
import app.simmo.SimmoApp
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Wake-ups for the roaming watch (SPEC "Data-roaming visibility"): the
 * broadcasts that can start a dead process at the moments that matter.
 * `TIMEZONE_CHANGED` is the arrival moment (landing, airplane mode off, the
 * network sets the clock), `CARRIER_CONFIG_CHANGED` fires on SIM
 * load/re-evaluation, and `BOOT_COMPLETED` exists to re-arm the connectivity
 * callback below — its real work happens before `onReceive` runs, because
 * starting the process re-registers the callback in `Application.onCreate`;
 * the refresh here is the post-boot check riding along. All three come from
 * the system, which reaches a non-exported receiver.
 *
 * [ACTION_CONNECTIVITY_EVENT] is the remaining lattice layer: Simmo's own
 * `PendingIntent`, delivered by `ConnectivityManager` when cellular
 * connectivity changes — the only wake for a same-timezone border crossing,
 * since no broadcast exists for "roaming started" itself. It fires for
 * routine at-home churn too, so the receiver checks the roaming capability
 * and skips fires whose network is verifiably not roaming; anything
 * uncertain — network gone, capabilities unreadable — refreshes anyway,
 * because "cellular just vanished" is exactly the no-data moment. The
 * action check is defense in depth, not the security boundary (the
 * component is private).
 *
 * The broadcast is held open (`goAsync`) until the refresh and watch check
 * finish — after `onReceive` returns, nothing else keeps a receiver-started
 * process alive that long. The bound is on `join()`, not on the work: the
 * refresh's blocking telephony IPC can't be interrupted, but the broadcast
 * must finish inside the platform's deadline regardless (an unfinished
 * `PendingResult` is a receiver ANR) — so at the timeout the broadcast
 * finishes, the work continues on process time if the process survives, and
 * the next wake-up retries the check either way.
 */
class DataWatchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_TIMEZONE_CHANGED,
            CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED,
            Intent.ACTION_BOOT_COMPLETED,
            -> Unit

            ACTION_CONNECTIVITY_EVENT ->
                if (!connectivityEventWorthChecking(context, intent)) return

            else -> return
        }
        val app = context.applicationContext as? SimmoApp ?: return
        val pending = goAsync()
        val work = app.appScope.launch { app.refreshTelephonyNow() }
        app.appScope.launch {
            withTimeoutOrNull(RECEIVER_TIMEOUT_MILLIS) { work.join() }
            pending.finish()
        }
    }

    companion object {
        /** The connectivity callback's explicit-intent action (SPEC layer 3). */
        const val ACTION_CONNECTIVITY_EVENT = "app.simmo.action.DATA_CONNECTIVITY_EVENT"

        /** Under the ~10 s broadcast limit, with margin for finish() itself. */
        private const val RECEIVER_TIMEOUT_MILLIS = 8_000L
    }
}

/**
 * Whether a connectivity fire warrants the full refresh-and-check: only a
 * network that is *verifiably* not roaming is skipped. Fail open — a missing
 * network extra or unreadable capabilities usually means the cellular
 * network just went away, which is the no-data nudge's moment, and a wrong
 * "skip" silently loses an arrival while a wrong "check" costs one refresh.
 */
internal fun connectivityEventWorthChecking(context: Context, intent: Intent): Boolean {
    val network = IntentCompat.getParcelableExtra(
        intent,
        ConnectivityManager.EXTRA_NETWORK,
        Network::class.java,
    ) ?: return true
    val capabilities = context.getSystemService(ConnectivityManager::class.java)
        ?.getNetworkCapabilities(network)
        ?: return true
    return !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)
}
