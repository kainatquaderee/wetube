package dev.wetube.app.data.backend

import java.io.IOException

/**
 * Thrown when every playback profile refuses a video because of the viewer's
 * age.
 *
 * It carries YouTube's own sentence — "Sign in to confirm your age", or
 * "… may be inappropriate for some users" — so the player screen can still show
 * the server's explanation and the mature-content filter can still recognise
 * the video. The type exists so an age wall can be told apart from every other
 * refusal *and* from [MatureHiddenException], whose wording also mentions the
 * video being age-restricted: an age wall is the one refusal the app can still
 * play, by handing the video back to YouTube's own web player, and only this
 * exception may trigger that.
 */
class AgeGateException(message: String) : IOException(message)

/**
 * Whether a refusal sentence is an age wall, in any wording YouTube uses.
 *
 * Shared with [ContentFilterBackend] so the video that is remembered as
 * age-gated is the same video the player screen offers to open in the browser,
 * and the matcher lives beside the exception it drives rather than inside one
 * caller.
 */
internal fun isAgeWallMessage(message: String?): Boolean {
    val text = message.orEmpty()
    return text.contains("confirm your age", ignoreCase = true) ||
        text.contains("age-restricted", ignoreCase = true) ||
        text.contains("inappropriate for some users", ignoreCase = true)
}