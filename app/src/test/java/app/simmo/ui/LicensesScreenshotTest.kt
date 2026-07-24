package app.simmo.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.simmo.R
import com.github.takahirom.roborazzi.captureRoboImage
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.util.withJson
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the open-source licenses screen. The library list is built
 * synchronously from the committed res/raw/aboutlibraries.json so the snapshot
 * is deterministic (production loads the same JSON asynchronously).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LicensesScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun licenses() {
        val libraries = Libs.Builder()
            .withJson(composeRule.activity, R.raw.aboutlibraries)
            .build()
        composeRule.setContent {
            MaterialTheme {
                LicensesContent(libraries)
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Open source licenses").assertExists()
        captureSnapshot("licenses.png")
    }

    /**
     * Tapping a component opens a dialog with its version and a tappable license
     * link (the bundled export carries no license text, so the link opens the
     * full text in the browser), and Done dismisses it. Interaction-only, like
     * CountryPicker's "New group" test — a Compose dialog renders in its own
     * window, which the decorView snapshot helper can't capture.
     */
    @Test
    fun licenseDialog_showsVersionAndOpensLicenseUrl() {
        val libraries = Libs.Builder()
            .withJson(composeRule.activity, R.raw.aboutlibraries)
            .build()
        var openedUrl: String? = null
        composeRule.setContent {
            MaterialTheme {
                LicensesContent(libraries, onOpenLicenseUrl = { openedUrl = it })
            }
        }
        composeRule.waitForIdle()

        // Tap the "Activity" row → dialog shows its version and license.
        composeRule.onNodeWithText("Activity").performClick()
        composeRule.onNodeWithText("Version 1.13.0").assertIsDisplayed()
        composeRule.onNodeWithText("Apache License 2.0").assertIsDisplayed()

        // The license name links out to the full text.
        composeRule.onNodeWithText("Apache License 2.0").performClick()
        composeRule.runOnIdle {
            assertEquals("https://spdx.org/licenses/Apache-2.0.html", openedUrl)
        }

        // Done dismisses the dialog.
        composeRule.onNodeWithText("Done").performClick()
        composeRule.onNodeWithText("Version 1.13.0").assertDoesNotExist()
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
