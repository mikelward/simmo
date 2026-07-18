package app.simmo.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.simmo.OnboardingScreen
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders first-launch onboarding with both grants outstanding. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OnboardingScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun onboarding_showsBothGrantRequests() {
        composeRule.setContent {
            MaterialTheme {
                OnboardingScreen(
                    isRoleHeld = { false },
                    isPhonePermissionGranted = { false },
                    requestRoleIntent = { Intent() },
                    onPhonePermissionGranted = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Call using the right SIM").assertExists()
        composeRule.onNodeWithText("See your SIMs").assertExists()
        composeRule.onNodeWithText("Make Simmo better").assertExists()
        // No exit while the required grants are outstanding.
        composeRule.onNodeWithText("Skip").assertDoesNotExist()
        composeRule.onNodeWithText("Continue").assertDoesNotExist()
        captureSnapshot("onboarding.png")
    }

    @Test
    fun onboarding_requiredGranted_offersOptionalRowsAndContinue() {
        composeRule.setContent {
            MaterialTheme {
                OnboardingScreen(
                    isRoleHeld = { true },
                    isPhonePermissionGranted = { true },
                    isNotificationsGranted = { false },
                    isCallPermissionGranted = { false },
                    isContactsPermissionGranted = { false },
                    requestRoleIntent = { Intent() },
                    onPhonePermissionGranted = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Show errors and shortcuts").assertExists()
        composeRule.onNodeWithText("Retry failed calls").assertExists()
        composeRule.onNodeWithText("Call your contacts").assertExists()
        // Skip is the exit while optional grants are outstanding; Continue is
        // the affirmative one and stays disabled until everything is on.
        composeRule.onNodeWithText("Skip").assertIsEnabled()
        composeRule.onNodeWithText("Continue").assertIsNotEnabled()
        // "All set" would contradict the Allow buttons still on screen.
        composeRule.onNodeWithText("All set. Your calls now follow your rules.").assertDoesNotExist()
        captureSnapshot("onboarding_optional.png")
    }

    @Test
    fun onboarding_advancesOnlyViaButtons() {
        var advanced = 0
        composeRule.setContent {
            MaterialTheme {
                OnboardingScreen(
                    isRoleHeld = { true },
                    isPhonePermissionGranted = { true },
                    requestRoleIntent = { Intent() },
                    onPhonePermissionGranted = {},
                    onAllGranted = { advanced++ },
                )
            }
        }
        composeRule.waitForIdle()

        // Required grants held, but the optional rows must not be yanked away:
        // only the buttons leave onboarding. With every grant and the opt-in
        // on (the defaults here), Continue is enabled.
        assertEquals(0, advanced)
        composeRule.onNodeWithText("Continue").assertIsEnabled().performClick()
        composeRule.waitForIdle()
        assertEquals(1, advanced)
    }

    @Test
    fun onboarding_skipAdvancesWithOptionalOutstanding() {
        var advanced = 0
        composeRule.setContent {
            MaterialTheme {
                OnboardingScreen(
                    isRoleHeld = { true },
                    isPhonePermissionGranted = { true },
                    isNotificationsGranted = { false },
                    isCallPermissionGranted = { false },
                    isContactsPermissionGranted = { false },
                    requestRoleIntent = { Intent() },
                    onPhonePermissionGranted = {},
                    onAllGranted = { advanced++ },
                )
            }
        }
        composeRule.waitForIdle()

        // Optional grants never gate leaving: Skip works while they're
        // outstanding (Continue is disabled in this state, asserted above).
        composeRule.onNodeWithText("Skip").performClick()
        composeRule.waitForIdle()
        assertEquals(1, advanced)
    }

    private fun captureSnapshot(name: String, widthPx: Int = 1080, heightPx: Int = 1920) {
        val isRecord = System.getProperty("roborazzi.test.record") == "true"
        val isVerify = System.getProperty("roborazzi.test.verify") == "true"
        if (!isRecord && !isVerify) return
        val root = composeRule.activity.window.decorView.rootView
        root.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, widthPx, heightPx)
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        bitmap.captureRoboImage(filePath = "src/test/snapshots/images/$name")
    }
}
