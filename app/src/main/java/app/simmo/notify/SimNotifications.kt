package app.simmo.notify

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.simmo.MainActivity
import app.simmo.R
import app.simmo.domain.HeldCall
import app.simmo.ui.ChooserActivity

/**
 * The SIM-assist notifications (SPEC "Disabled-SIM assist", "On SIM change"):
 * the held-call offer when a wanted SIM becomes active, and the one-time
 * "new SIM" nudge. Both degrade to nothing when POST_NOTIFICATIONS isn't
 * granted — the in-app surfaces still exist — and neither ever places a call
 * itself: tapping opens the chooser (or the rules list) and the user decides.
 */
class SimNotifications(private val context: Context) {

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
     * "Couldn't open <app>" — a hand-off rule's target app failed to launch, so
     * the call was placed on the carrier instead (the service launches the app
     * before cancelling, so a failed launch never strands the call). [Settings]
     * opens the app's system settings (e.g. to re-enable it); [Redial] re-places
     * the call (one-tap with CALL_PHONE, else opens the dialer with the number),
     * so the rule hands off once more now that the app is working.
     */
    fun postHandOffFailed(appLabel: String, packageName: String, number: String) {
        if (!canPost()) return
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
            .setContentText(context.getString(R.string.handoff_failed_body))
            .setAutoCancel(true)
            .addAction(0, context.getString(R.string.handoff_failed_settings), settings)
            .addAction(0, context.getString(R.string.handoff_failed_redial), redial)
            .build()
        try {
            manager.notify(tag, NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the post.
        }
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

    private companion object {
        const val CHANNEL_ID = "sim_assist"
        const val NOTIFICATION_ID = 1
        const val TAG_HELD_CALL = "held_call"
        const val TAG_NEW_SIM = "new_sim:"
        const val TAG_HANDOFF_FAILED = "handoff_failed:"

        /**
         * Floor for the self-dismiss window: the store only hands out live
         * held calls, but a race at the TTL edge must still leave the user a
         * moment to see the offer rather than posting a zero-length one.
         */
        const val MIN_TIMEOUT_MILLIS = 30_000L
    }
}
