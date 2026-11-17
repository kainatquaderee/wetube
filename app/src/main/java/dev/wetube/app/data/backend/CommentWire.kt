package dev.wetube.app.data.backend

import dev.wetube.app.core.parseCount
import dev.wetube.app.data.model.Comment
import dev.wetube.app.data.model.CommentActions
import dev.wetube.app.data.model.CommentPage
import dev.wetube.app.data.model.CommentReplies
import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * The opaque parameter a `comment/create_comment` request has to carry.
 *
 * YouTube will not take the video id on its own — the endpoint answers a bare
 * id with an argument error, because the parameter is a protobuf message that
 * names the video *and* the surface the comment comes from. Its three fields
 * are: the video id (field 2), a nested `params` holding the composer index
 * zero (field 5, whose only member is field 1), and the literal `7` (field 10)
 * that the web composer sends for a top-level comment. The message is then
 * base64-encoded and escaped the way `encodeURIComponent` escapes it, since it
 * travels as a JSON string that YouTube's own page percent-encodes.
 *
 * The field numbers are not guesswork: they are what the maintained clients
 * encode (YouTube.js `CreateCommentParams` in `protos/misc/params.proto`), and
 * they cannot drift without breaking YouTube's own site. A token minted by the
 * server would still be the better token — the signed-in page gets one on its
 * composer's submit button — but the reading path here never asks for that
 * button, so this is what we send.
 */
internal fun createCommentParams(videoId: String): String {
    val id = videoId.encodeToByteArray()
    val message = ByteArrayOutputStream().apply {
        write(0x12) // field 2, length-delimited: videoId
        writeVarint(id.size)
        write(id)
        write(0x2A) // field 5, length-delimited: params
        write(0x02) // its payload is exactly two bytes
        write(0x08) // field 1, varint: index
        write(0x00) // index = 0
        write(0x50) // field 10, varint: number
        write(0x07) // number = 7
    }.toByteArray()
    return percentEncode(Base64.getEncoder().encodeToString(message))
}

/** One protobuf base-128 varint, which is all a length under 128 needs to be. */
private fun ByteArrayOutputStream.writeVarint(value: Int) {
    var rest = value
    while (rest >= 0x80) {
        write((rest and 0x7F) or 0x80)
        rest = rest ushr 7
    }
    write(rest)
}

/**
 * The characters `encodeURIComponent` leaves alone.
 *
 * Written out rather than borrowed from `URLEncoder`, which is form encoding:
 * it turns a space into `+` and leaves `*` unescaped, and neither is what a
 * base64 payload in a JSON string needs. Base64's `+`, `/` and `=` are all
 * escaped by this set, which is exactly the difference that matters.
 */
private const val UNRESERVED =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*'()"

internal fun percentEncode(value: String): String = buildString(value.length) {
    for (c in value) {
        if (c in UNRESERVED) {
            append(c)
        } else {
            append('%')
            append("%02X".format(c.code))
        }
    }
}

/**
 * What YouTube did with a write, read from the answer body.
 *
 * The status code is not the answer: a refusal comes back `200` with the
 * explanation in the body, the same way a sign-in wall does (see
 * `docs/SYNC_PROTOCOL.md`). What says an accepted write was accepted is the
 * server's own verdict, and the three writes state it in two places: creating a
 * comment answers with a `createCommentAction`, while a like — which creates
 * nothing — answers with `actionResults[].status` of `STATUS_SUCCEEDED`, and the
 * reply endpoint declares its new comment as a `createCommentReplyAction`
 * beside a singular `actionResult`. [Refused] keeps YouTube's own sentence so
 * the sheet can show it verbatim instead of guessing at a cause.
 *
 * A `200` with no verdict and no refusal is read as a refusal, not a success.
 * The cost of being wrong is asymmetric: a comment is public and a cleared box
 * invites a retry, so the box keeps its text until something comes back, and a
 * false "it worked" would be the only outcome the viewer could not undo. For a
 * like the same rule only costs a row that keeps showing the old state, which
 * the next reload corrects.
 */
internal sealed interface CommentPost {

    /** The server created the comment. */
    data object Posted : CommentPost

    /** The server declined, and [reason] is its own sentence for why. */
    data class Refused(val reason: String) : CommentPost
}

