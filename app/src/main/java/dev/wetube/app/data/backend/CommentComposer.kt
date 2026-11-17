package dev.wetube.app.data.backend

import java.io.IOException

/**
 * The account-write side of the comment section.
 *
 * Reading comments needs no session, so every transport can do it. Writing one
 * is attributed to whoever is signed in, so it is a capability on [TubeBackend]
 * instead of a method every transport has to fail at: Invidious and Piped keep
 * the inherited `null`, and the sheet then offers no box at all rather than a
 * box that cannot post.
 *
 * There is no partial success here. The call returns only when the server
 * created the comment, and otherwise throws with YouTube's own reason, because
 * a refusal is the one part of this feature the app cannot phrase better than
 * the server does — a rate limit, a held-for-review note and a switched-off
 * comment section are three different sentences and three different answers.
 */
interface CommentComposer {

    /**
     * Posts [text] as a top-level comment on [videoId].
     *
     * Throws [CommentRefusedException] when YouTube answered but declined, and
     * an ordinary [IOException] when there was no answer at all. Callers reload
     * the thread afterwards rather than inserting the comment locally: the
     * server also decides where the new comment lands in the sort.
     */
    suspend fun post(videoId: String, text: String)
}

/**
 * Liking a comment and replying to one.
 *
 * Kept apart from [CommentComposer] because the two answer to different
 * questions. Posting is about the thread and mints its own parameters; reacting
 * is about one comment, and the parameters for that are handed out with the
 * comment itself — [CommentActions] is what the sheet passes back in.
 *
 * The same rule about failure applies: these return only when YouTube took the
 * action, and otherwise throw with the server's own sentence.
 */
interface CommentReactions {

    /**
     * Likes, or unlikes, the comment the action was minted for.
     *
     * [action] is `CommentActions.like` or `CommentActions.unlike`, never a
     * string this app builds: the parameter names the comment, the video and
     * the sort the page was in, and YouTube rejects anything it did not issue.
     */
    suspend fun like(action: String)

    /**
     * Posts [text] as a reply to the comment [params] was minted for.
     *
     * [params] is `CommentActions.reply`, the reply composer's own
     * `createCommentParams`; a top-level parameter would post a new top-level
     * comment instead of a reply.
     */
    suspend fun reply(params: String, text: String)
}

/**
 * A comment YouTube declined to create, carrying its explanation.
 *
 * [message] is shown as-is. It is an [IOException] so it travels the same path
 * as every other refusal the app already reports; the type only exists so a
 * server-declined write can be told apart from a transport that never reached
 * the server, which is what decides whether a retry is worth offering.
 */
class CommentRefusedException(message: String) : IOException(message)