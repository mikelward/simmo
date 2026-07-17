package app.simmo.telecom

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import app.simmo.domain.ActiveSim
import app.simmo.domain.ContactNumberIndex
import app.simmo.domain.DecisionSnapshot
import app.simmo.domain.PassToken
import app.simmo.domain.PhoneAccountRef
import app.simmo.store.SimmoStateHolder
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Reads the telephony side of the decision snapshot. All methods do IPC and
 * must run off the main thread and off the decision path — results are cached
 * in [SnapshotAssembler] and refreshed on subscription changes.
 */
class TelephonyReader(private val context: Context) {

    data class SimsAndAccounts(
        val activeSims: List<ActiveSim>,
        /** Domain ref ↔ platform handle, for turning verdicts back into redirects. */
        val handlesByRef: Map<PhoneAccountRef, PhoneAccountHandle>,
    )

    fun hasPhonePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Empty result (not an error) when READ_PHONE_STATE isn't granted — checked
     * up front, and also caught: the permission can be revoked between the
     * check and the reads, and a background refresh must degrade, not crash.
     */
    fun readSimsAndAccounts(): SimsAndAccounts {
        if (!hasPhonePermission()) return SimsAndAccounts(emptyList(), emptyMap())
        return try {
            // System services can be absent on no-radio devices, which the
            // manifest deliberately supports (telephony required=false).
            val telecom = context.getSystemService(TelecomManager::class.java)
                ?: return SimsAndAccounts(emptyList(), emptyMap())
            val telephony = context.getSystemService(TelephonyManager::class.java)
                ?: return SimsAndAccounts(emptyList(), emptyMap())
            val subscriptions = context.getSystemService(SubscriptionManager::class.java)
                ?.activeSubscriptionInfoList.orEmpty()
                .associateBy { it.subscriptionId }

            val sims = mutableListOf<ActiveSim>()
            val handles = mutableMapOf<PhoneAccountRef, PhoneAccountHandle>()
            for (handle in telecom.callCapablePhoneAccounts) {
                // API 30+: the platform's own mapping from phone account to
                // subscription — the reason minSdk is 30.
                val subId = telephony.getSubscriptionId(handle)
                val info = subscriptions[subId] ?: continue
                val ref = handle.toRef()
                handles[ref] = handle
                sims += ActiveSim(
                    subscriptionId = info.subscriptionId,
                    carrierName = info.carrierName?.toString().orEmpty(),
                    displayName = info.displayName?.toString().orEmpty(),
                    phoneAccount = ref,
                    countryIso = info.countryIso.orEmpty(),
                )
            }
            SimsAndAccounts(sims, handles)
        } catch (_: SecurityException) {
            SimsAndAccounts(emptyList(), emptyMap())
        } catch (_: UnsupportedOperationException) {
            SimsAndAccounts(emptyList(), emptyMap())
        }
    }

    /**
     * The region national-format numbers resolve against, absent an override.
     * No-radio devices (tablets, Chromebooks — the manifest supports them so
     * rules can be reviewed there) throw UnsupportedOperationException from
     * these reads; degrade to no region rather than crash the startup refresh.
     */
    fun readNetworkRegion(): String =
        try {
            val telephony = context.getSystemService(TelephonyManager::class.java) ?: return ""
            telephony.networkCountryIso.ifEmpty { telephony.simCountryIso }
        } catch (_: UnsupportedOperationException) {
            ""
        }
}

/** Stable, process-independent identity for a platform phone account handle. */
fun PhoneAccountHandle.toRef(): PhoneAccountRef =
    PhoneAccountRef("${componentName.flattenToString()}/$id")

/**
 * In-memory redirect-loop pass tokens (SPEC "Redirect-loop guard"). Written by
 * the chooser's re-place path (Phase 3); consumed by the decision engine.
 */
class PassTokenStore {
    private val tokens = CopyOnWriteArrayList<PassToken>()

    fun add(token: PassToken) {
        tokens += token
    }

    fun consume(token: PassToken) {
        tokens.remove(token)
    }

    fun currentTokens(nowMillis: Long): List<PassToken> {
        tokens.removeAll { it.expiresAtMillis <= nowMillis }
        return tokens.toList()
    }
}

/**
 * Assembles the [DecisionSnapshot] the redirection service reads. Everything
 * here is an in-memory read: persisted state comes from [SimmoStateHolder]'s
 * eager flow, telephony state from the cached result of the last [refresh]
 * (triggered at startup and on subscription changes, never on the decision
 * path). Returns null until the persisted state's first load lands — the
 * coordinator degrades that to "proceed unmodified".
 */
