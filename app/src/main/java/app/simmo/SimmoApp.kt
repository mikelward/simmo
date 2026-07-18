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
import android.util.Log
import androidx.core.content.ContextCompat
import app.simmo.analytics.TelemetryGate
import app.simmo.domain.ActiveSim
import app.simmo.domain.CountryVerdict
import app.simmo.domain.DecisionEngine
import app.simmo.domain.arrivalKey
import app.simmo.domain.evaluateDataRules
import app.simmo.domain.isMarkStale
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-wide wiring. Everything the redirection service will read is
 * assembled here, warmed off the main thread at process start (AGENTS.md
 * "Fast decision path"): the persisted state loads eagerly, libphonenumber
 * metadata is pre-loaded, and telephony state is cached and refreshed on
 * subscription changes. Nothing in [coordinator]'s read path blocks.
 */
class SimmoApp : Application() {

    /**
     * Background maintenance (state load, telephony refreshes, contact index,
     * registry capture). The handler keeps a failed refresh from killing the
     * process: this is the process that answers Telecom, and the honest
     * degradation for a background read blowing up mid-race (telephony going
     * away, permission revoked between check and read) is a stale snapshot —
     * calls proceed unmodified — never a crash. It also keeps a refresh
     * outliving a Robolectric test from failing the *next* test as an
     * uncaught exception when it hits the torn-down sandbox.
     */
    val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, e ->
                Log.e("SimmoApp", "Background maintenance failed", e)
            },
    )
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

    /**
     * Serializes roaming-watch checks (same shape as the assembler's
     * contacts mutex): overlapping startup/listener/broadcast checks could
     * otherwise interleave so an older check claims after a newer one and
     * posts a stale warning. Reads happen inside the lock (see
     * [checkDataWatch]), so every serialized check evaluates fresh state.
     */
    private val dataWatchMutex = Mutex()

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

    /**
     * The best-known choice right now from memory alone — never disk, so UI
     * construction can call it on the first frame's critical path: the
     * latest in-process tap (which the off-main marker seeding also feeds),
     * else the loaded state when it's already in memory (a restore carries
     * no marker — only `datastore/` is backed up), else the default (on).
     * For seeding initial UI state; [analyticsOptIns] is the live stream,
     * which folds in the durable marker itself.
     */
    fun currentAnalyticsOptIn(): Boolean =
        optInTaps.value
            ?: stateHolder()?.current?.analyticsOptIn
            ?: true

    /**
     * The effective "Make Simmo better" choice: the latest in-process tap (or
     * the durable marker it left behind) masking the persisted state. The
     * telemetry gate and the Settings switch both read this stream, so the
     * switch always shows what telemetry is actually doing — including in the
     * crash-recovery window where the marker says off but the main state
     * still says on.
     */
    fun analyticsOptIns(): Flow<Boolean> =
        TelemetryGate.effectiveOptIns(
            // Only the persisted branch waits for the holder; taps subscribe
            // and emit at once, so a flip in the first instants of the
            // process is visible before the holder is even published. The
            // branch consults the durable marker before it may emit — on the
            // collector's worker via flowOn, never blocking process start,
            // which sits on the cold-call path (AGENTS "Fast decision
            // path") — so a stale stored value can't slip out ahead of a
            // crash-recovered choice.
            persisted = flow {
                seedTapFromMarker()
                emitAll(
                    stateHolderFlow.filterNotNull().first().state
                        .filterNotNull()
                        .map { it.analyticsOptIn },
                )
            }.flowOn(Dispatchers.Default),
            taps = optInTaps,
        )

    /**
     * Seeds the durable marker as a synthetic tap — the marker is the
     * freshest record of the choice (every tap commits it before the slower
     * main-state write), so a crash that loses that write can't misapply
     * the last choice in either direction. Idempotent, and a real tap
     * always wins the seed.
     */
    private fun seedTapFromMarker() {
        val marker = getSharedPreferences(TELEMETRY_PREFS, MODE_PRIVATE)
        if (marker.contains(KEY_OPT_IN)) {
            optInTaps.compareAndSet(null, marker.getBoolean(KEY_OPT_IN, true))
        }
    }

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
            // completes the snapshot is null and calls proceed unmodified —
            // which is why this one-shot retries rather than trusting the
            // scope handler: logged-and-abandoned here would mean no rules
            // for the rest of the process lifetime.
            val holder = retryUntilDone("State load") {
                val installId = InstallMarker.get(this@SimmoApp)
                SimmoStateHolder(simmoStateStore, appScope, installId)
            }
            stateHolderFlow.value = holder
            maintenanceStep("Registry capture") { recordSims() }
            // The watch runs from both ends of the startup race: this launch
            // and the telephony refresh (reads cached, state maybe still
            // loading). Publishing the holder only *starts* the eager read,
            // so wait for the first real emission first — a check against a
            // null state is no check at all, and both ends returning early
            // would drop the very arrival a manifest wake exists to catch
            // (Codex on PR #55, twice). Whichever end finishes second has
            // both inputs; the atomic claim keeps them from double-posting.
            holder.state.filterNotNull().first()
            maintenanceStep("Roaming watch") { checkDataWatch() }
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
            // Retried for the same reason as the state load: without warm
            // metadata the coordinator never sees a snapshot.
            retryUntilDone("Parser warm-up") { detector.warmUp() }
            metadataWarm = true
        }
        appScope.launch {
            // Seed the marker off the main thread — cold start for a call
            // must never wait on disk (AGENTS "Fast decision path"). Stream
            // correctness doesn't depend on this early seed: every collector's
            // persisted branch consults the marker itself before emitting.
            // This one feeds the snap readers (currentAnalyticsOptIn) and the
            // opt-out cleanup below.
            seedTapFromMarker()
            // Crash reporting and analytics follow the effective choice;
            // until the state loads, the manifest keeps collection off.
            // No-op in builds without a Firebase config.
            val gate = telemetry ?: return@launch
            // A marked opt-out runs its cleanup now, not after the state loads.
            if (optInTaps.value == false) gate.set(false)
            gate.follow(analyticsOptIns())
        }
        appScope.launch {
            // One sequential collector persists the opt-in taps into the main
            // state: parallel per-tap jobs could finish out of tap order and
            // leave an earlier choice on disk. The StateFlow conflates to the
            // newest value — the only one that matters. (The durable marker
            // is committed in the tap handler itself; see setAnalyticsOptIn.)
            optInTaps.filterNotNull().collect { enabled ->
                // A durable user choice, so a transient write failure retries
                // instead of killing this collector — which would silently
                // stop persisting every later tap too (Codex on PR #52). The
                // conflated StateFlow keeps a retry from delaying anything
                // but itself; the durable marker already holds the truth.
                retryUntilDone("Persist analytics choice") {
                    stateHolderFlow.filterNotNull().first().setAnalyticsOptIn(enabled)
                }
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
        appScope.launch { refreshTelephonyNow() }
    }

    /**
     * The suspend body of [refreshTelephony], for callers that must outlast
     * it — the wake-up receiver holds its broadcast alive (`goAsync`) until
     * this returns, because after `onReceive` returns nothing else keeps a
     * receiver-started process running long enough to read telephony, claim
     * the arrival mark, and post the warning.
     */
    suspend fun refreshTelephonyNow() {
        // Under the same mutex as the watch check: each refresh's read and
        // publish become atomic, so an older blocking telephony read can't
        // overwrite a newer cache for the next check to evaluate as if
        // current (Codex on PR #55) — whichever refresh runs last read last.
        dataWatchMutex.withLock { assembler.refresh() }
        maintenanceStep("Registry capture") { recordSims() }
        maintenanceStep("Roaming watch") { checkDataWatch() }
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
        // The marker is committed durably before this returns — a couple of
        // milliseconds of I/O on a deliberate tap is the right trade for a
        // privacy control: if the process dies right now, the next launch
        // must still see this choice. Taps arrive on one thread, so these
        // commits also land in tap order.
        getSharedPreferences(TELEMETRY_PREFS, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_OPT_IN, enabled)
            .commit()
        // Publishing the tap does the rest: the gate's collector masks staler
        // persisted emissions with it, and the persister coroutine (onCreate)
        // writes the main state sequentially. Apply to the SDKs now as well —
        // a crash or upload while that write is in flight must already honor
        // the tap.
        optInTaps.value = enabled
        telemetry?.set(enabled)
    }

    /**
     * Runs one background maintenance step, isolating its failure: every step
     * self-heals on a later trigger, and one step's transient failure must
     * not suppress the steps after it — in particular a registry-capture
     * write failing must not swallow the arrival check that follows it, on
     * either end of the startup race (Codex on PR #55). Cancellation still
     * propagates.
     */
    private suspend fun maintenanceStep(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SimmoApp", "$what failed", e)
        }
    }

    private suspend fun recordSims() {
        // The held-call offer routes calls, so it only looks at call-capable
        // SIMs; the registry records every subscription — a data-only travel
        // eSIM must be remembered so the roaming watch's no-data nudge can
        // name it after it's disabled (Codex on PR #52). Data-only rows never
        // start a rule prompt, so the new-SIM nudges below stay call-scoped.
        val callCapableActive = assembler.activeSims()
        maybeOfferHeldCall(callCapableActive)
        val active = assembler.allActiveSims()
        val holder = stateHolderFlow.value ?: return
        if (active.isEmpty()) return
        // The capture reports whether it populated a previously empty
        // registry (fresh install): that first batch registers every current
        // SIM at once, and nudging about SIMs the user has had all along
        // would just be noise — marked notified without posting.
        val capture =
            holder.recordSeenSims(active, System.currentTimeMillis(), assembler.callCapableIds())
        if (capture.firstCapture) {
            // The whole batch, not just the call-capable pending rows: a SIM
            // Telecom's account list missed this instant records as data-only
            // and gets promoted on a later refresh — by then firstCapture is
            // false, and only this mark keeps the promotion from posting a
            // notification about a SIM present at install (Codex on PR #52).
            // The promotion still restores the in-app prompt card, exactly
            // what a normal fresh install shows.
            holder.markNewSimsNotified(capture.registry.map { it.ref() })
            return
        }
        // Gated on the call-capable snapshot, not the capture's union: the
        // notification opens the rules list, whose prompt card derives from
        // that same snapshot — posting during a degraded Telecom read would
        // land the tap on a screen with no matching prompt (Codex on PR #52).
        val pending = capture.registry.pendingNewSimNotifications(callCapableActive)
        if (pending.isEmpty() || !notifications.canPost()) {
            // No permission: leave unmarked so a later grant can still nudge
            // about a SIM whose prompt is genuinely unanswered.
            return
        }
        pending.forEach { sim ->
            notifications.postNewSim(sim.displayName.ifBlank { sim.carrierName })
        }
        holder.markNewSimsNotified(pending.map { it.ref() })
    }

    /**
     * The roaming watch (SPEC "Data rules"): evaluates the data rules against
     * the freshly cached data state — on every telephony refresh, so it rides
     * the same wake-ups the snapshot does and never touches the decision
     * path. The persisted once-per-arrival mark keeps constantly-firing
     * refreshes from re-nagging, and is only claimed when a warning can
     * actually surface: no in-app fallback exists until the triage card
     * lands, so consuming an arrival that reached nobody would lose it even
     * after a later permission grant (Codex on PR #55) — same stance as the
     * new-SIM nudge. Revisit when the triage card ships: displaying the card
     * should claim the arrival too.
     */
    private suspend fun checkDataWatch() = dataWatchMutex.withLock {
        // All reads happen INSIDE the lock: serialization alone would still
        // let an older-scheduled check evaluate its stale capture after a
        // newer one claimed — this way every check evaluates the freshest
        // state, so a late resume can't repost an arrival that's over or
        // seesaw the mark (Codex on PR #55).
        val holder = stateHolderFlow.value ?: return@withLock
        val state = holder.current ?: return@withLock
        val snapshot = assembler.currentDataSnapshot() ?: return@withLock
        val verdict = evaluateDataRules(state.dataRules, snapshot)
        // Housekeeping before ANY early return: a mark whose arrival is over
        // — the user moved country or the data SIM changed — clears, so a
        // later *return* to the marked place warns once again (SPEC: cleared
        // when the country changes). It must run on the unpostable path too:
        // a stale mark held while notifications were blocked would otherwise
        // suppress the warning once they're re-enabled back in the marked
        // country (Codex on PR #55). A mark for the same place keeps: a
        // flapping roaming flag must not re-nag mid-trip. Compare-and-swap
        // on the observed mark, so a concurrent refresh's fresh claim is
        // never deleted.
        val observed = state.dataWatchMark
        if (observed != null && isMarkStale(observed, snapshot)) {
            // The winner also takes down the posted warning: the arrival it
            // describes is over, and a present-tense "Using data roaming"
            // lingering after the trip would be false (Codex on PR #55). A
            // lost CAS means a fresh claim already posted its replacement —
            // cancelling then would tear the new warning down. A mark kept
            // for the same place keeps its notification too: dismissing it
            // on a flapping roaming flag would silently eat the one warning
            // this arrival gets. If a new arrival follows below, its post
            // replaces this cancel under the same tag.
            if (holder.clearDataWatchMark(observed)) {
                notifications.cancelDataWatch()
            }
        }
        val key = verdict.arrivalKey() ?: return
        // Unpostable (permission denied, channel blocked): leave the mark
        // unclaimed so a later grant still warns about this arrival. The
        // tiny window where a revocation lands between this check and the
        // post can still lose one warning — bounded, and the next arrival
        // recovers.
        if (!notifications.canPostDataWatch()) return
        // Atomic claim, not read-then-write: overlapping refreshes (startup,
        // the first subscription callback, a wake broadcast) may all see the
        // same stale mark; only the transaction that changed it posts. Claim
        // before posting: a crash in between costs one notification, never a
        // re-nag.
        if (!holder.claimDataWatchMark(key)) return
        notifications.postDataWatch(verdict)
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

/**
 * Retries a critical one-shot initialization until it succeeds. The app
 * scope's exception handler keeps a failed background job from crashing the
 * process, but for the one-shots that gate the snapshot a transient failure
 * would otherwise leave the app degraded for the whole process lifetime — no
 * rules, every call proceeding unmodified (Codex on PR #52); the repeatable
 * maintenance jobs (telephony refreshes, registry capture) self-heal on their
 * next trigger and don't need this. Exponential backoff, capped at a minute;
 * cancellation propagates.
 */
internal suspend fun <T> retryUntilDone(what: String, block: suspend () -> T): T {
    var backoffMillis = 1_000L
    while (true) {
        try {
            return block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SimmoApp", "$what failed; retrying in ${backoffMillis}ms", e)
            delay(backoffMillis)
            backoffMillis = (backoffMillis * 2).coerceAtMost(60_000L)
        }
    }
}
