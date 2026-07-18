package app.simmo.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class RuleBookMutationTest {

    private val au = Rule(RuleMatcher.Country("AU"), RuleAction.UseSim(SimRef(1, "Telstra", "Telstra AU")))
    private val us = Rule(RuleMatcher.Country("US"), RuleAction.UseSim(SimRef(2, "T-Mobile", "T-Mobile US")))
    private val fallback = Rule(RuleMatcher.AnyDestination, RuleAction.SystemDefault)
    private val book = RuleBook(listOf(au, us, fallback))

    @Test
    fun `new rules are added at the top, above the defaults`() {
        val nz = Rule(RuleMatcher.Country("NZ"), RuleAction.Ask)
        assertEquals(listOf(nz, au, us, fallback), book.withRuleAdded(nz).rules)
    }

    @Test
    fun `replacing edits in place without changing order`() {
        val edited = us.copy(action = RuleAction.Ask)
        assertEquals(listOf(au, edited, fallback), book.withRuleReplaced(1, edited).rules)
    }

    @Test
    fun `removing deletes exactly the indexed rule`() {
        assertEquals(listOf(au, fallback), book.withRuleRemoved(1).rules)
    }

    @Test
    fun `replacing by id edits in place, ignoring an unknown id`() {
        val idBook = RuleBook(listOf(au.copy(id = "a"), us.copy(id = "b"), fallback.copy(id = "c")))
        val edited = us.copy(id = "b", action = RuleAction.Ask)
        assertEquals(
            listOf(au.copy(id = "a"), edited, fallback.copy(id = "c")),
            idBook.withRuleReplaced("b", edited).rules,
        )
        // A stale id (its rule was deleted while the editor was open) is a no-op.
        assertEquals(idBook.rules, idBook.withRuleReplaced("gone", edited).rules)
    }

    @Test
    fun `restoring re-inserts below its neighbor, idempotent on retry`() {
        val a = au.copy(id = "a")
        val c = fallback.copy(id = "c")
        val afterDelete = RuleBook(listOf(a, c)) // "b" (us) was deleted from between "a" and "c"
        val b = us.copy(id = "b")
        val restored = afterDelete.withRuleRestored(b, afterId = "a", beforeId = "c")
        assertEquals(listOf(a, b, c), restored.rules)
        // Retrying the same restore (second Undo tap, replay after process death)
        // must not insert a second copy.
        assertSame(restored, restored.withRuleRestored(b, afterId = "a", beforeId = "c"))
    }

    @Test
    fun `restoring anchors to the neighbor id even when the list shifted`() {
        val a = au.copy(id = "a")
        val c = fallback.copy(id = "c")
        val b = us.copy(id = "b")
        // "b" sat below "a"; since deleting it, a rule "x" was prepended at the
        // top. Restoring by afterId still puts "b" right below "a", not at the
        // stale absolute index.
        val x = Rule(RuleMatcher.Country("NZ"), RuleAction.Ask, id = "x")
        val shifted = RuleBook(listOf(x, a, c))
        assertEquals(listOf(x, a, b, c), shifted.withRuleRestored(b, afterId = "a", beforeId = "c").rules)
    }

    @Test
    fun `restoring a top rule lands below a rule prepended since, via beforeId`() {
        // "a" was the top rule (afterId null, beforeId "b"). Before Undo, the
        // chooser prepended "x". Restoring must put "a" below "x" — above "b",
        // its old neighbor — not back at the top ahead of the newer "x".
        val b = us.copy(id = "b")
        val x = Rule(RuleMatcher.Country("NZ"), RuleAction.Ask, id = "x")
        val shifted = RuleBook(listOf(x, b))
        val a = au.copy(id = "a")
        assertEquals(listOf(x, a, b), shifted.withRuleRestored(a, afterId = null, beforeId = "b").rules)
        // With both anchors gone it falls back to the top.
        assertEquals("a", RuleBook(listOf(x)).withRuleRestored(a, afterId = "gone", beforeId = "gone").rules.first().id)
    }

    @Test
    fun `a blank id matches nothing, never every rule`() {
        // Rules with blank ids must not all be replaced or removed at once by a
        // blank-keyed edit — a blank id means "unassigned", never a target.
        val blanks = RuleBook(listOf(au, us, fallback)) // all default to id = ""
        assertSame(blanks, blanks.withRuleReplaced("", us.copy(action = RuleAction.Ask)))
        assertSame(blanks, blanks.withRuleRemoved(""))
    }

    @Test
    fun `removing by id drops the matching rule, ignoring an unknown id`() {
        val idBook = RuleBook(listOf(au.copy(id = "a"), us.copy(id = "b"), fallback.copy(id = "c")))
        assertEquals(
            listOf(au.copy(id = "a"), fallback.copy(id = "c")),
            idBook.withRuleRemoved("b").rules,
        )
        assertEquals(idBook.rules, idBook.withRuleRemoved("gone").rules)
    }

    @Test
    fun `duplicating copies the rule below it under a new, distinct id`() {
        val idBook = RuleBook(listOf(au.copy(id = "a"), us.copy(id = "b")))
        val dup = idBook.withRuleDuplicated(0, "a-copy")
        assertEquals(
            listOf(au.copy(id = "a"), au.copy(id = "a-copy"), us.copy(id = "b")),
            dup.rules,
        )
        // The copy is a distinct rule — it must not share the original's id.
        assertEquals(2, dup.rules.count { it.matcher == au.matcher })
        assertEquals(setOf("a", "a-copy"), dup.rules.filter { it.matcher == au.matcher }.mapTo(HashSet()) { it.id })
        // Out-of-range is a no-op.
        assertSame(idBook, idBook.withRuleDuplicated(9, "x"))
    }

    @Test
    fun `the preseeded defaults carry stable ids`() {
        // Ids must be identical across calls, or a fresh SimmoState would never
        // equal its round-trip and the editor could not address a default rule.
        assertEquals(
            RuleBook.defaultRules().map { it.id },
            RuleBook.defaultRules().map { it.id },
        )
        assertEquals(setOf("default-home-country-sim", "default-system"), RuleBook.defaultRules().mapTo(HashSet()) { it.id })
    }

    @Test
    fun `moving reorders for drag and drop`() {
        assertEquals(listOf(us, au, fallback), book.withRuleMoved(0, 1).rules)
        assertEquals(listOf(fallback, au, us), book.withRuleMoved(2, 0).rules)
    }

    @Test
    fun `moving to the same or an invalid index is a no-op`() {
        assertSame(book, book.withRuleMoved(1, 1))
        assertSame(book, book.withRuleMoved(5, 0))
        assertSame(book, book.withRuleMoved(0, -1))
    }

    @Test
    fun `inserting places the rule exactly there, clamping wild indices`() {
        val nz = Rule(RuleMatcher.Country("NZ"), RuleAction.Ask)
        assertEquals(listOf(au, nz, us, fallback), book.withRuleInserted(1, nz).rules)
        assertEquals(listOf(nz, au, us, fallback), book.withRuleInserted(-3, nz).rules)
        assertEquals(listOf(au, us, fallback, nz), book.withRuleInserted(99, nz).rules)
    }

    @Test
    fun `new-sim rules are suggested above the first paused rule`() {
        // Only the US SIM is active: the AU rule at index 0 is paused, so the
        // suggested slot is above it.
        val tmobileActive = ActiveSim(2, "T-Mobile", "T-Mobile US", PhoneAccountRef("a2"))
        assertEquals(0, book.newSimRuleInsertionIndex(listOf(tmobileActive)))

        // Both rule SIMs active: nothing is paused, so the slot is the top —
        // same as an ordinary add.
        val telstraActive = ActiveSim(1, "Telstra", "Telstra AU", PhoneAccountRef("a1"))
        assertEquals(0, book.newSimRuleInsertionIndex(listOf(telstraActive, tmobileActive)))

        // The paused rule sits mid-list: the suggestion lands right above it,
        // below the rules that are actually working.
        val pausedMidList = RuleBook(listOf(us, au, fallback))
        assertEquals(1, pausedMidList.newSimRuleInsertionIndex(listOf(tmobileActive)))
    }
}
