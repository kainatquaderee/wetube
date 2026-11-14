package dev.wetube.app.player.remote

import java.util.Locale

/**
 * Builds the DIDL-Lite document that travels next to `SetAVTransportURI`.
 *
 * A renderer picks its decoder from the `upnp:class` and from the MIME type in
 * `res protocolInfo`, so a video and an audio track must not share one document
 * shape. DLNA control points also disagree about how much metadata they accept;
 * the `*` in `protocolInfo` is the portable form, and everything optional is
 * dropped before the document gets large enough for a TV to close the
 * connection instead of answering.
 */
internal object Didl {

    /** What a muxed progressive video looks like to a renderer. */
    const val MIME_VIDEO_MP4 = "video/mp4"

    /** What an audio-only track looks like when it is handed over alone. */
    const val MIME_AUDIO_MP4 = "audio/mp4"

    /** HLS manifests: best-effort, only some renderers accept them. */
    const val MIME_HLS = "application/vnd.apple.mpegurl"

    const val CLASS_VIDEO = "object.item.videoItem"
    const val CLASS_AUDIO = "object.item.audioItem.musicTrack"

    /** DLNA advertises byte-seek + time-seek and treats the item as fully playable. */
    private const val DLNA_OPERATIONS =
        "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"

    /** Upper bound for one document. Real TVs start dropping 4 KB metadata blocks. */
    const val MAX_BYTES = 3 * 1024

    /**
     * The `protocolInfo` for [mime].
     *
     * DLNA's fourth field describes how the source can be operated on: `OP=01`
     * allows both byte-range and time seeks, `CI=0` says the source is not
     * transcoded. HLS has no DLNA profile, so it is advertised as a plain
     * `http-get` resource with no operation parameters.
     */
    fun protocolInfo(mime: String): String {
        val type = mime.trim().lowercase(Locale.US)
        return if (type == MIME_HLS) {
            "http-get:*:$type:*"
        } else {
            "http-get:*:$type:$DLNA_OPERATIONS"
        }
    }

    /**
     * Builds one DIDL-Lite `item` pointing at [url].
     *
     * [title], the class and the resource are mandatory. [creator] and
     * [artworkUrl] are dropped, artwork first, while the document is over
     * [budgetBytes]; if the mandatory parts alone are already over budget the
     * item is returned as-is rather than truncated into something unparseable.
     */
    fun build(
        url: String,
        mime: String,
        title: String,
        creator: String = "",
        artworkUrl: String = "",
        audioOnly: Boolean = false,
        budgetBytes: Int = MAX_BYTES,
    ): String {
        require(url.isNotBlank()) { "A DIDL item needs a resource URL" }

        val type = mime.trim().ifEmpty {
            if (audioOnly) MIME_AUDIO_MP4 else MIME_VIDEO_MP4
        }
        val className = if (audioOnly) CLASS_AUDIO else CLASS_VIDEO
        val info = protocolInfo(type)

        fun render(includeCreator: Boolean, includeArtwork: Boolean): String = buildString {
            append("<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\"")
            append(" xmlns:dc=\"http://purl.org/dc/elements/1.1/\"")
            append(" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">")
            append("<item id=\"0\" parentID=\"-1\" restricted=\"1\">")
            append("<dc:title>").append(UpnpSoap.escape(title)).append("</dc:title>")
            if (includeCreator && creator.isNotBlank()) {
                append("<dc:creator>").append(UpnpSoap.escape(creator)).append("</dc:creator>")
            }
            if (includeArtwork && artworkUrl.isNotBlank()) {
                append("<upnp:albumArtURI>")
                append(UpnpSoap.escape(artworkUrl))
                append("</upnp:albumArtURI>")
            }
            append("<upnp:class>").append(className).append("</upnp:class>")
            // The MIME a device sees comes from the backend, not the device, but
            // the value is still written as an XML attribute: escaping it keeps a
            // stray quote from ending `protocolInfo` early and reshaping the item.
            append("<res protocolInfo=\"").append(UpnpSoap.escape(info)).append("\">")
            append(UpnpSoap.escape(url)).append("</res>")
            append("</item></DIDL-Lite>")
        }

        var document = render(includeCreator = true, includeArtwork = true)
        if (document.utf8Size() > budgetBytes) {
            document = render(includeCreator = true, includeArtwork = false)
        }
        if (document.utf8Size() > budgetBytes) {
            document = render(includeCreator = false, includeArtwork = false)
        }
        return document
    }

    private fun String.utf8Size(): Int = toByteArray(Charsets.UTF_8).size
}