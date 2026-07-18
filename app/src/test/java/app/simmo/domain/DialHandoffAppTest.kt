package app.simmo.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class DialHandoffAppTest {

    // The '+' must be encoded as %2B in every template — a literal '+' in these
    // query/path values decodes to a space (docs/handoff-intents.md).

    @Test
    fun `Google Voice deep link is a new-call link with the plus encoded`() {
        assertEquals(
            "https://voice.google.com/calls?a=nc,%2B12125550123",
            DialHandoffApp.GOOGLE_VOICE.launchUri("+12125550123"),
        )
    }

    @Test
    fun `Teams deep link uses the PSTN MRI prefix with the plus encoded`() {
        assertEquals(
            "msteams://teams.microsoft.com/l/call/0/0?users=4:%2B12125550123",
            DialHandoffApp.TEAMS.launchUri("+12125550123"),
        )
    }
}
