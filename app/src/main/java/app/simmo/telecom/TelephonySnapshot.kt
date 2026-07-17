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
import app.simmo.domain.DecisionSnapshot
import app.simmo.domain.PassToken
import app.simmo.domain.PhoneAccountRef
import app.simmo.store.SimmoStateHolder
import java.util.concurrent.CopyOnWriteArrayList

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
            val telecom = context.getSystemService(TelecomManager::class.java)
            val telephony = context.getSystemService(TelephonyManager::class.java)
            val subscriptions = context.getSystemService(SubscriptionManager::class.java)
                .activeSubscriptionInfoList.orEmpty()
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
        }
    }

    /** The region national-format numbers resolve against, absent an override. */
    fun readNetworkRegion(): String {
        val telephony = context.getSystemService(TelephonyManager::class.java)
        return telephony.networkCountryIso.ifEmpty { telephony.simCountryIso }
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
    /** Provider because the holder is created asynchronously at process start. */
    private val stateHolder: () -> SimmoStateHolder?,
    private val tokens: PassTokenStore,
    private val nowMillis: () -> Long,
) {
    @Volatile
    private var sims: TelephonyReader.SimsAndAccounts =
        TelephonyReader.SimsAndAccounts(emptyList(), emptyMap())

    @Volatile
    private var networkRegion: String = ""

    /** Blocking IPC — call from a background dispatcher only. */
    fun refresh() {
        sims = reader.readSimsAndAccounts()
        networkRegion = reader.readNetworkRegion()
    }

    fun activeSims() = sims.activeSims

    fun handleFor(ref: PhoneAccountRef): PhoneAccountHandle? = sims.handlesByRef[ref]

    fun refFor(handle: PhoneAccountHandle?): PhoneAccountRef? = handle?.toRef()

    fun current(): DecisionSnapshot? {
        val state = stateHolder()?.current ?: return null
        return DecisionSnapshot(
            rules = state.rules,
            activeSims = sims.activeSims,
            defaultRegion = state.defaultRegionOverride ?: networkRegion,
            passTokens = tokens.currentTokens(nowMillis()),
            // Hand-off target discovery lands with Phase 5 (TODO.md).
            handOffAccounts = emptySet(),
            handOffApps = emptySet(),
        )
    }
}
