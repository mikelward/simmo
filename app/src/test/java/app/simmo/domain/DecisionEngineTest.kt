package app.simmo.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class DecisionEngineTest {

    private val engine = DecisionEngine(PhoneNumberCountryDetector())

    private val telstra =
        ActiveSim(1, "Telstra", "Telstra AU", PhoneAccountRef("acct-telstra"), countryIso = "au")
    private val tmobile =
        ActiveSim(2, "T-Mobile", "T-Mobile US", PhoneAccountRef("acct-tmobile"), countryIso = "us")
    private val now = 1_000_000L

    private val auNumber = "+61 412 345 678"
    private val usNumber = "+1 212 555 0123"
    private val gbNumber = "+44 20 7946 0958"

    private fun ActiveSim.ref() = SimRef(subscriptionId, carrierName, displayName)

    private fun country(region: String, action: RuleAction) = Rule(RuleMatcher.Country(region), action)
    private fun any(action: RuleAction) = Rule(RuleMatcher.AnyDestination, action)

    private fun snapshot(
        rules: List<Rule>,
        activeSims: List<ActiveSim> = listOf(telstra, tmobile),
        passTokens: List<PassToken> = emptyList(),
        customGroups: Map<String, List<String>> = emptyMap(),
        handOffAccounts: Set<PhoneAccountRef> = emptySet(),
        handOffApps: Set<String> = emptySet(),
        contacts: ContactNumberIndex = ContactNumberIndex.EMPTY,
    ) = DecisionSnapshot(
        RuleBook(rules),
        activeSims,
        defaultRegion = "AU",
        passTokens = passTokens,
        customGroups = customGroups,
        handOffAccounts = handOffAccounts,
        handOffApps = handOffApps,
        contacts = contacts,
    )

    private fun whatsAppContact(number: String, dataRowId: Long) = buildContactNumberIndex(
        numbers = listOf(RawContactNumber("mum", "Mum", number)),
        callActions = listOf(RawCallAction(ContactCallApp.WHATSAPP, number, dataRowId)),
        defaultRegion = "AU",
    )

    private fun call(
        number: String,
        currentAccount: PhoneAccountRef? = tmobile.phoneAccount,
        interactive: Boolean = true,
    ) = PlacedCall(number, currentAccount, interactive)

    // --- Ordering and matching ---

    @Test
    fun `first matching applicable rule wins`() {
        val rules = listOf(
            country("AU", RuleAction.UseSim(telstra.ref())),
            any(RuleAction.SystemDefault),
        )
        assertEquals(
            Verdict.RedirectToAccount(telstra.phoneAccount),
            engine.decide(call(auNumber), snapshot(rules), now),
        )
    }

    @Test
    fun `a disabled rule is skipped even when it matches`() {
        // The AU rule would win, but the user turned it off, so evaluation
        // falls through to the next applicable rule.
        val rules = listOf(
            country("AU", RuleAction.UseSim(telstra.ref())).copy(enabled = false),
            any(RuleAction.SystemDefault),
        )
        assertEquals(
            Verdict.Proceed(ProceedReason.SYSTEM_DEFAULT),
            engine.decide(call(auNumber), snapshot(rules), now),
        )
    }

    // --- App-to-app (contact) hand-off ---

    private val whatsApp = RuleAction.HandOff.ViaContactApp(ContactCallApp.WHATSAPP)

    @Test
    fun `hand off to a contact app routes when the number is a contact on that app`() {
        val rules = listOf(any(whatsApp), any(RuleAction.SystemDefault))
        assertEquals(
            Verdict.ForwardToContactApp(
                "com.whatsapp",
                ContactCallApp.WHATSAPP.dataMimeType,
                7L,
                ContactCallApp.WHATSAPP.label,
            ),
            engine.decide(call(auNumber), snapshot(rules, contacts = whatsAppContact(auNumber, 7L)), now),
        )
    }

    @Test
    fun `hand off to a contact app skips when the number is not that contact`() {
        val rules = listOf(any(whatsApp), any(RuleAction.SystemDefault))
        assertEquals(
            Verdict.Proceed(ProceedReason.SYSTEM_DEFAULT),
            engine.decide(call(auNumber), snapshot(rules, contacts = whatsAppContact(usNumber, 7L)), now),
        )
    }

    @Test
    fun `hand off to a contact app skips with no contact index at all`() {
        val rules = listOf(any(whatsApp), any(RuleAction.SystemDefault))
        assertEquals(
            Verdict.Proceed(ProceedReason.SYSTEM_DEFAULT),
            engine.decide(call(auNumber), snapshot(rules), now),
        )
    }

    @Test
    fun `hand off to a contact app is skipped in a non-interactive context`() {
        val rules = listOf(any(whatsApp), any(RuleAction.SystemDefault))
        assertEquals(
            Verdict.Proceed(ProceedReason.SYSTEM_DEFAULT),
            engine.decide(
                call(auNumber, interactive = false),
                snapshot(rules, contacts = whatsAppContact(auNumber, 7L)),
                now,
            ),
        )
    }

    @Test
    fun `country rules do not match other destinations`() {
        val rules = listOf(
            country("AU", RuleAction.UseSim(telstra.ref())),
            any(RuleAction.SystemDefault),
        )
        assertEquals(
            Verdict.Proceed(ProceedReason.SYSTEM_DEFAULT),
            engine.decide(call(gbNumber), snapshot(rules), now),
        )
    }

    @Test
    fun `rule order decides between competing matches`() {
        val rules = listOf(
            any(RuleAction.SystemDefault),
            country("AU", RuleAction.UseSim(telstra.ref())),
        )
        // The any-destination rule sits first, so the AU rule never runs.
        assertEquals(
            Verdict.Proceed(ProceedReason.SYSTEM_DEFAULT),
            engine.decide(call(auNumber), snapshot(rules), now),
        )
    }

    @Test
    fun `multi-country rule matches any of its countries`() {
        val rules = listOf(
            Rule(RuleMatcher.Countries(listOf("AU", "US")), RuleAction.UseSim(telstra.ref())),
            any(RuleAction.SystemDefault),
        )
        assertEquals(
            Verdict.RedirectToAccount(telstra.phoneAccount),
            engine.decide(call(auNumber), snapshot(rules), now),
        )
        assertEquals(
            Verdict.RedirectToAccount(telstra.phoneAccount),
            engine.decide(call(usNumber), snapshot(rules), now),
        )
        // A destination outside the set falls through, same as a single-country miss.
        assertEquals(
            Verdict.Proceed(ProceedReason.SYSTEM_DEFAULT),
            engine.decide(call(gbNumber), snapshot(rules), now),
        )
    }

    @Test
    fun `country group rule matches members and only members`() {
        val frNumber = "+33 1 42 68 53 00"
        val rules = listOf(
            Rule(
                RuleMatcher.Countries(groupIds = listOf(CountryGroups.EU_EEA)),
                RuleAction.UseSim(telstra.ref()),
            ),
            any(RuleAction.SystemDefault),
        )
        assertEquals(
            Verdict.RedirectToAccount(telstra.phoneAccount),
            engine.decide(call(frNumber), snapshot(rules), now),
        )
        // The UK is not an EU/EEA member: falls through to the next rule.
        assertEquals(
            Verdict.Proceed(ProceedReason.SYSTEM_DEFAULT),
            engine.decide(call(gbNumber), snapshot(rules), now),
        )
    }

    @Test
    fun `a custom group rule matches its member countries`() {
        // "Vodafone Zone 1": a user-defined group resolved from the snapshot.
        val zone = "custom:1"
        val rules = listOf(
            Rule(RuleMatcher.Countries(groupIds = listOf(zone)), RuleAction.UseSim(telstra.ref())),
            any(RuleAction.SystemDefault),
        )
        val groups = mapOf(zone to listOf("FR", "GB"))
        assertEquals(
            Verdict.RedirectToAccount(telstra.phoneAccount),
            engine.decide(call(gbNumber), snapshot(rules, customGroups = groups), now),
        )
        // A number outside the group's members falls through.
        assertEquals(
            Verdict.Proceed(ProceedReason.SYSTEM_DEFAULT),
            engine.decide(call(usNumber), snapshot(rules, customGroups = groups), now),
        )
        // Group deleted (absent from the snapshot): contributes nothing, the
        // rule matches none of its members and falls through — never an error.
        assertEquals(
            Verdict.Proceed(ProceedReason.SYSTEM_DEFAULT),
            engine.decide(call(gbNumber), snapshot(rules), now),
        )
    }

    @Test
    fun `countries can sit alongside a group in one rule`() {
        // The "my plan also covers the UK" shape: EU/EEA plus GB.
        val rules = listOf(
            Rule(
                RuleMatcher.Countries(regionCodes = listOf("GB"), groupIds = listOf(CountryGroups.EU_EEA)),
                RuleAction.UseSim(telstra.ref()),
            ),
        )
        assertEquals(
            Verdict.RedirectToAccount(telstra.phoneAccount),
            engine.decide(call(gbNumber), snapshot(rules), now),
        )
    }

    @Test
    fun `usa group treats territory calls as domestic`() {
        // libphonenumber resolves +1 787 to PR, not US — the group is what
        // keeps a Puerto Rico call on the "domestic" rule.
        val rules = listOf(
            Rule(
                RuleMatcher.Countries(groupIds = listOf(CountryGroups.USA_TERRITORIES)),
                RuleAction.UseSim(tmobile.ref()),
            ),
        )
        assertEquals(
            Verdict.RedirectToAccount(tmobile.phoneAccount),
            engine.decide(
                call("+1 787 555 0123", currentAccount = telstra.phoneAccount),
                snapshot(rules),
                now,
            ),
        )
    }

    @Test
    fun `caribbean guard catches look-alike domestic calls`() {
        // The guard shape the group exists for: "Caribbean +1 → Ask" placed
        // above the US rule. A Jamaican number dials like a domestic call but
        // hits the guard; a real US call falls through to its own rule.
        val rules = listOf(
            Rule(RuleMatcher.Countries(groupIds = listOf(CountryGroups.CARIBBEAN_NANP)), RuleAction.Ask),
            Rule(
                RuleMatcher.Countries(groupIds = listOf(CountryGroups.USA_TERRITORIES)),
                RuleAction.UseSim(tmobile.ref()),
            ),
        )
        assertEquals(
            Verdict.OpenChooser(),
            engine.decide(call("+1 876 555 0123"), snapshot(rules), now),
        )
        assertEquals(
            Verdict.Proceed(ProceedReason.ALREADY_ON_TARGET),
            engine.decide(call(usNumber, currentAccount = tmobile.phoneAccount), snapshot(rules), now),
        )
    }

    @Test
    fun `unknown group ids contribute nothing and never error`() {
        val rules = listOf(
            Rule(RuleMatcher.Countries(groupIds = listOf("from_the_future")), RuleAction.UseSim(telstra.ref())),
            any(RuleAction.SystemDefault),
        )
        assertEquals(
            Verdict.Proceed(ProceedReason.SYSTEM_DEFAULT),
            engine.decide(call(auNumber), snapshot(rules), now),
        )
    }

    @Test
    fun `multi-country rule matches regions case-insensitively`() {
        val rules = listOf(
            Rule(RuleMatcher.Countries(listOf("au")), RuleAction.UseSim(telstra.ref())),
        )
        assertEquals(
            Verdict.RedirectToAccount(telstra.phoneAccount),
            engine.decide(call(auNumber), snapshot(rules), now),
        )
    }

    @Test
    fun `undetermined destinations match only any-destination rules`() {
        val rules = listOf(
            country("AU", RuleAction.UseSim(telstra.ref())),
            any(RuleAction.SystemDefault),
        )
        assertEquals(
            Verdict.Proceed(ProceedReason.SYSTEM_DEFAULT),
            engine.decide(call("*#06#"), snapshot(rules), now),
        )
    }

    @Test
    fun `call already on the winning rule's target proceeds unmodified`() {
        val rules = listOf(country("US", RuleAction.UseSim(tmobile.ref())))
        assertEquals(
            Verdict.Proceed(ProceedReason.ALREADY_ON_TARGET),
            engine.decide(call(usNumber, currentAccount = tmobile.phoneAccount), snapshot(rules), now),
        )
    }

    @Test
    fun `empty rule list proceeds unmodified`() {
        assertEquals(
            Verdict.Proceed(ProceedReason.NO_APPLICABLE_RULE),
            engine.decide(call(auNumber), snapshot(emptyList()), now),
        )
    }

    // --- Skip semantics ---

    @Test
    fun `disabled-sim rule is skipped and the next rule applies`() {
        val vodafoneRef = SimRef(7, "Vodafone", "Voda AU")
        val rules = listOf(
            country("AU", RuleAction.UseSim(vodafoneRef)),
            any(RuleAction.UseSim(tmobile.ref())),
        )
        assertEquals(
            Verdict.RedirectToAccount(tmobile.phoneAccount),
            engine.decide(call(auNumber, currentAccount = telstra.phoneAccount), snapshot(rules), now),
        )
    }

    @Test
    fun `chooser learns which disabled sims were skipped on the way to Ask`() {
        val vodafoneRef = SimRef(7, "Vodafone", "Voda AU")
        val rules = listOf(
            country("AU", RuleAction.UseSim(vodafoneRef)),
            any(RuleAction.Ask),
        )
        assertEquals(
            Verdict.OpenChooser(skippedInactiveSims = listOf(vodafoneRef)),
            engine.decide(call(auNumber), snapshot(rules), now),
        )
    }

    @Test
    fun `ambiguous sim re-binding is skipped without polluting the chooser payload`() {
        val telstra2 = ActiveSim(3, "Telstra", "Telstra AU", PhoneAccountRef("acct-t2"), "au")
        val staleRef = SimRef(99, "Telstra", "Telstra AU")
        val rules = listOf(
            country("AU", RuleAction.UseSim(staleRef)),
            any(RuleAction.Ask),
        )
        // Two active Telstra SIMs with the same names: the rule is ambiguous,
        // not disabled — the chooser opens plain.
        assertEquals(
            Verdict.OpenChooser(skippedInactiveSims = emptyList()),
            engine.decide(call(auNumber), snapshot(rules, activeSims = listOf(telstra, telstra2)), now),
        )
    }

    @Test
    fun `unreachable hand-off targets are skipped`() {
        val rules = listOf(
            country("US", RuleAction.HandOff.ViaPhoneAccount(PhoneAccountRef("acct-gone"))),
            // Not in handOffApps (not installed) → skipped.
            country("US", RuleAction.HandOff.ViaDialIntent(DialHandoffApp.TEAMS)),
            any(RuleAction.SystemDefault),
        )
        assertEquals(
            Verdict.Proceed(ProceedReason.SYSTEM_DEFAULT),
            engine.decide(call(usNumber), snapshot(rules), now),
        )
    }

    @Test
    fun `rules needing ui are skipped when non-interactive`() {
        val rules = listOf(
            any(RuleAction.Ask),
            any(RuleAction.UseSim(telstra.ref())),
        )
        // Ask is skipped without UI; the next rule still routes silently.
        assertEquals(
            Verdict.RedirectToAccount(telstra.phoneAccount),
            engine.decide(call(auNumber, interactive = false), snapshot(rules), now),
        )
    }

    @Test
    fun `ask is skipped when the chooser would have no target to offer`() {
        // No active SIMs (permission revoked / degraded read): canceling for
        // the chooser would strand the call — the next rule decides instead.
        val rules = listOf(
            any(RuleAction.Ask),
            any(RuleAction.SystemDefault),
        )
        assertEquals(
            Verdict.Proceed(ProceedReason.SYSTEM_DEFAULT),
            engine.decide(
                call(auNumber, currentAccount = null),
                snapshot(rules, activeSims = emptyList()),
                now,
            ),
        )
    }

    @Test
    fun `nothing applicable proceeds, never drops`() {
        val rules = listOf(
            any(RuleAction.Ask),
            any(RuleAction.HandOff.ViaDialIntent(DialHandoffApp.GOOGLE_VOICE)),
        )
        assertEquals(
            Verdict.Proceed(ProceedReason.NO_APPLICABLE_RULE),
            engine.decide(
                call(auNumber, interactive = false),
                snapshot(rules, handOffApps = setOf(DialHandoffApp.GOOGLE_VOICE.packageName)),
                now,
            ),
        )
    }

    // --- Actions ---

    @Test
    fun `matching country sim default routes to the sim of the destination country`() {
        val rules = RuleBook.defaultRules()
        assertEquals(
            Verdict.RedirectToAccount(telstra.phoneAccount),
            engine.decide(call(auNumber, currentAccount = tmobile.phoneAccount), snapshot(rules), now),
        )
        assertEquals(
            Verdict.Proceed(ProceedReason.ALREADY_ON_TARGET),
            engine.decide(call(usNumber, currentAccount = tmobile.phoneAccount), snapshot(rules), now),
        )
    }

    @Test
    fun `matching country sim skips when no sim or several sims match`() {
        val rules = RuleBook.defaultRules()
        // No SIM for GB: falls through to the system-default rule.
        assertEquals(
            Verdict.Proceed(ProceedReason.SYSTEM_DEFAULT),
            engine.decide(call(gbNumber), snapshot(rules), now),
        )
        // Two AU SIMs: ambiguous, also falls through.
        val optus = ActiveSim(3, "Optus", "Optus AU", PhoneAccountRef("acct-optus"), "AU")
        assertEquals(
            Verdict.Proceed(ProceedReason.SYSTEM_DEFAULT),
            engine.decide(call(auNumber), snapshot(rules, activeSims = listOf(telstra, optus)), now),
        )
    }

    @Test
    fun `preseeded defaults leave undetermined destinations to the system`() {
        assertEquals(
            Verdict.Proceed(ProceedReason.SYSTEM_DEFAULT),
            engine.decide(call("*#06#"), snapshot(RuleBook.defaultRules()), now),
        )
    }

    @Test
    fun `hand-off via phone account redirects, even non-interactively`() {
        val voip = PhoneAccountRef("acct-google-voice")
        val rules = listOf(country("US", RuleAction.HandOff.ViaPhoneAccount(voip)))
        assertEquals(
            Verdict.RedirectToAccount(voip),
            engine.decide(
                call(usNumber, interactive = false),
                snapshot(rules, handOffAccounts = setOf(voip)),
                now,
            ),
        )
    }

    @Test
    fun `hand-off via dial intent forwards to Google Voice with the E164 deep link`() {
        val gv = DialHandoffApp.GOOGLE_VOICE
        val rules = listOf(country("US", RuleAction.HandOff.ViaDialIntent(gv)))
        assertEquals(
            Verdict.ForwardToApp(gv.packageName, gv.launchUri(normalizeToE164(usNumber, "AU")!!), gv.label),
            engine.decide(call(usNumber), snapshot(rules, handOffApps = setOf(gv.packageName)), now),
        )
    }

    @Test
    fun `hand-off via dial intent forwards to Teams with the E164 deep link`() {
        val teams = DialHandoffApp.TEAMS
        val rules = listOf(country("US", RuleAction.HandOff.ViaDialIntent(teams)))
        assertEquals(
            Verdict.ForwardToApp(teams.packageName, teams.launchUri(normalizeToE164(usNumber, "AU")!!), teams.label),
            engine.decide(call(usNumber), snapshot(rules, handOffApps = setOf(teams.packageName)), now),
        )
    }

    @Test
    fun `hand-off via dial intent skips a number that can't normalize to E164`() {
        // A short code has no E.164 form; the rule can't forward it, so it skips
        // to the next rather than stranding the call.
        val gv = DialHandoffApp.GOOGLE_VOICE
        val rules = listOf(
            any(RuleAction.HandOff.ViaDialIntent(gv)),
            any(RuleAction.SystemDefault),
        )
        assertEquals(
            Verdict.Proceed(ProceedReason.SYSTEM_DEFAULT),
            engine.decide(call("311"), snapshot(rules, handOffApps = setOf(gv.packageName)), now),
        )
    }

    @Test
    fun `a recovery pass token lets the redial through a failed hand-off on any SIM`() {
        // The hand-off launch failed and dropped the user in the dialer; the
        // service minted an account-agnostic token (the dialer doesn't pin a
        // SIM) so redialing the same number on whichever SIM the dialer uses
        // proceeds instead of re-selecting the still-failing hand-off and looping.
        val gv = DialHandoffApp.GOOGLE_VOICE
        val rules = listOf(country("US", RuleAction.HandOff.ViaDialIntent(gv)))
        val token = PassToken(usNumber, account = null, expiresAtMillis = now + 5_000)
        val snapshot = snapshot(rules, passTokens = listOf(token), handOffApps = setOf(gv.packageName))
        // Retry lands on either SIM — both match the wildcard token.
        for (account in listOf(tmobile.phoneAccount, telstra.phoneAccount)) {
            assertEquals(
                Verdict.Proceed(ProceedReason.PASS_TOKEN, consumedToken = token),
                engine.decide(call(usNumber, currentAccount = account), snapshot, now),
            )
        }
    }

    // --- Invariants ---

    @Test
    fun `emergency numbers always proceed, whatever the rules say`() {
        val rules = listOf(any(RuleAction.UseSim(telstra.ref())))
        assertEquals(
            Verdict.Proceed(ProceedReason.EMERGENCY),
            engine.decide(call("000", currentAccount = tmobile.phoneAccount), snapshot(rules), now),
        )
    }

    @Test
    fun `re-placed call with a live pass token proceeds and consumes the token`() {
        val token = PassToken(auNumber, telstra.phoneAccount, expiresAtMillis = now + 5_000)
        val rules = listOf(country("AU", RuleAction.UseSim(tmobile.ref())))
        assertEquals(
            Verdict.Proceed(ProceedReason.PASS_TOKEN, consumedToken = token),
            engine.decide(
                call(auNumber, currentAccount = telstra.phoneAccount),
                snapshot(rules, passTokens = listOf(token)),
                now,
            ),
        )
    }

    @Test
    fun `expired or mismatched pass tokens are ignored`() {
        val expired = PassToken(auNumber, telstra.phoneAccount, expiresAtMillis = now - 1)
        val rules = listOf(country("AU", RuleAction.UseSim(telstra.ref())))
        assertEquals(
            Verdict.Proceed(ProceedReason.ALREADY_ON_TARGET),
            engine.decide(
                call(auNumber, currentAccount = telstra.phoneAccount),
                snapshot(rules, passTokens = listOf(expired)),
                now,
            ),
        )
        val otherAccount = PassToken(auNumber, telstra.phoneAccount, expiresAtMillis = now + 5_000)
        assertEquals(
            Verdict.RedirectToAccount(telstra.phoneAccount),
            engine.decide(
                call(auNumber, currentAccount = tmobile.phoneAccount),
                snapshot(rules, passTokens = listOf(otherAccount)),
                now,
            ),
        )
    }

    // --- "Calling using" announcement (settings) ---

    @Test
    fun `announces the rule-picked SIM when the toast setting is on`() {
        val rules = listOf(country("AU", RuleAction.UseSim(telstra.ref())))
        assertEquals(
            Verdict.RedirectToAccount(telstra.phoneAccount, announceSim = "Telstra AU"),
            engine.decide(call(auNumber), snapshot(rules).copy(announceCalls = true), now),
        )
    }

    @Test
    fun `announces a rule-picked SIM the call is already on`() {
        val rules = listOf(country("AU", RuleAction.UseSim(telstra.ref())))
        assertEquals(
            Verdict.Proceed(ProceedReason.ALREADY_ON_TARGET, announceSim = "Telstra AU"),
            engine.decide(
                call(auNumber, currentAccount = telstra.phoneAccount),
                snapshot(rules).copy(announceCalls = true),
                now,
            ),
        )
    }

    @Test
    fun `announces a matching-country SIM pick too`() {
        val rules = listOf(any(RuleAction.UseMatchingCountrySim))
        assertEquals(
            Verdict.RedirectToAccount(telstra.phoneAccount, announceSim = "Telstra AU"),
            engine.decide(call(auNumber), snapshot(rules).copy(announceCalls = true), now),
        )
    }

    @Test
    fun `announces nothing while the toast setting is off`() {
        val rules = listOf(country("AU", RuleAction.UseSim(telstra.ref())))
        assertEquals(
            Verdict.RedirectToAccount(telstra.phoneAccount, announceSim = null),
            engine.decide(call(auNumber), snapshot(rules), now),
        )
    }

    @Test
    fun `a chooser-placed call with a pass token is not announced`() {
        // The user just tapped the SIM by name; toasting it again is noise.
        val token = PassToken(auNumber, telstra.phoneAccount, expiresAtMillis = now + 5_000)
        val rules = listOf(country("AU", RuleAction.UseSim(tmobile.ref())))
        assertEquals(
            Verdict.Proceed(ProceedReason.PASS_TOKEN, consumedToken = token, announceSim = null),
            engine.decide(
                call(auNumber, currentAccount = telstra.phoneAccount),
                snapshot(rules, passTokens = listOf(token)).copy(announceCalls = true),
                now,
            ),
        )
    }

    @Test
    fun `a hand-off phone account is not announced as a SIM`() {
        // The receiving app opening is its own feedback, and the account has
        // no SIM name to toast.
        val gv = PhoneAccountRef("acct-gv")
        val rules = listOf(country("US", RuleAction.HandOff.ViaPhoneAccount(gv)))
        assertEquals(
            Verdict.RedirectToAccount(gv, announceSim = null),
            engine.decide(
                call(usNumber),
                snapshot(rules, handOffAccounts = setOf(gv)).copy(announceCalls = true),
                now,
            ),
        )
    }
}
