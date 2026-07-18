package app.simmo.analytics

import androidx.test.core.app.ApplicationProvider
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
    fun `no gate in a build without a Firebase config`() {
        // The test APK is built without google-services.json, so this is the
        // exact degraded path production takes in such builds: no gate, no
        // Firebase calls, no crash.
        assertNull(TelemetryGate.firebase(ApplicationProvider.getApplicationContext()))
    }
}
