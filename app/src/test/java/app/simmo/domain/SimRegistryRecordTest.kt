package app.simmo.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class SimRegistryRecordTest {

    private val telstraActive = ActiveSim(1, "Telstra", "Telstra personal", PhoneAccountRef("a1"))
    private val tmobileActive = ActiveSim(2, "T-Mobile", "T-Mobile US", PhoneAccountRef("a2"))

    @Test
    fun `newly seen sims are added to the registry with a pending rule prompt`() {
        assertEquals(
            listOf(
                RegisteredSim(1, "Telstra", "Telstra personal", 500L, needsRulePrompt = true),
                RegisteredSim(2, "T-Mobile", "T-Mobile US", 500L, needsRulePrompt = true),
            ),
            emptyList<RegisteredSim>().recordSeen(listOf(telstraActive, tmobileActive), nowMillis = 500L),
        )
    }

    @Test
    fun `refreshing a known sim keeps its pending prompt`() {
        // Every subscription-change refresh re-records the registry; the
        // unanswered prompt must survive that, not vanish on the next refresh.
        val pending = RegisteredSim(1, "Telstra", "Telstra personal", 100L, needsRulePrompt = true)
        assertEquals(
            listOf(RegisteredSim(1, "Telstra", "Telstra personal", 900L, needsRulePrompt = true)),
            listOf(pending).recordSeen(listOf(telstraActive), nowMillis = 900L),
        )
    }

    @Test
    fun `re-binding a re-downloaded profile keeps its answered prompt`() {
        val answered = RegisteredSim(5, "Telstra", "Telstra personal", 100L, needsRulePrompt = false)
        val redownloaded = ActiveSim(9, "Telstra", "Telstra personal", PhoneAccountRef("a9"))
        assertEquals(
            listOf(RegisteredSim(9, "Telstra", "Telstra personal", 900L, needsRulePrompt = false)),
            listOf(answered).recordSeen(listOf(redownloaded), nowMillis = 900L),
        )
    }

    @Test
    fun `a data-only sim registers without a rule prompt`() {
        // A travel eSIM with no call-capable account: remembered (the no-data
        // nudge must be able to name it once it's disabled) but never nagged
        // about calling rules it could not serve.
        val dataOnly = ActiveSim(5, "Orange", "Orange Holiday", PhoneAccountRef("subscription:5"), "fr")
        assertEquals(
            listOf(
                RegisteredSim(1, "Telstra", "Telstra personal", 500L, needsRulePrompt = true),
                RegisteredSim(
                    5, "Orange", "Orange Holiday", 500L,
                    countryIso = "fr", needsRulePrompt = false, callCapable = false,
                    rulePromptOffered = false,
                ),
            ),
            emptyList<RegisteredSim>().recordSeen(
                listOf(telstraActive, dataOnly),
                nowMillis = 500L,
                callCapableIds = setOf(telstraActive.subscriptionId),
            ),
        )
    }

    @Test
    fun `a transient Telecom miss cannot cost the new-SIM prompt`() {
        // A call-capable SIM first recorded while Telecom briefly missed its
        // account registers as data-only with no prompt; the refresh that
        // sees the account must restore the never-offered prompt, not leave
        // it suppressed forever.
        val missed = emptyList<RegisteredSim>()
            .recordSeen(listOf(telstraActive), nowMillis = 500L, callCapableIds = emptySet())
        assertEquals(false, missed.single().needsRulePrompt)
        assertEquals(
            listOf(
                RegisteredSim(
                    1, "Telstra", "Telstra personal", 900L,
                    needsRulePrompt = true, callCapable = true, rulePromptOffered = true,
                ),
            ),
            missed.recordSeen(
                listOf(telstraActive),
                nowMillis = 900L,
                callCapableIds = setOf(telstraActive.subscriptionId),
            ),
        )
    }

    @Test
    fun `first-capture marking covers rows later promoted to call-capable`() {
        // Fresh install while Telecom misses the accounts: the batch records
        // data-only, and the first-capture suppression marks the WHOLE batch
        // notified — so the later promotion restores the in-app prompt card
        // but can never post a notification about a SIM present at install.
        val batch = emptyList<RegisteredSim>()
            .recordSeen(listOf(telstraActive), nowMillis = 500L, callCapableIds = emptySet())
        val marked = batch.withNewSimNotified(batch.map { it.ref() })
        val promoted = marked.recordSeen(listOf(telstraActive), nowMillis = 900L)
        assertEquals(true, promoted.single().needsRulePrompt)
        assertEquals(
            emptyList<RegisteredSim>(),
            promoted.pendingNewSimNotifications(listOf(telstraActive)),
        )
    }

    @Test
    fun `a rebind takes capability as fresh truth without re-asking the prompt`() {
        // An answered call-capable row re-binds to a profile with no
        // call-capable account — a genuinely data-only replacement, or a
        // degraded Telecom read. Capability follows the adopted profile (so
        // the editor stops offering a target that can't call), while the
        // prompt history keeps a later promotion from re-asking a prompt the
        // user already answered.
        val answered = RegisteredSim(5, "Telstra", "Telstra personal", 100L, needsRulePrompt = false)
        val redownloaded = ActiveSim(9, "Telstra", "Telstra personal", PhoneAccountRef("a9"))
        val rebound = listOf(answered).recordSeen(
            listOf(redownloaded),
            nowMillis = 900L,
            callCapableIds = emptySet(),
        )
        assertEquals(
            listOf(
                RegisteredSim(
                    9, "Telstra", "Telstra personal", 900L,
                    needsRulePrompt = false, callCapable = false, rulePromptOffered = true,
                ),
            ),
            rebound,
        )
        // The follow-up exact-id refresh that sees the account again promotes
        // the capability but must not re-ask the answered prompt.
        val promoted = rebound.recordSeen(listOf(redownloaded), nowMillis = 950L).single()
        assertEquals(true, promoted.callCapable)
        assertEquals(false, promoted.needsRulePrompt)
    }

    @Test
    fun `a degraded refresh cannot demote a call-capable sim`() {
        // Telecom can briefly report no accounts while the subscription list
        // still reads; the flag is sticky-true so the editor's options don't
        // flap in and out on read noise.
        val known = RegisteredSim(1, "Telstra", "Telstra personal", 100L)
        assertEquals(
            listOf(known.copy(lastSeenEpochMillis = 900L)),
            listOf(known).recordSeen(
                listOf(telstraActive),
                nowMillis = 900L,
                callCapableIds = emptySet(),
            ),
        )
    }

    @Test
    fun `answering the prompt clears exactly that sim`() {
        val telstra = RegisteredSim(1, "Telstra", "Telstra personal", 100L, needsRulePrompt = true)
        val tmobile = RegisteredSim(2, "T-Mobile", "T-Mobile US", 100L, needsRulePrompt = true)
        assertEquals(
            listOf(telstra.copy(needsRulePrompt = false), tmobile),
            listOf(telstra, tmobile).withRulePromptCleared(telstra.ref()),
        )
    }

    @Test
    fun `answering the prompt re-binds by name when the id is stale`() {
        // Same identity ladder as resolveSim: a sentinel id from a restore
        // still finds the entry by carrier + display name.
        val restored = RegisteredSim(3, "Telstra", "Telstra personal", 100L, needsRulePrompt = true)
        val staleRef = SimRef(SimRef.INVALID_SUBSCRIPTION_ID, "telstra", " Telstra personal ")
        assertEquals(
            listOf(restored.copy(needsRulePrompt = false)),
            listOf(restored).withRulePromptCleared(staleRef),
        )
    }

    @Test
    fun `known sims refresh names and last-seen`() {
        val registry = listOf(RegisteredSim(1, "Old Carrier", "Old name", 100L))
        assertEquals(
            listOf(RegisteredSim(1, "Telstra", "Telstra personal", 900L)),
            registry.recordSeen(listOf(telstraActive), nowMillis = 900L),
        )
    }

    @Test
    fun `capture records the sim's country and own number`() {
        val active = telstraActive.copy(countryIso = "au", phoneNumber = "+61412345678")
        assertEquals(
            listOf(
                RegisteredSim(
                    1, "Telstra", "Telstra personal", 500L,
                    countryIso = "au", phoneNumber = "+61412345678", needsRulePrompt = true,
                ),
            ),
            emptyList<RegisteredSim>().recordSeen(listOf(active), nowMillis = 500L),
        )
    }

    @Test
    fun `blank country and number reads keep the last-known values`() {
        // The platform reports these intermittently (and the number needs its
        // own permission, revocable any time); a blank read must not erase
        // what an earlier sighting learned.
        val known = RegisteredSim(
            1, "Telstra", "Telstra personal", 100L,
            countryIso = "au", phoneNumber = "+61412345678",
        )
        assertEquals(
            listOf(known.copy(lastSeenEpochMillis = 900L)),
            listOf(known).recordSeen(listOf(telstraActive), nowMillis = 900L),
        )
    }

    @Test
    fun `re-binding does not inherit the old row's country and number`() {
        // Restore case: the row that re-binds by carrier + name on the new
        // device can be a different physical SIM. A blank read must not
        // resurrect the old device's number/country onto it (Codex on PR #36);
        // the last-known fallback is for the exact-subscription refresh only.
        val restored = RegisteredSim(
            SimRef.INVALID_SUBSCRIPTION_ID, "Telstra", "Telstra personal", 100L,
            countryIso = "au", phoneNumber = "+61412345678",
        )
        assertEquals(
            listOf(RegisteredSim(1, "Telstra", "Telstra personal", 900L)),
            listOf(restored).recordSeen(listOf(telstraActive), nowMillis = 900L),
        )
    }

    @Test
    fun `re-binding a re-downloaded profile refreshes country and number`() {
        val stale = RegisteredSim(5, "Telstra", "Telstra personal", 100L, countryIso = "au")
        val redownloaded = ActiveSim(
            9, "Telstra", "Telstra personal", PhoneAccountRef("a9"),
            countryIso = "us", phoneNumber = "+12025550123",
        )
        assertEquals(
            listOf(
                RegisteredSim(
                    9, "Telstra", "Telstra personal", 900L,
                    countryIso = "us", phoneNumber = "+12025550123",
                ),
            ),
            listOf(stale).recordSeen(listOf(redownloaded), nowMillis = 900L),
        )
    }

    @Test
    fun `sims not currently active are kept for disabled-sim rules`() {
        val disabled = RegisteredSim(7, "Vodafone", "Voda AU", 100L)
        assertEquals(
            listOf(disabled, RegisteredSim(1, "Telstra", "Telstra personal", 900L, needsRulePrompt = true)),
            listOf(disabled).recordSeen(listOf(telstraActive), nowMillis = 900L),
        )
    }

    @Test
    fun `restored entry with invalidated id re-adopts the matching active sim`() {
        // After a device transfer the registry entry carries the sentinel id;
        // seeing the same-named SIM active must update it, not duplicate it.
        val restored = RegisteredSim(SimRef.INVALID_SUBSCRIPTION_ID, "Telstra", "Telstra personal", 100L)
        assertEquals(
            listOf(RegisteredSim(1, "Telstra", "Telstra personal", 900L)),
            listOf(restored).recordSeen(listOf(telstraActive), nowMillis = 900L),
        )
    }

    @Test
    fun `re-downloaded profile with a new id re-binds instead of duplicating`() {
        // Same device: the profile was deleted and re-downloaded, so Android
        // assigned a fresh subscription id. The old row must re-bind, not stay
        // beside a duplicate new row.
        val stale = RegisteredSim(5, "Telstra", "Telstra personal", 100L)
        val redownloaded = ActiveSim(9, "Telstra", "Telstra personal", PhoneAccountRef("a9"))
        assertEquals(
            listOf(RegisteredSim(9, "Telstra", "Telstra personal", 900L)),
            listOf(stale).recordSeen(listOf(redownloaded), nowMillis = 900L),
        )
    }

    @Test
    fun `same-named stale rows adopt deterministically without duplicating`() {
        // Two identically named stale rows, one active candidate: the rows are
        // indistinguishable by identity, so the first adopts it (deterministic,
        // no duplicate row) and the other stays for a future SIM.
        val staleA = RegisteredSim(5, "Telstra", "Telstra personal", 100L)
        val staleB = RegisteredSim(6, "Telstra", "Telstra personal", 200L)
        val active = ActiveSim(9, "Telstra", "Telstra personal", PhoneAccountRef("a9"))
        assertEquals(
            listOf(RegisteredSim(9, "Telstra", "Telstra personal", 900L), staleB),
            listOf(staleA, staleB).recordSeen(listOf(active), nowMillis = 900L),
        )
    }

    @Test
    fun `restored entry with different names is kept, not adopted`() {
        val restored = RegisteredSim(SimRef.INVALID_SUBSCRIPTION_ID, "Vodafone", "Voda AU", 100L)
        assertEquals(
            listOf(restored, RegisteredSim(1, "Telstra", "Telstra personal", 900L, needsRulePrompt = true)),
            listOf(restored).recordSeen(listOf(telstraActive), nowMillis = 900L),
        )
    }

    @Test
    fun `deleting removes exactly the referenced sim`() {
        val telstra = RegisteredSim(1, "Telstra", "Telstra personal", 100L)
        val vodafone = RegisteredSim(7, "Vodafone", "Voda AU", 100L)
        assertEquals(listOf(telstra), listOf(telstra, vodafone).withoutSim(vodafone.ref()))
    }

    @Test
    fun `deleting re-binds by name when the id is stale`() {
        // Same identity ladder as resolveSim: a sentinel-id ref (post-restore)
        // still deletes the row it names.
        val restored = RegisteredSim(3, "Vodafone", "Voda AU", 100L)
        val staleRef = SimRef(SimRef.INVALID_SUBSCRIPTION_ID, "vodafone", " Voda AU ")
        assertEquals(emptyList<RegisteredSim>(), listOf(restored).withoutSim(staleRef))
    }

    @Test
    fun `deleting one of two same-named sims spares its sibling`() {
        // Two rows sharing carrier + name under different real ids (e.g. a
        // profile deleted and re-downloaded over the years): deleting one by
        // its exact id must not name-match the other away too.
        val older = RegisteredSim(5, "Telstra", "Telstra personal", 100L)
        val newer = RegisteredSim(9, "Telstra", "Telstra personal", 900L)
        assertEquals(listOf(newer), listOf(older, newer).withoutSim(older.ref()))
    }

    @Test
    fun `new-sim notifications fire once, only for active unanswered prompts`() {
        val registry = listOf(
            // Unanswered, not yet notified, active: pending.
            RegisteredSim(1, "Telstra", "Telstra personal", 100L, needsRulePrompt = true),
            // Already notified: never again.
            RegisteredSim(2, "T-Mobile", "T-Mobile US", 100L, needsRulePrompt = true, newSimNotified = true),
            // Prompt answered: nothing to say.
            RegisteredSim(3, "Optus", "Optus AU", 100L, needsRulePrompt = false),
            // Unanswered but the SIM vanished again: held back.
            RegisteredSim(4, "Vodafone", "Voda AU", 100L, needsRulePrompt = true),
        )
        val active = listOf(
            telstraActive,
            tmobileActive,
            ActiveSim(3, "Optus", "Optus AU", PhoneAccountRef("a3")),
        )
        assertEquals(
            listOf(registry[0]),
            registry.pendingNewSimNotifications(active),
        )
    }

    @Test
    fun `marking notified sticks and survives refreshes`() {
        val pending = RegisteredSim(1, "Telstra", "Telstra personal", 100L, needsRulePrompt = true)
        val marked = listOf(pending).withNewSimNotified(listOf(pending.ref()))
        assertEquals(true, marked.single().newSimNotified)
        // recordSeen copies entries, so the flag rides through the constant
        // subscription-change refreshes without re-nagging.
        val refreshed = marked.recordSeen(listOf(telstraActive), nowMillis = 900L)
        assertEquals(true, refreshed.single().newSimNotified)
    }

    @Test
    fun `no active sims leaves the registry untouched`() {
        val registry = listOf(RegisteredSim(1, "Telstra", "Telstra personal", 100L))
        assertEquals(registry, registry.recordSeen(emptyList(), nowMillis = 900L))
    }
}
