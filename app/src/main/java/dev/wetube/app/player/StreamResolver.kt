package dev.wetube.app.player

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import dev.wetube.app.data.model.Playback
import dev.wetube.app.data.model.StreamFormat
import dev.wetube.app.data.model.VideoDetail

/**
 * Turns a backend's [dev.wetube.app.data.model.Playback] into a Media3 item.
 *
 * Backends give us either muxed progressive tracks, or separate video/audio
 * tracks. When separate tracks give better quality we wrap both URLs in a
 * private `wetube-merge://` URI that [StreamSourceFactory] expands into a
 * MergingMediaSource inside the playback service. That keeps the UI layer free
 * of Media3 internals and still works across the MediaSession boundary.
 */
object StreamResolver {

    const val MERGE_SCHEME = "wetube-merge"

    /**
     * Builds the item for [detail].
     *
     * [preferredHeight] is the user's quality choice: `0` means Auto (play the
     * best the network can carry), any other value asks for that resolution and
     * falls back to the closest one the video actually offers.
     */
    fun mediaItem(
        detail: VideoDetail,
        audioOnly: Boolean,
        preferredHeight: Int = 0,
    ): MediaItem? {
        val p = detail.playback
        if (p.isEmpty) return null

        val metadata = MediaMetadata.Builder()
            .setTitle(detail.item.title)
            .setArtist(detail.item.channelName)
            .apply {
                if (detail.item.thumbnail.isNotBlank()) setArtworkUri(Uri.parse(detail.item.thumbnail))
            }
            .build()

        // HLS is one adaptive manifest — which is how live streams arrive — and
        // there is nothing to choose inside it at this layer, so the quality
        // preference does not apply. It still wins whenever a backend offers it.
        p.hls?.takeIf { it.isNotBlank() }?.let { url ->
            return MediaItem.Builder()
                .setUri(url)
                .setMediaId(detail.item.id)
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                .setMediaMetadata(metadata)
                .build()
        }

        val bestAudio = pickAudio(p.audioOnly)

        if (audioOnly) {
            val track = bestAudio ?: p.muxed.filter { it.url.isNotBlank() }.maxByOrNull { it.height }
            return track?.let { build(it.url, it.mime, detail.item.id, metadata) }
        }

        val video = pickVideo(p, preferredHeight)

        // A muxed track already carries audio, so it needs no companion. An
        // adaptive track is paired with the best audio track we found.
        if (video != null && video.muxed) {
            return build(video.format.url, video.format.mime, detail.item.id, metadata)
        }
        if (video != null && bestAudio != null) {
            val uri = Uri.Builder()
                .scheme(MERGE_SCHEME)
                .appendQueryParameter("video", video.format.url)
                .appendQueryParameter("audio", bestAudio.url)
                .appendQueryParameter("vmime", video.format.mime)
                .appendQueryParameter("amime", bestAudio.mime)
                .build()
            return MediaItem.Builder()
                .setUri(uri)
                .setMediaId(detail.item.id)
                .setMediaMetadata(metadata)
                .build()
        }

        // No usable video track (or no audio to pair it with): keep whatever we
        // have so playback can still be attempted.
        val fallback = video?.format ?: bestAudio
        return fallback?.let { build(it.url, it.mime, detail.item.id, metadata) }
    }

    /**
     * The height [mediaItem] would actually play at, or `0` when that cannot be
     * known from here (HLS manifests and audio-only playback). The quality
     * picker uses it to show what "Auto" resolved to.
     */
    fun effectiveHeight(detail: VideoDetail, audioOnly: Boolean, preferredHeight: Int): Int {
        if (audioOnly || detail.playback.hls?.isNotBlank() == true) return 0
        return pickVideo(detail.playback, preferredHeight)?.format?.height ?: 0
    }

    private fun build(
        url: String,
        mime: String,
        videoId: String,
        metadata: MediaMetadata,
    ): MediaItem =
        MediaItem.Builder()
            .setUri(url)
            .setMediaId(videoId)
            .apply { if (mime.isNotBlank()) setMimeType(mime) }
            .setMediaMetadata(metadata)
            .build()

    private data class VideoPick(val format: StreamFormat, val muxed: Boolean)

    /**
     * Chooses one video track.
     *
     * Auto (`0`) prefers H.264 — hardware decoders are near-universal — and caps
     * at 1080p, because above that phones rarely have a hardware decoder and
     * "higher quality" would mean dropped frames and heat. 1440p/2160p are still
     * reachable when nothing smaller exists.
     *
     * An explicit height returns the closest match: the exact height when the
     * video has it, otherwise the largest one below it, otherwise the smallest
     * above it. When two tracks tie (same resolution, or a muxed/adaptive pair)
     * the single-file muxed one wins, because its audio cannot expire out from
     * under the video.
     */
    private fun pickVideo(p: Playback, preferredHeight: Int): VideoPick? {
        val all = p.muxed.filter { it.url.isNotBlank() }.map { VideoPick(it, true) } +
            p.videoOnly.filter { it.url.isNotBlank() }.map { VideoPick(it, false) }
        if (all.isEmpty()) return null

        // Prefer H.264/AAC: VP9 and AV1 may have to be decoded in software.
        val mp4 = all.filter { it.format.mime == MimeTypes.VIDEO_MP4 }
        val pool = mp4.ifEmpty { all }

        if (preferredHeight > 0) {
            val exact = pool.filter { it.format.height == preferredHeight }
            if (exact.isNotEmpty()) return exact.sortedByDescending { it.muxed }.first()

            val below = pool.filter { it.format.height in 1 until preferredHeight }
            if (below.isNotEmpty()) {
                return below.sortedWith(
                    compareByDescending<VideoPick> { it.format.height }.thenByDescending { it.muxed },
                ).first()
            }

            val above = pool.filter { it.format.height > preferredHeight }
            if (above.isNotEmpty()) {
                return above.sortedWith(
                    compareBy<VideoPick> { it.format.height }.thenByDescending { it.muxed },
                ).first()
            }

            // Heights were missing or zero: nothing to compare, take anything.
            return pool.first()
        }

        val capped = pool.filter { it.format.height in 1..1080 }
            .ifEmpty { pool.filter { it.format.height in 1..2160 } }
            .ifEmpty { pool }
        return capped.sortedWith(
            compareByDescending<VideoPick> { it.format.height }.thenByDescending { it.muxed },
        ).first()
    }

    private fun pickAudio(tracks: List<StreamFormat>): StreamFormat? {
        val usable = tracks.filter { it.url.isNotBlank() }
        if (usable.isEmpty()) return null
        val mp4 = usable.filter { it.mime == MimeTypes.AUDIO_MP4 }
        return (mp4.ifEmpty { usable }).maxByOrNull { it.bitrate }
    }
}
