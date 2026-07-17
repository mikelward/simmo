package app.simmo.telecom

import android.content.Context
import android.provider.ContactsContract
import androidx.test.core.app.ApplicationProvider
import app.simmo.domain.ContactCallApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.fakes.RoboCursor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ContactsReaderTest {

    private val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver

    private fun roboCursor(columns: List<String>, rows: List<Array<Any>>) = RoboCursor().apply {
        setColumnNames(columns)
        setResults(rows.toTypedArray())
    }

    @Test
    fun `attaches a WhatsApp action only to the number WhatsApp registered`() {
        // Mum has two numbers; only her mobile is a WhatsApp voip row. Dad has one.
        shadowOf(resolver).setCursor(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            roboCursor(
                listOf(
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                ),
                listOf(
                    arrayOf(1L, "key-mum", "Mum", "0412 345 678"),
                    arrayOf(1L, "key-mum", "Mum", "03 9000 1234"),
                    arrayOf(2L, "key-dad", "Dad", "0498765432"),
                ),
            ),
        )
        // WhatsApp voip rows carry the number in DATA1; only Mum's mobile.
        shadowOf(resolver).setCursor(
            ContactsContract.Data.CONTENT_URI,
            roboCursor(
                listOf(ContactsContract.Data._ID, ContactsContract.Data.DATA1),
                listOf(arrayOf(7L, "+61412345678")),
            ),
        )

        val index = ContactsReader(resolver).read("AU")

        // Mum's mobile is WhatsApp-callable…
        val mobile = index.lookup("0412345678", "AU")
        assertEquals("Mum", mobile?.displayName)
        assertEquals(mapOf(ContactCallApp.WHATSAPP to 7L), mobile?.callActions)

        // …her landline (same contact) is not.
        val landline = index.lookup("0390001234", "AU")
        assertEquals("Mum", landline?.displayName)
        assertEquals(emptyMap<ContactCallApp, Long>(), landline?.callActions)

        // Dad matches with no actions; an unknown number doesn't match at all.
        assertEquals(emptyMap<ContactCallApp, Long>(), index.lookup("0498765432", "AU")?.callActions)
        assertNull(index.lookup("0400000000", "AU"))
    }
}
