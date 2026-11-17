package dev.wetube.app.ui.comments

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.wetube.app.data.backend.CommentComposer
import dev.wetube.app.data.backend.CommentReactions
import dev.wetube.app.data.backend.CommentRefusedException
import dev.wetube.app.data.model.Comment
import dev.wetube.app.data.model.CommentPage
import dev.wetube.app.data.model.CommentThreadInfo
import dev.wetube.app.di.ServiceLocator
import dev.wetube.app.ui.compactCount
import dev.wetube.app.ui.friendlyError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The comment section, loaded through the same InnerTube response that powers
 * the video itself. The first page is addressed by a continuation token, so
 * opening the sheet costs no extra watch request; further pages are fetched one
 * at a time as the list reaches its footer.
 *
 * If YouTube refuses the request (comments off, age wall, backend without
 * pagination), the sheet explains that and offers the watch page in the browser.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentsSheet(
    locator: ServiceLocator,
    videoId: String,
    info: CommentThreadInfo,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val listState = rememberLazyListState()

    var comments by remember(videoId) { mutableStateOf<List<Comment>>(emptyList()) }
    var next by remember(videoId) { mutableStateOf<String?>(null) }
    var loading by remember(videoId) { mutableStateOf(true) }
    var appending by remember(videoId) { mutableStateOf(false) }
    var error by remember(videoId) { mutableStateOf<String?>(null) }
    var newest by remember(videoId) { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }

    // The thread whose replies are open, if there is one. Replies take over the
    // sheet rather than opening a second one on top of it, because a reply
    // belongs to the comment above it — and a back arrow that returns to the
    // list says that more plainly than a sheet stacked on a sheet.
    var thread by remember(videoId) { mutableStateOf<Comment?>(null) }

    // Writing needs two things at once, and neither is a given: a transport
    // with a composer (a public feed has none) and a session to attribute the
    // comment to. When either is missing the sheet shows no box at all, which
    // is the honest version of the old read-only rule rather than a box that
    // would fail on its first use.
    var composer by remember(videoId) { mutableStateOf<CommentComposer?>(null) }
    var reactions by remember(videoId) { mutableStateOf<CommentReactions?>(null) }
    var signedIn by remember(videoId) { mutableStateOf(false) }
    var draft by remember(videoId) { mutableStateOf("") }
    var posting by remember(videoId) { mutableStateOf(false) }
    var postError by remember(videoId) { mutableStateOf<String?>(null) }

    LaunchedEffect(videoId) {
        composer = runCatching { locator.backend().commentComposer }.getOrNull()
        reactions = runCatching { locator.backend().commentReactions }.getOrNull()
        signedIn = runCatching { locator.auth.signedIn.first() }.getOrDefault(false)
    }

    val sortable = info.continuation.isNotBlank() && info.newestContinuation.isNotBlank()

    LaunchedEffect(videoId, reload) {
        loading = true
        appending = false
        error = null
        comments = emptyList()
        next = null
        // The token that rides along with the watch response was minted by the
        // anonymous surface, and a page behind it carries counts but no
        // commands: there is nothing for a like to send. With a session the
        // entry point is read from the signed surface instead — one extra
        // `next` call, and the only way the page behind it has something to
        // press.
        val signedEntry = if (runCatching { locator.auth.signedIn.first() }.getOrDefault(false)) {
            runCatching { locator.backend().commentThread(videoId) }.getOrNull()
        } else {
            null
        }
        val entry = signedEntry
            ?.takeIf { it.continuation.isNotBlank() || it.newestContinuation.isNotBlank() }
            ?: info
        val token = if (newest) entry.newestContinuation else entry.continuation
        runCatching {
            if (token.isNotBlank()) {
                locator.backend().commentsPage(token)
            } else {
                // Other backends answer comments by video id and cannot
                // paginate. One page is still better than an empty sheet.
                CommentPage(comments = locator.backend().comments(videoId))
            }
        }
            .onSuccess { page ->
                comments = page.comments.dedupeById()
                next = page.next
            }
            .onFailure { error = friendlyError(it) }
        loading = false
    }

    fun loadMore() {
        val token = next ?: return
        if (appending || loading) return
        appending = true
        error = null
        scope.launch {
            runCatching { locator.backend().commentsPage(token) }
                .onSuccess { page ->
                    comments = (comments + page.comments).dedupeById()
                    next = page.next
                }
                .onFailure { error = friendlyError(it) }
            appending = false
        }
    }

    // Fetch the next page once the footer is actually on screen. The previous
    // implementation launched from inside the sentinel item, so every page
    // that scrolled into view fetched another one whether or not it was seen.
    val footerVisible by remember {
        derivedStateOf {
            val layout = listState.layoutInfo
            val lastVisible = layout.visibleItemsInfo.lastOrNull()?.index
            lastVisible != null && lastVisible >= layout.totalItemsCount - 1
        }
    }
    LaunchedEffect(next, footerVisible) {
        if (next != null && footerVisible) loadMore()
    }

    fun openInBrowser() {
        val url = "https://www.youtube.com/watch?v=$videoId"
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        context.startActivity(Intent.createChooser(view, "Open comments"))
    }

    /**
     * Posts the draft, then reloads the thread.
     *
     * The reload is deliberate. YouTube decides where a new comment lands in
     * the sort and whether it counts yet, and a comment the app invented from
     * its own text would be the one wrong row this feature could not recover
     * from — so nothing is inserted locally, not even briefly.
     */
    fun postDraft() {
        val target = composer ?: return
        val text = draft.trim()
        if (text.isEmpty() || posting) return
        scope.launch {
            posting = true
            postError = null
            try {
                target.post(videoId, text)
                draft = ""
                reload++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (refused: CommentRefusedException) {
                // YouTube's own sentence: a rate limit, a comment held for
                // review and a closed section are three different answers, and
                // only the server knows which one just happened.
                postError = refused.message
            } catch (failure: Exception) {
                postError = friendlyError(failure)
            } finally {
                posting = false
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        // The sheet is one screen or the other: the thread list, or the replies
        // of the comment that was opened. A back arrow leaves the pane, so the
        // list is still behind it.
        val openThread = thread
        if (openThread != null) {
            RepliesPane(
                locator = locator,
                parent = openThread,
                reactions = reactions,
                onBack = { thread = null },
            )
            return@ModalBottomSheet
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Comments", style = MaterialTheme.typography.titleMedium)
                        if (info.countText.isNotBlank()) {
                            Text(
                                "${info.countText} comments",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (sortable) {
                        TextButton(onClick = {
                            newest = !newest
                            reload++
                        }) {
                            Icon(
                                Icons.Default.SwapVert,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(if (newest) "Newest" else "Top")
                        }
                    }
                    IconButton(onClick = { openInBrowser() }) {
                        Icon(
                            Icons.Default.OpenInBrowser,
                            contentDescription = "Open in browser",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HorizontalDivider()
            }

            // The box sits above the thread, where the site puts it, and only
            // when there is something to write with and someone to write as.
            if (composer != null && signedIn) {
                item {
                    CommentComposerRow(
                        draft = draft,
                        posting = posting,
                        error = postError,
                        onDraftChange = { draft = it.take(MAX_COMMENT_LENGTH) },
                        onPost = { postDraft() },
                    )
                }
            }

            if (loading) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(30.dp), strokeWidth = 2.dp)
                    }
                }
            }

            if (!loading && comments.isEmpty()) {
                item {
                    EmptyComments(
                        message = error
                            ?: if (info.countText.isBlank()) {
                                "Comments are turned off for this video."
                            } else {
                                "No comments yet."
                            },
                        onRetry = if (error != null) {
                            { reload++ }
                        } else {
                            null
                        },
                        onOpenBrowser = { openInBrowser() },
                    )
                }
            }

            itemsIndexed(
                comments,
                key = { index, comment -> comment.id.ifBlank { "row-$index" } },
            ) { _, comment ->
                CommentRow(
                    comment = comment,
                    reactions = reactions,
                    // Only a comment the server gave a token for can open a
                    // thread; the count alone is then just a count.
                    onOpenReplies = comment.replies
                        ?.token
                        ?.takeIf { it.isNotBlank() }
                        ?.let { { thread = comment } },
                )
            }

            if (next != null && !loading) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 18.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (appending) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                    }
                }
            }

            if (!loading && error != null && comments.isNotEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                    ) {
                        Text(
                            error.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(onClick = { loadMore() }) { Text("Try again") }
                    }
                }
            }
        }
    }
}

