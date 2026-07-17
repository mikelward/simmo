package app.simmo.ui

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
    fun `tries the eUICC management screen first, then generic network settings`() {
        assertEquals(
            listOf(
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
            listOf(Settings.ACTION_WIRELESS_SETTINGS),
            simSettingsIntentCandidates(euiccEnabled = false).map { it.action },
        )
    }
}
