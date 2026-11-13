package dev.wetube.app.core

import java.util.Locale

/**
 * Text helpers shared by every screen and backend.
 *
 * They live here — not in the UI layer — so the data layer can normalise text
 * as it leaves a backend, which fixes every render site at once instead of one
 * `Text(...)` call at a time.
 */

/** `&amp;`, `&#39;`, `&#128293;`, `&#x1F525;` … the handful YouTube actually emits. */
private val NAMED_ENTITIES: Map<String, String> = mapOf(
    "amp" to "&",
    "lt" to "<",
    "gt" to ">",
    "quot" to "\"",
    "apos" to "'",
    "nbsp" to "\u00A0",
)

/**
 * Named, decimal (`&#128293;`) and hex (`&#x1F525;`) numeric references. The
 * hex marker is matched case-insensitively because real documents also carry
 * the uppercase form (`&#X1F525;`).
 */
private val ENTITY_REGEX = Regex("&(#[xX][0-9a-fA-F]+|#[0-9]+|[a-zA-Z][a-zA-Z0-9]*);")

/**
 * How many decode passes [decodeEntities] may run before giving up on further
 * change. Real payloads are at most double-encoded, but the bound also makes
 * the loop provably finite for any input: every effective pass strictly
 * shortens the string (an entity is at least three characters and its
 * replacement is at most one code point), and an unrecognised reference is
 * returned as-is, so the string can never oscillate.
 */
private const val MAX_DECODE_PASSES = 4

/**
 * Decodes the HTML entities YouTube leaves in JSON titles and descriptions, so
 * `&amp;` renders as `&` and `&#128293;` renders as 🔥.
 *
 * The text is decoded repeatedly until it stops changing, because a backend can
 * hand over a value that was escaped more than once: `&amp;#128293;` must reach
 * 🔥 rather than stopping at the still-visible `&#128293;`. Each pass is a
 * single left-to-right replacement, so a reference produced by a previous pass
 * is only expanded on the next one; the result is idempotent (decoding an
 * already-decoded string leaves it unchanged).
 *
 * [MAX_DECODE_PASSES] bounds the work as defence in depth. Anything the table
 * and the numeric ranges do not recognise is returned untouched, so an ordinary
 * `&` or an unknown `&foo;` is never mangled.
 */
fun decodeEntities(value: String): String {
    if (value.indexOf('&') < 0) return value
    var current = value
    repeat(MAX_DECODE_PASSES) {
        val next = decodeEntitiesOnce(current)
        if (next == current) return current
        current = next
    }
    return current
}

/** A single left-to-right pass; the loop in [decodeEntities] calls this. */
private fun decodeEntitiesOnce(value: String): String {
    return ENTITY_REGEX.replace(value) { match ->
        val body = match.groupValues[1]
        when {
            body.startsWith("#x", ignoreCase = true) -> codePointString(body.substring(2), 16)
            body.startsWith("#") -> codePointString(body.substring(1), 10)
            else -> NAMED_ENTITIES[body.lowercase(Locale.US)]
        } ?: match.value
    }
}

private fun codePointString(digits: String, radix: Int): String? {
    val codePoint = digits.toIntOrNull(radix) ?: return null
    if (codePoint <= 0 || codePoint > Character.MAX_CODE_POINT) return null
    // toChars handles the surrogate pair a supplementary plane character needs.
    return String(Character.toChars(codePoint))
}

/**
 * `1234 -> "1.2K"`, `222000 -> "222K"`, `1000000 -> "1M"`.
 *
 * One decimal place is kept only when it is non-zero, so counts read the way
 * YouTube prints them instead of the old `222.0K`. Values below a thousand and
 * a missing count both fall through to a plain integer.
 */
fun compactCount(n: Long?): String {
    val value = n ?: 0L
    if (value <= 0) return "0"
    if (value < 1_000) return value.toString()
    val (scaledValue, suffix) = when {
        value >= 1_000_000_000 -> value / 1_000_000_000.0 to "B"
        value >= 1_000_000 -> value / 1_000_000.0 to "M"
        else -> value / 1_000.0 to "K"
    }
    val text = scaled(scaledValue)
    // 999_999 scales to 999.999, which one decimal place rounds to "1000.0K".
    // Promote that to the next unit instead of printing four digits.
    return when {
        text != "1000" -> text + suffix
        suffix == "K" -> "1M"
        suffix == "M" -> "1B"
        else -> text + suffix
    }
}

private fun scaled(value: Double): String {
    val text = String.format(Locale.US, "%.1f", value)
    return if (text.endsWith(".0")) text.dropLast(2) else text
}

private val COUNT_REGEX = Regex("""([\d.,]+)\s*([KMB])?""", RegexOption.IGNORE_CASE)

/**
 * Reads an abbreviated count label back into a number:
 * `"1,234" -> 1234`, `"222.0K" -> 222000`, `"1.2M" -> 1200000`.
 *
 * YouTube's own labels are already localised strings, so this only recognises
 * the trailing K/M/B suffix it prints in every locale; anything without a
 * leading number parses as `0`.
 */
fun parseCount(text: String): Long {
    if (text.isBlank()) return 0
    val match = COUNT_REGEX.find(text) ?: return 0
    val number = match.groupValues[1].replace(",", "").toDoubleOrNull() ?: return 0
    return when (match.groupValues[2].uppercase(Locale.US)) {
        "K" -> (number * 1_000).toLong()
        "M" -> (number * 1_000_000).toLong()
        "B" -> (number * 1_000_000_000).toLong()
        else -> number.toLong()
    }
}

/** The word a subscriber label ends with, agreeing with the count. */
private fun subscriberUnit(count: Long): String = if (count == 1L) "subscriber" else "subscribers"

/**
 * One spelling for every subscriber count, whichever backend produced it.
 *
 * Direct (InnerTube) hands over a rendered label such as `"15.8M subscribers"`,
 * while Invidious and Piped hand over the same label, a bare `"15.8M"`, or an
 * empty string — and any of them may leave an HTML entity behind. Normalising
 * here, through [parseCount] and [compactCount], keeps the channel header and
 * the player showing the same string. Text with no readable number (for
 * example `"No subscribers"`) is returned untouched rather than zeroed.
 */
fun subscriberLabel(label: String): String {
    val decoded = decodeEntities(label).trim()
    if (decoded.isEmpty()) return ""
    val count = parseCount(decoded)
    if (count <= 0) return decoded
    return "${compactCount(count)} ${subscriberUnit(count)}"
}

/** Numeric overload of [subscriberLabel] for backends that expose a raw count. */
fun subscriberLabel(count: Long?): String {
    val value = count ?: 0L
    if (value <= 0) return ""
    return "${compactCount(value)} ${subscriberUnit(value)}"
}

/**
 * `1f -> "1×"`, `1.25f -> "1.25×"`, `1.75f -> "1.75×"` — the labels YouTube's
 * own speed menu uses. Whole values print without a decimal part.
 */
fun formatSpeed(speed: Float): String {
    val value = if (speed == speed.toInt().toFloat()) {
        speed.toInt().toString()
    } else {
        String.format(Locale.US, "%.2f", speed).trimEnd('0').trimEnd('.')
    }
    return "$value×"
}
