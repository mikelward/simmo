package app.simmo

import android.app.role.RoleManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import app.simmo.domain.CallingRule
import app.simmo.domain.RegisteredSim
import app.simmo.domain.RuleAction
import app.simmo.domain.RuleMatcher
import app.simmo.domain.SimRef
import app.simmo.store.SimmoState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds a paste-into-an-issue debug report — build and device info, Simmo's
 * settings and rule/SIM counts, and the recent routing-decision log
 * ([SimmoDebugLog]) — and hands it off via [Intent.ACTION_SEND] so the share
 * sheet can deliver it. Also drops the text on the clipboard as a paste
 * fallback. Text only: the useful diagnostic is the log, and the redirection
 * service has no on-screen state a screenshot would capture, so this needs no
 * FileProvider (unlike Type Launcher's screenshot-carrying bug report).
 *
 * The payload deliberately carries no full dialed numbers or message content:
 * the log holds numbers only redacted ([redactNumber]), and the settings
 * summary reports counts, not the rules' contents.
 */
internal object DebugReport {
    /** Builds the payload, copies it to the clipboard, and fires the share chooser. */
    fun share(context: Context) {
        val appContext = context.applicationContext
        val state = (appContext as? SimmoApp)?.stateHolder()?.current
        val text = collectPayload(appContext, state)
        copyToClipboard(appContext, text)
        startShare(context, text)
    }

    private fun collectPayload(context: Context, state: SimmoState?): String {
        val fileSink = (context.applicationContext as? SimmoApp)?.debugFileSink
        val payload = buildDebugReportPayload(
            nowMillis = System.currentTimeMillis(),
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE.toLong(),
            buildType = BuildConfig.BUILD_TYPE,
            applicationId = BuildConfig.APPLICATION_ID,
            isDebuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0,
            deviceManufacturer = Build.MANUFACTURER,
            deviceModel = Build.MODEL,
            androidRelease = Build.VERSION.RELEASE,
            androidSdkInt = Build.VERSION.SDK_INT,
            locale = Locale.getDefault(),
            redirectionRoleHeld = isRedirectionRoleHeld(context),
            state = state,
            recentLog = SimmoDebugLog.snapshot(),
            // The previous run's log if it ended without a clean exit (a crash or
            // a silent kill) — read once and cleared, so it rides along in this
            // report and this report only.
            previousRun = fileSink?.readPreviousRun(),
        )
        fileSink?.clearPreviousRun()
        return payload
    }

    private fun isRedirectionRoleHeld(context: Context): Boolean =
        runCatching {
            context.getSystemService(RoleManager::class.java)
                ?.isRoleHeld(RoleManager.ROLE_CALL_REDIRECTION) == true
        }.getOrDefault(false)

    private fun startShare(context: Context, text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Simmo debug log — ${BuildConfig.VERSION_NAME}")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        val chooser = Intent.createChooser(send, context.getString(R.string.settings_share_logs))
        // The chooser is launched from a non-Activity context in some callers.
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(chooser) }
            .onFailure { SimmoDebugLog.warning("DebugReport share intent failed", it) }
    }

    private fun copyToClipboard(context: Context, text: String) {
        runCatching {
            val cm = context.getSystemService(ClipboardManager::class.java) ?: return
            cm.setPrimaryClip(ClipData.newPlainText("Simmo debug log", text))
        }.onFailure { SimmoDebugLog.warning("DebugReport clipboard copy failed", it) }
    }
}

/**
 * The pure payload builder — no Android dependencies beyond [Locale] and
 * [SimmoState] — so the report's shape is unit-testable. [state] is null when
 * the in-memory state hasn't loaded yet (cold start); the report says so
 * rather than omitting the section.
 */
