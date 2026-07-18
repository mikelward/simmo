package app.simmo.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.simmo.domain.DialHandoffApp
import app.simmo.domain.PhoneAccountRef
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
                        "r",
                        SimmoRule(RuleMatcher.Country("AU"), RuleAction.UseSim(telstra)),
                    ),
                    simOptions = listOf(
                        SimOptionUi(telstra, "Telstra AU", active = true),
                        SimOptionUi(SimRef(2, "T-Mobile", "T-Mobile US"), "T-Mobile US", active = true),
                        SimOptionUi(SimRef(7, "Vodafone", "Voda AU"), "Voda AU", active = false),
                    ),
                    countryOptions = listOf(
                        CountryOptionUi("AU", "+61 Australia"),
                        CountryOptionUi("US", "+1 United States"),
                    ),
                    onSave = { _, _ -> },
                    onDelete = {},
                    onCancel = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Edit rule").assertExists()
        // Chosen countries are removable entries above the add affordance;
        // the full list lives behind the picker (CountryPickerScreenshotTest).
        composeRule.onNodeWithText("+61 Australia").assertExists()
        composeRule.onNodeWithText("Add a country or code").assertExists()
        // SIMs are listed directly as actions; no "A specific SIM" wrapper row.
        composeRule.onNodeWithText("Telstra AU").assertExists()
        composeRule.onNodeWithText("A specific SIM").assertDoesNotExist()
        captureSnapshot("rule_editor.png")
    }

    @Test
    fun editor_editingMultiCountryRule() {
        val telstra = SimRef(1, "Telstra", "Telstra AU")
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
                    simOptions = listOf(
                        SimOptionUi(telstra, "Telstra AU", active = true),
                        SimOptionUi(SimRef(2, "T-Mobile", "T-Mobile US"), "T-Mobile US", active = true),
                    ),
                    countryOptions = listOf(
                        CountryOptionUi("FR", "+33 France"),
                        CountryOptionUi("DE", "+49 Germany"),
                        CountryOptionUi("IT", "+39 Italy"),
                    ),
                    onSave = { _, _ -> },
                    onDelete = {},
                    onCancel = {},
                )
            }
        }
        composeRule.waitForIdle()

        // Every country on the rule shows as its own removable entry, with
        // the add affordance below them.
        composeRule.onNodeWithText("+33 France").assertExists()
        composeRule.onNodeWithText("+49 Germany").assertExists()
        composeRule.onNodeWithText("+39 Italy").assertExists()
        composeRule.onNodeWithText("Add a country or code").assertExists()
        captureSnapshot("rule_editor_multi_country.png")
    }

    @Test
    fun editor_editingGroupRule() {
        val telstra = SimRef(1, "Telstra", "Telstra AU")
        composeRule.setContent {
            MaterialTheme {
                RuleEditorContent(
                    target = EditorTarget.Existing(
                        "r",
                        // The "my plan also covers the UK" shape: one EU/EEA
                        // group entry plus a hand-picked country beside it.
                        SimmoRule(
                            RuleMatcher.Countries(listOf("GB"), listOf("eu_eea")),
                            RuleAction.UseSim(telstra),
                        ),
                    ),
                    simOptions = listOf(SimOptionUi(telstra, "Telstra AU", active = true)),
                    countryOptions = listOf(CountryOptionUi("GB", "+44 United Kingdom")),
                    groupOptions = listOf(
                        CountryGroupOptionUi(
                            id = "eu_eea",
                            label = "EU/EEA",
                            description = "European Union and EEA countries",
                            memberRegions = emptySet(),
                            searchTerms = emptyList(),
                        ),
                    ),
                    onSave = { _, _ -> },
                    onDelete = {},
                    onCancel = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("EU/EEA").assertExists()
        composeRule.onNodeWithText("+44 United Kingdom").assertExists()
        composeRule.onNodeWithText("Add a country or code").assertExists()
        captureSnapshot("rule_editor_group.png")
    }

    @Test
    fun editor_offersDialHandoffApps() {
        val telstra = SimRef(1, "Telstra", "Telstra AU")
        composeRule.setContent {
            MaterialTheme {
                RuleEditorContent(
                    target = EditorTarget.Existing(
                        "r",
                        SimmoRule(RuleMatcher.Country("US"), RuleAction.UseMatchingCountrySim),
                    ),
                    simOptions = listOf(SimOptionUi(telstra, "Telstra AU", active = true)),
                    countryOptions = listOf(CountryOptionUi("US", "+1 United States")),
                    // Both dial-intent targets installed → both offered as actions.
                    dialHandoffApps = DialHandoffApp.entries.toSet(),
                    onSave = { _, _ -> },
                    onDelete = {},
                    onCancel = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Google Voice").assertExists()
        composeRule.onNodeWithText("Microsoft Teams").assertExists()
        composeRule.onNodeWithText("Viber").assertExists()
        composeRule.onNodeWithText("Yolla").assertExists()
        captureSnapshot("rule_editor_dial_handoff.png")
    }

    @Test
    fun editor_offersCallingAccounts() {
        val telstra = SimRef(1, "Telstra", "Telstra AU")
        val sip = PhoneAccountRef("com.sip.app/.SipService/work")
        composeRule.setContent {
            MaterialTheme {
                RuleEditorContent(
                    target = EditorTarget.Existing(
                        "r",
                        // A SIP-account rule whose account is still registered:
                        // its row shows selected among the SIM actions.
                        SimmoRule(
                            RuleMatcher.Country("US"),
                            RuleAction.HandOff.ViaPhoneAccount(sip, "SIP work"),
                        ),
                    ),
                    simOptions = listOf(SimOptionUi(telstra, "Telstra AU", active = true)),
                    countryOptions = listOf(CountryOptionUi("US", "+1 United States")),
                    callingAccounts = listOf(
                        CallingAccountOptionUi(sip, "SIP work"),
                        CallingAccountOptionUi(PhoneAccountRef("com.sip.app/.SipService/home"), "SIP home"),
                    ),
                    onSave = { _, _ -> },
                    onDelete = {},
                    onCancel = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("SIP work").assertExists()
        composeRule.onNodeWithText("SIP home").assertExists()
        captureSnapshot("rule_editor_calling_accounts.png")
    }

    @Test
    fun editor_callingAccountUnavailable() {
        val telstra = SimRef(1, "Telstra", "Telstra AU")
        composeRule.setContent {
            MaterialTheme {
                RuleEditorContent(
                    target = EditorTarget.Existing(
                        "r",
                        // The stored account is no longer registered: it still
                        // shows (marked unavailable) so the rule can be kept.
                        SimmoRule(
                            RuleMatcher.Country("US"),
                            RuleAction.HandOff.ViaPhoneAccount(
                                PhoneAccountRef("com.sip.app/.SipService/work"),
                                "SIP work",
                            ),
                        ),
                    ),
                    simOptions = listOf(SimOptionUi(telstra, "Telstra AU", active = true)),
                    countryOptions = listOf(CountryOptionUi("US", "+1 United States")),
                    callingAccounts = emptyList(),
                    onSave = { _, _ -> },
                    onDelete = {},
                    onCancel = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("SIP work (unavailable)").assertExists()
        captureSnapshot("rule_editor_calling_account_unavailable.png")
    }

    @Test
    fun editor_handOffNotificationsHint() {
        val telstra = SimRef(1, "Telstra", "Telstra AU")
        composeRule.setContent {
            MaterialTheme {
                RuleEditorContent(
                    target = EditorTarget.Existing(
                        "r",
                        // A Google Voice hand-off rule edited while notifications
                        // are off: the enable hint sits under the selected action.
                        SimmoRule(
                            RuleMatcher.Country("US"),
                            RuleAction.HandOff.ViaDialIntent(DialHandoffApp.GOOGLE_VOICE),
                        ),
                    ),
                    simOptions = listOf(SimOptionUi(telstra, "Telstra AU", active = true)),
                    countryOptions = listOf(CountryOptionUi("US", "+1 United States")),
                    dialHandoffApps = DialHandoffApp.entries.toSet(),
                    notificationsEnabled = false,
                    onSave = { _, _ -> },
                    onDelete = {},
                    onCancel = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule
            .onNodeWithText("Let Simmo tell you if there's a Google Voice problem")
            .assertExists()
        composeRule.onNodeWithText("Allow").assertExists()
        captureSnapshot("rule_editor_notifications_hint.png")
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
