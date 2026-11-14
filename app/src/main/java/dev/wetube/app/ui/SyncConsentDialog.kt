package dev.wetube.app.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import dev.wetube.app.sync.SyncPreview

/**
 * The first-sync consent prompt, shared by the Library and Settings screens.
 *
 * The very first sync pulls the account down but stops *before* any write and
 * parks in `SyncStatus.AwaitingConsent`. This dialog is the only way out of that
 * pause, so both screens that can surface sync status render the same one:
 * showing the counts the engine measured and asking a single yes/no. It lives
 * here rather than in two screens because the wording is a safety boundary —
 * duplicating it would let the two copies drift and understate what "Merge"
 * does.
 *
 * [SyncPreview] is a plain data type with no Android types, so this composable is
 * a thin, untested shell over the engine's own decision.
 */
@Composable
fun FirstSyncConsentDialog(
    preview: SyncPreview,
    onMerge: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Merge with your account?") },
        text = {
            Text(
                "Your account has ${countOf(preview.subscriptionsOnAccount, "subscription")} " +
                    "and ${countOf(preview.favouritesOnAccount, "favourite")}. This device " +
                    "has ${countOf(preview.subscriptionsToPush, "subscription")} and " +
                    "${countOf(preview.favouritesToPush, "favourite")} to add. " +
                    "${countOf(preview.removalsToPush, "item")} you removed here would also " +
                    "be removed from the account. Merge them?",
            )
        },
        confirmButton = { TextButton(onClick = onMerge) { Text("Merge") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}
