package app.simmo.telecom

import app.simmo.domain.ActiveSim
import app.simmo.domain.CustomGroup
import app.simmo.domain.PhoneAccountRef
import app.simmo.domain.RegisteredSim
import app.simmo.store.SimmoState
import org.junit.Assert.assertEquals
import org.junit.Test

class DataSnapshotAssemblyTest {

    // A data-only travel eSIM: no call-capable account exists for it, so it
    // carries the reader's sentinel ref — the watch must still see it.
    private val travelEsim =
        ActiveSim(5, "Orange", "Orange Holiday", PhoneAccountRef("subscription:5"), countryIso = "fr")

    @Test
    fun `wires the cached reads and persisted state into the watch's input`() {
        val dataState = TelephonyReader.DataState(
            dataSubscriptionId = 5,
            networkCountry = "fr",
            roamingSubscriptionIds = setOf(5),
            dataRoamingEnabledSubscriptionIds = setOf(5),
            subscriptions = listOf(travelEsim),
        )
        val registered = RegisteredSim(7, "Orange", "Orange FR", 1_000L, countryIso = "fr")
        val state = SimmoState(
            simRegistry = listOf(registered),
            customGroups = listOf(CustomGroup("custom:1", "Zone 1", listOf("fr", "de"))),
        )

        val snapshot = buildDataSnapshot(dataState, state)

        assertEquals("fr", snapshot.networkCountry)
        // The watch's SIM list is the subscription rows, not the call
        // snapshot, so a data-only eSIM is watched too (Codex on PR #52).
        assertEquals(listOf(travelEsim), snapshot.activeSims)
        assertEquals(5, snapshot.dataSubscriptionId)
        assertEquals(setOf(5), snapshot.roamingSubscriptionIds)
        assertEquals(setOf(5), snapshot.dataRoamingEnabledSubscriptionIds)
        // Group members uppercased, exactly as the decision snapshot builds
        // them — both engines resolve groups through the same matcher helper.
        assertEquals(mapOf("custom:1" to listOf("FR", "DE")), snapshot.customGroups)
        assertEquals(listOf(registered), snapshot.registeredSims)
    }
}
