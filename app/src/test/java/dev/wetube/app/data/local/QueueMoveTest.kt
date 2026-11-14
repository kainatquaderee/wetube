package dev.wetube.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * [moved] is the one reorder shared by the store and the queue screen's drag
 * handling, so its edges are pinned here. The no-op cases are asserted with
 * `assertSame` on purpose: returning the *same* instance is what lets
 * [LibraryStore.moveInQueue] skip a write, and the property is easy to break
 * by rewriting the function with `filter`/`sortedBy`.
 */
class QueueMoveTest {

    @Test
    fun `an empty list has nothing to move`() {
        val empty = emptyList<String>()

        assertSame(empty, moved(empty, 0, 0))
    }

    @Test
    fun `a single item cannot move`() {
        val one = listOf("a")

        assertSame(one, moved(one, 0, 0))
    }

    @Test
    fun `moving to the same index is a no-op`() {
        val list = listOf("a", "b", "c")

        assertSame(list, moved(list, 1, 1))
    }

    @Test
    fun `an out-of-range source is a no-op`() {
        val list = listOf("a", "b")

        assertSame(list, moved(list, -1, 0))
        assertSame(list, moved(list, 2, 0))
    }

    @Test
    fun `an out-of-range target is a no-op`() {
        val list = listOf("a", "b")

        assertSame(list, moved(list, 0, -1))
        assertSame(list, moved(list, 0, 2))
    }

    @Test
    fun `a forward move shifts the item toward the end`() {
        assertEquals(listOf("b", "c", "a"), moved(listOf("a", "b", "c"), 0, 2))
    }

    @Test
    fun `a backward move shifts the item toward the start`() {
        assertEquals(listOf("c", "a", "b"), moved(listOf("a", "b", "c"), 2, 0))
    }

    @Test
    fun `the input list is never mutated`() {
        val list = mutableListOf("a", "b", "c")

        val result = moved(list, 0, 2)

        assertEquals(listOf("a", "b", "c"), list)
        assertEquals(listOf("b", "c", "a"), result)
    }
}
