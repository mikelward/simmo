package app.simmo.telecom

import android.app.Application
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities

/**
 * The connectivity-fire filter (SPEC "Data-roaming visibility" layer 3):
 * only a network that is *verifiably* not roaming may be skipped — every
 * uncertain shape refreshes, because a wrong skip silently loses an arrival
 * while a wrong check costs one refresh.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DataWatchReceiverTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun connectivityIntent(): Intent = Intent(DataWatchReceiver.ACTION_CONNECTIVITY_EVENT)

    @Test
    fun `a fire without a network still checks`() {
        assertEquals(true, connectivityEventWorthChecking(app, connectivityIntent()))
    }

    @Test
    fun `a fire whose network has vanished still checks`() {
        // The network extra is present but ConnectivityManager no longer
        // knows it — cellular just went away, the no-data nudge's moment.
        val gone = ShadowNetwork.newInstance(101)
        val intent = connectivityIntent().putExtra(ConnectivityManager.EXTRA_NETWORK, gone)
        assertEquals(true, connectivityEventWorthChecking(app, intent))
    }

    @Test
    fun `a verifiably not-roaming network is skipped`() {
        val network = ShadowNetwork.newInstance(102)
        val capabilities = ShadowNetworkCapabilities.newInstance()
        shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)
        shadowOf(app.getSystemService(ConnectivityManager::class.java))
            .setNetworkCapabilities(network, capabilities)
        val intent = connectivityIntent().putExtra(ConnectivityManager.EXTRA_NETWORK, network)
        assertEquals(false, connectivityEventWorthChecking(app, intent))
    }

    @Test
    fun `a roaming network checks`() {
        val network = ShadowNetwork.newInstance(103)
        val capabilities = ShadowNetworkCapabilities.newInstance()
        shadowOf(capabilities).removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)
        shadowOf(app.getSystemService(ConnectivityManager::class.java))
            .setNetworkCapabilities(network, capabilities)
        val intent = connectivityIntent().putExtra(ConnectivityManager.EXTRA_NETWORK, network)
        assertEquals(true, connectivityEventWorthChecking(app, intent))
    }
}
