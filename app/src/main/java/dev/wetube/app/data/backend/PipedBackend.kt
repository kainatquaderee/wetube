package dev.wetube.app.data.backend

import dev.wetube.app.data.model.ChannelInfo
import dev.wetube.app.data.model.Comment
import dev.wetube.app.data.model.Playback
import dev.wetube.app.data.model.SearchResult
import dev.wetube.app.data.model.StreamFormat
import dev.wetube.app.data.model.VideoDetail
import dev.wetube.app.data.model.VideoItem
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer

/** Piped backend: a second independent, documented API with a different shape. */
class PipedBackend(
    private val http: JsonHttp,
    baseUrl: String,
) : TubeBackend {

    override val displayName: String = "Piped"

    private val base = baseUrl.trimEnd('/').let { if (it.endsWith("/api")) it else "$it/api" }

    override suspend fun trending(region: String): List<VideoItem> =
        http.get("$base/trending?region=$region", kotlinx.serialization.builtins.ListSerializer(PipedVideo.serializer()))
            .map { it.toItem() }

    override suspend fun search(query: String, nextPage: String?): SearchResult {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val url = if (nextPage.isNullOrBlank()) {
            "$base/search?q=$encoded&filter=videos"
        } else {
            "$base/nextpage/search?nextpage=${java.net.URLEncoder.encode(nextPage, "UTF-8")}&q=$encoded&filter=videos"
        }
        val page = http.get(url, PipedSearch.serializer())
        return SearchResult(
            videos = page.items.map { it.toItem() },
            nextPage = page.nextpage,
        )
    }

    override suspend fun video(id: String): VideoDetail {
        val s = http.get("$base/streams/$id", PipedStreams.serializer())

        val muxed = s.videoStreams
            .filter { !it.videoOnly }
            .map {
                StreamFormat(
                    url = it.url,
                    label = it.quality,
                    mime = it.mimeType,
                    bitrate = it.bitrate.toLong(),
                    height = it.height,
                )
            }
        val videoOnly = s.videoStreams
            .filter { it.videoOnly }
            .map {
                StreamFormat(
                    url = it.url,
                    label = it.quality,
                    mime = it.mimeType,
                    bitrate = it.bitrate.toLong(),
                    height = it.height,
                )
            }
        val audioOnly = s.audioStreams.map {
            StreamFormat(
                url = it.url,
                label = it.quality,
                mime = it.mimeType,
                bitrate = it.bitrate.toLong(),
                audio = true,
            )
        }

        val item = VideoItem(
            id = id,
            title = s.title,
            channelName = s.uploader,
            channelId = s.uploaderUrl.substringAfterLast('/'),
            thumbnail = "https://i.ytimg.com/vi/$id/hqdefault.jpg",
            durationSeconds = s.duration,
            views = s.views,
            publishedText = s.uploadDate,
            live = s.live,
        )

        return VideoDetail(
            item = item,
            description = s.description,
            likes = s.likes,
            subscribersText = "",
            playback = Playback(
                muxed = muxed,
                videoOnly = videoOnly,
                audioOnly = audioOnly,
                hls = s.hls.takeIf { it.isNotBlank() },
            ),
            related = s.relatedStreams.map { it.toItem() },
            isLive = s.live,
        )
    }

    override suspend fun channel(id: String): ChannelInfo {
        val c = http.get("$base/channel/$id", PipedChannel.serializer())
        return ChannelInfo(
            id = id,
            name = c.name,
            avatar = c.avatarUrl,
            banner = c.bannerUrl,
            description = c.description,
            subscribersText = fmt(c.subscriberCount),
            videos = c.relatedStreams.map { it.toItem() },
        )
    }

    override suspend fun comments(id: String): List<Comment> {
        val c = http.get("$base/comments/$id", PipedComments.serializer())
        return c.comments.map {
            Comment(
                author = it.author,
                avatar = it.thumbnail,
                text = it.commentText,
                likes = it.likeCount,
                publishedText = it.commentedTime,
                replyCount = it.replyCount,
            )
        }
    }

    override suspend fun related(id: String): List<VideoItem> = video(id).related

    override suspend fun suggestions(query: String): List<String> = try {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        http.get("$base/suggestions?query=$q", kotlinx.serialization.builtins.ListSerializer(String.serializer()))
    } catch (_: Exception) {
        emptyList()
    }

    // ---- DTOs -----------------------------------------------------------

    @Serializable
    private data class PipedVideo(
        val url: String = "",
        val title: String = "",
        val uploaderName: String = "",
        val uploaderUrl: String = "",
        val thumbnail: String = "",
        val duration: Int = 0,
        val views: Long = 0,
        val uploadedDate: String = "",
        val type: String = "",
    ) {
        fun toItem(): VideoItem {
            val vid = url.substringAfter("v=", "").substringBefore('&').ifBlank { url.substringAfterLast('/') }
            return VideoItem(
                id = vid,
                title = title,
                channelName = uploaderName,
                channelId = uploaderUrl.substringAfterLast('/'),
                thumbnail = thumbnail.ifBlank { "https://i.ytimg.com/vi/$vid/hqdefault.jpg" },
                durationSeconds = duration,
                views = views,
                publishedText = uploadedDate,
            )
        }
    }

    @Serializable
    private data class PipedSearch(
        val items: List<PipedVideo> = emptyList(),
        val nextpage: String? = null,
    )

    @Serializable
    private data class PipedVideoStream(
        val url: String = "",
        val quality: String = "",
        val itag: Int = 0,
        val mimeType: String = "",
        val codec: String = "",
        val bitrate: Int = 0,
        val width: Int = 0,
        val height: Int = 0,
        val videoOnly: Boolean = false,
    )

    @Serializable
    private data class PipedAudioStream(
        val url: String = "",
        val quality: String = "",
        val itag: Int = 0,
        val mimeType: String = "",
        val codec: String = "",
        val bitrate: Int = 0,
        val audioTrackId: String? = null,
    )

    @Serializable
    private data class PipedStreams(
        val title: String = "",
        val description: String = "",
        val uploader: String = "",
        val uploaderUrl: String = "",
        val videoStreams: List<PipedVideoStream> = emptyList(),
        val audioStreams: List<PipedAudioStream> = emptyList(),
        val relatedStreams: List<PipedVideo> = emptyList(),
        val uploadDate: String = "",
        val views: Long = 0,
        val likes: Long = 0,
        val duration: Int = 0,
        val live: Boolean = false,
        val hls: String = "",
    )

    @Serializable
    private data class PipedChannel(
        val name: String = "",
        val avatarUrl: String = "",
        val bannerUrl: String = "",
        val description: String = "",
        val subscriberCount: Long = 0,
        val relatedStreams: List<PipedVideo> = emptyList(),
    )

    @Serializable
    private data class PipedComment(
        val author: String = "",
        val thumbnail: String = "",
        val commentText: String = "",
        val likeCount: Long = 0,
        val commentedTime: String = "",
        val replyCount: Int = 0,
    )

    @Serializable
    private data class PipedComments(
        val comments: List<PipedComment> = emptyList(),
    )

    private fun fmt(n: Long) = InvidiousBackend.formatCount(n)
}
