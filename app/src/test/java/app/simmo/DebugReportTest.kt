package app.simmo

import app.simmo.domain.CallingRule
import app.simmo.domain.CallingRuleBook
import app.simmo.domain.RegisteredSim
import app.simmo.domain.RuleAction
import app.simmo.domain.RuleMatcher
import app.simmo.domain.SimRef
import app.simmo.store.SimmoState
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugReportTest {

    private fun payload(
        state: SimmoState?,
        recentLog: List<String> = emptyList(),
    ): String = buildDebugReportPayload(
        nowMillis = 0L,
        versionName = "1.2.3+abc",
        versionCode = 42,
        buildType = "debug",
        applicationId = "app.simmo",
        isDebuggable = true,
        deviceManufacturer = "Google",
        deviceModel = "Pixel 8",
        androidRelease = "15",
        androidSdkInt = 35,
        locale = Locale.US,
        redirectionRoleHeld = true,
        state = state,
        recentLog = recentLog,
    )

    @Test
    fun `report carries build, device, and role info`() {
        val text = payload(SimmoState())
        assertTrue(text.contains("Simmo debug log"))
        assertTrue(text.contains("Version: 1.2.3+abc (42)"))
        assertTrue(text.contains("Google Pixel 8"))
        assertTrue(text.contains("Android: 15 (SDK 35)"))
        assertTrue(text.contains("Redirection role held: true"))
    }

    @Test
    fun `report includes settings and the actual rules, for diagnosis`() {
        val state = SimmoState(
            rules = CallingRuleBook(
                listOf(CallingRule(RuleMatcher.Country("MN"), RuleAction.UseSim(SimRef(3, "Verizon", "Verizon")))),
            ),
            showCallToast = true,
            callDelaySeconds = 3,
            guardOverseasHandsFree = true,
        )
        val text = payload(state)
        assertTrue(text.contains("Show which SIM is used: true"))
        assertTrue(text.contains("Call delay: 3s"))
        assertTrue(text.contains("Guard overseas (hands-free): true"))
        // The whole point: the rule is spelled out, not just counted.
        assertTrue(text.contains("Calling rules (1"))
        assertTrue(text.contains("MN → use SIM Verizon (#3)"))
        assertTrue(text.contains("Known SIMs (0)"))
    }

    @Test
    fun `rule and SIM rendering is readable and omits the SIM's own number`() {
        assertEquals(
            "MN → use SIM Verizon (#3)",
            describeRule(
                CallingRule(RuleMatcher.Country("mn"), RuleAction.UseSim(SimRef(3, "Verizon", "Verizon"))),
                emptyMap(),
            ),
        )
        assertEquals(
            "Any destination → ask [disabled]",
            describeRule(CallingRule(RuleMatcher.AnyDestination, RuleAction.Ask, enabled = false), emptyMap()),
        )
        val sim = RegisteredSim(
            subscriptionId = 3, carrierName = "Verizon", displayName = "Verizon work",
            lastSeenEpochMillis = 0L, countryIso = "US", phoneNumber = "+15551234567",
        )
        val line = describeSim(sim)
        assertTrue(line.contains("Verizon work"))
        assertTrue(line.contains("US"))
        // The SIM's own number rides along only redacted — never in full.
        assertTrue("a redacted own-number is shown", line.contains("•"))
        assertFalse("the full SIM number must not appear", line.contains("5551234567"))
    }

    @Test
    fun `describeSim redacts a display name that is itself a phone number`() {
        val sim = RegisteredSim(
            subscriptionId = 4, carrierName = "Verizon", displayName = "+15551234567",
            lastSeenEpochMillis = 0L, countryIso = "US", phoneNumber = "",
        )
        val line = describeSim(sim)
        assertTrue("phone-number display name is redacted", line.contains("•"))
        assertFalse("the full number must not appear", line.contains("15551234567"))
    }

    @Test
    fun `report includes the previous run only when one is present`() {
        assertFalse(payload(SimmoState()).contains("Previous run"))
        val withPrevious = buildDebugReportPayload(
            nowMillis = 0L, versionName = "v", versionCode = 1, buildType = "debug",
            applicationId = "app.simmo", isDebuggable = true, deviceManufacturer = "G",
            deviceModel = "P", androidRelease = "15", androidSdkInt = 35, locale = Locale.US,
            redirectionRoleHeld = true, state = SimmoState(),
            recentLog = listOf("07-20 D SimmoDebug: back up and running"),
            previousRun = "07-20 D SimmoDebug: Decision +614••78 -> Ask\n" +
                "07-20 W SimmoDebug: Uncaught exception in thread main",
        )
        assertTrue(withPrevious.contains("--- Previous run (ended without a clean exit) ---"))
        assertTrue(withPrevious.contains("Uncaught exception in thread main"))
        // The current run's log still follows the previous-run section.
        assertTrue(withPrevious.contains("back up and running"))
    }

    @Test
    fun `an oversized previous run keeps its newest lines, including the crash`() {
        // The file is oldest-first and the crash entry is last; an over-cap
        // previous run must keep the tail, not the head (Codex #90).
        val big = (0 until 300).joinToString("\n") { "line-$it " + "x".repeat(600) } +
            "\n07-20 W SimmoDebug: Uncaught exception in thread main"
        val text = buildDebugReportPayload(
            nowMillis = 0L, versionName = "v", versionCode = 1, buildType = "debug",
            applicationId = "app.simmo", isDebuggable = true, deviceManufacturer = "G",
            deviceModel = "P", androidRelease = "15", androidSdkInt = 35, locale = Locale.US,
            redirectionRoleHeld = true, state = SimmoState(), recentLog = emptyList(),
            previousRun = big,
        )
        assertTrue("the crash entry at the end survives", text.contains("Uncaught exception in thread main"))
        assertFalse("the oldest line is dropped", text.contains("line-0 "))
    }

    @Test
    fun `report notes when state has not loaded yet`() {
        val text = payload(state = null)
        assertTrue(text.contains("(state not loaded yet)"))
    }

    @Test
    fun `the shared log is bounded by total characters, keeping the newest lines`() {
        // 400 lines of 1000 chars each (~400k) must be trimmed well under the
        // ~1 MB Binder limit, and the newest lines are the ones kept.
        val lines = (0 until 400).map { "line-$it " + "x".repeat(1000) }
        val kept = boundedLogTail(lines, budgetChars = 150_000)
        assertTrue("must drop older lines", kept.size < lines.size)
        assertTrue(kept.sumOf { it.length + 1 } <= 150_000)
        assertEquals("newest line is kept", lines.last(), kept.last())
        assertFalse("oldest line is dropped", kept.contains(lines.first()))
    }

    @Test
    fun `a single oversized line is still kept`() {
        val kept = boundedLogTail(listOf("x".repeat(500_000)), budgetChars = 150_000)
        assertEquals(1, kept.size)
    }

    @Test
    fun `a huge rule set never crowds the recent log out of the report`() {
        val manyRules = CallingRuleBook(
            (0 until 4000).map {
                CallingRule(RuleMatcher.Country("MN"), RuleAction.UseSim(SimRef(it, "Carrier", "SIM $it")))
            },
        )
        val text = buildDebugReportPayload(
            nowMillis = 0L, versionName = "v", versionCode = 1, buildType = "debug",
            applicationId = "app.simmo", isDebuggable = true, deviceManufacturer = "G",
            deviceModel = "P", androidRelease = "15", androidSdkInt = 35, locale = Locale.US,
            redirectionRoleHeld = true, state = SimmoState(rules = manyRules),
            recentLog = listOf("07-20 D SimmoDebug: Decision +614••78 -> Proceed(NO_APPLICABLE_RULE)"),
        )
        assertTrue("structured section is truncated", text.contains("details truncated"))
        // The recent log — the whole point of the report — must survive.
        assertTrue("recent log section survives", text.contains("--- Recent log"))
        assertTrue("the log line survives", text.contains("Decision +614"))
    }

    @Test
    fun `report notes when older log lines were omitted`() {
        val lines = (0 until 400).map { "line-$it " + "x".repeat(1000) }
        val text = buildDebugReportPayload(
            nowMillis = 0L, versionName = "v", versionCode = 1, buildType = "debug",
            applicationId = "app.simmo", isDebuggable = true, deviceManufacturer = "G",
            deviceModel = "P", androidRelease = "15", androidSdkInt = 35, locale = Locale.US,
            redirectionRoleHeld = true, state = SimmoState(), recentLog = lines,
        )
        assertTrue(text.contains("older line(s) omitted"))
    }

    @Test
    fun `recent log lines are included verbatim, empty state is called out`() {
        assertTrue(payload(SimmoState()).contains("no captured log lines"))
        val withLog = payload(SimmoState(), recentLog = listOf("07-20 10:00:00.000 D SimmoDebug: Decision +614••78"))
        assertTrue(withLog.contains("Decision +614"))
        // The log carried only a redacted number; no full subscriber digits.
        assertFalse(withLog.contains("412345678"))
    }
}
