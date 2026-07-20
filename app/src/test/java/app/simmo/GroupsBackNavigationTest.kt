package app.simmo

import android.Manifest
import android.app.Application
import android.app.role.RoleManager
import android.os.Process
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowRoleManager

/**
 * The Country groups screen is a Settings sub-screen, so its header Back button
 * must return to Settings — never finish the activity and drop the user out of
 * the app, the way the old top-level "Done" button did (it was a holdover from
 * when groups were reached from the rules list).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
class GroupsBackNavigationTest {
    private val composeRule = createAndroidComposeRule<MainActivity>()

    // Hold the required grants before the activity launches so it opens straight
    // onto the rules list, past onboarding (the compose rule launches during its
    // own apply, ahead of any @Before).
    @get:Rule
    val chain: RuleChain = RuleChain
        .outerRule(
            object : org.junit.rules.TestRule {
                override fun apply(base: Statement, description: Description): Statement =
                    object : Statement() {
                        override fun evaluate() {
                            val app = ApplicationProvider.getApplicationContext<Application>()
                            shadowOf(app).grantPermissions(Manifest.permission.READ_PHONE_STATE)
                            ShadowRoleManager.addRoleHolder(
                                RoleManager.ROLE_CALL_REDIRECTION,
                                app.packageName,
                                Process.myUserHandle(),
                            )
                            base.evaluate()
                        }
                    }
            },
        )
        .around(composeRule)

    @Test
    fun `Back from groups returns to Settings without finishing the activity`() {
        composeRule.waitForIdle()

        // Rules list → Settings (the gear) → Country groups.
        composeRule.onNodeWithContentDescription("Settings").performClick()
        composeRule.onNodeWithText("Country groups").performClick()
        // On the groups screen: its add button is the marker onboarding and the
        // other screens never render.
        composeRule.onNodeWithContentDescription("New group").assertExists()

        composeRule.onNodeWithText("Back").performClick()
        composeRule.waitForIdle()

        // Back landed on Settings, not on a finished activity: the groups screen
        // is gone, Settings is showing, and the activity is still alive.
        composeRule.onNodeWithContentDescription("New group").assertDoesNotExist()
        composeRule.onNodeWithText("Settings").assertExists()
        assertFalse(composeRule.activity.isFinishing)
    }
}
