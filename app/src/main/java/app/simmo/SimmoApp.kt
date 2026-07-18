package app.simmo

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.provider.ContactsContract
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import app.simmo.analytics.TelemetryGate
import app.simmo.domain.ActiveSim
import app.simmo.domain.CountryVerdict
import app.simmo.domain.DecisionEngine
import app.simmo.domain.PhoneNumberCountryDetector
import app.simmo.domain.pendingNewSimNotifications
import app.simmo.notify.SimNotifications
import app.simmo.store.InstallMarker
import app.simmo.store.SimmoStateHolder
import app.simmo.store.simmoStateStore
import app.simmo.telecom.ContactsReader
import app.simmo.telecom.installedDialHandoffApps
import app.simmo.telecom.HeldCallStore
import app.simmo.telecom.PassTokenStore
import app.simmo.telecom.RedirectionCoordinator
import app.simmo.telecom.SnapshotAssembler
import app.simmo.telecom.TelephonyReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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

    /** Null in builds without a Firebase config (see SETUP.md). */
    private val telemetry by lazy { TelemetryGate.firebase(this) }

    /** The latest in-process opt-in tap (null before the first); see [setAnalyticsOptIn]. */
    private val optInTaps = MutableStateFlow<Boolean?>(null)

    /**
     * Snapshot readiness includes warm parsing metadata: until [detector]'s
     * warm-up completes, decisions would load parser tables on the live
     * redirection path, so the coordinator sees no snapshot and calls proceed
     * unmodified (fast and safe) instead.
     */
    @Volatile
    private var metadataWarm = false

    /** Trailing-edge debounce for the contacts-change observer; latest wins. */
    @Volatile
    private var contactRefreshJob: Job? = null

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
            contactsReader = ContactsReader(contentResolver),
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
            val holder = SimmoStateHolder(simmoStateStore, appScope, installId)
            stateHolderFlow.value = holder
            recordSims()
            // Publishing the holder only *starts* the async DataStore read
            // (its `state` begins at null), so `current.defaultRegionOverride`
            // isn't available yet. Rebuild the contact index once the state has
            // actually loaded, and again whenever the override changes (e.g. in
            // settings) — national-format contact numbers must be normalized
            // against the effective region, else hand-off rules skip them.
            holder.state
                .filterNotNull()
                .map { it.defaultRegionOverride }
                .distinctUntilChanged()
                .collect { assembler.refreshContacts() }
        }
        appScope.launch {
            detector.warmUp()
            metadataWarm = true
        }
        appScope.launch {
            // Crash reporting and analytics follow the persisted "Make Simmo
            // better" choice; until the state loads, the manifest keeps
            // collection off, and in-process taps mask staler persisted
            // values (see setAnalyticsOptIn). No-op in builds without a
            // Firebase config.
            val gate = telemetry ?: return@launch
            // An opt-out marked durably at tap time outlives a crash that
            // loses the slower main-state write: seed it as a synthetic tap
            // so a stale persisted "on" can't resurrect collection, and run
            // the opt-out cleanup now rather than after the state loads.
            val marker = getSharedPreferences(TELEMETRY_PREFS, MODE_PRIVATE)
            if (!marker.getBoolean(KEY_OPT_IN, true) && optInTaps.value == null) {
                optInTaps.value = false
                gate.set(false)
            }
            gate.follow(
                persisted = stateHolderFlow.filterNotNull().first().state
                    .filterNotNull()
                    .map { it.analyticsOptIn },
                taps = optInTaps,
            )
        }
        appScope.launch {
            // One sequential collector persists the opt-in taps: parallel
            // per-tap jobs could finish out of tap order and leave an earlier
            // choice on disk. The StateFlow conflates to the newest value —
            // the only one that matters.
            optInTaps.filterNotNull().collect { enabled ->
                // The durable marker first — a tiny commit with no
                // state-holder wait — so a crash before the main write still
                // leaves the choice on disk for the next launch's cleanup
                // (see the telemetry collector above).
                getSharedPreferences(TELEMETRY_PREFS, MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_OPT_IN, enabled)
                    .commit()
                stateHolderFlow.filterNotNull().first().setAnalyticsOptIn(enabled)
            }
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

        // Contacts can change while Simmo stays resident (a WhatsApp contact
        // added, removed, or its call-action row re-synced). Without tracking
        // that, the warm index would keep returning a stale — possibly deleted —
        // Data row for a hand-off, so the service would cancel the carrier call
        // and open WhatsApp on a dead row. Rebuild on change, debounced because
        // a single account sync fires a burst of notifications. Delivered on a
        // binder thread (null handler), so no main-thread work. Registered
        // unconditionally: without READ_CONTACTS the rebuild degrades to empty.
        contentResolver.registerContentObserver(
            ContactsContract.AUTHORITY_URI,
            /* notifyForDescendants = */ true,
            object : ContentObserver(null) {
                override fun onChange(selfChange: Boolean) {
                    // Drop the possibly-stale index *now* so a call placed during
                    // the debounce can't route to a just-deleted Data row (it
                    // degrades to "proceed unmodified"); debounce only the
                    // expensive repopulation.
                    assembler.clearContacts()
                    contactRefreshJob?.cancel()
                    contactRefreshJob = appScope.launch {
                        delay(CONTACT_REFRESH_DEBOUNCE_MILLIS)
                        assembler.refreshContacts()
                    }
                }
            },
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
            // Cache which dial-intent hand-off apps (Google Voice, Teams) are
            // reachable — installed and resolving their launch intent — so the
            // decision path can skip a rule whose target is gone. A
            // PackageManager query — fine here, off the decision path.
            assembler.setHandOffApps(
                installedDialHandoffApps(packageManager).mapTo(HashSet()) { it.packageName },
            )
            // Rebuild the contact index after the region is set; it feeds
            // app-to-app hand-off and degrades to empty without READ_CONTACTS.
            assembler.refreshContacts()
        }
    }

    /**
     * Rebuilds the contact index off the main thread — called after READ_CONTACTS
     * is granted (the startup build ran before the grant and read nothing).
     */
    fun refreshContacts() {
        appScope.launch { assembler.refreshContacts() }
    }

    /**
     * Immediately drops the warm contact index — called when READ_CONTACTS is
     * revoked so saved hand-off rules stop matching cached rows at once (a cheap
     * volatile write; no coroutine needed).
     */
    fun clearContacts() = assembler.clearContacts()

    /**
     * Records the "Make Simmo better" onboarding choice. The persister
     * coroutine writes it durably (waiting for the state holder rather than
     * dropping the write — the toggle can be flipped in the first seconds
     * after install, before the eager load has published), and telemetry
     * follows it immediately.
     */
    fun setAnalyticsOptIn(enabled: Boolean) {
        // Publishing the tap does everything ordered: the gate's collector
        // masks staler persisted emissions with it, and the persister
        // coroutine (onCreate) writes it to disk sequentially. Apply to the
        // SDKs now as well — a crash or upload while those writes are in
        // flight must already honor the tap.
        optInTaps.value = enabled
        telemetry?.set(enabled)
    }

    private suspend fun recordSims() {
        val active = assembler.activeSims()
        maybeOfferHeldCall(active)
        val holder = stateHolderFlow.value ?: return
        if (active.isEmpty()) return
        // The capture reports whether it populated a previously empty
        // registry (fresh install): that first batch registers every current
        // SIM at once, and nudging about SIMs the user has had all along
        // would just be noise — marked notified without posting.
        val capture = holder.recordSeenSims(active, System.currentTimeMillis())
        val pending = capture.registry.pendingNewSimNotifications(active)
        if (pending.isEmpty()) return
        when {
            capture.firstCapture -> holder.markNewSimsNotified(pending.map { it.ref() })
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
        val now = System.currentTimeMillis()
        val call = heldCalls.current(now) ?: return
        val sim = call.activatedWantedSim(active) ?: return
        if (!notifications.canPost()) return
        heldCalls.clear()
        notifications.postHeldCallOffer(
            call = call,
            simLabel = sim.displayName.ifBlank { sim.carrierName },
            nowMillis = now,
        )
    }

    private companion object {
        /** A contacts account sync fires a burst; wait for it to settle. */
        const val CONTACT_REFRESH_DEBOUNCE_MILLIS = 2_000L

        /** The telemetry choice's own durable copy (see [setAnalyticsOptIn]). */
        const val TELEMETRY_PREFS = "telemetry"
        const val KEY_OPT_IN = "optIn"
    }
}
