package app.simmo

import android.Manifest
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The foreground-only active-data-subscription watch (SPEC "SIMs screen"):
 * registered while the app UI is visible so the SIMs screen's data chips update
 * on an automatic data switch, and torn down when it leaves — never a resident
 * callback. Same permission-gated, idempotent registration shape as the
 * contacts observer ([ContactsObserverPermissionTest]); tested at the
 * [SimmoApp] level, where the wiring lives, rather than by driving the whole
 * activity to its ready state. The `MainActivity` binding is a one-line
 * `LifecycleStartEffect` calling exactly these start/stop methods.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ActiveDataWatchTest {

    @Test
    fun `start does not register the watch without phone permission`() {
        // Robolectric denies runtime permissions by default — the ungranted case.
        val app = ApplicationProvider.getApplicationContext<SimmoApp>()
        app.startActiveDataWatch()
        assertFalse(
            "No active-data watch should register without READ_PHONE_STATE",
            app.isActiveDataWatchRegistered(),
        )
    }

    @Test
    fun `start registers with permission, stop tears down, both idempotent`() {
        val app = ApplicationProvider.getApplicationContext<SimmoApp>()
        shadowOf(app).grantPermissions(Manifest.permission.READ_PHONE_STATE)

        app.startActiveDataWatch()
        assertTrue(app.isActiveDataWatchRegistered())

        // Re-entering the foreground (a second ON_START) must not stack callbacks.
        app.startActiveDataWatch()
        assertTrue(app.isActiveDataWatchRegistered())

        // Backgrounding (ON_STOP) tears the watch down — foreground-only.
        app.stopActiveDataWatch()
        assertFalse(app.isActiveDataWatchRegistered())

        // A second stop (e.g. a stop with none registered) is safe.
        app.stopActiveDataWatch()
        assertFalse(app.isActiveDataWatchRegistered())
    }
}
