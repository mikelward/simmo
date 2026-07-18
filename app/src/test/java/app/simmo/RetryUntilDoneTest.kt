package app.simmo

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Robolectric only for android.util.Log; the logic under test is pure. */
@RunWith(RobolectricTestRunner::class)
class RetryUntilDoneTest {

    @Test
    fun `retries through transient failures and returns the first success`() = runTest {
        var attempts = 0
        val result = retryUntilDone("test") {
            attempts++
            if (attempts < 3) error("transient")
            "done"
        }
        assertEquals("done", result)
        assertEquals(3, attempts)
        // Two failures back off 1s then 2s (virtual time under runTest).
        assertEquals(3_000L, testScheduler.currentTime)
    }

    @Test
    fun `cancellation propagates instead of being retried`() = runTest {
        var attempts = 0
        val thrown = try {
            retryUntilDone("test") {
                attempts++
                throw CancellationException("stop")
            }
        } catch (e: CancellationException) {
            e
        }
        assertEquals("stop", (thrown as CancellationException).message)
        assertEquals(1, attempts)
    }
}
