package dev.wetube.app.data.model

import kotlinx.serialization.Serializable

/** A video as it appears in a feed, search result or related list. */
@Serializable
data class VideoItem(
    val id: String,
    val title: String,
    val channelName: String,
    val channelId: String = "",
    val thumbnail: String = "",
    val durationSeconds: Int = 0,
    val views: Long = 0,
    val publishedText: String = "",
    val live: Boolean = false,
)

/** One concrete media track offered by a backend. */
data class StreamFormat(
    val url: String,
    val label: String,
    val mime: String,
    val bitrate: Long = 0,
    val height: Int = 0,
    val audio: Boolean = false,
)

/**
 * Everything the player needs. Backends differ wildly in what they expose, so
 * the player negotiates instead of assuming one container.
 */
data class Playback(
    val muxed: List<StreamFormat> = emptyList(),
    val videoOnly: List<StreamFormat> = emptyList(),
    val audioOnly: List<StreamFormat> = emptyList(),
    val hls: String? = null,
) {
    val isEmpty: Boolean get() = muxed.isEmpty() && videoOnly.isEmpty() && audioOnly.isEmpty() && hls == null
}

data class VideoDetail(
    val item: VideoItem,
    val description: String,
    val likes: Long,
    val subscribersText: String,
    val playback: Playback,
    val related: List<VideoItem>,
    val isLive: Boolean = false,
)

data class ChannelInfo(
    val id: String,
    val name: String,
    val avatar: String = "",
    val banner: String = "",
    val description: String = "",
    val subscribersText: String = "",
    val videos: List<VideoItem> = emptyList(),
)

data class Comment(
    val author: String,
    val avatar: String = "",
    val text: String,
    val likes: Long = 0,
    val publishedText: String = "",
    val replyCount: Int = 0,
)

data class SearchResult(
    val videos: List<VideoItem>,
    val channels: List<ChannelInfo> = emptyList(),
    val nextPage: String? = null,
)
