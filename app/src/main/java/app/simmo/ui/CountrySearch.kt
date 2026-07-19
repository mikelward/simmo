package app.simmo.ui

import app.simmo.domain.CountryGroups
import java.text.Normalizer
import java.util.Locale

/**
 * Extra strings users are likely to type for a country that its localized name
 * and ISO codes don't already cover. Keyed by ISO alpha-2 region code; extend
 * as needed. (Names, alpha-2/alpha-3, and calling code are handled generically
 * in [countrySearchTerms] and don't belong here.)
 */
internal val countryAliases: Map<String, List<String>> = mapOf(
    "GB" to listOf("UK", "Britain", "Great Britain"),
    "US" to listOf("USA", "America", "United States of America"),
)

/**
 * Accent-stripped, alphanumerics-only form with **case preserved**, so an
 * uppercase query letter can be required to match an uppercase target letter
 * (see [fuzzyScore]). "Côte d'Ivoire" → "CotedIvoire", "+44" → "44",
 * "United Kingdom" → "UnitedKingdom".
 */
internal fun foldForSearch(raw: String): String {
    val decomposed = Normalizer.normalize(raw, Normalizer.Form.NFD)
    return buildString {
        for (c in decomposed) if (c.code < 128 && c.isLetterOrDigit()) append(c)
    }
}

/**
 * The strings a country is found by — its display name, ISO alpha-2 and alpha-3
 * codes, calling code, and any [countryAliases] — each [foldForSearch]-folded.
 * Precomputed once per option (off the main thread) so ranking is a cheap scan.
 */
internal fun countrySearchTerms(
    regionCode: String,
    displayName: String,
    callingCode: Int,
): List<String> {
    val region = regionCode.uppercase()
    val alpha3 = runCatching { Locale.Builder().setRegion(region).build().isO3Country }.getOrDefault("")
    return buildList {
        add(displayName)
        add(region)
        if (alpha3.isNotBlank()) add(alpha3)
        if (callingCode > 0) add(callingCode.toString())
        addAll(countryAliases[region].orEmpty())
    }.map { foldForSearch(it) }.filter { it.isNotEmpty() }.distinct()
}

/**
 * How well [query] fuzzy-matches [target], or null if [query] is not a
 * subsequence of it. An uppercase query letter matches only an uppercase target
 * letter (so "US" is an acronym match for "UnitedStates" but not "Australia"); a
 * lowercase query letter matches either case. Higher is better: matches at the
 * start, on CamelCase word boundaries, and on consecutive characters score more,
 * and a full prefix (and especially an exact term) ranks well above an
 * incidental interior match.
 */
private fun fuzzyScore(query: String, target: String): Int? {
    if (query.isEmpty()) return 0
    var qi = 0
    var score = 0
    var prevMatch = -2
    var isPrefix = true
    for (ti in target.indices) {
        if (qi >= query.length) break
        if (!charMatches(query[qi], target[ti])) continue
        var bonus = 1
        when {
            ti == 0 -> bonus += 8
            target[ti].isUpperCase() && target[ti - 1].isLowerCase() -> bonus += 6
        }
        if (ti == prevMatch + 1) bonus += 3
        if (if (qi == 0) ti != 0 else ti != prevMatch + 1) isPrefix = false
        score += bonus
        prevMatch = ti
        qi++
    }
    if (qi < query.length) return null
    if (isPrefix) {
        score += 20
        if (target.length == query.length) score += 100
    }
    return score
}

private fun charMatches(queryChar: Char, targetChar: Char): Boolean =
    if (queryChar.isUpperCase()) targetChar == queryChar
    else targetChar.lowercaseChar() == queryChar.lowercaseChar()

/**
 * The best fuzzy-match score of [query] against any of [terms] (each already
 * [foldForSearch]-folded), or null if none match. A blank query scores 0
 * (matches everything), so callers can rank without special-casing the empty
 * field.
 */
internal fun termsMatchScore(query: String, terms: List<String>): Int? {
    val folded = foldForSearch(query)
    if (folded.isEmpty()) return 0
    return terms.mapNotNull { fuzzyScore(folded, it) }.maxOrNull()
}

/** [termsMatchScore] over a country's precomputed search terms. */
internal fun countryMatchScore(query: String, option: CountryOptionUi): Int? =
    termsMatchScore(query, option.searchTerms)

/** Whether this option matches [query] at all; a blank query matches everything. */
internal fun CountryOptionUi.matches(query: String): Boolean =
    countryMatchScore(query, this) != null

/**
 * The options that match [query], best first (exact/prefix above incidental
 * matches); a blank query returns [options] unchanged (already name-sorted).
 * Ranks rather than hard-filters, so a lower-quality-but-valid match still
 * appears — just further down.
 */
internal fun rankCountries(options: List<CountryOptionUi>, query: String): List<CountryOptionUi> {
    if (foldForSearch(query).isEmpty()) return options
    return options
        .mapNotNull { option -> countryMatchScore(query, option)?.let { option to it } }
        .sortedByDescending { it.second } // stable: equal scores keep the name order
        .map { it.first }
}

/**
 * Extra strings a country group is found by, beyond its display label.
 * Keyed by [app.simmo.domain.CountryGroups] id.
 */
internal val countryGroupAliases: Map<String, List<String>> = mapOf(
    CountryGroups.EU_EEA to listOf("EU", "EEA", "Europe", "European Union"),
    CountryGroups.USA_TERRITORIES to listOf("USA", "United States", "US territories", "America", "Domestic"),
    CountryGroups.NORTH_AMERICA to listOf("North America", "NA", "USMCA", "NAFTA"),
    CountryGroups.CARIBBEAN_NANP to listOf("Caribbean", "West Indies", "NANP"),
)

/** A group's folded search terms: its label plus [countryGroupAliases]. */
internal fun countryGroupSearchTerms(groupId: String, label: String): List<String> =
    (listOf(label) + countryGroupAliases[groupId].orEmpty())
        .map { foldForSearch(it) }
        .filter { it.isNotEmpty() }
        .distinct()

/**
 * The groups the picker should show above [matchedCountries] for [query]: all
 * of them on a blank query; otherwise those whose own terms match, plus those
 * with a *member* among the matched countries — so searching "France" or
 * "+49" also suggests the EU/EEA group right where the tap happens.
 */
internal fun matchingGroups(
    groups: List<CountryGroupOptionUi>,
    query: String,
    matchedCountries: List<CountryOptionUi>,
): List<CountryGroupOptionUi> {
    if (foldForSearch(query).isEmpty()) return groups
    return groups.filter { group ->
        termsMatchScore(query, group.searchTerms) != null ||
            matchedCountries.any { it.regionCode.uppercase() in group.memberRegions }
    }
}
