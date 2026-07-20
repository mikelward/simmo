package app.simmo

import android.util.Log
import app.simmo.domain.Verdict
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.IdentityHashMap
import java.util.Locale
import kotlin.random.Random

private const val SIMMO_DEBUG_TAG = "SimmoDebug"
internal const val DEBUG_LOG_MAX_ENTRIES = 300

// Cap each entry so one pathological stack trace can't dominate the buffer. The
// shareable payload is bounded separately, by total characters, in DebugReport
// (strings parcel as UTF-16, so the Binder limit is about total bytes, not entry
// count) — that is what keeps the share from silently failing (Codex on PR #76).
internal const val DEBUG_LOG_MAX_ENTRY_CHARS = 2_000

/**
 * A small in-memory ring buffer of recent routing decisions and errors, kept so
 * the user can share them from Settings ("Share debug logs") when a call
 * behaves unexpectedly — the redirection service runs in the background with no
 * visible UI, so without this its decisions leave no trace the user can read.
 *
 * Recording is a synchronized in-memory append only — no disk, no IPC — so it is
 * safe to call from the decision path (AGENTS.md "Fast decision path"): the
 * service logs each verdict here off the main thread while it answers Telecom.
 * The buffer is process-lived; it is never persisted (dialed numbers are only
 * ever held redacted, see [redactNumber], and never written to disk).
 */
internal object SimmoDebugLog {
    private val buffer = ArrayDeque<String>(DEBUG_LOG_MAX_ENTRIES)
    private val timestampFormat = ThreadLocal.withInitial {
        SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    }

    fun event(message: String) {
        record('D', message, throwable = null)
        // Best-effort mirror to logcat: the in-memory buffer above is the
        // source of truth, and android.util.Log is unavailable (throws) in
        // plain JUnit tests that exercise the decision path.
        runCatching { Log.d(SIMMO_DEBUG_TAG, message) }
    }

    fun warning(message: String, throwable: Throwable? = null) {
        record('W', message, throwable)
        runCatching { Log.w(SIMMO_DEBUG_TAG, message, throwable) }
    }

    /** The captured lines, oldest first. */
    fun snapshot(): List<String> = synchronized(buffer) { buffer.toList() }

    /** Test-only: empties the ring buffer so tests start from a known state. */
    internal fun clearForTest() {
        synchronized(buffer) { buffer.clear() }
    }

    private fun record(level: Char, message: String, throwable: Throwable?) {
        val timestamp = timestampFormat.get()!!.format(Date())
        val entry = if (throwable == null) {
            "$timestamp $level $SIMMO_DEBUG_TAG: $message"
        } else {
            "$timestamp $level $SIMMO_DEBUG_TAG: $message\n${throwable.typeAndFramesOnly().trimEnd()}"
        }
        val bounded = if (entry.length > DEBUG_LOG_MAX_ENTRY_CHARS) {
            entry.take(DEBUG_LOG_MAX_ENTRY_CHARS) + "…(truncated)"
        } else {
            entry
        }
        synchronized(buffer) {
            if (buffer.size >= DEBUG_LOG_MAX_ENTRIES) buffer.removeFirst()
            buffer.addLast(bounded)
        }
    }

    // Render only the exception type and stack frames — never the throwable's
    // own message. A platform exception can populate its message with a tel:
    // URI or a raw PhoneAccountHandle, which would land in the shareable buffer
    // unredacted (Codex on PR #76); the developer-supplied [message] above
    // carries the human context instead. Also avoids android.util.Log.
    // getStackTraceString (which throws under plain JUnit) and guards against a
    // cyclic cause chain.
    private fun Throwable.typeAndFramesOnly(): String = buildString {
        val seen = java.util.Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var prefix = ""
        var t: Throwable? = this@typeAndFramesOnly
        while (true) {
            val cur = t ?: break
            if (!seen.add(cur)) break
            appendLine("$prefix${cur.javaClass.name}")
            for (frame in cur.stackTrace) appendLine("\tat $frame")
            t = cur.cause
            prefix = "Caused by: "
        }
    }
}

/**
 * A dialed number, masked for the debug log: keeps the leading digits (the
 * country/area prefix routing is decided from) and the last two, replacing the
 * subscriber digits with `•`. Enough to correlate a call and see which region
 * it routed as, without writing a full phone number into a shareable log
 * (SPEC "Permissions and privacy" keeps dialed numbers out of persisted state).
 * Short codes and blanks are returned as-is — there is nothing to protect.
 */
internal fun redactNumber(number: String): String {
    val trimmed = number.trim()
    if (trimmed.isEmpty()) return "(empty)"
    // Never reveal more than half the characters — so even a short number can't
    // be reconstructed from a shared log — while keeping a little of the
    // routing-relevant leading prefix and the last digits to correlate a call
    // (Codex on PR #76: `take(4) + takeLast(2)` disclosed 6-char numbers whole).
    val revealBudget = trimmed.length / 2
    val keepSuffix = minOf(2, revealBudget)
    val keepPrefix = minOf(3, revealBudget - keepSuffix).coerceAtLeast(0)
    val maskedLen = trimmed.length - keepPrefix - keepSuffix
    return "${trimmed.take(keepPrefix)}${"•".repeat(maskedLen)}${trimmed.takeLast(keepSuffix)} " +
        "(len=${trimmed.length})"
}

/**
 * A stable, non-identifying fingerprint of a Telecom account id. A third-party
 * ConnectionService's `PhoneAccountHandle.id` can embed a SIP username or phone
 * number, so it must never be logged verbatim (it bypasses [redactNumber]); the
 * fingerprint still correlates the same account across log lines (Codex on
 * PR #76). SIM subscriptions are named by their SIM display name elsewhere, so
 * nothing diagnostic is lost.
 */
// A process-local random salt so the account fingerprint can't be reversed by
// hashing candidate numbers / SIP ids offline (a bare id.hashCode() is a
// deterministic 32-bit value; Codex on PR #76). Regenerated each process start;
// that's fine — correlation only needs to hold within a single shared report.
private val accountSalt: Long = Random.nextLong()

internal fun redactAccountId(id: String): String {
    if (id.isBlank()) return "(none)"
    val token = (id.hashCode().toLong() * -0x61c8864680b583ebL) xor accountSalt
    return "acct:" + java.lang.Long.toHexString(token).takeLast(8)
}

/** A short, log-friendly summary of a routing decision — no full dialed number. */
internal fun Verdict.debugSummary(): String = when (this) {
    is Verdict.Proceed -> "Proceed(${reason})" +
        (announceTarget?.let { " announce=$it" } ?: "")
    is Verdict.RedirectToAccount -> "RedirectToAccount(${redactAccountId(account.id)})" +
        (newNumber?.let { " +numberCorrection" } ?: "")
    is Verdict.RedirectNumber -> "RedirectNumber(+numberCorrection)"
    is Verdict.DelayedRedirect -> "DelayedRedirect(${targetLabel}, ${delaySeconds}s)"
    is Verdict.ForwardToApp -> "ForwardToApp(${packageName})"
    is Verdict.ForwardToContactApp -> "ForwardToContactApp(${packageName})"
    is Verdict.OpenChooser -> "OpenChooser(skippedSims=${skippedInactiveSims.size}" +
        (numberCorrection?.let { ", +numberCorrection" } ?: "") + ")"
    is Verdict.BlockCall -> "BlockCall(${reason}" +
        (destination?.let { ", $it" } ?: "") + ")"
}
