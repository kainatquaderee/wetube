package dev.wetube.app.data.download

import android.content.Context
import java.io.File

/**
 * The directory downloaded media is written to.
 *
 * App-specific external storage is preferred because a video is orders of
 * magnitude larger than anything the app keeps internally: on a device whose
 * internal storage is tight, this is the one location the user can clear without
 * wiping the app's data, and it needs no storage permission because no other app
 * can read it. It travels with the app when it is removed, so it cannot leak media
 * to a later install.
 *
 * When external storage is not mounted — [Context.getExternalFilesDir] returns
 * `null` — the internal files directory is the honest fallback: a download that
 * cannot be placed outside still lands somewhere playable rather than failing.
 */
internal fun mediaDirectory(context: Context): File =
    File(context.getExternalFilesDir(null) ?: context.filesDir, "media")
