package app.simmo.analytics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.FirebaseApp
import kotlinx.coroutines.flow.MutableSharedFlow
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
        val optIns = MutableSharedFlow<Boolean>()
        val job = launch { TelemetryGate { applied += it }.follow(optIns) }
        runCurrent()

        optIns.emit(true)
        // A re-emitted equal value (state re-publish) must not re-poke the SDK.
        optIns.emit(true)
        optIns.emit(false)
        optIns.emit(true)
        runCurrent()

        assertEquals(listOf(true, false, true), applied)
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
