package app.simmo.ui

import android.Manifest
import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import androidx.test.core.app.ApplicationProvider
import app.simmo.domain.ContactCallApp
import app.simmo.domain.DialHandoffApp
import app.simmo.domain.Rule as SimmoRule
import app.simmo.domain.RuleAction
import app.simmo.domain.RuleMatcher
import app.simmo.domain.SimRef
import app.simmo.notify.SimNotifications
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The editor's notifications coverage for hand-off actions: picking one asks
 * for POST_NOTIFICATIONS (WhatsApp through the contacts-then-notifications
 * chain), and while notifications stay off the selected action shows a
 * tappable enable hint.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
class RuleEditorNotificationsHintTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val telstra = SimRef(1, "Telstra", "Telstra AU")

    private fun setEditor(
        notificationsEnabled: Boolean,
        action: RuleAction = RuleAction.UseMatchingCountrySim,
        onRequestNotifications: () -> Unit = {},
        onContactHandOffPicked: () -> Unit = {},
        onEnableNotifications: () -> Unit = {},
    ) {
        composeRule.setContent {
            MaterialTheme {
                RuleEditorContent(
                    target = EditorTarget.Existing("r", SimmoRule(RuleMatcher.Country("US"), action)),
                    simOptions = listOf(SimOptionUi(telstra, "Telstra AU", active = true)),
                    countryOptions = listOf(CountryOptionUi("US", "+1 United States")),
                    handOffApps = setOf(ContactCallApp.WHATSAPP),
                    dialHandoffApps = setOf(DialHandoffApp.GOOGLE_VOICE),
                    notificationsEnabled = notificationsEnabled,
                    onRequestNotifications = onRequestNotifications,
                    onContactHandOffPicked = onContactHandOffPicked,
                    onEnableNotifications = onEnableNotifications,
                    onSave = { _, _ -> },
                    onDelete = null,
                    onCancel = {},
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun hint(app: String) =
        "Let Simmo tell you if there's a $app problem"

    @Test
    fun `picking a dial hand-off asks for notifications and shows the hint`() {
        var asked = 0
        setEditor(notificationsEnabled = false, onRequestNotifications = { asked++ })
        composeRule.onNodeWithText(hint("Google Voice")).assertDoesNotExist()

        composeRule.onNodeWithText("Google Voice").performClick()
        composeRule.waitForIdle()

        assertEquals(1, asked)
        composeRule.onNodeWithText(hint("Google Voice")).assertExists()
    }

    @Test
    fun `picking WhatsApp goes through the contacts-then-notifications chain`() {
        var picked = 0
        setEditor(notificationsEnabled = false, onContactHandOffPicked = { picked++ })

        composeRule.onNodeWithText("WhatsApp").performClick()
        composeRule.waitForIdle()

        assertEquals(1, picked)
        composeRule.onNodeWithText(hint("WhatsApp")).assertExists()
    }

    @Test
    fun `the hint's Allow button requests the fix`() {
        var enabled = 0
        setEditor(
            notificationsEnabled = false,
            // An existing hand-off rule shows the hint on open, no click needed
            // — the case of rules saved before notifications were turned off.
            action = RuleAction.HandOff.ViaDialIntent(DialHandoffApp.GOOGLE_VOICE),
            onEnableNotifications = { enabled++ },
        )

        composeRule.onNodeWithText(hint("Google Voice")).assertExists()
        composeRule.onNodeWithText("Allow").performClick()
        composeRule.waitForIdle()

        assertEquals(1, enabled)
    }

    @Test
    fun `no hint while notifications are on`() {
        setEditor(
            notificationsEnabled = true,
            action = RuleAction.HandOff.ViaDialIntent(DialHandoffApp.GOOGLE_VOICE),
        )
        composeRule.onNodeWithText(hint("Google Voice")).assertDoesNotExist()
    }

    @Test
    fun `a blocked sim_assist channel counts as notifications off`() {
        // App-level notifications on and the permission granted, but the
        // channel itself blocked: notify() would be silently suppressed, so
        // the hint must stay up and keep offering the settings path.
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(areNotificationsEnabled(app))

        NotificationManagerCompat.from(app).createNotificationChannel(
            NotificationChannelCompat.Builder(
                SimNotifications.CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_NONE,
            ).setName("SIM assist").build(),
        )

        assertFalse(areNotificationsEnabled(app))
    }
}
