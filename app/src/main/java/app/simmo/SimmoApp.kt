package app.simmo

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import app.simmo.domain.DecisionEngine
import app.simmo.domain.PhoneNumberCountryDetector
import app.simmo.store.InstallMarker
import app.simmo.store.SimmoStateHolder
import app.simmo.store.simmoStateStore
import app.simmo.telecom.PassTokenStore
import app.simmo.telecom.RedirectionCoordinator
import app.simmo.telecom.SnapshotAssembler
import app.simmo.telecom.TelephonyReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Process-wide wiring. Everything the redirection service will read is
 * assembled here, warmed off the main thread at process start (AGENTS.md
 * "Fast decision path"): the persisted state loads eagerly, libphonenumber
 * metadata is pre-loaded, and telephony state is cached and refreshed on
 * subscription changes. Nothing in [coordinator]'s read path blocks.
 */
class SimmoApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val passTokens = PassTokenStore()

    private val detector = PhoneNumberCountryDetector()
    private val stateHolderFlow = MutableStateFlow<SimmoStateHolder?>(null)

    /**
     * Snapshot readiness includes warm parsing metadata: until [detector]'s
     * warm-up completes, decisions would load parser tables on the live
     * redirection path, so the coordinator sees no snapshot and calls proceed
     * unmodified (fast and safe) instead.
     */
    @Volatile
    private var metadataWarm = false

    lateinit var assembler: SnapshotAssembler
        private set
    lateinit var coordinator: RedirectionCoordinator
        private set

    /** Null until the install marker read completes; readers degrade until then. */
    fun stateHolder(): SimmoStateHolder? = stateHolderFlow.value

    /** For the UI, which needs to react when the holder becomes available. */
    fun stateHolders(): StateFlow<SimmoStateHolder?> = stateHolderFlow

    override fun onCreate() {
        super.onCreate()
        val reader = TelephonyReader(this)
        assembler = SnapshotAssembler(
            reader = reader,
            stateHolder = { stateHolderFlow.value },
            tokens = passTokens,
            nowMillis = System::currentTimeMillis,
        )
        coordinator = RedirectionCoordinator(
            engine = DecisionEngine(detector),
            snapshotProvider = { if (metadataWarm) assembler.current() else null },
            nowMillis = System::currentTimeMillis,
        )

        appScope.launch {
            // Blocking file read, deliberately off the main thread; until it
            // completes the snapshot is null and calls proceed unmodified.
            val installId = InstallMarker.get(this@SimmoApp)
            stateHolderFlow.value = SimmoStateHolder(simmoStateStore, appScope, installId)
            recordSims()
        }
        appScope.launch {
            detector.warmUp()
            metadataWarm = true
        }
        refreshTelephony()

        getSystemService(SubscriptionManager::class.java).addOnSubscriptionsChangedListener(
            Dispatchers.Default.asExecutor(),
            object : SubscriptionManager.OnSubscriptionsChangedListener() {
                override fun onSubscriptionsChanged() {
                    refreshTelephony()
                }
            },
        )

        // Roaming across a border changes the network country without any
        // subscription change; without this, national-format numbers keep
        // parsing against the old country until a process restart — the exact
        // traveler scenario the product exists for.
        ContextCompat.registerReceiver(
            this,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    refreshTelephony()
                }
            },
            IntentFilter(TelephonyManager.ACTION_NETWORK_COUNTRY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    /**
     * Re-reads telephony state off the main thread. Also called by onboarding
     * right after READ_PHONE_STATE is granted — the startup refresh on a fresh
     * install runs before the grant and caches an empty SIM list, and no
     * subscription-change event fires for a permission grant.
     */
    fun refreshTelephony() {
        appScope.launch {
            assembler.refresh()
            recordSims()
        }
    }

    private suspend fun recordSims() {
        val holder = stateHolderFlow.value ?: return
        val active = assembler.activeSims()
        if (active.isNotEmpty()) {
            holder.recordSeenSims(active, System.currentTimeMillis())
        }
    }
}
