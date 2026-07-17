package app.simmo.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class CountryDetectionTest {

    private val detector = PhoneNumberCountryDetector()

    private fun detect(number: String, defaultRegion: String = "AU"): CountryVerdict =
        detector.detect(number, defaultRegion)

    @Test
    fun `international format maps to its region regardless of default region`() {
        assertEquals(CountryVerdict.Country("AU"), detect("+61 412 345 678", defaultRegion = "US"))
        assertEquals(CountryVerdict.Country("US"), detect("+1 212 555 0123", defaultRegion = "AU"))
        assertEquals(CountryVerdict.Country("GB"), detect("+44 20 7946 0958", defaultRegion = "AU"))
        assertEquals(CountryVerdict.Country("DE"), detect("+49 30 123456", defaultRegion = "AU"))
    }

    /**
     * The +1 NANP group must split by area code (TODO.md Phase 1): rules like
     * "US → T-Mobile" must not fire for Canadian or Caribbean numbers.
     */
    @Test
    fun `NANP countries sharing plus-one split by area code`() {
        assertEquals(CountryVerdict.Country("US"), detect("+1 212 555 0123")) // New York
        assertEquals(CountryVerdict.Country("US"), detect("+1 650 253 0000")) // California
        assertEquals(CountryVerdict.Country("CA"), detect("+1 416 555 0123")) // Toronto
        assertEquals(CountryVerdict.Country("CA"), detect("+1 604 555 0123")) // Vancouver
        // Caribbean rows use libphonenumber's example numbers: the fictional
        // 555-01XX pattern is not valid under every island's metadata.
        assertEquals(CountryVerdict.Country("JM"), detect("+1 876 210 1234")) // Jamaica
        assertEquals(CountryVerdict.Country("TT"), detect("+1 868 291 1234")) // Trinidad and Tobago
        assertEquals(CountryVerdict.Country("BS"), detect("+1 242 359 1234")) // Bahamas
    }

    @Test
    fun `national format numbers resolve against the default region`() {
        assertEquals(CountryVerdict.Country("AU"), detect("0412 345 678", defaultRegion = "AU"))
        assertEquals(CountryVerdict.Country("AU"), detect("(02) 9374 4000", defaultRegion = "AU"))
        assertEquals(CountryVerdict.Country("US"), detect("(212) 555-0123", defaultRegion = "US"))
    }

    @Test
    fun `same national digits follow the default region`() {
        // A traveler's saved local number means something different per region;
        // the default region decides, per SPEC "Country detection".
        assertEquals(CountryVerdict.Country("US"), detect("212 555 0123", defaultRegion = "US"))
    }

    @Test
    fun `lower-case default region from telephony apis is normalized`() {
        // TelephonyManager reports regions lower-case ("au"); rules and
        // libphonenumber use upper-case ISO codes.
        assertEquals(CountryVerdict.Country("AU"), detect("0412 345 678", defaultRegion = "au"))
        assertEquals(CountryVerdict.Emergency, detect("000", defaultRegion = " au "))
    }

    @Test
    fun `warm-up loads metadata for every region without breaking detection`() {
        detector.warmUp()
        assertEquals(CountryVerdict.Country("GB"), detect("+44 20 7946 0958"))
    }

    @Test
    fun `emergency numbers are flagged, never a country`() {
        assertEquals(CountryVerdict.Emergency, detect("000", defaultRegion = "AU"))
        assertEquals(CountryVerdict.Emergency, detect("911", defaultRegion = "US"))
        assertEquals(CountryVerdict.Emergency, detect("112", defaultRegion = "DE"))
    }

    @Test
    fun `global service numbers are undetermined, not a country`() {
        // +800 international toll-free lives in libphonenumber's non-geographic
        // "001" entity; only the fallback rule should match it.
        assertEquals(CountryVerdict.Undetermined, detect("+800 1234 5678"))
    }

    @Test
    fun `ussd and short codes are undetermined`() {
        assertEquals(CountryVerdict.Undetermined, detect("*#06#"))
        assertEquals(CountryVerdict.Undetermined, detect("#100#"))
    }

    @Test
    fun `garbage and invalid numbers are undetermined`() {
        assertEquals(CountryVerdict.Undetermined, detect("not a number"))
        assertEquals(CountryVerdict.Undetermined, detect(""))
        // Parses, but no NANP region has area code 100.
        assertEquals(CountryVerdict.Undetermined, detect("+1 100 555 0123"))
    }
}
