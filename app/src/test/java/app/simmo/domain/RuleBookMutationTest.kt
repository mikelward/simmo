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
}
