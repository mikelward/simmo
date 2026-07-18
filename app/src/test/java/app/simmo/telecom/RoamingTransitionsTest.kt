package app.simmo.telecom

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Only a roaming *flip* counts (SPEC "Data-roaming visibility"): capability
 * callbacks fire constantly for validation and metering churn, and the first
 * sighting of a network was already checked by the wake that surfaced it.
 */
class RoamingTransitionsTest {

    @Test
    fun `the first sighting is not a transition`() {
        val transitions = RoamingTransitions<Int>()
        assertEquals(false, transitions.isTransition(1, notRoaming = true))
        assertEquals(false, transitions.isTransition(2, notRoaming = false))
    }

    @Test
    fun `a roaming flip is a transition, repeats are not`() {
        val transitions = RoamingTransitions<Int>()
        transitions.isTransition(1, notRoaming = true)
        assertEquals(true, transitions.isTransition(1, notRoaming = false))
        // The same state again — validation churn, not a flip.
        assertEquals(false, transitions.isTransition(1, notRoaming = false))
        // Flipping back home is a transition too (the mark handles dedupe).
        assertEquals(true, transitions.isTransition(1, notRoaming = true))
    }

    @Test
    fun `a forgotten network starts over as a first sighting`() {
        val transitions = RoamingTransitions<Int>()
        transitions.isTransition(1, notRoaming = true)
        transitions.forget(1)
        assertEquals(false, transitions.isTransition(1, notRoaming = false))
    }
}
