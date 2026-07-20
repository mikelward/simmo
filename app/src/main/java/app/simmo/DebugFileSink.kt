package app.simmo

import android.content.Context
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

/** Current run's persisted log; renamed to a [PREVIOUS_PREFIX] file at the next start. */
private const val CURRENT_FILE = "debug.log"

/** Prefix for prior runs' logs, one file per run, surfaced then deleted by the report. */
private const val PREVIOUS_PREFIX = "debug-prev-"

/** How many unshared prior runs to keep — older ones are dropped at startup. */
private const val MAX_PREVIOUS_RUNS = 5

/**
 * Bound on the persisted content. The in-memory buffer is already count-bounded,
 * so mirroring it caps each file too; this is a belt-and-suspenders ceiling.
 */
private const val PERSIST_BUDGET_CHARS = 150_000

/**
 * Longest the crash handler will wait for the write lock before giving up and
 * chaining on. A stalled worker must never keep Crashlytics from capturing the
 * crash or the process from terminating (Codex on PR #90).
 */
private const val CRASH_WRITE_TIMEOUT_MS = 250L

/**
 * Persists the redacted debug log to app-private files so it survives the process
 * ending — a crash *or* a silent kill (the background redirection process is
 * killed with no visible UI and no uncaught exception, so a crash-only handler
 * would miss it). The current run mirrors the in-memory ring buffer
 * ([SimmoDebugLog]) to [CURRENT_FILE]; at the next start that file is renamed
 * aside under a unique [PREVIOUS_PREFIX] name, so every unshared prior run is
 * kept (several cold starts can happen before the user shares) and none is lost
 * to a later boring run. The shared report ([DebugReport]) reads and clears them.
 *
 * Startup does only metadata syscalls (rename + a bounded prune) — never data
 * I/O — so a Telecom cold-start is not slowed before `onPlaceCall` (AGENTS/SPEC
 * "Fast decision path"); reading the prior runs happens only when the user
 * shares. Current-run writes run on a dedicated prestarted worker, coalesced and
 * off the decision path. Content is the already-redacted buffer (numbers masked);
 * files live in [dir] (an app-private cache directory, excluded from backup) and
 * never hold a full dialed number.
 */
internal class DebugFileSink internal constructor(private val dir: File) : SimmoDebugLog.Sink {

    constructor(context: Context) : this(context.applicationContext.cacheDir)

    private val current get() = File(dir, CURRENT_FILE)
    private val temp get() = File(dir, "$CURRENT_FILE.tmp")

    /** Prior runs' files, oldest first (by last-modified time). */
    private fun previousFiles(): List<File> =
        (dir.listFiles { file -> file.name.startsWith(PREVIOUS_PREFIX) } ?: emptyArray())
            .sortedBy { it.lastModified() }

