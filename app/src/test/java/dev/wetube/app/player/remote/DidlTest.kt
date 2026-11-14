package dev.wetube.app.player.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The DIDL document is what a renderer decides to play, so the class and the
 * `protocolInfo` are pinned, and the size guard is pinned to drop artwork before
 * creator before giving up — a TV that receives 4 KB of metadata just closes the
 * connection.
 */
class DidlTest {

    @Test
    fun `a video item uses the video class and the documented protocol info`() {
        val didl = Didl.build(
            url = "http://cdn.test/v.mp4",
            mime = Didl.MIME_VIDEO_MP4,
            title = "A video",
            creator = "A channel",
            artworkUrl = "https://cdn.test/t.jpg",
        )

        assertTrue(didl.startsWith("<DIDL-Lite"))
        assertTrue(didl.contains("xmlns:dc=\"http://purl.org/dc/elements/1.1/\""))
        assertTrue(didl.contains("xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\""))
        assertTrue(didl.contains("<dc:title>A video</dc:title>"))
        assertTrue(didl.contains("<dc:creator>A channel</dc:creator>"))
        assertTrue(didl.contains("<upnp:albumArtURI>https://cdn.test/t.jpg</upnp:albumArtURI>"))
        assertTrue(didl.contains("<upnp:class>object.item.videoItem</upnp:class>"))
        assertTrue(
            didl.contains(
                "<res protocolInfo=\"http-get:*:video/mp4:" +
                    "DLNA.ORG_OP=01;DLNA.ORG_CI=0;" +
                    "DLNA.ORG_FLAGS=01700000000000000000000000000000\">http://cdn.test/v.mp4</res>",
            ),
        )
        assertTrue(didl.endsWith("</item></DIDL-Lite>"))
    }

    @Test
    fun `an audio-only item uses the music track class and an audio MIME type`() {
        val didl = Didl.build(
            url = "http://cdn.test/a.m4a",
            mime = Didl.MIME_AUDIO_MP4,
            title = "A song",
            audioOnly = true,
        )

        assertTrue(didl.contains("<upnp:class>object.item.audioItem.musicTrack</upnp:class>"))
        assertTrue(didl.contains("protocolInfo=\"http-get:*:audio/mp4:DLNA.ORG_OP=01"))
        assertFalse(didl.contains("object.item.videoItem"))
    }

    @Test
    fun `an empty MIME type falls back to the class the caller asked for`() {
        val video = Didl.build(url = "http://cdn.test/v", mime = "", title = "v")
        val audio = Didl.build(url = "http://cdn.test/a", mime = "  ", title = "a", audioOnly = true)

        assertTrue(video.contains("protocolInfo=\"http-get:*:video/mp4:"))
        assertTrue(audio.contains("protocolInfo=\"http-get:*:audio/mp4:"))
    }

    @Test
    fun `an HLS manifest is advertised as a plain http-get resource`() {
        val didl = Didl.build(
            url = "http://cdn.test/live.m3u8",
            mime = Didl.MIME_HLS,
            title = "Live",
        )

        assertTrue(didl.contains("protocolInfo=\"http-get:*:application/vnd.apple.mpegurl:*\""))
    }

    @Test
    fun `title creator and artwork are escaped`() {
        val didl = Didl.build(
            url = "http://cdn.test/v.mp4?a=1&b=2",
            mime = Didl.MIME_VIDEO_MP4,
            title = "Rock & Roll <3 \"live\"",
            creator = "A & B",
            artworkUrl = "https://cdn.test/t.jpg?w=1&h=2",
        )

        assertTrue(didl.contains("<dc:title>Rock &amp; Roll &lt;3 &quot;live&quot;</dc:title>"))
        assertTrue(didl.contains("<dc:creator>A &amp; B</dc:creator>"))
        assertTrue(didl.contains("<upnp:albumArtURI>https://cdn.test/t.jpg?w=1&amp;h=2</upnp:albumArtURI>"))
        assertTrue(didl.contains(">http://cdn.test/v.mp4?a=1&amp;b=2</res>"))
    }

