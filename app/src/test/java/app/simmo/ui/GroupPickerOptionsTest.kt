package app.simmo.ui

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import app.simmo.STATE_LOAD_TIMEOUT_MS
import app.simmo.SimmoApp
import app.simmo.domain.CountryGroups
import app.simmo.domain.CustomGroup
import app.simmo.domain.withGroupMarkedForRemoval
import app.simmo.domain.withGroupSaved
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * A soft-deleted group must not be offered as a new pick while it's awaiting
 * purge — otherwise a rule could be built pointing at a group the imminent purge
 * is about to drop, stranding that rule with a matcher that matches nothing. It
 * must, however, stay in the option list marked non-selectable, so an existing
 * rule that references it still resolves its label (not the raw id) during the
 * undo window, and stay in [customGroups] so the Groups screen renders it
 * struck-through (Codex on PR #61).
 */
@RunWith(RobolectricTestRunner::class)
class GroupPickerOptionsTest {

    private lateinit var vm: RulesViewModel

    @After
    fun cancelViewModel() {
        if (::vm.isInitialized) vm.viewModelScope.cancel()
    }

    @Test
    fun `no group options are offered until the persisted snapshot loads`() {
        val app = ApplicationProvider.getApplicationContext<SimmoApp>()
        vm = RulesViewModel(app, SavedStateHandle())
        // With nothing collecting groupOptions, its WhileSubscribed value is the
        // seed. It must be empty — the static shipped list is never offered as
        // selectable before load, so a group the user deleted can't be picked in
        // the cold-start window and saved into a match-nothing rule (Codex on
        // PR #104).
        assertEquals(emptyList<CountryGroupOptionUi>(), vm.groupOptions.value)
    }

    @Test
    fun `a soft-deleted group stays for its label but is not selectable`() = runBlocking {
        withTimeout(STATE_LOAD_TIMEOUT_MS) {
            val app = ApplicationProvider.getApplicationContext<SimmoApp>()
            vm = RulesViewModel(app, SavedStateHandle())
            val holder = app.stateHolders().filterNotNull().first()
            holder.updateCustomGroups {
                it.withGroupSaved(CustomGroup("custom:keep", "Keep", listOf("GB")))
                    .withGroupSaved(CustomGroup("custom:gone", "Gone", listOf("FR")))
                    .withGroupMarkedForRemoval("custom:gone")
            }

            // groupOptions is WhileSubscribed — keep a live collector so .value
            // reflects the store. Collect on the view model's own (main) scope so
            // idling the paused looper below actually drives them; a collector on
            // this runBlocking thread would never run while the poll loop sleeps.
            val collector = vm.viewModelScope.launch { vm.groupOptions.collect {} }
            val listCollector = vm.viewModelScope.launch { vm.customGroups.collect {} }
            val mainLooper = shadowOf(Looper.getMainLooper())
            val deadline = System.currentTimeMillis() + 30_000
            fun option(id: String) = vm.groupOptions.value.firstOrNull { it.id == id }
            while (option("custom:keep") == null && System.currentTimeMillis() < deadline) {
                mainLooper.idle()
                Thread.sleep(1)
            }

            // Both groups keep their label entry, so an existing rule renders the
            // name either way — but only the live group is selectable in a picker.
            assertEquals("Keep", option("custom:keep")?.label)
            assertTrue(option("custom:keep")!!.selectable)
            assertEquals("Gone", option("custom:gone")?.label)
            assertFalse(option("custom:gone")!!.selectable)
            // Still in the raw list too (struck-through in the Groups screen).
            assertEquals(
                CountryGroups.allIds().toSet() + setOf("custom:keep", "custom:gone"),
                vm.customGroups.value.mapTo(HashSet()) { it.id },
            )
            collector.cancel()
            listCollector.cancel()
        }
    }
}
