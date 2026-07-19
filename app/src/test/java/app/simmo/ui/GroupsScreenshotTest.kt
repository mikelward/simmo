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
    fun applyReplacesDoneWhileAGroupDeletionIsPending() {
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

        composeRule.onNodeWithText("Done").assertDoesNotExist()
        composeRule.onNodeWithText("Apply").performClick()
        composeRule.runOnIdle { assertEquals(true, applied) }
    }

    @Test
    fun doneShowsWithNoPendingGroup() {
        var done = false
        composeRule.setContent {
            MaterialTheme {
                GroupsContent(
                    groups = listOf(CustomGroup("custom:1", "Vodafone Zone 1", listOf("GB"))),
                    countryOptions = emptyList(),
                    onDone = { done = true },
                )
            }
        }

        composeRule.onNodeWithText("Apply").assertDoesNotExist()
        composeRule.onNodeWithText("Done").performClick()
        composeRule.runOnIdle { assertEquals(true, done) }
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