internal fun commentPostResult(json: JsonObject): CommentPost {
    val action = json.findAll("createCommentAction").firstOrNull()
    if (action != null && action["error"] == null) return CommentPost.Posted

    // The refusal sentence keeps the same company in every shape the server
    // uses: beside an action when it declined the action, under a
    // `messageRenderer` when it answered with a reason to display, and under a
    // top-level `error` when it declined the request outright.
    //
    // The middle shape is not hypothetical: a signed-out `create_comment`
    // answers `200` with `actions[0].showErrorAction.errorMessage` and the
    // sentence "Comment failed to post.". It is looked up across the whole body
    // rather than inside a `createCommentAction`, because on a refusal there is
    // no such action to look inside — the error action stands in its place.
    val reason = action?.get("error").text()
        ?: json.findObject("messageRenderer")?.get("text").text()
        ?: json["error"].obj()?.get("message").text()
        ?: json["error"].text()

    // A like creates no comment, so the action above can never appear for it,
    // and the reply endpoint names its new comment `createCommentReplyAction`.
    // Both would read as refusals on that alone. The verdict is the one field
    // every accepted write carries, so it is what decides the rest.
    if (reason.isNullOrBlank() && json.writeVerdict() == WRITE_SUCCEEDED) {
        return CommentPost.Posted
    }
    return CommentPost.Refused(
        reason?.takeIf { it.isNotBlank() } ?: "YouTube did not accept the comment.",
    )
}

/**
 * The verdict the server states on a write, wherever it states it.
 *
 * A creation states it once, as `actionResult`; a like states it as a list,
 * `actionResults`, because that endpoint can carry several actions at once. The
 * field inside is the same either way, and reading it is what lets one rule
 * cover writes that otherwise have nothing in common.
 */
private fun JsonObject.writeVerdict(): String? =
    findAll("actionResult").firstOrNull()?.get("status").text()
        ?: findArray("actionResults")
            ?.objects()
            ?.firstNotNullOfOrNull { it.get("status").text() }

/**
 * The newer comment shape, read alongside the older one.
 *
 * A thread used to arrive whole: one `commentThreadRenderer` held a
 * `commentRenderer` with the author, the text, the counts and the buttons in
 * it. On the signed WEB surface the same thread is split into three tables — a
 * view model naming a `commentKey`, a `commentEntityPayload` under that key
 * with the comment itself, and an `engagementToolbarStateEntityPayload` under a
 * separate state key with whether this account liked it. The reading code joins
 * them back together so nothing above this file has to know which shape it
 * was handed.
 */
private const val LIKE_STATE_LIKED = "TOOLBAR_LIKE_STATE_LIKED"

/** The status YouTube puts on a write it accepted. */
private const val WRITE_SUCCEEDED = "STATUS_SUCCEEDED"

/** The comments of a split page, keyed by the entity key their thread names. */
internal fun commentEntities(json: JsonObject): Map<String, JsonObject> =
    json.findAll("commentEntityPayload").mapNotNull { entity ->
        entity.str("key")?.takeIf { it.isNotBlank() }?.let { it to entity }
    }.toMap()

/**
 * Whether this account liked each comment, keyed by the state key the entity
 * points at.
 *
 * YouTube has a dislike state in the same vocabulary, but no surface shows a
 * comment's dislikes, so only the liked state is read. A state that is absent
 * from this map was never described, and reads as "not liked".
 */
internal fun commentLikedStates(json: JsonObject): Map<String, Boolean> =
    json.findAll("engagementToolbarStateEntityPayload").mapNotNull { state ->
        state.str("key")?.takeIf { it.isNotBlank() }
            ?.let { it to (state.str("likeState") == LIKE_STATE_LIKED) }
    }.toMap()

/** The entity key a thread's view model points at. */
internal fun JsonObject.commentEntityKey(): String? = findString("commentKey")

/** The toolbar state key a comment entity points at. */
internal fun JsonObject.commentStateKey(): String? =
    this["properties"].obj()?.str("toolbarStateKey")

/**
 * The toolbar surface key the thread's view model points at, where the
 * commands behind the comment's buttons live.
 */
internal fun JsonObject.commentToolbarSurfaceKey(): String? = findString("toolbarSurfaceKey")

/**
 * One comment from its entity.
 *
 * [liked] is passed in rather than read here because it lives in the state
 * table, and the count to show depends on it: YouTube sends both numbers, and
 * which one is current is exactly what the state says.
 */
