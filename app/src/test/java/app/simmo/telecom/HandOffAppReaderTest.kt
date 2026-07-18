package app.simmo.telecom

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import app.simmo.domain.DialHandoffApp
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HandOffAppReaderTest {

    private val packageManager =
        ApplicationProvider.getApplicationContext<Context>().packageManager

    /** Installs [app]; with [schemes], also an activity receiving VIEW on them. */
    private fun install(app: DialHandoffApp, vararg schemes: String) {
        val shadow = shadowOf(packageManager)
        shadow.installPackage(PackageInfo().apply { packageName = app.packageName })
        if (schemes.isEmpty()) return
        val component = ComponentName(app.packageName, "${app.packageName}.CallActivity")
        shadow.addActivityIfNotPresent(component)
        shadow.addIntentFilterForActivity(
            component,
            IntentFilter(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                schemes.forEach(::addDataScheme)
            },
        )
    }

    @Test
    fun `nothing installed means nothing offered`() {
        assertEquals(emptySet<DialHandoffApp>(), installedDialHandoffApps(packageManager))
    }

    @Test
    fun `apps whose launch intent resolves are offered`() {
        install(DialHandoffApp.VIBER, "viber")
        install(DialHandoffApp.YOLLA, "tel")
        assertEquals(
            setOf(DialHandoffApp.VIBER, DialHandoffApp.YOLLA),
            installedDialHandoffApps(packageManager),
        )
    }

    @Test
    fun `an installed app that cannot receive its launch intent is not offered`() {
        // Installed but with no activity for the deep link (e.g. a Yolla build
        // without a tel: handler): offering it would let users save a rule that
        // always falls through to an unmodified carrier call.
        install(DialHandoffApp.YOLLA)
        assertEquals(emptySet<DialHandoffApp>(), installedDialHandoffApps(packageManager))
    }
}
