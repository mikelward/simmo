package app.simmo.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
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
 * Renders the SIMs screen — the app's home (SPEC "SIMs screen"): current
 * country, the Settings gear, Edit rules / Change SIMs actions, the new-SIM
 * prompt, then active SIMs first with their status chips and stale entries
 * below with last-seen dates and delete affordances.
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
                    currentCountry = "Australia",
                    rows = listOf(
                        // The local SIM: Android's primary for calls and data,
                        // and Simmo's preferred for both here — all four chips.
                        RegistrySimRowUi(
                            ref = SimRef(1, "Telstra", "Telstra personal"),
                            name = "Telstra personal",
                            carrier = "Telstra",
                            detail = "+61 412 345 678 · Australia",
                            active = true,
                            lastSeenLabel = "Jul 17, 2026",
                            callingPrimary = true,
                            callingPreferred = true,
                            dataPrimary = true,
                            dataPreferred = true,
                        ),
                        // An active data-only travel eSIM: registered for the
                        // roaming watch and shown as Active like any SIM, even
                        // though it has no call-capable account. Here it is
                        // carrying data via automatic data switching while
                        // Telstra stays the primary — the "Data · temporary" chip.
                        RegistrySimRowUi(
                            ref = SimRef(5, "Orange", "Orange Holiday"),
                            name = "Orange Holiday",
                            carrier = "Orange",
                            detail = "France",
                            active = true,
                            lastSeenLabel = "Jul 18, 2026",
                            dataTemporary = true,
                        ),
                        RegistrySimRowUi(
                            ref = SimRef(2, "T-Mobile", ""),
                            name = "T-Mobile",
                            carrier = null,
                            detail = "United States",
                            active = false,
                            lastSeenLabel = "Jul 10, 2026",
                        ),
                        RegistrySimRowUi(
                            ref = SimRef(8, "Optus", "Optus travel"),
                            name = "Optus travel",
                            carrier = "Optus",
                            detail = null,
                            active = false,
                            lastSeenLabel = "Mar 2, 2026",
                        ),
                    ),
                    // The new-SIM prompt shows on the home too, not only the
                    // rules list.
                    newSimPrompts = listOf(
                        NewSimPromptUi(
                            ref = SimRef(5, "Orange", "Orange Holiday"),
                            label = "Orange Holiday",
                            homeRegion = "FR",
                        ),
                    ),
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Telstra personal").assertExists()
        // Two Active rows: the call-capable SIM and the data-only eSIM.
        composeRule.onAllNodesWithText("Active").assertCountEquals(2)
        composeRule.onNodeWithText("Orange Holiday").assertExists()
        composeRule.onNodeWithText("+61 412 345 678 · Australia").assertExists()
        composeRule.onNodeWithText("Last seen Mar 2, 2026").assertExists()
        // The current-country header the status chips are relative to.
        composeRule.onNodeWithText("Current country: Australia").assertExists()
        // The primary/preferred status chips on the local SIM.
        composeRule.onNodeWithText("Calling · primary").assertExists()
        composeRule.onNodeWithText("Data · preferred").assertExists()
        // The auto-data-switch chip on the SIM currently carrying data.
        composeRule.onNodeWithText("Data · temporary").assertExists()
        // The home actions: the Settings gear, Edit rules, and the jump-out.
        composeRule.onNodeWithContentDescription("Settings").assertExists()
        composeRule.onNodeWithText("Edit rules").assertExists()
        composeRule.onNodeWithText("Change SIMs").assertExists()
        // The new-SIM prompt card, surfaced on the home.
        composeRule.onNodeWithText("New SIM: Orange Holiday").assertExists()
        captureSnapshot("sim_registry.png")
    }

    @Test
    fun simRegistry_crashBanner() {
        composeRule.setContent {
            MaterialTheme {
                SimRegistryContent(
                    currentCountry = "Australia",
                    showCrashBanner = true,
                    rows = listOf(
                        RegistrySimRowUi(
                            ref = SimRef(1, "Telstra", "Telstra personal"),
                            name = "Telstra personal",
                            carrier = "Telstra",
                            detail = "+61 412 345 678 · Australia",
                            active = true,
                            lastSeenLabel = "Jul 17, 2026",
                            callingPrimary = true,
                            callingPreferred = true,
                        ),
                    ),
                )
            }
        }
        composeRule.waitForIdle()

        // The banner atop the list, with its explainer and two actions.
        composeRule.onNodeWithText("Simmo crashed").assertExists()
        composeRule.onNodeWithText("Send a crash report to help us fix it").assertExists()
        composeRule.onNodeWithText("Share").assertExists()
        composeRule.onNodeWithText("Dismiss").assertExists()
        // The SIM list is still there below it.
        composeRule.onNodeWithText("Telstra personal").assertExists()
        captureSnapshot("sim_registry_crash_banner.png")
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
