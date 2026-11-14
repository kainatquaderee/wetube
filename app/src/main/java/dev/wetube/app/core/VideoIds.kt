package dev.wetube.app.core

/**
 * A YouTube video id is exactly 11 URL-safe characters. Anchoring the whole
 * string rejects the path traversal (`..`, `/`) that an explicit, exported
 * intent can otherwise smuggle into a navigation route.
 */
val VIDEO_ID_PATTERN: Regex = Regex("^[A-Za-z0-9_-]{11}$")

fun isValidVideoId(id: String?): Boolean = id != null && VIDEO_ID_PATTERN.matches(id)

/**
 * The video id in a YouTube link a user pasted, or `null` when the text is not
 * a YouTube link.
 *
 * Search tells the user they can "paste a link to watch something specific", so
 * the field honours the two shapes the app's own deep links accept —
 * `youtu.be/ID` and `youtube.com/watch?v=ID` — with or without a scheme.
 * Anything else, a bare search phrase included, returns `null` and is searched
 * as text. The same host list and id check as the deep-link path are reused, so
 * a pasted link and a tapped link can never diverge.
 */
fun youtubeVideoId(text: String): String? {
    val trimmed = text.trim()
    if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
    if (!trimmed.contains("youtu", ignoreCase = true)) return null
    val withScheme =
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed
        else "https://$trimmed"
    val uri = runCatching { java.net.URI(withScheme) }.getOrNull() ?: return null
    val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
    val candidate = when (host) {
        "youtu.be" -> uri.path.orEmpty().trim('/').substringBefore('/').ifBlank { null }
        "youtube.com", "m.youtube.com" -> uri.rawQuery
            ?.split('&')
            ?.firstNotNullOfOrNull { part ->
                val eq = part.indexOf('=')
                if (eq > 0 && part.substring(0, eq) == "v") part.substring(eq + 1) else null
            }
        else -> null
    }
    return candidate?.takeIf(::isValidVideoId)
}
