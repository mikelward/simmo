package app.simmo.notify

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.os.Looper
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/**
 * The hand-off failure notice must never be silent (the user just watched the
 * wrong thing happen): it posts a notification when it can, and degrades to a
 * plain toast when POST_NOTIFICATIONS isn't granted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SimNotificationsTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val notifications = SimNotifications(app)

    private fun postedNotifications() =
        shadowOf(app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .allNotifications

    @Test
    fun `hand-off failure posts a notification when permitted`() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifications.postHandOffFailed(
            appLabel = "Google Voice",
            packageName = "com.google.android.apps.googlevoice",
            number = "+61412345678",
            placed = true,
        )
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, postedNotifications().size)
        assertNull(ShadowToast.getLatestToast())
    }

    @Test
    fun `hand-off failure falls back to a toast without the permission`() {
        // No POST_NOTIFICATIONS grant on API 33+: the notification can't post,
        // so the failure surfaces as a toast instead of disappearing.
        notifications.postHandOffFailed(
            appLabel = "Google Voice",
            packageName = "com.google.android.apps.googlevoice",
            number = "+61412345678",
            placed = true,
        )
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(0, postedNotifications().size)
        assertEquals(
            "Couldn't open Google Voice. Placed the call on your carrier instead.",
            ShadowToast.getTextOfLatestToast(),
        )
    }

    @Test
    fun `a blocked sim_assist channel also falls back to the toast`() {
        // Permission held and app-level notifications on, but the user blocked
        // just this channel: notify() would be suppressed without throwing.
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        NotificationManagerCompat.from(app).createNotificationChannel(
            NotificationChannelCompat.Builder("sim_assist", NotificationManagerCompat.IMPORTANCE_NONE)
                .setName("SIM assist")
                .build(),
        )
        notifications.postHandOffFailed(
            appLabel = "Google Voice",
            packageName = "com.google.android.apps.googlevoice",
            number = "+61412345678",
            placed = true,
        )
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(0, postedNotifications().size)
        assertEquals(
            "Couldn't open Google Voice. Placed the call on your carrier instead.",
            ShadowToast.getTextOfLatestToast(),
        )
    }

    @Test
    fun `the dropped-call toast tells the user to redial`() {
        notifications.postHandOffFailed(
            appLabel = "Viber",
            packageName = "com.viber.voip",
            number = "+61412345678",
            placed = false,
        )
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(
            "Couldn't open Viber. Redial to place your call.",
            ShadowToast.getTextOfLatestToast(),
        )
    }
}
