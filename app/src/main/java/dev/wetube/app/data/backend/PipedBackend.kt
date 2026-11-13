package dev.wetube.app.data.backend

import dev.wetube.app.core.DEFAULT_PIPED_HOST
import dev.wetube.app.core.decodeEntities
import dev.wetube.app.core.encodeUrlValue
import dev.wetube.app.core.isValidVideoId
import dev.wetube.app.core.sanitizeBackendHost
import dev.wetube.app.core.subscriberLabel
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

    /**
     * The instance origin, validated here as a last line of defence: the value
     * can come from a restored backup or a preference written by an older
     * build, so it is never trusted just because it reached the constructor.
     */
    private val base = "${sanitizeBackendHost(baseUrl) ?: DEFAULT_PIPED_HOST}/api"

    override suspend fun trending(region: String): List<VideoItem> =
        http.get(
            "$base/trending?region=${encodeUrlValue(region)}",
            kotlinx.serialization.builtins.ListSerializer(PipedVideo.serializer()),
        )
            .map { it.toItem() }

    override suspend fun search(query: String, nextPage: String?): SearchResult {
        val encoded = encodeUrlValue(query)
        val url = if (nextPage.isNullOrBlank()) {
            "$base/search?q=$encoded&filter=videos"
        } else {
            "$base/nextpage/search?nextpage=${encodeUrlValue(nextPage)}&q=$encoded&filter=videos"
        }
        val page = http.get(url, PipedSearch.serializer())
        return SearchResult(
            videos = page.items.map { it.toItem() },
            nextPage = page.nextpage,
        )
    }

    override suspend fun video(id: String): VideoDetail {
        val s = http.get("$base/streams/${encodeVideoId(id)}", PipedStreams.serializer())

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
            title = decodeEntities(s.title),
            channelName = decodeEntities(s.uploader),
            channelId = s.uploaderUrl.substringAfterLast('/'),
            thumbnail = "https://i.ytimg.com/vi/${encodeUrlValue(id)}/hqdefault.jpg",
            durationSeconds = s.duration,
            views = s.views,
            publishedText = decodeEntities(s.uploadDate),
            live = s.live,
        )

        return VideoDetail(
            item = item,
            description = decodeEntities(s.description),
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
        val c = http.get("$base/channel/${encodeUrlValue(id)}", PipedChannel.serializer())
        return ChannelInfo(
            id = id,
            name = decodeEntities(c.name),
            avatar = c.avatarUrl,
            banner = c.bannerUrl,
            description = decodeEntities(c.description),
            subscribersText = subscriberLabel(c.subscriberCount),
            videos = c.relatedStreams.map { it.toItem() },
        )
    }

    override suspend fun comments(id: String): List<Comment> {
        val c = http.get("$base/comments/${encodeVideoId(id)}", PipedComments.serializer())
        return c.comments.map {
            Comment(
                author = decodeEntities(it.author),
                avatar = it.thumbnail,
                text = decodeEntities(it.commentText),
                likes = it.likeCount,
                publishedText = decodeEntities(it.commentedTime),
                replyCount = it.replyCount,
            )
        }
    }

    override suspend fun related(id: String): List<VideoItem> = video(id).related

    override suspend fun suggestions(query: String): List<String> = try {
        val q = encodeUrlValue(query)
        http.get("$base/suggestions?query=$q", kotlinx.serialization.builtins.ListSerializer(String.serializer()))
            .map { decodeEntities(it) }
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * Validates and encodes a video id before it is placed in a path. The id is
     * always the 11-character YouTube form; anything else is rejected rather
     * than silently smuggled into the request line.
     */
    private fun encodeVideoId(id: String): String {
        require(isValidVideoId(id)) { "Not a valid video id." }
        return encodeUrlValue(id)
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
                title = decodeEntities(title),
                channelName = decodeEntities(uploaderName),
                channelId = uploaderUrl.substringAfterLast('/'),
                thumbnail = thumbnail.ifBlank { "https://i.ytimg.com/vi/${encodeUrlValue(vid)}/hqdefault.jpg" },
                durationSeconds = duration,
                views = views,
                publishedText = decodeEntities(uploadedDate),
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
}
