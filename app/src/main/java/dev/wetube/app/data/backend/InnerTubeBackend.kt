package dev.wetube.app.data.backend

import dev.wetube.app.BuildConfig
import dev.wetube.app.data.model.ChannelInfo
import dev.wetube.app.data.model.Comment
import dev.wetube.app.data.model.Playback
import dev.wetube.app.data.model.SearchResult
import dev.wetube.app.data.model.StreamFormat
import dev.wetube.app.data.model.VideoDetail
import dev.wetube.app.data.model.VideoItem
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.net.URLEncoder

/**
 * Direct InnerTube backend — the transport the official client uses.
 *
 * A JSON envelope is POSTed to `youtubei/v1/<endpoint>` with a client identity
 * in `context.client`; the response is a deeply nested renderer tree. We walk
 * that tree as generic JSON instead of binding DTOs, so new fields on
 * YouTube's side do not break deserialization.
 *
 * Two client profiles are used, because they are good at different things:
 *
 * | profile      | used for                  | why                                  |
 * |--------------|---------------------------|--------------------------------------|
 * | `ANDROID_VR` | player, home, channel     | returns plain (non-ciphered) URLs    |
 * | `WEB`        | search, suggestions       | richer cards: duration + upload date |
 *
 * Fragility is expected: profiles get rotated or gated by YouTube. That is why
 * [TubeBackend] exists and Invidious/Piped remain selectable in Settings.
 */
