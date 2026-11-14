package dev.wetube.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The pluralising helper behind the first-sync consent counts.
 *
 * The wording there is a safety boundary — it is what tells the user what a merge
 * will change — so `"2 removal(s)"` is not acceptable and the noun form is
 * checked directly rather than trusted.
 */
class UiFormatTest {

    @Test
    fun `one takes the singular`() {
        assertEquals("1 subscription", countOf(1, "subscription"))
    }

    @Test
    fun `zero takes the plural`() {
        assertEquals("0 subscriptions", countOf(0, "subscription"))
    }

    @Test
    fun `many takes the plural`() {
        assertEquals("12 subscriptions", countOf(12, "subscription"))
    }

    @Test
    fun `the singular form is the only special case`() {
        // Anything that is not exactly one is plural, so a stray negative cannot
        // slip a singular noun into the sentence.
        assertEquals("-1 subscriptions", countOf(-1, "subscription"))
    }

    @Test
    fun `an irregular plural can be supplied`() {
        assertEquals("1 child", countOf(1, "child", "children"))
        assertEquals("2 children", countOf(2, "child", "children"))
    }

    @Test
    fun `the noun is spelled out rather than templated`() {
        // Regression guard for the copy that used to read "3 item(s)".
        assertEquals("3 items", countOf(3, "item"))
    }
}
