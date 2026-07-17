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
 * first use, so the platform layer must warm this off the decision path
 * (AGENTS.md "Fast decision path").
 */
class PhoneNumberCountryDetector(
    private val util: PhoneNumberUtil = PhoneNumberUtil.getInstance(),
    private val shortNumbers: ShortNumberInfo = ShortNumberInfo.getInstance(),
) : CountryDetector {
    override fun detect(dialedNumber: String, defaultRegion: String): CountryVerdict {
        if (shortNumbers.isEmergencyNumber(dialedNumber, defaultRegion)) {
            return CountryVerdict.Emergency
        }
        val parsed = try {
            util.parse(dialedNumber, defaultRegion)
        } catch (_: NumberParseException) {
            return CountryVerdict.Undetermined
        }
        if (!util.isValidNumber(parsed)) return CountryVerdict.Undetermined
        val region = util.getRegionCodeForNumber(parsed) ?: return CountryVerdict.Undetermined
        return CountryVerdict.Country(region)
    }

    /** Force-load the metadata for [defaultRegion] plus the NANP table; see class KDoc. */
    fun warmUp(defaultRegion: String) {
        detect("+12025550123", defaultRegion)
        detect("0", defaultRegion)
    }
}
