package app.simmo.telecom

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [phoneNumberOrEmpty] backs `TelephonyReader.readPhoneNumber` — the
 * now-unconditional `SubscriptionManager.getPhoneNumber` path at minSdk 34. A
 * readable number passes through unchanged; a denied READ_PHONE_NUMBERS grant
 * (SecurityException) or a transient telephony error (IllegalStateException)
 * degrades to "" so the SIM keeps a number-less row rather than dropping out of
 * the snapshot. Anything else must propagate — the catch stays narrow.
 */
class PhoneNumberDegradationTest {

    @Test
    fun `a readable number passes through`() {
        assertEquals("+15550001111", phoneNumberOrEmpty { "+15550001111" })
    }

    @Test
    fun `an empty number passes through unchanged`() {
        assertEquals("", phoneNumberOrEmpty { "" })
    }

    @Test
    fun `a denied READ_PHONE_NUMBERS grant degrades to empty`() {
        assertEquals("", phoneNumberOrEmpty { throw SecurityException("READ_PHONE_NUMBERS denied") })
    }

    @Test
    fun `a transient telephony error degrades to empty`() {
        assertEquals("", phoneNumberOrEmpty { throw IllegalStateException("telephony unavailable") })
    }

    @Test(expected = RuntimeException::class)
    fun `an unexpected error is not swallowed`() {
        phoneNumberOrEmpty { throw RuntimeException("unexpected") }
    }
}
