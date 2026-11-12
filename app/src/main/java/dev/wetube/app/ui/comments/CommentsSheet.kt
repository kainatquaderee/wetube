package dev.wetube.app.ui.comments

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.SwapVert
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import dev.wetube.app.data.model.Comment
import dev.wetube.app.data.model.CommentPage
import dev.wetube.app.data.model.CommentThreadInfo
import dev.wetube.app.di.ServiceLocator
import dev.wetube.app.ui.compactCount
import dev.wetube.app.ui.friendlyError
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

    var comments by remember(videoId) { mutableStateOf<List<Comment>>(emptyList()) }
    var next by remember(videoId) { mutableStateOf<String?>(null) }
    var loading by remember(videoId) { mutableStateOf(true) }
    var appending by remember(videoId) { mutableStateOf(false) }
    var error by remember(videoId) { mutableStateOf<String?>(null) }
    var newest by remember(videoId) { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }

    val sortable = info.continuation.isNotBlank() && info.newestContinuation.isNotBlank()

    LaunchedEffect(videoId, reload) {
        loading = true
        appending = false
        error = null
        comments = emptyList()
        next = null
        val token = if (newest) info.newestContinuation else info.continuation
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
                comments = page.comments
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
                    val known = comments.mapTo(HashSet()) { it.id to it.text }
                    comments = comments + page.comments.filter { (it.id to it.text) !in known }
                    next = page.next
                }
                .onFailure { error = friendlyError(it) }
            appending = false
        }
    }

    fun openInBrowser() {
        val url = "https://www.youtube.com/watch?v=$videoId"
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        context.startActivity(Intent.createChooser(view, "Open comments"))
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        LazyColumn(
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
                CommentRow(comment)
            }

            if (next != null && !loading) {
                item {
                    LaunchedEffect(next) { loadMore() }
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

@Composable
private fun CommentRow(comment: Comment) {
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
                Icon(
                    Icons.Outlined.ThumbUp,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
                if (comment.likes > 0) {
                    Text(
                        compactCount(comment.likes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (comment.replyCount > 0) {
                    Spacer(Modifier.width(14.dp))
                    Icon(
                        Icons.Outlined.ChatBubbleOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        "${compactCount(comment.replyCount.toLong())} replies",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyComments(
    message: String,
    onRetry: (() -> Unit)?,
    onOpenBrowser: () -> Unit,
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
        Spacer(Modifier.height(10.dp))
        Row {
            if (onRetry != null) {
                TextButton(onClick = onRetry) { Text("Try again") }
            }
            TextButton(onClick = onOpenBrowser) { Text("Open in browser") }
        }
    }
}
