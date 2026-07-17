package app.simmo.telecom

import app.simmo.domain.HeldCall

/**
 * At most one in-memory held call (SPEC "Disabled-SIM assist" step 3): the
 * newest chooser visit with a wanted-but-disabled SIM wins — a fresher call
 * attempt supersedes an older one. Consumed when its offer notification
 * posts, cleared when a call is placed, and dropped silently on expiry.
 */
class HeldCallStore {

    @Volatile
    private var held: HeldCall? = null

    fun park(call: HeldCall) {
        held = call
    }

    fun clear() {
        held = null
    }

    /** The live held call, dropping an expired one on the way. */
    fun current(nowMillis: Long): HeldCall? {
        val call = held ?: return null
        if (call.expired(nowMillis)) {
            held = null
            return null
        }
        return call
    }
}
