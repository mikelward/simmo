package app.simmo.telecom

/**
 * Tracks each network's last-seen roaming state so only a *transition*
 * triggers work (SPEC "Data-roaming visibility"): capability callbacks fire
 * for many reasons — validation, metering, suspension — and the resident
 * watch only cares about the moment `NOT_ROAMING` flips. The first sighting
 * of a network is not a transition: the arrival that created it already ran
 * a check via the wake lattice. Keyed generically so the logic tests without
 * Android's `Network` type.
 */
internal class RoamingTransitions<K : Any> {
    private val lastNotRoaming = HashMap<K, Boolean>()

    /** Records [notRoaming] for [key]; true only when it changed a known state. */
    @Synchronized
    fun isTransition(key: K, notRoaming: Boolean): Boolean {
        val previous = lastNotRoaming.put(key, notRoaming)
        return previous != null && previous != notRoaming
    }

    /** Drops [key] so a later reappearance counts as a fresh first sighting. */
    @Synchronized
    fun forget(key: K) {
        lastNotRoaming.remove(key)
    }
}