internal fun JsonObject.entityComment(
    liked: Boolean,
    actions: CommentActions = CommentActions(),
    replies: CommentReplies? = null,
): Comment? {
    val properties = this["properties"].obj() ?: return null
    // The text sits one level down in a wrapper that also carries the runs the
    // app does not need: `content` holds the string and `commandRuns` the
    // timestamps and links inside it. A plain string is still accepted, since
    // that is how the older renderers said the same thing.
    val text = (properties["content"].obj()?.get("content") ?: properties["content"])
        .text()
        ?.trim()
        .orEmpty()
    if (text.isBlank()) return null

    val author = this["author"].obj()
    val toolbar = this["toolbar"].obj()
    val count = toolbar?.str(if (liked) "likeCountLiked" else "likeCountNotliked").orEmpty()

    return Comment(
        author = author?.str("displayName").orEmpty(),
        avatar = author?.str("avatarThumbnailUrl").orEmpty(),
        text = text,
        likes = parseCount(count),
        publishedText = properties.str("publishedTime").orEmpty(),
        replyCount = parseCount(toolbar?.str("replyCount").orEmpty()).toInt(),
        id = properties.str("commentId").orEmpty(),
        liked = liked,
        own = author.flag("isCurrentUser"),
        actions = actions,
        replies = replies,
    )
}

/**
 * The commands behind each comment's like, unlike and reply, keyed by the
 * toolbar surface entity the thread's view model points at.
 *
 * They are not on the comment's own entity: that one carries the counts and
 * the labels, while the parameters sit in a separate toolbar surface entity,
 * one per comment, each named by the `toolbarSurfaceKey` of the thread it
 * belongs to. A signed-out answer carries none of them, which is an empty set
 * rather than a faked one — see [CommentActions].
 */
internal fun commentSurfaceActions(json: JsonObject): Map<String, CommentActions> =
    json.findAll("engagementToolbarSurfaceEntityPayload")
        .mapNotNull { surface ->
            surface.str("key")
                ?.takeIf { it.isNotBlank() }
                ?.let { it to surface.surfaceActions() }
        }
        .toMap()

/** The commands one toolbar surface entity carries. */
private fun JsonObject.surfaceActions(): CommentActions = CommentActions(
    like = commandParam("likeCommand", "performCommentActionEndpoint", "action"),
    unlike = commandParam("unlikeCommand", "performCommentActionEndpoint", "action"),
    reply = commandParam("replyCommand", "createCommentReplyEndpoint", "createReplyParams"),
)

/**
 * The parameter inside one of a toolbar's commands.
 *
 * Every command wraps the endpoint that will consume its parameter, and the
 * two are shaped alike but not identically: a like and an unlike wrap a
 * `performCommentActionEndpoint` whose parameter is called `action`, while a
 * reply opens a dialog whose submit button wraps a `createCommentReplyEndpoint`
 * whose parameter is called `createReplyParams` and is answered by its own api
 * path. The endpoint is named rather than searched for as a whole, because a
 * reply dialog also carries the *composer's* `createCommentParams` — the one
 * that posts a new top-level comment — and taking that for the reply's would
 * post the reply to the video instead of to the comment.
 */
private fun JsonObject?.commandParam(command: String, endpoint: String, field: String): String? =
    (this?.get(command) as? JsonObject)
        ?.findObject(endpoint)
        ?.str(field)

/**
 * A page in whichever shape the surface answered in.
 *
 * [legacyComment] reads a whole `commentRenderer`, which the native profile
 * still sends; it is passed in rather than written here because it belongs to
 * the transport that receives it. The split shape is joined by the keys its two
 * tables share: a comment names its entity, and the entity names the toolbar
 * state that says whether this account liked the comment.
 *
 * The split shape is laid out two ways. A first page lists whole threads, each
 * item wrapping a `commentThreadRenderer`; a replies page lists bare view
 * models, because a thread has no threads inside it. [pageItems] finds the
 * items either way, so this reader does not care which page it was handed —
 * only the thread's own reply pointer, which a bare item never carries.
 */
internal fun JsonObject.commentPageFrom(legacyComment: (JsonObject) -> Comment?): CommentPage {
    val entities = commentEntities(this)
    val comments = if (entities.isEmpty()) {
        findAll("commentThreadRenderer").mapNotNull(legacyComment)
    } else {
        val liked = commentLikedStates(this)
        val actions = commentSurfaceActions(this)
        pageItems()?.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val entity = entities[item.commentEntityKey().orEmpty()] ?: return@mapNotNull null
            entity.entityComment(
                liked = liked[entity.commentStateKey()] == true,
                actions = actions[item.commentToolbarSurfaceKey()] ?: CommentActions(),
                replies = item.replies(),
            )
        }.orEmpty()
    }
    return CommentPage(comments = comments, next = nextCommentToken())
}

/** The first non-blank string stored under [key] anywhere below this element. */
internal fun JsonElement.findString(key: String): String? {
    when (this) {
        is JsonObject -> {
            this[key]?.text()?.takeIf { it.isNotBlank() }?.let { return it }
            values.forEach { child -> child.findString(key)?.let { return it } }
        }
        is JsonArray -> forEach { child -> child.findString(key)?.let { return it } }
        else -> Unit
    }
    return null
}

