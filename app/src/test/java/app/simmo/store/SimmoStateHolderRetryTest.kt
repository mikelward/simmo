package app.simmo.store

import androidx.datastore.core.DataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Separate from [SimmoStateHolderTest] because the retry path logs — which
 * needs Robolectric — while the rest of the holder tests stay plain JUnit.
 */
@RunWith(RobolectricTestRunner::class)
class SimmoStateHolderRetryTest {

    /** Fails the first [failures] reads, then serves [stored]. */
    private class FlakyDataStore(
        private val stored: SimmoState,
        private var failures: Int,
    ) : DataStore<SimmoState> {
        override val data: Flow<SimmoState> = flow {
            if (failures > 0) {
                failures--
                throw IOException("transient read failure")
            }
            emit(stored)
        }

        override suspend fun updateData(transform: suspend (t: SimmoState) -> SimmoState): SimmoState =
            transform(stored)
    }

    @Test
    fun `a transient read failure retries instead of staying null forever`() = runTest {
        // Without the retry, the eager collector dies into the app scope's
        // exception handler and the state stays null for the whole process —
        // every call would proceed ruleless.
        val stored = SimmoState(installId = "install-1", countryGroupsVersion = 1)
        val holder = SimmoStateHolder(FlakyDataStore(stored, failures = 2), backgroundScope, "install-1")
        assertEquals(stored, holder.state.filterNotNull().first())
    }
}
