package app.simmo.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.simmo.domain.CountryGroups
import app.simmo.domain.CustomGroup
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders the Country groups screen with a couple of user-defined groups. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GroupsScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun groups_listsCustomGroupsWithTheirMembers() {
        composeRule.setContent {
            MaterialTheme {
                GroupsContent(
                    groups = listOf(
                        CustomGroup("custom:1", "Vodafone Zone 1", listOf("GB", "FR", "DE")),
                        CustomGroup("custom:2", "Work trips", listOf("US", "JP")),
                    ),
                    countryOptions = emptyList(),
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Vodafone Zone 1").assertExists()
        // The member countries read as the group's subtitle.
        composeRule.onNodeWithText("United Kingdom, France, Germany").assertExists()
        composeRule.onNodeWithText("Work trips").assertExists()
        captureSnapshot("groups_list.png")
    }

    @Test
    fun groups_showATombstonedGroupStruckThroughWithUndo() {
        composeRule.setContent {
            MaterialTheme {
                GroupsContent(
                    groups = listOf(
                        CustomGroup("custom:1", "Vodafone Zone 1", listOf("GB", "FR", "DE")),
                        // Soft-deleted: dimmed, struck-through, inert, with Undo.
                        CustomGroup("custom:2", "Work trips", listOf("US", "JP"), pendingRemoval = true),
                    ),
                    countryOptions = emptyList(),
                    pendingRemovals = true,
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Work trips").assertExists()
        composeRule.onNodeWithText("Undo").assertExists()
        captureSnapshot("groups_list_pending_removal.png")
    }

    @Test
    fun applyReplacesBackWhileAGroupDeletionIsPending() {
        var applied = false
        composeRule.setContent {
            MaterialTheme {
                GroupsContent(
                    groups = listOf(
                        CustomGroup("custom:1", "Vodafone Zone 1", listOf("GB")),
                        CustomGroup("custom:2", "Work trips", listOf("US"), pendingRemoval = true),
                    ),
                    countryOptions = emptyList(),
                    pendingRemovals = true,
                    onApply = { applied = true },
                )
            }
        }

        composeRule.onNodeWithText("Back").assertDoesNotExist()
        composeRule.onNodeWithText("Apply").performClick()
        composeRule.runOnIdle { assertEquals(true, applied) }
    }

    @Test
    fun backShowsWithNoPendingGroup() {
        // The header button returns to the previous screen (Settings) — it's a
        // sub-screen, so it goes back rather than closing the app.
        var backed = false
        composeRule.setContent {
            MaterialTheme {
                GroupsContent(
                    groups = listOf(CustomGroup("custom:1", "Vodafone Zone 1", listOf("GB"))),
                    countryOptions = emptyList(),
                    onBack = { backed = true },
                )
            }
        }

        composeRule.onNodeWithText("Apply").assertDoesNotExist()
        composeRule.onNodeWithText("Back").performClick()
        composeRule.runOnIdle { assertEquals(true, backed) }
    }

    @Test
    fun theAddButtonOpensAnEmptyEditor() {
        composeRule.setContent {
            MaterialTheme { GroupsContent(groups = emptyList(), countryOptions = emptyList()) }
        }
        composeRule.onNodeWithContentDescription("New group").performClick()
        composeRule.onNodeWithText("New group").assertExists() // editor title
        composeRule.onNodeWithText("Add a country or code").assertExists()
    }

    @Test
    fun tappingAGroupOpensItsEditorPrefilled() {
        composeRule.setContent {
            MaterialTheme {
                GroupsContent(
                    groups = listOf(CustomGroup("custom:1", "Work trips", listOf("US"))),
                    countryOptions = listOf(CountryOptionUi("US", "+1 United States")),
                )
            }
        }
        composeRule.onNodeWithText("Work trips").performClick()
        composeRule.onNodeWithText("Edit group").assertExists()
        // The member country shows as a removable entry, labeled from options.
        composeRule.onNodeWithText("+1 United States").assertExists()
    }

    @Test
    fun deletingAGroupTakesEffectAtOnceWithNoConfirmDialog() {
        var deleted: String? = null
        composeRule.setContent {
            MaterialTheme {
                GroupsContent(
                    groups = listOf(CustomGroup("custom:1", "Work trips", listOf("US"))),
                    countryOptions = listOf(CountryOptionUi("US", "+1 United States")),
                    onDeleteGroup = { deleted = it },
                )
            }
        }
        composeRule.onNodeWithText("Work trips").performClick() // open its editor
        // The editor's Delete button soft-deletes at once — no confirm dialog.
        composeRule.onNodeWithText("Delete").performClick()
        composeRule.runOnIdle { assertEquals("custom:1", deleted) }
    }

    @Test
    fun restoreDefaultGroupsFromTheMenuConfirmsBeforeResetting() {
        var restored = false
        composeRule.setContent {
            MaterialTheme {
                GroupsContent(
                    groups = listOf(CustomGroup("custom:1", "Zone", listOf("AU"))),
                    countryOptions = emptyList(),
                    onRestoreDefaults = { restored = true },
                )
            }
        }
        composeRule.onNodeWithContentDescription("More options").performClick()
        composeRule.onNodeWithText("Restore default groups").performClick() // the menu item
        // The confirm dialog appears; nothing is reset until it's confirmed.
        composeRule.onNodeWithText("Restore default groups?").assertExists()
        composeRule.runOnIdle { assertEquals(false, restored) }
        composeRule.onNodeWithText("Restore default groups").performClick() // the confirm button
        composeRule.runOnIdle { assertEquals(true, restored) }
    }

    @Test
    fun cancelingTheRestoreDefaultsDialogResetsNothing() {
        var restored = false
        composeRule.setContent {
            MaterialTheme {
                GroupsContent(
                    groups = listOf(CustomGroup("custom:1", "Zone", listOf("AU"))),
                    countryOptions = emptyList(),
                    onRestoreDefaults = { restored = true },
                )
            }
        }
        composeRule.onNodeWithContentDescription("More options").performClick()
        composeRule.onNodeWithText("Restore default groups").performClick()
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.runOnIdle { assertEquals(false, restored) }
    }

    @Test
    fun groups_editorShowsResetToDefaultForAnEditedShippedGroup() {
        composeRule.setContent {
            MaterialTheme {
                GroupsContent(
                    // A shipped group edited away from its seed (renamed, trimmed).
                    groups = listOf(CustomGroup(CountryGroups.EU_EEA, "My Europe", listOf("FR", "DE"))),
                    countryOptions = listOf(
                        CountryOptionUi("FR", "+33 France"),
                        CountryOptionUi("DE", "+49 Germany"),
                    ),
                )
            }
        }
        composeRule.onNodeWithText("My Europe").performClick()
        composeRule.onNodeWithText("Reset to default").assertExists()
        captureSnapshot("groups_editor_reset_default.png")
    }

    @Test
    fun theEditorResetToDefaultRestoresThatOneGroup() {
        var restoredId: String? = null
        composeRule.setContent {
            MaterialTheme {
                GroupsContent(
                    groups = listOf(CustomGroup(CountryGroups.EU_EEA, "My Europe", listOf("FR"))),
                    countryOptions = listOf(CountryOptionUi("FR", "+33 France")),
                    onRestoreDefaultGroup = { restoredId = it },
                )
            }
        }
        composeRule.onNodeWithText("My Europe").performClick()
        composeRule.onNodeWithText("Reset to default").performClick()
        composeRule.runOnIdle { assertEquals(CountryGroups.EU_EEA, restoredId) }
    }

    @Test
    fun theEditorHidesResetToDefaultWhenThereIsNothingToRestore() {
        composeRule.setContent {
            MaterialTheme {
                GroupsContent(
                    groups = listOf(
                        // An untouched shipped seed and a user-created group.
                        CountryGroups.preseededGroup(CountryGroups.EU_EEA)!!,
                        CustomGroup("custom:1", "Zone", listOf("AU")),
                    ),
                    countryOptions = emptyList(),
                )
            }
        }
        // The seed matches its default, so there's nothing to reset.
        composeRule.onNodeWithText("EU/EEA").performClick()
        composeRule.onNodeWithText("Reset to default").assertDoesNotExist()
        composeRule.onNodeWithText("Cancel").performClick()
        // A user group is never "shippable", so it never offers a reset.
        composeRule.onNodeWithText("Zone").performClick()
        composeRule.onNodeWithText("Reset to default").assertDoesNotExist()
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