/**
 * The token for the next page of comments.
 *
 * The page's own continuation is the last item of the same list that holds the
 * threads; the continuations *inside* a thread page that thread's replies
 * instead. Picking the page one by position is therefore the whole trick: a
 * search for the first token on the page would hand back a reply page and drop
 * the viewer into someone else's replies.
 */
internal fun JsonObject.nextCommentToken(): String? {
    val tail = pageItems()?.lastOrNull()?.jsonObject
    return tail?.get("continuationItemRenderer")?.findString("token")
        ?: findAll("nextContinuationData").firstOrNull()?.str("continuation")
}

/**
 * The item list this page's comments are in.
 *
 * A first page wraps every comment in a `commentThreadRenderer`, because its
 * rows are threads; a replies page lists bare view models, because a thread
 * holds replies rather than threads. Both lists end with the page's own
 * continuation, so finding the list is what makes [nextCommentToken] able to
 * read it by position. Threads are preferred, since a first page can only ever
 * carry them and asking for them first keeps a replies page from being mistaken
 * for one.
 */
private fun JsonObject.pageItems(): JsonArray? {
    var threads: JsonArray? = null
    var loose: JsonArray? = null

    fun walk(element: JsonElement) {
        if (threads != null) return
        when (element) {
            is JsonArray -> when {
                element.any { it.threadHolder() != null } -> threads = element
                element.any { it.holdsLooseComment() } -> loose = loose ?: element
                else -> element.forEach(::walk)
            }
            is JsonObject -> element.values.forEach(::walk)
            else -> Unit
        }
    }

    walk(this)
    return threads ?: loose
}

/** The thread inside one list item, when that item is a thread. */
private fun JsonElement.threadHolder(): JsonObject? =
    (this as? JsonObject)?.get("commentThreadRenderer") as? JsonObject

/** Whether one list item is a bare comment view model, as a replies page sends. */
private fun JsonElement.holdsLooseComment(): Boolean {
    val viewModel = (this as? JsonObject)?.get("commentViewModel") as? JsonObject ?: return false
    return viewModel.findString("commentKey") != null &&
        viewModel.findString("toolbarSurfaceKey") != null
}

/**
 * The pointer to this comment's replies, when it has any.
 *
 * The thread renderer carries a `commentRepliesRenderer` whose only contents are
 * a continuation token and a label. The label is read from the collapsed button,
 * because that is the state the row shows before it is pressed; it and the
 * expanded button say the same thing. A comment without replies has no renderer
 * at all, and a bare view model — a reply inside a thread — never has one, which
 * is what keeps threading one level deep.
 */
private fun JsonObject.replies(): CommentReplies? {
    val renderer = findObject("commentRepliesRenderer") ?: return null
    val token = (renderer["contents"] as? JsonArray)
        ?.asReversed()
        ?.firstNotNullOfOrNull { item ->
            (item as? JsonObject)?.get("continuationItemRenderer")?.findString("token")
        }
        ?: renderer.findString("token")
        ?: return null
    val label = renderer["viewReplies"]?.findString("text")
        ?: renderer["hideReplies"]?.findString("text")
    return CommentReplies(token = token, label = label.orEmpty())
}

private fun JsonObject?.flag(key: String): Boolean =
    (this?.get(key) as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: false

/**
 * The body of a like or an unlike: the parameter the toolbar handed out, sent
 * back unchanged in the list `perform_comment_action` reads.
 *
 * The parameter travels as one member of an `actions` array rather than under a
 * bare `action` name. A single string is answered with `INVALID_ARGUMENT`,
 * because the endpoint is written to replay a batch of comment actions and the
 * array is what it decodes; every client that speaks this endpoint wraps the
 * one parameter it has, including YouTube.js, whose request builder rewrites
 * `action` into `actions` before the call is sent.
 */
internal fun commentLikeBody(action: String): JsonObject = buildJsonObject {
    put("actions", buildJsonArray { add(JsonPrimitive(action)) })
}

/**
 * The body of a reply.
 *
 * A reply does not reuse the composer's call. It has its own endpoint
 * (`comment/create_comment_reply`) and its own parameter,
 * `createReplyParams`, which names the video *and* the comment being answered,
 * so YouTube places the new comment inside that thread rather than beside it.
 * The parameter is the one the reply dialog's submit button was handed, sent
 * back unchanged, exactly as a like sends its own.
 */
internal fun commentReplyBody(params: String, text: String): JsonObject = buildJsonObject {
    put("commentText", text)
    put("createReplyParams", params)
}