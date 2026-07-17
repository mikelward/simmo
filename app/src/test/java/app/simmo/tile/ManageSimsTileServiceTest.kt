package app.simmo.tile

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import app.simmo.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ManageSimsTileServiceTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `tile intent targets MainActivity with the manage-SIMs action`() {
        val intent = ManageSimsTileService.manageSimsIntent(context)
        assertEquals(MainActivity.ACTION_MANAGE_SIMS, intent.action)
        assertEquals(MainActivity::class.java.name, intent.component?.className)
    }

    @Test
    fun `tile intent leaves the tile's task and reuses a foreground Simmo`() {
        val flags = ManageSimsTileService.manageSimsIntent(context).flags
        // NEW_TASK: launched from a service context; SINGLE_TOP: a foreground
        // MainActivity is routed via onNewIntent instead of a second instance
        // stacking on top.
        for (required in intArrayOf(
            Intent.FLAG_ACTIVITY_NEW_TASK,
            Intent.FLAG_ACTIVITY_SINGLE_TOP,
        )) {
            assertNotEquals(0, flags and required)
        }
    }

    @Test
    fun `tile intent never clears activities stacked above MainActivity`() {
        // The Ask chooser can sit above MainActivity mid-call-attempt;
        // CLEAR_TOP would finish it and silently drop the canceled-and-not-
        // yet-re-placed call (Codex on PR #22).
        val flags = ManageSimsTileService.manageSimsIntent(context).flags
        assertEquals(0, flags and Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}
