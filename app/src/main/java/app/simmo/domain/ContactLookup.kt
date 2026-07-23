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
 * One indexed number of a contact, with its calling region resolved when the
 * index was built — so the decision path never parses regions live.
 */
data class ContactNumber(
    val e164: String,
    /** Uppercase ISO region, or null when the region couldn't be resolved. */
    val region: String?,
)

/** One contact's identity as the correction lookup needs it. */
data class IndexedContact(
    val displayName: String,
    val numbers: List<ContactNumber>,
)

/**
 * A same-contact number correction (SPEC "Hands-free and Android Auto
 * safeguards"): the dialed number's owning contact(s) also have local-region
 * numbers, listed as [candidates]. [sharedLine] is true when the dialed
 * number itself is listed by more than one contact — such a correction may
 * only ever be *offered* (the chooser labels each candidate with its
 * contact), never applied silently: whose local number to call is the
 * user's guess to make. Serializable so the chooser's confirmation flow can
 * carry it in a launch intent.
 */
@Serializable
data class NumberCorrection(
    val candidates: List<CorrectionCandidate>,
    val sharedLine: Boolean = false,
)

/** One local number [number] (E.164) belonging to [contactName]. */
@Serializable
data class CorrectionCandidate(
    val contactName: String,
    val number: String,
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
class ContactNumberIndex(
    private val byE164: Map<String, ContactMatch>,
    /** Every contact (by lookupKey) with its numbers, regions pre-resolved. */
    private val contactsByKey: Map<String, IndexedContact> = emptyMap(),
    /** The contacts (lookupKeys) listing each E.164 — a shared line has several. */
    private val ownersByE164: Map<String, List<String>> = emptyMap(),
) {

    /** The contact [dialedNumber] belongs to, or null if it matches none. */
    fun lookup(
        dialedNumber: String,
        defaultRegion: String,
        util: PhoneNumberUtil = PhoneNumberUtil.getInstance(),
    ): ContactMatch? {
        val e164 = normalizeToE164(dialedNumber, defaultRegion, util) ?: return null
        return byE164[e164]
    }

    /**
     * Same-contact number correction (SPEC "Hands-free and Android Auto
     * safeguards"): when [dialedNumber] belongs to contact(s), their distinct
     * *other* numbers local to [defaultRegion], each labeled with its
     * contact; null when the number matches no contact or no owner has a
     * local alternative. A line shared by several contacts is flagged
     * ([NumberCorrection.sharedLine]) so callers only ever *offer* the
     * correction, never apply it silently — whose local number to call is
     * the user's guess to make (Codex on PR #41; maintainer: confirm-only).
     * Regions were resolved at build time, so beyond the same warm E.164
     * parse [lookup] does, this is an in-memory filter — safe on the
     * decision path.
     */
    fun localCorrectionFor(
        dialedNumber: String,
        defaultRegion: String,
        util: PhoneNumberUtil = PhoneNumberUtil.getInstance(),
    ): NumberCorrection? {
        val e164 = normalizeToE164(dialedNumber, defaultRegion, util) ?: return null
        if (e164 !in byE164) return null
        val region = defaultRegion.trim().uppercase()
        val owners = ownersByE164[e164].orEmpty()
        val candidates = owners.flatMap { key ->
            val contact = contactsByKey[key] ?: return@flatMap emptyList()
            contact.numbers
                .filter { it.e164 != e164 && region == it.region }
                .map { CorrectionCandidate(contact.displayName, it.e164) }
        }.distinct()
        if (candidates.isEmpty()) return null
        return NumberCorrection(candidates, sharedLine = owners.size > 1)
    }

    val size: Int get() = byE164.size

    /**
     * The calling regions of the indexed contacts, most-common first (ties broken
     * by region code so the order is stable). Backs the country picker's
     * "Suggested" bucket, surfacing the countries the user actually knows people
     * in. Counts *distinct contacts* per region, not numbers: a person with
     * several numbers in one country counts once, so a single multi-number
     * contact can't outrank a country that more people are actually in. Parses
     * each E.164 key's region — metadata work, so call off the main thread; never
     * on the decision path.
     */
    fun regionsByContactCount(
        util: PhoneNumberUtil = PhoneNumberUtil.getInstance(),
    ): List<String> {
        val contactsByRegion = LinkedHashMap<String, MutableSet<String>>()
        for (e164 in byE164.keys) {
            // Keys are already E.164 (leading +), so no default region is needed.
            val region = runCatching { util.getRegionCodeForNumber(util.parse(e164, null)) }
                .getOrNull()
                ?.takeUnless { it.isBlank() || it == "ZZ" }
                ?: continue
            // A number can belong to several contacts (a household landline).
            // [byE164] deliberately keeps only the first contact for lookup,
            // but suggestions count people, so use the complete owner index.
            contactsByRegion.getOrPut(region) { HashSet() }.addAll(ownersByE164[e164].orEmpty())
        }
        return contactsByRegion.entries
            .sortedWith(compareByDescending<Map.Entry<String, Set<String>>> { it.value.size }.thenBy { it.key })
            .map { it.key }
    }

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
    // Per contact, every normalized number with its region resolved now —
    // same-contact correction must not parse regions on the decision path.
    // Kept independent of the byE164 first-wins dedup: a contact's numbers
    // stay theirs even when another contact claimed the same line's slot.
    val numbersByContact = LinkedHashMap<String, MutableList<ContactNumber>>()
    val namesByContact = HashMap<String, String>()
    val ownersByE164 = LinkedHashMap<String, MutableList<String>>()
    for (row in numbers) {
        val e164 = normalizeToE164(row.number, defaultRegion, util) ?: continue
        namesByContact.putIfAbsent(row.lookupKey, row.displayName)
        val contactNumbers = numbersByContact.getOrPut(row.lookupKey) { ArrayList() }
        if (contactNumbers.none { it.e164 == e164 }) {
            val region = runCatching { util.getRegionCodeForNumber(util.parse(e164, null)) }
                .getOrNull()
                ?.takeUnless { it.isBlank() || it == "ZZ" }
            contactNumbers += ContactNumber(e164, region)
            // Distinct owners per line, so a shared number is detectable and
            // correction can label candidates — or refuse to apply silently.
            ownersByE164.getOrPut(e164) { ArrayList() } += row.lookupKey
        }
        if (byE164.containsKey(e164)) continue
        byE164[e164] = ContactMatch(row.lookupKey, row.displayName, actionsByE164[e164].orEmpty())
    }
    val contactsByKey = numbersByContact.entries.associate { (key, contactNumbers) ->
        key to IndexedContact(namesByContact.getValue(key), contactNumbers)
    }
    return ContactNumberIndex(byE164, contactsByKey, ownersByE164)
}
