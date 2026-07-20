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

    /**
     * External mirrors that each redacted line is fanned out to — the on-device
     * persistence file (always on) and, when Firebase is present and the user
     * has opted in, Crashlytics breadcrumbs. Only the already-redacted buffer
     * entry is passed, never a raw dialed number. Empty in plain-JUnit tests;
     * wired in SimmoApp. Kept behind this interface so this object stays
     * Android-/Firebase-free and unit-testable. Copy-on-write so fan-out never
     * blocks on registration, and each call is isolated so one sink's failure
     * can't affect another or the decision path.
     */
    private val sinks = java.util.concurrent.CopyOnWriteArrayList<Sink>()

    fun interface Sink {
        fun log(line: String)
    }

    fun addSink(sink: Sink) {
        sinks.addIfAbsent(sink)
    }

    fun removeSink(sink: Sink) {
        sinks.remove(sink)
    }

    fun event(message: String) {
        // Recording must never throw: these run on the decision path (see
        // RedirectionCoordinator), so an exception here must not preempt the
        // service's safe verdict. The in-memory buffer is best-effort (Codex #83).
        runCatching { record('D', message, throwable = null) }
        // Best-effort mirror to logcat: the in-memory buffer above is the
        // source of truth, and android.util.Log is unavailable (throws) in
        // plain JUnit tests that exercise the decision path.
        runCatching { Log.d(SIMMO_DEBUG_TAG, message) }
    }

    fun warning(message: String, throwable: Throwable? = null) {
        runCatching { record('W', message, throwable) }
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
        // Scrub the message itself, not just the throwable trace: a caller can
        // interpolate a free-form SIM/account display label (via
        // [Verdict.debugSummary]) that is itself a phone number, and every entry
        // is now exported automatically — to the persisted file ([DebugFileSink])
        // and Crashlytics breadcrumbs — not only the user-reviewed share.
        // Bounded first so scrubbing stays cheap on the decision path (Codex #90).
        val safeMessage = scrubPii(message.take(DEBUG_LOG_MAX_ENTRY_CHARS))
        val entry = if (throwable == null) {
            "$timestamp $level $SIMMO_DEBUG_TAG: $safeMessage"
        } else {
            "$timestamp $level $SIMMO_DEBUG_TAG: $safeMessage\n${throwable.sanitizedTrace().trimEnd()}"
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
        // Fan the redacted entry out to every mirror after it is safely in the
        // buffer. Each is isolated and best-effort: a sink failure must never
        // propagate onto the decision path (Codex #83), and sinks enqueue rather
        // than block, so this stays off the response path (Codex #90).
        sinks.forEach { runCatching { it.log(bounded) } }
    }

    /** Test-only: detaches all mirrors so tests don't leak a sink into each other. */
    internal fun clearSinksForTest() {
        sinks.clear()
    }

    // Render the exception type, its message, and stack frames. A platform
    // exception can put a tel: URI, a number, or a sip:/email account id in its
    // message, so the message is run through [scrubPii] rather than dropped —
    // keeping the useful "why" (e.g. "permission denied") while masking those
    // (Codex on PR #76/#83). Avoids android.util.Log.getStackTraceString (which
    // throws under plain JUnit) and guards against a cyclic cause chain.
    private fun Throwable.sanitizedTrace(): String = buildString {
        val seen = java.util.Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var prefix = ""
        var t: Throwable? = this@sanitizedTrace
        while (true) {
            val cur = t ?: break
            if (!seen.add(cur)) break
            // Bound the message before scrubbing: the whole entry is capped
            // below anyway, and scrubbing an unbounded message (a huge non-email
            // token backtracks the identifier regex) would burn CPU on the
            // decision path before the watchdog fires (Codex on PR #83).
            // Read the message defensively — a getMessage() override can throw,
            // and this trace is rendered while logging another exception on the
            // decision path, so we keep the type + frames rather than let it
            // escape (Codex on PR #83). null message → type + frames only.
            val msg = runCatching { cur.message }.getOrNull()
                ?.take(DEBUG_LOG_MAX_ENTRY_CHARS)?.let { scrubPii(it) }
            appendLine(prefix + cur.javaClass.name + (msg?.let { ": $it" } ?: ""))
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

// `(?U)` puts the matcher in Unicode mode so `\d`/`\s` cover localized digits
// (Arabic-Indic, Devanagari, …) and Unicode spaces, not just ASCII; the class
// also spells out Unicode space separators (`\p{Zs}`), dashes (`\p{Pd}`), and
// open/close punctuation (`\p{Ps}`/`\p{Pe}`, which covers both ASCII `()` and
// full-width `（）` U+FF08/FF09), plus full-width dot/slash. So a number
// formatted for any locale — separators or digits, ASCII or full-width — can't
// slip past the redaction (Codex on PR #83: full-width parens split the run and
// each fragment fell under the gate). The 6+-digit gate below decides a match.
private val PHONE_LIKE = Regex("""(?U)\+?\d[\d\s\p{Zs}\p{Pd}\p{Ps}\p{Pe}./．／]{3,}\d""")
// `tel:`/`sip:` URIs are masked whole (`(?:tel|sip):\S+`), so a vanity number
// whose letters would otherwise split the digit run below the gate — e.g.
// tel:1-800-FLOWERS — is still redacted (Codex on PR #83).
// `(?U)` so `\w` covers Unicode letters too — an internationalized email/SIP id
// (álîçé@example.com, alice@例え.テスト) must be masked like an ASCII one (Codex #83).
// The host part does not require a dot, so a single-label SIP host (alice@pbx, an
// internal PBX) is masked too, not just dotted domains (Codex on PR #83).
private val IDENTIFIER_LIKE = Regex("""(?U)(?:tel|sip):\S+|[\w.+-]+@[\w.-]+""", RegexOption.IGNORE_CASE)

/**
 * Masks personal identifiers inside free text — e.g. an exception message that
 * rendered a `tel:` URI, a raw number, a `sip:` URI, or an email/SIP username —
 * so a message can be kept for its context without leaking one. Phone-number-like
 * runs (6+ digits — the shortest a subscriber number reaches; [redactNumber]
 * masks that safely) go through [redactNumber]; `sip:`/email-style account ids
 * are masked whole. Shorter digit runs (line numbers, subscription ids, short
 * codes) and ordinary words are left alone. Best-effort: a bare opaque token with
 * no recognizable shape can't be detected — the airtight alternative is dropping
 * messages entirely (Codex on PR #83).
 */
internal fun scrubPii(text: String): String {
    val idsMasked = IDENTIFIER_LIKE.replace(text) { "•••" }
    return PHONE_LIKE.replace(idsMasked) { match ->
        if (match.value.count(Char::isDigit) >= 6) redactNumber(match.value) else match.value
    }
}

/**
 * A SIM display name or rule label with any embedded phone number / identifier
 * masked. Platform display names are free-form — a carrier may set the SIM's own
 * number as the name, or "Work +1 555 123 4567" — so numbers are scrubbed
 * wherever they appear, not only when the whole label is a number (Codex on
 * PR #83). Ordinary labels ("Personal", "Telstra", "SIM 2") pass through.
 */
internal fun redactLabel(label: String): String = scrubPii(label)

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
