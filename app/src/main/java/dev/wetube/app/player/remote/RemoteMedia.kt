package dev.wetube.app.player.remote

/**
 * What to hand a renderer: one URL, how to interpret it, and the labels a TV
 * shows next to it.
 *
 * Deliberately one resource and not a playlist. DLNA renderers play what they
 * are given; a merged `wetube-merge://` pair or an adaptive DASH manifest is not
 * something we can express here, so the caller resolves a single cast-eligible
 * progressive track first (see `StreamResolver.pickProgressive`).
 *
 * [videoId] is kept so the stream-death watchdog can re-resolve a fresh URL for
 * the same video without the playback manager knowing anything about backends.
 */
data class RemoteMedia(
    /** YouTube id of the video being cast; the key for re-resolving a URL. */
    val videoId: String,
    /** Absolute `http(s)` URL of the progressive track. */
    val url: String,
    /** MIME type as the renderer should see it, e.g. `video/mp4`. */
    val mime: String,
    /** Shown on the renderer's own screen. */
    val title: String,
    /** Channel name, shown under [title] when the renderer cares. */
    val creator: String = "",
    /** Poster image, dropped from the DIDL document when it would be too large. */
    val artworkUrl: String = "",
    /** An audio-only track needs a music class, not a video one. */
    val audioOnly: Boolean = false,
) {

    /**
     * The same item with a freshly resolved stream.
     *
     * Used by the watchdog: the metadata and identity are unchanged, only the
     * URL (and the MIME the backend paired with it) is new.
     */
    fun withStream(url: String, mime: String): RemoteMedia =
        if (url == this.url && mime == this.mime) this else copy(url = url, mime = mime)
}
