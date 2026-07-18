package app.simmo.notify

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.os.Looper
import app.simmo.domain.ActiveSim
import app.simmo.domain.CorrectionCandidate
import app.simmo.domain.DataVerdict
import app.simmo.domain.GuardBlockReason
import app.simmo.domain.NumberCorrection
import app.simmo.domain.PhoneAccountRef
import app.simmo.domain.RegisteredSim
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

    private val telstra =
        ActiveSim(1, "Telstra", "Telstra AU", PhoneAccountRef("subscription:1"), countryIso = "au")
    private val tmobile =
        ActiveSim(2, "T-Mobile", "T-Mobile US", PhoneAccountRef("subscription:2"), countryIso = "us")

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
    fun `the roaming warning posts on its own channel, naming SIM and country`() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifications.postDataWatch(
            DataVerdict.RoamingWarning(tmobile, "AU", localSims = listOf(telstra)),
        )
        shadowOf(Looper.getMainLooper()).idle()
        val posted = postedNotifications().single()
        assertEquals(
            "Using data roaming",
            posted.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
        )
        assertEquals(
            "T-Mobile US in Australia",
            posted.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        )
        // Its own channel, so travelers can silence data warnings alone.
        assertEquals("data_watch", posted.channelId)
    }

    @Test
    fun `the wrong-data-SIM nudge asks with the rule's SIM and offers Switch`() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifications.postDataWatch(DataVerdict.WrongDataSim(tmobile, telstra, "AU"))
        shadowOf(Looper.getMainLooper()).idle()
        val posted = postedNotifications().single()
        assertEquals(
            "Using non-preferred SIM",
            posted.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
        )
        assertEquals(
            "Switch to Telstra AU for data?",
            posted.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        )
        // The action verb matches the message's verb (maintainer direction).
        assertEquals(listOf("Switch", "Rules"), posted.actions.map { it.title.toString() })
    }

    @Test
    fun `the no-data nudge offers Enable for a disabled local profile`() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifications.postDataWatch(
            DataVerdict.NoDataNudge(
                tmobile,
                "FR",
                enableFirst = listOf(RegisteredSim(7, "Orange", "Orange Holiday", 0L, countryIso = "fr")),
            ),
        )
        shadowOf(Looper.getMainLooper()).idle()
        val posted = postedNotifications().single()
        assertEquals(
            "No data here",
            posted.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
        )
        assertEquals(
            "Enable Orange Holiday",
            posted.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        )
        assertEquals(listOf("Enable", "Rules"), posted.actions.map { it.title.toString() })
    }

    @Test
    fun `the no-data nudge offers Switch when an active local SIM exists`() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifications.postDataWatch(
            DataVerdict.NoDataNudge(tmobile, "AU", switchTo = listOf(telstra)),
        )
        shadowOf(Looper.getMainLooper()).idle()
        val posted = postedNotifications().single()
        assertEquals(
            "Switch to Telstra AU",
            posted.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        )
        assertEquals(listOf("Switch", "Rules"), posted.actions.map { it.title.toString() })
    }

    @Test
    fun `the watch reports unpostable when its channel is blocked`() {
        // The once-per-arrival mark must not be consumed while nothing can
        // surface; canPostDataWatch is what the watch consults first.
        assertEquals(false, notifications.canPostDataWatch())
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertEquals(true, notifications.canPostDataWatch())
        NotificationManagerCompat.from(app).createNotificationChannel(
            NotificationChannelCompat.Builder("data_watch", NotificationManagerCompat.IMPORTANCE_NONE)
                .setName("Data roaming")
                .build(),
        )
        assertEquals(false, notifications.canPostDataWatch())
    }

    @Test
    fun `cancelling the data watch removes the posted warning`() {
        // The arrival ended (stale mark cleared): the shade must not keep a
        // present-tense "Using data roaming" after the trip is over.
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifications.postDataWatch(DataVerdict.RoamingWarning(tmobile, "AU"))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, postedNotifications().size)
        notifications.cancelDataWatch()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, postedNotifications().size)
    }

    @Test
    fun `a silent verdict never posts`() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifications.postDataWatch(DataVerdict.Silent)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, postedNotifications().size)
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
