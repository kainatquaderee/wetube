package dev.wetube.app.data.backend

import dev.wetube.app.core.parseCount
import dev.wetube.app.data.auth.extractAccountChannelId
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * The real InnerTube implementation of [SyncBackend].
 *
 * This class owns *interpretation*, not transport: it builds only the
 * non-`context` members of each request and reads the renderer tree that comes
 * back. Everything that needs a session — the `context` envelope, `Cookie` /
 * `SAPISIDHASH`, the visitor id, the endpoint URL — lives behind
 * [SyncTransport]. That split is deliberate:
 *
 * - it keeps this file free of Android imports and I/O, so it is exercised in
 *   plain JUnit with canned replies and no account;
 * - it keeps the retry/pause policy in one place ([OpResult]) instead of spread
 *   across an OkHttp callback and a coroutine;
 * - it means a future transport (a proxy, a test double, a different client
 *   identity) can be swapped without re-implementing any parsing.
 *
 * All requests are `youtubei/v1/<path>` POSTs; [SyncTransport.post] maps an
 * [java.io.IOException] to [SyncReply.Unreachable] so this class never has to
 * catch one. Any Throwable a custom transport leaks is classified through the
 * shared [classify] helper, so even a broken seam cannot escape as an exception.
 *
 * **Write status.** Every *read* here follows the believed-correct shapes in
 * docs/SYNC_PROTOCOL.md §2. Every *write* is implemented to the documented
 * shape in §3 but is **UNVERIFIED** until the SYNC-0 capability test records a
 * pass. A `2xx` is the only thing this class can observe; whether YouTube
 * actually applied the change is the capability test's question, not this
 * class's.
 */
