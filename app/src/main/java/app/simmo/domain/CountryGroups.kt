package app.simmo.domain

/**
 * Seed data for the country groups shipped with Simmo (SPEC "Calling rules").
 * Every group is copied into persisted state once, where the user can rename it,
 * change its membership, or delete it. Rules retain these stable ids so existing
 * references point at the editable seeded copy.
 *
 * An id this table no longer knows simply contributes no regions (the rule's
 * other countries still match) — never an error on the decision path.
 */
object CountryGroups {

    /** The European Union and the EEA (which adds Iceland, Liechtenstein, Norway). */
    const val EU_EEA = "eu_eea"

    /**
     * The USA including all its territories. The states-only option is the
     * plain "United States" country entry (libphonenumber's US region is the
     * 50 states + D.C.), so no group exists for it.
     */
    const val USA_TERRITORIES = "usa_territories"

    /** North America: the USA (with territories), Canada, and Mexico. */
    const val NORTH_AMERICA = "north_america"

    /** The Caribbean countries sharing the +1 dialing code (the non-US NANP). */
    const val CARIBBEAN_NANP = "caribbean_nanp"

    /**
     * EU/EEA membership, as libphonenumber regions (last reviewed 2026-07):
     * the EU-27 and the three EEA EFTA states, plus the EU territories that
     * have their own region codes — Åland (AX) and France's outermost regions
     * (GP, MQ, GF, RE, YT, MF) are EU soil and dial differently from their
     * mainland — and Svalbard (SJ), kept so every +47 Norwegian number
     * behaves the same. Deliberately out, matching the label rather than any
     * carrier's roaming zone: Switzerland (EFTA but not EEA), the UK,
     * Ukraine/Moldova (in the EU *roaming* area since 2026 but not EU/EEA),
     * the microstates (AD, MC, SM, VA), Gibraltar, the Faroes/Greenland, and
     * the Caribbean parts of the Kingdom of the Netherlands. Users add such
     * countries to a rule alongside the group when their plan covers them.
     */
    private val euEea: List<String> = listOf(
        // EU-27.
        "AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR",
        "DE", "GR", "HU", "IE", "IT", "LV", "LT", "LU", "MT", "NL",
        "PL", "PT", "RO", "SK", "SI", "ES", "SE",
        // EEA EFTA.
        "IS", "LI", "NO",
        // EU territories with their own region codes.
        "AX", "GP", "MQ", "GF", "RE", "YT", "MF",
        // Svalbard: part of Norway, own libphonenumber region within +47.
        "SJ",
    )

    /**
     * The USA with every territory. Each territory is its own libphonenumber
     * region within +1, so a plain "US" country rule misses them all. The
     * territory inclusion is explicit in the group's name ("USA +
     * territories") because it isn't uniformly free: PR/VI rate as domestic
     * on every plan, but some prepaid tiers bill the Pacific territories
     * (GU/AS/MP) internationally (flagged by Codex on PR #17) — a user whose
     * plan excludes them picks the plain United States country entry and
     * hand-adds PR/VI instead. The Caribbean +1 countries are a different
     * thing again; they dial like domestic but bill internationally
     * everywhere ([caribbeanNanp]).
     */
    private val usaTerritories: List<String> = listOf(
        "US",
        // US territories, each its own region within +1.
        "PR", "VI", "GU", "AS", "MP",
    )

    /**
     * [usaTerritories] plus Canada and Mexico — the mainstream postpaid
     * "North America" plan footprint. Kept separate because CA/MX inclusion
     * is a plan tier: many prepaid/MVNO tiers are domestic-only.
     */
    private val northAmerica: List<String> = usaTerritories + listOf("CA", "MX")

    /**
     * The rest of the North American Numbering Plan: Caribbean countries that
     * dial like a domestic +1 call but bill internationally — the classic
     * accidental-expensive-call surface. As a group it enables the guard
     * shape "Caribbean +1 → Ask" placed above a US rule.
     */
    private val caribbeanNanp: List<String> = listOf(
        "AG", "AI", "BB", "BM", "BS", "DM", "DO", "GD", "JM", "KN",
        "KY", "LC", "MS", "SX", "TC", "TT", "VC", "VG",
    )

    private val membersById: Map<String, List<String>> = mapOf(
        EU_EEA to euEea,
        USA_TERRITORIES to usaTerritories,
        NORTH_AMERICA to northAmerica,
        CARIBBEAN_NANP to caribbeanNanp,
    )

    /** Member regions of [groupId]; empty for ids this version doesn't know. */
    fun members(groupId: String): List<String> = membersById[groupId].orEmpty()

    /** Every group this version offers, in picker display order. */
    fun allIds(): List<String> = listOf(EU_EEA, USA_TERRITORIES, NORTH_AMERICA, CARIBBEAN_NANP)

    /** Fresh editable copies, in the order shown on the Country groups screen. */
    fun preseededGroups(): List<CustomGroup> = listOf(
        CustomGroup(EU_EEA, "EU/EEA", euEea),
        CustomGroup(USA_TERRITORIES, "USA + territories", usaTerritories),
        CustomGroup(NORTH_AMERICA, "North America", northAmerica),
        CustomGroup(CARIBBEAN_NANP, "Caribbean +1", caribbeanNanp),
    )

    /**
     * The shipped default for [id] — its original name and members — or null if
     * [id] is not a shipped group. Backs "restore to default" on the Country
     * groups screen, which resets an edited seed or re-adds a deleted one.
     */
    fun preseededGroup(id: String): CustomGroup? = preseededGroups().firstOrNull { it.id == id }
}
