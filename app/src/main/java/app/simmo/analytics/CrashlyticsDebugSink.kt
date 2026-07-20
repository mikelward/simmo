package app.simmo.analytics

import app.simmo.SimmoDebugLog
import com.google.firebase.crashlytics.FirebaseCrashlytics
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Mirrors [SimmoDebugLog] lines into Firebase Crashlytics breadcrumbs, so an
 * uploaded crash report carries the recent routing decisions and errors that
 * led up to it — the same redacted lines the shared debug log shows.
 *
 * Only ever passed the already-redacted buffer entry (no raw dialed number), and
 * installed only when a Firebase config is present. Whether a breadcrumb is
 * delivered is decided by [optedIn], read from the single source of truth (the
 * effective "Make Simmo better" choice) at both submit and delivery — not a
 * separate enabled flag toggled from two threads, which raced (Codex on PR #90).
 * A choice that flips to opted-out before delivery therefore drops the
 * breadcrumb, and there is no stale-toggle window in either direction. [optedIn]
 * returns false until the choice is affirmatively known, so nothing mirrors
 * before then.
 *
 * Delivery runs on a dedicated prestarted single-thread worker, never inline:
 * `SimmoDebugLog.event` is on the redirection decision path, and per "Fast
 * decision path" the response must not block on the Firebase SDK. Single-thread,
 * so breadcrumb order is preserved; best-effort, so a failure is dropped.
 */
internal class CrashlyticsDebugSink(private val optedIn: () -> Boolean) : SimmoDebugLog.Sink {

    private val crashlytics = FirebaseCrashlytics.getInstance()
    private val delivery = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue(),
    ) { runnable -> Thread(runnable, "simmo-crashlytics-log").apply { isDaemon = true } }
        .apply { prestartCoreThread() }

    override fun log(line: String) {
        if (!optedIn()) return
        // execute() only enqueues, so this returns to the decision path at once;
        // delivery re-reads the current choice, so an opt-out that lands before
        // this runs still wins.
        runCatching {
            delivery.execute { if (optedIn()) runCatching { crashlytics.log(line) } }
        }
    }
}
