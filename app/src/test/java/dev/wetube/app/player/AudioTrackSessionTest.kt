package dev.wetube.app.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import dev.wetube.app.data.model.AudioStream
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The audio menu is built from whatever the player reports, so what it offers
 * and what it marks as playing is worth pinning without a device: a video with
 * dubs has to show each language once, named in the viewer's own language, with
 * the track that is actually playing ticked. The steering language is pinned
 * here too, because it is what keeps a multi-dub video from starting in a
 * language the viewer does not speak.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class AudioTrackSessionTest {

    /** An audio track as a manifest describes it: language, maybe a label. */
    private fun audio(language: String? = null, label: String? = null): Format =
        Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_MP4)
            .apply {
                language?.let { setLanguage(it) }
                label?.let { setLabel(it) }
            }
            .build()

    /** A video track, to prove non-audio groups never reach the menu. */
    private fun video(): Format = Format.Builder()
        .setSampleMimeType(MimeTypes.VIDEO_H264)
        .build()

    /**
     * One `Tracks.Group` around [formats], with [playing] the index the player
     * has selected (-1 for none). Every track is supported unless [support] says
     * otherwise, which is how the renderers' own verdict is spelled.
     */
    private fun group(
        vararg formats: Format,
        playing: Int = -1,
        support: IntArray? = null,
    ): Tracks.Group {
        val trackSupport = support ?: IntArray(formats.size) { C.FORMAT_HANDLED }
        val selected = BooleanArray(formats.size) { it == playing }
        return Tracks.Group(TrackGroup(*formats), true, trackSupport, selected)
    }

    private fun tracks(vararg groups: Tracks.Group): Tracks = Tracks(groups.toList())

    private fun labels(options: List<AudioTrackOption>): List<String> = options.map { it.label }

    @Test
    fun `each audio language is offered once, in media order`() {
        val options = audioTrackOptions(
            tracks(group(audio("en")), group(audio("ja"))),
            Locale.ENGLISH,
        )

        assertEquals(listOf("English", "Japanese"), labels(options))
    }

    @Test
    fun `a language the media lists twice is one row`() {
        val options = audioTrackOptions(
            tracks(group(audio("en")), group(audio("en"))),
            Locale.ENGLISH,
        )

        assertEquals(1, options.size)
        assertEquals("en", options.single().language)
    }

    @Test
    fun `a duplicate rendition stays marked while any copy is playing`() {
        val options = audioTrackOptions(
            tracks(group(audio("en")), group(audio("en"), playing = 0)),
            Locale.ENGLISH,
        )

        assertTrue(options.single().selected)
    }

    @Test
    fun `the playing track is the one marked, and a descriptive dub is its own row`() {
        val options = audioTrackOptions(
            tracks(
                group(audio("en")),
                group(audio("ja"), audio("ja", label = "Japanese descriptive"), playing = 1),
            ),
            Locale.ENGLISH,
        )

        assertEquals(
            listOf("English", "Japanese", "Japanese descriptive"),
            labels(options),
        )
        assertEquals(listOf(false, false, true), options.map { it.selected })
        assertEquals(1, options[2].trackIndex)
    }

    @Test
    fun `a format's own label outranks its language name`() {
        val options = audioTrackOptions(
            tracks(group(audio("en", label = "English (US)"))),
            Locale.ENGLISH,
        )

        assertEquals("English (US)", options.single().label)
    }

    @Test
    fun `a track with neither language nor label still gets a row`() {
        val options = audioTrackOptions(tracks(group(audio())), Locale.ENGLISH)

        assertEquals("Unknown language", options.single().label)
    }

    @Test
    fun `two untagged tracks are two rows, not one`() {
        val options = audioTrackOptions(
            tracks(group(audio(null, null)), group(audio(null, null))),
            Locale.ENGLISH,
        )

        // Nothing in the manifest tells them apart, so the pair alone gave both
        // the same key and the second one — unselectable, because the menu
        // offered it only as the first — silently disappeared.
        assertEquals(listOf("Unknown language", "Unknown language"), labels(options))
        assertNotEquals(options[0].key, options[1].key)
        assertEquals(listOf(0, 1), options.map { it.untagged })
    }

    @Test
    fun `two untagged tracks in one group keep their own rows too`() {
        val options = audioTrackOptions(tracks(group(audio(), audio())), Locale.ENGLISH)

        assertEquals(2, options.size)
        assertEquals(listOf(0, 1), options.map { it.trackIndex })
    }

    @Test
    fun `video groups are not offered as audio`() {
        val options = audioTrackOptions(
            tracks(group(video()), group(audio("en"))),
            Locale.ENGLISH,
        )

        assertEquals(listOf("English"), labels(options))
    }

    @Test
    fun `the language names follow the viewer's locale`() {
        val options = audioTrackOptions(tracks(group(audio("ja"))), Locale.JAPAN)

        assertEquals("日本語", options.single().label)
    }

    @Test
    fun `a dub the renderers cannot play is not offered, so it is never marked`() {
        val options = audioTrackOptions(
            tracks(
                group(audio("en")),
                group(
                    audio("ja"),
                    audio("ja", label = "Japanese descriptive"),
                    support = intArrayOf(C.FORMAT_HANDLED, C.FORMAT_UNSUPPORTED_TYPE),
                    // The player somehow selected the undecodable copy: the row
                    // that says what is playing must not follow it there.
                    playing = 1,
                ),
            ),
            Locale.ENGLISH,
        )

        assertEquals(listOf("English", "Japanese"), labels(options))
        assertEquals(listOf(false, false), options.map { it.selected })
    }

    @Test
    fun `a group nothing in it can play contributes no rows`() {
        val options = audioTrackOptions(
            tracks(
                group(audio("en")),
                group(audio("ja"), support = intArrayOf(C.FORMAT_UNSUPPORTED_TYPE)),
            ),
            Locale.ENGLISH,
        )

        assertEquals(listOf("English"), labels(options))
    }

    @Test
    fun `the default steering speaks the device language`() {
        assertEquals("ja", preferredAudioLanguage(Locale.JAPAN))
        assertEquals("en", preferredAudioLanguage(Locale.US))
    }

    @Test
    fun `a locale that names no language leaves the choice to the player`() {
        assertEquals("", preferredAudioLanguage(Locale.ROOT))
    }
}
/**
 * The dub a video starts in, and the rows a dubbed video offers.
 *
 * Without these the player starts in whatever language YouTube lists first, so
 * the rule that decides it — and the fact that a single-audio video keeps its
 * one row — is worth pinning without a device.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class AudioStreamDefaultTest {

    private fun dub(
        language: String,
        default: Boolean = false,
        original: Boolean = false,
    ): AudioStream = AudioStream(
        url = "https://cdn.test/$language",
        mime = MimeTypes.AUDIO_MP4,
        languageCode = language,
        displayName = language.uppercase(),
        isDefault = default,
        original = original,
    )

    @Test
    fun `the default pick is the viewer's own language`() {
        val streams = listOf(dub("ja", original = true), dub("en", default = true), dub("es"))

        // The device language wins even over the track YouTube calls default:
        // that flag is about the upload, not about who is watching.
        assertEquals("es", defaultAudioStream(streams, Locale.forLanguageTag("es"))?.languageCode)
    }

    @Test
    fun `without a locale match the default flag decides, then the original`() {
        val flagged = listOf(dub("ja", original = true), dub("en", default = true))
        val unflagged = listOf(dub("ja"), dub("en", original = true))

        assertEquals("en", defaultAudioStream(flagged, Locale.GERMAN)?.languageCode)
        assertEquals("en", defaultAudioStream(unflagged, Locale.GERMAN)?.languageCode)
    }

    @Test
    fun `a single-audio video offers exactly one option`() {
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_MP4)
            .setLanguage("en")
            .build()
        val only = Tracks.Group(
            TrackGroup(format),
            true,
            intArrayOf(C.FORMAT_HANDLED),
            booleanArrayOf(true),
        )
        val options = audioTrackOptions(Tracks(listOf(only)), Locale.ENGLISH)

        assertEquals(1, options.size)
        // Nothing to rebuild: the one row is a track of the stream itself.
        assertEquals(null, options.single().stream)
    }
}
