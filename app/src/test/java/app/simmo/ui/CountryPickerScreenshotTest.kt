package app.simmo.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders the country picker mid-search, filtered to a query. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CountryPickerScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun option(region: String, name: String, code: Int) =
        CountryOptionUi(region, "+$code $name", countrySearchTerms(region, name, code))

    @Test
    fun countryPicker_filteredBySearch() {
        composeRule.setContent {
            MaterialTheme {
                CountryPickerContent(
                    options = listOf(
                        option("AU", "Australia", 61),
                        option("FR", "France", 33),
                        option("AE", "United Arab Emirates", 971),
                        option("GB", "United Kingdom", 44),
                        option("US", "United States", 1),
                    ),
                    query = "united",
                    onQueryChange = {},
                    selectedRegions = setOf("GB"),
                    onSelect = {},
                    onBack = {},
                )
            }
        }
        composeRule.waitForIdle()

        // "united" keeps the three United* countries and drops the rest.
        composeRule.onNodeWithText("+44 United Kingdom").assertExists()
        composeRule.onNodeWithText("+971 United Arab Emirates").assertExists()
        composeRule.onNodeWithText("+61 Australia").assertDoesNotExist()
        captureSnapshot("country_picker.png")
    }

    @Test
    fun countryPicker_memberSearchSurfacesTheGroup() {
        val euEea = CountryGroupOptionUi(
            id = app.simmo.domain.CountryGroups.EU_EEA,
            label = "EU/EEA",
            description = "European Union and EEA countries",
            memberRegions = app.simmo.domain.CountryGroups
                .members(app.simmo.domain.CountryGroups.EU_EEA).toSet(),
            searchTerms = countryGroupSearchTerms(app.simmo.domain.CountryGroups.EU_EEA, "EU/EEA"),
        )
        composeRule.setContent {
            MaterialTheme {
                CountryPickerContent(
                    options = listOf(
                        option("AU", "Australia", 61),
                        option("FR", "France", 33),
                        option("GB", "United Kingdom", 44),
                    ),
                    query = "france",
                    onQueryChange = {},
                    selectedRegions = emptySet(),
                    onSelect = {},
                    groups = listOf(euEea),
                    selectedGroupIds = emptySet(),
                    onSelectGroup = {},
                    onBack = {},
                )
            }
        }
        composeRule.waitForIdle()

        // France is an EU/EEA member, so the group is suggested above it.
        composeRule.onNodeWithText("EU/EEA").assertExists()
        composeRule.onNodeWithText("+33 France").assertExists()
        composeRule.onNodeWithText("+61 Australia").assertDoesNotExist()
        captureSnapshot("country_picker_group.png")
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
