package app.simmo.telecom

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import app.simmo.domain.ActiveSim
import app.simmo.domain.CallingAccount
import app.simmo.domain.ContactNumberIndex
import app.simmo.domain.DecisionSnapshot
import app.simmo.domain.PassToken
import app.simmo.domain.PhoneAccountRef
import app.simmo.store.SimmoState
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
        val activeSims: List<ActiveSim> = emptyList(),
        /** Call-capable non-SIM accounts (SIP providers, VoIP apps), with labels. */
        val callingAccounts: List<CallingAccount> = emptyList(),
        /** Domain ref ↔ platform handle, for turning verdicts back into redirects. */
        val handlesByRef: Map<PhoneAccountRef, PhoneAccountHandle> = emptyMap(),
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
        if (!hasPhonePermission()) return SimsAndAccounts()
        return try {
            // Only Telecom is required. The telephony services can be absent on
            // no-radio devices, which the manifest deliberately supports
            // (telephony required=false) — there they just mean "no SIM
            // subscriptions", and a SIP/VoIP calling account enabled on such a
            // device must still be enumerated (Codex on PR #39).
            val telecom = context.getSystemService(TelecomManager::class.java)
                ?: return SimsAndAccounts()
            val telephony = context.getSystemService(TelephonyManager::class.java)
            val subscriptionManager = context.getSystemService(SubscriptionManager::class.java)
            // The manager can exist while the read still throws
            // UnsupportedOperationException (no FEATURE_TELEPHONY_SUBSCRIPTION,
            // per its docs) — that too just means "no SIM subscriptions", and
            // the Telecom accounts below must still be enumerated. A
            // SecurityException keeps propagating to the outer catch.
            val subscriptions = try {
                subscriptionManager?.activeSubscriptionInfoList.orEmpty()
            } catch (_: UnsupportedOperationException) {
                emptyList()
            }.associateBy { it.subscriptionId }

            val sims = mutableListOf<ActiveSim>()
            val accounts = mutableListOf<CallingAccount>()
            val handles = mutableMapOf<PhoneAccountRef, PhoneAccountHandle>()
            for (handle in telecom.callCapablePhoneAccounts) {
                // API 30+: the platform's own mapping from phone account to
                // subscription — the reason minSdk is 30. Absent or
                // feature-less telephony can't map any handle, and on such a
                // device no handle is a SIM's — invalid is the true answer.
                // (SecurityException still propagates to the outer catch: a
                // revoked permission must empty the snapshot, not relabel
                // SIM accounts as generic calling accounts.)
                val subId = try {
                    telephony?.getSubscriptionId(handle)
                        ?: SubscriptionManager.INVALID_SUBSCRIPTION_ID
                } catch (_: UnsupportedOperationException) {
                    SubscriptionManager.INVALID_SUBSCRIPTION_ID
                }
                val info = subscriptions[subId]
                val ref = handle.toRef()
                when {
                    info != null -> {
                        handles[ref] = handle
                        sims += ActiveSim(
                            subscriptionId = info.subscriptionId,
                            carrierName = info.carrierName?.toString().orEmpty(),
                            displayName = info.displayName?.toString().orEmpty(),
                            phoneAccount = ref,
                            countryIso = info.countryIso.orEmpty(),
                            // info came from subscriptionManager, so it is
                            // non-null here; the ?.let just avoids !!.
                            phoneNumber = subscriptionManager
                                ?.let { readPhoneNumber(it, info) }.orEmpty(),
                        )
                    }

                    subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID -> {
                        // Not a SIM: a SIP provider or another calling app whose
                        // enabled call-capable account Telecom offers as a call
                        // target (SPEC "Hand-off to another app"). Redirects use
                        // the same handle mechanism as a SIM account — and keep
                        // the original tel: handle, so only offer accounts that
                        // can place tel: calls; a sip:-only account would fail
                        // the redirected call (Codex on PR #39). An unreadable
                        // account (READ_PHONE_NUMBERS denied) is offered best
                        // effort: call-provider accounts near-universally take
                        // tel:, and withholding all of them would gut the
                        // feature for that grant state.
                        val account = runCatching { telecom.getPhoneAccount(handle) }.getOrNull()
                        if (account == null || account.supportsUriScheme(PhoneAccount.SCHEME_TEL)) {
                            handles[ref] = handle
                            accounts += CallingAccount(ref, callingAccountLabel(account, handle))
                        }
                    }

                    // A SIM-bound account whose subscription isn't readable
                    // right now: offering it as a generic calling account would
                    // mislabel a SIM, so it stays dropped (as before).
                }
            }
            SimsAndAccounts(sims, accounts, handles)
        } catch (_: SecurityException) {
            SimsAndAccounts()
        } catch (_: UnsupportedOperationException) {
            SimsAndAccounts()
        }
    }

    /**
     * The SIM's own line number, or "" when unknown. Needs READ_PHONE_NUMBERS
     * (split from READ_PHONE_STATE in API 30) — caught per-SIM so a missing
     * number grant degrades to a number-less row, never to losing the SIM list.
     * Many profiles simply carry no number; "" is a normal answer.
     */
    private fun readPhoneNumber(subscriptions: SubscriptionManager, info: SubscriptionInfo): String =
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                subscriptions.getPhoneNumber(info.subscriptionId)
            } else {
                @Suppress("DEPRECATION")
                info.number.orEmpty()
            }
        } catch (_: SecurityException) {
            ""
        } catch (_: IllegalStateException) {
            ""
        }

    /**
     * A non-SIM account's user-facing name. The account's own label is best
     * (one app can register several accounts — "SIP work" vs "SIP personal") but
     * `getPhoneAccount` needs READ_PHONE_NUMBERS on targetSdk 31+, which is
     * requested with READ_PHONE_STATE yet can be individually denied — so
     * [account] may be null; degrade to the registering app's name (visible via
     * the ConnectionService `<queries>` entry), then to its package name. Never
     * empty, so the editor and chooser always have something to show.
     */
    private fun callingAccountLabel(account: PhoneAccount?, handle: PhoneAccountHandle): String {
        account?.label?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        val packageName = handle.componentName.packageName
        runCatching {
            context.packageManager
                .getApplicationLabel(context.packageManager.getApplicationInfo(packageName, 0))
                .toString()
        }
            .getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
        return packageName
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
    private val simsFlow = MutableStateFlow(TelephonyReader.SimsAndAccounts())

    @Volatile
    private var networkRegion: String = ""

    /**
     * Warm contact reverse-lookup for app-to-app hand-off; empty until read.
     * A [StateFlow] so the UI can also derive the country picker's "Suggested"
     * bucket from it; the decision path still reads the current value in memory.
     */
    private val contactsFlow = MutableStateFlow(ContactNumberIndex.EMPTY)

    /**
     * Package names of reachable dial-intent hand-off apps (Google Voice, Teams)
     * — installed and able to receive their launch intent. The decision path
     * reads it to skip a hand-off rule whose target isn't reachable; populated
     * off-path from [installedDialHandoffApps].
     */
    @Volatile
    private var handOffApps: Set<String> = emptySet()

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
            if (generation == contactsGeneration) contactsFlow.value = fresh
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
            contactsFlow.value = ContactNumberIndex.EMPTY
        }
    }

    /** Caches installed dial-intent hand-off targets; called off the decision path. */
    fun setHandOffApps(packages: Set<String>) {
        handOffApps = packages
    }

    fun activeSims() = simsFlow.value.activeSims

    /** For the UI: disabled-SIM greying must track live telephony changes. */
    fun simsAndAccounts(): StateFlow<TelephonyReader.SimsAndAccounts> = simsFlow.asStateFlow()

    /** For the UI: the picker's "Suggested" countries track contact-index refreshes. */
    fun contacts(): StateFlow<ContactNumberIndex> = contactsFlow.asStateFlow()

    fun handleFor(ref: PhoneAccountRef): PhoneAccountHandle? = simsFlow.value.handlesByRef[ref]

    fun refFor(handle: PhoneAccountHandle?): PhoneAccountRef? = handle?.toRef()

    fun current(): DecisionSnapshot? {
        val state = stateHolder()?.current ?: return null
        return DecisionSnapshot(
            rules = state.rules,
            activeSims = simsFlow.value.activeSims,
            defaultRegion = state.defaultRegionOverride ?: networkRegion,
            passTokens = tokens.currentTokens(nowMillis()),
            // Custom-group membership, resolved on the decision path from this
            // in-memory map (built from the persisted state, never from I/O).
            customGroups = state.customGroups.associate { group ->
                group.id to group.regionCodes.map { it.uppercase() }
            },
            // Non-SIM calling accounts (SIP providers, VoIP apps) from the same
            // cached telephony read as the SIMs — in memory, refreshed off-path.
            // Labels ride along for the "Calling using" toast and the delay
            // countdown.
            handOffAccounts = simsFlow.value.callingAccounts.associate { it.ref to it.label },
            handOffApps = handOffApps,
            contacts = contactsFlow.value,
            announceCalls = state.showCallToast,
            // Clamped here so restored or hand-edited state can't make the
            // countdown absurd; the store's own setter clamps writes too.
            callDelaySeconds = state.callDelaySeconds.coerceIn(0, SimmoState.MAX_CALL_DELAY_SECONDS),
            correctContactNumbers = state.correctContactNumbers,
            guardOverseasHandsFree = state.guardOverseasHandsFree,
            guardDisabledSimHandsFree = state.guardDisabledSimHandsFree,
        )
    }
}
