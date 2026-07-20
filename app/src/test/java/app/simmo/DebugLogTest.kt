package app.simmo

import app.simmo.domain.GuardBlockReason
import app.simmo.domain.PhoneAccountRef
import app.simmo.domain.ProceedReason
import app.simmo.domain.Verdict
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DebugLogTest {

    @Before
    @After
    fun reset() {
        SimmoDebugLog.clearForTest()
        SimmoDebugLog.clearSinksForTest()
    }

    @Test
    fun `events are captured oldest first`() {
        SimmoDebugLog.event("first")
        SimmoDebugLog.event("second")
        val lines = SimmoDebugLog.snapshot()
        assertEquals(2, lines.size)
        assertTrue(lines[0].endsWith("first"))
        assertTrue(lines[1].endsWith("second"))
    }

    @Test
    fun `warnings keep the exception message but scrub numbers from it`() {
        SimmoDebugLog.warning("boom", IllegalStateException("failed for tel:+15551234567"))
        val line = SimmoDebugLog.snapshot().single()
        assertTrue(line.contains("boom"))
        assertTrue(line.contains("IllegalStateException"))
        assertTrue("the message context is kept", line.contains("failed for"))
        assertTrue("stack frames are kept", line.contains("at "))
        assertFalse("the number is scrubbed from the message", line.contains("15551234567"))
    }

    @Test
    fun `scrubPii masks numbers and account ids in free text but keeps the rest`() {
        val scrubbed = scrubPii("redirect to tel:+15551234567 failed")
        assertTrue(scrubbed.contains("redirect to"))
        assertTrue(scrubbed.contains("failed"))
        assertFalse(scrubbed.contains("15551234567"))
        // Email / SIP account ids are masked too (Codex #83).
        assertFalse(scrubPii("account alice@example.com denied").contains("alice@example.com"))
        assertFalse(scrubPii("via sip:alice@voip.example;9991234567").contains("alice"))
        // Internationalized (Unicode) emails are masked too (Codex #83).
        assertFalse(scrubPii("account álîçé@example.com denied").contains("álîçé"))
        assertFalse(scrubPii("to alice@例え.テスト failed").contains("例え"))
        // Slash-formatted numbers (e.g. German 030/12 34 56) too.
        val slash = scrubPii("fax 030/12 34 56 done")
        assertTrue(slash.contains("fax"))
        assertFalse("slash-formatted number is masked", slash.contains("34"))
        // Short digit runs (line numbers, subscription ids) are left alone.
        assertEquals("at Foo.kt:163 sub=3", scrubPii("at Foo.kt:163 sub=3"))
        // A single-label SIP host (internal PBX) is masked, not just dotted ones.
        assertFalse(scrubPii("account alice@pbx denied").contains("alice@pbx"))
        // A six-digit subscriber number is masked (redactNumber handles it safely).
        val sixDigit = scrubPii("dialed 123456 failed")
        assertTrue(sixDigit.contains("dialed"))
        assertFalse("six-digit number is masked", sixDigit.contains("123456"))
        // A vanity tel: number (letters split the digit run) is masked whole.
        val vanity = scrubPii("dial tel:1-800-FLOWERS now")
        assertTrue(vanity.contains("dial"))
        assertFalse("vanity tel: number is masked", vanity.contains("FLOWERS"))
    }

    @Test
    fun `sinks mirror captured lines and a failing sink cannot break logging`() {
        val mirrored = mutableListOf<String>()
        SimmoDebugLog.addSink { mirrored += it }
        SimmoDebugLog.event("Decision +614••78 -> Proceed")
        assertEquals(1, mirrored.size)
        assertTrue(mirrored.single().endsWith("Decision +614••78 -> Proceed"))

        // A second, throwing sink must not propagate onto the decision path, and
        // the line is still captured in the buffer and delivered to the good sink.
        SimmoDebugLog.addSink { throw RuntimeException("sink down") }
        SimmoDebugLog.event("still recorded")
        assertTrue(SimmoDebugLog.snapshot().last().endsWith("still recorded"))
        assertTrue("fan-out reaches the healthy sink", mirrored.last().endsWith("still recorded"))
    }

    @Test
    fun `a raw number interpolated into a message is scrubbed everywhere it is exported`() {
        // A verdict summary can carry a free-form SIM/account label that is
        // itself a number (e.g. a delayed-redirect target). It must be scrubbed
        // in the buffer (shared report + crash file) and in the mirror
        // (Crashlytics breadcrumbs) alike (Codex #90).
        val mirrored = mutableListOf<String>()
        SimmoDebugLog.addSink { mirrored += it }
        SimmoDebugLog.event("DelayedRedirect(+1 555 123 4567, 3s)")
        val line = SimmoDebugLog.snapshot().single()
        assertTrue(line.contains("DelayedRedirect"))
        assertFalse("scrubbed in the buffer", line.contains("123 4567"))
        assertFalse("scrubbed in the mirror", mirrored.single().contains("123 4567"))
    }

    @Test
    fun `a throwable whose getMessage throws cannot escape logging`() {
        val evil = object : RuntimeException() {
            override val message: String get() = throw IllegalStateException("boom in getMessage")
        }
        // Must not throw — logging runs on the decision path and must never
        // preempt the service's safe verdict (Codex #83).
        SimmoDebugLog.warning("logging evil", evil)
        val line = SimmoDebugLog.snapshot().single()
        assertTrue("the developer message is kept", line.contains("logging evil"))
        assertTrue("stack frames are still rendered", line.contains("at "))
    }

    @Test
    fun `scrubPii catches numbers formatted with unicode separators`() {
        val nnbsp = "\u202F" // narrow no-break space, used to group digits in fr-FR
        val spaced = scrubPii("call +33${nnbsp}6${nnbsp}12${nnbsp}34${nnbsp}56${nnbsp}78 dropped")
        assertTrue(spaced.contains("call"))
        assertTrue(spaced.contains("\u2022"))
        assertFalse("unicode-spaced number is masked", spaced.contains("56"))
        // Unicode (non-breaking / en) dashes too.
        val dashed = scrubPii("number 555\u20112345\u201367 failed")
        assertTrue(dashed.contains("\u2022"))
        assertFalse("unicode-dashed number is masked", dashed.contains("2345"))
    }

    @Test
    fun `scrubPii catches numbers formatted with full-width punctuation`() {
        // Full-width parens (U+FF08/FF09) are Unicode Ps/Pe, not Zs/Pd — without
        // them in the separator class the run split into sub-7-digit fragments and
        // the whole number leaked (Codex #83). Full-width digits are Nd, so the
        // gate still counts them.
        val fullWidth = scrubPii("dialed ０３（１２３４）５６７８ failed")
        assertTrue(fullWidth.contains("dialed"))
        assertTrue(fullWidth.contains("•"))
        assertFalse("full-width number is masked", fullWidth.contains("５６７８"))
    }

    @Test
    fun `scrubPii catches numbers written in localized digits`() {
        // Eastern Arabic-Indic digits \u0661\u0662\u0663\u0664\u0665\u0666\u0667\u0668 \u2014 JVM \d is ASCII-only without (?U).
        val arabic = "\u0661\u0662\u0663\u0664\u0665\u0666\u0667\u0668"
        val scrubbed = scrubPii("sim $arabic here")
        assertTrue(scrubbed.contains("sim"))
        assertTrue(scrubbed.contains("\u2022"))
        assertFalse("localized-digit number is masked", scrubbed.contains(arabic))
    }

    @Test
    fun `redactLabel masks numbers anywhere in a label but leaves ordinary labels`() {
        assertEquals("Personal", redactLabel("Personal"))
        assertEquals("Telstra", redactLabel("Telstra"))
        assertEquals("SIM 2", redactLabel("SIM 2"))
        // A whole-number name and a number embedded in text are both masked.
        assertFalse(redactLabel("+1 555 123 4567").contains("5551234567"))
        val embedded = redactLabel("Work +1 555 123 4567")
        assertTrue("surrounding text is kept", embedded.contains("Work"))
        assertTrue(embedded.contains("•"))
        assertFalse(embedded.contains("123 4567"))
    }

    @Test
    fun `oversized entries are truncated so the shared payload stays bounded`() {
        SimmoDebugLog.warning("huge", RuntimeException("x".repeat(50_000)))
        val line = SimmoDebugLog.snapshot().single()
        assertTrue(line.length <= DEBUG_LOG_MAX_ENTRY_CHARS + "…(truncated)".length)
        assertTrue(line.endsWith("…(truncated)"))
    }

    @Test
    fun `the buffer is bounded and evicts the oldest entries`() {
        repeat(DEBUG_LOG_MAX_ENTRIES + 25) { SimmoDebugLog.event("entry-$it") }
        val lines = SimmoDebugLog.snapshot()
        assertEquals(DEBUG_LOG_MAX_ENTRIES, lines.size)
        // The first 25 were evicted; entry-25 is now the oldest.
        assertTrue(lines.first().endsWith("entry-25"))
        assertTrue(lines.last().endsWith("entry-${DEBUG_LOG_MAX_ENTRIES + 24}"))
    }

    @Test
    fun `redactNumber masks the subscriber digits but keeps prefix and length`() {
        val redacted = redactNumber("+61412345678")
        // A little of the leading prefix and the last two digits survive; the
        // middle is masked and the length is preserved for correlation.
        assertTrue(redacted.startsWith("+61"))
        assertTrue(redacted.contains("78"))
        assertTrue(redacted.contains("len=12"))
        assertTrue(redacted.contains("•"))
        assertFalse("full number must not appear", redacted.contains("412345678"))
    }

    @Test
    fun `redactNumber never reveals more than half of a short number`() {
        // Regression: take(4)+takeLast(2) disclosed a 6-char number whole.
        val six = redactNumber("123456")
        assertFalse("6-char number must not appear in full", six.contains("123456"))
        assertTrue(six.contains("•"))
        assertTrue(six.contains("len=6"))
        // At least half the digits are masked, whatever the length.
        listOf("123456", "1234567", "150", "0000").forEach { number ->
            val masked = redactNumber(number).substringBefore(" (len=").count { it == '•' }
            assertTrue("$number should mask at least half", masked * 2 >= number.length)
        }
    }

    @Test
    fun `redactNumber reports blanks`() {
        assertEquals("(empty)", redactNumber(""))
        assertEquals("(empty)", redactNumber("   "))
    }

    @Test
    fun `redactAccountId fingerprints, never revealing the raw id`() {
        val id = "sip:alice@example.com;12065551234"
        val fp = redactAccountId(id)
        assertTrue(fp.startsWith("acct:"))
        assertFalse(fp.contains("alice"))
        assertFalse(fp.contains("12065551234"))
        // Stable: the same id fingerprints identically, so lines correlate.
        assertEquals(fp, redactAccountId(id))
        assertEquals("(none)", redactAccountId(""))
    }

    @Test
    fun `verdict summaries name the outcome without a full number`() {
        assertEquals(
            "Proceed(NO_APPLICABLE_RULE)",
            Verdict.Proceed(ProceedReason.NO_APPLICABLE_RULE).debugSummary(),
        )
        // The opaque account id is fingerprinted, never logged verbatim.
        val redirect = Verdict.RedirectToAccount(PhoneAccountRef("sip:alice@example.com")).debugSummary()
        assertTrue(redirect.startsWith("RedirectToAccount(acct:"))
        assertFalse(redirect.contains("alice"))
        assertEquals(
            "BlockCall(OVERSEAS, US)",
            Verdict.BlockCall(GuardBlockReason.OVERSEAS, destination = "US").debugSummary(),
        )
    }
}
