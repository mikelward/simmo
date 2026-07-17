package app.simmo

import android.os.Bundle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ManageSimsRequestTest {

    private fun savedState(pending: Boolean) = Bundle().apply {
        putBoolean("manage_sims_requested", pending)
    }

    @Test
    fun `fresh launch from the tile requests the SIMs screen`() {
        assertTrue(
            MainActivity.pendingManageSimsRequest(null, MainActivity.ACTION_MANAGE_SIMS),
        )
    }

    @Test
    fun `fresh launch from the launcher does not`() {
        assertFalse(MainActivity.pendingManageSimsRequest(null, "android.intent.action.MAIN"))
        assertFalse(MainActivity.pendingManageSimsRequest(null, null))
    }

    @Test
    fun `recreation keeps an unconsumed tile request`() {
        // A tile tap that lands on onboarding, then rotation/process death
        // there: the request must survive until it can be consumed (Codex on
        // PR #22).
        assertTrue(
            MainActivity.pendingManageSimsRequest(savedState(true), MainActivity.ACTION_MANAGE_SIMS),
        )
    }

    @Test
    fun `recreation does not resurrect a consumed request from the redelivered intent`() {
        assertFalse(
            MainActivity.pendingManageSimsRequest(savedState(false), MainActivity.ACTION_MANAGE_SIMS),
        )
    }
}
