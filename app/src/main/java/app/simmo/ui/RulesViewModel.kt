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
import app.simmo.domain.withoutGroup
import app.simmo.telecom.installedDialHandoffApps
import app.simmo.domain.RegisteredSim
import app.simmo.domain.Rule
import app.simmo.domain.RuleAction
import app.simmo.domain.SimRef
import app.simmo.domain.CountryGroups
import app.simmo.domain.SimResolution
import app.simmo.domain.groupIds
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
}

/** One row of the rules list, ready to render (SPEC "Rules"). */
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
)

sealed interface ActionUi {
    data class UseSim(val simName: String) : ActionUi
    data object MatchingCountrySim : ActionUi
    data class HandOffApp(val target: String) : ActionUi
    data object Ask : ActionUi
    data object SystemDefault : ActionUi
}

@OptIn(ExperimentalCoroutinesApi::class)
class RulesViewModel(
    application: Application,
    private val savedState: SavedStateHandle,
) : AndroidViewModel(application) {
    private val app = application as SimmoApp
    private val editorJson = Json { ignoreUnknownKeys = true }

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
                buildRows(state, sims.activeSims)
            }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private fun buildRows(state: SimmoState?, activeSims: List<ActiveSim>): List<RuleRowUi> {
        // Built-in labels are static; a custom group's label is its user-typed
        // name from the state. An id neither knows still shows (as its raw id).
        val labels = builtInGroupLabels + state?.customGroups.orEmpty().associate { it.id to it.name }
        return state?.rules?.rules.orEmpty().map { rule ->
            rule.toRow(activeSims, groupLabel = { id -> labels[id] ?: id })
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

    /** The groups the picker offers: the built-ins plus the user's custom groups. */
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
        )
    }

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
        app.stateHolders()
            .flatMapLatest { holder -> holder?.state ?: flowOf(null) }
            .combine(app.assembler.simsAndAccounts()) { state, sims ->
                buildRegistryRows(state?.simRegistry.orEmpty(), sims.activeSims)
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

    fun closeSimRegistry() = setRegistryOpen(false)

    private fun setRegistryOpen(open: Boolean) {
        _registryOpen.value = open
        savedState[KEY_REGISTRY_OPEN] = open
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

    /** Delete a custom group; rules referencing it keep the id but match none of it. */
    fun deleteCustomGroup(id: String) {
        viewModelScope.launch {
            app.stateHolders().filterNotNull().first().updateCustomGroups { it.withoutGroup(id) }
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

    fun openEditRule(index: Int) {
        currentRules().getOrNull(index)?.let { setEditorTarget(EditorTarget.Existing(index, it)) }
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
    fun currentRules(): List<Rule> = app.stateHolder()?.current?.rules?.rules.orEmpty()

    fun addRule(rule: Rule) = edit { it.withRuleAdded(rule) }
    fun replaceRule(index: Int, rule: Rule) = edit { it.withRuleReplaced(index, rule) }
    fun removeRule(index: Int) = edit { it.withRuleRemoved(index) }
    fun moveRule(from: Int, to: Int) = edit { it.withRuleMoved(from, to) }

    /** Insert a copy of the rule at [index] directly below it. */
    fun duplicateRule(index: Int) = edit { book ->
        book.rules.getOrNull(index)?.let { book.withRuleInserted(index + 1, it) } ?: book
    }

    /** Turn the rule at [index] on or off (kept in place either way). */
    fun setRuleEnabled(index: Int, enabled: Boolean) = edit { book ->
        book.rules.getOrNull(index)?.let { book.withRuleReplaced(index, it.copy(enabled = enabled)) } ?: book
    }

    /**
     * Save from the new-SIM prompt's editor: the rule is suggested above the
     * first paused rule (SPEC "On SIM change") rather than at the very top,
     * and the answered prompt is retired.
     */
    fun addRuleForNewSim(sim: SimRef, rule: Rule) {
        viewModelScope.launch {
            val holder = app.stateHolders().filterNotNull().first()
            val activeSims = app.assembler.activeSims()
            holder.updateRules { book -> book.withRuleInserted(book.newSimRuleInsertionIndex(activeSims), rule) }
            holder.markSimRulePromptAnswered(sim)
        }
    }

    /** The prompt's "not now": retire it without adding anything. */
    fun dismissNewSimPrompt(sim: SimRef) {
        viewModelScope.launch {
            app.stateHolders().filterNotNull().first().markSimRulePromptAnswered(sim)
        }
    }

    private fun edit(transform: (app.simmo.domain.RuleBook) -> app.simmo.domain.RuleBook) {
        // Wait for the holder rather than dropping the edit: on a fast cold
        // start the rules screen can be interactive before SimmoApp finishes
        // building the holder, and a lost add/edit/delete would look saved.
        viewModelScope.launch {
            app.stateHolders().filterNotNull().first().updateRules(transform)
        }
    }

    private companion object {
        const val KEY_EDITOR_TARGET = "editor_target"
        const val KEY_REGISTRY_OPEN = "registry_open"
        const val KEY_GROUPS_OPEN = "groups_open"
        const val KEY_SETTINGS_OPEN = "settings_open"

        /** How many contact-derived countries the "Suggested" bucket shows. */
        const val SUGGESTED_COUNTRY_LIMIT = 5
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
)

internal fun buildSimOptions(
    registry: List<RegisteredSim>,
    activeSims: List<ActiveSim>,
): List<SimOptionUi> {
    val activeById = activeSims.associateBy { it.subscriptionId }
    // Registry union active, so a SIM seen this session but not yet persisted
    // still shows. De-dup by subscription id when it's a real one, else by the
    // carrier + display-name fallback identity: after a restore every stored id
    // is invalidated to the same sentinel, so keying by id alone would collapse
    // distinct disabled SIMs into one option.
    val fromActive = activeSims.map { RegisteredSim(it.subscriptionId, it.carrierName, it.displayName, 0L) }
    val merged = (registry + fromActive)
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

internal fun Rule.toRow(
    activeSims: List<ActiveSim>,
    groupLabel: (String) -> String = { it },
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

        is RuleAction.HandOff.ViaPhoneAccount ->
            RuleRowUi(matcherLabel, ActionUi.HandOffApp(a.account.id))

        is RuleAction.HandOff.ViaDialIntent ->
            RuleRowUi(matcherLabel, ActionUi.HandOffApp(a.app.label))

        is RuleAction.HandOff.ViaContactApp ->
            RuleRowUi(matcherLabel, ActionUi.HandOffApp(a.app.label))

        RuleAction.Ask -> RuleRowUi(matcherLabel, ActionUi.Ask)

        RuleAction.SystemDefault -> RuleRowUi(matcherLabel, ActionUi.SystemDefault)
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
