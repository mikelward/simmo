package app.simmo.domain

import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.ShortNumberInfo

/** Where a dialed number is going, as far as routing rules care (SPEC "Country detection"). */
sealed interface CountryVerdict {
    /** ISO 3166-1 alpha-2 region the number belongs to, e.g. "AU", "US", "CA". */
    data class Country(val regionCode: String) : CountryVerdict

    /** Short codes, USSD, malformed input — matches only the fallback rule. */
    data object Undetermined : CountryVerdict

    /** Emergency numbers are never touched; the engine short-circuits to Proceed. */
    data object Emergency : CountryVerdict
}

fun interface CountryDetector {
    fun detect(dialedNumber: String, defaultRegion: String): CountryVerdict
}

/**
 * libphonenumber-backed detector. Countries sharing a calling code (the +1 NANP
 * group) are split by area code via [PhoneNumberUtil.getRegionCodeForNumber];
 * national-format numbers are interpreted against [detect]'s default region.
 *
 * The emergency check here is defense in depth over the raw dialed string; the
 * platform never consults the redirection service for numbers *it* knows are
 * emergency, and the platform's own classification always wins.
 *
 * Construction touches no metadata; libphonenumber loads per-region tables on
 * first use, so the platform layer must run [warmUp] off the decision path
 * (AGENTS.md "Fast decision path").
 */
class PhoneNumberCountryDetector(
    private val util: PhoneNumberUtil = PhoneNumberUtil.getInstance(),
    private val shortNumbers: ShortNumberInfo = ShortNumberInfo.getInstance(),
) : CountryDetector {
    override fun detect(dialedNumber: String, defaultRegion: String): CountryVerdict {
        // Android telephony APIs report regions lower-case ("au"); libphonenumber
        // expects upper-case ISO codes. Normalize at this boundary so no caller
        // has to remember.
        val region = defaultRegion.trim().uppercase()
        if (shortNumbers.isEmergencyNumber(dialedNumber, region)) {
            return CountryVerdict.Emergency
        }
        val parsed = try {
            util.parse(dialedNumber, region)
        } catch (_: NumberParseException) {
            return CountryVerdict.Undetermined
        }
        if (!util.isValidNumber(parsed)) return CountryVerdict.Undetermined
        val numberRegion = util.getRegionCodeForNumber(parsed) ?: return CountryVerdict.Undetermined
        // Global service numbers (+800 international toll-free etc.) resolve to
        // libphonenumber's non-geographic "001" entity — not a country, so only
        // the fallback rule can match them.
        if (numberRegion == NON_GEO_ENTITY) return CountryVerdict.Undetermined
        return CountryVerdict.Country(numberRegion)
    }

    /**
     * Force-load the phone and short-number metadata for every supported region,
     * so no [detect] call during a live decision loads parser tables — a call can
     * be placed toward any country regardless of which rules exist. One-time cost
     * of a few MB / well under a second; run it off the decision path at startup.
     */
    fun warmUp() {
        for (region in util.supportedRegions) {
            util.getExampleNumber(region)
            shortNumbers.isEmergencyNumber("112", region)
        }
        // Non-geographic entities (+800 etc.) are excluded from supportedRegions
        // but their metadata still lazy-loads on first validation of such a number.
        for (callingCode in util.supportedGlobalNetworkCallingCodes) {
            util.getExampleNumberForNonGeoEntity(callingCode)
        }
    }

    private companion object {
        /** libphonenumber's region code for non-geographic numbering plans. */
        const val NON_GEO_ENTITY = "001"
    }
}
