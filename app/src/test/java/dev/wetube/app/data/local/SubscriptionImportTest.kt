package dev.wetube.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The import parsers are pure and take a plain string, so they are tested
 * directly with sample files — no Context, no DataStore and no Android API.
 */
class SubscriptionImportTest {

    /** A plausible 24-character channel id. */
    private val id = "UC" + "a".repeat(22)
    private val otherId = "UC" + "b".repeat(22)

    private val header = "Channel Id,Channel Url,Channel Title\n"

    // ----- Google Takeout CSV -----------------------------------------------

    @Test
    fun `the header is skipped and a normal row is parsed`() {
        val csv = header + "$id,http://www.youtube.com/channel/$id,Ada Lovelace\n"

        val result = parseTakeoutCsv(csv)

        assertEquals(listOf(ImportChannel(id, "Ada Lovelace")), result.channels)
        assertEquals(0, result.skipped)
    }

    @Test
    fun `a quoted title keeps its comma`() {
        val csv = header + "$id,http://www.youtube.com/channel/$id,\"Lovelace, Ada\"\n"

        val result = parseTakeoutCsv(csv)

        assertEquals(listOf(ImportChannel(id, "Lovelace, Ada")), result.channels)
        assertEquals(0, result.skipped)
    }

    @Test
    fun `a quoted title may span a newline`() {
        val csv = header + "$id,http://www.youtube.com/channel/$id,\"Ada\nLovelace\"\n"

        val result = parseTakeoutCsv(csv)

        assertEquals(listOf(ImportChannel(id, "Ada\nLovelace")), result.channels)
    }

    @Test
    fun `a blank id column derives the id from a channel url`() {
        val csv = header + ",https://www.youtube.com/channel/$id?sub_confirmation=1,Ada\n"

        val result = parseTakeoutCsv(csv)

        assertEquals(listOf(ImportChannel(id, "Ada")), result.channels)
        assertEquals(0, result.skipped)
    }

    @Test
    fun `a name-less channel falls back to its id`() {
        val csv = header + "$id,http://www.youtube.com/channel/$id,\n"

        val result = parseTakeoutCsv(csv)

        assertEquals(listOf(ImportChannel(id, id)), result.channels)
    }

    @Test
    fun `a url with no channel id is skipped and dropped`() {
        val csv = header +
            ",https://www.youtube.com/c/SomeName,Some Name\n" +
            ",https://www.youtube.com/@handle,Handle\n" +
            ",https://www.youtube.com/user/legacy,Legacy\n"

        val result = parseTakeoutCsv(csv)

        assertTrue(result.channels.isEmpty())
        assertEquals(3, result.skipped)
    }

    @Test
    fun `a row that yields no id is counted once`() {
        val csv = header + ",,,No id here\n"

        val result = parseTakeoutCsv(csv)

        assertTrue(result.channels.isEmpty())
        assertEquals(1, result.skipped)
    }

    @Test
    fun `a duplicate id appears once`() {
        val csv = header +
            "$id,http://www.youtube.com/channel/$id,Ada\n" +
            "$id,http://www.youtube.com/channel/$id,Ada again\n"

        val result = parseTakeoutCsv(csv)

        assertEquals(listOf(ImportChannel(id, "Ada")), result.channels)
        assertEquals(0, result.skipped)
    }

    @Test
    fun `a BOM and CRLF line endings are tolerated`() {
        val csv = "\uFEFF" + header.trimEnd('\n') + "\r\n" +
            "$id,http://www.youtube.com/channel/$id,Ada\r\n" +
            "\r\n" + // a blank line is ignored, not counted as skipped
            "$otherId,http://www.youtube.com/channel/$otherId,Grace\r\n"

        val result = parseTakeoutCsv(csv)

        assertEquals(
            listOf(ImportChannel(id, "Ada"), ImportChannel(otherId, "Grace")),
            result.channels,
        )
        assertEquals(0, result.skipped)
    }

    @Test
    fun `empty and header-only input yield nothing`() {
        assertEquals(ImportResult(emptyList(), 0), parseTakeoutCsv(""))
        assertEquals(ImportResult(emptyList(), 0), parseTakeoutCsv(header))
    }

    // ----- OPML -------------------------------------------------------------

    private fun opml(body: String): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<opml version=\"2.0\"><head><title>Subs</title></head><body>\n" +
            body +
            "\n</body></opml>"

    @Test
    fun `a nested feed outline yields its id and name`() {
        val doc = opml(
            "<outline text=\"Subscriptions\" title=\"Subscriptions\">\n" +
                "  <outline type=\"rss\" text=\"Ada\" title=\"Ada Lovelace\" " +
                "xmlUrl=\"https://www.youtube.com/feeds/videos.xml?channel_id=$id\" />\n" +
                "</outline>",
        )

        val result = parseOpml(doc)

        assertEquals(listOf(ImportChannel(id, "Ada Lovelace")), result.channels)
        assertEquals(0, result.skipped)
    }

    @Test
    fun `a feed without a title falls back to the text attribute`() {
        val doc = opml(
            "<outline type=\"rss\" text=\"Grace Hopper\" " +
                "xmlUrl=\"https://www.youtube.com/feeds/videos.xml?channel_id=$id\" />",
        )

        val result = parseOpml(doc)

        assertEquals(listOf(ImportChannel(id, "Grace Hopper")), result.channels)
    }

    @Test
    fun `extra feed query parameters are trimmed away`() {
        val doc = opml(
            "<outline type=\"rss\" title=\"Ada\" " +
                "xmlUrl=\"https://www.youtube.com/feeds/videos.xml?channel_id=$id&amp;extra=1#top\" />",
        )

        val result = parseOpml(doc)

        assertEquals(listOf(ImportChannel(id, "Ada")), result.channels)
        assertEquals(0, result.skipped)
    }

    @Test
    fun `a non youtube feed is ignored`() {
        val doc = opml("<outline type=\"rss\" title=\"Blog\" xmlUrl=\"https://example.com/feed.xml\" />")

        val result = parseOpml(doc)

        assertTrue(result.channels.isEmpty())
        assertEquals(0, result.skipped)
    }

    @Test
    fun `a youtube feed without a channel id is skipped`() {
        val doc = opml(
            "<outline type=\"rss\" title=\"Old\" " +
                "xmlUrl=\"https://www.youtube.com/feeds/videos.xml?user=legacy\" />",
        )

        val result = parseOpml(doc)

        assertTrue(result.channels.isEmpty())
        assertEquals(1, result.skipped)
    }

    @Test
    fun `malformed xml yields nothing and does not throw`() {
        assertEquals(ImportResult(emptyList(), 0), parseOpml("<opml><body><outline"))
    }

    @Test
    fun `empty input yields nothing`() {
        assertEquals(ImportResult(emptyList(), 0), parseOpml(""))
    }
}
