package app.simmo.telecom

import android.content.ContentResolver
import android.provider.ContactsContract
import app.simmo.domain.ContactCallApp
import app.simmo.domain.ContactNumberIndex
import app.simmo.domain.RawCallAction
import app.simmo.domain.RawContactNumber
import app.simmo.domain.buildContactNumberIndex
import com.google.i18n.phonenumbers.PhoneNumberUtil

/**
 * Reads the device contacts into a warm [ContactNumberIndex] for app-to-app
 * hand-off (SPEC "Hand-off to another app") and, later, same-contact number
 * correction. Runs off the decision path — it does contacts IPC — and needs
 * `READ_CONTACTS`; without the grant (or on any provider error) it returns an
 * empty index, so callers simply see "no contact match" rather than failing.
 */
class ContactsReader(
    private val resolver: ContentResolver,
    private val util: PhoneNumberUtil = PhoneNumberUtil.getInstance(),
) {
    fun read(defaultRegion: String): ContactNumberIndex =
        try {
            buildContactNumberIndex(readPhoneNumbers(), readCallActions(), defaultRegion, util)
        } catch (_: RuntimeException) {
            // Any provider failure degrades to no matches rather than crashing the
            // snapshot warmer: a missing/revoked READ_CONTACTS (SecurityException),
            // or an OEM/provider fault (IllegalArgument/IllegalState/SQLite/etc.).
            ContactNumberIndex.EMPTY
        }

    /**
     * The app-to-app call actions, one per registered *number* — keyed to the
     * number the app can call (its `Data.DATA1`), so the builder attaches it only
     * to that line, never to the contact's other numbers. (`DATA1` for these voip
     * rows is the phone number; the exact column still wants device confirmation
     * — see docs/handoff-intents.md.)
     */
    private fun readCallActions(): List<RawCallAction> {
        val actions = ArrayList<RawCallAction>()
        for (app in ContactCallApp.entries) {
            resolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data._ID, ContactsContract.Data.DATA1),
                "${ContactsContract.Data.MIMETYPE} = ?",
                arrayOf(app.dataMimeType),
                null,
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(ContactsContract.Data._ID)
                val numberCol = cursor.getColumnIndexOrThrow(ContactsContract.Data.DATA1)
                while (cursor.moveToNext()) {
                    val raw = cursor.getString(numberCol) ?: continue
                    // Some apps store the number as a JID ("61412345678@s.whatsapp.net"):
                    // the part before '@' is the full international number without the
                    // leading '+'. Mark it international, else "61412345678" would parse
                    // as an (invalid) national number against the region and be dropped,
                    // so the contact never gets a call action. A plain stored number
                    // (no '@') keeps normalizing against the region as before.
                    val number = if ('@' in raw) "+${raw.substringBefore('@')}" else raw
                    actions += RawCallAction(app, number, cursor.getLong(idCol))
                }
            }
        }
        return actions
    }

    private fun readPhoneNumbers(): List<RawContactNumber> {
        val rows = ArrayList<RawContactNumber>()
        resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            val contactCol = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val keyCol = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY)
            val nameCol = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberCol = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext()) {
                val number = cursor.getString(numberCol) ?: continue
                val contactId = cursor.getLong(contactCol)
                rows += RawContactNumber(
                    lookupKey = cursor.getString(keyCol) ?: contactId.toString(),
                    displayName = cursor.getString(nameCol).orEmpty(),
                    number = number,
                )
            }
        }
        return rows
    }
}
