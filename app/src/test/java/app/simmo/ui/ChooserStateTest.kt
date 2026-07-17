package app.simmo.ui

import app.simmo.domain.ActiveSim
import app.simmo.domain.CountryVerdict
import app.simmo.domain.PhoneAccountRef
import app.simmo.domain.SimRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChooserStateTest {

    private val telstra = ActiveSim(1, "Telstra", "Telstra AU", PhoneAccountRef("a1"), "au")
    private val tmobile = ActiveSim(2, "T-Mobile", "", PhoneAccountRef("a2"), "us")

    @Test
    fun `determined destination shows its country and offers the remember rule`() {
        val state = buildChooserUiState(
            dialedNumber = "+61412345678",
            country = CountryVerdict.Country("AU"),
            activeSims = listOf(telstra, tmobile),
            skippedInactiveSims = emptyList(),
        )
        assertEquals("+61412345678", state.dialedNumber)
        assertEquals("+61 Australia", state.countryLabel)
        assertEquals("AU", state.rememberRegion)
        assertEquals("Australia", state.rememberCountryName)
        assertEquals(
            listOf(
                ChooserTargetUi(SimRef(1, "Telstra", "Telstra AU"), PhoneAccountRef("a1"), "Telstra AU"),
                // Display name blank: the carrier name is the label.
                ChooserTargetUi(SimRef(2, "T-Mobile", ""), PhoneAccountRef("a2"), "T-Mobile"),
            ),
            state.targets,
        )
    }

    @Test
    fun `undetermined destination has no country and no remember rule`() {
        val state = buildChooserUiState(
            dialedNumber = "*100#",
            country = CountryVerdict.Undetermined,
            activeSims = listOf(telstra),
            skippedInactiveSims = emptyList(),
        )
        assertNull(state.countryLabel)
        assertNull(state.rememberRegion)
        assertNull(state.rememberCountryName)
    }

    @Test
    fun `skipped disabled sims surface by name`() {
        val state = buildChooserUiState(
            dialedNumber = "+61412345678",
            country = CountryVerdict.Country("AU"),
            activeSims = listOf(tmobile),
            skippedInactiveSims = listOf(
                SimRef(7, "Vodafone", "Voda AU"),
                SimRef(8, "Optus", ""),
            ),
        )
        assertEquals(listOf("Voda AU", "Optus"), state.skippedSimNames)
    }

    @Test
    fun `a skipped sim that was re-enabled drops off the note`() {
        // The user jumped to SIM settings from the chooser and enabled the
        // SIM: rebuilt against the refreshed SIM list, the note clears (the
        // SIM now has its own call button in targets).
        val vodafone = ActiveSim(7, "Vodafone", "Voda AU", PhoneAccountRef("a7"), "au")
        val state = buildChooserUiState(
            dialedNumber = "+61412345678",
            country = CountryVerdict.Country("AU"),
            activeSims = listOf(tmobile, vodafone),
            skippedInactiveSims = listOf(SimRef(7, "Vodafone", "Voda AU")),
        )
        assertEquals(emptyList<String>(), state.skippedSimNames)
        assertEquals(
            listOf(
                ChooserTargetUi(SimRef(2, "T-Mobile", ""), PhoneAccountRef("a2"), "T-Mobile"),
                ChooserTargetUi(SimRef(7, "Vodafone", "Voda AU"), PhoneAccountRef("a7"), "Voda AU"),
            ),
            state.targets,
        )
    }
}
