package dev.wetube.app.data.backend

import dev.wetube.app.data.content.AgeGateStore
import dev.wetube.app.data.model.ChannelInfo
import dev.wetube.app.data.model.Comment
import dev.wetube.app.data.model.SearchResult
import dev.wetube.app.data.model.VideoDetail
import dev.wetube.app.data.model.VideoItem
import dev.wetube.app.data.prefs.SettingsStore
import kotlinx.coroutines.flow.first
import java.io.IOException

/** Thrown when a known age-gated video is opened with mature content switched off. */
class MatureHiddenException :
    IOException("This video is age-restricted. Enable \"Show mature content\" in Settings to open it.")

/**
 * Applies the mature-content preference in one place, so screens stay unaware
 * of it.
 *
 * Listings cannot tell us which videos are age-gated, but the player response
 * can. We remember those ids (see [AgeGateStore]); while the switch is off they
 * are filtered out of every feed and cannot be opened. With the switch on they
 * are shown and playback is attempted normally — if YouTube still insists on a
 * signed-in account, the error says so instead of pretending to bypass it.
 */
class ContentFilterBackend(
    private val delegate: TubeBackend,
    private val settings: SettingsStore,
    private val ageGate: AgeGateStore,
) : TubeBackend {

    override val displayName: String get() = delegate.displayName

    private suspend fun showMature(): Boolean = settings.settings.first().showMature

    private suspend fun visible(items: List<VideoItem>): List<VideoItem> {
        val mature = showMature()
        val blocked = runCatching { ageGate.ids() }.getOrDefault(emptySet())
        val kept = if (mature) items else items.filterNot { it.id in blocked }
        if (dev.wetube.app.BuildConfig.DEBUG) {
            android.util.Log.d(
                "WeTube",
                "filter: mature=$mature in=${items.size} out=${kept.size} blocked=${blocked.size}",
            )
        }
        return kept
    }

    override suspend fun trending(region: String): List<VideoItem> = visible(delegate.trending(region))

    override suspend fun search(query: String, nextPage: String?): SearchResult {
        val result = delegate.search(query, nextPage)
        return result.copy(videos = visible(result.videos))
    }

    override suspend fun video(id: String): VideoDetail {
        if (!showMature() && runCatching { ageGate.isRestricted(id) }.getOrDefault(false)) {
            throw MatureHiddenException()
        }
        return try {
            delegate.video(id)
        } catch (e: IOException) {
            if (isAgeWall(e.message)) runCatching { ageGate.mark(id) }
            throw e
        }
    }

    override suspend fun channel(id: String): ChannelInfo {
        val channel = delegate.channel(id)
        return channel.copy(videos = visible(channel.videos))
    }

    override suspend fun comments(id: String): List<Comment> = delegate.comments(id)

    override suspend fun related(id: String): List<VideoItem> = visible(delegate.related(id))

    override suspend fun shorts(): List<VideoItem> = visible(delegate.shorts())

    override suspend fun suggestions(query: String): List<String> = delegate.suggestions(query)

    override suspend fun subscriptions(): List<VideoItem> = visible(delegate.subscriptions())

    private fun isAgeWall(message: String?): Boolean {
        val text = message.orEmpty()
        return text.contains("confirm your age", ignoreCase = true) ||
            text.contains("age-restricted", ignoreCase = true) ||
            text.contains("inappropriate for some users", ignoreCase = true)
    }
}
