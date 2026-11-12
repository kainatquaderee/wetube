package dev.wetube.app.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/**
 * Builds real MediaSources from the lightweight URIs the UI produces.
 *
 * Everything else falls through to the default factory, so HLS/DASH keep working
 * if a backend starts offering manifests.
 */
@UnstableApi
class StreamSourceFactory(context: Context) : MediaSource.Factory {

    private val dataSourceFactory = DefaultDataSource.Factory(context)
    private val progressive = ProgressiveMediaSource.Factory(dataSourceFactory)
    private val hls = HlsMediaSource.Factory(dataSourceFactory)
    private val delegate = DefaultMediaSourceFactory(context)

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val uri = mediaItem.localConfiguration?.uri

        if (uri != null && uri.scheme == StreamResolver.MERGE_SCHEME) {
            val videoUrl = uri.getQueryParameter("video").orEmpty()
            val audioUrl = uri.getQueryParameter("audio").orEmpty()
            if (videoUrl.isNotBlank() && audioUrl.isNotBlank()) {
                val videoMime = uri.getQueryParameter("vmime")?.takeIf { it.isNotBlank() }
                    ?: MimeTypes.VIDEO_MP4
                val audioMime = uri.getQueryParameter("amime")?.takeIf { it.isNotBlank() }
                    ?: MimeTypes.AUDIO_MP4

                val videoSource = progressive.createMediaSource(
                    MediaItem.Builder().setUri(videoUrl).setMimeType(videoMime).build()
                )
                val audioSource = progressive.createMediaSource(
                    MediaItem.Builder().setUri(audioUrl).setMimeType(audioMime).build()
                )
                return MergingMediaSource(videoSource, audioSource)
            }
        }

        if (uri != null && (uri.path?.endsWith(".m3u8") == true ||
                mediaItem.localConfiguration?.mimeType == MimeTypes.APPLICATION_M3U8)
        ) {
            return hls.createMediaSource(mediaItem)
        }

        return delegate.createMediaSource(mediaItem)
    }

    override fun getSupportedTypes(): IntArray = delegate.supportedTypes

    override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory {
        delegate.setDrmSessionManagerProvider(provider)
        return this
    }

    override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): MediaSource.Factory {
        delegate.setLoadErrorHandlingPolicy(policy)
        return this
    }
}
