package app.simmo.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The data list's edits mirror the calling book's (SPEC "Data rules": same
 * editing surface): new rules land on top, reorder clamps, out-of-range is a
 * no-op.
 */
class DataRuleBookMutationTest {

    private fun rule(region: String) = DataRule(
        matcher = RuleMatcher.Country(region),
        expectation = DataExpectation.AlwaysWarn,
    )

    @Test
    fun `added rules land on top, above the preseeded default`() {
        val book = DataRuleBook().withRuleAdded(rule("AU"))
        assertEquals(2, book.rules.size)
        assertEquals(rule("AU"), book.rules.first())
    }

    @Test
    fun `insert clamps to the list bounds`() {
        val book = DataRuleBook(rules = listOf(rule("AU")))
        assertEquals(
            listOf(rule("AU"), rule("NZ")),
            book.withRuleInserted(99, rule("NZ")).rules,
        )
        assertEquals(
            listOf(rule("NZ"), rule("AU")),
            book.withRuleInserted(-1, rule("NZ")).rules,
        )
    }

    @Test
    fun `replace, remove, and move edit in place`() {
        val book = DataRuleBook(rules = listOf(rule("AU"), rule("NZ"), rule("US")))
        assertEquals(
            listOf(rule("AU"), rule("FR"), rule("US")),
            book.withRuleReplaced(1, rule("FR")).rules,
        )
        assertEquals(listOf(rule("AU"), rule("US")), book.withRuleRemoved(1).rules)
        assertEquals(
            listOf(rule("NZ"), rule("US"), rule("AU")),
            book.withRuleMoved(0, 2).rules,
        )
        // Out-of-range moves are a no-op, matching the calling book.
        assertEquals(book.rules, book.withRuleMoved(0, 5).rules)
    }

    @Test
    fun `replace and remove by id match the calling book, no-op on unknown id`() {
        val book = DataRuleBook(
            rules = listOf(rule("AU").copy(id = "a"), rule("NZ").copy(id = "b"), rule("US").copy(id = "c")),
        )
        val edited = rule("FR").copy(id = "b")
        assertEquals(
            listOf(rule("AU").copy(id = "a"), edited, rule("US").copy(id = "c")),
            book.withRuleReplaced("b", edited).rules,
        )
        assertEquals(book.rules, book.withRuleReplaced("gone", edited).rules)
        assertEquals(
            listOf(rule("AU").copy(id = "a"), rule("US").copy(id = "c")),
            book.withRuleRemoved("b").rules,
        )
        assertEquals(book.rules, book.withRuleRemoved("gone").rules)
    }

    @Test
    fun `restoring re-inserts below its neighbor, idempotent on retry`() {
        val a = rule("AU").copy(id = "a")
        val c = rule("US").copy(id = "c")
        val afterDelete = DataRuleBook(rules = listOf(a, c)) // "b" was deleted from between "a" and "c"
        val b = rule("NZ").copy(id = "b")
        val restored = afterDelete.withRuleRestored(b, afterId = "a", beforeId = "c")
        assertEquals(listOf(a, b, c), restored.rules)
        assertEquals(restored.rules, restored.withRuleRestored(b, afterId = "a", beforeId = "c").rules)
    }

    @Test
    fun `a blank id matches nothing, never every rule`() {
        val blanks = DataRuleBook(rules = listOf(rule("AU"), rule("NZ"))) // all id = ""
        assertEquals(blanks.rules, blanks.withRuleReplaced("", rule("FR")).rules)
        assertEquals(blanks.rules, blanks.withRuleRemoved("").rules)
    }

    @Test
    fun `duplicating copies the rule below it under a new, distinct id`() {
        val book = DataRuleBook(rules = listOf(rule("AU").copy(id = "a"), rule("NZ").copy(id = "b")))
        val dup = book.withRuleDuplicated(0, "a-copy")
        assertEquals(
            listOf(rule("AU").copy(id = "a"), rule("AU").copy(id = "a-copy"), rule("NZ").copy(id = "b")),
            dup.rules,
        )
        assertEquals(book.rules, book.withRuleDuplicated(9, "x").rules)
    }

    @Test
    fun `the preseeded default carries a stable id`() {
        assertEquals(listOf("default-eu-roaming"), DataRuleBook.defaultDataRules().map { it.id })
    }
}
