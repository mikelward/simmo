package app.simmo.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.github.takahirom.roborazzi.captureRoboImage
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
                        analyticsOptIn = true,
                    ),
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Settings").assertExists()
        composeRule.onNodeWithText("SIMs").assertExists()
        composeRule.onNodeWithText("Show which SIM or app is used").assertExists()
        composeRule.onNodeWithText("Delay before calling").assertExists()
        composeRule.onNodeWithText("3 seconds").assertExists()
        composeRule.onNodeWithText("Use contacts' local numbers").assertExists()
        composeRule.onNodeWithText("Make Simmo better").assertExists()
        captureSnapshot("settings.png")
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
