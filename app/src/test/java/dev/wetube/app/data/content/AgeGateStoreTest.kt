package dev.wetube.app.data.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the FIFO cap that keeps the age-gate id set from growing forever.
 * The `AgeGateStore` itself needs a `Context` and a real DataStore, so the cap
 * logic was extracted into [cappedAgeGateIds] to make it testable on the JVM.
 */
class AgeGateStoreTest {

    @Test
    fun `new ids are appended in insertion order`() {
        val ids = listOf("a", "b", "c")
            .fold(emptyList<String>()) { acc, id -> cappedAgeGateIds(acc, id, 500) }

        assertEquals(listOf("a", "b", "c"), ids)
    }

    @Test
    fun `the set never exceeds the cap and evicts the oldest first`() {
        var ids = emptyList<String>()
        repeat(505) { index -> ids = cappedAgeGateIds(ids, "id$index", 500) }

        assertEquals(500, ids.size)
        assertFalse("id0" in ids)
        assertFalse("id4" in ids)
        assertTrue("id5" in ids)
        assertEquals("id5", ids.first())
        assertEquals("id504", ids.last())
    }

    @Test
    fun `re-marking an existing id does not make it newer`() {
        // `a` is the oldest. Re-marking it must not move it to the end, so it
        // is still the one evicted when `d` arrives.
        var ids = listOf("a", "b", "c")
        ids = cappedAgeGateIds(ids, "a", 3)
        assertEquals(listOf("a", "b", "c"), ids)

        ids = cappedAgeGateIds(ids, "d", 3)
        assertEquals(listOf("b", "c", "d"), ids)
    }

    @Test
    fun `duplicate and blank entries already stored are normalised`() {
        val ids = cappedAgeGateIds(listOf("a", "a", "", "b"), "c", 500)

        assertEquals(listOf("a", "b", "c"), ids)
    }

    @Test
    fun `a cap of one keeps only the newest id`() {
        assertEquals(listOf("new"), cappedAgeGateIds(listOf("old"), "new", 1))
    }
}
