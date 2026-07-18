package app.simmo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

    @Test
    fun `without a marker, a loaded opt-out still seeds the effective choice`() = runBlocking {
        // Bound every await on the app's background state load: these only
        // block until the store yields, which the isolated per-test store
        // already guarantees. The timeout is belt-and-suspenders — a future
        // regression that stalls the load fails here in seconds with a clear
        // timeout instead of hanging until the CI job's own timeout.
        withTimeout(STATE_LOAD_TIMEOUT_MS) {
            val app = ApplicationProvider.getApplicationContext<SimmoApp>()
            // An opt-out written straight to the state — a restore (no marker is
            // backed up) or a choice saved before the marker existed.
            val holder = app.stateHolders().filterNotNull().first()
            holder.setAnalyticsOptIn(false)
            holder.state.filterNotNull().first { !it.analyticsOptIn }

            assertFalse(app.currentAnalyticsOptIn())
        }
    }
}
