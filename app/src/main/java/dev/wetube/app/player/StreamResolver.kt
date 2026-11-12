package dev.wetube.app.player

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
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

    fun mediaItem(detail: VideoDetail, audioOnly: Boolean): MediaItem? {
        val p = detail.playback
        if (p.isEmpty) return null

        val metadata = MediaMetadata.Builder()
            .setTitle(detail.item.title)
            .setArtist(detail.item.channelName)
            .apply {
                if (detail.item.thumbnail.isNotBlank()) setArtworkUri(Uri.parse(detail.item.thumbnail))
            }
            .build()

        val bestAudio = pickAudio(p.audioOnly)
        val bestVideo = pickVideo(p.videoOnly)
        val bestMuxed = p.muxed.maxByOrNull { it.height }

        if (audioOnly) {
            val track = bestAudio ?: bestMuxed
            return track?.let { build(it.url, it.mime, metadata) }
        }

        if (bestVideo != null && bestAudio != null && bestVideo.url.isNotBlank() && bestAudio.url.isNotBlank()) {
            val uri = Uri.Builder()
                .scheme(MERGE_SCHEME)
                .appendQueryParameter("video", bestVideo.url)
                .appendQueryParameter("audio", bestAudio.url)
                .appendQueryParameter("vmime", bestVideo.mime)
                .appendQueryParameter("amime", bestAudio.mime)
                .build()
            return MediaItem.Builder().setUri(uri).setMediaMetadata(metadata).build()
        }

        val fallback = bestMuxed ?: bestVideo ?: bestAudio
        return fallback?.let { build(it.url, it.mime, metadata) }
    }

    private fun build(url: String, mime: String, metadata: MediaMetadata): MediaItem =
        MediaItem.Builder()
            .setUri(url)
            .apply { if (mime.isNotBlank()) setMimeType(mime) }
            .setMediaMetadata(metadata)
            .build()

    /**
     * Prefer H.264/AAC: hardware decoding is near-universal, and 4K VP9/AV1
     * would often fall back to software decode on phones.
     *
     * We also cap H.264 at 1080p. Above that, phones rarely have a hardware
     * H.264 decoder, so "higher quality" would actually mean dropped frames and
     * heat. A manual quality picker is on the roadmap.
     */
    private fun pickVideo(tracks: List<StreamFormat>): StreamFormat? {
        val usable = tracks.filter { it.url.isNotBlank() }
        if (usable.isEmpty()) return null
        val mp4 = usable.filter { it.mime == MimeTypes.VIDEO_MP4 }
        val pool = mp4.ifEmpty { usable }
        val hd = pool.filter { it.height in 1..1080 }
        return (hd.ifEmpty { pool.filter { it.height in 1..2160 } }.ifEmpty { pool })
            .maxByOrNull { it.height }
    }

    private fun pickAudio(tracks: List<StreamFormat>): StreamFormat? {
        val usable = tracks.filter { it.url.isNotBlank() }
        if (usable.isEmpty()) return null
        val mp4 = usable.filter { it.mime == MimeTypes.AUDIO_MP4 }
        return (mp4.ifEmpty { usable }).maxByOrNull { it.bitrate }
    }
}
