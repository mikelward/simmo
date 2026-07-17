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
}
