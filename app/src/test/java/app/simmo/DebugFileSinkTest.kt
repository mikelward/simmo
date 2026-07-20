package app.simmo

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DebugFileSinkTest {

    // A fresh directory per test so the shared cache never leaks state between
    // tests (the crash handler is a process-global, so ordering must not matter).
    @get:Rule
    val folder = TemporaryFolder()

    private var original: Thread.UncaughtExceptionHandler? = null

    @Before
    fun setUp() {
        original = Thread.getDefaultUncaughtExceptionHandler()
        SimmoDebugLog.clearForTest()
    }

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(original)
        SimmoDebugLog.clearForTest()
        SimmoDebugLog.clearSinksForTest()
    }

    @Test
    fun `a run's log is persisted and rotated into the previous-run slot next start`() {
        val dir = folder.newFolder()
        val first = DebugFileSink(dir)
        first.start()
        SimmoDebugLog.event("Decision +614••78 -> Ask")
        first.log("trigger") // writes the snapshot to disk (async)
        first.awaitIdleForTest()

        // Nothing rotated yet — this is the current run.
        assertNull(first.readPreviousRun())

        // Next launch (same directory): the prior run's file rotates into the
        // previous-run slot.
        val second = DebugFileSink(dir)
        second.start()
        val previous = second.readPreviousRun()!!
        assertTrue("carries the prior run's log", previous.contains("Decision +614••78 -> Ask"))

        // Surfaced once: clearing removes it.
        second.clearPreviousRun()
        assertNull(second.readPreviousRun())
    }

    @Test
    fun `the crash handler persists a redacted snapshot and chains`() {
        val dir = folder.newFolder()
        var chainedTo = false
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> chainedTo = true }

        val sink = DebugFileSink(dir)
        sink.start()
        SimmoDebugLog.event("Decision +614••78 -> Ask")

        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(
            Thread.currentThread(),
            IllegalStateException("redirect failed for tel:+15551234567"),
        )
        assertTrue("chains to the previous handler (Crashlytics)", chainedTo)

        // Next launch (same directory) sees the crash in the previous-run slot.
        val next = DebugFileSink(dir)
        next.start()
        val previous = next.readPreviousRun()!!
        assertTrue(previous.contains("Decision +614••78 -> Ask"))
        assertTrue(previous.contains("Uncaught exception in thread"))
        assertFalse("the number in the crash message is redacted", previous.contains("15551234567"))
    }

    @Test
    fun `an unread previous run survives later boring restarts`() {
        val dir = folder.newFolder()

        // Run A: an interesting log the user hasn't shared yet.
        val runA = DebugFileSink(dir)
        runA.start()
        SimmoDebugLog.event("Decision +614••78 -> Ask (run A, interesting)")
        runA.log("x")
        runA.awaitIdleForTest()

        // Run B: a boring cold start (Telecom/receiver), then Run C — without a
        // share in between. A's log must not be lost.
        val runB = DebugFileSink(dir)
        runB.start()
        SimmoDebugLog.clearForTest()
        SimmoDebugLog.event("boring startup (run B)")
        runB.log("x")
        runB.awaitIdleForTest()

        val runC = DebugFileSink(dir)
        runC.start()
        val previous = runC.readPreviousRun()!!
        assertTrue("A's unread log survives multiple restarts", previous.contains("run A, interesting"))
    }

    @Test
    fun `readPreviousRun is null when the last run left nothing`() {
        val sink = DebugFileSink(folder.newFolder())
        sink.start()
        assertNull(sink.readPreviousRun())
    }

    @Test
    fun `a routine (non-crash) prior run does not raise the banner`() {
        val dir = folder.newFolder()

        // Run A logs and its snapshot persists, but it ends without a crash — a
        // graceful exit, OS reclaim, force-stop, app update, or a silent kill,
        // none of which is an uncaught exception.
        val runA = DebugFileSink(dir)
        runA.start()
        SimmoDebugLog.event("Decision +614••78 -> Ask")
        runA.log("x")
        runA.awaitIdleForTest()

        val runB = DebugFileSink(dir)
        runB.start()
        assertFalse("an ordinary process death never says crashed", runB.hasUnacknowledgedCrash())
        // The log still persisted and is shareable — the #90 value is kept.
        assertTrue(runB.readPreviousRun()!!.contains("Decision +614••78 -> Ask"))
    }

    @Test
    fun `a crashed run raises the banner, which dismiss then silences`() {
        val dir = folder.newFolder()

        // Run A logs, then crashes (uncaught exception).
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> }
        val runA = DebugFileSink(dir)
        runA.start()
        SimmoDebugLog.event("Decision +614••78 -> Ask")
        triggerCrash()

        // Next launch sees the crash — the banner shows.
        val runB = DebugFileSink(dir)
        runB.start()
        assertTrue("a crashed prior run raises the banner", runB.hasUnacknowledgedCrash())

        // Dismiss renames the crash log off the crash suffix; it stays quiet even
        // across a boring restart, and its log is kept (still shareable).
        runB.acknowledgeCrashBanner()
        assertFalse("dismissed crash stays quiet", runB.hasUnacknowledgedCrash())
        assertTrue(
            "the dismissed run's log is kept and shareable",
            runB.readPreviousRun()!!.contains("Decision +614••78 -> Ask"),
        )

        val runC = DebugFileSink(dir)
        runC.start()
        assertFalse("the dismissal survives a boring restart", runC.hasUnacknowledgedCrash())
    }

    @Test
    fun `a later crash re-raises the banner after an earlier dismiss`() {
        val dir = folder.newFolder()

        // Run A crashes; dismiss its banner.
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> }
        val runA = DebugFileSink(dir)
        runA.start()
        SimmoDebugLog.event("run A")
        triggerCrash("run A boom")

        val runB = DebugFileSink(dir)
        runB.start()
        assertTrue(runB.hasUnacknowledgedCrash())
        runB.acknowledgeCrashBanner()
        assertFalse(runB.hasUnacknowledgedCrash())

        // Run B then crashes too, leaving a newer crash file; the next start must
        // show the banner again — the ack named A's file, not this newer one.
        // (runB.start() already installed runB's handler as the default; triggering
        // it records this crash — don't reset the default here or the marker never
        // gets written.)
        SimmoDebugLog.clearForTest()
        SimmoDebugLog.event("run B")
        triggerCrash("run B boom")

        val runC = DebugFileSink(dir)
        runC.start()
        assertTrue("a newer crash re-raises the banner", runC.hasUnacknowledgedCrash())
    }

    @Test
    fun `sharing a crashed run clears the banner`() {
        val dir = folder.newFolder()

        Thread.setDefaultUncaughtExceptionHandler { _, _ -> }
        val runA = DebugFileSink(dir)
        runA.start()
        SimmoDebugLog.event("run A")
        triggerCrash()

        val runB = DebugFileSink(dir)
        runB.start()
        assertTrue(runB.hasUnacknowledgedCrash())
        // Sharing reads then clears the prior run (as DebugReport does).
        runB.readPreviousRun()
        runB.clearPreviousRun()
        assertFalse("a shared crash leaves no banner", runB.hasUnacknowledgedCrash())
    }

    /** Fires the currently-installed default handler, as an OS crash would. */
    private fun triggerCrash(message: String = "boom") {
        Thread.getDefaultUncaughtExceptionHandler()!!
            .uncaughtException(Thread.currentThread(), IllegalStateException(message))
    }
}