    // Single worker, prestarted so the first call never pays thread creation
    // (Codex on PR #90). Daemon, so it never keeps the process alive.
    private val worker = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue(),
    ) { runnable -> Thread(runnable, "simmo-debug-log").apply { isDaemon = true } }
        .apply { prestartCoreThread() }

    private val writePending = AtomicBoolean(false)

    // Serializes file writes. A ReentrantLock (not `synchronized`) so the crash
    // handler can bound its wait: the worker may be mid-write, and the crash
    // handler must not block indefinitely and prevent chaining (Codex on PR #90).
    private val writeLock = ReentrantLock()

    /**
     * Renames the just-ended run's log aside (metadata only) and installs a
     * chained crash handler. Call once, early in [SimmoApp.onCreate], and
     * register this as a sink afterward so the rename is ordered before any of
     * this run's writes.
     */
    fun start() {
        runCatching {
            if (current.exists()) {
                // Metadata-only: rename this-just-ended run's file aside under a
                // unique name, so it isn't clobbered by this run's writes and an
                // earlier unshared run isn't lost to a later one. Renaming (not
                // merging/copying) keeps startup off any data I/O — Telecom
                // cold-starts here before the watchdog arms (Codex on PR #90).
                // Kept synchronous so it is ordered before the crash handler can
                // overwrite `current`.
                current.renameTo(File(dir, "$PREVIOUS_PREFIX${System.nanoTime()}.log"))
                // Bound how many unshared runs pile up; drop the oldest. Metadata.
                val prior = previousFiles()
                if (prior.size > MAX_PREVIOUS_RUNS) {
                    prior.take(prior.size - MAX_PREVIOUS_RUNS).forEach { it.delete() }
                }
            }
            // Discard any leftover temp from a write interrupted mid-flight: it
            // was never renamed into place, so by the atomic-write contract it is
            // uncommitted and possibly partial (Codex on PR #90).
            temp.delete()
        }
        installCrashHandler()
    }

    override fun log(line: String) {
        // Coalesce: while a write is queued, later lines are already captured by
        // that write's snapshot read, so we don't queue another. Enqueue-only, so
        // this returns to the decision path at once.
        if (writePending.compareAndSet(false, true)) {
            runCatching {
                worker.execute {
                    writePending.set(false)
                    writeLock.lock()
                    try {
                        writeSnapshot()
                    } finally {
                        writeLock.unlock()
                    }
                }
            }.onFailure { writePending.set(false) }
        }
    }

    private fun writeSnapshot() {
        runCatching {
            val text = boundedLogTail(SimmoDebugLog.snapshot(), PERSIST_BUDGET_CHARS)
                .joinToString("\n")
            // Atomic replace: write a temp file, then rename it over the current
            // one. A kill mid-write then leaves the prior *complete* snapshot
            // intact rather than a truncated/empty file — surviving exactly that
            // kill is the point (Codex on PR #90). Fall back to a direct write
            // only if the rename is refused.
            temp.writeText(text)
            if (!temp.renameTo(current)) {
                current.writeText(text)
                temp.delete()
            }
        }
    }

    // The files that [readPreviousRun] last actually read, so [clearPreviousRun]
    // deletes only those — a file that failed to read is left for next time.
    private var lastSurfaced: List<File> = emptyList()

    /**
     * Every unshared prior run's log, oldest first, newest-bounded — or null if
     * the last run(s) left nothing. Read only when the user shares (off the hot
     * path); a file that fails to read is skipped and left in place, never
     * destroyed (it is not added to the set [clearPreviousRun] deletes).
     */
    fun readPreviousRun(): String? {
        val files = previousFiles()
        val read = mutableListOf<File>()
        val lines = files.flatMap { file ->
            val text = runCatching { file.readText() }.getOrNull()
            if (text != null) {
                read += file
                text.split("\n")
            } else {
                emptyList()
            }
        }.filter { it.isNotEmpty() }
        lastSurfaced = read
        if (lines.isEmpty()) return null
        return boundedLogTail(lines, PERSIST_BUDGET_CHARS).joinToString("\n").takeIf { it.isNotBlank() }
    }

    /** Deletes only the prior-run files that were surfaced by the last read. */
    fun clearPreviousRun() {
        lastSurfaced.forEach { runCatching { it.delete() } }
        lastSurfaced = emptyList()
    }

    private fun installCrashHandler() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // On the dying thread: record the crash and force the freshest buffer
            // to disk (the coalesced async write may not have run), then delegate
            // so Crashlytics still uploads and the process still terminates.
            // Best-effort and time-bounded — never mask the crash or stall the
            // chain if the worker is holding the lock (Codex on PR #90).
            runCatching {
                SimmoDebugLog.warning("Uncaught exception in thread ${thread.name}", throwable)
                if (writeLock.tryLock(CRASH_WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    try {
                        writeSnapshot()
                    } finally {
                        writeLock.unlock()
                    }
                }
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    /** Test-only: blocks until the worker has drained its queue. */
    internal fun awaitIdleForTest() {
        worker.submit {}.get()
    }
}
