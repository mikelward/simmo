package app.simmo.telecom

import app.simmo.domain.DecisionEngine
import app.simmo.domain.DecisionSnapshot
import app.simmo.domain.NumberCorrection
import app.simmo.domain.PlacedCall
import app.simmo.domain.ProceedReason
import app.simmo.domain.Verdict

/**
 * The service's one entry point into the domain, hardened per AGENTS.md
 * "Fast decision path": a missing snapshot (cold start, first load pending)
 * or any internal error degrades to an explicit "proceed unmodified" verdict —
 * the caller always gets an answer to send before the platform's cancel
 * deadline, never an exception.
 */
class RedirectionCoordinator(
    private val engine: DecisionEngine,
    private val snapshotProvider: () -> DecisionSnapshot?,
    private val nowMillis: () -> Long,
) {
    fun decide(call: PlacedCall): Verdict {
        val snapshot = try {
            snapshotProvider()
        } catch (_: RuntimeException) {
            null
        } ?: return Verdict.Proceed(ProceedReason.SNAPSHOT_UNAVAILABLE)
        return try {
            engine.decide(call, snapshot, nowMillis())
        } catch (_: RuntimeException) {
            Verdict.Proceed(ProceedReason.INTERNAL_ERROR)
        }
    }

    /**
     * A same-contact correction [decide] could neither confirm nor apply for
     * [call] (see [DecisionEngine.missedCorrection]); the caller offers it by
     * notification after responding. Same degradation as [decide]: a missing
     * snapshot or an internal error is just "nothing to offer".
     */
    fun missedCorrection(call: PlacedCall): NumberCorrection? = try {
        snapshotProvider()?.let { engine.missedCorrection(call, it, nowMillis()) }
    } catch (_: RuntimeException) {
        null
    }
}
