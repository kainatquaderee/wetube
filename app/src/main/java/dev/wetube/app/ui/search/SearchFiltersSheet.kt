package dev.wetube.app.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.wetube.app.data.backend.SearchDuration
import dev.wetube.app.data.backend.SearchFeature
import dev.wetube.app.data.backend.SearchFilters
import dev.wetube.app.data.backend.SearchSort
import dev.wetube.app.data.backend.SearchType
import dev.wetube.app.data.backend.UploadDate

/**
 * "Search filters": the four narrowing rows YouTube puts above its results, plus
 * the feature toggles worth having, applied to the query already on screen.
 *
 * The selection is edited in a local draft and only handed back by Apply, which
 * is what lets Cancel leave the list exactly as it was: nothing here re-runs the
 * search on its own. The rows are built from the model's own enums rather than a
 * parallel list of strings, so a choice that exists cannot be missing from the
 * menu and a choice that does not cannot be invented here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchFiltersSheet(
    filters: SearchFilters,
    supported: Boolean,
    onApply: (SearchFilters) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Keyed on the applied selection: reopening the sheet starts from what is in
    // force, never from a draft somebody abandoned last time.
    var draft by remember(filters) { mutableStateOf(filters) }

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
            Text(
                "Search filters",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            if (!supported) {
                Text(
                    "This server cannot apply YouTube's search filters. Switch to InnerTube " +
                        "in Settings to use them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }

            FilterRow(
                label = "Type",
                selected = draft.type,
                options = SearchType.entries,
                optionLabel = { it.label },
                onSelect = { draft = draft.copy(type = it) },
            )
            FilterRow(
                label = "Duration",
                selected = draft.duration,
                options = SearchDuration.entries,
                optionLabel = { it.label },
                onSelect = { draft = draft.copy(duration = it) },
            )
            FilterRow(
                label = "Upload date",
                selected = draft.uploadDate,
                options = UploadDate.entries,
                optionLabel = { it.label },
                onSelect = { draft = draft.copy(uploadDate = it) },
            )
            FilterRow(
                label = "Prioritize",
                selected = draft.sort,
                options = SearchSort.entries,
                optionLabel = { it.label },
                onSelect = { draft = draft.copy(sort = it) },
            )

            Text(
                "Features",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
            )
            // One line that scrolls rather than a wrapping grid: four chips fit on
            // most screens, and the fourth is not worth a second row of the sheet.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SearchFeature.entries.forEach { feature ->
                    FilterChip(
                        selected = feature in draft.features,
                        onClick = {
                            draft = draft.copy(
                                features = if (feature in draft.features) {
                                    draft.features - feature
                                } else {
                                    draft.features + feature
                                },
                            )
                        },
                        label = { Text(feature.label) },
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                // Cancel is the plain way out — swipe or tap outside do the same
                // thing — and it is safe because nothing was applied yet.
                TextButton(onClick = onDismiss) { Text("Cancel") }
                TextButton(onClick = { onApply(draft) }, enabled = supported) {
                    Text("Apply")
                }
            }
        }
    }
}

/**
 * One `label … value ⌄` row.
 *
 * The shape is YouTube's own: the name on the left, the current choice on the
 * right, and the alternatives in a menu under it. Generic over the choice because
 * all four rows differ only in which enum they edit and what it is called.
 */
@Composable
private fun <T> FilterRow(
    label: String,
    selected: T,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { open = true }
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                optionLabel(selected),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    // Marked in the text, the way the settings menu already marks
                    // the selected entry.
                    text = {
                        val name = optionLabel(option)
                        Text(if (option == selected) "$name  ✓" else name)
                    },
                    onClick = {
                        open = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

/** Menu wording, kept next to the menu rather than in the model, which carries wire values. */
private val SearchType.label: String
    get() = when (this) {
        SearchType.ALL -> "All"
        SearchType.VIDEO -> "Video"
    }

private val SearchDuration.label: String
    get() = when (this) {
        SearchDuration.ANY -> "Any"
        SearchDuration.UNDER_FOUR_MINUTES -> "Under 4 minutes"
        SearchDuration.FOUR_TO_TWENTY_MINUTES -> "4–20 minutes"
        SearchDuration.OVER_TWENTY_MINUTES -> "Over 20 minutes"
    }

private val UploadDate.label: String
    get() = when (this) {
        UploadDate.ANY_TIME -> "Anytime"
        UploadDate.TODAY -> "Today"
        UploadDate.THIS_WEEK -> "This week"
        UploadDate.THIS_MONTH -> "This month"
        UploadDate.THIS_YEAR -> "This year"
    }

private val SearchSort.label: String
    get() = when (this) {
        SearchSort.RELEVANCE -> "Relevance"
        SearchSort.UPLOAD_DATE -> "Upload date"
        SearchSort.VIEW_COUNT -> "View count"
        SearchSort.RATING -> "Rating"
    }

private val SearchFeature.label: String
    get() = when (this) {
        SearchFeature.FOUR_K -> "4K"
        SearchFeature.HD -> "HD"
        SearchFeature.SUBTITLES -> "Subtitles/CC"
        SearchFeature.LIVE -> "Live"
    }