package dev.wetube.app.player.remote

import java.util.Locale

/**
 * `H:MM:SS(.F)?` ⇄ milliseconds.
 *
 * UPnP spells timestamps for `RelTime`, `TrackDuration` and `Seek` in this
 * format, and devices are sloppy about it: some omit hours, some send a
 * fractional part, and a few answer `NOT_IMPLEMENTED` where a number belongs.
 * Nothing in the playlist UI should have to know that, so every parse here
 * yields a number and every strange input becomes `0`.
 */
internal object UpnpTime {

    /** The literal a device sends when it cannot report the value at all. */
    const val NOT_IMPLEMENTED = "NOT_IMPLEMENTED"

    /** Beyond ~416 days a value is noise, and multiplying it overflows a `Long`. */
    private const val MAX_HOURS = 9_999L

    /**
     * Parses `H:MM:SS`, `H:MM:SS.F` or the hour-less `MM:SS` into milliseconds.
     *
     * [NOT_IMPLEMENTED], blank text, negative fields and anything that is not a
     * colon-separated time all return `0`, because "unknown" and "at the start"
     * are the same thing to a progress bar that only shows what it was told.
     */
    fun parse(text: String?): Long {
        val raw = text?.trim().orEmpty()
        if (raw.isEmpty() || raw.equals(NOT_IMPLEMENTED, ignoreCase = true)) return 0

        val parts = raw.split(':')
        if (parts.size !in 2..3) return 0

        val secondsPart = parts.last()
        val minutes = parts[parts.size - 2].toLongOrNull() ?: return 0
        val hours = if (parts.size == 3) parts[0].toLongOrNull() ?: return 0 else 0L
        if (hours < 0 || hours > MAX_HOURS || minutes < 0) return 0

        val dot = secondsPart.indexOf('.')
        val seconds = (if (dot >= 0) secondsPart.substring(0, dot) else secondsPart).toLongOrNull() ?: return 0
        if (seconds < 0) return 0
        val millis = if (dot >= 0) fractionMillis(secondsPart.substring(dot + 1)) else 0L

        return ((hours * 3_600 + minutes * 60 + seconds) * 1_000) + millis
    }

    /**
     * Formats [ms] as `H:MM:SS`, the form every renderer accepts for `Seek`.
     *
     * Negative values clamp to zero: seeking before the start is meaningless and
     * a negative timestamp would be rejected by the device.
     */
    fun format(ms: Long): String {
        val totalSeconds = ms.coerceAtLeast(0) / 1_000
        val hours = totalSeconds / 3_600
        val minutes = (totalSeconds % 3_600) / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    }

    /** Turns `.F` into milliseconds, padding or truncating to three digits. */
    private fun fractionMillis(fraction: String): Long {
        if (fraction.isEmpty() || fraction.any { !it.isDigit() }) return 0
        return fraction.take(3).padEnd(3, '0').toLongOrNull() ?: 0
    }
}