package app.simmo.ui

import android.content.Context
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import app.simmo.SimmoApp
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class SettingsAnalyticsToggleTest {

    private lateinit var vm: RulesViewModel

    /**
     * The view model's eager settings collector (and its background upstream)
     * would otherwise outlive the test: Robolectric tears the Android
     * environment down between tests, and a straggler coroutine touching the
     * torn-down Context surfaces as an uncaught exception that fails a later
     * test's runTest.
     */
    @After
    fun cancelViewModel() {
        if (::vm.isInitialized) vm.viewModelScope.cancel()
    }

    @Test
    fun `the settings toggle routes through the app's gated telemetry path`() {
        val app = ApplicationProvider.getApplicationContext<SimmoApp>()
        vm = RulesViewModel(app, SavedStateHandle())

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
        vm = RulesViewModel(app, SavedStateHandle())
        // A marker-less opt-out (restore) written straight to the state after
        // the view model exists but with the Settings screen closed.
        val holder = app.stateHolders().filterNotNull().first()
        holder.setAnalyticsOptIn(false)

        // The eagerly-shared flow must reflect it without any subscriber —
        // .value is what the screen's first frame renders, so poll it rather
        // than collect (a subscriber would mask an Eagerly -> WhileSubscribed
        // regression). The choice crosses background dispatchers before the
        // view model's main-dispatcher collector applies it, and under
        // Robolectric's paused looper a hop posted to the main queue never
        // runs during a wall-clock sleep — idle the looper between polls so
        // the wait drains deterministically. The deadline is only a hang
        // guard; the passing path converges in milliseconds.
        val mainLooper = shadowOf(Looper.getMainLooper())
        val deadline = System.currentTimeMillis() + 30_000
        while (vm.settings.value.analyticsOptIn && System.currentTimeMillis() < deadline) {
            mainLooper.idle()
            Thread.sleep(1)
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
        vm = RulesViewModel(app, SavedStateHandle())
        assertFalse(vm.settings.value.analyticsOptIn)
    }
}
