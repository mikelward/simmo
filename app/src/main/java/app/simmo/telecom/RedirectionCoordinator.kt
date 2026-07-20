package app.simmo.telecom

import app.simmo.SimmoDebugLog
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
    /**
     * The verdict plus anything to surface after responding, computed from a
     * single snapshot read — a telephony refresh landing between two separate
     * reads could otherwise reclassify the correction against a target set
     * the decision never saw and swallow the promised offer (Codex on
     * PR #44).
     */
    data class CallDecision(
        val verdict: Verdict,
        /**
         * A same-contact correction the decision could neither confirm nor
         * apply (see [DecisionEngine.missedCorrection]); the caller offers it
         * by notification after responding. Null on degraded decisions —
         * a missing snapshot or an internal error is "nothing to offer".
         */
        val missedCorrection: NumberCorrection? = null,
    )

    fun decide(call: PlacedCall): CallDecision {
        val snapshot = try {
            snapshotProvider()
        } catch (e: RuntimeException) {
            SimmoDebugLog.warning("Snapshot read failed; proceeding unmodified", e)
            null
        } ?: run {
            SimmoDebugLog.event("Snapshot unavailable (not warm yet); proceeding unmodified")
            return CallDecision(Verdict.Proceed(ProceedReason.SNAPSHOT_UNAVAILABLE))
        }
        return try {
            CallDecision(
                verdict = engine.decide(call, snapshot, nowMillis()),
                missedCorrection = engine.missedCorrection(call, snapshot, nowMillis()),
            )
        } catch (e: RuntimeException) {
            SimmoDebugLog.warning("Decision threw; proceeding unmodified", e)
            CallDecision(Verdict.Proceed(ProceedReason.INTERNAL_ERROR))
        }
    }
}
