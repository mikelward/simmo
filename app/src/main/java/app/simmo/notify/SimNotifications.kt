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
import android.widget.Toast
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.simmo.MainActivity
import app.simmo.R
import app.simmo.domain.HeldCall
import app.simmo.domain.NumberCorrection
import app.simmo.ui.ChooserActivity

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

    /** The "new SIM seen — add rules?" nudge; opens the rules list and its card. */
    fun postNewSim(simLabel: String) {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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

    /** Whether the sim_assist channel exists but the user has blocked it. */
    private fun isChannelBlocked(): Boolean =
        NotificationManagerCompat.from(context)
            .getNotificationChannelCompat(CHANNEL_ID)
            ?.importance == NotificationManagerCompat.IMPORTANCE_NONE

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
    ) {
        if (!canPost()) return
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(context.getString(R.string.notification_channel_name))
                .build(),
        )
        val pending = PendingIntent.getActivity(
            context,
            /* requestCode = */ tag.hashCode(),
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .apply { timeoutMillis?.let(::setTimeoutAfter) }
            .build()
        try {
            manager.notify(tag, NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the post; the in-app
            // surfaces still cover the same information.
        }
    }

    companion object {
        /** Shared with the editor's notifications hint, which must treat a
         * blocked channel the same as notifications-off (Codex on PR #32). */
        internal const val CHANNEL_ID = "sim_assist"
        private const val NOTIFICATION_ID = 1
        private const val TAG_HELD_CALL = "held_call"
        private const val TAG_NEW_SIM = "new_sim:"
        private const val TAG_HANDOFF_FAILED = "handoff_failed:"
        private const val TAG_LOCAL_NUMBER = "local_number:"

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
