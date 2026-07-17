package app.simmo.telecom

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.ContactsContract
import androidx.test.core.app.ApplicationProvider
import app.simmo.domain.ContactCallApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Serves the phone rows and WhatsApp voip rows the reader queries. */
class FakeContactsProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = when (uri) {
        ContactsContract.CommonDataKinds.Phone.CONTENT_URI -> MatrixCursor(
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
            ),
        ).apply {
            addRow(arrayOf<Any?>(1L, "key-mum", "Mum", "0412 345 678"))
            addRow(arrayOf<Any?>(1L, "key-mum", "Mum", "03 9000 1234"))
            addRow(arrayOf<Any?>(2L, "key-dad", "Dad", "0498765432"))
        }

        ContactsContract.Data.CONTENT_URI -> MatrixCursor(
            arrayOf(ContactsContract.Data._ID, ContactsContract.Data.DATA1),
        ).apply {
            // Only WhatsApp voip rows, and only Mum's mobile. Stored as a JID
            // ("<intl digits>@s.whatsapp.net") — the format that must normalize as
            // international, not against the region.
            if (selectionArgs?.firstOrNull() == ContactCallApp.WHATSAPP.dataMimeType) {
                addRow(arrayOf<Any?>(7L, "61412345678@s.whatsapp.net"))
            }
        }

        else -> null
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, s: String?, a: Array<out String>?) = 0
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ContactsReaderTest {

    private val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver

    @Before
    fun setUp() {
        Robolectric.setupContentProvider(FakeContactsProvider::class.java, ContactsContract.AUTHORITY)
    }

    @Test
    fun `attaches a WhatsApp action only to the number WhatsApp registered`() {
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
