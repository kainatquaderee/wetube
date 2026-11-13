package dev.wetube.app.player

import dev.wetube.app.data.model.Playback
import dev.wetube.app.data.model.StreamFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `pickVideo` is the whole quality policy: no Android types, no network. These
 * cases pin the fallbacks the settings screen promises (exact, next best below,
 * smallest above) and the rule that a video request never resolves to audio.
 */
class StreamResolverTest {

    private fun video(height: Int, mime: String = MP4, url: String = "https://cdn.test/v$height") =
        StreamFormat(
            url = url,
            label = "${height}p",
            mime = mime,
            bitrate = 1_000_000,
            height = height,
        )

    private fun audio(url: String = "https://cdn.test/a") =
        StreamFormat(url = url, label = "audio", mime = "audio/mp4", bitrate = 128_000)

    @Test
    fun `the pinned height is chosen when the video offers it`() {
        val playback = Playback(
            muxed = listOf(video(720)),
            videoOnly = listOf(video(1080), video(480)),
        )

        val pick = StreamResolver.pickVideo(playback, 720)!!

        assertEquals(720, pick.format.height)
        assertTrue(pick.muxed)
    }

    @Test
    fun `when the pinned height is missing the largest lower one wins`() {
        val playback = Playback(videoOnly = listOf(video(480), video(720), video(360)))

        val pick = StreamResolver.pickVideo(playback, 1080)!!

        assertEquals(720, pick.format.height)
        assertFalse(pick.muxed)
    }

    @Test
    fun `when nothing is lower the smallest higher one wins`() {
        val playback = Playback(videoOnly = listOf(video(1440), video(1080), video(2160)))

        val pick = StreamResolver.pickVideo(playback, 720)!!

        assertEquals(1080, pick.format.height)
    }

    @Test
    fun `auto prefers H 264 and caps at 1080p`() {
        val playback = Playback(
            videoOnly = listOf(
                video(2160),
                video(1440),
                video(1080),
                video(720),
                video(1080, WEBM),
            ),
        )

        val pick = StreamResolver.pickVideo(playback, preferredHeight = 0)!!

        assertEquals(1080, pick.format.height)
        assertEquals(MP4, pick.format.mime)
    }

    @Test
    fun `auto only goes above 1080p when nothing smaller exists`() {
        val playback = Playback(videoOnly = listOf(video(2160), video(1440)))

        val pick = StreamResolver.pickVideo(playback, preferredHeight = 0)!!

        assertEquals(2160, pick.format.height)
    }

    @Test
    fun `auto falls back to webm when there is no H 264 track`() {
        val playback = Playback(videoOnly = listOf(video(1080, WEBM), video(720, WEBM)))

        val pick = StreamResolver.pickVideo(playback, preferredHeight = 0)!!

        assertEquals(1080, pick.format.height)
        assertEquals(WEBM, pick.format.mime)
    }

    @Test
    fun `a muxed track wins a same-resolution tie`() {
        val playback = Playback(muxed = listOf(video(720)), videoOnly = listOf(video(720)))

        val pick = StreamResolver.pickVideo(playback, preferredHeight = 0)!!

        assertTrue(pick.muxed)
    }

    @Test
    fun `audio-only tracks are never picked for a video request`() {
        val playback = Playback(audioOnly = listOf(audio()))

        assertNull(StreamResolver.pickVideo(playback, preferredHeight = 0))
    }

    @Test
    fun `tracks without a URL are ignored`() {
        val playback = Playback(
            muxed = listOf(video(720, url = "")),
            videoOnly = listOf(video(1080, url = "   ")),
        )

        assertNull(StreamResolver.pickVideo(playback, preferredHeight = 0))
    }

    @Test
    fun `a preferred height still resolves when every height is missing`() {
        val playback = Playback(videoOnly = listOf(video(0), video(0)))

        val pick = StreamResolver.pickVideo(playback, preferredHeight = 720)

        assertNotNull(pick)
        assertEquals(0, pick!!.format.height)
    }

    private companion object {
        const val MP4 = "video/mp4"
        const val WEBM = "video/webm"
    }
}
