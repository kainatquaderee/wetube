package dev.wetube.app.data.backend

import dev.wetube.app.data.model.ChannelInfo
import dev.wetube.app.data.model.Comment
import dev.wetube.app.data.model.SearchResult
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

    suspend fun related(id: String): List<VideoItem> = emptyList()

    /** Query autocomplete. Backends that cannot answer return an empty list. */
    suspend fun suggestions(query: String): List<String> = emptyList()
}
