package dev.wetube.app.data.backend

import dev.wetube.app.data.model.ChannelInfo
import dev.wetube.app.data.model.Comment
import dev.wetube.app.data.model.CommentPage
import dev.wetube.app.data.model.FeedPage
import dev.wetube.app.data.model.HomeBlock
import dev.wetube.app.data.model.HomePage
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

    /**
     * One page of the front-page feed. [nextPage] is a token from a previous
     * page, or `null` for the first page.
     *
     * Backends whose feed cannot be paged keep this default: one call to
     * [trending] and no next page, so the feed simply ends at the first page.
     */
    suspend fun trendingPage(region: String, nextPage: String? = null): FeedPage =
        FeedPage(trending(region))

    /**
     * One page of Home, in the shape the page is actually laid out in: a chip
     * bar, an ordered run of videos and shelves, and the token for the next
     * page.
     *
     * [chip] selects one entry of the bar — the token a previous [HomePage]
     * handed out — and `null` means the feed's own first page. A transport
     * without a chip bar ignores [chip] and answers with no chips, which is
     * exactly how Home then renders: no bar at all.
     *
     * The default follows [trendingPage], so a backend that only knows how to
     * produce a flat list still fills Home; it just shows no chips or shelves.
     */
    suspend fun homeFeed(
        region: String,
        nextPage: String? = null,
        chip: String? = null,
    ): HomePage = trendingPage(region, nextPage).let { page ->
        HomePage(
            blocks = page.videos.map { HomeBlock.Video(it) },
            nextPage = page.nextPage,
        )
    }

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

    /**
     * Ask the next playback resolve to lead with a different client profile.
     *
     * Recovery calls this when a stream URL was rejected at playback: asking
     * the same transport for the same URL again usually earns the same
     * rejection, so the next [video] starts from the next profile instead. It
     * is non-suspending and parameterless on purpose — a backend with a single
     * transport keeps the no-op, and the UI never needs to know profile names.
     */
    fun forcePlaybackFallback() {}

    /**
     * The account-write transport, when this backend has one.
     *
     * Sync is a capability, not a requirement: a public-feed transport such as
     * Invidious or Piped has no way to write to an account, and the default
     * `null` lets the engine report itself disabled rather than fail at runtime.
     * Only the InnerTube transport can grow a real implementation (a later
     * batch), and [ContentFilterBackend] forwards whatever the delegate offers
     * so the filter layer never hides the capability.
     */
    val sync: SyncBackend? get() = null
}
