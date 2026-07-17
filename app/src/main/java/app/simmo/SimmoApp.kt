package app.simmo

import android.app.Application
import android.telephony.SubscriptionManager
import app.simmo.domain.DecisionEngine
import app.simmo.domain.PhoneNumberCountryDetector
import app.simmo.store.InstallMarker
import app.simmo.store.SimmoStateHolder
import app.simmo.store.simmoStateStore
import app.simmo.telecom.PassTokenStore
import app.simmo.telecom.RedirectionCoordinator
import app.simmo.telecom.SnapshotAssembler
import app.simmo.telecom.TelephonyReader
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asExecutor
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
    private val stateHolderRef = AtomicReference<SimmoStateHolder?>(null)

    lateinit var assembler: SnapshotAssembler
        private set
    lateinit var coordinator: RedirectionCoordinator
        private set

    /** Null until the install marker read completes; readers degrade until then. */
    fun stateHolder(): SimmoStateHolder? = stateHolderRef.get()

    override fun onCreate() {
        super.onCreate()
        val reader = TelephonyReader(this)
        assembler = SnapshotAssembler(
            reader = reader,
            stateHolder = { stateHolderRef.get() },
            tokens = passTokens,
            nowMillis = System::currentTimeMillis,
        )
        coordinator = RedirectionCoordinator(
            engine = DecisionEngine(detector),
            snapshotProvider = assembler::current,
            nowMillis = System::currentTimeMillis,
        )

        appScope.launch {
            // Blocking file read, deliberately off the main thread; until it
            // completes the snapshot is null and calls proceed unmodified.
            val installId = InstallMarker.get(this@SimmoApp)
            stateHolderRef.set(SimmoStateHolder(simmoStateStore, appScope, installId))
            recordSims()
        }
        appScope.launch { detector.warmUp() }
        appScope.launch {
            assembler.refresh()
            recordSims()
        }

        getSystemService(SubscriptionManager::class.java).addOnSubscriptionsChangedListener(
            Dispatchers.Default.asExecutor(),
            object : SubscriptionManager.OnSubscriptionsChangedListener() {
                override fun onSubscriptionsChanged() {
                    appScope.launch {
                        assembler.refresh()
                        recordSims()
                    }
                }
            },
        )
    }

    private suspend fun recordSims() {
        val holder = stateHolderRef.get() ?: return
        val active = assembler.activeSims()
        if (active.isNotEmpty()) {
            holder.recordSeenSims(active, System.currentTimeMillis())
        }
    }
}
