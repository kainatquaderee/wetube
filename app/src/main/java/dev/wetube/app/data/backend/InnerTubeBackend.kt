package dev.wetube.app.data.backend

import dev.wetube.app.BuildConfig
import dev.wetube.app.core.compactCount
import dev.wetube.app.core.decodeEntities
import dev.wetube.app.core.encodeUrlValue
import dev.wetube.app.core.parseCount
import dev.wetube.app.core.subscriberLabel
import dev.wetube.app.data.auth.AuthStore
import dev.wetube.app.data.model.ChannelInfo
import dev.wetube.app.data.model.CaptionSet
import dev.wetube.app.data.model.CaptionTrack
import dev.wetube.app.data.model.Comment
import dev.wetube.app.data.model.CommentPage
import dev.wetube.app.data.model.CommentThreadInfo
import dev.wetube.app.data.model.Playback
import dev.wetube.app.data.model.SearchResult
import dev.wetube.app.data.model.StreamFormat
import dev.wetube.app.data.model.TranslationLanguage
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
import android.os.SystemClock
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
 * Two client profiles carry the load, because they are good at different
 * things:
 *
 * | profile      | used for                      | why                              |
 * |--------------|-------------------------------|----------------------------------|
 * | `ANDROID`    | playback                      | plain progressive URLs, no `n`   |
 * | `ANDROID_VR` | anonymous home, channel, next | only client that answers those   |
 * | `WEB`        | signed-in feed, subscriptions | the only surface that takes a    |
 * |              |                               | session (native clients 400 it)  |
 *
 * Fragility is expected: profiles get rotated or gated by YouTube. That is why
 * [TubeBackend] exists and Invidious/Piped remain selectable in Settings.
 */
