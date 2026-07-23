package app.simmo.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContactLookupTest {

    private fun number(name: String, number: String, key: String = name) =
        RawContactNumber(lookupKey = key, displayName = name, number = number)

    private fun whatsApp(number: String, dataRowId: Long) =
        RawCallAction(ContactCallApp.WHATSAPP, number, dataRowId)

    @Test
    fun `a dialed number matches its contact regardless of stored format`() {
        val index = buildContactNumberIndex(
            numbers = listOf(number("Mum", "(04) 1234 5678")),
            callActions = listOf(whatsApp("+61412345678", 7L)),
            defaultRegion = "AU",
        )
        for (dialed in listOf("0412345678", "0412 345 678", "+61412345678")) {
            val match = index.lookup(dialed, "AU")
            assertEquals("dialed=$dialed", "Mum", match?.displayName)
            assertEquals(mapOf(ContactCallApp.WHATSAPP to 7L), match?.callActions)
        }
    }

    @Test
    fun `a call action attaches only to the number the app knows`() {
        // One contact, two numbers; WhatsApp knows only the mobile.
        val index = buildContactNumberIndex(
            numbers = listOf(
                number("Mum", "+61412345678", key = "mum"),
                number("Mum", "+61390001234", key = "mum"),
            ),
            callActions = listOf(whatsApp("+61412345678", 7L)),
            defaultRegion = "AU",
        )
        // The WhatsApp mobile carries the action…
        assertEquals(mapOf(ContactCallApp.WHATSAPP to 7L), index.lookup("+61412345678", "AU")?.callActions)
        // …the landline on the same contact does not (the bug this guards).
        val landline = index.lookup("+61390001234", "AU")
        assertEquals("Mum", landline?.displayName)
        assertEquals(emptyMap<ContactCallApp, Long>(), landline?.callActions)
    }

    @Test
    fun `a number belonging to no contact does not match`() {
        val index = buildContactNumberIndex(listOf(number("Mum", "0412345678")), emptyList(), "AU")
        assertNull(index.lookup("0498765432", "AU"))
    }

    @Test
    fun `unparseable numbers are dropped from the index`() {
        val index = buildContactNumberIndex(
            listOf(number("Shortcode", "1234"), number("Mum", "+61412345678")),
            emptyList(),
            "AU",
        )
        assertEquals(1, index.size)
        assertNull(index.lookup("1234", "AU"))
        assertEquals("Mum", index.lookup("+61412345678", "AU")?.displayName)
    }

    @Test
    fun `a contact with no app-to-app action still matches with no call actions`() {
        val index = buildContactNumberIndex(listOf(number("Dad", "+61498765432")), emptyList(), "AU")
        val match = index.lookup("0498765432", "AU")
        assertEquals("Dad", match?.displayName)
        assertEquals(emptyMap<ContactCallApp, Long>(), match?.callActions)
    }

    @Test
    fun `an action for a number no contact lists is simply unused`() {
        val index = buildContactNumberIndex(
            numbers = listOf(number("Mum", "+61412345678")),
            callActions = listOf(whatsApp("+61499999999", 9L)),
            defaultRegion = "AU",
        )
        assertEquals(emptyMap<ContactCallApp, Long>(), index.lookup("+61412345678", "AU")?.callActions)
    }

    @Test
    fun `region is applied when the dialed number is national format`() {
        val index = buildContactNumberIndex(listOf(number("Al", "+14155550123")), emptyList(), "US")
        assertEquals("Al", index.lookup("(415) 555-0123", "US")?.displayName)
        assertNull(index.lookup("0415550123", "AU"))
    }

    @Test
    fun `suggested regions are ranked by contact count, ties broken by region`() {
        // Two AU numbers, one US, one GB — AU leads, then GB and US tie on 1
        // and sort by region code (GB before US) for a stable order.
        val index = buildContactNumberIndex(
            numbers = listOf(
                number("Mum", "+61412345678", key = "mum"),
                number("Dad", "+61498765432", key = "dad"),
                number("Al", "+14155550123", key = "al"),
                number("Bea", "+442071234567", key = "bea"),
            ),
            callActions = emptyList(),
            defaultRegion = "AU",
        )
        assertEquals(listOf("AU", "GB", "US"), index.regionsByContactCount())
    }

    @Test
    fun `suggested regions count distinct contacts, not numbers`() {
        // One AU contact with three numbers must not outrank two GB contacts:
        // ranking is by people, not phone rows.
        val index = buildContactNumberIndex(
            numbers = listOf(
                number("Mum", "+61412345678", key = "mum"),
                number("Mum", "+61498765432", key = "mum"),
                number("Mum", "+61390001234", key = "mum"),
                number("Bea", "+442071234567", key = "bea"),
                number("Cy", "+442079999999", key = "cy"),
            ),
            callActions = emptyList(),
            defaultRegion = "AU",
        )
        assertEquals(listOf("GB", "AU"), index.regionsByContactCount())
    }

    @Test
    fun `suggested regions count every contact that shares a number`() {
        val index = buildContactNumberIndex(
            numbers = listOf(
                number("Mum", "+442071234567", key = "mum"),
                number("Dad", "+442071234567", key = "dad"),
                number("Nan", "+442071234567", key = "nan"),
                number("Al", "+61412345678", key = "al"),
                number("Bea", "+61498765432", key = "bea"),
            ),
            callActions = emptyList(),
            defaultRegion = "AU",
        )

        assertEquals(listOf("GB", "AU"), index.regionsByContactCount())
    }

    @Test
    fun `an empty index suggests no regions`() {
        assertEquals(emptyList<String>(), ContactNumberIndex.EMPTY.regionsByContactCount())
    }

    // --- Same-contact number correction ---

    @Test
    fun `an overseas number offers the same contact's local number`() {
        val index = buildContactNumberIndex(
            numbers = listOf(
                number("Mum", "+442071234567", key = "mum"),
                // Stored national-format; must still normalize and qualify.
                number("Mum", "0412 345 678", key = "mum"),
            ),
            callActions = emptyList(),
            defaultRegion = "AU",
        )
        assertEquals(
            NumberCorrection(listOf(CorrectionCandidate("Mum", "+61412345678"))),
            index.localCorrectionFor("+442071234567", "AU"),
        )
    }

    @Test
    fun `a contact with several local numbers offers them all`() {
        val index = buildContactNumberIndex(
            numbers = listOf(
                number("Mum", "+442071234567", key = "mum"),
                number("Mum", "+61412345678", key = "mum"),
                number("Mum", "+61390001234", key = "mum"),
            ),
            callActions = emptyList(),
            defaultRegion = "AU",
        )
        assertEquals(
            NumberCorrection(
                listOf(
                    CorrectionCandidate("Mum", "+61412345678"),
                    CorrectionCandidate("Mum", "+61390001234"),
                ),
            ),
            index.localCorrectionFor("+442071234567", "AU"),
        )
    }

    @Test
    fun `no correction without a local alternative`() {
        // The contact's other number is also overseas.
        val index = buildContactNumberIndex(
            numbers = listOf(
                number("Bea", "+442071234567", key = "bea"),
                number("Bea", "+14155550123", key = "bea"),
            ),
            callActions = emptyList(),
            defaultRegion = "AU",
        )
        assertNull(index.localCorrectionFor("+442071234567", "AU"))
    }

    @Test
    fun `no correction for a number belonging to no contact`() {
        val index = buildContactNumberIndex(
            listOf(number("Mum", "+61412345678")),
            emptyList(),
            "AU",
        )
        assertNull(index.localCorrectionFor("+442071234567", "AU"))
    }

    @Test
    fun `a local number never corrects to itself`() {
        // Dialing the contact's own local number offers nothing.
        val index = buildContactNumberIndex(
            numbers = listOf(
                number("Mum", "+442071234567", key = "mum"),
                number("Mum", "+61412345678", key = "mum"),
            ),
            callActions = emptyList(),
            defaultRegion = "AU",
        )
        assertNull(index.localCorrectionFor("+61412345678", "AU"))
    }

    @Test
    fun `a shared line is flagged and offers every owner's local numbers`() {
        // A shared line (family landline) can't say whose local number to
        // prefer, so the correction is flagged confirm-only and labels each
        // owner's candidates (maintainer: ok to ask, never silent).
        val index = buildContactNumberIndex(
            numbers = listOf(
                number("Mum", "+442071234567", key = "mum"),
                number("Mum", "+61412345678", key = "mum"),
                number("Aunt Vi", "+442071234567", key = "aunt"),
                number("Aunt Vi", "+61390001234", key = "aunt"),
            ),
            callActions = emptyList(),
            defaultRegion = "AU",
        )
        assertEquals(
            NumberCorrection(
                listOf(
                    CorrectionCandidate("Mum", "+61412345678"),
                    CorrectionCandidate("Aunt Vi", "+61390001234"),
                ),
                sharedLine = true,
            ),
            index.localCorrectionFor("+442071234567", "AU"),
        )
    }

    @Test
    fun `a shared line with one owner's local number is still confirm-only`() {
        // Only Mum has a local alternative, but the dialed line is shared —
        // silently calling Mum could still reach the wrong person.
        val index = buildContactNumberIndex(
            numbers = listOf(
                number("Mum", "+442071234567", key = "mum"),
                number("Mum", "+61412345678", key = "mum"),
                number("Aunt Vi", "+442071234567", key = "aunt"),
            ),
            callActions = emptyList(),
            defaultRegion = "AU",
        )
        assertEquals(
            NumberCorrection(
                listOf(CorrectionCandidate("Mum", "+61412345678")),
                sharedLine = true,
            ),
            index.localCorrectionFor("+442071234567", "AU"),
        )
    }

    @Test
    fun `another contact's numbers are never offered as corrections`() {
        val index = buildContactNumberIndex(
            numbers = listOf(
                number("Bea", "+442071234567", key = "bea"),
                number("Mum", "+61412345678", key = "mum"),
            ),
            callActions = emptyList(),
            defaultRegion = "AU",
        )
        assertNull(index.localCorrectionFor("+442071234567", "AU"))
    }
}