internal class InnerTubeSync(
    private val display: String,
    private val transport: SyncTransport,
    private val json: Json,
) : SyncBackend {

    override val displayName: String = display

    // ----- Reads ------------------------------------------------------------

    /**
     * The account's own channel id, or `Ok("")` when the menu answers but
     * carries no `UC...` id.
     *
     * The engine only asks whether this call is [OpResult.Ok]: `accountKey` is
     * derived separately from the session, and docs/SYNC_PROTOCOL.md §1 records
     * the SAPISID hash as the fallback identity. Returning an empty string on a
     * valid 200 therefore keeps a menu whose shape changed from aborting a sync
     * that can otherwise proceed; a failure (auth wall, 4xx, 5xx) is still a
     * real failure and is reported as such.
     */
    override suspend fun accountChannelId(): OpResult<String> =
        perform("account/account_menu", buildJsonObject {}) { root ->
            extractAccountChannelId(root).orEmpty()
        }

    /**
     * Every channel the account follows, from the `FEchannels` browse tab.
     *
     * `FEchannels` is believed correct (docs/SYNC_PROTOCOL.md §2.1) but is
     * paginated by a continuation this method intentionally ignores for now: the
     * reconciler reasons about presence, and the first page is the set the
     * capability test checks. If it is empty *or* fails, this falls back to
     * `FEsubscriptions` and harvests channel ids from each video card's byline.
     *
     * The fallback is **degraded**: it only contains channels with a recent
     * upload, so it is not a complete subscription list. The reconciler is safe
     * against that because a missing id is "not seen remotely", not "deleted
     * remotely"; the engine's merge prompt can warn that the account list is
     * incomplete. When the fallback also fails, the *first* failure is returned
     * so the reason names the primary endpoint that mattered; when it succeeds
     * empty, an empty account is `Ok(emptyList())`, never an error.
     */
    override suspend fun pullSubscriptions(): OpResult<List<RemoteChannel>> {
        val primary = perform("browse", buildJsonObject { put("browseId", "FEchannels") }) { root ->
            root.channelRenderers()
        }
        if (primary is OpResult.Ok && primary.value.isNotEmpty()) return primary

        val fallback = perform("browse", buildJsonObject { put("browseId", "FEsubscriptions") }) { root ->
            root.subscriptionFallbackChannels()
        }
        return when (fallback) {
            is OpResult.Ok -> fallback
            else -> primary
        }
    }

    /**
     * Every playlist the account owns, from the `FEplaylist_aggregation` tab.
     *
     * Both renderer families are read: the older `gridPlaylistRenderer` and the
     * web's `lockupViewModel` with `contentType == "PLAYLIST"`. An empty account
     * is `Ok(emptyList())`.
     *
     * The authoritative source of the WeTube-owned favourites mirror is
     * `AccountSyncState.favouritesPlaylistId`; a playlist is never recognised as
     * owned because of its title.
     */
    override suspend fun pullPlaylists(): OpResult<List<RemotePlaylist>> =
        perform("browse", buildJsonObject { put("browseId", "FEplaylist_aggregation") }) { root ->
            root.playlistRenderers()
        }

    /**
     * One page of [playlistId]'s contents.
     *
     * The first page addresses `browseId: "VL" + playlistId`; later pages send
     * the previous page's `continuation` token, exactly as the web player does
     * (docs/SYNC_PROTOCOL.md §2.3). `setVideoId` is the identity of a *membership*
     * and is required to remove it, so it is copied verbatim when present and
     * left empty when absent — never guessed, because a wrong value would remove
     * the wrong video or fail confusingly.
     */
    override suspend fun pullPlaylist(playlistId: String, next: String?): OpResult<PlaylistPage> {
        val extra = buildJsonObject {
            if (next != null) put("continuation", next) else put("browseId", "VL$playlistId")
        }
        return perform("browse", extra) { root ->
            PlaylistPage(items = root.playlistVideoRenderers(), next = root.nextContinuation())
        }
    }

    // ----- Writes (endpoint shapes are UNVERIFIED; see docs/SYNC_PROTOCOL.md §3) -----

    /**
     * Subscribes to [channelIds].
     *
     * UNVERIFIED: `subscription/subscribe` and the plural `channelIds` array are
     * the documented shape (§3.1), but the engine deliberately sends one id at a
     * time until the capability test proves batching is honoured.
     */
    override suspend fun subscribe(channelIds: List<String>): OpResult<Unit> =
        write("subscription/subscribe", channelIdsBody(channelIds))

    /** UNVERIFIED: same uncertain endpoint/array shape as [subscribe] (§3.1). */
    override suspend fun unsubscribe(channelIds: List<String>): OpResult<Unit> =
        write("subscription/unsubscribe", channelIdsBody(channelIds))

    /**
     * Creates a private-by-default playlist and returns its id.
     *
     * UNVERIFIED: `playlist/create` and `privacyStatus` are the documented shape
     * (§3.2). The id is read from whichever place the response put it — the
     * top-level `playlistId`, a `createPlaylistResponse`, or a nested
     * `playlistEditEndpoint` — because §5 lists that location as an open
     * question. A `2xx` without a discoverable id is [OpResult.Terminal]: the
     * caller cannot address the playlist it just made, and retrying the same
     * create could make duplicates.
     */
    override suspend fun createPlaylist(
        title: String,
        privacy: PlaylistPrivacy,
        videoIds: List<String>,
    ): OpResult<String> = perform(
        "playlist/create",
        buildJsonObject {
            put("title", title)
            put("privacyStatus", privacy.wire())
            put("videoIds", stringArray(videoIds))
        },
    ) { root -> root.firstPlaylistId() }

    /**
     * Renames [playlistId].
     *
     * UNVERIFIED: `playlist/edit` with `ACTION_SET_PLAYLIST_NAME` (§3.2).
     */
    override suspend fun renamePlaylist(playlistId: String, title: String): OpResult<Unit> =
        editPlaylist(
            playlistId,
            buildJsonArray {
                add(buildJsonObject {
                    put("action", "ACTION_SET_PLAYLIST_NAME")
                    put("playlistName", title)
                })
            },
        )

    /**
     * Changes [playlistId]'s visibility.
     *
     * UNVERIFIED: `playlist/edit` with `ACTION_SET_PLAYLIST_PRIVACY` (§3.2). The
     * documented enum values are the same words [PlaylistPrivacy] already uses.
     */
    override suspend fun setPlaylistPrivacy(
        playlistId: String,
        privacy: PlaylistPrivacy,
    ): OpResult<Unit> = editPlaylist(
        playlistId,
        buildJsonArray {
            add(buildJsonObject {
                put("action", "ACTION_SET_PLAYLIST_PRIVACY")
                put("privacyStatus", privacy.wire())
            })
        },
    )

    /**
     * Adds [videoIds] to [playlistId].
     *
     * UNVERIFIED: `playlist/edit` with one `ACTION_ADD_VIDEO` action per id
     * (§3.2). The action list is the batch the write planner already chunks, so
     * a transport call maps one-to-one to a planned op.
     */
    override suspend fun addToPlaylist(playlistId: String, videoIds: List<String>): OpResult<Unit> =
        editPlaylist(
            playlistId,
            buildJsonArray {
                videoIds.forEach { id ->
                    add(buildJsonObject {
                        put("action", "ACTION_ADD_VIDEO")
                        put("addedVideoId", id)
                    })
                }
            },
        )

    /**
     * Removes [removals] from [playlistId].
     *
     * UNVERIFIED: `playlist/edit` with `ACTION_REMOVE_VIDEO` and both
     * `removedVideoId` and `setVideoId` per removal (§2.3, §3.3). A blank
     * `setVideoId` is copied as-is rather than guessed: the protocol says such an
     * item is pull-only, and the engine is responsible for not queuing it.
     */
    override suspend fun removeFromPlaylist(
        playlistId: String,
        removals: List<RemotePlaylistItem>,
    ): OpResult<Unit> = editPlaylist(
        playlistId,
        buildJsonArray {
            removals.forEach { item ->
                add(buildJsonObject {
                    put("action", "ACTION_REMOVE_VIDEO")
                    put("removedVideoId", item.videoId)
                    put("setVideoId", item.setVideoId)
                })
            }
        },
    )

    /**
     * Deletes [playlistId].
     *
     * UNVERIFIED: `playlist/delete` (§3.2). The engine only ever calls this for
     * a playlist WeTube created; that safety rule lives in the engine, not here,
     * so this method stays a faithful transport of the request.
     */
    override suspend fun deletePlaylist(playlistId: String): OpResult<Unit> =
        perform(
            "playlist/delete",
            buildJsonObject { put("playlistId", playlistId) },
        ) { Unit }

    // ----- Transport interpretation ----------------------------------------

    /**
     * Sends one request and turns the reply into an [OpResult].
     *
     * [parse] runs only for a `2xx` that is a JSON object without a top-level
     * `error`. It returns `null` when the response is well-formed but cannot
     * answer the question (for example a create with no id); that is a
     * [OpResult.Terminal], because retrying a request the server already accepted
     * could duplicate it. A read instead returns an empty collection rather than
     * `null`, so an empty account stays [OpResult.Ok].
     */
    private suspend fun <T : Any> perform(
        path: String,
        extra: JsonObject,
        parse: (JsonObject) -> T?,
    ): OpResult<T> {
        val reply = try {
            transport.post(path, extra)
        } catch (e: CancellationException) {
            // Coroutine cancellation is not a sync failure; let it unwind.
            throw e
        } catch (e: Exception) {
            // The seam promises never to throw. If a custom transport does, use
            // the same classifier an IOException would take, so a bug cannot
            // turn a transient failure into an escaped exception.
            return classify(e, code = null)
        }

        return when (reply) {
            is SyncReply.Unreachable -> OpResult.Retryable(reply.reason)
            is SyncReply.Http ->
                if (reply.code in 200..299) successful(reply.body, parse)
                else classifyHttpStatus(reply.code)
        }
    }

    /** Interprets a `2xx` body: auth wall, then structure, then [parse]. */
    private fun <T : Any> successful(body: String, parse: (JsonObject) -> T?): OpResult<T> {
        if (looksLikeSignInWall(body)) {
            return OpResult.AuthRequired("sign-in required")
        }
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: return OpResult.Terminal("malformed response body")
        if (root.containsKey("error")) {
            return OpResult.Terminal("response carried an error")
        }
        val value = runCatching { parse(root) }.getOrNull()
            ?: return OpResult.Terminal("response did not contain the expected value")
        return OpResult.Ok(value)
    }

    private suspend fun write(path: String, extra: JsonObject): OpResult<Unit> =
        perform(path, extra) { Unit }

    private suspend fun editPlaylist(playlistId: String, actions: JsonArray): OpResult<Unit> =
        write(
            "playlist/edit",
            buildJsonObject {
                put("playlistId", playlistId)
                put("actions", actions)
            },
        )

    private fun channelIdsBody(channelIds: List<String>): JsonObject = buildJsonObject {
        put("channelIds", stringArray(channelIds))
    }

    // ----- JSON walking -----------------------------------------------------

    /** `gridChannelRenderer` / `channelRenderer` entries, in page order. */
    private fun JsonObject.channelRenderers(): List<RemoteChannel> {
        val found = ArrayList<RemoteChannel>()
        fun walk(element: JsonElement) {
            when (element) {
                is JsonObject -> element.forEach { (key, value) ->
                    if (key == "gridChannelRenderer" || key == "channelRenderer") {
                        (value as? JsonObject)?.toRemoteChannel()?.let(found::add)
                    }
                    walk(value)
                }

                is JsonArray -> element.forEach(::walk)
                else -> Unit
            }
        }
        walk(this)
        return found.distinctBy { it.channelId }
    }

    /**
     * One channel card. `channelId` is the stable id; the navigation endpoint is
     * the fallback used by some shelf variants that only carry the link.
     */
    private fun JsonObject.toRemoteChannel(): RemoteChannel? {
        val channelId = str("channelId")
            ?: this["navigationEndpoint"].obj()
                ?.get("browseEndpoint").obj()
                ?.str("browseId")
            ?: return null
        if (channelId.isBlank()) return null
        return RemoteChannel(
            channelId = channelId,
            title = this["title"].text().orEmpty(),
            avatar = this["thumbnail"].thumbnailUrl().orEmpty(),
            subscriberText = this["subscriberCountText"].text().orEmpty(),
        )
    }

    /**
     * The degraded `FEsubscriptions` fallback: harvest every channel
     * `browseEndpoint.browseId` in the response.
     *
     * Only ids that look like a channel (`UC` + 22 characters) or whose
     * `canonicalBaseUrl` is a `/channel/<id>` link are kept, so a tab or playlist
     * endpoint cannot leak into the subscription set. Titles are unavailable from
     * this surface, which is part of why the list is labelled degraded.
     */
    private fun JsonObject.subscriptionFallbackChannels(): List<RemoteChannel> {
        val ids = LinkedHashSet<String>()
        fun walk(element: JsonElement) {
            when (element) {
                is JsonObject -> {
                    val endpoint = element["browseEndpoint"] as? JsonObject
                    val id = (endpoint?.get("browseId") as? JsonPrimitive)?.contentOrNull
                    val canonical = (endpoint?.get("canonicalBaseUrl") as? JsonPrimitive)?.contentOrNull
                    val channel = when {
                        id != null && CHANNEL_ID.matches(id) -> id
                        canonical != null && canonical.startsWith("/channel/") ->
                            canonical.removePrefix("/channel/").substringBefore('/')
                        else -> null
                    }
                    if (channel != null && CHANNEL_ID.matches(channel)) ids += channel
                    element.values.forEach(::walk)
                }

                is JsonArray -> element.forEach(::walk)
                else -> Unit
            }
        }
        walk(this)
        return ids.map { RemoteChannel(channelId = it, title = "") }
    }

    /** `gridPlaylistRenderer` and PLAYLIST `lockupViewModel` entries. */
    private fun JsonObject.playlistRenderers(): List<RemotePlaylist> {
        val found = ArrayList<RemotePlaylist>()
        fun walk(element: JsonElement) {
            when (element) {
                is JsonObject -> element.forEach { (key, value) ->
                    val playlist = when (key) {
                        "gridPlaylistRenderer" -> (value as? JsonObject)?.toGridPlaylist()
                        "lockupViewModel" -> (value as? JsonObject)?.toLockupPlaylist()
                        else -> null
                    }
                    if (playlist != null) found.add(playlist)
                    walk(value)
                }

                is JsonArray -> element.forEach(::walk)
                else -> Unit
            }
        }
        walk(this)
        return found.distinctBy { it.playlistId }
    }

    private fun JsonObject.toGridPlaylist(): RemotePlaylist? {
        val id = str("playlistId")?.takeIf { it.isNotBlank() } ?: return null
        val title = this["title"].text().orEmpty()
        return RemotePlaylist(
            playlistId = id,
            title = title,
            itemCount = parseCount(this["videoCountText"].text().orEmpty()).toCount(),
        )
    }

    /**
     * A web lockup playlist. Its title and item count live under
     * `metadata.lockupMetadataViewModel`, with the count in one of the metadata
     * text parts as e.g. `"12 videos"`.
     */
    private fun JsonObject.toLockupPlaylist(): RemotePlaylist? {
        if (str("contentType") != "PLAYLIST") return null
        val id = str("contentId")?.takeIf { it.isNotBlank() } ?: return null
        val meta = this["metadata"].obj()?.get("lockupMetadataViewModel").obj()
        val title = meta?.get("title").text().orEmpty()
        val countText = meta?.get("metadata").obj()
            ?.get("contentMetadataViewModel").obj()
            ?.get("metadataRows").objects()
            .orEmpty()
            .flatMap { it["metadataParts"].objects() }
            .mapNotNull { it["text"].text() }
            .firstOrNull { it.contains("video", ignoreCase = true) }
            .orEmpty()
        return RemotePlaylist(
            playlistId = id,
            title = title,
            itemCount = parseCount(countText).toCount(),
        )
    }

    /** `playlistVideoRenderer` entries, in page order. */
    private fun JsonObject.playlistVideoRenderers(): List<RemotePlaylistItem> {
        val found = ArrayList<RemotePlaylistItem>()
        fun walk(element: JsonElement) {
            when (element) {
                is JsonObject -> element.forEach { (key, value) ->
                    if (key == "playlistVideoRenderer") {
                        (value as? JsonObject)?.toRemotePlaylistItem()?.let(found::add)
                    }
                    walk(value)
                }

                is JsonArray -> element.forEach(::walk)
                else -> Unit
            }
        }
        walk(this)
        return found
    }

    /**
     * One playlist membership. `setVideoId` is copied only when the renderer
     * actually carries it; a blank value means removal is impossible and is
     * deliberately not replaced by `videoId` or a made-up token.
     */
    private fun JsonObject.toRemotePlaylistItem(): RemotePlaylistItem? {
        val videoId = str("videoId")?.takeIf { it.isNotBlank() } ?: return null
        val byline = this["shortBylineText"] ?: this["longBylineText"]
        val channelId = byline.obj()
            ?.get("runs").objects()
            ?.firstOrNull()
            ?.get("navigationEndpoint").obj()
            ?.get("browseEndpoint").obj()
            ?.str("browseId")
            ?: this["navigationEndpoint"].obj()
                ?.get("browseEndpoint").obj()
                ?.str("browseId")
            ?: ""
        return RemotePlaylistItem(
            videoId = videoId,
            setVideoId = str("setVideoId").orEmpty(),
            index = this["index"].text()?.toIntOrNull() ?: 0,
            title = this["title"].text().orEmpty(),
            channelName = byline.text().orEmpty(),
            channelId = channelId,
            thumbnail = this["thumbnail"].thumbnailUrl().orEmpty(),
            durationSeconds = parseDurationLabel(this["lengthText"].text()),
        )
    }

    /**
     * The next page token, from every shape the renderer tree uses: a
     * `continuationItemRenderer` (whose command `findAll` already finds), the
     * older `nextContinuationData`, or a bare `continuations` entry.
     */
    private fun JsonObject.nextContinuation(): String? =
        findAll("continuationCommand").firstOrNull()?.str("token")?.takeIf { it.isNotBlank() }
            ?: findAll("nextContinuationData").firstOrNull()
                ?.str("continuation")?.takeIf { it.isNotBlank() }

    /**
     * The first playlist id anywhere in a create response. The protocol lists
     * the location as an open question (§5), so both a top-level `playlistId`
     * and a nested `playlistEditEndpoint.playlistId` / `createPlaylistResponse`
     * are accepted; only a `PL...` value counts.
     */
    private fun JsonObject.firstPlaylistId(): String? {
        var found: String? = null
        fun walk(element: JsonElement) {
            if (found != null) return
            when (element) {
                is JsonObject -> {
                    val id = (element["playlistId"] as? JsonPrimitive)?.contentOrNull
                    if (id != null && id.startsWith("PL")) {
                        found = id
                        return
                    }
                    element.values.forEach(::walk)
                }

                is JsonArray -> element.forEach(::walk)
                else -> Unit
            }
        }
        walk(this)
        return found
    }

    private fun stringArray(values: List<String>): JsonArray = buildJsonArray {
        values.forEach { add(JsonPrimitive(it)) }
    }

    private fun PlaylistPrivacy.wire(): String = when (this) {
        PlaylistPrivacy.PRIVATE -> "PRIVATE"
        PlaylistPrivacy.UNLISTED -> "UNLISTED"
        PlaylistPrivacy.PUBLIC -> "PUBLIC"
    }

    /** `parseCount` is a `Long`; [RemotePlaylist.itemCount] is an `Int`. */
    private fun Long.toCount(): Int = coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

    private companion object {
        /**
         * A YouTube channel id: `UC` plus 22 id characters. The fallback surface
         * has no stable field label to trust, so an id that does not match this
         * shape is rejected rather than added to the set.
         */
        val CHANNEL_ID = Regex("^UC[A-Za-z0-9_-]{22}$")
    }
}

