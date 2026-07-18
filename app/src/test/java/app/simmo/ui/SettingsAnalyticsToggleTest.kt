package app.simmo.ui

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import app.simmo.SimmoApp
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsAnalyticsToggleTest {

    @Test
    fun `the settings toggle routes through the app's gated telemetry path`() {
        val app = ApplicationProvider.getApplicationContext<SimmoApp>()
        val vm = RulesViewModel(app, SavedStateHandle())

        vm.setAnalyticsOptIn(false)

        // The durable marker is the app path's signature: a direct
        // holder write would persist the flag but skip the telemetry
        // gating, leaving collection running until the next launch.
        val prefs = app.getSharedPreferences("telemetry", Context.MODE_PRIVATE)
        assertFalse(prefs.getBoolean("optIn", true))
    }

    @Test
    fun `an opt-out loaded while Settings is closed reaches the first frame`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<SimmoApp>()
        val vm = RulesViewModel(app, SavedStateHandle())
        // A marker-less opt-out (restore) written straight to the state after
        // the view model exists but with the Settings screen closed.
        val holder = app.stateHolders().filterNotNull().first()
        holder.setAnalyticsOptIn(false)

        // The eagerly-shared flow must reflect it without any subscriber —
        // .value is what the screen's first frame renders.
        val deadline = System.currentTimeMillis() + 5_000
        while (vm.settings.value.analyticsOptIn && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertFalse(vm.settings.value.analyticsOptIn)
    }

    @Test
    fun `a fresh view model starts from the effective choice, not the default`() {
        val app = ApplicationProvider.getApplicationContext<SimmoApp>()
        app.setAnalyticsOptIn(false)

        // Restored straight into Settings on a cold start, the switch's very
        // first frame must already show the opt-out, not the optimistic
        // default that only corrects once the state loads.
        val vm = RulesViewModel(app, SavedStateHandle())
        assertFalse(vm.settings.value.analyticsOptIn)
    }
}
