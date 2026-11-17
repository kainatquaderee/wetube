package dev.wetube.app.ui

import dev.wetube.app.data.backend.MatureHiddenException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The age-gate copy the player ends up showing.
 *
 * Two refusals look alike in a log and must not look alike to the viewer:
 * YouTube's age wall, which a signed-in session can clear, and the app's own
 * refusal while "Show mature content" is off, whose sentence names the exact
 * switch to turn. The second one used to be flattened into "Something went
 * wrong", which told the viewer nothing they could act on.
 */
class FriendlyErrorTest {

    @Test
    fun `an age wall names the two conditions that clear it`() {
        val message = friendlyError(
            IOException(
                "Sign in to confirm your age. This video may be inappropriate for some users.",
            ),
        )
        assertTrue(message.contains("signed-in session"))
        assertTrue(message.contains("Show mature content"))
    }

    @Test
    fun `the mature-content refusal keeps the backend's instruction`() {
        assertEquals(
            "This video is age-restricted. Enable \"Show mature content\" in Settings to open it.",
            friendlyError(MatureHiddenException()),
        )
    }

    @Test
    fun `a server body that mentions an age gate is not echoed`() {
        // The phrase only earns the mature-content sentence when the app wrote
        // it; a transport error carries whatever the server sent, URL included.
        assertEquals(
            "The server is having trouble. Try again, or switch to another server in Settings.",
            friendlyError(
                IOException(
                    "HTTP 503 for https://example.test/watch?v=x: this video is age-restricted",
                ),
            ),
        )
    }

    @Test
    fun `an unclassified failure stays generic`() {
        assertEquals(
            "Something went wrong. Please try again.",
            friendlyError(IllegalStateException("boom")),
        )
    }
}