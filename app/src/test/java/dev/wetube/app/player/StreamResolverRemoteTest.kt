package dev.wetube.app.player

import dev.wetube.app.data.model.Playback
import dev.wetube.app.data.model.StreamFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cast path's stream policy: a renderer gets one URL it can open on its
 * own, so `pickProgressive` must never produce a track that needs a companion.
 * No Android types and no network — every case is a plain [Playback] value.
 */
class StreamResolverRemoteTest {

    private fun muxed(height: Int, mime: String = MP4, url: String = "https://cdn.test/muxed-$height") =
        StreamFormat(
            url = url,
            label = "${height}p",
            mime = mime,
            bitrate = 1_000_000,
            height = height,
            audio = true,
        )

    private fun videoOnly(height: Int, url: String = "https://cdn.test/video-$height") =
        StreamFormat(
            url = url,
            label = "${height}p",
            mime = MP4,
            bitrate = 2_000_000,
            height = height,
        )

    private fun audio(bitrate: Long = 128_000, url: String = "https://cdn.test/audio-$bitrate") =
        StreamFormat(url = url, label = "audio", mime = "audio/mp4", bitrate = bitrate, audio = true)

    @Test
    fun `the best muxed track at or below 1080p is chosen`() {
        val playback = Playback(muxed = listOf(muxed(360), muxed(720), muxed(1080)))

        val pick = StreamResolver.pickProgressive(playback, preferredHeight = 0, audioOnly = false)!!

        assertEquals(1080, pick.height)
        assertEquals(MP4, pick.mime)
    }

    @Test
    fun `auto ignores a muxed track above the 1080p cap when a smaller one exists`() {
        val playback = Playback(muxed = listOf(muxed(1440), muxed(720)))

        val pick = StreamResolver.pickProgressive(playback, preferredHeight = 0, audioOnly = false)!!

        assertEquals(720, pick.height)
    }

    @Test
    fun `a muxed 1440p is still cast when it is the only option`() {
        val playback = Playback(muxed = listOf(muxed(1440)))

        val pick = StreamResolver.pickProgressive(playback, preferredHeight = 0, audioOnly = false)!!

        assertEquals(1440, pick.height)
    }

    @Test
    fun `an explicit height walks exact then below then above`() {
        val playback = Playback(muxed = listOf(muxed(360), muxed(720), muxed(1080)))

        assertEquals(
            720,
            StreamResolver.pickProgressive(playback, preferredHeight = 720, audioOnly = false)!!.height,
        )
        assertEquals(
            720,
            StreamResolver.pickProgressive(playback, preferredHeight = 900, audioOnly = false)!!.height,
        )
        assertEquals(
            360,
            StreamResolver.pickProgressive(playback, preferredHeight = 360, audioOnly = false)!!.height,
        )

        // Nothing at or below the request: the smallest available one wins.
        val onlyTall = Playback(muxed = listOf(muxed(1080)))
        assertEquals(
            1080,
            StreamResolver.pickProgressive(onlyTall, preferredHeight = 720, audioOnly = false)!!.height,
        )
    }

    @Test
    fun `a request above the cap resolves to what the cap allows`() {
        val playback = Playback(muxed = listOf(muxed(1080), muxed(1440)))

        val pick = StreamResolver.pickProgressive(playback, preferredHeight = 1440, audioOnly = false)!!

        // A TV decodes 1080p in hardware; the cap is a guardrail, not a guess.
        assertEquals(1080, pick.height)
    }

    @Test
    fun `adaptive-only playback is not castable`() {
        val playback = Playback(
            videoOnly = listOf(videoOnly(1080), videoOnly(720)),
            audioOnly = listOf(audio()),
        )

        assertNull(StreamResolver.pickProgressive(playback, preferredHeight = 0, audioOnly = false))
    }

    @Test
    fun `the pick is never a video-only track nor a merge pseudo URL`() {
        val playback = Playback(
            muxed = listOf(muxed(720)),
            videoOnly = listOf(videoOnly(2160)),
            audioOnly = listOf(audio()),
        )

        val pick = StreamResolver.pickProgressive(playback, preferredHeight = 2160, audioOnly = false)!!

        // The 2160p adaptive track is taller, but a renderer cannot pair it
        // with the audio track itself, so the muxed 720p wins instead.
        assertEquals(720, pick.height)
        assertFalse(pick.url.contains(StreamResolver.MERGE_SCHEME))
    }

