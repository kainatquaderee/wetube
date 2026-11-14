package dev.wetube.app.ui.library

import dev.wetube.app.data.model.VideoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The queue screen's two decisions are pure functions so they can be tested
 * without a device or a Compose host: turning a drag distance into a target
 * index, and choosing the item a finished video should auto-play. Those are
 * also exactly the parts that are hard to read back off a running screen.
 */
class QueueLogicTest {

    private fun video(id: String) = VideoItem(id = id, title = "Video $id", channelName = "Chan")

    // ----- nextQueued -------------------------------------------------------

    @Test
    fun `an empty queue has nothing to auto-play`() {
        assertNull(nextQueued(emptyList()))
    }

    @Test
    fun `the head of the queue is what plays next`() {
        val head = video("a")

        assertSame(head, nextQueued(listOf(head, video("b"))))
    }

    @Test
    fun `the video on screen is skipped so the queue can advance`() {
        // "Play all" leaves the starting video at the head; auto-play must move
        // past it instead of freezing on the ended frame.
        val head = video("a")
        val next = video("b")

        assertSame(next, nextQueued(listOf(head, next), currentId = "a"))
    }

    @Test
    fun `a queue holding only the current video has nothing to play`() {
        assertNull(nextQueued(listOf(video("a")), currentId = "a"))
    }

    @Test
    fun `without a current video the head still plays next`() {
        val head = video("a")

        assertSame(head, nextQueued(listOf(head, video("b")), currentId = null))
    }

    // ----- targetIndex ------------------------------------------------------

    @Test
    fun `an unmeasured row leaves the item where it is`() {
        assertEquals(3, targetIndex(from = 3, dragY = 100f, rowHeightPx = 0f, size = 5))
    }

    @Test
    fun `an empty list leaves the item where it is`() {
        assertEquals(0, targetIndex(from = 0, dragY = 100f, rowHeightPx = 50f, size = 0))
    }

    @Test
    fun `less than half a row does not move`() {
        assertEquals(1, targetIndex(from = 1, dragY = 20f, rowHeightPx = 50f, size = 5))
    }

    @Test
    fun `more than half a row moves down one`() {
        assertEquals(2, targetIndex(from = 1, dragY = 30f, rowHeightPx = 50f, size = 5))
    }

    @Test
    fun `a drag upward moves toward the start`() {
        assertEquals(0, targetIndex(from = 2, dragY = -120f, rowHeightPx = 50f, size = 5))
    }

    @Test
    fun `a drag past the end clamps to the last index`() {
        assertEquals(4, targetIndex(from = 3, dragY = 1000f, rowHeightPx = 50f, size = 5))
    }

    @Test
    fun `a drag past the start clamps to zero`() {
        assertEquals(0, targetIndex(from = 1, dragY = -1000f, rowHeightPx = 50f, size = 5))
    }
}
