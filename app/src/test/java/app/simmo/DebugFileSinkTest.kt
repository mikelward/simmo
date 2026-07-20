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
}
