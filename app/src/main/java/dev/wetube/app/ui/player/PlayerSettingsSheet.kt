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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeUp
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.wetube.app.ui.formatSpeed

/** Which level of the settings sheet is showing. */
private enum class SettingsPage { Main, Quality, Speed }

private val speedChoices = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f, 4f)

/**
 * The label for a quality choice.
 *
 * An explicit pick shows the height that is actually playing whenever the video
 * is too small for the chosen one, so the chip never claims 2160p over a 1080p
 * stream; Auto shows the height it resolved to.
 */
internal fun qualityValue(quality: Int, effectiveHeight: Int): String = when {
    quality <= 0 && effectiveHeight > 0 -> "Auto (${effectiveHeight}p)"
    quality <= 0 -> "Auto"
    effectiveHeight > 0 -> "${effectiveHeight}p"
    else -> "${quality}p"
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
    audioOnlyInBackground: Boolean,
    sponsorBlock: Boolean,
    showDislikes: Boolean,
    /** True while a renderer, not the phone, is playing. */
    castActive: Boolean = false,
    onQuality: (Int) -> Unit,
    onSpeed: (Float) -> Unit,
    onOpenCaptions: () -> Unit,
    onAudioOnly: (Boolean) -> Unit,
    onBackgroundPlayback: (Boolean) -> Unit,
    onAudioOnlyInBackground: (Boolean) -> Unit,
    onSponsorBlock: (Boolean) -> Unit,
    onShowDislikes: (Boolean) -> Unit,
    onOpenAllSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var page by remember { mutableStateOf(SettingsPage.Main) }

    // Only the heights the video actually offers, so a choice here can never
    // silently fall back to a smaller stream. This matches the player's More
    // menu, which lists the same set.
    val heights = remember(qualities) { qualities.distinct().sortedDescending() }

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
                        value = formatSpeed(speed),
                        enabled = !castActive,
                        onClick = { page = SettingsPage.Speed },
                    )
                    NavRow(
                        icon = Icons.Default.Subtitles,
                        title = "Captions",
                        value = if (captionsEnabled) "On" else "Off",
                        enabled = captionsAvailable && !castActive,
                        onClick = onOpenCaptions,
                    )
                    if (castActive) {
                        // The renderer decodes and drives captions/speed, so
                        // these two cannot be changed from the phone without a
                        // re-cast. Say so instead of offering dead rows.
                        Text(
                            text = "Speed and captions are controlled on the TV while casting. " +
                                "Quality re-casts the video there.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                        )
                    }

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
                        icon = Icons.AutoMirrored.Filled.VolumeUp,
                        title = "Audio only in the background",
                        checked = audioOnlyInBackground,
                        onChange = onAudioOnlyInBackground,
                    )
                    SwitchRow(
                        icon = Icons.Default.FastForward,
                        title = "Skip sponsor segments",
                        checked = sponsorBlock,
                        onChange = onSponsorBlock,
                    )
                    SwitchRow(
                        icon = Icons.Default.ThumbUp,
                        title = "Show dislike count",
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
                        onClick = {
                            onQuality(0)
                            onDismiss()
                        },
                    )
                    heights.forEach { height ->
                        ChoiceRow(
                            title = "${height}p",
                            subtitle = null,
                            selected = quality == height,
                            onClick = {
                                onQuality(height)
                                onDismiss()
                            },
                        )
                    }
                }

                SettingsPage.Speed -> {
                    SubHeader(title = "Playback speed", onBack = { page = SettingsPage.Main })
                    speedChoices.forEach { choice ->
                        ChoiceRow(
                            title = formatSpeed(choice),
                            subtitle = null,
                            selected = choice == speed,
                            onClick = {
                                onSpeed(choice)
                                onDismiss()
                            },
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
            // Toggleable carries the switch semantics (checked state and
            // Role.Switch) for the whole row, so screen readers announce one
            // control instead of a label next to an unnamed switch.
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onChange,
            )
            // Collapse the icon, the label and the display-only switch into
            // that one node. Merging descendants alone still left the title as
            // a separate child node, so the checkable node read as unlabelled;
            // replacing the children's semantics puts the title in this node's
            // description and the checked state on the same node.
            .clearAndSetSemantics {
                contentDescription = title
                role = Role.Switch
                toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
            }
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
