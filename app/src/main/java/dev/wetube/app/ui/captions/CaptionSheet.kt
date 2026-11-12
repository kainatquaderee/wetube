package dev.wetube.app.ui.captions

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.wetube.app.data.model.CaptionTrack
import dev.wetube.app.data.model.TranslationLanguage
import dev.wetube.app.player.CaptionSession

/** The line of subtitles currently due, drawn over the video like YouTube does. */
@Composable
fun CaptionOverlay(
    session: CaptionSession,
    positionMs: Long,
    modifier: Modifier = Modifier,
) {
    val text = session.captionAt(positionMs)
    AnimatedVisibility(
        visible = !text.isNullOrBlank(),
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        Text(
            text = text.orEmpty(),
            color = Color.White,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xD9000000))
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

/**
 * The CC menu: on/off, the available tracks, and YouTube's own machine
 * translation list. Nothing leaves YouTube — the translated track is fetched
 * from the same signed timed-text URL with `tlang` appended.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptionSheet(
    session: CaptionSession,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by rememberSaveable { mutableStateOf("") }
    var showAll by rememberSaveable { mutableStateOf(false) }

    val translations = remember(session.translations, query) {
        val text = query.trim()
        if (text.isBlank()) session.translations
        else session.translations.filter {
            it.name.contains(text, ignoreCase = true) ||
                it.languageCode.contains(text, ignoreCase = true)
        }
    }
    val visibleTranslations = if (query.isBlank() && !showAll) {
        translations.take(10)
    } else {
        translations
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp),
            contentPadding = PaddingValues(bottom = 28.dp),
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Subtitles,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Subtitles/CC", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (session.enabled) "Captions are on" else "Captions are off",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = session.enabled,
                        enabled = session.tracks.isNotEmpty(),
                        onCheckedChange = session::setCaptionsEnabled,
                    )
                }
            }

            item {
                Text(
                    "Captions",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 20.dp, top = 10.dp, bottom = 4.dp),
                )
            }

            if (session.tracks.isEmpty()) {
                item {
                    Text(
                        "This video has no captions.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
            } else {
                items(session.tracks, key = { it.languageCode + "|" + it.baseUrl }) { track ->
                    TrackRow(
                        track = track,
                        selected = session.enabled &&
                            session.selected == track &&
                            session.translateTo.isBlank(),
                        onSelect = { session.choose(track) },
                    )
                }
            }

            if (session.translations.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider()
                    Text(
                        "Auto-translate",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 2.dp),
                    )
                    Text(
                        "YouTube translates the caption track on the fly.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }

                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = session.translateTo.isBlank(),
                                onClick = session::clearTranslation,
                            )
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = session.translateTo.isBlank(),
                            onClick = session::clearTranslation,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Original language", style = MaterialTheme.typography.bodyMedium)
                    }
                }

                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Search languages") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 6.dp),
                    )
                }

                items(visibleTranslations, key = { it.languageCode }) { language ->
                    TranslationRow(
                        language = language,
                        selected = session.enabled && session.translateTo == language.languageCode,
                        onSelect = {
                            session.choose(
                                session.selected ?: session.tracks.firstOrNull(),
                                language.languageCode,
                            )
                        },
                    )
                }

                if (query.isBlank() && !showAll && translations.size > visibleTranslations.size) {
                    item {
                        TextButton(
                            onClick = { showAll = true },
                            modifier = Modifier.padding(start = 8.dp),
                        ) {
                            Text("Show all ${translations.size} languages")
                        }
                    }
                }
            }

            if (query.isNotBlank() && visibleTranslations.isEmpty()) {
                item {
                    Text(
                        "No language matches \"$query\".",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
            }

            session.error?.let { message ->
                item {
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    )
                }
            }

            if (session.loading) {
                item {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackRow(
    track: CaptionTrack,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(track.name, style = MaterialTheme.typography.bodyMedium)
            if (track.autoGenerated) {
                Text(
                    "Auto-generated",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (selected) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun TranslationRow(
    language: TranslationLanguage,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(Modifier.width(8.dp))
        Text(
            language.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** Small inline helper used by the player's buffering surface. */
@Composable
fun CaptionSpinner() {
    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
}