class InnerTubeBackend(
    private val http: JsonHttp,
    private val region: String,
) : TubeBackend {

    override val displayName: String = "Direct (InnerTube)"

    private data class Profile(
        val userAgent: String,
        val context: JsonObject,
        /** Numeric id YouTube uses for this client in `X-Youtube-Client-Name`. */
        val clientId: String,
        val version: String,
    )

    // 1.65.10 is deliberate. Older ANDROID_VR builds (1.60.x) now get
    // "Sign in to confirm you're not a bot" for most videos even when the
    // request is otherwise correct, and unversioned or made-up builds are
    // rejected. The inner `userAgent` field is part of the identity too.
    private val vrUa = "com.google.android.apps.youtube.vr.oculus/1.65.10 " +
        "(Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"

    private val vr = Profile(
        userAgent = vrUa,
        clientId = "28",
        version = "1.65.10",
        context = clientContext(
            name = "ANDROID_VR",
            version = "1.65.10",
            extra = mapOf(
                "androidSdkVersion" to 32,
                "deviceMake" to "Oculus",
                "deviceModel" to "Quest 3",
                "userAgent" to vrUa,
                "osName" to "Android",
                "osVersion" to "12L",
                "timeZone" to "UTC",
                "utcOffsetMinutes" to 0,
            ),
        ),
    )

    private val web = Profile(
        userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
        clientId = "1",
        version = "2.20240101.00.00",
        context = clientContext(
            name = "WEB",
            version = "2.20240101.00.00",
            extra = emptyMap(),
        ),
    )

    /**
     * YouTube binds freshness to a visitor id. Without one, most player calls
     * are answered with a bot challenge no matter how correct the rest of the
     * request is. We cache whatever the last response handed us and fall back
     * to the homepage when a deep link opens the player before any browse call.
     */
    @Volatile
    private var visitorCache: String = ""

    private val visitorLock = kotlinx.coroutines.sync.Mutex()

    private suspend fun visitor(): String {
        visitorCache.takeIf { it.isNotBlank() }?.let { return it }
        return visitorLock.withLock {
            visitorCache.takeIf { it.isNotBlank() } ?: run {
                runCatching {
                    val html = http.getText(
                        "https://www.youtube.com/",
                        mapOf("User-Agent" to web.userAgent, "Accept-Language" to "en-US,en;q=0.9"),
                    )
                    Regex("\"visitorData\":\"([^\"]+)\"")
                        .find(html)
                        ?.groupValues
                        ?.get(1)
                        ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                }.getOrNull().orEmpty().also { visitorCache = it }
            }
        }
    }

    private fun clientContext(
        name: String,
        version: String,
        extra: Map<String, Any>,
    ): JsonObject = buildJsonObject {
        put("client", buildJsonObject {
            put("clientName", name)
            put("clientVersion", version)
            put("hl", "en")
            put("gl", region)
            extra.forEach { (key, value) ->
                when (value) {
                    is Int -> put(key, value)
                    else -> put(key, value.toString())
                }
            }
        })
    }

    private suspend fun call(
        path: String,
        profile: Profile,
        extra: JsonObject,
    ): JsonObject {
        val visitor = visitor()
        val client = profile.context["client"]!!.jsonObject.toMutableMap().apply {
            if (visitor.isNotBlank()) put("visitorData", JsonPrimitive(visitor))
        }
        val payload = buildJsonObject {
            put("context", buildJsonObject { put("client", JsonObject(client)) })
            extra.forEach { (key, value) -> put(key, value) }
        }
        val response = http.postJson(
            "$endpoint/$path?prettyPrint=false",
            payload,
            buildMap {
                put("User-Agent", profile.userAgent)
                put("X-Goog-Api-Format-Version", "2")
                put("X-Youtube-Client-Name", profile.clientId)
                put("X-Youtube-Client-Version", profile.version)
                put("Origin", "https://www.youtube.com")
                if (visitor.isNotBlank()) put("X-Goog-Visitor-Id", visitor)
            },
        )
        response["responseContext"].obj()?.str("visitorData")
            ?.takeIf { it.isNotBlank() }
            ?.let { visitorCache = it }
        return response
    }

    // ---- TubeBackend ----------------------------------------------------

    override suspend fun trending(region: String): List<VideoItem> {
        val response = call("browse", vr, buildJsonObject {
            put("browseId", "FEwhat_to_watch")
        })
        return response.videos()
    }

    override suspend fun search(query: String, nextPage: String?): SearchResult {
        val extra = buildJsonObject {
            if (nextPage != null) put("continuation", nextPage) else put("query", query)
        }
        val response = call("search", web, extra)
        return SearchResult(
            videos = response.videos(),
            nextPage = response.continuationToken(),
        )
    }

    override suspend fun suggestions(query: String): List<String> = runCatching {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val root = http.get(
            "https://suggestqueries.google.com/complete/search?client=firefox&ds=yt&q=$encoded",
            JsonElement.serializer(),
        )
        (root as? JsonArray)?.getOrNull(1)
            ?.let { it.asArray() }
            ?.mapNotNull { it.text() }
            .orEmpty()
    }.getOrDefault(emptyList())

    override suspend fun video(id: String): VideoDetail {
        val response = call("player", vr, buildJsonObject {
            put("videoId", id)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
            put("playbackContext", buildJsonObject {
                put("contentPlaybackContext", buildJsonObject {
                    put("html5Preference", "HTML5_PREF_WANTS")
                })
            })
        })

        val status = response["playabilityStatus"].obj()?.str("status")
        if (status != "OK") {
            val reason = response["playabilityStatus"].obj()?.str("reason")
            if (BuildConfig.DEBUG) {
                android.util.Log.w("WeTube", "video $id playability=$status reason=$reason")
            }
            throw IOException(reason ?: "This video is not playable ($status).")
        }

        val details = response["videoDetails"].obj()
        val streaming = response["streamingData"].obj()

        if (BuildConfig.DEBUG) {
            android.util.Log.d(
                "WeTube",
                "video $id: ${streaming?.get("formats").objects().size ?: 0} muxed, " +
                    "${streaming?.get("adaptiveFormats").objects().size ?: 0} adaptive",
            )
        }
        val muxed = streaming?.get("formats").objects().mapNotNull { it.toFormat(audio = false) }
            .toMutableList()
        streaming?.str("hlsManifestUrl")?.takeIf { it.isNotBlank() }?.let { url ->
            muxed += StreamFormat(
                url = url,
                label = "live",
                mime = "application/x-mpegURL",
                bitrate = 0,
                height = 0,
                audio = false,
            )
        }

        val adaptive = streaming?.get("adaptiveFormats").objects()
        val videoOnly = adaptive
            .filter { it.str("mimeType")?.startsWith("video/") == true }
            .mapNotNull { it.toFormat(audio = false) }
        val audioOnly = adaptive
            .filter { it.str("mimeType")?.startsWith("audio/") == true }
            .mapNotNull { it.toFormat(audio = true) }

        val lengthSeconds = details?.str("lengthSeconds")?.toIntOrNull() ?: 0
        val live = details?.str("isLiveContent")?.toBoolean() == true
        val item = VideoItem(
            id = id,
            title = details?.str("title").orEmpty(),
            channelName = details?.str("author").orEmpty(),
            channelId = details?.str("channelId").orEmpty(),
            thumbnail = details?.get("thumbnail").thumbnailUrl()
                ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg",
            durationSeconds = lengthSeconds,
            views = details?.str("viewCount")?.toLongOrNull() ?: 0,
            publishedText = "",
            live = live,
        )

        // One extra request fills in the fields the player response omits:
        // likes, subscriber count, publish date, description and related items.
        val watch = runCatching { call("next", vr, buildJsonObject { put("videoId", id) }) }
            .getOrNull()

        val likes = watch?.findAll("likeButtonRenderer")
            ?.firstOrNull()
            ?.let { (it["likeCount"] as? JsonPrimitive)?.longOrNull }
            ?: 0L

        val subscribers = watch?.findAll("subscriberCountText")
            ?.firstOrNull()
            ?.text()
            .orEmpty()

        val description = details?.str("shortDescription").orEmpty().ifBlank {
            watch?.findAll("attributedDescription")
                ?.firstOrNull()
                ?.str("content")
                .orEmpty()
        }

        val publishText = watch?.findAll("dateText")
            ?.firstOrNull()
            ?.text()
            ?.removePrefix("Published on ")
            ?.removePrefix("Streamed live on ")
            ?.removePrefix("Premiered ")

        val related = watch?.videos()
            .orEmpty()
            .filter { it.id != id }

        return VideoDetail(
            item = item.copy(publishedText = publishText.orEmpty()),
            description = description,
            likes = likes,
            subscribersText = subscribers,
            playback = Playback(
                muxed = muxed,
                videoOnly = videoOnly,
                audioOnly = audioOnly,
            ),
            related = related,
            isLive = live,
        )
    }

    override suspend fun channel(id: String): ChannelInfo {
        val response = call("browse", vr, buildJsonObject { put("browseId", id) })

        // Depending on the surface, channel identity arrives either in
        // `metadata.channelMetadataRenderer` or in the tabbed page header.
        val meta = response["metadata"].obj()?.get("channelMetadataRenderer").obj()
        val header = response["header"].obj()?.get("c4TabbedHeaderRenderer").obj()

        return ChannelInfo(
            id = meta?.str("externalId") ?: header?.str("channelId") ?: id,
            name = meta?.str("title") ?: header?.str("title").orEmpty(),
            avatar = (meta?.get("avatar") ?: header?.get("avatar")).thumbnailUrl().orEmpty(),
            banner = (meta?.get("banner") ?: header?.get("banner")).thumbnailUrl().orEmpty(),
            description = meta?.str("description").orEmpty(),
            subscribersText = response.findAll("subscriberCountText").firstOrNull()?.text().orEmpty(),
            videos = response.videos(),
        )
    }

    override suspend fun comments(id: String): List<Comment> = emptyList()

    override suspend fun related(id: String): List<VideoItem> = runCatching {
        call("next", vr, buildJsonObject { put("videoId", id) })
            .videos()
            .filter { it.id != id }
    }.getOrDefault(emptyList())

    // ---- JSON walking ---------------------------------------------------

    private fun JsonObject.videos(): List<VideoItem> {
        val renderers = findAll("compactVideoRenderer") +
            findAll("videoRenderer") +
            findAll("videoWithContextRenderer") +
            findAll("gridVideoRenderer")
        return renderers.mapNotNull { it.toVideoItem() }.distinctBy { it.id }
    }

    private fun JsonObject.toVideoItem(): VideoItem? {
        val videoId = str("videoId") ?: str("contentId") ?: return null
        if (videoId.isBlank()) return null

        val title = (this["title"] ?: this["headline"]).text() ?: return null
        if (title.isBlank()) return null

        val byline = this["longBylineText"] ?: this["shortBylineText"] ?: this["ownerText"]
        val channelId = this["navigationEndpoint"].obj()
            ?.get("browseEndpoint").obj()?.str("browseId")
            ?: byline.obj()?.get("runs").objects().firstOrNull()
                ?.get("navigationEndpoint").obj()
                ?.get("browseEndpoint").obj()?.str("browseId")
            ?: ""

        val viewText = (this["viewCountText"] ?: this["shortViewCountText"]).text().orEmpty()
        val published = this["publishedTimeText"].text().orEmpty()
        val badgeText = this["badges"].text().orEmpty()

        return VideoItem(
            id = videoId,
            title = title,
            channelName = byline.text().orEmpty(),
            channelId = channelId,
            thumbnail = (this["thumbnail"] ?: this["channelThumbnail"]).thumbnailUrl()
                ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
            durationSeconds = parseLength(this["lengthText"].text()),
            views = parseCount(viewText),
            publishedText = published,
            live = badgeText.contains("LIVE", ignoreCase = true) ||
                viewText.contains("watching", ignoreCase = true),
        )
    }

    private fun JsonObject.toFormat(audio: Boolean): StreamFormat? {
        val url = str("url") ?: return null
        val mime = str("mimeType").orEmpty()
        val label = if (audio) {
            str("audioQuality")?.removePrefix("AUDIO_QUALITY_")?.lowercase() ?: "audio"
        } else {
            str("qualityLabel") ?: str("quality") ?: "video"
        }
        return StreamFormat(
            url = url,
            label = label,
            mime = mime.substringBefore(';').trim(),
            bitrate = (this["bitrate"] as? JsonPrimitive)?.longOrNull ?: 0L,
            height = (this["height"] as? JsonPrimitive)?.intOrNull ?: 0,
            audio = audio || mime.startsWith("audio/"),
        )
    }

    /** Pagination token, wherever the tree put it. */
    private fun JsonObject.continuationToken(): String? =
        findAll("continuationCommand").firstOrNull()?.str("token")

    private fun parseLength(text: String?): Int {
        if (text.isNullOrBlank()) return 0
        val parts = text.split(':').mapNotNull { it.trim().toIntOrNull() }
        return when (parts.size) {
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            2 -> parts[0] * 60 + parts[1]
            1 -> parts[0]
            else -> 0
        }
    }

    private fun parseCount(text: String): Long {
        if (text.isBlank()) return 0
        val match = Regex("([\\d.,]+)\\s*([KMB])?", RegexOption.IGNORE_CASE).find(text) ?: return 0
        val number = match.groupValues[1].replace(",", "").toDoubleOrNull() ?: return 0
        return when (match.groupValues[2].uppercase()) {
            "K" -> (number * 1_000).toLong()
            "M" -> (number * 1_000_000).toLong()
            "B" -> (number * 1_000_000_000).toLong()
            else -> number.toLong()
        }
    }

    private companion object {
        const val endpoint = "https://www.youtube.com/youtubei/v1"
        const val apiKey = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
    }
}

