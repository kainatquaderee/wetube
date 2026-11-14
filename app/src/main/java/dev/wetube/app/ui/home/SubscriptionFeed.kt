package dev.wetube.app.ui.home

import dev.wetube.app.data.model.VideoItem
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/*
 * Home's Subscriptions feed is assembled from the device-local subscription
 * list, so it needs no account and works for a signed-out user. Each subscribed
 * channel's recent uploads are fetched with bounded concurrency and merged
 * newest-first; the relative `publishedText` ("3 hours ago") is the only
 * ordering signal a feed item carries. The pieces below are pure and take plain
 * values so they can be unit-tested without a device, a backend or a `Context`.
 */

private const val SECOND = 1L
private const val MINUTE = 60L * SECOND
private const val HOUR = 60L * MINUTE
private const val DAY = 24L * HOUR
private const val WEEK = 7L * DAY

/**
 * YouTube gives no absolute upload time on a feed item, only a human string
 * such as "3 hours ago". We approximate a month as 30 days and a year as 365,
 * which is all the ordering needs: a newer upload always parses to a smaller
 * age than an older one within the same unit.
 */
private const val MONTH = 30L * DAY
private const val YEAR = 365L * DAY

/**
 * Matches a `"<n> <unit> ago"` fragment anywhere in the text. The unit is kept
 * as a group so it can be looked up in [AGE_UNITS]; the optional plural `s` and
 * the `ago` suffix are required, so unrelated prose ("5 things to try") does not
 * parse as a time.
 */
private val RELATIVE_AGE = Regex(
    """(\d+)\s*(second|minute|hour|day|week|month|year)s?\s+ago""",
    RegexOption.IGNORE_CASE,
)

private val AGE_UNITS: Map<String, Long> = mapOf(
    "second" to SECOND,
    "minute" to MINUTE,
    "hour" to HOUR,
    "day" to DAY,
    "week" to WEEK,
    "month" to MONTH,
    "year" to YEAR,
)

/**
 * The approximate age of an upload, in seconds, parsed from a relative string
 * like `"3 hours ago"`, `"Streamed 5 minutes ago"` or `"2 years ago"`.
 *
 * The leading word ("Streamed", "Premiered") is ignored: the number and unit
 * carry all the meaning. `"just now"` and `"moments ago"` are `0`. Matching is
 * case-insensitive and collapses runs of whitespace, because the exact casing
 * and spacing of YouTube's strings vary by client. Returns `null` when nothing
 * parses — an unknown-age item sorts after every known one rather than
 * masquerading as the newest.
 */
internal fun publishedAgeSeconds(text: String): Long? {
    val normalized = text.trim().replace(Regex("""\s+"""), " ").lowercase()
    if (normalized.isEmpty()) return null
    if (normalized.startsWith("just now") || normalized.startsWith("moments ago")) return 0L

    val match = RELATIVE_AGE.find(normalized) ?: return null
    val count = match.groupValues[1].toLongOrNull() ?: return null
    val unit = AGE_UNITS[match.groupValues[2]] ?: return null
    // A crafted or absurd count must not overflow into a negative age, which
    // would sort as the newest thing in the feed.
    return if (count > Long.MAX_VALUE / unit) null else count * unit
}

/**
 * The feed order: de-duplicated by id, newest first, capped at [cap].
 *
 * Ordering is by [publishedAgeSeconds] ascending (smallest age first), with
 * unknown ages last, and it is *stable*: items of equal age keep the order they
 * arrived in, so a merge of several channels stays deterministic. De-duplication
 * keeps the first occurrence's identity, which matters because the same video
 * can appear in two channels' uploads (a collaboration, a re-upload). A
 * non-positive [cap] yields no items.
 */
internal fun feedOrder(items: List<VideoItem>, cap: Int): List<VideoItem> {
    if (cap <= 0) return emptyList()
    return items.distinctBy { it.id }
        .sortedWith(
            Comparator { a, b ->
                val aAge = publishedAgeSeconds(a.publishedText)
                val bAge = publishedAgeSeconds(b.publishedText)
                when {
                    aAge == null && bAge == null -> 0
                    aAge == null -> 1
                    bAge == null -> -1
                    else -> aAge.compareTo(bAge)
                }
            },
        )
        .take(cap)
}

/**
 * Runs [block] for every item with at most [concurrency] calls in flight, and
 * calls [onResult] as each completes.
 *
 * Results are delivered in *completion* order, not input order, which is what
 * lets a caller render the first channel that answers without waiting for the
 * slowest one. Every item is dispatched before this returns; a [concurrency]
 * below 1 is treated as 1. An exception from [block] propagates (and cancels
 * the rest) so the caller must isolate any per-item failure itself — that is
 * deliberate, so this stays a small, predictable concurrency primitive rather
 * than a retry policy.
 */
internal suspend fun <T, R> collectBounded(
    items: List<T>,
    concurrency: Int,
    block: suspend (T) -> R,
    onResult: (R) -> Unit,
) = coroutineScope {
    if (items.isEmpty()) return@coroutineScope
    val permits = Semaphore(concurrency.coerceAtLeast(1))
    // An unlimited channel used purely as a completion queue: each worker sends
    // its result, and the consumer below drains it in the order sends land.
    val results = Channel<R>(Channel.UNLIMITED)

    // Launch every item up front, so all are dispatched even while only
    // `concurrency` of them hold a permit.
    val workers = items.map { item ->
        launch {
            results.send(permits.withPermit { block(item) })
        }
    }
    // Close the queue once every worker has settled, so the drain below ends.
    // A worker that throws cancels the scope, which unblocks the drain anyway.
    launch {
        workers.forEach { it.join() }
        results.close()
    }

    for (result in results) onResult(result)
}
