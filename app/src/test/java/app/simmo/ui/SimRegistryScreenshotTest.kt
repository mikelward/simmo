package app.simmo.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.simmo.domain.SimRef
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the SIMs screen (SPEC "Disabled-SIM assist"): active SIMs first,
 * stale entries below with their last-seen dates and delete affordances.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SimRegistryScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun simRegistry_activeAndStaleSims() {
        composeRule.setContent {
            MaterialTheme {
                SimRegistryContent(
                    rows = listOf(
                        RegistrySimRowUi(
                            ref = SimRef(1, "Telstra", "Telstra personal"),
                            name = "Telstra personal",
                            carrier = "Telstra",
                            active = true,
                            lastSeenLabel = "Jul 17, 2026",
                        ),
                        RegistrySimRowUi(
                            ref = SimRef(2, "T-Mobile", ""),
                            name = "T-Mobile",
                            carrier = null,
                            active = false,
                            lastSeenLabel = "Jul 10, 2026",
                        ),
                        RegistrySimRowUi(
                            ref = SimRef(8, "Optus", "Optus travel"),
                            name = "Optus travel",
                            carrier = "Optus",
                            active = false,
                            lastSeenLabel = "Mar 2, 2026",
                        ),
                    ),
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Telstra personal").assertExists()
        composeRule.onNodeWithText("Active").assertExists()
        composeRule.onNodeWithText("Last seen Mar 2, 2026").assertExists()
        captureSnapshot("sim_registry.png")
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
