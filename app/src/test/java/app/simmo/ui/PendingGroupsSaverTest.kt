package app.simmo.ui

import androidx.compose.runtime.saveable.SaverScope
import app.simmo.domain.CustomGroup
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rule editor holds groups built in the picker as *pending* until the rule
 * is saved; the draft is saveable so a pending group survives process death and
 * still commits (the fix for the Codex P2 on PR #35). This locks that the flat
 * form round-trips, including multi-country groups and names with separators.
 */
class PendingGroupsSaverTest {

    private val scope = SaverScope { true }

    private fun roundTrip(groups: List<CustomGroup>): List<CustomGroup> {
        // Compose saves an empty list as null ("no state"); restore then falls
        // back to the initial (empty) value — same round-trip result.
        val saved = with(PendingGroupsSaver) { scope.save(groups) } ?: return emptyList()
        return PendingGroupsSaver.restore(saved) ?: emptyList()
    }

    @Test
    fun `pending groups round-trip through the saver`() {
        val groups = listOf(
            CustomGroup("custom:a", "Vodafone Zone 1", listOf("GB", "FR", "DE")),
            CustomGroup("custom:b", "Work, and play", listOf("US")),
            CustomGroup("custom:c", "Empty", emptyList()),
        )
        assertEquals(groups, roundTrip(groups))
    }

    @Test
    fun `an empty pending list round-trips`() {
        assertEquals(emptyList<CustomGroup>(), roundTrip(emptyList()))
    }
}