class SnapshotAssembler(
    private val reader: TelephonyReader,
    private val contactsReader: ContactsReader,
    /** Provider because the holder is created asynchronously at process start. */
    private val stateHolder: () -> SimmoStateHolder?,
    private val tokens: PassTokenStore,
    private val nowMillis: () -> Long,
) {
    private val simsFlow =
        MutableStateFlow(TelephonyReader.SimsAndAccounts(emptyList(), emptyMap()))

    @Volatile
    private var networkRegion: String = ""

    /** Warm contact reverse-lookup for app-to-app hand-off; empty until read. */
    @Volatile
    private var contacts: ContactNumberIndex = ContactNumberIndex.EMPTY

    /**
     * Serializes contact refreshes. At startup two callers race to build the
     * index — the telephony refresh (before the persisted region override has
     * loaded) and the state-load launch (after it publishes the holder). Without
     * this, the earlier, wrong-region read could finish last and overwrite the
     * correct index. The lock makes each refresh read the region and write the
     * result atomically, so whichever runs last reads the freshest region — and
     * the holder is always published before the last acquirer reads it.
     */
    private val contactsMutex = Mutex()

    /**
     * Guards the contact-index publish against a racing [clearContacts]. A
     * refresh captures this before its (slow) read and only publishes if it
     * hasn't changed since — so an in-flight, pre-change read can't re-publish
     * rows a [clearContacts] just dropped. Bumped under [contactsWriteLock],
     * which also makes the capture and the guarded write atomic w.r.t. a clear.
     */
    @Volatile
    private var contactsGeneration = 0
    private val contactsWriteLock = Any()

    /** Blocking IPC — call from a background dispatcher only. */
    fun refresh() {
        simsFlow.value = reader.readSimsAndAccounts()
        networkRegion = reader.readNetworkRegion()
    }

    /**
     * Rebuilds the contact index. Blocking contacts IPC — call from a background
     * dispatcher only, and after [refresh] so the current region is set (it
     * drives normalization of national-format contact numbers). Empty without
     * READ_CONTACTS. Serialized by [contactsMutex] so concurrent refreshes can't
     * overwrite each other with a stale-region result; and the publish is
     * dropped if a [clearContacts] ran during the read (its rows would be stale).
     */
    suspend fun refreshContacts() = contactsMutex.withLock {
        val generation = synchronized(contactsWriteLock) { contactsGeneration }
        val fresh = contactsReader.read(stateHolder()?.current?.defaultRegionOverride ?: networkRegion)
        synchronized(contactsWriteLock) {
            // A clear during the read bumped the generation; its empty index is
            // the safe truth and this read predates the change — drop it and let
            // the next (debounced) refresh republish post-change rows.
            if (generation == contactsGeneration) contacts = fresh
        }
    }

    /**
     * Immediately drops the warm index (no IPC — safe to call from any thread).
     * A decision then sees no contacts, so app-to-app hand-off rules skip to
     * "proceed unmodified" rather than routing to a row that may have just been
     * deleted. Used to make the window safe before a debounced [refreshContacts]
     * repopulates it, and to clear on permission revocation. Bumping the
     * generation invalidates any read already in flight so it can't republish
     * the pre-clear rows over this empty index.
     */
    fun clearContacts() {
        synchronized(contactsWriteLock) {
            contactsGeneration++
            contacts = ContactNumberIndex.EMPTY
        }
    }

    fun activeSims() = simsFlow.value.activeSims

    /** For the UI: disabled-SIM greying must track live telephony changes. */
    fun simsAndAccounts(): StateFlow<TelephonyReader.SimsAndAccounts> = simsFlow.asStateFlow()

    fun handleFor(ref: PhoneAccountRef): PhoneAccountHandle? = simsFlow.value.handlesByRef[ref]

    fun refFor(handle: PhoneAccountHandle?): PhoneAccountRef? = handle?.toRef()

    fun current(): DecisionSnapshot? {
        val state = stateHolder()?.current ?: return null
        return DecisionSnapshot(
            rules = state.rules,
            activeSims = simsFlow.value.activeSims,
            defaultRegion = state.defaultRegionOverride ?: networkRegion,
            passTokens = tokens.currentTokens(nowMillis()),
            // Phone-account / dial-intent hand-off target discovery lands later
            // (TODO.md Phase 5); the contact index backs app-to-app hand-off.
            handOffAccounts = emptySet(),
            handOffApps = emptySet(),
            contacts = contacts,
        )
    }
}
