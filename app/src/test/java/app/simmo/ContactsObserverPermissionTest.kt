package app.simmo

import android.Manifest
import android.provider.ContactsContract
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Registering a [android.database.ContentObserver] on the Contacts provider
 * requires READ_CONTACTS. Doing it unconditionally in [SimmoApp.onCreate] threw
 * SecurityException on a real device before the grant — crashing the process on
 * every fresh-install launch, before onboarding could even ask for contacts.
 * Robolectric denies runtime permissions by default, so its auto-created app
 * runs onCreate in exactly that ungranted state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ContactsObserverPermissionTest {

    @Test
    fun `onCreate does not register the contacts observer without permission`() {
        // The global app already ran onCreate with READ_CONTACTS denied — the
        // fresh-install case. Before the fix it registered anyway (and would
        // have thrown on a device); now it must skip registration.
        val app = ApplicationProvider.getApplicationContext<SimmoApp>()
        val observers = shadowOf(app.contentResolver)
            .getContentObservers(ContactsContract.AUTHORITY_URI)
        assertTrue(
            "No contacts observer should be registered without READ_CONTACTS",
            observers.isEmpty(),
        )
    }

    @Test
    fun `granting contacts registers the observer, and only once`() {
        val app = ApplicationProvider.getApplicationContext<SimmoApp>()
        shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS)

        app.registerContactsObserver()
        assertEquals(
            1,
            shadowOf(app.contentResolver)
                .getContentObservers(ContactsContract.AUTHORITY_URI).size,
        )

        // Idempotent: the non-grant refreshes (resume, telephony refresh) that
        // also funnel through here must not stack duplicate observers.
        app.registerContactsObserver()
        assertEquals(
            1,
            shadowOf(app.contentResolver)
                .getContentObservers(ContactsContract.AUTHORITY_URI).size,
        )
    }
}
