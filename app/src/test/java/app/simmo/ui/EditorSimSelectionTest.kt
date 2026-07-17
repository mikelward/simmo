package app.simmo.ui

import app.simmo.domain.SimRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The editor must highlight the SIM a rule points at even after a restore has
 * invalidated or reissued its subscription id — [resolveSelectedSim] mirrors
 * the routing layer's carrier/display fallback so the option still shows.
 */
class EditorSimSelectionTest {

    private fun option(id: Int, carrier: String, name: String, active: Boolean = true) =
        SimOptionUi(SimRef(id, carrier, name), name, active)

    private val telstra = option(1, "Telstra", "Telstra AU")
    private val tmobile = option(2, "T-Mobile", "T-Mobile US")
    private val options = listOf(telstra, tmobile)

    @Test
    fun `an exact subscription id selects that option`() {
        assertEquals(telstra.ref, resolveSelectedSim(telstra.ref, options))
    }

    @Test
    fun `a null ref selects the first option`() {
        assertEquals(telstra.ref, resolveSelectedSim(null, options))
        assertNull(resolveSelectedSim(null, emptyList()))
    }

    @Test
    fun `a sentinel-id ref re-links by carrier and display name`() {
        val stored = SimRef(SimRef.INVALID_SUBSCRIPTION_ID, "Telstra", "Telstra AU")
        assertEquals(telstra.ref, resolveSelectedSim(stored, options))
    }

    @Test
    fun `a reissued subscription id re-links by carrier and display name`() {
        // Same SIM, new id after a profile reinstall — no exact id match.
        val stored = SimRef(99, "Telstra", "Telstra AU")
        assertEquals(telstra.ref, resolveSelectedSim(stored, options))
    }

    @Test
    fun `matching ignores case and surrounding whitespace`() {
        val stored = SimRef(SimRef.INVALID_SUBSCRIPTION_ID, " telstra ", "TELSTRA AU")
        assertEquals(telstra.ref, resolveSelectedSim(stored, options))
    }

    @Test
    fun `an ambiguous carrier-and-name match keeps the stored ref rather than guessing`() {
        val twins = listOf(
            option(3, "Carrier", "Work"),
            option(4, "Carrier", "Work"),
        )
        val stored = SimRef(SimRef.INVALID_SUBSCRIPTION_ID, "Carrier", "Work")
        assertEquals(stored, resolveSelectedSim(stored, twins))
    }

    @Test
    fun `a SIM no longer present keeps the stored ref`() {
        val stored = SimRef(SimRef.INVALID_SUBSCRIPTION_ID, "Vodafone", "Voda AU")
        assertEquals(stored, resolveSelectedSim(stored, options))
    }
}
