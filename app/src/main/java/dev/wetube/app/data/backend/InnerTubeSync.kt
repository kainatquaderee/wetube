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
import kotlinx.serialization.json.intOrNull
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
    /**
     * Where one debug line per read goes, or nothing.
     *
     * A read that answers `2xx` with a body this class cannot interpret is
     * indistinguishable, to the engine, from an account that owns nothing: both
     * are `Ok(emptyList())`. Naming the endpoint and what came back is the only
     * way to tell those apart from a log. It is injected rather than logged here
     * so this file stays free of Android imports and I/O, and defaults to a
     * no-op so a test never has to supply one.
     */
    private val log: (String) -> Unit = {},
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
     * web's `lockupViewModel`, which labels a playlist
     * `contentType: "LOCKUP_CONTENT_TYPE_PLAYLIST"`. The same renderer also
     * serves video rows (a virtualised playlist page is nothing else, §2.3), so a
     * row is classified by its type token rather than by its shape. An empty
     * account is `Ok(emptyList())`.
     *
     * The authoritative source of the WeTube-owned favourites mirror is
     * `AccountSyncState.favouritesPlaylistId`; a playlist is never recognised as
     * owned because of its title.
     *
     * A playlist whose item count reads `0` is either genuinely uncounted or
     * counted in a shape this reader does not know, and the two look identical
     * to the caller. The count is the only change signal the engine has, so
     * every pull names the ids with the counts it read: a `0` beside a playlist
     * that visibly has items is the whole diagnosis, and one debug line per sync
     * is what it costs.
     */
    override suspend fun pullPlaylists(): OpResult<List<RemotePlaylist>> =
        perform("browse", buildJsonObject { put("browseId", "FEplaylist_aggregation") }) { root ->
            val playlists = root.playlistRenderers()
            log(
                "FEplaylist_aggregation counts: " +
                    playlists.joinToString { "${it.playlistId}=${it.itemCount}" },
            )
            playlists
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
     *
     * The web serves the rows themselves in two shapes, and a playlist large
     * enough to be virtualised comes back in the second one; both are read by
     * [playlistMemberships].
     */
    override suspend fun pullPlaylist(playlistId: String, next: String?): OpResult<PlaylistPage> {
        val extra = buildJsonObject {
            if (next != null) put("continuation", next) else put("browseId", "VL$playlistId")
        }
        return perform("browse", extra) { root ->
            val items = root.playlistMemberships()
            val next = root.nextContinuation()
            if (items.isEmpty() && next == null) {
                // A page with no rows and nowhere to continue is either a
                // genuinely empty playlist or one whose rows are in a shape this
                // reader does not know — to the caller they are the same value,
                // so name the keys this body did carry.
                log("${extra.brief()} 2xx and no playlist rows; keys: ${root.keyCensus()}")
            }
            PlaylistPage(items = items, next = next)
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
     * `browse/edit_playlist` with `ACTION_SET_PLAYLIST_NAME` (§3.2). Whether the
     * endpoint accepts a rename action at all is still unverified.
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
     * `browse/edit_playlist` with `ACTION_SET_PLAYLIST_PRIVACY` (§3.2). The
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
     * `browse/edit_playlist` with one `ACTION_ADD_VIDEO` action per id (§3.2).
     * The action list is the batch the write planner already chunks, so a
     * transport call maps one-to-one to a planned op.
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
     * `browse/edit_playlist` with `ACTION_REMOVE_VIDEO_BY_VIDEO_ID` and
     * `removedVideoId` per removal (§2.3, §3.3). The membership's `setVideoId`
     * is deliberately not sent: it is only meaningful to the by-set-video-id
     * action, and an unrecognised member is how a write turns into a `400`.
     * A blank `setVideoId` is still copied into the record verbatim by the
     * puller rather than guessed — the protocol says such an item is pull-only,
     * and the engine is responsible for not queuing it.
     */
    override suspend fun removeFromPlaylist(
        playlistId: String,
        removals: List<RemotePlaylistItem>,
    ): OpResult<Unit> = editPlaylist(
        playlistId,
        buildJsonArray {
            removals.forEach { item ->
                add(buildJsonObject {
                    put("action", "ACTION_REMOVE_VIDEO_BY_VIDEO_ID")
                    put("removedVideoId", item.videoId)
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

        val result = when (reply) {
            is SyncReply.Unreachable -> OpResult.Retryable(reply.reason)
            is SyncReply.Http ->
                if (reply.code in 200..299) successful(reply.body, parse)
                else classifyHttpStatus(reply.code)
        }
        log("$path -> ${result.summary()}")
        return result
    }

    /**
     * What a read returned, in one line: a count where there is one, the reason
     * otherwise. Sizes are what matters — a shape this class cannot read and an
     * account that owns nothing both arrive as `Ok(emptyList())`.
     */
    private fun <T> OpResult<T>.summary(): String = when (this) {
        is OpResult.Ok -> when (val value = value) {
            is Collection<*> -> "${value.size} items"
            is Map<*, *> -> "${value.size} entries"
            else -> value.toString().take(120)
        }

        is OpResult.Retryable -> "retryable: $reason"
        is OpResult.Terminal -> "terminal: $reason"
        is OpResult.AuthRequired -> "auth required: $reason"
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
        if (value is Collection<*> && value.isEmpty()) {
            // An empty account and an unreadable reply are the same value to the
            // caller, so name the keys this body did carry: that is what says
            // whether the renderer shape moved.
            log("${body.length}B 2xx and nothing read; keys: ${root.keyCensus()}")
        }
        return OpResult.Ok(value)
    }

    private suspend fun write(path: String, extra: JsonObject): OpResult<Unit> =
        perform(path, extra) { Unit }

    /**
     * One playlist edit, on the endpoint the web client actually posts to.
     *
     * `playlist/edit` was our guess from the old API and answers `404`; the web
     * player's own playlist edits go to `browse/edit_playlist` (§3.2).
     */
    private suspend fun editPlaylist(playlistId: String, actions: JsonArray): OpResult<Unit> =
        write(
            "browse/edit_playlist",
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

    /** `gridPlaylistRenderer` and playlist `lockupViewModel` entries. */
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
     * A web lockup playlist, or `null` for the video rows built from the same
     * renderer.
     *
     * The title lives under `metadata.lockupMetadataViewModel`; the item count
     * does not (§2.2), and is read off the thumbnail badge by [lockupItemCount].
     * The type is tested for the token `PLAYLIST` rather than for one whole
     * spelling, because the web writes `"LOCKUP_CONTENT_TYPE_PLAYLIST"` while a
     * bare `"PLAYLIST"` is what the older shape used.
     */
    private fun JsonObject.toLockupPlaylist(): RemotePlaylist? {
        if (!str("contentType").orEmpty().contains(PLAYLIST_TYPE_TOKEN, ignoreCase = true)) return null
        val id = str("contentId")?.takeIf { it.isNotBlank() } ?: return null
        val metadata = this["metadata"].obj()?.get("lockupMetadataViewModel").obj()
        return RemotePlaylist(
            playlistId = id,
            title = metadata?.get("title").text().orEmpty(),
            itemCount = lockupItemCount(),
        )
    }

    /**
     * A playlist lockup's item count, read off the badge painted over the
     * thumbnail.
     *
     * The count is not in the metadata text parts (§2.2): those name the
     * channel, the privacy and the "View full playlist" affordance, and never a
     * number of items — which is why a parts-based read returned `0` for every
     * playlist. It is instead the thumbnail badge under `contentImage`, and that
     * badge is told apart from a duration by the `PLAYLISTS` icon it carries
     * ([PLAYLISTS_BADGE_ICON]).
     *
     * A duration badge is never read as a count. A badge without the icon is
     * accepted only when its own text names videos or episodes
     * ([ITEM_COUNT_LABEL]) — the narrow fallback for a badge whose icon this
     * reader has not seen. Anything else reads `0`, which the engine takes as
     * "count unknown" and re-pulls, never as "empty".
     */
    private fun JsonObject.lockupItemCount(): Int {
        val badges = this["contentImage"].obj()?.findAll("thumbnailBadgeViewModel").orEmpty()
        val text = badges
            .firstOrNull { badge ->
                badge.badgeIconNames().any { it.equals(PLAYLISTS_BADGE_ICON, ignoreCase = true) }
            }
            ?.str("text")
            ?: badges.mapNotNull { it.str("text") }.firstOrNull { ITEM_COUNT_LABEL.containsMatchIn(it) }
            ?: return 0
        return parseCount(text).toCount()
    }

    /** The `clientResource` image names on a badge's icon, in written order. */
    private fun JsonObject.badgeIconNames(): List<String> =
        findAll("clientResource").mapNotNull { it.str("imageName") }

    /**
     * Every text part of a lockup's `contentMetadataViewModel`, in the order the
     * web wrote them.
     *
     * The membership reader's first part is the video's channel (§2.3). The
     * playlist reader deliberately does *not* read a count from these parts —
     * they name the channel, the privacy and "View full playlist", never a
     * number (§2.2) — so nothing here shares that mistake.
     */
    private fun JsonObject?.lockupMetadataTexts(): List<String> =
        this?.findAll("contentMetadataViewModel")
            .orEmpty()
            .flatMap { it["metadataRows"].objects() }
            .flatMap { it["metadataParts"].objects() }
            .mapNotNull { it["text"].text() }

    /**
     * The page's memberships, in page order, from both shapes the web serves:
     * the legacy `playlistVideoRenderer`, and the `lockupViewModel` rows a
     * virtualised (large) playlist is returned as (see [toLockupVideoItem]).
     *
     * One walker rather than two lists so the rows keep the order the page wrote
     * them in, whichever mix of shapes it used.
     */
    private fun JsonObject.playlistMemberships(): List<RemotePlaylistItem> {
        val found = ArrayList<RemotePlaylistItem>()
        fun walk(element: JsonElement) {
            when (element) {
                is JsonObject -> element.forEach { (key, value) ->
                    val item = when (key) {
                        "playlistVideoRenderer" -> (value as? JsonObject)?.toRemotePlaylistItem()
                        "lockupViewModel" -> (value as? JsonObject)?.toLockupVideoItem()
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
     * One membership out of a `lockupViewModel` row, or `null` for the playlist
     * lockups built from the same renderer (see [toLockupPlaylist]).
     *
     * A playlist large enough for the web to virtualise its page is served this
     * way instead of as `playlistVideoRenderer`s. Such a row carries **no
     * `setVideoId`**, so everything read here is pull-only and
     * [RemotePlaylistItem.setVideoId] is deliberately left empty rather than
     * filled in with the `videoId`.
     *
     * Each field sits in a different view model:
     * - the video id is the lockup's own `contentId`;
     * - the title and the channel's avatar hang off `metadata.lockupMetadataViewModel`;
     * - the thumbnail is the largest `contentImage.thumbnailViewModel.image` source;
     * - the duration is a thumbnail badge, read through the shared
     *   [parseDurationLabel] so it means here what it means everywhere else;
     * - the channel id is the row's only channel-shaped `browseEndpoint` (the
     *   avatar's own tap target);
     * - the position is the row's `watchEndpoint.index`, which counts from zero
     *   where the legacy renderer's `index` counts from one.
     */
    private fun JsonObject.toLockupVideoItem(): RemotePlaylistItem? {
        if (!str("contentType").orEmpty().contains(VIDEO_TYPE_TOKEN, ignoreCase = true)) return null
        val videoId = str("contentId")?.takeIf { it.isNotBlank() } ?: return null
        val metadata = this["metadata"].obj()?.get("lockupMetadataViewModel").obj()
        val duration = findAll("thumbnailBadgeViewModel")
            .firstNotNullOfOrNull { parseDurationLabel(it["text"].text()).takeIf { seconds -> seconds > 0 } }
        return RemotePlaylistItem(
            videoId = videoId,
            setVideoId = "",
            index = findAll("watchEndpoint")
                .firstNotNullOfOrNull { (it["index"] as? JsonPrimitive)?.intOrNull }
                ?.plus(1)
                ?: 0,
            title = metadata?.get("title").text().orEmpty(),
            channelName = metadata.lockupMetadataTexts().firstOrNull().orEmpty(),
            channelId = findAll("browseEndpoint")
                .firstNotNullOfOrNull { it.str("browseId")?.takeIf(CHANNEL_ID::matches) }
                .orEmpty(),
            thumbnail = this["contentImage"].obj()
                ?.get("thumbnailViewModel").obj()
                ?.get("image").thumbnailUrl().orEmpty(),
            durationSeconds = duration ?: 0,
        )
    }

    /**
     * The next page token, from every shape the renderer tree uses: a
     * `continuationItemRenderer` or `continuationItemViewModel` (whose command
     * `findAll` already finds), the older `nextContinuationData`, or a bare
     * `continuations` entry.
     *
     * The command key is searched rather than taken from the first match,
     * because the web wraps one `continuationCommand` inside another: the outer
     * object holds only `innertubeCommand`, and the token is on the inner one.
     */
    private fun JsonObject.nextContinuation(): String? =
        findAll("continuationCommand")
            .firstNotNullOfOrNull { it.str("token")?.takeIf(String::isNotBlank) }
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

    /**
     * The distinct object keys in a reply, in first-seen order.
     *
     * This exists for one question: a `2xx` this class read nothing from. The
     * answer is always a key name (`lockupViewModel` gained a field, a renderer
     * was renamed), and a census names the shapes that were there instead of
     * dumping a body that can run to hundreds of kilobytes. Bounded on both axes
     * — keys and characters — so a debug line stays a log line.
     */
    private fun JsonObject.keyCensus(limit: Int = 80): String {
        val keys = LinkedHashSet<String>()
        fun walk(element: JsonElement) {
            if (keys.size >= limit) return
            when (element) {
                is JsonObject -> element.forEach { (key, value) ->
                    keys.add(key)
                    walk(value)
                }

                is JsonArray -> element.forEach(::walk)
                else -> Unit
            }
        }
        walk(this)
        return keys.joinToString(" ").take(MAX_CENSUS_CHARS)
    }

    private companion object {
        /** Cap on the key census a debug line prints. */
        const val MAX_CENSUS_CHARS = 2000

        /**
         * A YouTube channel id: `UC` plus 22 id characters. The fallback surface
         * has no stable field label to trust, so an id that does not match this
         * shape is rejected rather than added to the set.
         */
        val CHANNEL_ID = Regex("^UC[A-Za-z0-9_-]{22}$")

        /**
         * The token inside a lockup's `contentType` that marks a playlist. The
         * value is spelled `"LOCKUP_CONTENT_TYPE_PLAYLIST"` today and was a bare
         * `"PLAYLIST"` before, so only the token is trusted.
         */
        const val PLAYLIST_TYPE_TOKEN = "PLAYLIST"

        /**
         * The token inside a lockup's `contentType` that marks one video, i.e.
         * a playlist *membership* rather than a playlist. Bounded to the token
         * for the same reason as [PLAYLIST_TYPE_TOKEN]; the two spells
         * (`"LOCKUP_CONTENT_TYPE_VIDEO"` / `"LOCKUP_CONTENT_TYPE_PLAYLIST"`) do
         * not overlap, so a row is classified by which one it carries.
         */
        const val VIDEO_TYPE_TOKEN = "VIDEO"

        /**
         * The icon the web puts on the badge that counts a playlist's items
         * (§2.2). A duration badge carries `MUSIC` or no icon at all, so the
         * icon is what separates "2 videos" from "41:18" on the same page.
         */
        const val PLAYLISTS_BADGE_ICON = "PLAYLISTS"

        /**
         * A badge whose text names what it counts, e.g. `"12 videos"` /
         * `"36 episodes"`. Only a badge that names a countable thing this way is
         * read as a count when its icon is unknown, so a duration badge
         * (`"41:18"`) is never mistaken for one.
         */
        val ITEM_COUNT_LABEL = Regex("\\b(?:videos?|episodes?)\\b", RegexOption.IGNORE_CASE)
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