class InnerTubeBackend(
    private val http: JsonHttp,
    private val region: String,
    private val auth: AuthStore,
) : TubeBackend {

    override val displayName: String = "Direct (InnerTube)"

    /**
     * The account-write transport, built lazily from the same WEB session the
     * signed-in feeds already use.
     *
     * `by lazy` makes it stable for the lifetime of this backend instance: the
     * engine holds one [SyncBackend] reference and the transport closure stays
     * bound to *this* backend's visitor, auth headers and HTTP client. Because
     * [InnerTubeSync] owns only interpretation, this is the single place that
     * re-implements the session envelope, and it mirrors [call]'s WEB branch
     * exactly so a read and a write can never disagree about who is signed in.
     */
    override val sync: SyncBackend by lazy {
        InnerTubeSync(
            display = displayName,
            transport = SyncTransport { path, extra -> postForSync(path, extra) },
            json = http.json,
        )
    }

    private data class Profile(
        val userAgent: String,
        val context: JsonObject,
        /** Numeric id YouTube uses for this client in `X-Youtube-Client-Name`. */
        val clientId: String,
        val version: String,
        /**
         * Whether this client accepts a session. The native clients answer a
         * cookie-bearing request with HTTP 400 `INVALID_ARGUMENT`, so they are
         * never given one; only the web clients can speak for an account.
         */
        val acceptsAuth: Boolean = true,
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
        acceptsAuth = false,
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
     * The plain Android client.
     *
     * The safety net for playback: it always seems to hand out one un-ciphered
     * progressive URL (360p) that stays readable for the whole video, so
     * whatever else fails there is something to play. What it does not give is
     * choice — its adaptive tracks are SABR-only (no URL at all), which is why
     * [iosClient] leads.
     *
     * It cannot be combined with cookies: YouTube rejects ANDROID requests that
     * carry a session ("invalid argument"), so [call] skips the auth headers
     * for this profile.
     */
    private val androidClient = Profile(
        userAgent = "com.google.android.youtube/21.02.35 (Linux; U; Android 11) gzip",
        clientId = "3",
        version = "21.02.35",
        acceptsAuth = false,
        context = clientContext(
            name = "ANDROID",
            version = "21.02.35",
            extra = mapOf(
                "androidSdkVersion" to 30,
                "userAgent" to "com.google.android.youtube/21.02.35 (Linux; U; Android 11) gzip",
                "osName" to "Android",
                "osVersion" to "11",
            ),
        ),
    )

    /**
     * The iOS client, and the source of high-resolution playback.
     *
     * The Android clients now hide their adaptive tracks behind SABR — the
     * player response lists 29 of them and not one carries a URL — and the VR
     * client's URLs are throttled by YouTube's JavaScript `n` challenge after a
     * few tens of megabytes. IOS still answers with plain, un-ciphered adaptive
     * URLs for every quality up to 2160p, and those URLs download at full speed
     * because their `n` is already applied. Its identity is fixed (a made-up
     * build is rejected), and like the other native clients it refuses a
     * session, so playback stays anonymous while signed in.
     */
    private val iosUa = "com.google.ios.youtube/20.10.4 " +
        "(iPhone16,2; U; CPU iOS 18_3_1 like Mac OS X)"

    private val iosClient = Profile(
        userAgent = iosUa,
        clientId = "5",
        version = "20.10.4",
        acceptsAuth = false,
        context = clientContext(
            name = "IOS",
            version = "20.10.4",
            extra = mapOf(
                "deviceMake" to "Apple",
                "deviceModel" to "iPhone16,2",
                "osName" to "iPhone",
                "osVersion" to "18.3.1.22D72",
                "userAgent" to iosUa,
            ),
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

    /** The `WEB` client version the homepage currently advertises. */
    @Volatile
    private var webVersionCache: String = ""

    /**
     * The player signature timestamp (`sts`). The desktop player rejects a
     * request that omits it with "The page needs to be reloaded.", and it can
     * only be read from a watch page, so it is cached for an hour at a time.
     */
    @Volatile
    private var stsCache: Int = 0
    @Volatile
    private var stsAt: Long = 0L

    private suspend fun signatureTimestamp(id: String): Int {
        val age = SystemClock.elapsedRealtime() - stsAt
        if (stsCache > 0 && age < 60 * 60 * 1000L) return stsCache
        return runCatching {
            val html = http.getText(
                "https://www.youtube.com/watch?v=${encodeUrlValue(id)}",
                buildMap {
                    put("User-Agent", web.userAgent)
                    put("Accept-Language", "en-US,en;q=0.9")
                    putAll(auth.headers())
                },
            )
            Regex("\"STS\":(\\d{4,8})").find(html)?.groupValues?.get(1)?.toIntOrNull()
        }.getOrNull()?.also {
            stsCache = it
            stsAt = SystemClock.elapsedRealtime()
        } ?: stsCache
    }

    private val visitorLock = kotlinx.coroutines.sync.Mutex()

    private suspend fun visitor(): String {
        visitorCache.takeIf { it.isNotBlank() }?.let { return it }
        return visitorLock.withLock {
            visitorCache.takeIf { it.isNotBlank() } ?: run {
                runCatching {
                    val html = http.getText(
                        "https://www.youtube.com/",
                        buildMap {
                            put("User-Agent", web.userAgent)
                            put("Accept-Language", "en-US,en;q=0.9")
                            putAll(auth.headers())
                        },
                    )
                    Regex("\"visitorData\":\"([^\"]+)\"")
                        .find(html)
                        ?.groupValues
                        ?.get(1)
                        ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                        .also { visitor ->
                            Regex("\"INNERTUBE_CLIENT_VERSION\":\"([^\"]+)\"")
                                .find(html)
                                ?.groupValues
                                ?.get(1)
                                ?.takeIf { it.isNotBlank() }
                                ?.let { webVersionCache = it }
                        }
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
        withAuth: Boolean = true,
    ): JsonObject {
        val visitor = visitor()
        val client = profile.context["client"]!!.jsonObject.toMutableMap().apply {
            if (visitor.isNotBlank()) put("visitorData", JsonPrimitive(visitor))
        }
        val payload = buildJsonObject {
            put("context", buildJsonObject { put("client", JsonObject(client)) })
            extra.forEach { (key, value) -> put(key, value) }
        }
        // Signed in, these turn an anonymous call into an account call: YouTube
        // then answers with the user's own feed, subscriptions and age-gated
        // videos instead of the generic surface. Native clients do not accept a
        // session at all, so only the profile itself decides.
        val session = if (withAuth && profile.acceptsAuth) auth.headers() else emptyMap()
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
                putAll(session)
                if (session.isNotEmpty()) put("X-Youtube-Bootstrap-Logged-In", "true")
            },
        )
        response["responseContext"].obj()?.str("visitorData")
            ?.takeIf { it.isNotBlank() }
            ?.let { visitorCache = it }
        return response
    }

    /**
     * The real [SyncTransport] body: one signed WEB request, returned as data.
     *
     * This mirrors the envelope and headers [call] builds instead of calling
     * [call] itself, for two deliberate reasons. [call] throws on a non-2xx and
     * discards the body, while sync needs the status and the body *together*
     * because a sign-in wall is a 2xx body, not a status code. And keeping
     * [call]'s feed behaviour untouched means a change here cannot alter
     * trending, subscriptions or playback.
     *
     * `X-Goog-Api-Key` is intentionally omitted: docs/SYNC_PROTOCOL.md §1 records
     * it as uncertain for writes and the read path does not send it, so adding
     * it would be an unverifiable guess. An [IOException] is mapped to
     * [SyncReply.Unreachable] so the sync class never sees a transport
     * exception; any HTTP answer (including 4xx/5xx) is passed through verbatim
     * for [InnerTubeSync] to classify.
     */
    private suspend fun postForSync(path: String, extra: JsonObject): SyncReply = try {
        val visitor = visitor()
        val client = web.context["client"]!!.jsonObject.toMutableMap().apply {
            if (visitor.isNotBlank()) put("visitorData", JsonPrimitive(visitor))
        }
        val payload = buildJsonObject {
            put("context", buildJsonObject { put("client", JsonObject(client)) })
            extra.forEach { (key, value) -> put(key, value) }
        }
        // Signed in, these turn an anonymous call into an account call; only the
        // WEB profile accepts a session, which is why sync uses it and not the
        // native profiles.
        val session = auth.headers()
        val headers = buildMap {
            put("User-Agent", web.userAgent)
            put("X-Goog-Api-Format-Version", "2")
            put("X-Youtube-Client-Name", web.clientId)
            put("X-Youtube-Client-Version", web.version)
            put("Origin", "https://www.youtube.com")
            if (visitor.isNotBlank()) put("X-Goog-Visitor-Id", visitor)
            putAll(session)
            if (session.isNotEmpty()) put("X-Youtube-Bootstrap-Logged-In", "true")
        }
        val (code, body) = http.postJsonRaw("$endpoint/$path?prettyPrint=false", payload, headers)
        SyncReply.Http(code, body)
    } catch (e: IOException) {
        val reason = e.message?.takeIf { it.isNotBlank() } ?: e::class.simpleName ?: "network error"
        SyncReply.Unreachable(reason)
    }

    // ---- TubeBackend ----------------------------------------------------

    override suspend fun trending(region: String): List<VideoItem> {
        // Signed in, the account's own recommendations only exist on the web
        // surface; the native home feed would be the anonymous one. Fall back
        // to the anonymous feed if the web response comes back empty.
        if (auth.headers().isNotEmpty()) {
            val personal = runCatching {
                call("browse", web, buildJsonObject { put("browseId", "FEwhat_to_watch") }).videos()
            }.getOrDefault(emptyList())
            if (personal.isNotEmpty()) return personal
        }
        val response = call("browse", vr, buildJsonObject {
            put("browseId", "FEwhat_to_watch")
        })
        return response.videos()
    }

    /**
     * The account's own subscriptions feed. Empty without a session, because the
     * anonymous equivalent is just another list of recommendations.
     */
    override suspend fun subscriptions(): List<VideoItem> {
        if (auth.headers().isEmpty()) return emptyList()
        val response = call("browse", web, buildJsonObject {
            put("browseId", "FEsubscriptions")
        })
        return response.videos()
    }

    /**
     * A Shorts feed for the dedicated section.
     *
     * Signed in, the account's own Shorts shelf sits inside its personalised
     * home feed (`FEwhat_to_watch`), so that shelf is what the section follows;
     * the anonymous surface has no such shelf. Either way we also search the
     * `#shorts` hashtag, whose real clips arrive as `shortsLockupViewModel`
     * cards, and finally fall back to filtering the regular video feed.
     */
    override suspend fun shorts(): List<VideoItem> {
        val personal = if (auth.headers().isNotEmpty()) {
            runCatching {
                call("browse", web, buildJsonObject { put("browseId", "FEwhat_to_watch") })
                    .shortsLockups()
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }

        val search = runCatching {
            call("search", web, buildJsonObject { put("query", "#shorts") })
        }.getOrNull()
        val searched = buildList {
            search?.shortsLockups()?.let(::addAll)
            search?.videos()?.filterTo(this) { it.durationSeconds in 1..61 }
        }

        val merged = (personal + searched).distinctBy { it.id }
        if (BuildConfig.DEBUG) {
            android.util.Log.d(
                "WeTube",
                "shorts: signed=${auth.headers().isNotEmpty()} personal=${personal.size} " +
                    "searched=${searched.size} merged=${merged.size}",
            )
        }
        if (merged.isNotEmpty()) return merged.take(40)

        return runCatching {
            call("browse", vr, buildJsonObject { put("browseId", "FEwhat_to_watch") })
                .videos()
                .filter { it.durationSeconds in 1..61 }
        }.getOrDefault(emptyList())
            .distinctBy { it.id }
            .take(40)
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

    /**
     * Two player calls for the same video, made at the same time with the same
     * visitor, invalidate each other: YouTube signs each response against one
     * playback session, and the losing response comes back with media URLs that
     * answer 403. The UI can legitimately ask twice (the screen is recreated
     * while a deep link is handled), so collapse concurrent calls for the same
     * id onto the first response instead of racing them.
     */
    private val playerLock = kotlinx.coroutines.sync.Mutex()
    private var playerMemoId: String = ""
    private var playerMemoAt: Long = 0L
    private var playerMemo: JsonObject? = null

    /**
     * A one-shot request from [forcePlaybackFallback]. Player calls run under
     * [playerLock], so a set flag is consumed by the next resolve and cannot
     * leak into an unrelated one.
     */
    private val fallbackRequested = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun forcePlaybackFallback() {
        fallbackRequested.set(true)
    }

    /**
     * Asks YouTube for stream URLs.
     *
     * Client choice is deliberately narrow, and ordered by quality.
     *
     * `IOS` is the source of real resolution: it answers with un-ciphered
     * adaptive URLs for every quality up to 2160p, and those URLs download at
     * full speed (a 77 MB 1080p file end to end) because they carry no
     * throttling token. `ANDROID` is the safety net: it hands out one
     * progressive 360p file that always plays, even on a strict network.
     * `ANDROID_VR` is the last resort for the rare video the other two refuse;
     * its URLs stop working partway through, so it only helps clips.
     *
     * A recovery may have just had an IOS URL rejected at playback, so it asks
     * for a fallback: the order then starts at `ANDROID`, skipping the leader
     * that failed, and the short-lived memo is bypassed because it is likely to
     * hold exactly the response being retried.
     *
     * Mobile web and desktop web are not used here: they answer with URLs that
     * need a JavaScript `n` challenge solved, which WeTube does not fake.
     */
    private suspend fun playerResponse(id: String): JsonObject = playerLock.withLock {
        val fallback = fallbackRequested.getAndSet(false)
        val memo = playerMemo
        val fresh = SystemClock.elapsedRealtime() - playerMemoAt < 3_000
        if (!fallback && memo != null && playerMemoId == id && fresh) return@withLock memo

        // Native clients reject a request that carries a session ("invalid
        // argument"), so playback is anonymous even while signed in. The
        // session is still used for feeds and subscriptions.
        //
        // IOS answers with the resolution ladder; Android is the unbreakable
        // 360p fallback. Only the VR client wants the desktop signature, and
        // reading the watch page for it is not free, so that payload is built
        // only once both of the others come back empty.
        var answer: JsonObject? = null
        for (profile in playbackProfileOrder(skipLeading = fallback)) {
            answer = playerFor(profile, id)
            if (answer != null) break
        }
        val response = answer ?: error("YouTube did not answer the player request.")

        playerMemoId = id
        playerMemoAt = SystemClock.elapsedRealtime()
        playerMemo = response
        response
    }

    /**
     * One player request for [profile], or `null` when it had nothing playable.
     *
     * The VR body is built here, so its watch-page read happens only when the
     * order actually reaches VR: a playable IOS or Android response ends the
     * loop before this is called. A failed request is logged with its profile
     * name and treated as a miss, so the next profile still gets a turn.
     */
    private suspend fun playerFor(profile: PlaybackProfile, id: String): JsonObject? {
        val (client, body) = when (profile) {
            PlaybackProfile.IOS -> iosClient to playerBody(id)
            PlaybackProfile.ANDROID -> androidClient to playerBody(id)
            PlaybackProfile.VR -> vr to signedPlayerPayload(id)
        }
        return runCatching { call("player", client, body, withAuth = false) }
            .onFailure {
                if (BuildConfig.DEBUG) {
                    android.util.Log.w(
                        "WeTube",
                        "player (${profile.name.lowercase()}) failed: ${it.javaClass.simpleName}",
                    )
                }
            }
            .getOrNull()
            ?.takeIf { it.playableUrlCount() > 0 }
    }

    /** The minimal player body: one video id and the two content flags. */
    private fun playerBody(id: String): JsonObject = buildJsonObject {
        put("videoId", id)
        put("contentCheckOk", true)
        put("racyCheckOk", true)
    }

    /**
     * One muxed progressive track, for handing to a DLNA renderer.
     *
     * A television cannot pair an adaptive video with an adaptive audio track,
     * so this never returns a `videoOnly`/`audioOnly` entry and never needs the
     * local player's `wetube-merge://` wrapper: the result is one URL that
     * already carries both. A player response still inside the memo's freshness
     * window is free to read, but casting deliberately does not trigger the full
     * IOS ladder — the ANDROID client exists for exactly this case, and answers
     * with one always-playable progressive file in a single request.
     *
     * Both paths share [playerLock]: two player calls for the same video at the
     * same time invalidate each other's media URLs.
     */
    override suspend fun progressive(id: String): StreamFormat? {
        playerLock.withLock { freshMemoProgressive(id) }?.let { return it }

        return playerLock.withLock {
            // Re-check: a concurrent `video()` call may have filled the memo
            // while we waited for the lock.
            freshMemoProgressive(id)
                ?: runCatching { call("player", androidClient, playerBody(id), withAuth = false) }
                    .getOrNull()
                    ?.muxedProgressive()
        }
    }

    /**
     * The muxed track of the memo, when it is for [id] and still young enough
     * for its URLs to be valid. Caller holds [playerLock].
     */
    private fun freshMemoProgressive(id: String): StreamFormat? {
        val memo = playerMemo ?: return null
        if (playerMemoId != id) return null
        if (SystemClock.elapsedRealtime() - playerMemoAt >= 3_000) return null
        return memo.muxedProgressive()
    }

    /**
     * The payload the VR client wants: the base player body plus the watch
     * page's signature timestamp. Reading the watch page is expensive, so this
     * is built only for the last-resort client, never on the happy path.
     */
    private suspend fun signedPlayerPayload(id: String): JsonObject {
        val sts = signatureTimestamp(id)
        return buildJsonObject {
            put("videoId", id)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
            put("playbackContext", buildJsonObject {
                put("contentPlaybackContext", buildJsonObject {
                    put("html5Preference", "HTML5_PREF_WANTS")
                    if (sts > 0) put("signatureTimestamp", sts)
                })
            })
        }
    }

    override suspend fun video(id: String): VideoDetail {
        val response = playerResponse(id)

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

        val adaptive = streaming?.get("adaptiveFormats").objects()
        val videoOnly = adaptive
            .filter { it.str("mimeType")?.startsWith("video/") == true }
            .mapNotNull { it.toFormat(audio = false) }
        val audioOnly = adaptive
            .filter { it.str("mimeType")?.startsWith("audio/") == true }
            .mapNotNull { it.toFormat(audio = true) }

        val lengthSeconds = details?.str("lengthSeconds")?.toIntOrNull() ?: 0
        val live = details?.str("isLiveContent")?.toBoolean() == true

        // A live stream only exists as an adaptive manifest, so it keeps its
        // HLS URL. A recorded video keeps its per-quality streams instead —
        // that is what the quality picker drives — so its manifest is dropped.
        val hls = streaming?.str("hlsManifestUrl")
            ?.takeIf { it.isNotBlank() && (live || videoOnly.isEmpty()) }

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
            subscribersText = subscriberLabel(subscribers),
            playback = Playback(
                muxed = muxed,
                videoOnly = videoOnly,
                audioOnly = audioOnly,
                hls = hls,
            ),
            related = related,
            isLive = live,
            captions = response.captionSet(),
            comments = watch?.commentThreadInfo() ?: CommentThreadInfo(),
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
            subscribersText = subscriberLabel(
                response.findAll("subscriberCountText").firstOrNull()?.text().orEmpty(),
            ),
            videos = response.videos(),
        )
    }

    override suspend fun comments(id: String): List<Comment> {
        val watch = call("next", vr, buildJsonObject { put("videoId", id) })
        val token = watch.commentThreadInfo().continuation
        if (token.isBlank()) return emptyList()
        return commentsPage(token).comments
    }

    override suspend fun commentsPage(token: String): CommentPage {
        if (token.isBlank()) return CommentPage()
        val response = call("next", vr, buildJsonObject { put("continuation", token) })
        val comments = response.findAll("commentThreadRenderer").mapNotNull { it.toComment() }
        val next = response.findAll("nextContinuationData")
            .firstOrNull()
            ?.str("continuation")
            ?.takeIf { it.isNotBlank() }
        return CommentPage(comments = comments, next = next)
    }

    /**
     * The comment entry point in a watch response. Its header carries the
     * comment count and the sort menu, and its content holds the continuation
     * token for the first page of the default ("Top") sort.
     */
    private fun JsonObject.commentThreadInfo(): CommentThreadInfo {
        val panel = findAll("engagementPanelSectionListRenderer")
            .firstOrNull { it.str("panelIdentifier") == "comment-item-section" }
            ?: return CommentThreadInfo()

        val header = panel["header"].obj()
            ?.get("engagementPanelTitleHeaderRenderer").obj()

        val content = panel["content"].obj()
        val top = content?.get("sectionListRenderer").obj()
            ?.get("continuations").objects()
            ?.firstOrNull()
            ?.get("reloadContinuationData").obj()
            ?.str("continuation")
            ?: content?.get("continuationItemRenderer").obj()
                ?.get("continuationEndpoint").obj()
                ?.get("continuationCommand").obj()
                ?.str("token")
                .orEmpty()

        val newest = header?.get("menu").obj()
            ?.get("sortFilterSubMenuRenderer").obj()
            ?.get("subMenuItems").objects()
            ?.firstOrNull { it["title"].text()?.equals("Newest", ignoreCase = true) == true }
            ?.get("continuation").obj()
            ?.get("reloadContinuationData").obj()
            ?.str("continuation")
            .orEmpty()

        return CommentThreadInfo(
            continuation = top,
            newestContinuation = newest,
            // The header prints a localised label ("1,234", "222.0K"); compact
            // it here so every surface (player chip, comments sheet, Shorts
            // rail) shows the same "1.2K"/"222K" style.
            countText = compactCountLabel(header?.get("contextualInfo").text().orEmpty()),
        )
    }

    private fun JsonObject.toComment(): Comment? {
        val renderer = this["comment"].obj()?.get("commentRenderer").obj() ?: return null
        val text = renderer["contentText"].text()?.trim().orEmpty()
        if (text.isBlank()) return null

        val actions = renderer["actionButtons"].obj()
            ?.get("commentActionButtonsRenderer").obj()
        val likeLabel = actions?.get("likeButton").obj()
            ?.get("toggleButtonRenderer").obj()
            ?.get("accessibilityData").obj()
            ?.get("accessibilityData").obj()
            ?.str("label")

        return Comment(
            author = renderer["authorText"].text().orEmpty(),
            avatar = renderer["authorThumbnail"].thumbnailUrl().orEmpty(),
            text = text,
            likes = parseCount(renderer["voteCount"].text().orEmpty().ifBlank { likeLabel.orEmpty() }),
            publishedText = renderer["publishedTimeText"].text().orEmpty(),
            replyCount = parseCount(
                actions?.get("replyButton").obj()
                    ?.get("buttonRenderer").obj()
                    ?.get("text").text().orEmpty(),
            ).toInt(),
            id = renderer.str("commentId").orEmpty(),
        )
    }

    override suspend fun related(id: String): List<VideoItem> = runCatching {
        call("next", vr, buildJsonObject { put("videoId", id) })
            .videos()
            .filter { it.id != id }
    }.getOrDefault(emptyList())

    // ---- JSON walking ---------------------------------------------------

    /**
     * Subtitle tracks from a player response.
     *
     * The Android client is asked first for playback, and it is also the client
     * that still returns usable (non-PoToken-gated) subtitle URLs. When a video
     * carries no translation list, the menu simply offers the original tracks.
     */
    private fun JsonObject.captionSet(): CaptionSet {
        val renderer = this["captions"].obj()
            ?.get("playerCaptionsTracklistRenderer").obj()
            ?: return CaptionSet()

        val tracks = renderer["captionTracks"].objects().mapNotNull { track ->
            val url = track.str("baseUrl")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val code = track.str("languageCode").orEmpty().ifBlank { "und" }
            CaptionTrack(
                languageCode = code,
                name = track["name"].text()?.takeIf { it.isNotBlank() } ?: code,
                baseUrl = url,
                autoGenerated = track.str("kind") == "asr",
            )
        }

        val translations = renderer["translationLanguages"].objects().mapNotNull { language ->
            val code = language.str("languageCode") ?: return@mapNotNull null
            TranslationLanguage(
                languageCode = code,
                name = language["languageName"].text()?.takeIf { it.isNotBlank() } ?: code,
            )
        }

        return CaptionSet(tracks = tracks, translations = translations)
    }

    /** Progressively downloadable tracks (a URL, not a cipher) in a player response. */
    private fun JsonObject.playableUrlCount(): Int {
        val data = this["streamingData"].obj() ?: return 0
        val tracks = data["formats"].objects() + data["adaptiveFormats"].objects()
        return tracks.count { !it.str("url").isNullOrBlank() }
    }

    /**
     * Video cards, in the order the page lists them.
     *
     * Two card families exist: the older `*VideoRenderer` objects used by the
     * native surfaces, and the web's `lockupViewModel` cards. Walking the tree
     * once keeps the feed order instead of grouping by renderer name.
     */
    private fun JsonObject.videos(): List<VideoItem> {
        val found = ArrayList<VideoItem>()

        fun walk(element: JsonElement) {
            when (element) {
                is JsonObject -> element.forEach { (key, value) ->
                    val item = when (key) {
                        "compactVideoRenderer", "videoRenderer", "videoWithContextRenderer",
                        "gridVideoRenderer", "reelItemRenderer",
                        -> (value as? JsonObject)?.toVideoItem()

                        "lockupViewModel" -> (value as? JsonObject)?.toLockupItem()

                        else -> null
                    }
                    if (item != null) found.add(item)
                    walk(value)
                }

                is JsonArray -> element.forEach(::walk)
                else -> Unit
            }
        }

        walk(this)
        return found.distinctBy { it.id }
    }

    /** Web Shorts shelf cards (`shortsLockupViewModel`), in page order. */
    private fun JsonObject.shortsLockups(): List<VideoItem> {
        val found = ArrayList<VideoItem>()

        fun walk(element: JsonElement) {
            when (element) {
                is JsonObject -> element.forEach { (key, value) ->
                    if (key == "shortsLockupViewModel") {
                        (value as? JsonObject)?.toShortsLockupItem()?.let(found::add)
                    }
                    walk(value)
                }

                is JsonArray -> element.forEach(::walk)
                else -> Unit
            }
        }

        walk(this)
        return found.distinctBy { it.id }
    }

    /**
     * A Shorts card. The clip id lives in the reel endpoint, the title and view
     * count in the overlay, and the poster frame in the thumbnail view model.
     * The card carries no channel name or length, so the Shorts screen fills the
     * channel in from the player response once the clip starts.
     */
    private fun JsonObject.toShortsLockupItem(): VideoItem? {
        val reel = this["onTap"].obj()
            ?.get("innertubeCommand").obj()
            ?.get("reelWatchEndpoint").obj()
        val videoId = reel?.str("videoId")
            ?: str("entityId")?.removePrefix("shorts-shelf-item-")?.takeIf { it.isNotBlank() }
            ?: return null

        val overlay = this["overlayMetadata"].obj()
        val title = overlay?.get("primaryText").obj()?.str("content")
            ?.takeIf { it.isNotBlank() }
            ?: str("accessibilityText")?.substringBefore(" - play Short").orEmpty()
        val thumbnail = this["thumbnailViewModel"].obj()
            ?.get("thumbnailViewModel").obj()
            ?.get("image").thumbnailUrl()
            ?: reel?.get("thumbnail").thumbnailUrl()

        return VideoItem(
            id = videoId,
            title = title,
            channelName = "",
            channelId = "",
            thumbnail = thumbnail ?: "https://i.ytimg.com/vi/$videoId/frame0.jpg",
            durationSeconds = 0,
            views = parseCount(overlay?.get("secondaryText").obj()?.str("content").orEmpty()),
            publishedText = "",
            live = false,
        )
    }

    /**
     * A web lockup card ("lockupViewModel"). It carries its video id in
     * `contentId` and its metadata in `lockupMetadataViewModel`.
     */
    private fun JsonObject.toLockupItem(): VideoItem? {
        val type = str("contentType").orEmpty()
        if (!type.endsWith("VIDEO") && !type.endsWith("SHORTS")) return null

        val videoId = str("contentId")?.takeIf { it.isNotBlank() } ?: return null
        val meta = this["metadata"].obj()?.get("lockupMetadataViewModel").obj() ?: return null
        val title = meta["title"].text()?.takeIf { it.isNotBlank() } ?: return null

        val texts = meta["metadata"].obj()
            ?.get("contentMetadataViewModel").obj()
            ?.get("metadataRows").objects()
            .flatMap { it["metadataParts"].objects() }
            .mapNotNull { it["text"].text() }

        val channelName = texts.firstOrNull().orEmpty()
        val viewsText = texts.firstOrNull { it.contains("view", ignoreCase = true) }.orEmpty()
        val published = texts.firstOrNull { it.contains("ago", ignoreCase = true) }.orEmpty()

        val thumbnail = this["contentImage"].obj()
            ?.get("thumbnailViewModel").obj()
            ?.get("image").thumbnailUrl()
            ?: meta["image"].thumbnailUrl()

        val overlays = this["contentImage"].obj()
            ?.get("thumbnailViewModel").obj()
            ?.get("overlays").objects()
            .orEmpty()
        val badgeText = overlays
            .flatMap { it["thumbnailBottomOverlayViewModel"].obj()?.get("badges").objects().orEmpty() }
            .mapNotNull { it["thumbnailBadgeViewModel"].obj()?.str("text") }
            .joinToString(" ")

        return VideoItem(
            id = videoId,
            title = title,
            channelName = channelName,
            channelId = meta.findAll("browseEndpoint").firstOrNull()?.str("browseId").orEmpty(),
            thumbnail = thumbnail ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
            durationSeconds = overlays
                .mapNotNull { it["thumbnailBottomOverlayViewModel"].obj() }
                .flatMap { it["badges"].objects() }
                .mapNotNull { it["thumbnailBadgeViewModel"].obj()?.str("text") }
                .map(::parseLength)
                .firstOrNull { it > 0 } ?: 0,
            views = parseCount(viewsText),
            publishedText = published,
            live = badgeText.contains("LIVE", ignoreCase = true) ||
                viewsText.contains("watching", ignoreCase = true),
        )
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

    /**
     * The best muxed progressive track in a player response, or `null`.
     *
     * Only `streamingData.formats` is considered; `adaptiveFormats` are
     * single-purpose tracks a renderer cannot pair on its own. H.264/AAC is
     * preferred and the pick caps at 1080p, because that is what consumer
     * televisions decode in hardware. A rare progressive file that only exists
     * above the cap is still accepted rather than refusing to cast.
     */
    private fun JsonObject.muxedProgressive(): StreamFormat? {
        val muxed = get("streamingData").obj()?.get("formats").objects()
            .mapNotNull { it.toFormat(audio = false) }
            .filter { it.url.isNotBlank() }
        if (muxed.isEmpty()) return null

        val mp4 = muxed.filter { it.mime == "video/mp4" }
        val capped = (mp4.ifEmpty { muxed }).filter { it.height in 1..1080 }
        return (capped.ifEmpty { mp4.ifEmpty { muxed } }).maxByOrNull { it.height }
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

    private fun parseLength(text: String?): Int = parseDurationLabel(text)

    /** Leaves non-numeric labels ("Comments are turned off") untouched. */
    private fun compactCountLabel(text: String): String =
        if (text.any { it.isDigit() }) compactCount(parseCount(text)) else text

    private companion object {
        const val endpoint = "https://www.youtube.com/youtubei/v1"
        const val apiKey = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
    }
}

// ---- small JSON helpers -------------------------------------------------

internal fun JsonElement?.obj(): JsonObject? = this as? JsonObject

internal fun JsonObject?.str(key: String): String? = this?.get(key).text()

/**
 * Human-readable text, decoded at the JSON→model boundary.
 *
 * The walker below deliberately reads through [rawText], so a run array is
 * joined before its entities are decoded. Decoding inside the recursion would
 * run [decodeEntities] on each fragment separately, which can split a
 * multi-character reference across runs and leave it half-decoded; joining
 * first keeps whole references intact.
 */
internal fun JsonElement?.text(): String? = rawText()?.let(::decodeEntities)

/** The structural walker behind [text]: joins runs and arrays without decoding. */
private fun JsonElement?.rawText(): String? = when (this) {
    is JsonPrimitive -> contentOrNull
    is JsonObject -> this["simpleText"].rawText()
        ?: this["runs"].rawText()
        ?: this["content"].rawText()
        ?: this["text"].rawText()
    is JsonArray -> joinToString("") { it.rawText().orEmpty() }.ifBlank { null }
    else -> null
}

internal fun JsonElement?.objects(): List<JsonObject> =
    (this as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

private fun JsonElement?.asArray(): JsonArray? = this as? JsonArray

internal fun JsonElement?.thumbnailUrl(): String? {
    val object0 = this as? JsonObject ?: return null
    // Native renderers say `thumbnails`; web view models say `sources`.
    val thumbnails = (object0["thumbnails"] ?: object0["sources"]).objects()
    return thumbnails
        .maxByOrNull { (it["width"] as? JsonPrimitive)?.intOrNull ?: 0 }
        ?.get("url")?.text()
}

/** Every object stored under [key], anywhere in the tree. */
internal fun JsonObject.findAll(key: String): List<JsonObject> {
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

/**
 * Reads a renderer length label (`H:MM:SS`, `M:SS` or a bare `S`) into seconds.
 *
 * Shared by the feed cards and the sync playlist reader so a duration never
 * means two different things on two surfaces. Only the colon forms YouTube
 * actually prints are recognised; anything else is `0` rather than a guess.
 * Top-level (not a member) because the sync reader lives in another file and
 * both need the same answer.
 */
internal fun parseDurationLabel(text: String?): Int {
    if (text.isNullOrBlank()) return 0
    val parts = text.split(':').mapNotNull { it.trim().toIntOrNull() }
    return when (parts.size) {
        3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
        2 -> parts[0] * 60 + parts[1]
        1 -> parts[0]
        else -> 0
    }
}

