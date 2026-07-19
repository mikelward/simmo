package app.simmo.ui

import android.app.Application
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.simmo.SimmoApp
import app.simmo.domain.ActiveSim
import app.simmo.domain.ContactCallApp
import app.simmo.domain.CustomGroup
import app.simmo.domain.DialHandoffApp
import app.simmo.domain.withGroupSaved
import app.simmo.domain.withGroupMarkedForRemoval
import app.simmo.domain.withGroupRemovalUndone
import app.simmo.telecom.TelephonyReader
import app.simmo.telecom.buildDataSnapshot
import app.simmo.telecom.installedDialHandoffApps
import app.simmo.domain.DataExpectation
import app.simmo.domain.DataRule
import app.simmo.domain.DataRuleBook
import app.simmo.domain.DataSimScope
import app.simmo.domain.DataTriage
import app.simmo.domain.roamingOkRule
import app.simmo.domain.triageFor
import app.simmo.domain.PhoneAccountRef
import app.simmo.domain.RegisteredSim
import app.simmo.domain.CallingRule
import app.simmo.domain.RuleAction
import app.simmo.domain.CallingRuleBook
import app.simmo.domain.SimRef
import app.simmo.domain.CountryGroups
import app.simmo.domain.SimResolution
import app.simmo.domain.groupIds
import app.simmo.domain.newRuleId
import app.simmo.domain.newSimRuleInsertionIndex
import app.simmo.domain.regionCodes
import app.simmo.domain.resolveSim
import app.simmo.store.SimmoState
import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Why a rule cannot act right now — each points at a different recovery. */
enum class RulePause {
    /** The SIM is installed but disabled; re-enable it in SIM settings. */
    SIM_DISABLED,

    /** The stored ref can't re-bind unambiguously; the rule needs re-linking. */
    SIM_AMBIGUOUS,

    /**
     * The rule's calling account (SIP provider, VoIP app) is no longer
     * registered/enabled; re-enable it in the system's calling-accounts
     * settings or reinstall the app.
     */
    ACCOUNT_UNAVAILABLE,
}

/** One row of the rules list, ready to render (SPEC "Calling rules"). */
data class RuleRowUi(
    /**
     * e.g. "+61 Australia" — or a comma-joined list for a multi-country rule;
     * null means the rule matches any destination.
     */
    val matcherCountryLabel: String?,
    val action: ActionUi,
    /** Non-null when the rule is auto-skipped (disabled SIM, ambiguous) — shown greyed. */
    val pause: RulePause? = null,
    /** False when the user turned the rule off; shown greyed and skipped. */
    val enabled: Boolean = true,
    /**
     * The rule's stable id, so a row action (edit, duplicate, enable, delete)
     * addresses the rule the user tapped even if a concurrent restore or write
     * shifts the list — the row menu resolves by this, not by list position.
     */
    val id: String = "",
    /** Soft-deleted awaiting purge: the row shows struck-through with an Undo affordance. */
    val pendingRemoval: Boolean = false,
)

sealed interface ActionUi {
    data class UseSim(val simName: String) : ActionUi
    data object MatchingCountrySim : ActionUi
    data class HandOffApp(val target: String) : ActionUi
    data object Ask : ActionUi
    data object SystemDefault : ActionUi
}

/** Which list the rules home shows (SPEC "Product behavior" terminology). */
enum class RulesTab { CALLING, DATA }

/** One row of the data rules list, ready to render (SPEC "Data rules"). */
data class DataRuleRowUi(
    /**
     * e.g. "EU/EEA" or "Australia" — where the user is, so no dialing codes;
     * null means the rule applies anywhere.
     */
    val matcherCountryLabel: String?,
    val expectation: DataExpectationUi,
    /** Non-null when the rule is auto-skipped (disabled SIM, ambiguous) — shown greyed. */
    val pause: RulePause? = null,
    /** False when the user turned the rule off; shown greyed and skipped. */
    val enabled: Boolean = true,
    /** The rule's stable id; see [RuleRowUi.id]. */
    val id: String = "",
    /** Soft-deleted awaiting purge; see [RuleRowUi.pendingRemoval]. */
    val pendingRemoval: Boolean = false,
)

sealed interface DataExpectationUi {
    data class UseSimForData(val simName: String) : DataExpectationUi
    data object RoamingOkAnySim : DataExpectationUi
    data object RoamingOkHomedInMatched : DataExpectationUi

    /** Specific-SIM scope; [simNames] is the comma-joined display list. */
    data class RoamingOkSims(val simNames: String) : DataExpectationUi
    data object AlwaysWarn : DataExpectationUi
}

/** Which shape of situation the triage card shows (SPEC "Data rules" → Triage). */
enum class DataTriageKind { ROAMING, NO_DATA, WRONG_SIM }

/** A shipped or custom group offered as a one-tap "widen This is OK to…" chip. */
data class TriageGroupUi(val id: String, val label: String)

/**
 * The triage card atop the Data tab: the live data situation, ready to render.
 * [country] and [dataSimRef] are the non-display payload the "This is OK"
 * action reads back to build the Roaming OK rule.
 */
