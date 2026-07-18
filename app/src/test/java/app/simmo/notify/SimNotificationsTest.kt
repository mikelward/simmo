package app.simmo.notify

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.os.Looper
import app.simmo.domain.CorrectionCandidate
import app.simmo.domain.GuardBlockReason
import app.simmo.domain.NumberCorrection
import app.simmo.domain.SimRef
import app.simmo.domain.Verdict
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
    fun `the local-number offer posts when permitted and skips silently otherwise`() {
        val correction = NumberCorrection(listOf(CorrectionCandidate("Mum", "+61412345678")))
        // Without POST_NOTIFICATIONS: nothing failed, so no toast fallback
        // either — the ambiguous call simply proceeded as dialed.
        notifications.postLocalNumberOffer(correction, Uri.parse("tel:+442071234567"))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, postedNotifications().size)
        assertNull(ShadowToast.getLatestToast())

        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifications.postLocalNumberOffer(correction, Uri.parse("tel:+442071234567"))
        shadowOf(Looper.getMainLooper()).idle()
        val posted = postedNotifications().single()
        assertEquals(
            "Call Mum's local number?",
            posted.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
        )
    }

    @Test
    fun `a shared-line offer never names one owner in its title`() {
        // Mum and Aunt Vi share the dialed line and only Mum has a local
        // number: "Call Mum's local number?" would presume Mum was who the
        // user meant to reach (Codex on PR #44) — stay generic.
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifications.postLocalNumberOffer(
            NumberCorrection(
                listOf(CorrectionCandidate("Mum", "+61412345678")),
                sharedLine = true,
            ),
            Uri.parse("tel:+442071234567"),
        )
        shadowOf(Looper.getMainLooper()).idle()
        val posted = postedNotifications().single()
        assertEquals(
            "Shared number — call a local one?",
            posted.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
        )
    }

    @Test
    fun `a guard block posts its notification when permitted`() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifications.postCallBlocked(
            Verdict.BlockCall(GuardBlockReason.OVERSEAS, destination = "GB"),
            Uri.parse("tel:+442071234567"),
        )
        shadowOf(Looper.getMainLooper()).idle()
        val posted = postedNotifications().single()
        assertEquals(
            "Blocked a call to United Kingdom",
            posted.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
        )
        assertNull(ShadowToast.getLatestToast())
    }

    @Test
    fun `a guard block is NEVER silent - it degrades to a toast`() {
        // The guard is the only sanctioned drop (SPEC): with notifications
        // unavailable the block must still reach the user somehow.
        notifications.postCallBlocked(
            Verdict.BlockCall(GuardBlockReason.OVERSEAS, destination = "GB"),
            Uri.parse("tel:+442071234567"),
        )
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, postedNotifications().size)
        assertEquals(
            "Simmo blocked your call to +442071234567",
            ShadowToast.getTextOfLatestToast(),
        )
    }

    @Test
    fun `a blocked channel also degrades the guard to a toast`() {
        // notify() on a blocked channel is suppressed without throwing, so
        // the guard must check, not catch (Codex on PR #48).
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        NotificationManagerCompat.from(app).createNotificationChannel(
            NotificationChannelCompat.Builder("sim_assist", NotificationManagerCompat.IMPORTANCE_NONE)
                .setName("SIM assist")
                .build(),
        )
        notifications.postCallBlocked(
            Verdict.BlockCall(GuardBlockReason.OVERSEAS, destination = "GB"),
            Uri.parse("tel:+442071234567"),
        )
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, postedNotifications().size)
        assertEquals(
            "Simmo blocked your call to +442071234567",
            ShadowToast.getTextOfLatestToast(),
        )
    }

    @Test
    fun `a disabled-SIM block names the wanted SIM`() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifications.postCallBlocked(
            Verdict.BlockCall(
                GuardBlockReason.DISABLED_SIM,
                wantedSims = listOf(SimRef(7, "Vodafone", "Voda AU")),
            ),
            Uri.parse("tel:+61412345678"),
        )
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(
            "Blocked a call: Voda AU is disabled",
            postedNotifications().single().extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
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
