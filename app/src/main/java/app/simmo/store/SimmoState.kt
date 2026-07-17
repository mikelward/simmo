package app.simmo.store

import app.simmo.domain.RegisteredSim
import app.simmo.domain.RuleBook
import kotlinx.serialization.Serializable

/**
 * Everything Simmo persists (SPEC "Architecture"): the rules and the SIM
 * registry, plus the optional default-region override. Dialed numbers are
 * never part of this state. Included in Android backups / device transfers by
 * the maintainer's decision — see the backup rules in `res/xml/` and SPEC
 * "Permissions and privacy".
 */
@Serializable
data class SimmoState(
    val rules: RuleBook = RuleBook(),
    val simRegistry: List<RegisteredSim> = emptyList(),
    /** Overrides the network/SIM-derived default region when set (ISO code). */
    val defaultRegionOverride: String? = null,
)
