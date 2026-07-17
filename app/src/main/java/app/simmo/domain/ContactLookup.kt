package app.simmo.domain

import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat
import kotlinx.serialization.Serializable

/**
 * An app that can place a call to a *contact* (not an arbitrary number) via a
 * per-contact intent — the app-to-app hand-off case (SPEC "Hand-off to another
 * app"; `docs/handoff-intents.md`). [packageName] targets the launch intent and
 * [dataMimeType] is the `ContactsContract.Data` MIME type the app registers for
 * a callable contact (the platform reader keys off it); [label] is the
 * user-facing name. Serializable because a rule's action stores which app.
 * Extend as more app-to-app targets are verified.
 */
@Serializable
enum class ContactCallApp(
    val packageName: String,
    val dataMimeType: String,
    val label: String,
) {
    WHATSAPP("com.whatsapp", "vnd.android.cursor.item/vnd.com.whatsapp.voip.call", "WhatsApp"),
}

/**
 * A contact a dialed number belongs to, with the app-to-app call actions
 * available for it. [callActions] maps each capable app to the
 * `ContactsContract.Data` row id to `ACTION_VIEW` when launching that app's
 * call — so the hand-off can be built later without another contacts query.
 */
data class ContactMatch(
    val lookupKey: String,
    val displayName: String,
    val callActions: Map<ContactCallApp, Long>,
)

/**
 * One raw contact phone row as read from the platform, before normalization:
 * a single number belonging to a contact. The reader emits one per (contact,
 * number); the builder normalizes and de-dups them.
 */
data class RawContactNumber(
    val lookupKey: String,
    val displayName: String,
    val number: String,
)

/**
 * One raw app-to-app call action, before normalization: the specific [number]
 * an app (e.g. WhatsApp) registered as callable, and the `ContactsContract.Data`
 * row id to `ACTION_VIEW` for it. Keyed to the *number* — not the aggregate
 * contact — so a contact's other (non-app) numbers are never treated as
 * app-callable.
 */
data class RawCallAction(
    val app: ContactCallApp,
    val number: String,
    val dataRowId: Long,
)

/**
 * Normalizes [number] to E.164 (`+<cc><national>`) against [defaultRegion], or
 * null when it can't be parsed to a valid number. Contacts store numbers in
 * every format (`0412…`, `(04) 1234…`, `+61…`); keying the index by E.164 lets
 * a dialed number in any format match. Not for the decision path directly —
 * this parses metadata; build the index off the main thread.
 */
internal fun normalizeToE164(
    number: String,
    defaultRegion: String,
    util: PhoneNumberUtil = PhoneNumberUtil.getInstance(),
): String? = try {
    val parsed = util.parse(number, defaultRegion.trim().uppercase())
    if (util.isValidNumber(parsed)) util.format(parsed, PhoneNumberFormat.E164) else null
} catch (_: NumberParseException) {
    null
}

/**
 * The warm, in-memory index from a dialed number to the contact it belongs to
 * (SPEC "Same-contact number correction"; app-to-app hand-off). Built off the
 * decision path — contacts IPC is slow and needs `READ_CONTACTS` — so the
 * decision path only does the in-memory [lookup].
 */
class ContactNumberIndex(private val byE164: Map<String, ContactMatch>) {

    /** The contact [dialedNumber] belongs to, or null if it matches none. */
    fun lookup(
        dialedNumber: String,
        defaultRegion: String,
        util: PhoneNumberUtil = PhoneNumberUtil.getInstance(),
    ): ContactMatch? {
        val e164 = normalizeToE164(dialedNumber, defaultRegion, util) ?: return null
        return byE164[e164]
    }

    val size: Int get() = byE164.size

    companion object {
        val EMPTY = ContactNumberIndex(emptyMap())
    }
}

/**
 * Builds a [ContactNumberIndex] from raw contact numbers and app-to-app call
 * actions, keyed by E.164. Each call action attaches only to the number it
 * actually calls (matched by E.164), so on a multi-number contact only the line
 * a given app knows is offered for that app — never its other numbers. Numbers
 * that don't normalize are dropped (short codes, malformed entries); when a line
 * appears twice, the first contact identity wins.
 */
fun buildContactNumberIndex(
    numbers: List<RawContactNumber>,
    callActions: List<RawCallAction>,
    defaultRegion: String,
    util: PhoneNumberUtil = PhoneNumberUtil.getInstance(),
): ContactNumberIndex {
    val actionsByE164 = HashMap<String, MutableMap<ContactCallApp, Long>>()
    for (action in callActions) {
        val e164 = normalizeToE164(action.number, defaultRegion, util) ?: continue
        actionsByE164.getOrPut(e164) { LinkedHashMap() }[action.app] = action.dataRowId
    }
    val byE164 = LinkedHashMap<String, ContactMatch>()
    for (row in numbers) {
        val e164 = normalizeToE164(row.number, defaultRegion, util) ?: continue
        if (byE164.containsKey(e164)) continue
        byE164[e164] = ContactMatch(row.lookupKey, row.displayName, actionsByE164[e164].orEmpty())
    }
    return ContactNumberIndex(byE164)
}
