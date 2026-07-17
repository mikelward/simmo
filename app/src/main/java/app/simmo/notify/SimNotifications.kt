package app.simmo.notify

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.simmo.MainActivity
import app.simmo.R
import app.simmo.domain.SimRef
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
    fun postHeldCallOffer(handle: Uri, skippedSims: List<SimRef>, simLabel: String) {
        val intent = ChooserActivity.launchIntent(context, handle, skippedSims)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        post(
            tag = TAG_HELD_CALL,
            title = context.getString(R.string.notification_held_call_title, simLabel),
            body = context.getString(
                R.string.notification_held_call_body,
                handle.schemeSpecificPart.orEmpty(),
            ),
            contentIntent = intent,
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

    private fun post(tag: String, title: String, body: String, contentIntent: Intent) {
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
    }
}
