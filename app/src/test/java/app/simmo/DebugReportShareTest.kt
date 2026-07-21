package app.simmo

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * [DebugReport.share] consumes the crashed prior run only when the report was
 * actually retained (the clipboard copy landed), and keeps it for a retry
 * otherwise — the conditional clear added when the read moved off the main thread
 * (Codex on PR #94). The clipboard write and the main-thread hop are injected so
 * both outcomes are drivable without a real Activity or `ClipboardManager`.
 */
@RunWith(RobolectricTestRunner::class)
class DebugReportShareTest {

    private lateinit var app: SimmoApp
    private lateinit var sink: DebugFileSink

    private var original: Thread.UncaughtExceptionHandler? = null

    @Before
    fun setUp() {
        original = Thread.getDefaultUncaughtExceptionHandler()
        app = ApplicationProvider.getApplicationContext()
        sink = app.debugFileSink!!
        SimmoDebugLog.clearForTest()
    }

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(original)
        SimmoDebugLog.clearForTest()
        SimmoDebugLog.clearSinksForTest()
    }

    @Test
    fun `a landed clipboard copy consumes the crashed prior run`() = runBlocking {
        seedCrashedPriorRun("Decision +614••78 -> Ask (crashed)")
        assertTrue("precondition: the crash raises the banner", sink.hasUnacknowledgedCrash())

        withTimeout(SHARE_TIMEOUT_MS) {
            DebugReport.share(app, mainDispatcher = Dispatchers.Unconfined) { _, _ -> true }
        }

        assertFalse("a retained report consumes the prior run", sink.hasUnacknowledgedCrash())
        assertNull("...and clears it so it never rides a second report", sink.readPreviousRun())
    }

    @Test
    fun `a failed clipboard copy keeps the crashed prior run for retry`() = runBlocking {
        seedCrashedPriorRun("Decision +614••78 -> Ask (crashed)")
        assertTrue(sink.hasUnacknowledgedCrash())

        withTimeout(SHARE_TIMEOUT_MS) {
            DebugReport.share(app, mainDispatcher = Dispatchers.Unconfined) { _, _ -> false }
        }

        assertTrue(
            "a report that never reached the user keeps the crash log for the next attempt",
            sink.hasUnacknowledgedCrash(),
        )
        assertNotNull(sink.readPreviousRun())
    }

    /**
     * Drops a crash-suffixed prior-run file straight into the sink's cache dir, as
     * the next start would leave one after a crash — so
     * [DebugFileSink.hasUnacknowledgedCrash] is true without simulating the whole
     * crash + rotation. The sink resolves [android.content.Context.getCacheDir],
     * so that is where it reads from.
     */
    private fun seedCrashedPriorRun(line: String) {
        File(app.cacheDir, "debug-prev-${System.nanoTime()}.crash.log").writeText(line)
    }

    private companion object {
        const val SHARE_TIMEOUT_MS = 30_000L
    }
}
