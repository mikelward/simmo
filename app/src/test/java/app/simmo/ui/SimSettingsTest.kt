package app.simmo.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import android.telephony.euicc.EuiccManager
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SimSettingsTest {

    @Test
    fun `leads with the SIMs page, then eUICC, then generic network settings`() {
        assertEquals(
            listOf(
                Settings.ACTION_MANAGE_ALL_SIM_PROFILES_SETTINGS,
                EuiccManager.ACTION_MANAGE_EMBEDDED_SUBSCRIPTIONS,
                Settings.ACTION_WIRELESS_SETTINGS,
            ),
            simSettingsIntentCandidates(euiccEnabled = true).map { it.action },
        )
    }

    @Test
    fun `skips the eUICC screen when the device's eUICC is absent or disabled`() {
        // The eUICC activity can resolve and launch on such devices, then
        // immediately finish RESULT_CANCELED without throwing — offering it
        // would eat the jump before the fallback (Codex on PR #22).
        assertEquals(
            listOf(
                Settings.ACTION_MANAGE_ALL_SIM_PROFILES_SETTINGS,
                Settings.ACTION_WIRELESS_SETTINGS,
            ),
            simSettingsIntentCandidates(euiccEnabled = false).map { it.action },
        )
    }

    @Test
    fun `launches the first candidate that starts without throwing`() {
        val started = mutableListOf<Intent>()
        launchSimSettings(simSettingsIntentCandidates(euiccEnabled = true)) { started.add(it) }

        assertEquals(
            listOf(Settings.ACTION_MANAGE_ALL_SIM_PROFILES_SETTINGS),
            started.map { it.action },
        )
    }

    @Test
    fun `falls through to the next candidate when a deep link throws SecurityException`() {
        // A SIM-settings deep link throwing SecurityException (launcher isn't
        // the carrier) is the kind of failure that crashed the "Change SIMs"
        // button — the fallback must still be reached, not the exception
        // propagated.
        val started = mutableListOf<Intent>()
        launchSimSettings(simSettingsIntentCandidates(euiccEnabled = true)) { intent ->
            if (intent.action == Settings.ACTION_MANAGE_ALL_SIM_PROFILES_SETTINGS) {
                throw SecurityException("not the carrier")
            }
            started.add(intent)
        }

        assertEquals(
            listOf(EuiccManager.ACTION_MANAGE_EMBEDDED_SUBSCRIPTIONS),
            started.map { it.action },
        )
    }

    @Test
    fun `does not throw when every candidate fails to launch`() {
        // No settings screen resolves: openSimSettings promises never to throw,
        // so a mid-call chooser sitting beneath the SIMs screen can't be taken
        // down by a failed jump.
        launchSimSettings(simSettingsIntentCandidates(euiccEnabled = true)) {
            throw ActivityNotFoundException("no activity")
        }
    }
}
