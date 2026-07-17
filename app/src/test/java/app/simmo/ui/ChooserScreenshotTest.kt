package app.simmo.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.simmo.domain.PhoneAccountRef
import app.simmo.domain.SimRef
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the Ask chooser (SPEC "The chooser (Ask flow)"): number + detected
 * country, one button per active SIM, the skipped disabled-SIM note, the
 * remember-for-country rule, and cancel.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChooserScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun chooser_countryCallWithSkippedDisabledSim() {
        composeRule.setContent {
            MaterialTheme {
                ChooserContent(
                    state = ChooserUiState(
                        dialedNumber = "+61 412 345 678",
                        countryLabel = "+61 Australia",
                        rememberRegion = "AU",
                        rememberCountryName = "Australia",
                        targets = listOf(
                            ChooserTargetUi(SimRef(1, "Telstra", "Telstra AU"), PhoneAccountRef("a1"), "Telstra AU"),
                            ChooserTargetUi(SimRef(2, "T-Mobile", "T-Mobile US"), PhoneAccountRef("a2"), "T-Mobile US"),
                        ),
                        skippedSimNames = listOf("Voda AU"),
                    ),
                    onPlace = { _, _ -> },
                    onOpenSimSettings = {},
                    onCancel = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("+61 412 345 678").assertExists()
        composeRule.onNodeWithText("+61 Australia").assertExists()
        composeRule.onNodeWithText("Call with Telstra AU").assertExists()
        composeRule.onNodeWithText("Your rule wanted Voda AU, but that SIM is disabled.").assertExists()
        composeRule.onNodeWithText("SIM settings").assertExists()
        composeRule.onNodeWithText("Remember for Australia").assertExists()
        composeRule.onNodeWithText("Cancel call").assertExists()
        captureSnapshot("chooser.png")
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
