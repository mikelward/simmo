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
}
