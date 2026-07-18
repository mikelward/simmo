package app.simmo.analytics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.FirebaseApp
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TelemetryGateTest {

    @Test
    fun `applies the stored choice and every change, without repeats`() = runTest {
        val applied = mutableListOf<Boolean>()
        val persisted = MutableSharedFlow<Boolean>()
        val job = launch {
            TelemetryGate { applied += it }.follow(persisted, taps = MutableStateFlow(null))
        }
        runCurrent()

        persisted.emit(true)
        // A re-emitted equal value (state re-publish) must not re-poke the SDK.
        persisted.emit(true)
        persisted.emit(false)
        persisted.emit(true)
        runCurrent()

        assertEquals(listOf(true, false, true), applied)
        job.cancel()
    }

    @Test
    fun `a tap wins over staler persisted emissions`() = runTest {
        val applied = mutableListOf<Boolean>()
        val persisted = MutableSharedFlow<Boolean>()
        val taps = MutableStateFlow<Boolean?>(null)
        val job = launch { TelemetryGate { applied += it }.follow(persisted, taps) }
        runCurrent()

        persisted.emit(true) // Fresh install: the default loads as opted in.
        taps.value = false // The user opts out; the write is still in flight.
        persisted.emit(true) // A stale pre-write emission must not re-enable.
        runCurrent()

        assertEquals(listOf(true, false), applied)
        job.cancel()
    }

    @Test
    fun `set applies right away, without waiting for a flow emission`() {
        val applied = mutableListOf<Boolean>()
        TelemetryGate { applied += it }.set(false)
        assertEquals(listOf(false), applied)
    }

    @Test
    fun `no gate without an initialized Firebase app`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // A machine with a local google-services.json (SETUP.md) builds a test
        // APK whose default FirebaseApp initializes even under Robolectric;
        // drop any apps first so this asserts the degraded no-config path —
        // no gate, no Firebase calls, no crash — in every setup.
        FirebaseApp.getApps(context).forEach { it.delete() }
        assertNull(TelemetryGate.firebase(context))
    }
}
