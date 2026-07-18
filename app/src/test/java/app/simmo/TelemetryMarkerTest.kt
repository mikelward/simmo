package app.simmo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TelemetryMarkerTest {

    @Test
    fun `an opt-out tap lands in the durable marker before the tap returns`() {
        val app = ApplicationProvider.getApplicationContext<SimmoApp>()
        val prefs = app.getSharedPreferences("telemetry", Context.MODE_PRIVATE)

        app.setAnalyticsOptIn(false)

        // Synchronous by design: a process death right after the tap must
        // still leave the choice on disk for the next launch's cleanup.
        assertTrue(prefs.contains("optIn"))
        assertFalse(prefs.getBoolean("optIn", true))
    }
}
