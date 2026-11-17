package dev.wetube.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.wetube.app.data.model.FeedBlock
import dev.wetube.app.data.model.FeedShelf
import dev.wetube.app.data.model.VideoItem

/**
 * Appends [page] to [existing], keeping the order both lists arrived in and
 * dropping anything already shown.
 *
 * A page repeats items across continuations, and a shelf can repeat a video that
 * also appears as a row; appending blindly would duplicate cards and, worse, give
 * two list entries the same Compose key. A shelf that ends up with nothing new is
 * dropped rather than left on the page as an empty row.
 */
internal fun appendFeedPage(existing: List<FeedBlock>, page: List<FeedBlock>): List<FeedBlock> {
    if (page.isEmpty()) return existing
    val seen = existing.flatMap { it.videos }.mapTo(HashSet()) { it.id }
    val appended = ArrayList<FeedBlock>(page.size)
    for (block in page) {
        when (block) {
            is FeedBlock.Video -> if (seen.add(block.video.id)) appended += block
            is FeedBlock.Shelf -> {
                val items = block.shelf.items.filter { seen.add(it.id) }
                if (items.isNotEmpty()) {
                    appended += FeedBlock.Shelf(block.shelf.copy(items = items))
                }
            }
        }
    }
    return if (appended.isEmpty()) existing else existing + appended
}

/**
 * The token to keep for the next page, or `null` to stop paginating.
 *
 * A blank token, or the same token coming back unchanged, would ask for the page
 * that was just read; both end the list instead of looping on it.
 */
internal fun nextFeedToken(next: String?, pageJustRead: String?): String? =
    next?.takeIf { it.isNotBlank() && it != pageJustRead }

/**
 * One shelf of a feed or a result page: its title and a horizontally scrolling
 * row of cards.
 *
 * Shorts shelves keep the app's portrait cards, so a clip looks the way it does
 * in the Shorts tab; every other shelf is a row of ordinary 16:9 videos. Which
 * player a tap opens is a property of the shelf, so that choice lives here rather
 * than being repeated by each screen.
 */
@Composable
internal fun FeedShelfRow(
    shelf: FeedShelf,
    onVideoClick: (VideoItem) -> Unit,
    onShortsClick: (VideoItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp),
    ) {
        val heading = shelf.title.ifBlank { if (shelf.isShorts) "Shorts" else "" }
        if (heading.isNotBlank()) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (shelf.isShorts) {
                    Icon(
                        imageVector = Icons.Default.PlayCircle,
                        contentDescription = null,
                        tint = Color(0xFFFF0033),
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = heading,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(shelf.items, key = { it.id }) { item ->
                FeedShelfCard(
                    video = item,
                    portrait = shelf.isShorts,
                    onClick = {
                        // A clip in a Shorts shelf opens the Shorts player on
                        // that clip, which is what the shelf is for; every other
                        // shelf is a row of ordinary videos.
                        if (shelf.isShorts) onShortsClick(item) else onVideoClick(item)
                    },
                )
            }
        }
    }
}

/** A card inside a shelf, sized for its shelf rather than for the full-width feed. */
@Composable
private fun FeedShelfCard(
    video: VideoItem,
    portrait: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(if (portrait) 148.dp else 220.dp)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(if (portrait) 9f / 16f else 16f / 9f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            AsyncImage(
                model = video.thumbnail,
                contentDescription = video.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (video.live) {
                Badge("LIVE", Color(0xFFFF0033))
            } else if (video.durationSeconds > 0) {
                Badge(formatDuration(video.durationSeconds), Color(0xCC000000), Alignment.BottomEnd)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = video.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val meta = buildList {
            if (video.channelName.isNotBlank()) add(video.channelName)
            if (video.views > 0) add("${compactCount(video.views)} views")
        }.joinToString(" · ")
        if (meta.isNotBlank()) {
            Text(
                text = meta,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}