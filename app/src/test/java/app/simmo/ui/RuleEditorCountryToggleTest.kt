package app.simmo.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.simmo.domain.CallingRule as SimmoRule
import app.simmo.domain.RuleAction
import app.simmo.domain.RuleMatcher
import app.simmo.domain.SimRef
import app.simmo.domain.regionCodes
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The selected-country checkboxes advertise a [androidx.compose.ui.semantics.Role.Checkbox]
 * toggle, so activating one must actually change state (never a silent no-op):
 * unchecking a checked entry removes it, and checking one from the dimmed
 * "Any country" state re-selects the country set.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RuleEditorCountryToggleTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val telstra = SimRef(1, "Telstra", "Telstra AU")

    private fun multiCountryEditor(onSave: (RuleMatcher) -> Unit = {}) {
        composeRule.setContent {
            MaterialTheme {
                RuleEditorContent(
                    target = EditorTarget.Existing(
                        "r",
                        SimmoRule(
                            RuleMatcher.Countries(listOf("FR", "DE", "IT")),
                            RuleAction.UseSim(telstra),
                        ),
                    ),
                    simOptions = listOf(SimOptionUi(telstra, "Telstra AU", active = true)),
                    countryOptions = listOf(
                        CountryOptionUi("FR", "+33 France"),
                        CountryOptionUi("DE", "+49 Germany"),
                        CountryOptionUi("IT", "+39 Italy"),
                    ),
                    onSave = { _, draft -> onSave(draft.matcher) },
                    onDelete = {},
                    onCancel = {},
                )
            }
        }
    }

    @Test
    fun uncheckingACheckedCountryRemovesItFromTheSet() {
        var savedMatcher: RuleMatcher? = null
        multiCountryEditor(onSave = { savedMatcher = it })

        // Each entry starts checked; unchecking France drops it from the rule.
        composeRule.onNodeWithText("+33 France").assertIsOn()
        composeRule.onNodeWithText("+33 France").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("+33 France").assertDoesNotExist()
        composeRule.onNodeWithText("+49 Germany").assertIsOn()
        composeRule.onNodeWithText("+39 Italy").assertIsOn()

        composeRule.onNodeWithText("Save").performClick()
        composeRule.runOnIdle {
            assertEquals(listOf("DE", "IT"), savedMatcher?.regionCodes())
        }
    }

    @Test
    fun checkingACountryUnderAnyReselectsTheSet() {
        multiCountryEditor()

        // Choose "Any country": the set stays listed but dimmed and unchecked.
        composeRule.onNodeWithText("Any country").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("+33 France").assertIsOff()

        // Checking any entry switches back to the country set — all re-check.
        composeRule.onNodeWithText("+33 France").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("+33 France").assertIsOn()
        composeRule.onNodeWithText("+49 Germany").assertIsOn()
    }
}
