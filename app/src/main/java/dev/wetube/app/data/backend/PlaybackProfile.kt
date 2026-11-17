package dev.wetube.app.data.backend

/**
 * The client identities a playback resolve can go through, in the order they
 * are tried.
 *
 * `IOS` leads because it answers with plain, un-throttled adaptive URLs for
 * every quality. When a URL it handed out has already been rejected at
 * playback, asking the same client again tends to produce the same rejection,
 * so a recovery drops it and starts from the unbreakable Android fallback,
 * still falling through to VR.
 */
internal enum class PlaybackProfile { IOS, ANDROID, VR }

/**
 * What one playback resolve should do about the fallback profile.
 *
 * The answer belongs to the call, not to the transport, because resolves overlap:
 * the Shorts section resolves the clips after the one on screen while the player
 * recovers from a URL the CDN just refused. A transport-wide "fall back next"
 * flag is consumed by whichever resolve reaches it first, so the prefetch can
 * take the recovery meant for the player and then resolve a neighbour through a
 * profile it never asked for.
 */
enum class PlaybackFallback {

    /**
     * Apply a pending
     * [dev.wetube.app.data.backend.TubeBackend.forcePlaybackFallback] request and
     * consume it; resolve normally when there is none.
     *
     * The default, and the route the full player takes: it asks before it
     * resolves instead of passing the answer.
     */
    Inherit,

    /** This resolve is the recovery: lead with the fallback profile. */
    Force,

    /**
     * An ordinary resolve. It is neither affected by a pending request nor able
     * to consume one, which is what makes a prefetch harmless.
     */
    Never,
}

/**
 * What [request] does to a pending `forcePlaybackFallback()` request: [fallback]
 * says whether this resolve starts from the fallback profile, and [keepArmed]
 * whether the request is still waiting for the resolve that asked for it.
 */
internal data class PlaybackFallbackPlan(val fallback: Boolean, val keepArmed: Boolean)

/**
 * Resolves one call's [request] against the request a caller armed with
 * `forcePlaybackFallback()` (see [PlaybackFallback]).
 *
 * Pure so the rule can be pinned without a transport: a prefetch must be able to
 * run in the middle of a recovery, so its call may not take an armed request and
 * may not be turned into one either.
 */
internal fun playbackFallbackPlan(
    request: PlaybackFallback,
    armed: Boolean,
): PlaybackFallbackPlan = when (request) {
    PlaybackFallback.Inherit -> PlaybackFallbackPlan(fallback = armed, keepArmed = false)
    PlaybackFallback.Force -> PlaybackFallbackPlan(fallback = true, keepArmed = false)
    PlaybackFallback.Never -> PlaybackFallbackPlan(fallback = false, keepArmed = armed)
}

/**
 * The order to try [PlaybackProfile]s in for one resolve.
 *
 * [skipLeading] is what a recovery sets after a stream URL was rejected: it
 * drops `IOS`, whose response was just refused, and starts from `ANDROID`,
 * whose progressive file always plays, falling through to `VR` as the last
 * resort. Skipping only ever removes the leader; a fallback never invents a
 * profile the normal order does not already contain.
 */
internal fun playbackProfileOrder(skipLeading: Boolean): List<PlaybackProfile> =
    if (skipLeading) PlaybackProfile.entries.drop(1) else PlaybackProfile.entries