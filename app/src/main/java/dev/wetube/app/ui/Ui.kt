package dev.wetube.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.wetube.app.data.model.VideoItem

fun compactCount(n: Long): String = when {
    n >= 1_000_000_000 -> "%.1fB".format(n / 1_000_000_000.0)
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 1_000 -> "%.1fK".format(n / 1_000.0)
    else -> n.toString()
}

/** 1.5 -> "1.5×", 2.0 -> "2×", matching the labels YouTube uses. */
fun formatSpeed(speed: Float): String {
    val value = if (speed == speed.toInt().toFloat()) {
        speed.toInt().toString()
    } else {
        String.format(java.util.Locale.US, "%.2f", speed).trimEnd('0').trimEnd('.')
    }
    return "$value×"
}

fun formatDuration(totalSeconds: Int): String {
    if (totalSeconds <= 0) return ""
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun friendlyError(t: Throwable): String {
    val raw = t.message.orEmpty()
    return when {
        // YouTube answers 200 with this reason instead of streams when it wants
        // an attestation token. It is per video and often clears by itself.
        raw.contains("confirm your age", ignoreCase = true) ||
            raw.contains("inappropriate for some users", ignoreCase = true) ->
            "This video is age-restricted. YouTube only streams it to a browser session that " +
                "can pass its JavaScript challenge; being signed in is not enough, and WeTube " +
                "does not fake that challenge. Everything else plays normally."
        raw.contains("not a bot", ignoreCase = true) ->
            "YouTube asked this network to sign in before playing this video. " +
                "It usually clears in a few minutes; try again, pick another video, " +
                "or switch to Invidious in Settings."
        raw.contains("HTTP 404") -> "Not found on this instance."
        raw.contains("HTTP 5") -> "The instance is having trouble. Try another one in Settings."
        raw.contains("Unable to resolve host") || raw.contains("UnknownHost") ->
            "Can't reach the instance. Check your connection or change it in Settings."
        raw.contains("timeout", ignoreCase = true) -> "The request timed out."
        raw.isBlank() -> "Something went wrong."
        else -> raw.take(220)
    }
}

data class Chapter(val startMs: Long, val title: String)

/** Pulls "0:00 Intro" style lines out of a description. */
fun parseChapters(description: String): List<Chapter> {
    val chapterRegex = Regex("""(?m)^\s*\(?(\d{1,2}):(\d{2})(?::(\d{2}))?\)?\s*[-–—:.)]?\s*(.{1,80})$""")
    val found = chapterRegex.findAll(description).mapNotNull { m ->
        val a = m.groupValues[1].toLongOrNull() ?: return@mapNotNull null
        val b = m.groupValues[2].toLongOrNull() ?: return@mapNotNull null
        val c = m.groupValues[3].toLongOrNull()
        val ms = if (c != null) ((a * 3600) + (b * 60) + c) * 1000 else ((a * 60) + b) * 1000
        val title = m.groupValues[4].trim()
        if (title.isEmpty()) null else Chapter(ms, title)
    }.toList()
    return if (found.size >= 2) found.sortedBy { it.startMs } else emptyList()
}

@Composable
fun VideoCard(
    video: VideoItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onChannelClick: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
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

        Spacer(Modifier.height(10.dp))
        Text(
            text = video.title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = video.channelName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .then(if (onChannelClick != null) Modifier.clickable(onClick = onChannelClick) else Modifier),
            )
            val meta = buildList {
                if (video.views > 0) add("${compactCount(video.views)} views")
                if (video.publishedText.isNotBlank()) add(video.publishedText)
            }.joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(
                    text = "  ·  $meta",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun CompactVideoRow(video: VideoItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Box(
            modifier = Modifier
                .width(150.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            AsyncImage(
                model = video.thumbnail,
                contentDescription = video.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (video.durationSeconds > 0 && !video.live) {
                Badge(formatDuration(video.durationSeconds), Color(0xCC000000), Alignment.BottomEnd)
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                video.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                video.channelName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = buildList {
                if (video.views > 0) add("${compactCount(video.views)} views")
                if (video.publishedText.isNotBlank()) add(video.publishedText)
            }.joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(
                    meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun Badge(text: String, background: Color, alignment: Alignment = Alignment.BottomEnd) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(6.dp),
        contentAlignment = alignment,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(background)
                .padding(horizontal = 5.dp, vertical = 2.dp),
        )
    }
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/** The little "2×" pill shown while the viewer holds the screen to fast-forward. */
@Composable
fun CaptionSpeedBadge(
    text: String = "2×",
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0xCC000000))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.FastForward,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text,
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
fun MessageBox(title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            action()
        }
    }
}
