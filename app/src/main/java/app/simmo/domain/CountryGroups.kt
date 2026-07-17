package app.simmo.domain

/**
 * Country groups a rule can match as a single entry (SPEC "Rules"). Rules
 * persist the group *id*; membership is resolved here at decision time, so
 * it tracks app updates instead of being frozen into stored rules — and the
 * decision path resolves it from this in-memory table, never from I/O.
 *
 * An id this table no longer knows simply contributes no regions (the rule's
 * other countries still match) — never an error on the decision path.
 */
object CountryGroups {

    /** The European Union and the EEA (which adds Iceland, Liechtenstein, Norway). */
    const val EU_EEA = "eu_eea"

    /** The USA — including its territories, which every US plan rates as domestic calls. */
    const val USA = "usa"

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
     * The USA as plans treat it: the states plus the US territories, which
     * every domestic calling plan rates as domestic because they're inside
     * the NANP — but which libphonenumber splits off as their own regions,
     * so a plain "US" country rule silently misses a call to Puerto Rico.
     * The Caribbean +1 countries are NOT the same thing; they dial like
     * domestic but bill internationally ([caribbeanNanp]).
     */
    private val usa: List<String> = listOf(
        "US",
        // US territories, each its own region within +1.
        "PR", "VI", "GU", "AS", "MP",
    )

    /**
     * [usa] plus Canada and Mexico — the mainstream postpaid "North America"
     * plan footprint. Kept separate from [usa] because CA/MX inclusion is a
     * plan tier: many prepaid/MVNO tiers are domestic-only.
     */
    private val northAmerica: List<String> = usa + listOf("CA", "MX")

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
        USA to usa,
        NORTH_AMERICA to northAmerica,
        CARIBBEAN_NANP to caribbeanNanp,
    )

    /** Member regions of [groupId]; empty for ids this version doesn't know. */
    fun members(groupId: String): List<String> = membersById[groupId].orEmpty()

    /** Every group this version offers, in picker display order. */
    fun allIds(): List<String> = listOf(EU_EEA, USA, NORTH_AMERICA, CARIBBEAN_NANP)
}