/**
 * Keeps the first occurrence of each non-blank comment id.
 *
 * The same comment can be offered again by a later continuation token (or by
 * a backend that merges "top comments" into the first page). Comments without
 * an id cannot be compared, so they are all kept and fall back to the index
 * key used by the list.
 */
private fun List<Comment>.dedupeById(): List<Comment> {
    val seen = HashSet<String>()
    return filter { comment -> comment.id.isBlank() || seen.add(comment.id) }
}

/**
 * YouTube's own ceiling for one comment, so the field cannot promise more than
 * the server will take. Counted in characters, as the site counts it.
 */
private const val MAX_COMMENT_LENGTH = 10_000

/**
 * The comment box.
 *
 * It is deliberately plain: the draft, one button, and whatever YouTube said
 * last time. There is no optimistic entry in the thread below, because the
 * server decides a new comment's place in the sort — the reload is the receipt.
 */
@Composable
private fun CommentComposerRow(
    draft: String,
    posting: Boolean,
    error: String?,
    onDraftChange: (String) -> Unit,
    onPost: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Add a comment…") },
                enabled = !posting,
                maxLines = 4,
                textStyle = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = onPost,
                enabled = !posting && draft.isNotBlank(),
            ) {
                if (posting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("Post")
                }
            }
        }
        if (error != null) {
            Text(
                error,
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun CommentRow(
    comment: Comment,
    reactions: CommentReactions?,
    onOpenReplies: (() -> Unit)? = null,
    replyParams: String? = null,
    replyPrefix: String = "",
    onReplyPosted: (() -> Unit)? = null,
) {
    // The row owns the one thing it can change. A tap shows at once, because a
    // like that waits for a round trip reads as a broken button, and it is put
    // back if YouTube refuses: the refusal is the only answer that outranks
    // what the viewer just saw. Nothing is kept beyond the row — the next
    // reload carries the server's own count, which is the receipt.
    var liked by remember(comment.id, comment.liked) { mutableStateOf(comment.liked) }
    var likes by remember(comment.id, comment.likes) { mutableStateOf(comment.likes) }
    var pending by remember(comment.id) { mutableStateOf(false) }
    var failure by remember(comment.id) { mutableStateOf<String?>(null) }
    var replying by remember(comment.id) { mutableStateOf(false) }
    var replyDraft by remember(comment.id) { mutableStateOf("") }
    var sendingReply by remember(comment.id) { mutableStateOf(false) }
    var replied by remember(comment.id) { mutableStateOf(false) }
    var replyCount by remember(comment.id, comment.replyCount) { mutableStateOf(comment.replyCount) }
    val scope = rememberCoroutineScope()
    val likeable = reactions != null && comment.actions.canLike
    val toggleLike = {
        val action = if (liked) comment.actions.unlike else comment.actions.like
        if (action != null && !pending) {
            val wasLiked = liked
            val wasLikes = likes
            liked = !liked
            likes = if (liked) likes + 1 else (likes - 1).coerceAtLeast(0)
            pending = true
            failure = null
            scope.launch {
                try {
                    reactions?.like(action)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    liked = wasLiked
                    likes = wasLikes
                    failure = error.message ?: "YouTube did not take the like."
                }
                pending = false
            }
        }
    }
    // A reply is not shown in the thread below, because a reply belongs to its
    // own thread and this sheet lists top-level comments. The confirmation is
    // therefore the count going up by one, and the next reload decides the
    // rest — the same "reload is the receipt" rule the composer follows.
    //
    // [replyParams] is the caller's way of saying where a reply lands. Inside a
    // thread it is the top-level comment's own parameter, so the answer stays
    // in this thread and a reply is still replyable even when its own surface
    // carries nothing to send.
    val sendReply = {
        val params = replyParams ?: comment.actions.reply
        if (params != null && !sendingReply && replyDraft.isNotBlank()) {
            sendingReply = true
            failure = null
            scope.launch {
                try {
                    reactions?.reply(params, replyDraft)
                    replyDraft = ""
                    replying = false
                    replyCount += 1
                    replied = true
                    onReplyPosted?.invoke()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    failure = error.message ?: "YouTube did not take the reply."
                }
                sendingReply = false
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        AsyncImage(
            model = comment.avatar,
            contentDescription = null,
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    comment.author,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (comment.publishedText.isNotBlank()) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        comment.publishedText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(
                comment.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(end = 8.dp),
            )
            Spacer(Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable(enabled = likeable && !pending, onClick = toggleLike)
                        .padding(4.dp),
                ) {
                    Icon(
                        if (liked) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
                        contentDescription = if (liked) "Remove like" else "Like",
                        tint = if (liked) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(14.dp),
                    )
                }
                if (likes > 0) {
                    Text(
                        compactCount(likes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val replyable = reactions != null && (replyParams != null || comment.actions.canReply)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable(enabled = replyable && !sendingReply) {
                            val opening = !replying
                            replying = opening
                            failure = null
                            // Answering a reply is only a mention: the new
                            // comment joins the same thread, so the box opens
                            // with the handle already in it.
                            if (opening && replyDraft.isBlank()) replyDraft = replyPrefix
                        }
                        .padding(4.dp),
                ) {
                    Icon(
                        Icons.Outlined.ChatBubbleOutline,
                        contentDescription = if (replyable) "Reply" else null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                }
                // The count doubles as the way into the thread, when the server
                // gave a token for it. Its own wording is preferred over a count
                // built here, so the row and the page it opens cannot disagree.
                val threadLabel = comment.replies?.label?.takeIf { it.isNotBlank() }
                    ?: when {
                        replyCount == 1 -> "1 reply"
                        replyCount > 1 -> "${compactCount(replyCount.toLong())} replies"
                        else -> ""
                    }
                if (threadLabel.isNotBlank() || onOpenReplies != null) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .then(
                                if (onOpenReplies != null) {
                                    Modifier.clickable(onClick = onOpenReplies)
                                } else {
                                    Modifier
                                },
                            )
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            threadLabel.ifBlank { "Replies" },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (onOpenReplies != null) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        if (onOpenReplies != null) {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = "Open replies",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }
            if (replying) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    OutlinedTextField(
                        value = replyDraft,
                        onValueChange = { replyDraft = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Add a reply...") },
                        enabled = !sendingReply,
                        maxLines = 4,
                        textStyle = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = { sendReply() },
                        enabled = !sendingReply && replyDraft.isNotBlank(),
                    ) {
                        if (sendingReply) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp))
                        } else {
                            Text("Reply")
                        }
                    }
                }
            }
            failure?.let { message ->
                Spacer(Modifier.height(4.dp))
                Text(
                    message,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (replied) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Reply posted.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * The replies of one comment, shown in place of the thread list.
 *
 * YouTube threads only one level deep: a reply is never a thread of its own. So
 * this pane is flat — the comment whose replies these are stays on top for
 * context, the replies follow in the order the server lists them, and a footer
 * pages the rest in. Replying names the parent's own reply parameter, which is
 * what keeps a new answer inside this thread; answering a reply adds that
 * author's handle to the front of the text instead of nesting any deeper.
 */
@Composable
private fun RepliesPane(
    locator: ServiceLocator,
    parent: Comment,
    reactions: CommentReactions?,
    onBack: () -> Unit,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val token = parent.replies?.token.orEmpty()

    var replies by remember(parent.id) { mutableStateOf<List<Comment>>(emptyList()) }
    var next by remember(parent.id) { mutableStateOf<String?>(null) }
    var loading by remember(parent.id) { mutableStateOf(true) }
    var appending by remember(parent.id) { mutableStateOf(false) }
    var error by remember(parent.id) { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(token, reload) {
        loading = true
        appending = false
        error = null
        replies = emptyList()
        next = null
        if (token.isNotBlank()) {
            runCatching { locator.backend().commentsPage(token) }
                .onSuccess { page ->
                    replies = page.comments.dedupeById()
                    next = page.next
                }
                .onFailure { error = friendlyError(it) }
        }
        loading = false
    }

    fun loadMore() {
        val more = next ?: return
        if (appending || loading) return
        appending = true
        error = null
        scope.launch {
            runCatching { locator.backend().commentsPage(more) }
                .onSuccess { page ->
                    replies = (replies + page.comments).dedupeById()
                    next = page.next
                }
                .onFailure { error = friendlyError(it) }
            appending = false
        }
    }

    val footerVisible by remember {
        derivedStateOf {
            val layout = listState.layoutInfo
            val lastVisible = layout.visibleItemsInfo.lastOrNull()?.index
            lastVisible != null && lastVisible >= layout.totalItemsCount - 1
        }
    }
    LaunchedEffect(next, footerVisible) {
        if (next != null && footerVisible) loadMore()
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.92f),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item(key = "replies-header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text("Replies", style = MaterialTheme.typography.titleMedium)
                    val label = parent.replies?.label.orEmpty()
                    if (label.isNotBlank()) {
                        Text(
                            label,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            HorizontalDivider()
        }

        // The comment itself stays on top so the rows below read as answers to
        // something. Its own reply button is how a reply is written here.
        item(key = "replies-parent") {
            CommentRow(
                comment = parent,
                reactions = reactions,
                onReplyPosted = { reload++ },
            )
            HorizontalDivider()
        }

        if (loading) {
            item(key = "replies-loading") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(30.dp), strokeWidth = 2.dp)
                }
            }
        }

        if (!loading && replies.isEmpty()) {
            item(key = "replies-empty") {
                EmptyComments(
                    message = error ?: "No replies yet.",
                    onRetry = if (error != null) { { reload++ } } else null,
                    onOpenBrowser = null,
                )
            }
        }

        itemsIndexed(
            replies,
            key = { index, reply -> reply.id.ifBlank { "reply-$index" } },
        ) { _, reply ->
            CommentRow(
                comment = reply,
                reactions = reactions,
                replyParams = parent.actions.reply,
                replyPrefix = mention(reply.author),
                onReplyPosted = { reload++ },
            )
        }

        if (next != null && !loading) {
            item(key = "replies-footer") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 18.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (appending) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                }
            }
        }

        if (!loading && error != null && replies.isNotEmpty()) {
            item(key = "replies-error") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                ) {
                    Text(
                        error.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = { loadMore() }) { Text("Try again") }
                }
            }
        }
    }
}

/**
 * The handle a reply inside a thread opens with.
 *
 * YouTube's own display names already carry the `@` (`@LunaFreya-q6c`), and a
 * mention takes exactly one, so a name that arrived bare still gets one and a
 * name that arrived with two still ends up with one.
 */
private fun mention(author: String): String =
    if (author.isBlank()) "" else "@${author.trimStart('@').trim()} "

@Composable
private fun EmptyComments(
    message: String,
    onRetry: (() -> Unit)?,
    onOpenBrowser: (() -> Unit)?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.ChatBubbleOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (onRetry != null || onOpenBrowser != null) {
            Spacer(Modifier.height(10.dp))
            Row {
                if (onRetry != null) {
                    TextButton(onClick = onRetry) { Text("Try again") }
                }
                if (onOpenBrowser != null) {
                    TextButton(onClick = onOpenBrowser) { Text("Open in browser") }
                }
            }
        }
    }
}
