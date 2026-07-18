package app.simmo.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.telephony.euicc.EuiccManager
import android.util.Log

private const val TAG = "SimmoSimSettings"

/**
 * Where "System settings" jumps land, most specific first: apps cannot enable or
 * disable SIMs themselves (carrier privileges), so the best any Simmo surface
 * can do is hand the user to the system's embedded-SIM management screen,
 * falling back to the generic network settings. The eUICC screen is only a
 * candidate while [euiccEnabled]: on devices whose eUICC is absent or
 * disabled the activity can still resolve and launch, then immediately
 * finish RESULT_CANCELED instead of throwing — which would eat the jump
 * without ever reaching the fallback (Codex on PR #22). Which deep link is
 * best per Android version is on the device-QA list (TODO.md Phase 4).
 */
internal fun simSettingsIntentCandidates(euiccEnabled: Boolean): List<Intent> = buildList {
    if (euiccEnabled) add(Intent(EuiccManager.ACTION_MANAGE_EMBEDDED_SUBSCRIPTIONS))
    add(Intent(Settings.ACTION_WIRELESS_SETTINGS))
}

/** Opens the best available system SIM settings screen; never throws. */
internal fun Context.openSimSettings() {
    val euiccEnabled = try {
        getSystemService(EuiccManager::class.java)?.isEnabled == true
    } catch (_: UnsupportedOperationException) {
        // Physical-SIM-only devices lack FEATURE_TELEPHONY_EUICC, and the
        // platform's telephony feature enforcement makes isEnabled throw
        // there rather than return false (Codex on PR #22).
        false
    }
    for (candidate in simSettingsIntentCandidates(euiccEnabled)) {
        try {
            startActivity(candidate)
            return
        } catch (_: ActivityNotFoundException) {
            // Try the next, more generic screen.
        }
    }
    Log.e(TAG, "No SIM settings screen available on this device")
}
