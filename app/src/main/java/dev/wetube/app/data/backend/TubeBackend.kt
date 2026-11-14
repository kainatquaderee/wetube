package dev.wetube.app.data.backend

import dev.wetube.app.data.model.ChannelInfo
import dev.wetube.app.data.model.Comment
import dev.wetube.app.data.model.CommentPage
import dev.wetube.app.data.model.SearchResult
import dev.wetube.app.data.model.StreamFormat
import dev.wetube.app.data.model.VideoDetail
import dev.wetube.app.data.model.VideoItem

/**
 * The one contract the UI knows about.
 *
 * YouTube's own client is a protobuf/gRPC client against youtubei.googleapis.com
 * (see docs/ARCHITECTURE.md). We deliberately do not hard-code that inside the
 * UI: any number of transports can implement this interface, which is what makes
 * the app testable and keeps it alive when a transport breaks.
 */
interface TubeBackend {

    /** Human readable name, shown in Settings. */
    val displayName: String

    suspend fun trending(region: String): List<VideoItem>

    suspend fun search(query: String, nextPage: String? = null): SearchResult

    suspend fun video(id: String): VideoDetail

    suspend fun channel(id: String): ChannelInfo

    suspend fun comments(id: String): List<Comment>

    /**
     * One page of comments, starting from a token handed out by the watch
     * response (or by a previous page). Backends without pagination keep the
     * default: one call to [comments] and no next page.
     */
    suspend fun commentsPage(token: String): CommentPage = CommentPage()

    suspend fun related(id: String): List<VideoItem> = emptyList()

    /** A Shorts feed for the dedicated Shorts section. */
    suspend fun shorts(): List<VideoItem> = emptyList()

    /** The signed-in account's subscriptions. Empty when not signed in. */
    suspend fun subscriptions(): List<VideoItem> = emptyList()

    /** Query autocomplete. Backends that cannot answer return an empty list. */
    suspend fun suggestions(query: String): List<String> = emptyList()

    /**
     * One muxed progressive track for [id], or `null` when the backend cannot
     * offer one.
     *
     * Casting needs a single URL a television can open on its own, and this is
     * the only place that knows which transports can produce one. It is
     * deliberately separate from [video]: a cast happens while the local player
     * is paused or playing something else, and the caller only needs one track,
     * not a whole [VideoDetail]. Backends that cannot answer return `null`, and
     * the caller reports "not castable" instead of guessing at an adaptive URL.
     */
    suspend fun progressive(id: String): StreamFormat? = null
}
