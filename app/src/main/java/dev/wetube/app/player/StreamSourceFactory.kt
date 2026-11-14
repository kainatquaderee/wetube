package dev.wetube.app.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
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
class StreamSourceFactory(
    context: Context,
    httpClient: okhttp3.OkHttpClient,
) : MediaSource.Factory {

    // Media3 would otherwise use HttpURLConnection, which resolves CDN hosts
    // the same IPv6-first way the platform does. Going through OkHttp keeps
    // stream downloads on the app's IPv4-first resolver and connection pool.
    private val dataSourceFactory = OkHttpDataSource.Factory(httpClient)
    private val progressive = ProgressiveMediaSource.Factory(dataSourceFactory)
    private val hls = HlsMediaSource.Factory(dataSourceFactory)
    // A downloaded file is read straight off the disk: `DefaultDataSource`
    // understands `file:` and `content:` URIs where `OkHttpDataSource` does not.
    private val files = ProgressiveMediaSource.Factory(DefaultDataSource.Factory(context))
    private val delegate = DefaultMediaSourceFactory(context)

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val uri = mediaItem.localConfiguration?.uri

        // A local path never touches OkHttp; it is the whole point of having
        // saved the file, and OkHttp cannot open a `file:` URI at all.
        if (uri != null && (uri.scheme == "file" || uri.scheme == "content")) {
            return files.createMediaSource(mediaItem)
        }

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

        // Everything else is a plain progressive URL: keep it on OkHttp too, so
        // a single-file stream cannot fall back to the platform's IPv6-first
        // resolver and stall.
        return progressive.createMediaSource(mediaItem)
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
