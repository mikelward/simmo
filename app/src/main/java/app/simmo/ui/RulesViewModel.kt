package app.simmo.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.simmo.SimmoApp
import app.simmo.domain.ActiveSim
import app.simmo.domain.RegisteredSim
import app.simmo.domain.Rule
import app.simmo.domain.RuleAction
import app.simmo.domain.RuleMatcher
import app.simmo.domain.SimRef
import app.simmo.domain.SimResolution
import app.simmo.domain.resolveSim
import app.simmo.store.SimmoState
import com.google.i18n.phonenumbers.PhoneNumberUtil
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
    /** e.g. "+61 Australia"; null means the rule matches any destination. */
    val matcherCountryLabel: String?,
    val action: ActionUi,
    /** Non-null when the rule is skipped during evaluation — shown greyed. */
    val pause: RulePause? = null,
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

    private fun buildRows(state: SimmoState?, activeSims: List<ActiveSim>): List<RuleRowUi> =
        state?.rules?.rules.orEmpty().map { rule -> rule.toRow(activeSims) }

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
     * Which rule the editor is open on. Held here (not in composition) and
     * mirrored into [SavedStateHandle] so it survives both configuration
     * changes and process death — restore reopens the editor, letting the
     * editor's own rememberSaveable draft come back rather than dropping the
     * user to the list with an in-progress add/edit lost.
     */
    private val _editorTarget = MutableStateFlow(restoreEditorTarget())
    val editorTarget: StateFlow<EditorTarget?> = _editorTarget

    fun openNewRule() = setEditorTarget(EditorTarget.New)

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
    }
}

/** A SIM the rule editor can target. */
data class SimOptionUi(
    val ref: SimRef,
    val label: String,
    val active: Boolean,
)

/** A country the rule editor can match. */
data class CountryOptionUi(
    val regionCode: String,
    val label: String,
    /** Normalized names/codes/aliases this country is found by; see [matches]. */
    val searchTerms: List<String> = emptyList(),
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

/** The localized country name alone, e.g. "Australia". */
internal fun countryDisplayName(regionCode: String): String {
    val region = regionCode.uppercase()
    return Locale("", region).displayCountry.ifBlank { region }
}

internal fun Rule.toRow(activeSims: List<ActiveSim>): RuleRowUi {
    val matcherLabel = when (val m = matcher) {
        RuleMatcher.AnyDestination -> null
        is RuleMatcher.Country -> countryLabel(m.regionCode)
    }
    return when (val a = action) {
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
            RuleRowUi(matcherLabel, ActionUi.HandOffApp(a.packageName))

        RuleAction.Ask -> RuleRowUi(matcherLabel, ActionUi.Ask)

        RuleAction.SystemDefault -> RuleRowUi(matcherLabel, ActionUi.SystemDefault)
    }
}

/** "+61 Australia" — calling code + localized country name. Not for composition. */
internal fun countryLabel(regionCode: String): String {
    val region = regionCode.uppercase()
    val callingCode = PhoneNumberUtil.getInstance().getCountryCodeForRegion(region)
    val name = countryDisplayName(region)
    return if (callingCode > 0) "+$callingCode $name" else name
}
