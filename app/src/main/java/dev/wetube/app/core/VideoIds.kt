package dev.wetube.app.core

/**
 * A YouTube video id is exactly 11 URL-safe characters. Anchoring the whole
 * string rejects the path traversal (`..`, `/`) that an explicit, exported
 * intent can otherwise smuggle into a navigation route.
 */
val VIDEO_ID_PATTERN: Regex = Regex("^[A-Za-z0-9_-]{11}$")

fun isValidVideoId(id: String?): Boolean = id != null && VIDEO_ID_PATTERN.matches(id)
