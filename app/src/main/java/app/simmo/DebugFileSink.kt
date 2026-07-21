package app.simmo

import android.content.Context
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Current run's persisted log; renamed to a [PREVIOUS_PREFIX] file at the next start. */
private const val CURRENT_FILE = "debug.log"

/** Prefix for prior runs' logs, one file per run, surfaced then deleted by the report. */
private const val PREVIOUS_PREFIX = "debug-prev-"

/** Suffix for a prior run that ended gracefully or by a silent kill (no crash). */
private const val PREVIOUS_PLAIN_SUFFIX = ".log"

/**
 * Suffix marking a prior run that ended in an *uncaught exception* — a real
 * crash, as opposed to a routine kill (OS reclaim, force-stop, app update) or a
 * silent kill of the background service. Only these raise the post-crash banner,
 * so "Simmo crashed" is never shown for an ordinary process death. Both kinds of
 * prior run are still readable/shareable ([readPreviousRun]); the suffix only
 * gates the *banner*.
 */
private const val PREVIOUS_CRASH_SUFFIX = ".crash.log"

/**
 * Companion to [CURRENT_FILE], written by the uncaught-exception handler and
 * consumed at the next start. Its presence there is the one reliable in-process
 * signal that this run crashed rather than exited or was killed; a graceful run
 * never creates it. Kept out of [previousFiles] by not matching [PREVIOUS_PREFIX].
 */
private const val CRASH_MARKER_FILE = "$CURRENT_FILE.crash"

/** How many unshared prior runs to keep — older ones are dropped at startup. */
private const val MAX_PREVIOUS_RUNS = 5

/**
 * Bound on the persisted content. The in-memory buffer is already count-bounded,
 * so mirroring it caps each file too; this is a belt-and-suspenders ceiling.
 */
private const val PERSIST_BUDGET_CHARS = 150_000

/**
 * How long the crash handler waits for the final flush (queued behind any
 * in-flight write) before chaining on. Long enough to land the crash snapshot on
 * healthy storage, short enough that a stalled disk never delays Crashlytics or
 * process termination (Codex on PR #90).
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
 * A run that ended in an *uncaught exception* is marked (via [CRASH_MARKER_FILE])
 * and rotated to a distinct crash-suffixed name, so the next start can tell a
 * real crash apart from a routine kill and raise the post-crash banner only for
 * the former ([hasUnacknowledgedCrash]); [acknowledgeCrashBanner] dismisses it.
 *
 * **Everything touching the filesystem runs on a single daemon worker** — the
 * startup rotation, the continuous mirroring, and the crash flush — so nothing
 * blocks the caller on disk. That matters twice here: a Telecom cold-start races
 * the ~5 s decision deadline before `onPlaceCall`, and the redirection service
 * must never wait on storage on that path (AGENTS/SPEC "Fast decision path"); the
 * worker is FIFO, so the rotation enqueued in [start] always runs before this
 * run's writes or the crash flush, preserving "rotate before we clobber the prior
 * run" without a lock. Content is the already-redacted buffer (numbers masked);
 * files live in [dir] (an app-private cache directory, excluded from backup) and
 * never hold a full dialed number.
 */
