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
 * Renders the rules list with the states that matter (SPEC "Calling rules"): an
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
    fun dataRulesTab_showsExpectationsWithPlainCountryNames() {
        composeRule.setContent {
            MaterialTheme {
                RulesScreenContent(
                    rows = emptyList(),
                    dataRows = listOf(
                        // The preseeded default leads (SPEC "Data rules").
                        DataRuleRowUi("EU/EEA", DataExpectationUi.RoamingOkHomedInMatched),
                        DataRuleRowUi(
                            "Australia",
                            DataExpectationUi.UseSimForData("Telstra"),
                            pause = RulePause.SIM_DISABLED,
                        ),
                        DataRuleRowUi("United States", DataExpectationUi.RoamingOkSims("T-Mobile")),
                        DataRuleRowUi("France", DataExpectationUi.RoamingOkAnySim, enabled = false),
                        DataRuleRowUi(null, DataExpectationUi.AlwaysWarn),
                    ),
                    tab = RulesTab.DATA,
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Data rules").assertExists()
        composeRule.onNodeWithText("Roaming OK on SIMs homed in these countries").assertExists()
        composeRule.onNodeWithText("Use Telstra for data").assertExists()
        composeRule.onNodeWithText("SIM disabled — rule paused").assertExists()
        composeRule.onNodeWithText("Anywhere").assertExists()
        captureSnapshot("data_rules_list.png")
    }

    @Test
    fun rulesList_showsATombstonedRuleStruckThroughWithUndo() {
        composeRule.setContent {
            MaterialTheme {
                RulesScreenContent(
                    rows = listOf(
                        RuleRowUi("+61 Australia", ActionUi.UseSim("Telstra"), id = "r0"),
                        // Soft-deleted: dimmed, struck-through, inert, Undo in
                        // place of the row menu.
                        RuleRowUi("+1 United States", ActionUi.UseSim("T-Mobile"), id = "r1", pendingRemoval = true),
                        RuleRowUi(null, ActionUi.SystemDefault),
                    ),
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("+1 United States").assertExists()
        composeRule.onNodeWithText("Undo").assertExists()
        captureSnapshot("rules_list_pending_removal.png")
    }

    @Test
    fun dataRulesTab_showsATombstonedRuleStruckThroughWithUndo() {
        composeRule.setContent {
            MaterialTheme {
                RulesScreenContent(
                    rows = emptyList(),
                    dataRows = listOf(
                        DataRuleRowUi("EU/EEA", DataExpectationUi.RoamingOkHomedInMatched),
                        DataRuleRowUi(
                            "United States",
                            DataExpectationUi.RoamingOkAnySim,
                            id = "d1",
                            pendingRemoval = true,
                        ),
                        DataRuleRowUi(null, DataExpectationUi.AlwaysWarn),
                    ),
                    tab = RulesTab.DATA,
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("United States").assertExists()
        composeRule.onNodeWithText("Undo").assertExists()
        captureSnapshot("data_rules_list_pending_removal.png")
    }

    @Test
    fun dataTab_leadsWithTheTriageCard() {
        composeRule.setContent {
            MaterialTheme {
                RulesScreenContent(
                    rows = emptyList(),
                    dataRows = listOf(
                        DataRuleRowUi("EU/EEA", DataExpectationUi.RoamingOkHomedInMatched, id = "d0"),
                    ),
                    triage = DataTriageUi(
                        kind = DataTriageKind.ROAMING,
                        // A foreign SIM actually roaming in the US (a US SIM wouldn't).
                        dataSimName = "Vodafone",
                        countryLabel = "United States",
                        otherSimName = null,
                        country = "US",
                        dataSimRef = SimRef(2, "Vodafone", "Vodafone"),
                        // The real shipped groups that contain the US.
                        widenGroups = listOf(
                            TriageGroupUi("usa_territories", "USA + territories"),
                            TriageGroupUi("north_america", "North America"),
                        ),
                    ),
                    tab = RulesTab.DATA,
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Data roaming in United States").assertExists()
        composeRule.onNodeWithText("Currently using Vodafone for data").assertExists()
        composeRule.onNodeWithText("Use in United States").assertExists()
        composeRule.onNodeWithText("Use in USA + territories").assertExists()
        composeRule.onNodeWithText("Use in North America").assertExists()
        composeRule.onNodeWithText("Change SIM").assertExists()
        captureSnapshot("data_triage.png")
    }

    @Test
    fun triageUseCountryAndGroup_passTheRenderedCardIdentity() {
        val ref = SimRef(2, "T-Mobile", "T-Mobile US")
        var useCountry: Pair<String, SimRef>? = null
        var usedGroup: Triple<String, SimRef, String>? = null
        composeRule.setContent {
            MaterialTheme {
                RulesScreenContent(
                    rows = emptyList(),
                    triage = DataTriageUi(
                        kind = DataTriageKind.ROAMING,
                        dataSimName = "T-Mobile US",
                        countryLabel = "France",
                        otherSimName = null,
                        country = "FR",
                        dataSimRef = ref,
                        widenGroups = listOf(TriageGroupUi("eu_eea", "EU/EEA")),
                    ),
                    tab = RulesTab.DATA,
                    onTriageThisIsOk = { country, sim -> useCountry = country to sim },
                    onTriageWiden = { country, sim, groupId -> usedGroup = Triple(country, sim, groupId) },
                )
            }
        }

        // The tap carries the identity the card rendered, so the write can act
        // on exactly the shown situation (Codex on PR #62).
        composeRule.onNodeWithText("Use in France").performClick()
        composeRule.runOnIdle { assertEquals("FR" to ref, useCountry) }
        composeRule.onNodeWithText("Use in EU/EEA").performClick()
        composeRule.runOnIdle { assertEquals(Triple("FR", ref, "eu_eea"), usedGroup) }
    }

    @Test
    fun triageNoData_offersOnlySystemSettings() {
        composeRule.setContent {
            MaterialTheme {
                RulesScreenContent(
                    rows = emptyList(),
                    triage = DataTriageUi(
                        kind = DataTriageKind.NO_DATA,
                        dataSimName = "T-Mobile US",
                        countryLabel = "Australia",
                        otherSimName = "Telstra AU",
                        country = "AU",
                        dataSimRef = SimRef(2, "T-Mobile", "T-Mobile US"),
                    ),
                    tab = RulesTab.DATA,
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("No data in Australia").assertExists()
        composeRule.onNodeWithText("T-Mobile US has no data here; Telstra AU is preferred").assertExists()
        // No rule to make here - only the change-SIM resolution.
        composeRule.onNodeWithText("Use in Australia").assertDoesNotExist()
        composeRule.onNodeWithText("Change SIM").assertExists()
    }

    @Test
    fun triageRoaming_namesTheLocalSimToPrefer() {
        composeRule.setContent {
            MaterialTheme {
                RulesScreenContent(
                    rows = emptyList(),
                    triage = DataTriageUi(
                        kind = DataTriageKind.ROAMING,
                        dataSimName = "T-Mobile US",
                        countryLabel = "Australia",
                        otherSimName = "Telstra AU",
                        country = "AU",
                        dataSimRef = SimRef(2, "T-Mobile", "T-Mobile US"),
                    ),
                    tab = RulesTab.DATA,
                )
            }
        }
        composeRule.waitForIdle()

        // SPEC: the roaming card names which active SIM is local (Codex on PR #62).
        composeRule.onNodeWithText("Currently using T-Mobile US for data; Telstra AU is preferred")
            .assertExists()
    }

    @Test
    fun switchingTabs_invokesTheCallback() {
        var selected: RulesTab? = null
        composeRule.setContent {
            MaterialTheme {
                RulesScreenContent(
                    rows = emptyList(),
                    onSelectTab = { selected = it },
                )
            }
        }

        composeRule.onNodeWithText("Data").performClick()
        composeRule.runOnIdle { assertEquals(RulesTab.DATA, selected) }
    }

    @Test
    fun overflowMenu_duplicateAndDisableInvokeCallbacks() {
        var duplicated: String? = null
        var toggled: Pair<String, Boolean>? = null
        composeRule.setContent {
            MaterialTheme {
                RulesScreenContent(
                    rows = listOf(RuleRowUi("+61 Australia", ActionUi.UseSim("Telstra"), id = "r0")),
                    onDuplicateRule = { duplicated = it },
                    onSetRuleEnabled = { id, enabled -> toggled = id to enabled },
                )
            }
        }

        // An enabled rule's menu offers Disable; picking it toggles off — by id.
        composeRule.onNodeWithContentDescription("More options").performClick()
        composeRule.onNodeWithText("Disable").performClick()
        composeRule.runOnIdle { assertEquals("r0" to false, toggled) }

        composeRule.onNodeWithContentDescription("More options").performClick()
        composeRule.onNodeWithText("Duplicate").performClick()
        composeRule.runOnIdle { assertEquals("r0", duplicated) }
    }

    @Test
    fun deletingARuleTakesEffectAtOnceWithNoConfirmDialog() {
        var deleted: String? = null
        composeRule.setContent {
            MaterialTheme {
                RulesScreenContent(
                    rows = listOf(RuleRowUi("+61 Australia", ActionUi.UseSim("Telstra"), id = "r0")),
                    onDeleteRule = { deleted = it },
                )
            }
        }

        composeRule.onNodeWithContentDescription("More options").performClick()
        composeRule.onNodeWithText("Delete").performClick() // the menu item
        // No confirm dialog — the delete fires immediately (by id), and the
        // Undo bar (hosted by RulesScreen) is the safety net.
        composeRule.onNodeWithText("Delete this rule?").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals("r0", deleted) }
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
