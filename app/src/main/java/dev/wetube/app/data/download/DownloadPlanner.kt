package dev.wetube.app.data.download

import java.util.Locale

/**
 * Pure helpers shared by the download store and the downloader.
 *
 * None of these touch Android, the filesystem or the network, so the rules that
 * decide a file name, a size label and a resume offset can be pinned by unit
 * tests without a device.
 */

/**
 * The file name a video is stored under: its id, sanitised, plus an extension
 * chosen from [mime].
 *
 * The id is reduced to `[A-Za-z0-9_-]`; every other character becomes `_`, so a
 * value that came from a feed can never escape the downloads directory or
 * smuggle a path separator into it. A `.` becomes `_` too, which is what keeps
 * a dots-only id from producing a hidden file; an empty id still has to yield a
 * usable name, so it falls back to a fixed stem. The extension follows the
 * container in [mime] and defaults to `mp4` for anything else, because that is
 * the muxed progressive container the downloader assumes.
 */
internal fun downloadFileName(videoId: String, mime: String): String {
    val stem = buildString(videoId.length) {
        for (ch in videoId) {
            append(
                if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '_' || ch == '-') {
                    ch
                } else {
                    '_'
                },
            )
        }
    }
    val safeStem = stem.ifEmpty { "download" }
    val type = mime.substringBefore(';').trim().lowercase(Locale.US)
    val extension = when (type) {
        "video/mp4", "audio/mp4" -> "mp4"
        "video/webm", "audio/webm" -> "webm"
        else -> "mp4"
    }
    return "$safeStem.$extension"
}

/**
 * A short, human size for [bytes].
 *
 * 1000-based units, because that is what a phone's storage settings show and
 * what a data-count reads as; the labels are not exact bytes but they are never
 * off by more than a unit step. Under 1000 bytes the exact count is shown, zero
 * and negatives collapse to `"0 B"`, and a scaled value below 10 keeps one
 * decimal (`"1.4 MB"`, `"2.0 GB"`) while a larger one is rounded to a whole
 * number (`"512 KB"`). The decimal point is always `.`, independent of the
 * device locale, so a label is stable wherever the app runs.
 */
internal fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    if (bytes < 1000) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB")
    var value = bytes.toDouble()
    var unit = -1
    do {
        value /= 1000.0
        unit++
    } while (value >= 1000 && unit < units.lastIndex)
    val label = if (value >= 10) {
        Math.round(value).toString()
    } else {
        String.format(Locale.US, "%.1f", value)
    }
    return "$label ${units[unit]}"
}

/**
 * The starting byte from a `Content-Range` header, or `null` when it does not
 * name one.
 *
 * `"bytes 100-999/1000"` yields `100`. It is the counterpart of
 * [totalFromContentRange] and exists for the same reason: a resume must check
 * that the body it appends begins exactly where the part ends, and the only
 * place that is stated is here. A malformed header, an unsatisfiable-range
 * header that names a wildcard position, and an absent header all yield
 * `null`, so a caller treats "position unknown" as "cannot safely append".
 */
internal fun startFromContentRange(header: String?): Long? =
    header
        ?.substringAfter(' ', missingDelimiterValue = "")
        ?.substringBefore('-')
        ?.trim()
        ?.toLongOrNull()

/**
 * The storage limit in bytes for a limit stored in the settings' 1000-based
 * megabytes.
 *
 * The settings store the limit in the very units [formatBytes] prints and the
 * Settings dropdown labels ("1 GB"): a limit of `1000` is one billion bytes.
 * Scaling it by 1024 twice would enforce a ceiling about 7% larger than the
 * figure the user picked, so the bytes an owner counts against the limit would
 * not match the label. A limit of `0` or less means unlimited and yields `0`,
 * which the manager already reads as "no limit".
 */
internal fun downloadLimitBytes(limitMb: Int): Long =
    if (limitMb <= 0) 0L else limitMb.toLong() * 1_000L * 1_000L

/**
 * The fraction of a download that is done, as `0f..1f`, or `null` when the
 * total is unknown or meaningless.
 *
 * A non-positive [total] means the server did not tell us how big the file is,
 * so there is no honest fraction to show and the caller must fall back to an
 * indeterminate indicator. Counting more than [total] (a wrong `Content-Length`)
 * clamps to a full bar rather than an impossible one.
 */
internal fun downloadProgress(bytes: Long, total: Long): Float? {
    if (total <= 0) return null
    return (bytes.toDouble() / total).toFloat().coerceIn(0f, 1f)
}

/**
 * The total file size from a `Content-Range` header, or `null` when it does not
 * name one.
 *
 * `"bytes 100-999/1000"` yields `1000`; the wildcard total (`"*"` after the
 * slash), a malformed header and an absent header all yield `null`, so a caller
 * treats "size unknown" the same however the server failed to state it.
 */
internal fun totalFromContentRange(header: String?): Long? =
    header?.substringAfterLast('/', missingDelimiterValue = "")?.trim()?.toLongOrNull()

/**
 * The `Range` header that resumes a partial file from [offset], or `null` when
 * there is nothing to resume.
 *
 * A zero (or negative) offset means nothing has been written yet, so no range
 * is requested and the server sends the whole file.
 */
internal fun rangeHeader(offset: Long): String? =
    if (offset > 0) "bytes=$offset-" else null