/**
 * Recognises a sign-in wall inside a `2xx` body.
 *
 * A wall is a property of the *body*, not the status: YouTube answers some
 * session-gated reads with `200 OK` plus a renderer that says the session is
 * gone. It always arrives inside the error envelope, so the body must carry an
 * `"error"` key *and* one of the two known wall spellings:
 * - `LOGIN_REQUIRED` — the playability/status label, which also covers the
 *   `"status":"LOGIN_REQUIRED"` form, so a separate quoted match is unnecessary;
 * - `Sign in to confirm` — the bot-challenge wording, which is also a session
 *   problem from sync's point of view (retrying it verbatim cannot help).
 *
 * Requiring the envelope is what stops a video or playlist *title* that merely
 * contains the phrase from aborting an otherwise-valid sync as [OpResult.AuthRequired].
 */
internal fun looksLikeSignInWall(body: String): Boolean {
    if (!body.contains("\"error\"")) return false
    return body.contains("LOGIN_REQUIRED") || body.contains("Sign in to confirm")
}

/**
 * Classifies an HTTP status when there is no throwable to hand to [classify].
 *
 * The bands match [classify] exactly, plus the one case that helper deliberately
 * leaves to the transport: `401`/`403` are [OpResult.AuthRequired], because those
 * are how InnerTube spells a dead session. Everything else follows the shared
 * policy: 429/5xx retry, other 4xx are terminal, and an unexpected code retries
 * rather than risking a stranding a change that might succeed.
 */
internal fun classifyHttpStatus(code: Int): OpResult<Nothing> = when {
    code == 401 || code == 403 -> OpResult.AuthRequired("HTTP $code")
    code == 429 || code in 500..599 -> OpResult.Retryable("HTTP $code")
    code in 400..499 -> OpResult.Terminal("HTTP $code")
    else -> OpResult.Retryable("HTTP $code")
}