internal fun buildDebugReportPayload(
    nowMillis: Long,
    versionName: String,
    versionCode: Long,
    buildType: String,
    applicationId: String,
    isDebuggable: Boolean,
    deviceManufacturer: String,
    deviceModel: String,
    androidRelease: String,
    androidSdkInt: Int,
    locale: Locale,
    redirectionRoleHeld: Boolean,
    state: SimmoState?,
    recentLog: List<String>,
    previousRun: String? = null,
): String {
    val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date(nowMillis))
    val head = buildString {
        appendLine("Simmo debug log")
        appendLine("Captured: $timestamp")
        appendLine()
        appendLine("--- Build ---")
        appendLine("Version: $versionName ($versionCode)")
        appendLine("Build type: $buildType")
        appendLine("Application id: $applicationId")
        appendLine("Debuggable: $isDebuggable")
        appendLine()
        appendLine("--- Device ---")
        appendLine("Model: $deviceManufacturer $deviceModel")
        appendLine("Android: $androidRelease (SDK $androidSdkInt)")
        appendLine("Locale: ${locale.toLanguageTag()}")
        appendLine("Redirection role held: $redirectionRoleHeld")
        appendLine()
        appendLine("--- Settings ---")
        if (state == null) {
            appendLine("(state not loaded yet)")
        } else {
            appendLine("Default region override: ${state.defaultRegionOverride ?: "(none)"}")
            appendLine("Show which SIM is used: ${state.showCallToast}")
            appendLine("Call delay: ${state.callDelaySeconds}s")
            appendLine("Correct contact numbers: ${state.correctContactNumbers}")
            appendLine("Guard overseas (hands-free): ${state.guardOverseasHandsFree}")
            appendLine("Guard disabled SIM (hands-free): ${state.guardDisabledSimHandsFree}")
            appendLine("Analytics opt-in: ${state.analyticsOptIn}")

            // The rules and SIMs in full — the point of the report is seeing
            // *which* rule fired (or didn't) and what SIM it targets, e.g. an
            // "MN → Verizon" rule that never matched. A SIM's own number rides
            // along only redacted (like dialed numbers); names, carriers,
            // countries, and subscription ids are what diagnosis needs.
            val groupNames = state.customGroups.associate { it.id to it.name }
            appendLine()
            appendLine("--- Calling rules (${state.rules.rules.size}, first match wins) ---")
            if (state.rules.rules.isEmpty()) {
                appendLine("(none)")
            } else {
                state.rules.rules.forEachIndexed { i, rule ->
                    appendLine("${i + 1}. ${describeRule(rule, groupNames)}")
                }
            }
            appendLine()
            appendLine("--- Known SIMs (${state.simRegistry.size}) ---")
            if (state.simRegistry.isEmpty()) {
                appendLine("(none)")
            } else {
                state.simRegistry.forEach { appendLine("- ${describeSim(it)}") }
            }
            appendLine()
            appendLine("--- Country groups (${state.customGroups.size}) ---")
            if (state.customGroups.isEmpty()) {
                appendLine("(none)")
            } else {
                state.customGroups.forEach { group ->
                    val members = group.regionCodes.joinToString().ifEmpty { "(empty)" }
                    // Group names are free user text; cap so one huge pasted name
                    // can't bloat the report (the whole payload is capped below too).
                    appendLine("- ${group.name.take(MAX_GROUP_NAME_CHARS)}: $members")
                }
            }
            appendLine("Data rules: ${state.dataRules.rules.size}")
        }
    }
    // Cap the structured section on its own, then always append the (already
    // bounded) newest log after it. Prefix-truncating the whole report would
    // drop the recent log — appended last — exactly when a huge rule set is what
    // pushed it over the limit, losing the diagnostic the report exists for
    // (Codex on PR #76). Both parts are bounded, so the concatenation is too:
    // strings parcel as UTF-16, so this keeps the clipboard / ACTION_SEND
    // payload well under the ~1 MB Binder limit instead of failing silently.
    val boundedHead = if (head.length > MAX_STRUCTURED_CHARS) {
        head.take(MAX_STRUCTURED_CHARS) + "\n…(details truncated to keep the report shareable)\n"
    } else {
        head
    }
    // The previous run's log if it didn't exit cleanly — its own bounded section
    // between the structured head and the current run's log, so the crash or kill
    // that ended the last run is right there without crowding either neighbor out.
    val crash = if (previousRun.isNullOrBlank()) {
        ""
    } else {
        buildString {
            appendLine()
            appendLine("--- Previous run (ended without a clean exit) ---")
            // Keep the NEWEST lines: the file is oldest-first, so the crash entry
            // and the last decisions are at the end — a head take() would drop
            // exactly the context this section exists for (Codex on PR #90).
            appendLine(
                boundedLogTail(previousRun.trimEnd().split("\n"), MAX_CRASH_PAYLOAD_CHARS)
                    .joinToString("\n"),
            )
        }
    }
    val log = buildString {
        appendLine()
        val kept = boundedLogTail(recentLog, MAX_LOG_PAYLOAD_CHARS)
        val dropped = recentLog.size - kept.size
        appendLine("--- Recent log (newest last, ${kept.size} of ${recentLog.size} shown, max $DEBUG_LOG_MAX_ENTRIES) ---")
        if (recentLog.isEmpty()) {
            appendLine("(no captured log lines — has a call been placed since the app started?)")
        } else {
            if (dropped > 0) appendLine("($dropped older line(s) omitted to keep the report shareable)")
            kept.forEach { appendLine(it) }
        }
    }
    return boundedHead + crash + log
}

