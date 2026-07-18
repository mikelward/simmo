package app.simmo.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
 * Renders the rules list with the states that matter (SPEC "Rules"): an
 * enabled country rule, a greyed rule whose SIM is disabled, the two
 * preseeded defaults at the bottom, and the new-SIM prompt card on top.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RulesScreenScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun rulesList_showsCountryRulesDisabledRuleAndDefaults() {
        composeRule.setContent {
            MaterialTheme {
                RulesScreenContent(
                    rows = listOf(
                        RuleRowUi("+61 Australia", ActionUi.UseSim("Telstra")),
                        RuleRowUi("+1 United States", ActionUi.UseSim("T-Mobile")),
                        RuleRowUi("+44 United Kingdom", ActionUi.UseSim("Vodafone UK"), pause = RulePause.SIM_DISABLED),
                        RuleRowUi("+81 Japan", ActionUi.UseSim("Docomo"), pause = RulePause.SIM_AMBIGUOUS),
                        // User-disabled: greyed with its own label, separate from a SIM pause.
                        RuleRowUi("+64 New Zealand", ActionUi.Ask, enabled = false),
                        RuleRowUi("+33 France, +49 Germany", ActionUi.UseSim("T-Mobile")),
                        RuleRowUi(null, ActionUi.MatchingCountrySim),
                        RuleRowUi(null, ActionUi.SystemDefault),
                    ),
                    newSimPrompts = listOf(
                        NewSimPromptUi(SimRef(3, "Optus", "Optus travel"), "Optus travel", homeRegion = "AU"),
                    ),
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("+61 Australia").assertExists()
        composeRule.onNodeWithText("SIM disabled — rule paused").assertExists()
        composeRule.onNodeWithText("Disabled").assertExists()
        composeRule.onNodeWithText("New SIM: Optus travel").assertExists()
        captureSnapshot("rules_list.png")
    }

    @Test
    fun overflowMenu_duplicateAndDisableInvokeCallbacks() {
        var duplicated = -1
        var toggled: Pair<Int, Boolean>? = null
        composeRule.setContent {
            MaterialTheme {
                RulesScreenContent(
                    rows = listOf(RuleRowUi("+61 Australia", ActionUi.UseSim("Telstra"))),
                    onDuplicateRule = { duplicated = it },
                    onSetRuleEnabled = { i, enabled -> toggled = i to enabled },
                )
            }
        }

        // An enabled rule's menu offers Disable; picking it toggles off.
        composeRule.onNodeWithContentDescription("More options").performClick()
        composeRule.onNodeWithText("Disable").performClick()
        composeRule.runOnIdle { assertEquals(0 to false, toggled) }

        composeRule.onNodeWithContentDescription("More options").performClick()
        composeRule.onNodeWithText("Duplicate").performClick()
        composeRule.runOnIdle { assertEquals(0, duplicated) }
    }

    @Test
    fun deletingARuleAsksToConfirmFirst() {
        var deleted = -1
        composeRule.setContent {
            MaterialTheme {
                RulesScreenContent(
                    rows = listOf(RuleRowUi("+61 Australia", ActionUi.UseSim("Telstra"))),
                    onDeleteRule = { deleted = it },
                )
            }
        }

        composeRule.onNodeWithContentDescription("More options").performClick()
        composeRule.onNodeWithText("Delete").performClick() // the menu item
        // The confirm dialog appears and nothing is deleted yet.
        composeRule.onNodeWithText("Delete this rule?").assertExists()
        composeRule.runOnIdle { assertEquals(-1, deleted) }
        // Confirming deletes.
        composeRule.onNodeWithText("Delete").performClick()
        composeRule.runOnIdle { assertEquals(0, deleted) }
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