// ---- small JSON helpers -------------------------------------------------

private fun JsonElement?.obj(): JsonObject? = this as? JsonObject

private fun JsonObject?.str(key: String): String? = this?.get(key).text()

private fun JsonElement?.text(): String? = when (this) {
    is JsonPrimitive -> contentOrNull
    is JsonObject -> this["simpleText"].text()
        ?: this["runs"].text()
        ?: this["content"].text()
        ?: this["text"].text()
    is JsonArray -> joinToString("") { it.text().orEmpty() }.ifBlank { null }
    else -> null
}

private fun JsonElement?.objects(): List<JsonObject> =
    (this as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

private fun JsonElement?.asArray(): JsonArray? = this as? JsonArray

private fun JsonElement?.thumbnailUrl(): String? {
    val thumbnails = (this as? JsonObject)?.get("thumbnails").objects()
    return thumbnails
        .maxByOrNull { (it["width"] as? JsonPrimitive)?.intOrNull ?: 0 }
        ?.get("url")?.text()
}

/** Every object stored under [key], anywhere in the tree. */
private fun JsonObject.findAll(key: String): List<JsonObject> {
    val found = ArrayList<JsonObject>()
    fun walk(element: JsonElement) {
        when (element) {
            is JsonObject -> {
                (element[key] as? JsonObject)?.let(found::add)
                element.values.forEach(::walk)
            }
            is JsonArray -> element.forEach(::walk)
            else -> Unit
        }
    }
    walk(this)
    return found
}
