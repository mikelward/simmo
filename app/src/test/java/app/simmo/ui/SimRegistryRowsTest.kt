package app.simmo.ui

import app.simmo.domain.ActiveSim
import app.simmo.domain.PhoneAccountRef
import app.simmo.domain.RegisteredSim
import app.simmo.domain.SimRef
import org.junit.Assert.assertEquals
import org.junit.Test

class SimRegistryRowsTest {

    private val telstraActive = ActiveSim(1, "Telstra", "Telstra personal", PhoneAccountRef("a1"))

    private fun rows(registry: List<RegisteredSim>, active: List<ActiveSim>) =
        buildRegistryRows(registry, active, formatLastSeen = { "day $it" })

    @Test
    fun `active sims lead, stale rows sink to the bottom by last seen`() {
        val registry = listOf(
            RegisteredSim(7, "Vodafone", "Voda AU", 100L),
            RegisteredSim(1, "Telstra", "Telstra personal", 900L),
            RegisteredSim(8, "Optus", "Optus travel", 500L),
        )
        assertEquals(
            listOf(
                RegistrySimRowUi(SimRef(1, "Telstra", "Telstra personal"), "Telstra personal", "Telstra", detail = null, active = true, lastSeenLabel = "day 900"),
                RegistrySimRowUi(SimRef(8, "Optus", "Optus travel"), "Optus travel", "Optus", detail = null, active = false, lastSeenLabel = "day 500"),
                RegistrySimRowUi(SimRef(7, "Vodafone", "Voda AU"), "Voda AU", "Vodafone", detail = null, active = false, lastSeenLabel = "day 100"),
            ),
            rows(registry, listOf(telstraActive)),
        )
    }

    @Test
    fun `a data-only subscription counts as active`() {
        // The SIMs screen unions the call snapshot with the subscription
        // rows: an active travel eSIM without calling must not be shown and
        // sorted as merely "last seen".
        val dataOnly = ActiveSim(5, "Orange", "Orange Holiday", PhoneAccountRef("subscription:5"), "fr")
        val registry = listOf(
            RegisteredSim(5, "Orange", "Orange Holiday", 100L, countryIso = "fr", callCapable = false),
        )
        assertEquals(true, rows(registry, listOf(telstraActive, dataOnly)).single().active)
    }

    @Test
    fun `detail line shows the sim's number and country`() {
        val registry = listOf(
            RegisteredSim(1, "Telstra", "Telstra personal", 100L, countryIso = "au", phoneNumber = "+61412345678"),
        )
        assertEquals("+61 412 345 678 · Australia", rows(registry, emptyList()).single().detail)
    }

    @Test
    fun `detail line degrades to whichever half is known`() {
        assertEquals(
            "Australia",
            registryDetailLabel(phoneNumber = "", countryIso = "AU"),
        )
        assertEquals(
            "+61 412 345 678",
            registryDetailLabel(phoneNumber = "+61412345678", countryIso = ""),
        )
        assertEquals(null, registryDetailLabel(phoneNumber = "", countryIso = ""))
    }

    @Test
    fun `unparseable numbers show verbatim rather than hiding`() {
        assertEquals(
            "12 · Australia",
            registryDetailLabel(phoneNumber = "12", countryIso = "AU"),
        )
    }

    @Test
    fun `national-format numbers resolve against the sim's own country`() {
        assertEquals(
            "+61 412 345 678 · Australia",
            registryDetailLabel(phoneNumber = "0412345678", countryIso = "AU"),
        )
    }

    @Test
    fun `name falls back to the carrier and drops a redundant carrier line`() {
        val registry = listOf(
            // Blank display name: the carrier is the name, so no second line.
            RegisteredSim(2, "T-Mobile", "", 100L),
            // Display name equal to the carrier (case/space aside): same.
            RegisteredSim(3, "Optus", " optus ", 100L),
        )
        val built = rows(registry, emptyList())
        assertEquals("T-Mobile", built[0].name)
        assertEquals(null, built[0].carrier)
        assertEquals(null, built[1].carrier)
    }
}