    @Test
    fun `HLS is the last resort when no muxed track exists`() {
        val playback = Playback(
            videoOnly = listOf(videoOnly(1080)),
            audioOnly = listOf(audio()),
            hls = "https://cdn.test/live.m3u8",
        )

        val pick = StreamResolver.pickProgressive(playback, preferredHeight = 0, audioOnly = false)!!

        assertEquals("https://cdn.test/live.m3u8", pick.url)
        assertEquals("application/vnd.apple.mpegurl", pick.mime)
        assertEquals("HLS", pick.label)
    }

    @Test
    fun `a muxed track beats the HLS manifest`() {
        val playback = Playback(
            muxed = listOf(muxed(720)),
            hls = "https://cdn.test/live.m3u8",
        )

        val pick = StreamResolver.pickProgressive(playback, preferredHeight = 0, audioOnly = false)!!

        assertEquals("https://cdn.test/muxed-720", pick.url)
    }

    @Test
    fun `a blank HLS manifest is not offered`() {
        val playback = Playback(videoOnly = listOf(videoOnly(1080)), hls = "  ")

        assertNull(StreamResolver.pickProgressive(playback, preferredHeight = 0, audioOnly = false))
    }

    @Test
    fun `audio-only prefers the highest-bitrate audio MP4`() {
        val playback = Playback(
            muxed = listOf(muxed(720)),
            audioOnly = listOf(audio(bitrate = 64_000), audio(bitrate = 256_000)),
        )

        val pick = StreamResolver.pickProgressive(playback, preferredHeight = 0, audioOnly = true)!!

        assertEquals(256_000, pick.bitrate)
        assertTrue(pick.url.endsWith("audio-256000"))
    }

    @Test
    fun `audio-only falls back to a muxed track when no audio stream exists`() {
        val playback = Playback(muxed = listOf(muxed(480)))

        val pick = StreamResolver.pickProgressive(playback, preferredHeight = 0, audioOnly = true)!!

        assertEquals("https://cdn.test/muxed-480", pick.url)
    }

    @Test
    fun `tracks with a blank URL are ignored`() {
        val playback = Playback(
            muxed = listOf(muxed(720, url = " ")),
            audioOnly = listOf(audio(url = "")),
            hls = null,
        )

        assertNull(StreamResolver.pickProgressive(playback, preferredHeight = 0, audioOnly = false))
        assertNull(StreamResolver.pickProgressive(playback, preferredHeight = 0, audioOnly = true))
    }

    @Test
    fun `the pick never asks for the merge scheme`() {
        val playback = Playback(muxed = listOf(muxed(720)))

        val pick = StreamResolver.pickProgressive(playback, preferredHeight = 0, audioOnly = false)!!

        assertFalse(pick.url.startsWith(StreamResolver.MERGE_SCHEME))
    }

    @Test
    fun `audio-only is never handed a video-only track`() {
        // The audio-only branch may fall back to a muxed file, but a bare
        // adaptive video track has no audio and is not a URL a renderer can
        // play on its own, so there is nothing castable here at all.
        val playback = Playback(videoOnly = listOf(videoOnly(1080), videoOnly(720)))

        assertNull(StreamResolver.pickProgressive(playback, preferredHeight = 0, audioOnly = true))
    }

    @Test
    fun `every pick is a self-contained resource a renderer can open`() {
        // Whatever the branch, the URL is a plain http(s) resource: never the
        // merge pseudo-scheme that only the local ExoPlayer knows how to expand.
        val cases = listOf(
            Playback(muxed = listOf(muxed(720))) to false,
            Playback(muxed = listOf(muxed(720)), hls = "https://cdn.test/live.m3u8") to false,
            Playback(videoOnly = listOf(videoOnly(1080)), hls = "https://cdn.test/live.m3u8") to false,
            Playback(muxed = listOf(muxed(480))) to true,
            Playback(audioOnly = listOf(audio(bitrate = 128_000))) to true,
        )

        for ((playback, audioOnly) in cases) {
            val pick = StreamResolver.pickProgressive(playback, preferredHeight = 0, audioOnly = audioOnly)!!
            assertFalse(pick.url, pick.url.startsWith(StreamResolver.MERGE_SCHEME))
            assertTrue(pick.url, pick.url.startsWith("http"))
        }
    }

    private companion object {
        const val MP4 = "video/mp4"
    }
}