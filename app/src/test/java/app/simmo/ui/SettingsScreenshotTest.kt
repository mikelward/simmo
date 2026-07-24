package app.simmo.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
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

/** Renders the Settings screen's app-level options. */
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
        // A visible Back button in the header, beside the system back gesture.
        composeRule.onNodeWithText("Back").assertExists()
        // Rules leads the list (the SIMs screen is the home, so Settings no
        // longer links to it); Country groups follows.
        composeRule.onNodeWithText("Rules").assertExists()
        composeRule.onNodeWithText("Calling and data rules").assertExists()
        composeRule.onNodeWithText("Country groups").assertExists()
        composeRule.onNodeWithText("Custom sets of countries a rule can match").assertExists()
        composeRule.onNodeWithText("Show which SIM or app is used").assertExists()
        composeRule.onNodeWithText("Delay before calling").assertExists()
        composeRule.onNodeWithText("3 seconds").assertExists()
        composeRule.onNodeWithText("Use contacts' local numbers").assertExists()
        composeRule.onNodeWithText("Hands-free guard").assertExists()
        // Each switch names the action, so a row read on its own — away from
        // the heading above it — still says what turning it on does.
        composeRule.onNodeWithText("Guard overseas calls").assertExists()
        composeRule.onNodeWithText("Guard calls needing a disabled SIM").assertExists()
        composeRule.onNodeWithText("Make Simmo better").assertExists()
        // Privacy policy, Licenses, Share debug logs, and version sit at the
        // foot of the page.
        composeRule.onNodeWithText("Privacy policy").assertExists()
        composeRule.onNodeWithText("Licenses").assertExists()
        composeRule.onNodeWithText("Share debug logs").assertExists()
        composeRule.onNodeWithText("Version 1.4.87+ab12cd").assertExists()
        // Tall enough to show the whole scrolling page, including the footer
        // (the new "Share debug logs" row pushed the version line down).
        captureSnapshot("settings.png", heightPx = 2500)
    }

    @Test
    fun tappingBackExitsSettings() {
        var backed = false
        composeRule.setContent {
            MaterialTheme {
                SettingsContent(onBack = { backed = true })
            }
        }
        composeRule.onNodeWithText("Back").performClick()
        composeRule.runOnIdle { assertEquals(true, backed) }
    }

    @Test
    fun tappingRulesOpensThem() {
        var opened = false
        composeRule.setContent {
            MaterialTheme {
                SettingsContent(onOpenRules = { opened = true })
            }
        }
        composeRule.onNodeWithText("Rules").performClick()
        composeRule.runOnIdle { assertEquals(true, opened) }
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

    @Test
    fun tappingLicensesOpensThem() {
        var opened = false
        composeRule.setContent {
            MaterialTheme {
                SettingsContent(onOpenLicenses = { opened = true })
            }
        }
        composeRule.onNodeWithText("Licenses").performClick()
        composeRule.runOnIdle { assertEquals(true, opened) }
    }

    @Test
    fun tappingShareDebugLogsInvokesIt() {
        var shared = false
        composeRule.setContent {
            MaterialTheme {
                SettingsContent(onShareDebugLog = { shared = true })
            }
        }
        composeRule.onNodeWithText("Share debug logs").performClick()
        composeRule.runOnIdle { assertEquals(true, shared) }
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
