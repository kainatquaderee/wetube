package dev.wetube.app.player

import android.net.Uri
import androidx.media3.common.MediaItem
import dev.wetube.app.data.model.AudioStream
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

/**
 * Switching dubs rebuilds the item, because a dub is its own URL.
 *
 * A pick that produces an item still pointing at the old audio URL is a pick
 * that does nothing, so the rebuilt URL is pinned here; and a video without a
 * separate audio stream must come back untouched rather than be turned into
 * something that cannot play.
 */
class StreamResolverAudioPickTest {

    private fun dub(language: String): AudioStream = AudioStream(
        url = "https://cdn.test/$language.m4a",
        mime = "audio/mp4",
        languageCode = language,
    )

    private val english = dub("en")
    private val japanese = dub("ja")

    /** The item URL [StreamResolver.mediaItem] builds for a dubbed video. */
    private fun mergedUrl(): String = StreamResolver.mergeUrl(
        video = "https://cdn.test/v1080.mp4",
        vmime = "video/mp4",
        audio = english.url,
        amime = "audio/mp4",
    )

    @Test
    fun `a pick produces an item URL carrying that track's audio`() {
        val rebuilt = StreamResolver.audioSwapUrl(mergedUrl(), japanese, listOf(english, japanese))!!

        assertTrue(rebuilt.startsWith(StreamResolver.MERGE_SCHEME))
        // The video half rides along; only the audio half is replaced.
        assertTrue(rebuilt.contains("video=https%3A%2F%2Fcdn.test%2Fv1080.mp4"))
        assertTrue(rebuilt.contains("audio=https%3A%2F%2Fcdn.test%2Fja.m4a"))
        assertFalse(rebuilt.contains("en.m4a"))
    }

    @Test
    fun `a picked dub survives a rebuild at another height`() {
        val playback = Playback(
            videoOnly = listOf(
                StreamFormat("https://cdn.test/v1080.mp4", "1080p", "video/mp4", height = 1080),
                StreamFormat("https://cdn.test/v480.mp4", "480p", "video/mp4", height = 480),
            ),
            audioOnly = listOf(StreamFormat(english.url, "en", "audio/mp4", audio = true)),
            audioStreams = listOf(english, japanese),
        )

        // Every rebuild of the same video goes through this: without the pick
        // the audio half silently falls back to the default dub, which is how
        // choosing Japanese and then changing quality undid the choice.
        for (height in listOf(480, 1080)) {
            val video = StreamResolver.pickVideo(playback, height)!!.format
            val audio = StreamResolver.audioPick(playback, japanese)!!

            assertEquals(japanese.url, audio.url)
            assertEquals(japanese.mime, audio.mime)
            assertTrue(StreamResolver.mergeUrl(video.url, video.mime, audio.url, audio.mime)
                .contains("audio=https%3A%2F%2Fcdn.test%2Fja.m4a"))
        }
        // No pick leaves the default rule in charge, unchanged.
        assertEquals(english.url, StreamResolver.audioPick(playback)!!.url)
    }

    @Test
    fun `a video with one audio track is never rebuilt`() {
        // A muxed progressive file: its audio is baked into the video, so no
        // URL swap can change the language and the item must be left alone.
        assertNull(
            StreamResolver.audioSwapUrl(
                "https://cdn.test/m.mp4",
                japanese,
                listOf(english, japanese),
            ),
        )
    }
}
