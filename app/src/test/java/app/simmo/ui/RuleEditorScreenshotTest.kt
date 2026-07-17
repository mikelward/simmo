package app.simmo.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.simmo.domain.Rule as SimmoRule
import app.simmo.domain.RuleAction
import app.simmo.domain.RuleMatcher
import app.simmo.domain.SimRef
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders the rule editor for an existing "AU → Telstra" rule. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RuleEditorScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun editor_editingCountrySimRule() {
        val telstra = SimRef(1, "Telstra", "Telstra AU")
        composeRule.setContent {
            MaterialTheme {
                RuleEditorContent(
                    target = EditorTarget.Existing(
                        0,
                        SimmoRule(RuleMatcher.Country("AU"), RuleAction.UseSim(telstra)),
                    ),
                    simOptions = listOf(
                        SimOptionUi(telstra, "Telstra AU", active = true),
                        SimOptionUi(SimRef(2, "T-Mobile", "T-Mobile US"), "T-Mobile US", active = true),
                        SimOptionUi(SimRef(7, "Vodafone", "Voda AU"), "Voda AU", active = false),
                    ),
                    countryOptions = listOf(
                        CountryOptionUi("AU", "+61 Australia"),
                        CountryOptionUi("CA", "+1 Canada"),
                        CountryOptionUi("FR", "+33 France"),
                        CountryOptionUi("DE", "+49 Germany"),
                        CountryOptionUi("JP", "+81 Japan"),
                        CountryOptionUi("NZ", "+64 New Zealand"),
                        CountryOptionUi("GB", "+44 United Kingdom"),
                        CountryOptionUi("US", "+1 United States"),
                    ),
                    onSave = {},
                    onDelete = {},
                    onCancel = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Edit rule").assertExists()
        composeRule.onNodeWithText("+61 Australia").assertExists()
        composeRule.onNodeWithText("+81 Japan").assertExists()
        composeRule.onNodeWithText("A specific SIM").assertExists()
        captureSnapshot("rule_editor.png")
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
