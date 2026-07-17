package app.simmo.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.simmo.SimmoApp
import app.simmo.domain.ActiveSim
import app.simmo.domain.Rule
import app.simmo.domain.RuleAction
import app.simmo.domain.RuleMatcher
import app.simmo.domain.SimResolution
import app.simmo.domain.resolveSim
import app.simmo.store.SimmoState
import com.google.i18n.phonenumbers.PhoneNumberUtil
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

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
class RulesViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as SimmoApp

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
    val name = Locale("", region).displayCountry.ifBlank { region }
    return if (callingCode > 0) "+$callingCode $name" else name
}
