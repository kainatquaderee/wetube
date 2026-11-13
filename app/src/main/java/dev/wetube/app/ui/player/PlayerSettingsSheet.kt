package dev.wetube.app.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.wetube.app.ui.formatSpeed
import dev.wetube.app.ui.qualityHeights

/** Which level of the settings sheet is showing. */
private enum class SettingsPage { Main, Quality, Speed }

private val speedChoices = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f)

/** "1.0", as the official panel prints it, rather than "1×". */
private fun speedValue(speed: Float): String = if (speed == 1f) "1.0" else formatSpeed(speed)

/** "Auto (1080p)" when the automatic pick is known, otherwise "720p"/"Auto". */
private fun qualityValue(quality: Int, effectiveHeight: Int): String = when {
    quality > 0 -> "${quality}p"
    effectiveHeight > 0 -> "Auto (${effectiveHeight}p)"
    else -> "Auto"
}

/**
 * The player's settings panel, opened from the control bar's gear.
 *
 * It mirrors the official player: a sheet of value rows — Quality, Playback
 * speed, Captions — that open a second level, followed by the playback
 * switches and a door into the full settings. Everything that matters while
 * watching can be changed without leaving the video.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerSettingsSheet(
    quality: Int,
    qualities: List<Int>,
    effectiveHeight: Int,
    speed: Float,
    captionsAvailable: Boolean,
    captionsEnabled: Boolean,
    audioOnly: Boolean,
    backgroundPlayback: Boolean,
    sponsorBlock: Boolean,
    showDislikes: Boolean,
    onQuality: (Int) -> Unit,
    onSpeed: (Float) -> Unit,
    onOpenCaptions: () -> Unit,
    onAudioOnly: (Boolean) -> Unit,
    onBackgroundPlayback: (Boolean) -> Unit,
    onSponsorBlock: (Boolean) -> Unit,
    onShowDislikes: (Boolean) -> Unit,
    onOpenAllSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var page by remember { mutableStateOf(SettingsPage.Main) }

    // Every height the video offers plus the standard ladder, highest first, so
    // the list looks the same even for a video that only has one file.
    val heights = remember(qualities) {
        (qualities + qualityHeights).distinct().sortedDescending()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(top = 4.dp, bottom = 24.dp),
        ) {
            when (page) {
                SettingsPage.Main -> {
                    if (qualities.isNotEmpty()) {
                        NavRow(
                            icon = Icons.Default.HighQuality,
                            title = "Quality",
                            value = qualityValue(quality, effectiveHeight),
                            onClick = { page = SettingsPage.Quality },
                        )
                    }
                    NavRow(
                        icon = Icons.Default.Speed,
                        title = "Playback speed",
                        value = speedValue(speed),
                        onClick = { page = SettingsPage.Speed },
                    )
                    NavRow(
                        icon = Icons.Default.Subtitles,
                        title = "Captions",
                        value = if (captionsEnabled) "On" else "Off",
                        enabled = captionsAvailable,
                        onClick = onOpenCaptions,
                    )

                    SwitchRow(
                        icon = Icons.Default.PlayCircle,
                        title = "Audio only",
                        checked = audioOnly,
                        onChange = onAudioOnly,
                    )
                    SwitchRow(
                        icon = Icons.Default.PlayArrow,
                        title = "Keep playing in the background",
                        checked = backgroundPlayback,
                        onChange = onBackgroundPlayback,
                    )
                    SwitchRow(
                        icon = Icons.Default.FastForward,
                        title = "Skip sponsor segments",
                        checked = sponsorBlock,
                        onChange = onSponsorBlock,
                    )
                    SwitchRow(
                        icon = Icons.Default.ThumbUp,
                        title = "Show likes and dislikes",
                        checked = showDislikes,
                        onChange = onShowDislikes,
                    )

                    NavRow(
                        icon = Icons.Default.Settings,
                        title = "More settings",
                        value = "",
                        onClick = onOpenAllSettings,
                    )
                }

                SettingsPage.Quality -> {
                    SubHeader(title = "Quality", onBack = { page = SettingsPage.Main })
                    ChoiceRow(
                        title = "Auto",
                        subtitle = if (effectiveHeight > 0) "Currently ${effectiveHeight}p" else null,
                        selected = quality <= 0,
                        onClick = { onQuality(0) },
                    )
                    heights.forEach { height ->
                        ChoiceRow(
                            title = "${height}p",
                            subtitle = null,
                            selected = quality == height,
                            onClick = { onQuality(height) },
                        )
                    }
                }

                SettingsPage.Speed -> {
                    SubHeader(title = "Playback speed", onBack = { page = SettingsPage.Main })
                    speedChoices.forEach { choice ->
                        ChoiceRow(
                            title = speedValue(choice),
                            subtitle = null,
                            selected = choice == speed,
                            onClick = { onSpeed(choice) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SubHeader(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = 20.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun SwitchRow(
    icon: ImageVector,
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(16.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        // The row owns the click so the whole line toggles, like the official
        // panel; the switch itself is display-only.
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun NavRow(
    icon: ImageVector,
    title: String,
    value: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(16.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (value.isNotBlank()) {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ChoiceRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (selected) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
