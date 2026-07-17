package app.simmo

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keeps the CI unit-test lane exercised until the Phase 1 domain tests land
 * (rule evaluation, country detection, SIM re-binding — see TODO.md).
 */
class ScaffoldTest {
    @Test
    fun `test infrastructure runs`() {
        assertTrue(true)
    }
}
