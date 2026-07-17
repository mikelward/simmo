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
        handOffAccounts: Set<PhoneAccountRef> = emptySet(),
        handOffApps: Set<String> = emptySet(),
    ) = DecisionSnapshot(
        RuleBook(rules),
        activeSims,
        defaultRegion = "AU",
        passTokens = passTokens,
        handOffAccounts = handOffAccounts,
        handOffApps = handOffApps,
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
            country("US", RuleAction.HandOff.ViaDialIntent("com.example.gone")),
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
    fun `nothing applicable proceeds, never drops`() {
        val rules = listOf(
            any(RuleAction.Ask),
            any(RuleAction.HandOff.ViaDialIntent("com.example.voip")),
        )
        assertEquals(
            Verdict.Proceed(ProceedReason.NO_APPLICABLE_RULE),
            engine.decide(
                call(auNumber, interactive = false),
                snapshot(rules, handOffApps = setOf("com.example.voip")),
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
    fun `hand-off via dial intent forwards to the app when interactive`() {
        val rules = listOf(country("US", RuleAction.HandOff.ViaDialIntent("com.example.voip")))
        assertEquals(
            Verdict.ForwardToApp("com.example.voip"),
            engine.decide(call(usNumber), snapshot(rules, handOffApps = setOf("com.example.voip")), now),
        )
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
}
