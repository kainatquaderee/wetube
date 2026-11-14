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