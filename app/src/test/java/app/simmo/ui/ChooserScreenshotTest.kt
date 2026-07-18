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
                            // A non-SIM calling account (SIP provider) target.
                            ChooserTargetUi(sim = null, PhoneAccountRef("sip1"), "SIP work"),
                        ),
                        skippedSimNames = listOf("Voda AU"),
                    ),
                    onPlace = { _, _, _ -> },
                    onOpenSimSettings = {},
                    onCancel = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("+61 412 345 678").assertExists()
        composeRule.onNodeWithText("+61 Australia").assertExists()
        composeRule.onNodeWithText("Call with Telstra AU").assertExists()
        composeRule.onNodeWithText("Call with SIP work").assertExists()
        composeRule.onNodeWithText("Your rule wanted Voda AU, but that SIM is disabled.").assertExists()
        composeRule.onNodeWithText("SIM settings").assertExists()
        composeRule.onNodeWithText("Remember for Australia").assertExists()
        composeRule.onNodeWithText("Cancel call").assertExists()
        captureSnapshot("chooser.png")
    }

    @Test
    fun chooser_numberCorrection() {
        // The same-contact correction confirmation (SPEC "Hands-free and
        // Android Auto safeguards"): the contact's local number preselected,
        // "as dialed" one tap away.
        composeRule.setContent {
            MaterialTheme {
                ChooserContent(
                    state = ChooserUiState(
                        dialedNumber = "+44 20 7123 4567",
                        countryLabel = "+44 United Kingdom",
                        // Correction confirmations never offer the remember
                        // rule, matching buildChooserUiState.
                        rememberRegion = null,
                        rememberCountryName = null,
                        targets = listOf(
                            ChooserTargetUi(SimRef(1, "Telstra", "Telstra AU"), PhoneAccountRef("a1"), "Telstra AU"),
                        ),
                        skippedSimNames = emptyList(),
                        numberChoices = listOf(
                            NumberChoiceUi("+61412345678", contactName = "Mum"),
                            NumberChoiceUi("+44 20 7123 4567", contactName = null),
                        ),
                    ),
                    onPlace = { _, _, _ -> },
                    onOpenSimSettings = {},
                    onCancel = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("+61412345678").assertExists()
        composeRule.onNodeWithText("Mum's local number").assertExists()
        composeRule.onNodeWithText("As dialed").assertExists()
        composeRule.onNodeWithText("Call with Telstra AU").assertExists()
        captureSnapshot("chooser_local_number.png")
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
