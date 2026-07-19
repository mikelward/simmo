package app.simmo.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders the Settings screen: the SIMs entry and the app-level options. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun settings() {
        composeRule.setContent {
            MaterialTheme {
                SettingsContent(
                    settings = SettingsUi(
                        showCallToast = true,
                        callDelaySeconds = 3,
                        correctContactNumbers = true,
                        guardOverseasHandsFree = true,
                        analyticsOptIn = true,
                    ),
                    versionName = "1.4.87+ab12cd",
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Settings").assertExists()
        composeRule.onNodeWithText("SIMs").assertExists()
        // Country groups moved here from the rules toolbar.
        composeRule.onNodeWithText("Country groups").assertExists()
        composeRule.onNodeWithText("Custom sets of countries a rule can match").assertExists()
        composeRule.onNodeWithText("Show which SIM or app is used").assertExists()
        composeRule.onNodeWithText("Delay before calling").assertExists()
        composeRule.onNodeWithText("3 seconds").assertExists()
        composeRule.onNodeWithText("Use contacts' local numbers").assertExists()
        composeRule.onNodeWithText("Hands-free guard").assertExists()
        composeRule.onNodeWithText("Overseas calls").assertExists()
        composeRule.onNodeWithText("Calls needing a disabled SIM").assertExists()
        composeRule.onNodeWithText("Make Simmo better").assertExists()
        // Privacy policy link and version sit at the foot of the page.
        composeRule.onNodeWithText("Privacy policy").assertExists()
        composeRule.onNodeWithText("Version 1.4.87+ab12cd").assertExists()
        // Tall enough to show the whole scrolling page, including the footer.
        captureSnapshot("settings.png", heightPx = 2150)
    }

    @Test
    fun tappingCountryGroupsOpensThem() {
        var opened = false
        composeRule.setContent {
            MaterialTheme {
                SettingsContent(onOpenGroups = { opened = true })
            }
        }
        composeRule.onNodeWithText("Country groups").performClick()
        composeRule.runOnIdle { assertEquals(true, opened) }
    }

    @Test
    fun tappingPrivacyPolicyOpensIt() {
        var opened = false
        composeRule.setContent {
            MaterialTheme {
                SettingsContent(onOpenPrivacyPolicy = { opened = true })
            }
        }
        composeRule.onNodeWithText("Privacy policy").performClick()
        composeRule.runOnIdle { assertEquals(true, opened) }
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
