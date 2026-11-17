package dev.wetube.app.data.backend

import java.net.URLDecoder
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the wire format of a posted comment.
 *
 * Both halves are guesses that only a real request can confirm, so both are
 * pinned exactly rather than structurally. The parameters are a protobuf
 * message that YouTube's own page percent-encodes, so the bytes *and* the
 * escaping matter: a base64 `+` left unescaped silently turns into a space in
 * the JSON string that carries it. The answer is read as data because a refusal
 * arrives as `200`, which is the one thing about this endpoint that has already
 * bitten sync (see `docs/SYNC_PROTOCOL.md`).
 */
class CommentWireTest {

    @Test
    fun `the parameters carry the video id and the composer literal`() {
        val raw = URLDecoder.decode(createCommentParams("dQw4w9WgXcQ"), "UTF-8")
        val expected = byteArrayOf(0x12, 0x0B) +
            "dQw4w9WgXcQ".toByteArray() +
            byteArrayOf(0x2A, 0x02, 0x08, 0x00, 0x50, 0x07)
        assertArrayEquals(expected, Base64.getDecoder().decode(raw))
    }

    @Test
    fun `the encoded parameters match what the page would send`() {
        assertEquals("EgtkUXc0dzlXZ1hjUSoCCABQBw%3D%3D", createCommentParams("dQw4w9WgXcQ"))
        assertEquals("EgthQjMtXzl6enpaWioCCABQBw%3D%3D", createCommentParams("aB3-_9zzzZZ"))
    }

    @Test
    fun `base64 separators and padding are escaped, not left raw`() {
        // `encodeURIComponent` semantics: a raw `+` in a JSON string is a plus
        // only by luck of the reader, and a raw `=`/`/` is not what the page
        // sends either.
        assertEquals("a%2Bb%2Fc%3D", percentEncode("a+b/c="))
        assertEquals("unreserved-_.'()!~*", percentEncode("unreserved-_.'()!~*"))
    }

    @Test
    fun `an action in the answer means the comment was created`() {
        val body = """{"actions":[{"createCommentAction":{"trackingParams":"x"}}]}"""
        assertTrue(commentPostResult(parse(body)) is CommentPost.Posted)
    }

    @Test
    fun `a refusal keeps the server's own sentence`() {
        val body = """{"error":{"message":"Commenting is turned off for this video."}}"""
        assertEquals(
            CommentPost.Refused("Commenting is turned off for this video."),
            commentPostResult(parse(body)),
        )
    }

    @Test
    fun `a plain string error is read the same way`() {
        assertEquals(
            CommentPost.Refused("Sign in to continue."),
            commentPostResult(parse("""{"error":"Sign in to continue."}""")),
        )
    }

    @Test
    fun `a body with neither is a refusal, never a success`() {
        // The status code already said 200 by the time this runs, so "nothing
        // here" must never be read as "it worked".
        assertEquals(
            CommentPost.Refused("YouTube did not accept the comment."),
            commentPostResult(parse("{}")),
        )
    }

    @Test
    fun `a displayed error action keeps the sentence YouTube wrote`() {
        // The shape a real signed-out post comes back with: 200, an action
        // whose only content is the message the page would have shown.
        val body = """
            {
              "actions": [
                {
                  "clickTrackingParams": "CAAQ",
                  "showErrorAction": {
                    "errorMessage": {
                      "messageRenderer": {
                        "text": { "simpleText": "Comment failed to post." },
                        "trackingParams": "CAEQ"
                      }
                    }
                  }
                }
              ],
              "trackingParams": "CAAQ"
            }
        """.trimIndent()
        assertEquals(
            CommentPost.Refused("Comment failed to post."),
            commentPostResult(parse(body)),
        )
    }

