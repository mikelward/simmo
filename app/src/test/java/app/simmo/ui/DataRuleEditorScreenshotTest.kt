package app.simmo.ui

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
import app.simmo.domain.CustomGroup
import app.simmo.domain.DataExpectation
import app.simmo.domain.DataRule
import app.simmo.domain.DataSimScope
import app.simmo.domain.RuleMatcher
import app.simmo.domain.SimRef
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The data rule editor (SPEC "Data rules"): a "When in" matcher reusing the
 * shared picker, and an "Expect" radio group — per-SIM use-for-data rows,
 * the roaming-OK scopes, and the always-warn guard.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DataRuleEditorScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val telstra = SimOptionUi(SimRef(1, "Telstra", "Telstra AU"), "Telstra AU", active = true)
    private val orange = SimOptionUi(SimRef(2, "Orange", "Orange Holiday"), "Orange Holiday", active = false)

    @Test
    fun newDataRule_offersExpectationsPerSim() {
        composeRule.setContent {
            MaterialTheme {
                DataRuleEditorContent(
                    target = DataEditorTarget.New,
                    simOptions = listOf(telstra, orange),
                    countryOptions = emptyList(),
                    onSave = { _, _ -> },
                    onDelete = null,
                    onCancel = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("New data rule").assertExists()
        composeRule.onNodeWithText("Use Telstra AU for data").assertExists()
        composeRule.onNodeWithText("Use Orange Holiday (disabled) for data").assertExists()
        composeRule.onNodeWithText("Roaming OK on any SIM").assertExists()
        composeRule.onNodeWithText("Roaming OK on SIMs homed in these countries").assertExists()
        composeRule.onNodeWithText("Always warn").assertExists()
        captureSnapshot("data_rule_editor.png")
    }

    @Test
    fun savingBuildsTheSingleSimRoamingRule() {
        var saved: DataRule? = null
        var savedGroups: List<CustomGroup>? = null
        composeRule.setContent {
            MaterialTheme {
                DataRuleEditorContent(
                    target = DataEditorTarget.New,
                    simOptions = listOf(telstra),
                    countryOptions = emptyList(),
                    onSave = { groups, rule -> savedGroups = groups; saved = rule },
                    onDelete = null,
                    onCancel = {},
                )
            }
        }

        composeRule.onNodeWithText("Anywhere").performClick()
        composeRule.onNodeWithText("Roaming OK on Telstra AU").performClick()
        composeRule.onNodeWithText("Save").performClick()
        composeRule.runOnIdle {
            assertEquals(
                DataRule(
                    RuleMatcher.AnyDestination,
                    DataExpectation.RoamingOk(DataSimScope.Sims(listOf(telstra.ref))),
                ),
                saved,
            )
            assertEquals(emptyList<CustomGroup>(), savedGroups)
        }
    }

    @Test
    fun orphanedSimTargetRequiresRelinkingBeforeSave() {
        // The rule's SIM left the registry: nothing is visibly selected, so
        // Save must stay disabled until the user re-links to an offered SIM
        // — silently re-saving the orphaned target would look like success.
        val existing = DataRule(
            RuleMatcher.Country("AU"),
            DataExpectation.UseSimForData(SimRef(9, "Optus", "Optus travel")),
        )
        composeRule.setContent {
            MaterialTheme {
                DataRuleEditorContent(
                    target = DataEditorTarget.Existing(0, existing),
                    simOptions = listOf(telstra),
                    countryOptions = emptyList(),
                    onSave = { _, _ -> },
                    onDelete = {},
                    onCancel = {},
                )
            }
        }

        composeRule.onNodeWithText("Save").assertIsNotEnabled()
        composeRule.onNodeWithText("Use Telstra AU for data").performClick()
        composeRule.onNodeWithText("Save").assertIsEnabled()
    }

    @Test
    fun editingKeepsTheDisabledFlag() {
        // Editing a rule the user turned off must not silently re-enable it.
        var saved: DataRule? = null
        val existing = DataRule(
            RuleMatcher.Country("AU"),
            DataExpectation.AlwaysWarn,
            enabled = false,
        )
        composeRule.setContent {
            MaterialTheme {
                DataRuleEditorContent(
                    target = DataEditorTarget.Existing(0, existing),
                    simOptions = listOf(telstra),
                    countryOptions = emptyList(),
                    onSave = { _, rule -> saved = rule },
                    onDelete = {},
                    onCancel = {},
                )
            }
        }

        composeRule.onNodeWithText("Save").performClick()
        composeRule.runOnIdle {
            assertEquals(existing, saved)
        }
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
