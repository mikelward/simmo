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

/**
 * Renders the delay-before-calling countdown (SPEC "Call feedback and
 * delay"): the rule-picked SIM, number + detected country, the seconds left,
 * Call now, and cancel.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DelayedCallScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun delayedCall_countdown() {
        composeRule.setContent {
            MaterialTheme {
                DelayedCallContent(
                    state = DelayedCallUiState(
                        simLabel = "Telstra AU",
                        dialedNumber = "+61 412 345 678",
                        countryLabel = "+61 Australia",
                        remainingSeconds = 3,
                    ),
                    onCallNow = {},
                    onCancel = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Calling using Telstra AU").assertExists()
        composeRule.onNodeWithText("+61 412 345 678").assertExists()
        composeRule.onNodeWithText("+61 Australia").assertExists()
        composeRule.onNodeWithText("Calling in 3 seconds").assertExists()
        composeRule.onNodeWithText("Call now").assertExists()
        composeRule.onNodeWithText("Cancel call").assertExists()
        captureSnapshot("delayed_call.png")
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