    @Test
    fun `the verdict is what says a like was taken`() {
        // A real like answer: 200, no comment anywhere, and the only thing that
        // reports the outcome is the list of results. Reading this as a refusal
        // is what made a working like flip back to unliked.
        val body = """
            {
              "responseContext": { "responseId": "IhMI" },
              "actionResults": [
                { "status": "STATUS_SUCCEEDED", "feedback": "FEEDBACK_LIKE" }
              ],
              "actions": [
                {
                  "clickTrackingParams": "IhMI",
                  "runAttestationCommand": {
                    "ids": [ { "commentId": "Ugz8WpD8ZclKIDQfbX54AaABAg" } ],
                    "engagementType": "ENGAGEMENT_TYPE_COMMENT_LIKE"
                  }
                }
              ]
            }
        """.trimIndent()
        assertTrue(commentPostResult(parse(body)) is CommentPost.Posted)
    }

    @Test
    fun `the verdict is what says a reply was taken`() {
        // A real reply answer: the new comment is declared as a
        // `createCommentReplyAction`, not a `createCommentAction`, and the
        // verdict is a single object rather than a list.
        val body = """
            {
              "responseContext": { "responseId": "IhMI" },
              "actions": [
                {
                  "clickTrackingParams": "CAAQ",
                  "createCommentReplyAction": {
                    "parentCommentId": "Ugz8WpD8ZclKIDQfbX54AaABAg",
                    "contents": { "commentViewModel": { "commentId": "Ugz8.W6dE" } },
                    "replyToCommentId": ""
                  }
                }
              ],
              "actionResult": {
                "status": "STATUS_SUCCEEDED",
                "feedbackText": { "runs": [ { "text": "Reply added" } ] }
              },
              "trackingParams": "CAAQ"
            }
        """.trimIndent()
        assertTrue(commentPostResult(parse(body)) is CommentPost.Posted)
    }

    @Test
    fun `a failed verdict is still a refusal`() {
        // The verdict is read for its value, not its presence: a write YouTube
        // declined can answer with the same field saying so.
        val body = """
            {
              "actionResults": [ { "status": "STATUS_FAILED", "feedback": "FEEDBACK_LIKE" } ]
            }
        """.trimIndent()
        assertTrue(commentPostResult(parse(body)) is CommentPost.Refused)
    }

    @Test
    fun `a refusal outranks a verdict`() {
        // An explanation is never overruled by a status beside it.
        val body = """
            {
              "actionResult": { "status": "STATUS_SUCCEEDED" },
              "error": { "message": "Commenting is turned off for this video." }
            }
        """.trimIndent()
        assertEquals(
            CommentPost.Refused("Commenting is turned off for this video."),
            commentPostResult(parse(body)),
        )
    }

    private fun parse(body: String): JsonObject = Json.parseToJsonElement(body).obj()!!

    @Test
    fun `a like sends the parameter as it was handed out`() {
        val body = commentLikeBody("EgtkUXc0dzlXZ1hjUSoCCABQBw%3D%3D")

        // One field, and the parameter untouched: the escaping is the server's
        // own, so re-encoding it here would be the bug.
        assertEquals(
            listOf("actions"),
            body.keys.toList(),
        )
        // The parameter travels in an array, which is the only shape the
        // endpoint decodes; a bare string is answered with INVALID_ARGUMENT.
        val actions = body["actions"] as JsonArray
        assertEquals(1, actions.size)
        assertEquals("EgtkUXc0dzlXZ1hjUSoCCABQBw%3D%3D", (actions[0] as JsonPrimitive).content)
    }

    @Test
    fun `a reply sends the text with the parameter off the button`() {
        val body = commentReplyBody("REPLY-PARAMS", "  hello  ")

        // The text is sent as typed: the composer sends it the same way, and
        // YouTube trims its own copy.
        assertEquals(listOf("commentText", "createReplyParams"), body.keys.toList())
        assertEquals("  hello  ", body.string("commentText"))
        assertEquals("REPLY-PARAMS", body.string("createReplyParams"))
    }

    private fun JsonObject.string(key: String): String =
        (this[key] as JsonPrimitive).content
}