    @Test
    fun `a MIME type carrying a quote cannot escape the protocolInfo attribute`() {
        val didl = Didl.build(
            url = "http://cdn.test/v.mp4",
            mime = "video/\"mp4",
            title = "T",
        )

        // The quote must survive as an entity inside the attribute value, never
        // as its terminator, or a hostile MIME could reshape the whole item.
        assertTrue(
            didl.contains("protocolInfo=\"http-get:*:video/&quot;mp4:DLNA.ORG_OP=01"),
        )
        assertTrue(didl.contains(">http://cdn.test/v.mp4</res>"))
        assertFalse(didl.contains("protocolInfo=\"http-get:*:video/\"mp4"))
    }

    @Test
    fun `artwork is dropped before the document goes over budget`() {
        val artwork = "https://cdn.test/" + "a".repeat(200) + ".jpg"
        val full = Didl.build(
            url = "http://cdn.test/v.mp4",
            mime = Didl.MIME_VIDEO_MP4,
            title = "T",
            creator = "C",
            artworkUrl = artwork,
        )

        val trimmed = Didl.build(
            url = "http://cdn.test/v.mp4",
            mime = Didl.MIME_VIDEO_MP4,
            title = "T",
            creator = "C",
            artworkUrl = artwork,
            budgetBytes = full.length - 1,
        )

        assertTrue(full.contains("albumArtURI"))
        assertTrue(full.length > trimmed.length)
        assertFalse(trimmed.contains("albumArtURI"))
        // The creator is cheap enough to keep once the artwork is gone.
        assertTrue(trimmed.contains("<dc:creator>C</dc:creator>"))
        assertTrue(trimmed.contains("<dc:title>T</dc:title>"))
        assertTrue(trimmed.length <= full.length - 1)
    }

    @Test
    fun `the creator goes too when the budget is still exceeded`() {
        val didl = Didl.build(
            url = "http://cdn.test/v.mp4",
            mime = Didl.MIME_VIDEO_MP4,
            title = "T",
            creator = "C",
            artworkUrl = "https://cdn.test/t.jpg",
            budgetBytes = 200,
        )

        assertFalse(didl.contains("albumArtURI"))
        assertFalse(didl.contains("dc:creator"))
        assertTrue(didl.contains("<dc:title>T</dc:title>"))
        assertTrue(didl.contains("<upnp:class>object.item.videoItem</upnp:class>"))
        assertTrue(didl.contains("<res protocolInfo="))
    }

    @Test
    fun `the mandatory parts survive a budget nothing could fit`() {
        val didl = Didl.build(
            url = "http://cdn.test/v.mp4",
            mime = Didl.MIME_VIDEO_MP4,
            title = "T",
            creator = "C",
            artworkUrl = "https://cdn.test/t.jpg",
            budgetBytes = 1,
        )

        assertTrue(didl.contains("<dc:title>T</dc:title>"))
        assertTrue(didl.contains("<res protocolInfo="))
        assertTrue(didl.endsWith("</item></DIDL-Lite>"))
    }

    @Test
    fun `optional elements are simply omitted when the caller has no values`() {
        val didl = Didl.build(url = "http://cdn.test/v.mp4", mime = Didl.MIME_VIDEO_MP4, title = "T")

        assertFalse(didl.contains("dc:creator"))
        assertFalse(didl.contains("albumArtURI"))
    }

    @Test
    fun `an item without a resource URL is a programming error`() {
        assertThrows(IllegalArgumentException::class.java) {
            Didl.build(url = "  ", mime = Didl.MIME_VIDEO_MP4, title = "T")
        }
    }

    @Test
    fun `the default budget is the documented three kilobytes`() {
        assertEquals(3 * 1024, Didl.MAX_BYTES)
    }
}