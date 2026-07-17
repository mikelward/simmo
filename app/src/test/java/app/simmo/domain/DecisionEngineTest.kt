package app.simmo.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class DecisionEngineTest {

    private val engine = DecisionEngine(PhoneNumberCountryDetector())

    private val telstra = ActiveSim(1, "Telstra", "Telstra AU", PhoneAccountRef("acct-telstra"))
    private val tmobile = ActiveSim(2, "T-Mobile", "T-Mobile US", PhoneAccountRef("acct-tmobile"))
    private val now = 1_000_000L

    private val auNumber = "+61 412 345 678"
    private val usNumber = "+1 212 555 0123"

    private fun ActiveSim.ref() = SimRef(subscriptionId, carrierName, displayName)

    private fun snapshot(
        rules: RuleBook = RuleBook(),
        activeSims: List<ActiveSim> = listOf(telstra, tmobile),
        passTokens: List<PassToken> = emptyList(),
        handOffAccounts: Set<PhoneAccountRef> = emptySet(),
        handOffApps: Set<String> = emptySet(),
    ) = DecisionSnapshot(
        rules,
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

    private val countryRules = RuleBook(
        countryRules = mapOf(
            "AU" to RuleAction.UseSim(telstra.ref()),
            "US" to RuleAction.UseSim(tmobile.ref()),
        ),
    )

    @Test
    fun `rule hit redirects to the rule's sim`() {
        assertEquals(
            Verdict.RedirectToAccount(telstra.phoneAccount),
            engine.decide(call(auNumber, currentAccount = tmobile.phoneAccount), snapshot(countryRules), now),
        )
    }

    @Test
    fun `call already on the rule's sim proceeds unmodified`() {
        assertEquals(
            Verdict.Proceed(ProceedReason.ALREADY_ON_TARGET),
            engine.decide(call(usNumber, currentAccount = tmobile.phoneAccount), snapshot(countryRules), now),
        )
    }

    @Test
    fun `unmatched country falls back to Ask and opens the chooser`() {
        assertEquals(
            Verdict.OpenChooser(ChooserMode.Ask),
            engine.decide(call("+44 20 7946 0958"), snapshot(countryRules), now),
        )
    }

    @Test
    fun `undetermined numbers use the fallback rule`() {
        assertEquals(
            Verdict.OpenChooser(ChooserMode.Ask),
            engine.decide(call("*#06#"), snapshot(countryRules), now),
        )
    }

    @Test
    fun `configured fallback action applies to unmatched countries`() {
        val rules = countryRules.copy(fallback = RuleAction.UseSim(tmobile.ref()))
        assertEquals(
            Verdict.RedirectToAccount(tmobile.phoneAccount),
            engine.decide(call("+44 20 7946 0958", currentAccount = telstra.phoneAccount), snapshot(rules), now),
        )
    }

    @Test
    fun `emergency numbers always proceed, even with a matching country rule`() {
        val rules = RuleBook(countryRules = mapOf("AU" to RuleAction.UseSim(telstra.ref())))
        assertEquals(
            Verdict.Proceed(ProceedReason.EMERGENCY),
            engine.decide(call("000", currentAccount = tmobile.phoneAccount), snapshot(rules), now),
        )
    }

    @Test
    fun `rule targeting an inactive sim opens the enable flow`() {
        val vodafoneRef = SimRef(7, "Vodafone", "Voda AU")
        val rules = RuleBook(countryRules = mapOf("AU" to RuleAction.UseSim(vodafoneRef)))
        assertEquals(
            Verdict.OpenChooser(ChooserMode.EnableSim(vodafoneRef)),
            engine.decide(call(auNumber), snapshot(rules), now),
        )
    }

    @Test
    fun `ambiguous carrier re-binding asks which sim was meant`() {
        val telstra2 = ActiveSim(3, "Telstra", "Telstra work", PhoneAccountRef("acct-telstra-2"))
        val staleRef = SimRef(99, "Telstra", "Telstra old")
        val rules = RuleBook(countryRules = mapOf("AU" to RuleAction.UseSim(staleRef)))
        assertEquals(
            Verdict.OpenChooser(ChooserMode.PickAmong(staleRef, listOf(telstra, telstra2))),
            engine.decide(call(auNumber), snapshot(rules, activeSims = listOf(telstra, telstra2)), now),
        )
    }

    @Test
    fun `hand-off via phone account redirects, even non-interactively`() {
        val voip = PhoneAccountRef("acct-google-voice")
        val rules = RuleBook(countryRules = mapOf("US" to RuleAction.HandOff.ViaPhoneAccount(voip)))
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
        val rules = RuleBook(
            countryRules = mapOf("US" to RuleAction.HandOff.ViaDialIntent("com.example.voip")),
        )
        assertEquals(
            Verdict.ForwardToApp("com.example.voip"),
            engine.decide(call(usNumber), snapshot(rules, handOffApps = setOf("com.example.voip")), now),
        )
    }

    @Test
    fun `stale hand-off account falls back to the chooser, never a blind redirect`() {
        // The rule's target app was uninstalled or its phone account disabled
        // after the rule was created: it is absent from the snapshot's
        // reachable targets.
        val voip = PhoneAccountRef("acct-gone")
        val rules = RuleBook(countryRules = mapOf("US" to RuleAction.HandOff.ViaPhoneAccount(voip)))
        assertEquals(
            Verdict.OpenChooser(ChooserMode.Ask),
            engine.decide(call(usNumber), snapshot(rules), now),
        )
        assertEquals(
            Verdict.Proceed(ProceedReason.NON_INTERACTIVE_DEGRADE),
            engine.decide(call(usNumber, interactive = false), snapshot(rules), now),
        )
    }

    @Test
    fun `stale hand-off app falls back to the chooser, never a dead-end cancel`() {
        val rules = RuleBook(
            countryRules = mapOf("US" to RuleAction.HandOff.ViaDialIntent("com.example.gone")),
        )
        assertEquals(
            Verdict.OpenChooser(ChooserMode.Ask),
            engine.decide(call(usNumber), snapshot(rules), now),
        )
        assertEquals(
            Verdict.Proceed(ProceedReason.NON_INTERACTIVE_DEGRADE),
            engine.decide(call(usNumber, interactive = false), snapshot(rules), now),
        )
    }

    @Test
    fun `verdicts needing ui degrade to proceed when non-interactive`() {
        // A call is never silently dropped (SPEC): Ask, the enable flow, and
        // intent hand-off all pass the call through instead when UI is forbidden.
        val degraded = Verdict.Proceed(ProceedReason.NON_INTERACTIVE_DEGRADE)
        assertEquals(
            degraded,
            engine.decide(call("+44 20 7946 0958", interactive = false), snapshot(countryRules), now),
        )
        val inactiveRule = RuleBook(countryRules = mapOf("AU" to RuleAction.UseSim(SimRef(7, "Vodafone", "Voda AU"))))
        assertEquals(
            degraded,
            engine.decide(call(auNumber, interactive = false), snapshot(inactiveRule), now),
        )
        val intentRule = RuleBook(countryRules = mapOf("US" to RuleAction.HandOff.ViaDialIntent("com.example.voip")))
        assertEquals(
            degraded,
            engine.decide(call(usNumber, interactive = false), snapshot(intentRule), now),
        )
    }

    @Test
    fun `silent rules still apply when non-interactive`() {
        assertEquals(
            Verdict.RedirectToAccount(telstra.phoneAccount),
            engine.decide(call(auNumber, interactive = false), snapshot(countryRules), now),
        )
    }

    @Test
    fun `re-placed call with a live pass token proceeds and consumes the token`() {
        val token = PassToken(auNumber, telstra.phoneAccount, expiresAtMillis = now + 5_000)
        assertEquals(
            Verdict.Proceed(ProceedReason.PASS_TOKEN, consumedToken = token),
            engine.decide(
                call(auNumber, currentAccount = telstra.phoneAccount),
                snapshot(countryRules, passTokens = listOf(token)),
                now,
            ),
        )
    }

    @Test
    fun `expired pass token is ignored`() {
        val token = PassToken(auNumber, telstra.phoneAccount, expiresAtMillis = now - 1)
        // Rule and current account already agree, so the call proceeds for that
        // reason — the token itself is dead.
        assertEquals(
            Verdict.Proceed(ProceedReason.ALREADY_ON_TARGET),
            engine.decide(
                call(auNumber, currentAccount = telstra.phoneAccount),
                snapshot(countryRules, passTokens = listOf(token)),
                now,
            ),
        )
    }

    @Test
    fun `pass token for a different account does not match`() {
        val token = PassToken(auNumber, telstra.phoneAccount, expiresAtMillis = now + 5_000)
        assertEquals(
            Verdict.RedirectToAccount(telstra.phoneAccount),
            engine.decide(
                call(auNumber, currentAccount = tmobile.phoneAccount),
                snapshot(countryRules, passTokens = listOf(token)),
                now,
            ),
        )
    }

    @Test
    fun `emergency wins over a live pass token`() {
        val token = PassToken("000", telstra.phoneAccount, expiresAtMillis = now + 5_000)
        assertEquals(
            Verdict.Proceed(ProceedReason.EMERGENCY),
            engine.decide(
                call("000", currentAccount = telstra.phoneAccount),
                snapshot(countryRules, passTokens = listOf(token)),
                now,
            ),
        )
    }

    @Test
    fun `empty rule book asks for every determinable call`() {
        assertEquals(
            Verdict.OpenChooser(ChooserMode.Ask),
            engine.decide(call(auNumber), snapshot(), now),
        )
    }
}
