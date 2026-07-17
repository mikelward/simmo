package app.simmo.domain

/**
 * A call the chooser ended while a rule's wanted SIM was disabled (SPEC
 * "Disabled-SIM assist" step 3): kept in memory so that when the wanted SIM
 * becomes active, Simmo can offer — never auto-place — the call again.
 * Process death drops it, which is fine: the offer's useful window is the
 * minutes it takes to flip a SIM on in Settings.
 */
data class HeldCall(
    /** The original call handle URI (tel:...), re-offered verbatim. */
    val handleUri: String,
    /** The disabled SIMs the skipped rules wanted. */
    val wantedSims: List<SimRef>,
    val parkedAtMillis: Long,
) {
    fun expired(nowMillis: Long): Boolean = nowMillis - parkedAtMillis > TTL_MILLIS

    /**
     * The wanted SIM that is now active and unambiguous, if any — the moment
     * the "place the call?" offer becomes possible.
     */
    fun activatedWantedSim(activeSims: List<ActiveSim>): ActiveSim? =
        wantedSims.firstNotNullOfOrNull { wanted ->
            (resolveSim(wanted, activeSims) as? SimResolution.Active)?.sim
        }

    companion object {
        /**
         * How long the offer stays relevant. Enabling an eSIM takes moments;
         * an offer resurfacing an hour later would re-place a call the user
         * has long moved past.
         */
        const val TTL_MILLIS: Long = 15 * 60 * 1000
    }
}