data class DataTriageUi(
    val kind: DataTriageKind,
    val dataSimName: String,
    val countryLabel: String,
    /**
     * The other SIM named in the body: the local SIM to prefer (roaming and
     * no-data — active or a disabled local profile alike; "Change SIM" is the
     * one action for both) or the wanted SIM (wrong-SIM).
     */
    val otherSimName: String?,
    val country: String,
    val dataSimRef: SimRef,
    /** Widen-to-group chips; empty except for the roaming situation. */
    val widenGroups: List<TriageGroupUi> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class RulesViewModel(
    application: Application,
    private val savedState: SavedStateHandle,
) : AndroidViewModel(application) {
    private val app = application as SimmoApp
    private val editorJson = Json { ignoreUnknownKeys = true }

    /**
     * Purge every soft-deleted rule, data rule, and custom group — the moment a
     * delete becomes final. Called when the rules screen stops (SPEC "Calling
     * rules": deletes are undoable until you leave). A crash doesn't run
     * `onStop`, so a soft-deleted item survives it and stays undoable. One
     * atomic write so a leave can't purge some lists but not others.
     *
     * Runs on the application scope, not [viewModelScope]: leaving by Back
     * finishes the activity, and `onDestroy` cancels [viewModelScope] moments
     * after `onStop` — a purge still suspended in `DataStore.updateData` would
     * be canceled with it, leaving the items persisted and wrongly undoable on
     * the next launch (Codex on PR #61). The app scope outlives the activity.
     */
    fun purgePendingRemovals() {
        app.appScope.launch {
            app.stateHolders().filterNotNull().first().purgePendingRemovals()
        }
    }

    /**
     * Rebuilt off the main thread whenever the stored rules OR the live SIM
     * snapshot changes — disabling a SIM must grey its rules immediately, not
     * on the next persisted-state write. Label building touches libphonenumber
     * metadata, which never belongs in composition.
     */
    val rows: StateFlow<List<RuleRowUi>> =
        app.stateHolders()
            .flatMapLatest { holder -> holder?.state ?: flowOf(null) }
            .combine(app.assembler.simsAndAccounts()) { state, sims ->
                buildRows(state, sims)
            }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * True when any list — calling rules, data rules, or custom groups — holds a
     * soft-deleted entry awaiting purge. The header's Apply button (which commits
     * all three at once) shows across every screen while this is true, so a
     * pending group delete surfaces Apply on the rules screen too, not just where
     * the struck-through row happens to be visible.
     */
    val hasPendingRemovals: StateFlow<Boolean> =
        app.stateHolders()
            .flatMapLatest { holder -> holder?.state ?: flowOf(null) }
            .map { state ->
                state != null && (
                    state.rules.rules.any { it.pendingRemoval } ||
                        state.dataRules.rules.any { it.pendingRemoval } ||
                        state.customGroups.any { it.pendingRemoval }
                    )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private fun buildRows(state: SimmoState?, sims: TelephonyReader.SimsAndAccounts): List<RuleRowUi> {
        // Built-in labels are static; a custom group's label is its user-typed
        // name from the state. An id neither knows still shows (as its raw id).
        val labels = builtInGroupLabels + state?.customGroups.orEmpty().associate { it.id to it.name }
        val availableAccounts = sims.callingAccounts.mapTo(HashSet()) { it.ref }
        return state?.rules?.rules.orEmpty().map { rule ->
            rule.toRow(sims.activeSims, groupLabel = { id -> labels[id] ?: id }, availableAccounts)
                .copy(id = rule.id, pendingRemoval = rule.pendingRemoval)
        }
    }

    /**
     * The data list's rows; rebuilt like [rows] whenever rules or SIMs
     * change. Resolution runs against ALL active subscriptions — a rule
     * targeting an active data-only eSIM must not grey as "SIM disabled"
     * when the watch can act on it (Codex on PR #57), so the data-only rows
     * the call-capable list can't see are merged in, as [dataSimOptions]
     * does.
     */
    val dataRows: StateFlow<List<DataRuleRowUi>> =
        app.stateHolders()
            .flatMapLatest { holder -> holder?.state ?: flowOf(null) }
            .combine(app.assembler.simsAndAccounts()) { state, sims -> state to sims }
            .combine(app.assembler.dataStates()) { (state, sims), dataState ->
                buildDataRows(state, allActiveSims(sims, dataState))
            }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private fun buildDataRows(state: SimmoState?, activeSims: List<ActiveSim>): List<DataRuleRowUi> {
        val labels = builtInGroupLabels + state?.customGroups.orEmpty().associate { it.id to it.name }
        return state?.dataRules?.rules.orEmpty().map { rule ->
            rule.toRow(activeSims, groupLabel = { id -> labels[id] ?: id })
                .copy(id = rule.id, pendingRemoval = rule.pendingRemoval)
        }
    }

    /** Call-capable actives plus the data-only subscriptions they can't see. */
    private fun allActiveSims(
        sims: TelephonyReader.SimsAndAccounts,
        dataState: TelephonyReader.DataState,
    ): List<ActiveSim> {
        val callIds = sims.activeSims.mapTo(HashSet()) { it.subscriptionId }
        return sims.activeSims + dataState.subscriptions.filterNot { it.subscriptionId in callIds }
    }

    /**
     * The triage card atop the Data tab (SPEC "Data rules" → Triage): the live
     * data situation while one exists, recomputed off the main thread from the
     * data rules and the current data snapshot. Unlike the notification it is
     * NOT gated by the once-per-arrival mark, so it shows the current state
     * every time the screen is open; null (no card) when a rule already covers
     * the situation, the data SIM is home, or the country is unknown. Adding a
     * "This is OK" rule makes the verdict Silent, so the card clears itself.
     */
    val triage: StateFlow<DataTriageUi?> =
        app.stateHolders()
            .flatMapLatest { holder -> holder?.state ?: flowOf(null) }
            .combine(app.assembler.dataStates()) { state, dataState ->
                state?.let { buildTriage(it, dataState) }
            }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun buildTriage(state: SimmoState, dataState: TelephonyReader.DataState): DataTriageUi? {
        val triage = triageFor(state.dataRules, buildDataSnapshot(dataState, state)) ?: return null
        val labels = builtInGroupLabels + state.customGroups.associate { it.id to it.name }
        return triage.toUi(groupLabel = { id -> labels[id] ?: id })
    }

    private fun DataTriage.toUi(groupLabel: (String) -> String): DataTriageUi = when (this) {
        is DataTriage.Roaming -> DataTriageUi(
            kind = DataTriageKind.ROAMING,
            dataSimName = dataSim.name(),
            countryLabel = countryDisplayName(country),
            otherSimName = localSims.firstOrNull()?.name(),
            country = country.trim().uppercase(),
            dataSimRef = dataSim.simRef(),
            widenGroups = widenGroupIds.map { TriageGroupUi(it, groupLabel(it)) },
        )
        is DataTriage.NoData -> DataTriageUi(
            kind = DataTriageKind.NO_DATA,
            dataSimName = dataSim.name(),
            countryLabel = countryDisplayName(country),
            // The local SIM to prefer: an active one to switch to, or a
            // disabled local profile — "Change SIM" covers enabling it too.
            otherSimName = switchTo.firstOrNull()?.name()
                ?: enableFirst.firstOrNull()?.let { it.displayName.ifBlank { it.carrierName } },
            country = country.trim().uppercase(),
            dataSimRef = dataSim.simRef(),
        )
        is DataTriage.WrongSim -> DataTriageUi(
            kind = DataTriageKind.WRONG_SIM,
            dataSimName = dataSim.name(),
            countryLabel = countryDisplayName(country),
            otherSimName = wantedSim.name(),
            country = country.trim().uppercase(),
            dataSimRef = dataSim.simRef(),
        )
    }

    private fun ActiveSim.name() = displayName.ifBlank { carrierName }
    private fun ActiveSim.simRef() = SimRef(subscriptionId, carrierName, displayName)

    // True while a triage "Use in" write is in flight, so a double-tap — or a
    // country and a group tapped before the first write lands — mints exactly
    // one rule, not several (Codex on PR #62). Touched only on the main thread
    // (taps and viewModelScope's default dispatcher), so a plain flag suffices.
    private var confirmingTriage = false

    /**
     * "Use in ⟨place⟩" (SPEC "Data rules" → Triage): record that roaming here is
     * expected, on the SIM the card named — for [country], or for [groupId] when
     * widening to a group that contains it. [country] and [dataSimRef] are the
     * identity the tapped card *rendered*; the write only lands if the live
     * situation still matches it (same country, same data SIM, still roaming,
     * and — for a widen — the group still applies), so a tap left stale by a
     * country or SIM change between render and tap is a no-op rather than
     * silently approving a situation the user never saw (Codex on PR #62).
     * Adding the rule makes the situation covered, so the card clears.
     */
    fun confirmDataRoamingOk(country: String, dataSimRef: SimRef, groupId: String? = null) {
        val card = triage.value ?: return
        if (card.kind != DataTriageKind.ROAMING) return
        if (card.country != country || card.dataSimRef != dataSimRef) return
        if (groupId != null && card.widenGroups.none { it.id == groupId }) return
        // The in-flight guard, not a duplicate search, is what stops a
        // double-tap stacking rules: a second tap while a confirm is pending is
        // dropped. A duplicate search would wrongly no-op when an equivalent
        // rule exists but is disabled or shadowed below an AlwaysWarn — the
        // card legitimately shows there, and the tap must add a fresh,
        // effective rule on top (Codex on PR #62).
        if (confirmingTriage) return
        confirmingTriage = true
        val rule = roamingOkRule(country, groupId, dataSimRef)
        viewModelScope.launch {
            try {
                app.stateHolders().filterNotNull().first().updateDataRules { it.withRuleAdded(rule) }
                // Hold the guard until the card actually clears, not just until
                // the write returns: the state flow and triage recompute lag the
                // commit, and a tap in that gap would otherwise prepend a second
                // rule (Codex on PR #62). Released once triage no longer shows
                // this arrival (the rule covered it, or the situation changed),
                // with a backstop so a write that never clears the card can't
                // wedge the button forever.
                withTimeoutOrNull(GUARD_RELEASE_TIMEOUT_MILLIS) {
                    triage.first { it?.country != country || it?.dataSimRef != dataSimRef }
                }
                // Retire any notification already posted for the accepted
                // arrival — otherwise it lingers for the rest of the trip (the
                // mark only clears on a country/SIM change, and the now-covered
                // verdict never re-posts). Only when the situation resolved to
                // nothing: if a different arrival appeared while the write was
                // suspended, triage is non-null and its own (newer) warning
                // must not be cancelled by our shared-tag cancel (Codex on
                // PR #62).
                if (triage.value == null) app.notifications.cancelDataWatch()
            } finally {
                confirmingTriage = false
            }
        }
    }

    /** SIMs the user can target: every registered SIM, active or not. */
    val simOptions: StateFlow<List<SimOptionUi>> =
        app.stateHolders()
            .flatMapLatest { holder -> holder?.state ?: flowOf(null) }
            .combine(app.assembler.simsAndAccounts()) { state, sims ->
                buildSimOptions(state?.simRegistry.orEmpty(), sims.activeSims)
            }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * SIMs the *data* editor can target: unlike [simOptions], data-only rows
     * (travel eSIMs with no call-capable account) are offered too — carrying
     * data is exactly what they're for — and the active set includes the
     * data-only subscriptions the call-capable list can't see.
     */
    val dataSimOptions: StateFlow<List<SimOptionUi>> =
        app.stateHolders()
            .flatMapLatest { holder -> holder?.state ?: flowOf(null) }
            .combine(app.assembler.simsAndAccounts()) { state, sims -> state to sims }
            .combine(app.assembler.dataStates()) { (state, sims), dataState ->
                buildSimOptions(
                    state?.simRegistry.orEmpty(),
                    allActiveSims(sims, dataState),
                    callCapableOnly = false,
                )
            }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The whole country list, sorted by localized name; built off the main thread. */
    val countryOptions: StateFlow<List<CountryOptionUi>> =
        flowOf(Unit)
            .map { allCountryOptions() }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * The countries to surface in the picker's "Suggested" bucket: the ones the
     * user's contacts have numbers in, most-common first, capped at
     * [SUGGESTED_COUNTRY_LIMIT]. Derived off the main thread from the warm
     * contact index (empty without READ_CONTACTS) and re-derived when it
     * refreshes, so granting contacts fills the bucket without a manual reload.
     */
    val suggestedCountries: StateFlow<List<CountryOptionUi>> =
        combine(app.assembler.contacts(), countryOptions) { contacts, options ->
            if (options.isEmpty()) return@combine emptyList()
            val optionByRegion = options.associateBy { it.regionCode.uppercase() }
            contacts.regionsByContactCount()
                .mapNotNull { optionByRegion[it.uppercase()] }
                .take(SUGGESTED_COUNTRY_LIMIT)
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The shipped groups, resolved once; custom groups are appended per state. */
    private val builtInGroupOptions: List<CountryGroupOptionUi> = CountryGroups.allIds().map { id ->
        val label = application.getString(groupLabelRes(id))
        CountryGroupOptionUi(
            id = id,
            label = label,
            description = application.getString(groupDescriptionRes(id)),
            memberRegions = CountryGroups.members(id).map { it.uppercase() }.toSet(),
            searchTerms = countryGroupSearchTerms(id, label),
        )
    }

    private val builtInGroupLabels = builtInGroupOptions.associate { it.id to it.label }

    /** The user's custom groups, live from persisted state. */
    val customGroups: StateFlow<List<CustomGroup>> =
        app.stateHolders()
            .flatMapLatest { holder -> holder?.state ?: flowOf(null) }
            .map { it?.customGroups.orEmpty() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The built-ins plus the user's custom groups. A soft-deleted group stays in
     * the list but is marked non-[CountryGroupOptionUi.selectable]: the picker
     * hides it, so a new rule can't point at a group the imminent purge is about
     * to drop (which would strand that rule with a matcher that matches nothing),
     * while an existing rule that already references it still resolves its label
     * here during the undo window rather than showing the raw id (Codex on PR
     * #61).
     */
    val groupOptions: StateFlow<List<CountryGroupOptionUi>> =
        customGroups
            .map { groups -> builtInGroupOptions + groups.map(::customGroupOption) }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), builtInGroupOptions)

    private fun customGroupOption(group: CustomGroup): CountryGroupOptionUi {
        val regions = group.regionCodes.map { it.uppercase() }
        return CountryGroupOptionUi(
            id = group.id,
            label = group.name,
            // The member countries read as the group's description; the name is
            // the user's, so there's nothing else to say about it.
            description = regions.joinToString { countryDisplayName(it) },
            memberRegions = regions.toSet(),
            searchTerms = countryGroupSearchTerms(group.id, group.name),
            // A soft-deleted group keeps its label for existing selections but is
            // not offered as a new pick until the undo window ends (purge).
            selectable = !group.pendingRemoval,
        )
    }

    /**
     * Non-SIM calling accounts (SIP providers, VoIP apps registered with
     * Telecom) the editor can target, live from the telephony snapshot so an
     * account enabled or removed in system settings shows up on refresh.
     */
    val callingAccounts: StateFlow<List<CallingAccountOptionUi>> =
        app.assembler.simsAndAccounts()
            .map { sims -> sims.callingAccounts.map { CallingAccountOptionUi(it.ref, it.label) } }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Installed app-to-app hand-off targets, so the editor offers only reachable
     * apps (SPEC "Hand-off to another app"). PackageManager query, off the main
     * thread.
     */
    val handOffApps: StateFlow<Set<ContactCallApp>> =
        flowOf(Unit)
            .map { installedContactCallApps(app.packageManager) }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /**
     * Installed dial-intent hand-off targets (Google Voice, Teams), so the editor
     * offers only reachable apps. PackageManager query, off the main thread.
     */
    val dialHandoffApps: StateFlow<Set<DialHandoffApp>> =
        flowOf(Unit)
            .map { installedDialHandoffApps(app.packageManager) }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** After READ_CONTACTS is granted, rebuild the warm contact index. */
    fun onContactsAccessGranted() = app.refreshContacts()

    /** After READ_PHONE_NUMBERS is granted, re-read subscriptions so numbers show. */
    fun onPhoneNumbersGranted() = app.refreshTelephony()

    /**
     * SIMs whose "add rules for this new SIM?" prompt is pending. Only shown
     * while the SIM is actually active — a prompt for a SIM the user just
     * disabled again would only offer a rule that starts out paused.
     */
    val newSimPrompts: StateFlow<List<NewSimPromptUi>> =
        app.stateHolders()
            .flatMapLatest { holder -> holder?.state ?: flowOf(null) }
            .combine(app.assembler.simsAndAccounts()) { state, sims ->
                buildNewSimPrompts(state?.simRegistry.orEmpty(), sims.activeSims)
            }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The SIMs screen rows: the whole registry with live active state, built
     * off the main thread (the last-seen date formatting included, so
     * composition just renders strings).
     */
    val registryRows: StateFlow<List<RegistrySimRowUi>> =
        combine(
            app.stateHolders().flatMapLatest { holder -> holder?.state ?: flowOf(null) },
            app.assembler.simsAndAccounts(),
            app.assembler.dataStates(),
        ) { state, sims, dataState ->
            // The subscription rows join the call snapshot: a data-only eSIM
            // is just as active, and without them its row would sort and
            // label as "last seen" while it is live (Codex on PR #52).
            buildRegistryRows(
                state?.simRegistry.orEmpty(),
                sims.activeSims + dataState.subscriptions,
            )
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Whether the SIMs screen is open. Mirrored into [SavedStateHandle] for
     * the same reason as [editorTarget]: recreation and process death must
     * bring the user back where they were.
     */
    private val _registryOpen = MutableStateFlow(savedState.get<Boolean>(KEY_REGISTRY_OPEN) ?: false)
    val registryOpen: StateFlow<Boolean> = _registryOpen

    fun openSimRegistry() = setRegistryOpen(true)

    /**
     * The Quick Settings tile's landing: a deliberate navigation, so any open
     * editor is closed too (abandoning its draft, exactly like Cancel) —
     * otherwise the editor would keep covering the registry and the tile tap
     * would appear to do nothing.
     */
    fun openSimRegistryFromShortcut() {
        setEditorTarget(null)
        // The data editor's route outranks the registry too — leaving it set
        // would keep covering the registry the same way (Codex on PR #57).
        setDataEditorTarget(null)
        setRegistryOpen(true)
    }

    fun closeSimRegistry() = setRegistryOpen(false)

    private fun setRegistryOpen(open: Boolean) {
        _registryOpen.value = open
        savedState[KEY_REGISTRY_OPEN] = open
    }

    /**
     * The Settings screen's options, mirroring persisted state — except the
     * telemetry choice, which comes from the app's effective stream (taps and
     * the durable marker masking stale persisted state) so the switch always
     * shows what telemetry is actually doing.
     */
    val settings: StateFlow<SettingsUi> =
        app.stateHolders()
            .flatMapLatest { holder -> holder?.state ?: flowOf(null) }
            .combine(app.analyticsOptIns()) { state, analyticsOptIn ->
                SettingsUi(
                    showCallToast = state?.showCallToast ?: false,
                    callDelaySeconds = state?.callDelaySeconds ?: 0,
                    correctContactNumbers = state?.correctContactNumbers ?: false,
                    guardOverseasHandsFree = state?.guardOverseasHandsFree ?: false,
                    guardDisabledSimHandsFree = state?.guardDisabledSimHandsFree ?: false,
                    analyticsOptIn = analyticsOptIn,
                )
            }
            .stateIn(
                viewModelScope,
                // Eager, unlike the sibling flows: Settings renders .value for
                // its first frame, so it must track a choice loaded while the
                // screen was closed — idle WhileSubscribed sharing would hold
                // the one-time seed until after that frame.
                SharingStarted.Eagerly,
                // Seeded, not defaulted: restored straight into Settings on a
                // cold start, the switch must show the last applied choice
                // even before the state loads.
                SettingsUi(analyticsOptIn = app.currentAnalyticsOptIn()),
            )

    /** The settings "Show which SIM is used" toggle. */
    fun setShowCallToast(enabled: Boolean) {
        // Wait for the holder rather than dropping the write, like [edit].
        viewModelScope.launch {
            app.stateHolders().filterNotNull().first().setShowCallToast(enabled)
        }
    }

    /**
     * The settings "Make Simmo better" toggle. Routed through the app — not
     * written to the holder directly — so telemetry applies the choice
     * immediately and the durable marker is committed (see
     * [SimmoApp.setAnalyticsOptIn]).
     */
    fun setAnalyticsOptIn(enabled: Boolean) = app.setAnalyticsOptIn(enabled)

    /** The settings "Delay before calling" slider, in seconds (0 = off). */
    fun setCallDelaySeconds(seconds: Int) {
        viewModelScope.launch {
            app.stateHolders().filterNotNull().first().setCallDelaySeconds(seconds)
        }
    }

    /** The settings "Use contacts' local numbers" toggle. */
    fun setCorrectContactNumbers(enabled: Boolean) {
        viewModelScope.launch {
            app.stateHolders().filterNotNull().first().setCorrectContactNumbers(enabled)
        }
    }

    /** The hands-free guard's "Block overseas calls" toggle. */
    fun setGuardOverseasHandsFree(enabled: Boolean) {
        viewModelScope.launch {
            app.stateHolders().filterNotNull().first().setGuardOverseasHandsFree(enabled)
        }
    }

    /** The guard's "Block calls needing a disabled SIM" toggle. */
    fun setGuardDisabledSimHandsFree(enabled: Boolean) {
        viewModelScope.launch {
            app.stateHolders().filterNotNull().first().setGuardDisabledSimHandsFree(enabled)
        }
    }

    /** Whether the Settings screen is open; saved like [registryOpen]. */
    private val _settingsOpen = MutableStateFlow(savedState.get<Boolean>(KEY_SETTINGS_OPEN) ?: false)
    val settingsOpen: StateFlow<Boolean> = _settingsOpen

    fun openSettings() = setSettingsOpen(true)

    fun closeSettings() = setSettingsOpen(false)

    private fun setSettingsOpen(open: Boolean) {
        _settingsOpen.value = open
        savedState[KEY_SETTINGS_OPEN] = open
    }

    fun deleteRegisteredSim(ref: SimRef) {
        viewModelScope.launch {
            app.stateHolders().filterNotNull().first().deleteRegisteredSim(ref)
        }
    }

    /** Whether the Groups screen is open; mirrored into [SavedStateHandle]. */
    private val _groupsOpen = MutableStateFlow(savedState.get<Boolean>(KEY_GROUPS_OPEN) ?: false)
    val groupsOpen: StateFlow<Boolean> = _groupsOpen

    fun openGroups() = setGroupsOpen(true)

    fun closeGroups() = setGroupsOpen(false)

    private fun setGroupsOpen(open: Boolean) {
        _groupsOpen.value = open
        savedState[KEY_GROUPS_OPEN] = open
    }

    /**
     * Add a new custom group (when [id] is null) or edit the existing one.
     * Membership is stored uppercased. A blank name or empty membership is the
     * editor's to prevent; this trusts what it passes.
     */
    fun saveCustomGroup(id: String?, name: String, regionCodes: List<String>) {
        viewModelScope.launch {
            app.stateHolders().filterNotNull().first().updateCustomGroups { groups ->
                // A new group gets a never-reused id; editing keeps the same one.
                val groupId = id ?: CustomGroup.newId()
                groups.withGroupSaved(
                    CustomGroup(groupId, name.trim(), regionCodes.map { it.uppercase() }),
                )
            }
        }
    }

    /**
     * Soft-delete a custom group: mark it [CustomGroup.pendingRemoval] (struck-
     * through, but still resolvable) rather than removing it, so referencing
     * rules keep matching its countries until purge. Undoable in place
     * ([undoGroupRemoval]); the loss of those references only happens when the
     * group is actually purged on leaving the screen.
     */
    fun deleteCustomGroup(id: String) {
        viewModelScope.launch {
            app.stateHolders().filterNotNull().first().updateCustomGroups { it.withGroupMarkedForRemoval(id) }
        }
    }

    /** Undo a group soft-delete: clear [CustomGroup.pendingRemoval] on the group with [id]. */
    fun undoGroupRemoval(id: String) {
        viewModelScope.launch {
            app.stateHolders().filterNotNull().first().updateCustomGroups { it.withGroupRemovalUndone(id) }
        }
    }

    /**
     * Which rule the editor is open on. Held here (not in composition) and
     * mirrored into [SavedStateHandle] so it survives both configuration
     * changes and process death — restore reopens the editor, letting the
     * editor's own rememberSaveable draft come back rather than dropping the
     * user to the list with an in-progress add/edit lost.
     */
    private val _editorTarget = MutableStateFlow(restoreEditorTarget())
    val editorTarget: StateFlow<EditorTarget?> = _editorTarget

    fun openNewRule() = setEditorTarget(EditorTarget.New())

    /**
     * The prompt's "add a rule" path: the editor opens preset to the SIM,
     * with its home country preseeding the matcher (the user still has to
     * scope the rule deliberately before Save enables — no accidental
     * any-destination rules from accepting defaults).
     */
    fun openNewRuleForSim(prompt: NewSimPromptUi) =
        setEditorTarget(EditorTarget.New(presetSim = prompt.ref, presetRegion = prompt.homeRegion))

    fun openEditRule(id: String) {
        currentRules().firstOrNull { it.id == id }?.let { setEditorTarget(EditorTarget.Existing(it.id, it)) }
    }

    fun closeEditor() = setEditorTarget(null)

    private fun setEditorTarget(target: EditorTarget?) {
        _editorTarget.value = target
        savedState[KEY_EDITOR_TARGET] = target?.let { editorJson.encodeToString(it) }
    }

    private fun restoreEditorTarget(): EditorTarget? =
        savedState.get<String?>(KEY_EDITOR_TARGET)?.let { encoded ->
            runCatching { editorJson.decodeFromString<EditorTarget>(encoded) }.getOrNull()
        }

    /** The current rules, as domain objects, for the editor to read and edit. */
    fun currentRules(): List<CallingRule> = app.stateHolder()?.current?.rules?.rules.orEmpty()

    // [pendingGroups] are groups the user built in the picker while making this
    // rule; they're committed in the *same* transaction as the rule so the state
    // never holds a rule pointing at an unsaved group (Codex on PR #35). Empty
    // for every path but the create-group-in-picker one.
    fun addRule(rule: CallingRule, pendingGroups: List<CustomGroup> = emptyList()) =
        commit(pendingGroups) { it.withRuleAdded(rule) }
    /** Save an edit from the editor, keyed by the rule's stable id (see [EditorTarget.Existing]). */
    fun replaceRule(id: String, rule: CallingRule, pendingGroups: List<CustomGroup> = emptyList()) =
        commit(pendingGroups) { it.withRuleReplaced(id, rule) }
    /**
     * Soft-delete the rule with [id] (SPEC "Calling rules"): no confirm dialog.
     * It's marked [CallingRule.pendingRemoval] in place — struck-through and skipped in
     * evaluation — rather than removed, so the delete takes effect at once but
     * stays undoable ([undoRuleRemoval]) until the screen is left ([purgePendingRemovals]).
     * Used by both the row menu and the editor's Delete button.
     */
    fun removeRule(id: String) = edit { it.withRuleMarkedForRemoval(id) }

    /** Undo a soft-delete: un-strike the rule with [id], in place. */
    fun undoRuleRemoval(id: String) = edit { it.withRuleRemovalUndone(id) }

    fun moveRule(from: Int, to: Int) = edit { it.withRuleMoved(from, to) }

    /** Insert a copy of the rule with [id] directly below it, under a new id. */
    fun duplicateRule(id: String) = edit { book ->
        book.rules.indexOfFirst { it.id == id }.takeIf { it >= 0 }
            ?.let { book.withRuleDuplicated(it, newRuleId()) } ?: book
    }

    /** Turn the rule with [id] on or off (kept in place either way). */
    fun setRuleEnabled(id: String, enabled: Boolean) = edit { book ->
        book.rules.firstOrNull { it.id == id }?.let { book.withRuleReplaced(id, it.copy(enabled = enabled)) } ?: book
    }

    /**
     * Save from the new-SIM prompt's editor: the rule is suggested above the
     * first paused rule (SPEC "On SIM change") rather than at the very top,
     * and the answered prompt is retired.
     */
    fun addRuleForNewSim(sim: SimRef, rule: CallingRule, pendingGroups: List<CustomGroup> = emptyList()) {
        viewModelScope.launch {
            val holder = app.stateHolders().filterNotNull().first()
            val activeSims = app.assembler.activeSims()
            // Groups + rule in one transaction (see [addRule]); the prompt-answered
            // write is independent and can't strand a group.
            holder.updateGroupsAndRules(pendingGroups) { book ->
                book.withRuleInserted(book.newSimRuleInsertionIndex(activeSims), rule)
            }
            holder.markSimRulePromptAnswered(sim)
        }
    }

    /** The prompt's "not now": retire it without adding anything. */
    fun dismissNewSimPrompt(sim: SimRef) {
        viewModelScope.launch {
            app.stateHolders().filterNotNull().first().markSimRulePromptAnswered(sim)
        }
    }

    /** Which list the rules home shows; survives recreation like the routes. */
    private val _rulesTab = MutableStateFlow(
        savedState.get<String>(KEY_RULES_TAB)
            ?.let { stored -> RulesTab.entries.firstOrNull { it.name == stored } }
            ?: RulesTab.CALLING,
    )
    val rulesTab: StateFlow<RulesTab> = _rulesTab

    fun selectRulesTab(tab: RulesTab) {
        _rulesTab.value = tab
        savedState[KEY_RULES_TAB] = tab.name
    }

    /**
     * The data-watch notification's Rules action (and body tap): land on the
     * data list itself, so anything covering the rules home closes — the
     * warning is about now, not about wherever the user last left the app.
     */
    fun openDataRules() {
        selectRulesTab(RulesTab.DATA)
        setEditorTarget(null)
        setDataEditorTarget(null)
        setRegistryOpen(false)
        setGroupsOpen(false)
        setSettingsOpen(false)
    }

    /** The data editor's route, held like [editorTarget] for the same reasons. */
    private val _dataEditorTarget = MutableStateFlow(restoreDataEditorTarget())
    val dataEditorTarget: StateFlow<DataEditorTarget?> = _dataEditorTarget

    fun openNewDataRule() = setDataEditorTarget(DataEditorTarget.New)

    fun openEditDataRule(id: String) {
        currentDataRules().firstOrNull { it.id == id }?.let {
            setDataEditorTarget(DataEditorTarget.Existing(it.id, it))
        }
    }

    fun closeDataEditor() = setDataEditorTarget(null)

    private fun setDataEditorTarget(target: DataEditorTarget?) {
        _dataEditorTarget.value = target
        savedState[KEY_DATA_EDITOR_TARGET] = target?.let { editorJson.encodeToString(it) }
    }

    private fun restoreDataEditorTarget(): DataEditorTarget? =
        savedState.get<String?>(KEY_DATA_EDITOR_TARGET)?.let { encoded ->
            runCatching { editorJson.decodeFromString<DataEditorTarget>(encoded) }.getOrNull()
        }

    /** The current data rules, as domain objects, for the editor to read and edit. */
    fun currentDataRules(): List<DataRule> = app.stateHolder()?.current?.dataRules?.rules.orEmpty()

    // The data list's edits mirror the calling ones; pendingGroups commit in
    // the same transaction for the same no-orphan reason (see [addRule]).
    fun addDataRule(rule: DataRule, pendingGroups: List<CustomGroup> = emptyList()) =
        commitData(pendingGroups) { it.withRuleAdded(rule) }
    /** Save a data-rule edit from the editor, keyed by stable id. */
    fun replaceDataRule(id: String, rule: DataRule, pendingGroups: List<CustomGroup> = emptyList()) =
        commitData(pendingGroups) { it.withRuleReplaced(id, rule) }
    /** Soft-delete the data rule with [id]; struck-through and undoable until purge. See [removeRule]. */
    fun removeDataRule(id: String) = editData { it.withRuleMarkedForRemoval(id) }

    /** Undo a data-rule soft-delete: un-strike the rule with [id], in place. */
    fun undoDataRuleRemoval(id: String) = editData { it.withRuleRemovalUndone(id) }

    fun moveDataRule(from: Int, to: Int) = editData { it.withRuleMoved(from, to) }

    /** Insert a copy of the data rule with [id] directly below it, under a new id. */
    fun duplicateDataRule(id: String) = editData { book ->
        book.rules.indexOfFirst { it.id == id }.takeIf { it >= 0 }
            ?.let { book.withRuleDuplicated(it, newRuleId()) } ?: book
    }

    /** Turn the data rule with [id] on or off (kept in place either way). */
    fun setDataRuleEnabled(id: String, enabled: Boolean) = editData { book ->
        book.rules.firstOrNull { it.id == id }?.let { book.withRuleReplaced(id, it.copy(enabled = enabled)) } ?: book
    }

    private fun editData(transform: (DataRuleBook) -> DataRuleBook) {
        // Wait for the holder rather than dropping the edit, like [edit].
        viewModelScope.launch {
            app.stateHolders().filterNotNull().first().updateDataRules(transform)
        }
    }

    private fun commitData(
        pendingGroups: List<CustomGroup>,
        transform: (DataRuleBook) -> DataRuleBook,
    ) {
        viewModelScope.launch {
            app.stateHolders().filterNotNull().first().updateGroupsAndDataRules(pendingGroups, transform)
        }
    }

    private fun edit(transform: (CallingRuleBook) -> CallingRuleBook) {
        // Wait for the holder rather than dropping the edit: on a fast cold
        // start the rules screen can be interactive before SimmoApp finishes
        // building the holder, and a lost add/edit/delete would look saved.
        viewModelScope.launch {
            app.stateHolders().filterNotNull().first().updateRules(transform)
        }
    }

    /** Like [edit], but commits [pendingGroups] in the same transaction as the rule. */
    private fun commit(
        pendingGroups: List<CustomGroup>,
        transform: (CallingRuleBook) -> CallingRuleBook,
    ) {
        viewModelScope.launch {
            app.stateHolders().filterNotNull().first().updateGroupsAndRules(pendingGroups, transform)
        }
    }

    private companion object {
        const val KEY_EDITOR_TARGET = "editor_target"
        const val KEY_DATA_EDITOR_TARGET = "data_editor_target"
        const val KEY_RULES_TAB = "rules_tab"
        const val KEY_REGISTRY_OPEN = "registry_open"
        const val KEY_GROUPS_OPEN = "groups_open"
        const val KEY_SETTINGS_OPEN = "settings_open"

        /** How many contact-derived countries the "Suggested" bucket shows. */
        const val SUGGESTED_COUNTRY_LIMIT = 5

        /** Backstop so a triage confirm can never wedge the "Use in" button. */
        const val GUARD_RELEASE_TIMEOUT_MILLIS = 5_000L
    }
}

internal fun buildRegistryRows(
    registry: List<RegisteredSim>,
    activeSims: List<ActiveSim>,
    formatLastSeen: (Long) -> String = ::defaultLastSeenLabel,
): List<RegistrySimRowUi> {
    val activeIds = activeSims.map { it.subscriptionId }.toSet()
    return registry
        // Active SIMs first, then most recently seen — the deletion
        // candidates (long-stale rows) end up at the bottom together.
        .sortedWith(
            compareByDescending<RegisteredSim> { it.subscriptionId in activeIds }
                .thenByDescending { it.lastSeenEpochMillis },
        )
        .map { sim ->
            val name = sim.displayName.ifBlank { sim.carrierName }
            RegistrySimRowUi(
                ref = sim.ref(),
                name = name,
                // The carrier line only earns its place when it says
                // something the name doesn't (it IS the name for SIMs with a
                // blank or carrier-equal display name).
                carrier = sim.carrierName
                    .takeIf { it.isNotBlank() && !it.trim().equals(name.trim(), ignoreCase = true) },
                detail = registryDetailLabel(sim.phoneNumber, sim.countryIso),
                active = sim.subscriptionId in activeIds,
                lastSeenLabel = formatLastSeen(sim.lastSeenEpochMillis),
            )
        }
}

/**
 * The SIMs screen's "number · country" line, e.g. "+61 412 345 678 · Australia";
 * either half alone when the other is unknown, null when both are. The number is
 * shown in international format when it parses to a valid number (libphonenumber
 * metadata — never call in composition), verbatim otherwise: a number the
 * library can't read is still better shown than hidden.
 */
internal fun registryDetailLabel(phoneNumber: String, countryIso: String): String? {
    val country = countryIso.takeIf { it.isNotBlank() }?.let(::countryDisplayName)
    val number = phoneNumber.takeIf { it.isNotBlank() }?.let { raw ->
        val util = PhoneNumberUtil.getInstance()
        try {
            val parsed = util.parse(raw, countryIso.trim().uppercase().ifEmpty { null })
            if (util.isValidNumber(parsed)) {
                util.format(parsed, PhoneNumberUtil.PhoneNumberFormat.INTERNATIONAL)
            } else {
                raw
            }
        } catch (_: NumberParseException) {
            raw
        }
    }
    return listOfNotNull(number, country).joinToString(" · ").ifEmpty { null }
}

private fun defaultLastSeenLabel(epochMillis: Long): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochMillis))

/** A SIM the rule editor can target. */
data class SimOptionUi(
    val ref: SimRef,
    val label: String,
    val active: Boolean,
)

/** A non-SIM calling account (SIP provider, VoIP app) the editor can target. */
data class CallingAccountOptionUi(
    val ref: PhoneAccountRef,
    val label: String,
    /** False when a rule's stored account is not currently registered/enabled. */
    val available: Boolean = true,
)

/** The user-facing name of a hand-off action's target. */
internal fun RuleAction.HandOff.displayLabel(): String = when (this) {
    // Rules stored before the label existed fall back to the raw account id.
    is RuleAction.HandOff.ViaPhoneAccount -> label.ifBlank { account.id }
    is RuleAction.HandOff.ViaDialIntent -> app.label
    is RuleAction.HandOff.ViaContactApp -> app.label
}

/** One pending "add rules for this new SIM?" nudge on the rules list. */
data class NewSimPromptUi(
    val ref: SimRef,
    val label: String,
    /**
     * The SIM's home country (uppercase ISO region) when the platform reports
     * one; preseeds the suggested rule's matcher so accepting the prompt
     * doesn't silently create an any-destination rule.
     */
    val homeRegion: String?,
)

internal fun buildNewSimPrompts(
    registry: List<RegisteredSim>,
    activeSims: List<ActiveSim>,
): List<NewSimPromptUi> {
    val activeById = activeSims.associateBy { it.subscriptionId }
    return registry
        .filter { it.needsRulePrompt && it.subscriptionId in activeById }
        .map { sim ->
            NewSimPromptUi(
                ref = sim.ref(),
                label = sim.displayName.ifBlank { sim.carrierName },
                homeRegion = activeById.getValue(sim.subscriptionId)
                    .countryIso.uppercase().ifBlank { null },
            )
        }
}

/** A country the rule editor can match. */
data class CountryOptionUi(
    val regionCode: String,
    val label: String,
    /** Normalized names/codes/aliases this country is found by; see [matches]. */
    val searchTerms: List<String> = emptyList(),
)

/** A country group the picker offers as a single one-tap entry. */
data class CountryGroupOptionUi(
    val id: String,
    /** e.g. "EU/EEA". */
    val label: String,
    /** e.g. "European Union and EEA countries". */
    val description: String,
    /** Uppercase member regions; surfaces the group on member searches. */
    val memberRegions: Set<String>,
    /** Folded terms the group itself is found by (EU, EEA, Europe, …). */
    val searchTerms: List<String>,
    /**
     * False for a soft-deleted group awaiting purge: the picker hides it (no new
     * rule may select it), but it stays in the option list so an existing rule
     * that references it still resolves its label during the undo window.
     */
    val selectable: Boolean = true,
)

internal fun buildSimOptions(
    registry: List<RegisteredSim>,
    activeSims: List<ActiveSim>,
    /** False for the data editor: data-only travel eSIMs are valid targets there. */
    callCapableOnly: Boolean = true,
): List<SimOptionUi> {
    val activeById = activeSims.associateBy { it.subscriptionId }
    // Registry union active, so a SIM seen this session but not yet persisted
    // still shows. De-dup by subscription id when it's a real one, else by the
    // carrier + display-name fallback identity: after a restore every stored id
    // is invalidated to the same sentinel, so keying by id alone would collapse
    // distinct disabled SIMs into one option. Data-only rows (no call-capable
    // account — travel eSIMs) are registered for the roaming watch but can't
    // place calls, so calling rules never offer them as targets.
    val fromActive = activeSims.map { RegisteredSim(it.subscriptionId, it.carrierName, it.displayName, 0L) }
    val merged = (registry.filter { !callCapableOnly || it.callCapable } + fromActive)
        .associateBy { sim ->
            if (sim.subscriptionId == SimRef.INVALID_SUBSCRIPTION_ID) {
                "name:${sim.carrierName.trim().lowercase()}|${sim.displayName.trim().lowercase()}"
            } else {
                "id:${sim.subscriptionId}"
            }
        }
        .values
    return merged
        .map {
            SimOptionUi(
                ref = it.ref(),
                label = it.displayName.ifBlank { it.carrierName },
                active = it.subscriptionId in activeById,
            )
        }
        .sortedWith(compareByDescending<SimOptionUi> { it.active }.thenBy { it.label.lowercase() })
}

private fun allCountryOptions(): List<CountryOptionUi> {
    val util = PhoneNumberUtil.getInstance()
    return util.supportedRegions
        .map { region -> region to countryDisplayName(region) }
        // Sort by the localized country name, not the "+61 …" label, so the
        // picker reads alphabetically (Australia under A, not by dialing code).
        .sortedBy { (_, name) -> name.lowercase() }
        .map { (region, name) ->
            CountryOptionUi(
                regionCode = region,
                label = countryLabel(region),
                searchTerms = countrySearchTerms(region, name, util.getCountryCodeForRegion(region)),
            )
        }
}

private fun groupLabelRes(groupId: String): Int = when (groupId) {
    CountryGroups.EU_EEA -> app.simmo.R.string.group_eu_eea
    CountryGroups.USA_TERRITORIES -> app.simmo.R.string.group_usa_territories
    CountryGroups.NORTH_AMERICA -> app.simmo.R.string.group_north_america
    CountryGroups.CARIBBEAN_NANP -> app.simmo.R.string.group_caribbean_nanp
    else -> error("Unknown country group: $groupId")
}

private fun groupDescriptionRes(groupId: String): Int = when (groupId) {
    CountryGroups.EU_EEA -> app.simmo.R.string.group_eu_eea_description
    CountryGroups.USA_TERRITORIES -> app.simmo.R.string.group_usa_territories_description
    CountryGroups.NORTH_AMERICA -> app.simmo.R.string.group_north_america_description
    CountryGroups.CARIBBEAN_NANP -> app.simmo.R.string.group_caribbean_nanp_description
    else -> error("Unknown country group: $groupId")
}

/** The localized country name alone, e.g. "Australia". */
internal fun countryDisplayName(regionCode: String): String {
    val region = regionCode.uppercase()
    return Locale("", region).displayCountry.ifBlank { region }
}

internal fun CallingRule.toRow(
    activeSims: List<ActiveSim>,
    groupLabel: (String) -> String = { it },
    /** Refs of the currently registered non-SIM calling accounts. */
    availableAccounts: Set<PhoneAccountRef> = emptySet(),
): RuleRowUi {
    // Groups lead ("EU/EEA, +44 United Kingdom"): the group is the rule's
    // headline and the hand-picked countries read as its additions.
    val parts = matcher.groupIds().map(groupLabel) +
        matcher.regionCodes().map { countryLabel(it) }
    val matcherLabel = parts.takeIf { it.isNotEmpty() }?.joinToString()
    val base = when (val a = action) {
        is RuleAction.UseSim -> RuleRowUi(
            matcherCountryLabel = matcherLabel,
            action = ActionUi.UseSim(a.sim.displayName.ifBlank { a.sim.carrierName }),
            pause = when (resolveSim(a.sim, activeSims)) {
                is SimResolution.Active -> null
                SimResolution.Inactive -> RulePause.SIM_DISABLED
                is SimResolution.Ambiguous -> RulePause.SIM_AMBIGUOUS
            },
        )

        RuleAction.UseMatchingCountrySim ->
            RuleRowUi(matcherLabel, ActionUi.MatchingCountrySim)

        is RuleAction.HandOff.ViaPhoneAccount -> RuleRowUi(
            matcherCountryLabel = matcherLabel,
            action = ActionUi.HandOffApp(a.displayLabel()),
            // Same greying as a disabled SIM: the engine skips the rule while
            // its account is gone, and it re-applies when the account returns.
            pause = if (a.account in availableAccounts) null else RulePause.ACCOUNT_UNAVAILABLE,
        )

        is RuleAction.HandOff.ViaDialIntent ->
            RuleRowUi(matcherLabel, ActionUi.HandOffApp(a.app.label))

        is RuleAction.HandOff.ViaContactApp ->
            RuleRowUi(matcherLabel, ActionUi.HandOffApp(a.app.label))

        RuleAction.Ask -> RuleRowUi(matcherLabel, ActionUi.Ask)

        RuleAction.SystemDefault -> RuleRowUi(matcherLabel, ActionUi.SystemDefault)
    }
    return base.copy(enabled = enabled)
}

internal fun DataRule.toRow(
    activeSims: List<ActiveSim>,
    groupLabel: (String) -> String = { it },
): DataRuleRowUi {
    // Groups lead, like calling rows — but plain country names: the matcher is
    // where the user *is*, so dialing codes would just be noise.
    val parts = matcher.groupIds().map(groupLabel) +
        matcher.regionCodes().map { countryDisplayName(it) }
    val matcherLabel = parts.takeIf { it.isNotEmpty() }?.joinToString()
    val base = when (val e = expectation) {
        is DataExpectation.UseSimForData -> DataRuleRowUi(
            matcherCountryLabel = matcherLabel,
            expectation = DataExpectationUi.UseSimForData(
                e.sim.displayName.ifBlank { e.sim.carrierName },
            ),
            // Same greying as calling rows: the watch skips the rule while its
            // SIM can't act (SPEC "Data rules").
            pause = when (resolveSim(e.sim, activeSims)) {
                is SimResolution.Active -> null
                SimResolution.Inactive -> RulePause.SIM_DISABLED
                is SimResolution.Ambiguous -> RulePause.SIM_AMBIGUOUS
            },
        )

        is DataExpectation.RoamingOk -> when (val scope = e.scope) {
            DataSimScope.AnySim ->
                DataRuleRowUi(matcherLabel, DataExpectationUi.RoamingOkAnySim)
            DataSimScope.HomedInMatchedCountries ->
                DataRuleRowUi(matcherLabel, DataExpectationUi.RoamingOkHomedInMatched)
            is DataSimScope.Sims -> {
                // A SIM-scoped rule whose SIMs all fail to resolve can never
                // cover the data SIM — evaluation skips it — so it greys like
                // an unresolvable use-for-data rule instead of masquerading
                // as active (Codex on PR #57). One resolvable SIM is enough:
                // the scope can still silence for that SIM.
                val resolutions = scope.sims.map { resolveSim(it, activeSims) }
                DataRuleRowUi(
                    matcherCountryLabel = matcherLabel,
                    expectation = DataExpectationUi.RoamingOkSims(
                        scope.sims.joinToString { it.displayName.ifBlank { it.carrierName } },
                    ),
                    pause = when {
                        resolutions.any { it is SimResolution.Active } -> null
                        resolutions.any { it is SimResolution.Ambiguous } -> RulePause.SIM_AMBIGUOUS
                        else -> RulePause.SIM_DISABLED
                    },
                )
            }
        }

        DataExpectation.AlwaysWarn -> DataRuleRowUi(matcherLabel, DataExpectationUi.AlwaysWarn)
    }
    return base.copy(enabled = enabled)
}

/**
 * The app-to-app hand-off targets currently installed. Needs a `<queries>` entry
 * per package in the manifest for Android 11+ package visibility.
 */
internal fun installedContactCallApps(packageManager: PackageManager): Set<ContactCallApp> =
    ContactCallApp.entries.filterTo(mutableSetOf()) { app ->
        runCatching { packageManager.getPackageInfo(app.packageName, 0) }.isSuccess
    }

/** "+61 Australia" — calling code + localized country name. Not for composition. */
internal fun countryLabel(regionCode: String): String {
    val region = regionCode.uppercase()
    val callingCode = PhoneNumberUtil.getInstance().getCountryCodeForRegion(region)
    val name = countryDisplayName(region)
    return if (callingCode > 0) "+$callingCode $name" else name
}
