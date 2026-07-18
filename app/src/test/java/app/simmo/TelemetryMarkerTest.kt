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
    fun `an opt-out tap lands in the durable marker without the main state write`() {
        val app = ApplicationProvider.getApplicationContext<SimmoApp>()
        val prefs = app.getSharedPreferences("telemetry", Context.MODE_PRIVATE)

        app.setAnalyticsOptIn(false)

        // The marker write runs on the app scope with no state-holder wait;
        // poll briefly instead of assuming scheduling order.
        val deadline = System.currentTimeMillis() + 5_000
        while (!prefs.contains("optIn") && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertTrue(prefs.contains("optIn"))
        assertFalse(prefs.getBoolean("optIn", true))
    }
}
