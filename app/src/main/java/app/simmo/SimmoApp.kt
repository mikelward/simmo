package app.simmo

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import app.simmo.domain.ActiveSim
import app.simmo.domain.CountryVerdict
import app.simmo.domain.DecisionEngine
import app.simmo.domain.PhoneNumberCountryDetector
import app.simmo.domain.pendingNewSimNotifications
import app.simmo.notify.SimNotifications
import app.simmo.store.InstallMarker
import app.simmo.store.SimmoStateHolder
import app.simmo.store.simmoStateStore
import app.simmo.telecom.HeldCallStore
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
    val heldCalls = HeldCallStore()
    val notifications by lazy { SimNotifications(this) }

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

    /**
     * Country of [dialedNumber] against the current default region, for the
     * chooser's header. The chooser is only ever launched by the service
     * after a warm snapshot produced an Ask verdict, so the parser metadata
     * is already loaded and this stays instant.
     */
    fun detectCountry(dialedNumber: String): CountryVerdict =
        detector.detect(dialedNumber, assembler.current()?.defaultRegion ?: "")

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
        val active = assembler.activeSims()
        maybeOfferHeldCall(active)
        val holder = stateHolderFlow.value ?: return
        if (active.isEmpty()) return
        // A first-ever capture registers every current SIM at once; nudging
        // about SIMs the user has had all along would just be noise, so that
        // batch is marked notified without posting.
        val firstCapture = holder.current?.simRegistry.isNullOrEmpty()
        val registry = holder.recordSeenSims(active, System.currentTimeMillis())
        val pending = registry.pendingNewSimNotifications(active)
        if (pending.isEmpty()) return
        when {
            firstCapture -> holder.markNewSimsNotified(pending.map { it.ref() })
            notifications.canPost() -> {
                pending.forEach { sim ->
                    notifications.postNewSim(sim.displayName.ifBlank { sim.carrierName })
                }
                holder.markNewSimsNotified(pending.map { it.ref() })
            }
            // No permission: leave unmarked so a later grant can still nudge
            // about a SIM whose prompt is genuinely unanswered.
        }
    }

    /**
     * The held-call offer (SPEC "Disabled-SIM assist" step 3): when a
     * subscription change makes a wanted SIM active, post the one-tap offer.
     * The notification opens the chooser; the call is never auto-placed.
     */
    private fun maybeOfferHeldCall(active: List<ActiveSim>) {
        val call = heldCalls.current(System.currentTimeMillis()) ?: return
        val sim = call.activatedWantedSim(active) ?: return
        if (!notifications.canPost()) return
        heldCalls.clear()
        notifications.postHeldCallOffer(
            handle = Uri.parse(call.handleUri),
            skippedSims = call.wantedSims,
            simLabel = sim.displayName.ifBlank { sim.carrierName },
        )
    }
}
