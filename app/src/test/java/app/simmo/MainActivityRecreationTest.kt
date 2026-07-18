package app.simmo

import android.Manifest
import android.app.Application
import android.app.role.RoleManager
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Onboarding's Continue button must stay the only exit across recreation:
 * granting the required rows and then rotating must restore the in-progress
 * onboarding, not recompute readiness and jump to the rules list (Codex on
 * PR #32).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
class MainActivityRecreationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun `granting required rows then recreating stays on onboarding`() {
        // Launched with neither required grant: onboarding, no exit yet.
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Continue").assertDoesNotExist()

        // The user works through the system dialogs...
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.READ_PHONE_STATE)
        shadowOf(app.getSystemService(RoleManager::class.java))
            .addHeldRole(RoleManager.ROLE_CALL_REDIRECTION)

        // ...then the activity is recreated (rotation). The saved in-progress
        // state must win over recomputing from the now-held grants, so the
        // optional rows and Continue are still on screen.
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Continue").assertExists()
        composeRule.onNodeWithText("Calling rules").assertDoesNotExist()
    }
}