/** A calling rule as one readable line: `destination → action [flags]`. */
internal fun describeRule(rule: CallingRule, groupNames: Map<String, String>): String {
    val flags = buildList {
        if (!rule.enabled) add("disabled")
        if (rule.pendingRemoval) add("deleted")
    }
    val suffix = if (flags.isEmpty()) "" else " [${flags.joinToString()}]"
    return "${describeMatcher(rule.matcher, groupNames)} → ${describeAction(rule.action)}$suffix"
}

internal fun describeMatcher(matcher: RuleMatcher, groupNames: Map<String, String>): String =
    when (matcher) {
        RuleMatcher.AnyDestination -> "Any destination"
        is RuleMatcher.Country -> matcher.regionCode.uppercase()
        is RuleMatcher.Countries -> {
            val regions = matcher.regionCodes.map { it.uppercase() }
            val groups = matcher.groupIds.map { groupNames[it] ?: it }
            (regions + groups).joinToString(" + ").ifEmpty { "(no destinations)" }
        }
    }

internal fun describeAction(action: RuleAction): String = when (action) {
    is RuleAction.UseSim -> "use SIM ${simRefLabel(action.sim)}"
    RuleAction.UseMatchingCountrySim -> "use the SIM for that country"
    is RuleAction.HandOff.ViaPhoneAccount ->
        "hand off to ${action.label.ifBlank { redactAccountId(action.account.id) }}"
    is RuleAction.HandOff.ViaDialIntent -> "hand off to ${action.app.label}"
    is RuleAction.HandOff.ViaContactApp -> "call via ${action.app.label}"
    RuleAction.Ask -> "ask"
    RuleAction.SystemDefault -> "no change"
}

/**
 * A registry SIM as one line. Every phone number is redacted: the dedicated
 * own-number field always, and the display name too when it is itself a number
 * (via [redactLabel]) — some carriers set the display name to the SIM's number.
 */
internal fun describeSim(sim: RegisteredSim): String {
    val name = redactLabel(sim.displayName).ifBlank { "(no name)" }
    val country = sim.countryIso.ifBlank { "?" }
    val dataOnly = if (!sim.callCapable) " [data-only]" else ""
    val number = if (sim.phoneNumber.isNotBlank()) ", ${redactNumber(sim.phoneNumber)}" else ""
    return "$name — ${sim.carrierName}, $country$number (${subLabel(sim.subscriptionId)})$dataOnly"
}

private fun simRefLabel(sim: SimRef): String {
    // Redact a display name that is itself a phone number, like describeSim.
    val name = redactLabel(sim.displayName.ifBlank { sim.carrierName }).ifBlank { "SIM" }
    return "$name (${subLabel(sim.subscriptionId)})"
}

private fun subLabel(subscriptionId: Int): String =
    if (subscriptionId == SimRef.INVALID_SUBSCRIPTION_ID) "unbound" else "#$subscriptionId"

/** ~0.3 MB of UTF-16 text — comfortably inside the ~1 MB Binder limit. */
private const val MAX_LOG_PAYLOAD_CHARS = 150_000

/**
 * Ceiling for the previous-run crash section. Bounded here again (it is already
 * capped when written to disk) so the three sections together — structured
 * head, crash, current log — stay well inside the Binder limit.
 */
private const val MAX_CRASH_PAYLOAD_CHARS = 100_000

/**
 * Ceiling for the structured section (build/device/settings/rules/SIMs/groups),
 * bounded separately from the log so a huge rule set can't crowd the log out.
 * With the log budget above the whole report stays well under the Binder limit.
 */
private const val MAX_STRUCTURED_CHARS = 100_000

/** Cap for a single free-text group name in the report. */
private const val MAX_GROUP_NAME_CHARS = 100

/**
 * The newest lines of [lines] (oldest-first) whose combined length fits
 * [budgetChars], returned oldest-first; at least the single newest line is kept
 * even if it alone exceeds the budget (it is already per-entry capped).
 */
internal fun boundedLogTail(lines: List<String>, budgetChars: Int): List<String> {
    val kept = ArrayDeque<String>()
    var used = 0
    for (line in lines.asReversed()) {
        val cost = line.length + 1 // + the newline appendLine adds
        if (used + cost > budgetChars && kept.isNotEmpty()) break
        kept.addFirst(line)
        used += cost
    }
    return kept
}
