package app.simmo.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.telephony.euicc.EuiccManager
import android.util.Log

private const val TAG = "SimmoSimSettings"

/**
 * Where "Change SIMs" jumps land, most specific first: apps cannot enable or
 * disable SIMs themselves (carrier privileges), so the best any Simmo surface
 * can do is hand the user to the system screen that manages them.
 *
 * The SIMs page ([Settings.ACTION_MANAGE_ALL_SIM_PROFILES_SETTINGS]) leads: it
 * is exactly where the default calling/data SIM and per-SIM enable toggles live
 * — the "primary" that "Change SIMs" points the user at — and it's a public
 * Settings action since API 33, so it is present on our minSdk 34. Behind it,
 * the embedded-SIM management screen and finally the generic network settings.
 * The eUICC screen is only a candidate while [euiccEnabled]: on devices whose
 * eUICC is absent or disabled the activity can still resolve and launch, then
 * immediately finish RESULT_CANCELED instead of throwing — which would eat the
 * jump without ever reaching the fallback (Codex on PR #22). Any candidate that
 * doesn't resolve on a given device is skipped by [launchSimSettings].
 */
internal fun simSettingsIntentCandidates(euiccEnabled: Boolean): List<Intent> = buildList {
    add(Intent(Settings.ACTION_MANAGE_ALL_SIM_PROFILES_SETTINGS))
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
    launchSimSettings(simSettingsIntentCandidates(euiccEnabled), ::startActivity)
}

/**
 * Launches the first candidate that starts without throwing, falling through
 * to the next on any launch failure and logging if none start. It's not only
 * [android.content.ActivityNotFoundException] we swallow: OEM SIM-settings and
 * eUICC deep links can also throw [SecurityException] (the launcher isn't the
 * carrier) or other runtime exceptions on some devices, which crashed the app
 * from the SIMs screen's "Change SIMs" button instead of degrading. This
 * surface can sit directly above a chooser holding a mid-call, so it must
 * honor [openSimSettings]'s "never throws" contract. Extracted with an
 * injected [start] so the fallthrough is unit-testable without a device.
 */
internal fun launchSimSettings(candidates: List<Intent>, start: (Intent) -> Unit) {
    for (candidate in candidates) {
        try {
            start(candidate)
            return
        } catch (e: RuntimeException) {
            // Try the next, more generic screen.
            Log.w(TAG, "SIM settings screen ${candidate.action} failed to launch", e)
        }
    }
    Log.e(TAG, "No SIM settings screen available on this device")
}