internal class DebugFileSink internal constructor(
    dirProvider: () -> File,
) : SimmoDebugLog.Sink {

    // Production: resolve cacheDir lazily, so the (possibly dir-creating) I/O of
    // `getCacheDir()` runs on the worker at first use, not on the calling thread
    // in the constructor during cold start (Codex on PR #90/#92).
    constructor(context: Context) : this({ context.applicationContext.cacheDir })

    /** Tests / direct use: an already-resolved directory. */
    internal constructor(dir: File) : this({ dir })

    // Resolved on first access, which is always inside a worker task (every file
    // operation runs there), so cacheDir resolution never touches the caller.
    private val dir: File by lazy(dirProvider)

    private val current get() = File(dir, CURRENT_FILE)
    private val temp get() = File(dir, "$CURRENT_FILE.tmp")
    private val crashMarker get() = File(dir, CRASH_MARKER_FILE)

    /** Prior runs' files, oldest first (by last-modified time). */
    private fun previousFiles(): List<File> =
        (dir.listFiles { file -> file.name.startsWith(PREVIOUS_PREFIX) } ?: emptyArray())
            .sortedBy { it.lastModified() }

    /** Prior runs that crashed (crash-suffixed) — the files that raise the banner. */
    private fun crashedFiles(): List<File> =
        previousFiles().filter { it.name.endsWith(PREVIOUS_CRASH_SUFFIX) }

    // Single worker, prestarted so the first call never pays thread creation
    // (Codex on PR #90). Daemon, so it never keeps the process alive. Being
    // single-threaded and FIFO is what lets every file operation be serialized
    // without an explicit lock.
    private val worker = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue(),
    ) { runnable -> Thread(runnable, "simmo-debug-log").apply { isDaemon = true } }
        .apply { prestartCoreThread() }

    private val writePending = AtomicBoolean(false)

    /**
     * Enqueues the just-ended run's rotation on the worker and installs a chained
     * crash handler. Call once, early in [SimmoApp.onCreate], and register this as
     * a sink afterward. The rotation is enqueued (not run inline), so `onCreate`
     * does no synchronous disk I/O on the cold-start path; the FIFO worker still
     * runs it before any of this run's writes.
     */
    fun start() {
        // Rotate on the worker, never on the calling thread: onCreate is on the
        // cold-start path that Telecom races before onPlaceCall, and it must not
        // block on disk (Codex on PR #90/#92). FIFO ordering means this runs
        // before this run's log writes and the crash flush, so the prior run is
        // renamed aside before anything can clobber it.
        runCatching {
            worker.execute {
                runCatching {
                    if (current.exists()) {
                        // Was the just-ended run a crash? The uncaught-exception
                        // handler leaves [crashMarker] behind; a graceful exit or a
                        // routine/silent kill does not. Only a crashed run gets the
                        // [PREVIOUS_CRASH_SUFFIX] that raises the banner, so an
                        // ordinary process death never shows "Simmo crashed" (Codex
                        // on PR #91). A cheap metadata existence check.
                        val suffix = if (crashMarker.exists()) PREVIOUS_CRASH_SUFFIX else PREVIOUS_PLAIN_SUFFIX
                        current.renameTo(File(dir, "$PREVIOUS_PREFIX${System.nanoTime()}$suffix"))
                        // Bound how many unshared runs pile up; drop the oldest.
                        val prior = previousFiles()
                        if (prior.size > MAX_PREVIOUS_RUNS) {
                            prior.take(prior.size - MAX_PREVIOUS_RUNS).forEach { it.delete() }
                        }
                    }
                    // Consume the just-read crash marker so it can't mislabel this run.
                    crashMarker.delete()
                    // Discard any leftover temp from a write interrupted mid-flight:
                    // never renamed into place, so by the atomic-write contract it
                    // is uncommitted and possibly partial (Codex on PR #90).
                    temp.delete()
                }
            }
        }
        installCrashHandler()
    }

    override fun log(line: String) {
        // Coalesce: while a write is queued, later lines are already captured by
        // that write's snapshot read, so we don't queue another. Enqueue-only, so
        // this returns to the decision path at once. (No debounce delay: Simmo
        // logs per call decision, not per keystroke, so coalescing over a window
        // would buy almost nothing while risking the last decision's tail on a
        // silent kill — the diagnostic this persistence exists to keep.)
        if (writePending.compareAndSet(false, true)) {
            runCatching {
                worker.execute {
                    writePending.set(false)
                    writeSnapshot()
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
     * the last run(s) left nothing. Runs on the worker so it is ordered *after*
     * the startup rotation [start] queued there — otherwise a share racing a slow
     * rotation could scan before `debug.log` is renamed and miss the just-ended
     * run (Codex on PR #90/#92). Call off the main thread (this blocks on the
     * worker and reads up to [MAX_PREVIOUS_RUNS] files); a file that fails to read
     * is skipped and left in place, never destroyed (it is not added to the set
     * [clearPreviousRun] deletes).
     */
    fun readPreviousRun(): String? =
        runCatching { worker.submit<String?> { readPreviousRunOnWorker() }.get() }.getOrNull()

    private fun readPreviousRunOnWorker(): String? {
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

    /**
     * Deletes only the prior-run files that were surfaced by the last read. On the
     * worker too, so it can't race the mirror's writes and reads/mutates
     * [lastSurfaced] under the same single-threaded ordering as [readPreviousRun].
     */
    fun clearPreviousRun() {
        runCatching {
            worker.submit {
                lastSurfaced.forEach { runCatching { it.delete() } }
                lastSurfaced = emptyList()
            }.get()
        }
    }

    /**
     * Whether a prior run *crashed* (ended in an uncaught exception) and the user
     * has neither shared nor dismissed it — the signal the SIMs screen's
     * post-crash banner shows on. A routine kill, force-stop, app update, or
     * silent kill leaves a prior-run file too, but not a crash-suffixed one, so
     * this stays false for them and the banner never mislabels an ordinary exit
     * (Codex on PR #91). Runs on the worker (like [readPreviousRun]) so FIFO
     * ordering places it after the startup rotation — otherwise a check racing a
     * slow rotation could scan before `debug.log` is renamed to its crash-suffixed
     * name and miss the just-ended crash. Metadata only (a directory listing;
     * never the log itself), so it is cheap; call it off the main thread as it
     * blocks on the worker. Sharing clears the runs ([clearPreviousRun]) and
     * dismissing renames them off the crash suffix ([acknowledgeCrashBanner]);
     * either way this then returns false until a later run crashes.
     */
    fun hasUnacknowledgedCrash(): Boolean =
        runCatching { worker.submit<Boolean> { crashedFiles().isNotEmpty() }.get() }.getOrElse { false }

    /**
     * Dismiss: rename every crashed prior-run file off the crash suffix so it no
     * longer raises the banner, keeping its log (still shareable from Settings).
     * Renaming in place — rather than recording a separate ack marker in this
     * evictable cache dir — so cache eviction can't resurrect the prompt by
     * dropping the marker while keeping the crash file (Codex on PR #91). A later
     * crash writes a fresh crash-suffixed file and raises the banner again. On the
     * worker too, so it can't race the rotation or the mirror's reads/writes.
     */
    fun acknowledgeCrashBanner() {
        runCatching {
            worker.submit {
                crashedFiles().forEach { file ->
                    val plain = File(dir, file.name.removeSuffix(PREVIOUS_CRASH_SUFFIX) + PREVIOUS_PLAIN_SUFFIX)
                    runCatching { file.renameTo(plain) }
                }
            }.get()
        }
    }

    private fun installCrashHandler() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // On the dying thread: record the crash, flush the freshest buffer on
            // the worker (which also holds the rotation, so ordering is preserved),
            // then delegate so Crashlytics captures the report and the process
            // terminates. Wait for the flush, but with a short bounded deadline:
            // the daemon worker's task can be dropped at process death, so a
            // fire-and-forget enqueue could lose the crash snapshot — yet the wait
            // must never delay the chain beyond CRASH_WRITE_TIMEOUT_MS on stalled
            // storage (Codex on PR #90/#92). The atomic temp-then-rename means a
            // write killed at the deadline still leaves the prior complete snapshot
            // intact. The marker rides with the snapshot so the next start rotates
            // this run's log to a banner-raising name.
            runCatching {
                // Claim writePending BEFORE recording the crash line: warning()
                // fans out to every registered sink, and *this* file sink's log()
                // would otherwise queue its own full writeSnapshot ahead of the
                // marker task below. Because the worker is FIFO, on slow storage
                // that redundant write eats the whole deadline before the marker is
                // even written, so the next start would misclassify the crash as a
                // routine kill and skip the banner (Codex on PR #94). Claiming it
                // makes the fan-out's compareAndSet a no-op; the crash task clears
                // it and does the one real write (marker first, then snapshot). The
                // Crashlytics-breadcrumb sink is a separate sink, so it still fires.
                writePending.set(true)
                // warning() records to the buffer (and fans to the breadcrumb sink)
                // with no recordException, so the fatal is not double-counted as a
                // non-fatal; only the chained Crashlytics handler reports it, once.
                SimmoDebugLog.warning("Uncaught exception in thread ${thread.name}", throwable)
                // Marker first so a write killed at the deadline still leaves the
                // banner signal; the snapshot (freshest buffer, incl. the crash
                // line) rides behind it. Bounded so a stalled disk can't delay
                // Crashlytics or termination (Codex on PR #90/#92).
                val flush = worker.submit {
                    writePending.set(false)
                    runCatching { crashMarker.writeText("1") }
                    writeSnapshot()
                }
                runCatching { flush.get(CRASH_WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    /** Test-only: blocks until the worker has drained its queue (incl. rotation). */
    internal fun awaitIdleForTest() {
        worker.submit {}.get()
    }
}
