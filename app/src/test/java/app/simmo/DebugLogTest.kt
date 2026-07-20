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
    fun reset() = SimmoDebugLog.clearForTest()

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
    fun `warnings capture the exception type and frames but not its message`() {
        // The throwable message can carry PII (a tel: URI, an account id), so
        // it is dropped; the developer message and the type + frames remain.
        SimmoDebugLog.warning("boom", IllegalStateException("dialed tel:+15551234567"))
        val line = SimmoDebugLog.snapshot().single()
        assertTrue(line.contains("boom"))
        assertTrue(line.contains("IllegalStateException"))
        assertTrue("stack frames are kept", line.contains("at "))
        assertFalse("the throwable message is not retained", line.contains("15551234567"))
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
