package app.simmo.notify

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telephony.euicc.EuiccManager
import android.widget.Toast
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.simmo.MainActivity
import app.simmo.R
import app.simmo.domain.ActiveSim
import app.simmo.domain.DataVerdict
import app.simmo.domain.GuardBlockReason
import app.simmo.domain.HeldCall
import app.simmo.domain.NumberCorrection
import app.simmo.domain.Verdict
import app.simmo.ui.ChooserActivity
import app.simmo.ui.countryDisplayName
import app.simmo.ui.simSettingsIntentCandidates

/**
 * The SIM-assist notifications (SPEC "Disabled-SIM assist", "On SIM change"):
 * the held-call offer when a wanted SIM becomes active, and the one-time
 * "new SIM" nudge. Both degrade to nothing when POST_NOTIFICATIONS isn't
 * granted — the in-app surfaces still exist — and neither ever places a call
 * itself: tapping opens the chooser (or the rules list) and the user decides.
 * The hand-off failure notice is the exception: it must never be silent (the
 * user just watched the wrong thing happen), so it degrades to a toast.
 */
class SimNotifications(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())

    fun canPost(): Boolean {
        val permitted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        return permitted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /** "Telstra is now active — place your call?" Opens the chooser; never auto-places. */
    fun postHeldCallOffer(call: HeldCall, simLabel: String, nowMillis: Long) {
        val handle = Uri.parse(call.handleUri)
        val intent = ChooserActivity.launchIntent(context, handle, call.wantedSims)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        post(
            tag = TAG_HELD_CALL,
            title = context.getString(R.string.notification_held_call_title, simLabel),
            body = context.getString(
                R.string.notification_held_call_body,
                handle.schemeSpecificPart.orEmpty(),
            ),
            contentIntent = intent,
            // The offer must not outlive the held call it represents: left in
            // the shade, the notification self-dismisses when the call's TTL
            // (measured from parking) runs out, so a stale tel: URI can't be
            // re-offered hours later (Codex on PR #21).
            timeoutMillis = (call.parkedAtMillis + HeldCall.TTL_MILLIS - nowMillis)
                .coerceAtLeast(MIN_TIMEOUT_MILLIS),
        )
    }

    /** The "new SIM seen — add rules?" nudge; opens the calling rules list and its card. */
    fun postNewSim(simLabel: String) {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_CALLING_RULES)
            // SINGLE_TOP so a foregrounded MainActivity routes ACTION_CALLING_RULES
            // through onNewIntent instead of stacking a duplicate — same as the
            // data-watch notification (Codex on PR #84).
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        post(
            // Tagged per SIM so two new SIMs get two notifications instead of
            // the second overwriting the first.
            tag = TAG_NEW_SIM + simLabel,
            title = context.getString(R.string.new_sim_prompt_title, simLabel),
            body = context.getString(R.string.new_sim_prompt_body),
            contentIntent = intent,
        )
    }

    /**
     * "Couldn't open <app>" — a hand-off rule's target app couldn't take the
     * call. [placed] is true when the carrier call went through anyway (the app
     * couldn't handle the hand-off, so the call was left unmodified) and false
     * when the call was already cancelled before the launch failed (the user
     * should redial). [Settings] opens the app's system settings (e.g. to
     * re-enable it); [Redial] re-places the call (one-tap with CALL_PHONE, else
     * opens the dialer), so the rule hands off once more now that the app works.
     */
    fun postHandOffFailed(appLabel: String, packageName: String, number: String, placed: Boolean) {
        // The channel check matters too: a user who blocked just the
        // sim_assist channel leaves canPost() true, and notify() on a blocked
        // channel is suppressed without throwing (Codex on PR #32).
        if (!canPost() || isChannelBlocked()) {
            toastHandOffFailed(appLabel, placed)
            return
        }
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(context.getString(R.string.notification_channel_name))
                .build(),
        )
        val tag = TAG_HANDOFF_FAILED + number
        val settings = activityPending(
            "$tag:settings",
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        // Prefer ACTION_CALL for a one-tap redial, but only when CALL_PHONE is
        // granted — a hand-off-only user may never have been asked for it, and
        // ACTION_CALL without the grant would just fail. Fall back to ACTION_DIAL,
        // which needs no permission (opens the dialer; placing the call re-runs
        // the rule and hands off again).
        val canPlaceCall = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED
        val redial = activityPending(
            "$tag:redial",
            Intent(
                if (canPlaceCall) Intent.ACTION_CALL else Intent.ACTION_DIAL,
                Uri.fromParts("tel", number, null),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(context.getString(R.string.handoff_failed_title, appLabel))
            .setContentText(
                context.getString(
                    if (placed) R.string.handoff_failed_body_placed else R.string.handoff_failed_body_dropped,
                ),
            )
            .setAutoCancel(true)
            .addAction(0, context.getString(R.string.handoff_failed_settings), settings)
            .addAction(0, context.getString(R.string.handoff_failed_redial), redial)
            .build()
        try {
            manager.notify(tag, NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the post.
            toastHandOffFailed(appLabel, placed)
        }
    }

    /** Whether [channelId] exists but the user has blocked it. */
    private fun isChannelBlocked(channelId: String = CHANNEL_ID): Boolean =
        NotificationManagerCompat.from(context)
            .getNotificationChannelCompat(channelId)
            ?.importance == NotificationManagerCompat.IMPORTANCE_NONE

    /**
     * Whether a data-watch warning would actually reach the user right now.
     * The watch consults this before consuming its once-per-arrival mark: no
     * in-app fallback exists until the triage card lands, so an arrival
     * claimed while nothing can surface would be lost even after a later
     * permission grant (Codex on PR #55).
     */
    fun canPostDataWatch(): Boolean = canPost() && !isChannelBlocked(DATA_CHANNEL_ID)

    /**
     * The roaming watch's warnings (SPEC "Data rules"): "Using data roaming",
     * the wrong-data-SIM arrival nudge, and the rule-less no-data nudge, all
     * on their own channel so travelers who only want the call assists can
     * silence data warnings alone. Two actions (maintainer, 2026-07): the
     * doing-action — **Enable** when the fix is a disabled local profile,
     * **Switch** otherwise — jumps to the system SIM settings where data
     * switches actually flip, and **Rules** (also the body tap) opens Simmo,
     * where the data rules screen and its triage card land next. One tag for
     * all three: a new arrival replaces the previous warning. Optional like
     * the SIM-assist nudges — no permission, no toast; the in-app surface
     * shows the same state.
     */
    fun postDataWatch(verdict: DataVerdict) {
        val (title, body) = when (verdict) {
            DataVerdict.Silent -> return
            is DataVerdict.RoamingWarning ->
                context.getString(R.string.notification_data_roaming_title) to
                    context.getString(
                        R.string.notification_data_roaming_body,
                        verdict.dataSim.label(),
                        countryDisplayName(verdict.country),
                    )
            is DataVerdict.WrongDataSim ->
                // Situation first, action question second (maintainer): the
                // title states what's happening, the body carries the verb
                // the Switch button matches.
                context.getString(R.string.notification_wrong_data_sim_title) to
                    context.getString(
                        R.string.notification_wrong_data_sim_body,
                        verdict.wantedSim.label(),
                    )
            is DataVerdict.NoDataNudge -> {
                // The body's verb matches the action button (maintainer,
                // 2026-07): switch to an active local SIM when one exists,
                // else enable the disabled local profile. The engine only
                // nudges when one of the two exists.
                val body = verdict.switchTo.firstOrNull()?.label()
                    ?.let { context.getString(R.string.notification_no_data_switch_body, it) }
                    ?: verdict.enableFirst.firstOrNull()
                        ?.let { it.displayName.ifBlank { it.carrierName } }
                        ?.let { context.getString(R.string.notification_no_data_enable_body, it) }
                    ?: return
                context.getString(R.string.notification_no_data_title) to body
            }
        }
        if (!canPost() || isChannelBlocked(DATA_CHANNEL_ID)) return
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(DATA_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(context.getString(R.string.data_watch_channel_name))
                .build(),
        )
        // "Enable" only when enabling is the whole fix — a disabled local
        // profile and nothing active to switch to; everything else is a
        // switch in the same system screen.
        val doLabel = if (verdict is DataVerdict.NoDataNudge &&
            verdict.switchTo.isEmpty() && verdict.enableFirst.isNotEmpty()
        ) {
            R.string.notification_action_enable
        } else {
            R.string.notification_action_switch
        }
        val rules = activityPending(
            "$TAG_DATA_WATCH:rules",
            // Straight to the data rules list (SPEC "Data rules"): the warning
            // is answered by recording a rule, so both the action and the body
            // tap land there. SINGLE_TOP so a foregrounded app routes through
            // onNewIntent instead of stacking a second instance.
            Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ACTION_DATA_RULES)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        val notification = NotificationCompat.Builder(context, DATA_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(rules)
            .setAutoCancel(true)
            .addAction(
                0,
                context.getString(doLabel),
                activityPending("$TAG_DATA_WATCH:settings", simSettingsLaunchIntent()),
            )
            .addAction(0, context.getString(R.string.notification_action_rules), rules)
            .build()
        try {
            manager.notify(TAG_DATA_WATCH, NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the post; optional
            // surface, the in-app state remains.
        }
    }

    /**
     * Removes a posted data-watch warning: the watch calls this when it
     * clears a stale arrival mark — the user left the marked country or the
     * data SIM changed — because a present-tense "Using data roaming" sitting
     * in the shade after the trip is over would be false (Codex on PR #55).
     * Cancelling an absent notification is a no-op, so this needs no
     * was-posted bookkeeping.
     */
    fun cancelDataWatch() {
        NotificationManagerCompat.from(context).cancel(TAG_DATA_WATCH, NOTIFICATION_ID)
    }

    /**
     * The best system SIM-settings screen as a single launchable intent — a
     * notification action can't run [openSimSettings]'s try-next fallback, so
     * the chain is resolved once at post time.
     */
    private fun simSettingsLaunchIntent(): Intent {
        val euiccEnabled = try {
            context.getSystemService(EuiccManager::class.java)?.isEnabled == true
        } catch (_: UnsupportedOperationException) {
            false
        }
        return simSettingsIntentCandidates(euiccEnabled)
            .firstOrNull { it.resolveActivity(context.packageManager) != null }
            .let { it ?: Intent(Settings.ACTION_WIRELESS_SETTINGS) }
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun ActiveSim.label(): String = displayName.ifBlank { carrierName }

    /**
     * "Call <contact>'s local number?" — a same-contact correction existed
     * but could neither be confirmed (no UI allowed) nor applied silently
     * (a shared line, or several local numbers), so the call went out as
     * dialed (SPEC "Hands-free and Android Auto safeguards"). Tapping opens
     * the chooser's number confirmation for [handle]; the in-flight call is
     * never touched and nothing is auto-placed. Needs POST_NOTIFICATIONS —
     * nothing failed here, so there is no toast fallback; the notification
     * self-dismisses so a stale tel: URI isn't offered much later.
     */
    fun postLocalNumberOffer(correction: NumberCorrection, handle: Uri) {
        val number = handle.schemeSpecificPart.orEmpty()
        val intent = ChooserActivity.launchIntent(context, handle, emptyList(), correction)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        post(
            tag = TAG_LOCAL_NUMBER + number,
            // Gate on the line being shared, not on how many candidate names
            // remain: a shared line where only one owner has a local number
            // must not presume that owner is who the user meant to reach
            // (Codex on PR #44). A sole-owner line names its contact.
            title = if (correction.sharedLine) {
                context.getString(R.string.notification_local_number_title_shared)
            } else {
                context.getString(
                    R.string.notification_local_number_title,
                    correction.candidates.first().contactName,
                )
            },
            body = context.getString(R.string.notification_local_number_body, number),
            contentIntent = intent,
            timeoutMillis = LOCAL_NUMBER_OFFER_TIMEOUT_MILLIS,
        )
    }

    /**
     * The hands-free call guard's record of a block (SPEC "Hands-free and
     * Android Auto safeguards"): the call was cancelled — the only sanctioned
     * drop — so this must NEVER be silent. When a notification can't post
     * (permission, notifications off, blocked channel), it degrades to a
     * plain text toast, like the hand-off failure notice. Tapping opens the
     * chooser for the number the user should have reached — the corrected
     * local number when a silent correction was pending, else as dialed —
     * with the disabled SIMs offered for enabling and any pending number
     * correction's choices included. No timeout: post-drive can be hours
     * away, and this notification is the only record the call didn't happen.
     */
    fun postCallBlocked(verdict: Verdict.BlockCall, handle: Uri) {
        // The redial offers the call the user should place now: a silently
        // correctable number redials as its local form.
        val redialHandle = verdict.correctedNumber
            ?.let { Uri.fromParts("tel", it, null) }
            ?: handle
        val number = redialHandle.schemeSpecificPart.orEmpty()
        val title = when (verdict.reason) {
            GuardBlockReason.OVERSEAS -> context.getString(
                R.string.guard_blocked_overseas_title,
                verdict.destination?.let { countryDisplayName(it) } ?: number,
            )
            GuardBlockReason.DISABLED_SIM -> context.getString(
                R.string.guard_blocked_sim_title,
                verdict.wantedSims.firstOrNull()
                    ?.let { it.displayName.ifBlank { it.carrierName } }
                    .orEmpty(),
            )
        }
        val posted = tryPost(
            tag = TAG_CALL_BLOCKED + number,
            title = title,
            body = context.getString(R.string.guard_blocked_body, number),
            contentIntent = ChooserActivity
                .launchIntent(context, redialHandle, verdict.wantedSims, verdict.numberCorrection)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        if (!posted) {
            // Never silent: the user's call just didn't happen. tryPost also
            // covers a permission revoked mid-post, which the generic path
            // deliberately swallows (Codex on PR #48).
            val text = context.getString(R.string.guard_blocked_toast, handle.schemeSpecificPart.orEmpty())
            mainHandler.post { Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
        }
    }

    /**
     * The settings "Show which SIM is used" announcement: "Calling using
     * <SIM>" as the redirection service routes a rule-picked call. A plain
     * text toast — permission-free, and posted from the service's background
     * context (the same "text toasts pass on Android 12+" expectation as
     * [toastHandOffFailed]; shares its device-QA item).
     */
    fun toastCallingUsing(simLabel: String) {
        val text = context.getString(R.string.calling_using, simLabel)
        mainHandler.post { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }
    }

    /**
     * The notifications-off fallback for the hand-off failure: a plain text
     * toast (still allowed from the background, unlike custom toasts). It
     * can't carry the Settings/Redial actions, but the dropped case already
     * opens the dialer with the number as its own permission-free recovery.
     * Whether the toast reliably shows from the redirection service on modern
     * Android is on the device-QA list (TODO.md Phase 5).
     */
    private fun toastHandOffFailed(appLabel: String, placed: Boolean) {
        val text = context.getString(
            if (placed) R.string.handoff_failed_toast_placed
            else R.string.handoff_failed_toast_dropped,
            appLabel,
        )
        mainHandler.post { Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
    }

    private fun activityPending(key: String, intent: Intent): PendingIntent =
        PendingIntent.getActivity(
            context,
            /* requestCode = */ key.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun post(
        tag: String,
        title: String,
        body: String,
        contentIntent: Intent,
        timeoutMillis: Long? = null,
        channelId: String = CHANNEL_ID,
    ) {
        // Failure is fine for the optional notifications: the in-app
        // surfaces still cover the same information. The guard's block uses
        // [tryPost] directly, because for it failure must fall back to a
        // toast instead of vanishing.
        tryPost(tag, title, body, contentIntent, timeoutMillis, channelId)
    }

    /**
     * Posts and reports whether the notification actually went out — false
     * when the permission is missing or was revoked mid-post, notifications
     * are off, or the channel is blocked (notify() on a blocked channel is
     * suppressed without throwing, so it must be checked, not caught).
     */
    private fun tryPost(
        tag: String,
        title: String,
        body: String,
        contentIntent: Intent,
        timeoutMillis: Long? = null,
        channelId: String = CHANNEL_ID,
    ): Boolean {
        if (!canPost() || isChannelBlocked(channelId)) return false
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(channelId, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(
                    context.getString(
                        if (channelId == DATA_CHANNEL_ID) R.string.data_watch_channel_name
                        else R.string.notification_channel_name,
                    ),
                )
                .build(),
        )
        val pending = PendingIntent.getActivity(
            context,
            /* requestCode = */ tag.hashCode(),
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .apply { timeoutMillis?.let(::setTimeoutAfter) }
            .build()
        return try {
            manager.notify(tag, NOTIFICATION_ID, notification)
            true
        } catch (_: SecurityException) {
            // Permission revoked between the check and the post.
            false
        }
    }

    companion object {
        /** Shared with the editor's notifications hint, which must treat a
         * blocked channel the same as notifications-off (Codex on PR #32). */
        internal const val CHANNEL_ID = "sim_assist"

        /** The roaming watch's own channel, so it can be silenced alone. */
        internal const val DATA_CHANNEL_ID = "data_watch"
        private const val NOTIFICATION_ID = 1
        private const val TAG_HELD_CALL = "held_call"
        private const val TAG_DATA_WATCH = "data_watch"
        private const val TAG_NEW_SIM = "new_sim:"
        private const val TAG_HANDOFF_FAILED = "handoff_failed:"
        private const val TAG_LOCAL_NUMBER = "local_number:"
        private const val TAG_CALL_BLOCKED = "call_blocked:"

        /** Same window as a held call: don't re-offer a stale tel: URI later. */
        private const val LOCAL_NUMBER_OFFER_TIMEOUT_MILLIS = 15 * 60_000L

        /**
         * Floor for the self-dismiss window: the store only hands out live
         * held calls, but a race at the TTL edge must still leave the user a
         * moment to see the offer rather than posting a zero-length one.
         */
        private const val MIN_TIMEOUT_MILLIS = 30_000L
    }
}
