package dev.wetube.app.data.local

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * A subscription import from an existing YouTube client, reduced to what the
 * local subscription list needs: the channel [id] (a `UC...` channel id) and its
 * display [name].
 */
data class ImportChannel(val id: String, val name: String)

/**
 * The outcome of parsing an import file.
 *
 * [skipped] counts only the rows or feed entries that *should* have yielded a
 * channel but did not resolve to a `UC...` id — a Takeout row whose id column is
 * blank and whose URL has no `/channel/` id, or a genuine YouTube feed whose
 * `xmlUrl` carries no usable `channel_id`. Rows and outlines that are not
 * channel entries at all (a header, a blank line, a non-YouTube feed) are
 * ignored and never counted.
 */
data class ImportResult(val channels: List<ImportChannel>, val skipped: Int)

/**
 * A plausible YouTube channel id: the `UC` prefix and long enough to be a real
 * id (they are 24 characters), so a stray `UC` in a URL segment is rejected.
 */
private fun isChannelId(candidate: String): Boolean =
    candidate.length >= 20 && candidate.startsWith("UC")

/**
 * Extracts a channel id from a YouTube channel URL, preferring a
 * `/channel/<id>` segment and stopping at the next path segment, query or
 * fragment. A `/c/`, `/user/` or `/@handle` URL — anything that identifies a
 * channel only by name — has no id to return, so it yields `null`.
 */
private fun channelIdFromChannelUrl(url: String): String? = idAfterMarker(url, "/channel/")

/**
 * Extracts the `channel_id` query value from a feed `xmlUrl`, stopping at the
 * next `&`, query or fragment so extra parameters are not swallowed.
 */
private fun channelIdFromFeedUrl(url: String): String? = idAfterMarker(url, "channel_id=")

/** The `UC...` token that follows [marker] in [text], or `null` when there is none. */
private fun idAfterMarker(text: String, marker: String): String? {
    val start = text.indexOf(marker)
    if (start < 0) return null
    val rest = text.substring(start + marker.length)
    val id = rest.takeWhile { it != '/' && it != '?' && it != '&' && it != '#' }
    return id.takeIf(::isChannelId)
}

/**
 * Parses a Google Takeout `subscriptions.csv` into channels.
 *
 * The first record is treated as the column header and skipped. The `Channel Id`
 * column is preferred when it holds a `UC...` id; newer Takeout exports leave it
 * blank, so the id is then derived from the `Channel URL` (`/channel/<id>`). A
 * `/c/`, `/user/` or `/@handle` URL has no id and is counted in
 * [ImportResult.skipped]. The name is the `Channel Title`, trimmed, falling back
 * to the id when blank.
 *
 * A UTF-8 BOM, CRLF or LF line endings, blank lines and surrounding whitespace
 * are all tolerated. Blank or empty input yields an empty result.
 */
internal fun parseTakeoutCsv(text: String): ImportResult {
    val rows = parseCsvRecords(text.removePrefix("\uFEFF"))
    if (rows.isEmpty()) return ImportResult(emptyList(), 0)

    var skipped = 0
    // Keyed by id so a channel repeated in the file is kept once; insertion
    // order preserves the file's order.
    val channels = LinkedHashMap<String, ImportChannel>()
    for (row in rows.drop(1)) {
        if (row.all { it.isBlank() }) continue
        val idColumn = row.getOrNull(0).orEmpty().trim()
        val url = row.getOrNull(1).orEmpty().trim()
        val title = row.getOrNull(2).orEmpty().trim()
        val id = idColumn.takeIf(::isChannelId) ?: channelIdFromChannelUrl(url)
        if (id == null) {
            skipped++
            continue
        }
        channels.putIfAbsent(id, ImportChannel(id, title.ifBlank { id }))
    }
    return ImportResult(channels.values.toList(), skipped)
}

/**
 * Splits raw CSV [text] into records of fields.
 *
 * It is a small, correct field splitter rather than a `split(",")`: a field may
 * be surrounded by double quotes, a quoted field may contain commas and
 * newlines, and an escaped quote inside a quoted field is written `""`. The
 * outer quotes are removed and `""` becomes one `"`. Both CRLF and LF end a
 * record, and a trailing newline does not produce a spurious empty record.
 */
private fun parseCsvRecords(text: String): List<List<String>> {
    val records = ArrayList<List<String>>()
    var record = ArrayList<String>()
    val field = StringBuilder()
    var inQuotes = false
    var index = 0
    while (index < text.length) {
        val c = text[index]
        when {
            inQuotes -> when {
                c == '"' && index + 1 < text.length && text[index + 1] == '"' -> {
                    field.append('"')
                    index++
                }
                c == '"' -> inQuotes = false
                else -> field.append(c)
            }
            c == '"' -> inQuotes = true
            c == ',' -> {
                record.add(field.toString())
                field.setLength(0)
            }
            c == '\r' || c == '\n' -> {
                record.add(field.toString())
                field.setLength(0)
                records.add(record)
                record = ArrayList()
                // Consume the pair of a CRLF so it ends one record, not two.
                if (c == '\r' && index + 1 < text.length && text[index + 1] == '\n') index++
            }
            else -> field.append(c)
        }
        index++
    }
    // Flush a final record only when something is pending, so a trailing
    // newline does not add an empty one.
    if (field.isNotEmpty() || record.isNotEmpty()) {
        record.add(field.toString())
        records.add(record)
    }
    return records
}

/**
 * Parses an OPML file exported by NewPipe, FreeTube, Invidious or a similar
 * client into channels.
 *
 * Channel entries are `<outline>` elements with an `xmlUrl` that points at a
 * YouTube feed (`youtube.com/feeds/videos.xml?channel_id=UC...`). The
 * `channel_id` is extracted, stopping before any further `&` parameters or a
 * fragment. The name is the `title` attribute, then `text`, then the id. A
 * genuine YouTube feed whose `xmlUrl` carries no usable `channel_id` is counted
 * in [ImportResult.skipped]; a non-YouTube feed or a non-feed outline is simply
 * ignored.
 *
 * XML is parsed with the JDK's own DOM parser, configured to reject a DOCTYPE
 * and to expand neither XInclude nor entity references: an OPML file is
 * untrusted input. Malformed XML degrades to an empty result instead of
 * throwing, as does empty input.
 */
internal fun parseOpml(text: String): ImportResult {
    if (text.isBlank()) return ImportResult(emptyList(), 0)
    val document = runCatching {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setXIncludeAware(false)
        factory.setExpandEntityReferences(false)
        factory.newDocumentBuilder().parse(InputSource(StringReader(text)))
    }.getOrNull() ?: return ImportResult(emptyList(), 0)

    var skipped = 0
    val channels = LinkedHashMap<String, ImportChannel>()
    val outlines = document.getElementsByTagName("outline")
    for (i in 0 until outlines.length) {
        val outline = outlines.item(i) as? Element ?: continue
        val xmlUrl = outline.getAttribute("xmlUrl")
        if (xmlUrl.isBlank()) continue
        val isYouTubeFeed = xmlUrl.contains("youtube.com", ignoreCase = true) ||
            xmlUrl.contains("channel_id=", ignoreCase = true)
        if (!isYouTubeFeed) continue
        val id = channelIdFromFeedUrl(xmlUrl)
        if (id == null) {
            skipped++
            continue
        }
        val name = outline.getAttribute("title").takeIf { it.isNotBlank() }
            ?: outline.getAttribute("text").takeIf { it.isNotBlank() }
            ?: id
        channels.putIfAbsent(id, ImportChannel(id, name.trim()))
    }
    return ImportResult(channels.values.toList(), skipped)
}
