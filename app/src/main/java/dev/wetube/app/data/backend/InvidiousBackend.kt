package dev.wetube.app.data.backend

import dev.wetube.app.data.model.ChannelInfo
import dev.wetube.app.data.model.Comment
import dev.wetube.app.data.model.Playback
import dev.wetube.app.data.model.SearchResult
import dev.wetube.app.data.model.StreamFormat
import dev.wetube.app.data.model.VideoDetail
import dev.wetube.app.data.model.VideoItem
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * Invidious backend.
 *
 * Invidious is an independent, documented, read-only HTTP API. Nothing here is
 * derived from YouTube's shipped client; the endpoint shapes come from the
 * Invidious API documentation.
 */
class InvidiousBackend(
    private val http: JsonHttp,
    baseUrl: String,
    private val proxyStreams: Boolean,
) : TubeBackend {

    override val displayName: String = "Invidious"

    private val base = baseUrl.trimEnd('/').let { if (it.endsWith("/api/v1")) it else "$it/api/v1" }

    override suspend fun trending(region: String): List<VideoItem> =
        http.get("$base/trending?region=$region&type=Default", ListSerializer(IvVideo.serializer()))
            .filter { it.videoId.isNotBlank() }
            .map { it.toItem() }

    override suspend fun search(query: String, nextPage: String?): SearchResult {
        val page = nextPage?.toIntOrNull() ?: 1
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val items = http.get("$base/search?q=$encoded&page=$page&type=video", ListSerializer(IvVideo.serializer()))
        return SearchResult(
            videos = items.filter { it.videoId.isNotBlank() }.map { it.toItem() },
            nextPage = if (items.isEmpty()) null else (page + 1).toString(),
        )
    }

    override suspend fun video(id: String): VideoDetail {
        val local = if (proxyStreams) "?local=true" else ""
        val v = http.get("$base/videos/$id$local", IvVideo.serializer())

        val videoTracks = v.adaptiveFormats
            .filter { it.type.startsWith("video/") }
            .map { it.toFormat(audio = false) }
        val audioTracks = v.adaptiveFormats
            .filter { it.type.startsWith("audio/") }
            .map { it.toFormat(audio = true) }

        return VideoDetail(
            item = v.toItem(),
            description = v.description,
            likes = v.likeCount,
            subscribersText = v.subCountText,
            playback = Playback(
                muxed = v.formatStreams.map { it.toFormat(audio = false) },
                videoOnly = videoTracks,
                audioOnly = audioTracks,
                hls = v.hlsUrl.takeIf { it.isNotBlank() },
            ),
            related = v.recommendedVideos.filter { it.videoId.isNotBlank() }.map { it.toItem() },
            isLive = v.liveNow,
        )
    }

    override suspend fun channel(id: String): ChannelInfo {
        val c = http.get("$base/channels/$id", IvChannel.serializer())
        return ChannelInfo(
            id = c.authorId.ifBlank { id },
            name = c.author,
            avatar = c.authorThumbnails.maxByOrNull { it.width }?.url.orEmpty(),
            banner = c.authorBanners.maxByOrNull { it.width }?.url.orEmpty(),
            description = c.description,
            subscribersText = formatCount(c.subCount),
            videos = c.latestVideos.filter { it.videoId.isNotBlank() }.map { it.toItem() },
        )
    }

    override suspend fun comments(id: String): List<Comment> {
        val page = http.get("$base/comments/$id", IvComments.serializer())
        return page.comments.map {
            Comment(
                author = it.author,
                avatar = it.authorThumbnails.firstOrNull()?.url.orEmpty(),
                text = it.content,
                likes = it.likeCount,
                publishedText = it.publishedText,
                replyCount = it.replies?.replyCount ?: 0,
            )
        }
    }

    override suspend fun related(id: String): List<VideoItem> = video(id).related

    override suspend fun suggestions(query: String): List<String> = try {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        http.get("$base/search/suggestions?q=$q", IvSuggestions.serializer()).suggestions
    } catch (_: Exception) {
        emptyList()
    }

    // ---- DTOs / mapping -------------------------------------------------

    @Serializable
    private data class IvSuggestions(val suggestions: List<String> = emptyList())

    @Serializable
    private data class IvThumb(val url: String = "", val width: Int = 0, val height: Int = 0)

    @Serializable
    private data class IvFormat(
        val url: String = "",
        val itag: String = "",
        val type: String = "",
        val qualityLabel: String? = null,
        val bitrate: String = "",
        val audioQuality: String? = null,
        val container: String = "",
        val encoding: String = "",
        val height: Int = 0,
        val width: Int = 0,
    ) {
        fun toFormat(audio: Boolean) = StreamFormat(
            url = url,
            label = if (audio) (audioQuality ?: "audio") else (qualityLabel ?: "video"),
            mime = type.substringBefore(';').trim(),
            bitrate = bitrate.toLongOrNull() ?: 0L,
            height = height,
            audio = audio,
        )
    }

    @Serializable
    private data class IvReplies(val replyCount: Int = 0)

    @Serializable
    private data class IvComment(
        val author: String = "",
        val authorThumbnails: List<IvThumb> = emptyList(),
        val content: String = "",
        val likeCount: Long = 0,
        val publishedText: String = "",
        val replies: IvReplies? = null,
    )

    @Serializable
    private data class IvComments(val comments: List<IvComment> = emptyList())

    @Serializable
    private data class IvChannel(
        val author: String = "",
        val authorId: String = "",
        val authorThumbnails: List<IvThumb> = emptyList(),
        val authorBanners: List<IvThumb> = emptyList(),
        val subCount: Long = 0,
        val description: String = "",
        val latestVideos: List<IvVideo> = emptyList(),
    )

    @Serializable
    private data class IvVideo(
        val title: String = "",
        val videoId: String = "",
        val author: String = "",
        val authorId: String = "",
        val videoThumbnails: List<IvThumb> = emptyList(),
        val viewCount: Long = 0,
        val publishedText: String = "",
        val lengthSeconds: Int = 0,
        val liveNow: Boolean = false,
        val description: String = "",
        val subCountText: String = "",
        val likeCount: Long = 0,
        @SerialName("hlsUrl") val hlsUrl: String = "",
        val adaptiveFormats: List<IvFormat> = emptyList(),
        val formatStreams: List<IvFormat> = emptyList(),
        val recommendedVideos: List<IvVideo> = emptyList(),
    ) {
        fun toItem() = VideoItem(
            id = videoId,
            title = title,
            channelName = author,
            channelId = authorId,
            thumbnail = videoThumbnails.maxByOrNull { it.width }?.url
                ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
            durationSeconds = lengthSeconds,
            views = viewCount,
            publishedText = publishedText,
            live = liveNow,
        )
    }

    companion object {
        /** 1.2M / 34K style formatting, matching what feeds usually show. */
        fun formatCount(n: Long): String = when {
            n >= 1_000_000_000 -> "%.1fB".format(n / 1_000_000_000.0)
            n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
            n >= 1_000 -> "%.1fK".format(n / 1_000.0)
            else -> n.toString()
        }
    }
}
