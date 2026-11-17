package dev.wetube.app.data.backend

import dev.wetube.app.data.model.FeedShelf
import dev.wetube.app.data.model.FeedBlock
import dev.wetube.app.data.model.VideoItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins how one answer becomes a page of blocks.
 *
 * The three shapes a real account actually returns are covered: a first page
 * (`richGridRenderer.contents`), a chip switch
 * (`reloadContinuationItemsCommand.continuationItems`) and a next page
 * (`appendContinuationItemsAction.continuationItems`). A search says the same
 * thing with different wrappers, so its first page and its continuation are
 * covered too, along with the three shelf shapes either can carry. The card
 * reader is injected, because turning a `lockupViewModel` into a [VideoItem]
 * needs the client and region; these tests are about order, shelves and tokens
 * instead.
 *
 * Every fixture is hand-written and neutral — nothing here was captured from an
 * account, and no fixture carries a real id or title.
 */
class FeedParserTest {

    private fun at(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    /** Reads the fabricated card shape `{"card": {"id": ...}}`. */
    private val card: (JsonObject) -> VideoItem? = { entry ->
        entry["card"].obj()?.let { c ->
            VideoItem(
                id = c["id"].text().orEmpty(),
                title = c["title"].text().orEmpty(),
                channelName = "Sample channel",
            )
        }
    }

    private fun cardJson(id: String) =
        """{"card":{"id":"$id","title":"Sample video $id"}}"""

    private fun row(id: String) = """{"richItemRenderer":{"content":${cardJson(id)}}}"""

    private fun card(id: String) =
        VideoItem(id = id, title = "Sample video $id", channelName = "Sample channel")

    private fun ids(blocks: List<FeedBlock>) = blocks.flatMap { it.videos }.map { it.id }

    private fun shelfJson(title: String, vararg items: String, icon: String? = null) = """
        {"richSectionRenderer":{"content":{"richShelfRenderer":{
          "title":{"runs":[{"text":"$title"}]},
          ${if (icon == null) "" else """"icon":{"iconType":"$icon"},"""}
          "contents":[${items.joinToString(",") { row(it) }}]
        }}}}
    """.trimIndent()

    /** A search groups its cards in an `itemSectionRenderer`; that wrapper is flattened away. */
    private fun section(vararg entries: String) =
        """{"itemSectionRenderer":{"contents":[${entries.joinToString(",")}]}}"""

    /**
     * The web's own shelf, which is what a search puts its Shorts rows in.
     *
     * [shorts] adds the brand icon; the title is deliberately free-form, because
     * a real answer titles these rows after the channel ("Latest Shorts from
     * ...") as often as it titles them "Shorts".
     */
    private fun gridShelfJson(title: String, vararg items: String, shorts: Boolean = true): String {
        val icon = if (shorts) {
            """,
              "leadingAccessory":{"image":{"sources":[
                {"clientResource":{"imageName":"YOUTUBE_SHORTS_BRAND_24"}}]}}"""
        } else {
            ""
        }
        return """
            {"gridShelfViewModel":{
              "contents":[${items.joinToString(",") { cardJson(it) }}],
              "header":{"sectionHeaderViewModel":{
                "headline":{"content":"$title"}$icon
              }}
            }}
        """.trimIndent()
    }

    /**
     * A search's other shelf: the older `shelfRenderer`, whose cards sit one
     * level down inside whichever list renderer holds them.
     */
    private fun plainShelfJson(title: String, vararg items: String) = """
        {"shelfRenderer":{
          "title":{"simpleText":"$title"},
          "content":{"verticalListRenderer":{"items":[
            ${items.joinToString(",") { cardJson(it) }}
          ]}}
        }}
    """.trimIndent()

    /** A shelf of posts, which this app has nothing to play. */
    private fun postsShelfJson(title: String) = """
        {"shelfRenderer":{
          "title":{"simpleText":"$title"},
          "content":{"horizontalListRenderer":{"items":[
            {"postRenderer":{"postId":"sample-post"}}
          ]}}
        }}
    """.trimIndent()

    private fun bannerJson() = """
        {"richSectionRenderer":{"content":{"statementBannerRenderer":{
          "title":{"simpleText":"A statement"},
          "description":{"simpleText":"Something to say"}
        }}}}
    """.trimIndent()

    private fun playablesJson() = """
        {"richSectionRenderer":{"content":{"richShelfRenderer":{
          "title":{"runs":[{"text":"Playables"}]},
          "contents":[{"richItemRenderer":{"content":{"miniGameCardViewModel":{
            "gameId":"sample-game","title":{"simpleText":"Sample game"}
          }}}}]
        }}}}
    """.trimIndent()

    private fun terminatorJson(token: String) = """
        {"continuationItemRenderer":{"trigger":"CONTINUATION_TRIGGER_ON_ITEM_SHOWN",
         "continuationEndpoint":{"continuationCommand":{"token":"$token","request":"CONTINUATION_REQUEST_TYPE_BROWSE"}}}}
    """.trimIndent()

    // ---- where a page's entries come from -------------------------------

    @Test
    fun `a first page reads its entries from the grid`() {
        val body = at(
            """{"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[
                 {"tabRenderer":{"content":{"richGridRenderer":{"contents":[${row("a")},${row("b")}]}}}}
               ]}}}""",
        )

        val entries = feedEntries(body)

        assertEquals(2, entries.size)
        assertEquals(listOf("a", "b"), feedBlocks(entries, card).map { (it as FeedBlock.Video).video.id })
    }

    @Test
    fun `a chip switch reads its entries from the reload action`() {
        // A chip answer replaces the page, so it arrives as a reload command.
        val body = at(
            """{"onResponseReceivedActions":[{"reloadContinuationItemsCommand":{
                 "targetId":"FEwhat_to_watch","continuationItems":[${row("a")},${row("b")}]
               }}]}""",
        )

        assertEquals(listOf("a", "b"), ids(feedBlocks(feedEntries(body), card)))
    }

    @Test
    fun `a next page reads its entries from the append action`() {
        // A next page only adds to the page, so it arrives as an append command.
        val body = at(
            """{"onResponseReceivedActions":[{"appendContinuationItemsAction":{
                 "continuationItems":[${row("c")},${row("d")}]
               }}]}""",
        )

        assertEquals(listOf("c", "d"), ids(feedBlocks(feedEntries(body), card)))
    }

    @Test
    fun `a page with no grid and no action has no entries`() {
        assertTrue(feedEntries(at("""{"responseContext":{}}""")).isEmpty())
    }

    // ---- a search answer -------------------------------------------------

    @Test
    fun `a search first page is flattened out of its sections`() {
        // A search groups its results in `itemSectionRenderer`s; the grouping is
        // transport detail, so the entries are the cards in the order sent.
        val body = at(
            """{"contents":{"twoColumnSearchResultsRenderer":{"primaryContents":{"sectionListRenderer":{
                 "contents":[${section(row("a"), gridShelfJson("Shorts", "s1", "s2"), row("b"))},
                             ${terminatorJson("more")}]
               }}}}}""",
        )

        val entries = searchEntries(body)

        assertEquals(4, entries.size)
        assertEquals(listOf("a", "s1", "s2", "b"), ids(feedBlocks(entries, card)))
        assertEquals("more", feedNextToken(entries))
    }

    @Test
    fun `a search continuation is read from its append command`() {
        // A search continues under `onResponseReceivedCommands`, not under the
        // `onResponseReceivedActions` a browse answer uses.
        val body = at(
            """{"onResponseReceivedCommands":[{"appendContinuationItemsAction":{
                 "continuationItems":[${section(row("c"), gridShelfJson("Shorts", "s3"))},
                                      ${terminatorJson("page-2")}]
               }}]}""",
        )

        val entries = searchEntries(body)

        assertEquals(listOf("c", "s3"), ids(feedBlocks(entries, card)))
        assertEquals("page-2", feedNextToken(entries))
    }

    @Test
    fun `a search page with no sections has no entries`() {
        assertTrue(searchEntries(at("""{"responseContext":{}}""")).isEmpty())
    }

    @Test
    fun `a search keeps a channel's video shelf between its rows`() {
        // A channel's "Latest from ..." shelf is a `shelfRenderer`; dropping it
        // would silently hide ten videos the search did return.
        val body = at(
            """{"contents":{"twoColumnSearchResultsRenderer":{"primaryContents":{"sectionListRenderer":{
                 "contents":[${section(
                row("a"),
                plainShelfJson("Latest from a channel", "v1", "v2"),
                row("b"),
            )},${terminatorJson("more")}]
               }}}}}""",
        )

        val blocks = feedBlocks(searchEntries(body), card)

        assertEquals(3, blocks.size)
        assertEquals(listOf("a", "v1", "v2", "b"), ids(blocks))
        val shelf = (blocks[1] as FeedBlock.Shelf).shelf
        assertEquals("Latest from a channel", shelf.title)
        assertFalse(shelf.isShorts)
    }

    @Test
    fun `a search shelf of posts becomes no block at all`() {
        // A posts shelf holds nothing this app can play, so it is dropped rather
        // than left on the page as an empty row.
        val body = at(
            """{"onResponseReceivedCommands":[{"appendContinuationItemsAction":{"continuationItems":[
                 ${section(postsShelfJson("Posts"), row("c"))}
               ]}}]}""",
        )

        val blocks = feedBlocks(searchEntries(body), card)

        assertEquals(listOf("c"), ids(blocks))
        assertTrue(blocks.single() is FeedBlock.Video)
    }

    // ---- the page as blocks, in feed order ------------------------------

    @Test
    fun `rows and shelves keep the order the server sent`() {
        val body = at(
            """{"continuationItems":[${row("a")},${shelfJson("A shelf", "b", "c")},${row("d")}]}""",
        )

        val blocks = feedBlocks(feedEntries(body), card)

        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is FeedBlock.Video)
        assertTrue(blocks[1] is FeedBlock.Shelf)
        assertTrue(blocks[2] is FeedBlock.Video)
        assertEquals(listOf("a", "b", "c", "d"), ids(blocks))
        assertEquals("A shelf", (blocks[1] as FeedBlock.Shelf).shelf.title)
    }

    @Test
    fun `a video repeated in a later shelf is shown once`() {
        // A shelf may re-feature a video that already has its own row, and two
        // list entries with the same key would crash the feed.
        val body = at("""{"continuationItems":[${row("a")},${shelfJson("A shelf", "a", "b")}]}""")

        val blocks = feedBlocks(feedEntries(body), card)

        assertEquals(listOf("a", "b"), ids(blocks))
        assertEquals(listOf("b"), (blocks[1] as FeedBlock.Shelf).shelf.items.map { it.id })
    }

    @Test
    fun `a shelf whose cards are all repeats is dropped`() {
        val body = at("""{"continuationItems":[${row("a")},${shelfJson("A shelf", "a")}]}""")

        val blocks = feedBlocks(feedEntries(body), card)

        assertEquals(1, blocks.size)
        assertTrue(blocks.single() is FeedBlock.Video)
    }

    @Test
    fun `a shelf of cards this app cannot read is dropped`() {
        val body = at("""{"continuationItems":[${playablesJson()},${row("a")}]}""")

        val blocks = feedBlocks(feedEntries(body), card)

        assertEquals(listOf("a"), ids(blocks))
        assertTrue(blocks.single() is FeedBlock.Video)
    }

    @Test
    fun `a statement banner and the terminator become no block at all`() {
        val body = at("""{"continuationItems":[${bannerJson()},${terminatorJson("next")}]}""")

        assertTrue(feedBlocks(feedEntries(body), card).isEmpty())
    }

    // ---- Shorts is decided by the icon, never by the title --------------

    @Test
    fun `a shelf carrying the Shorts brand icon is a Shorts shelf`() {
        val entry = at(shelfJson("Shorts", "a", icon = "YOUTUBE_SHORTS_BRAND_24"))

        val shelf = feedShelf(entry, card)

        assertTrue(shelf!!.isShorts)
        assertEquals(listOf("a"), shelf.items.map { it.id })
    }

    @Test
    fun `a shelf titled Shorts without the icon is an ordinary shelf`() {
        // Titles are localised; the icon is not.
        val entry = at(shelfJson("Shorts", "a"))

        assertFalse(feedShelf(entry, card)!!.isShorts)
    }

    @Test
    fun `a shelf of some other brand is not a Shorts shelf`() {
        val entry = at(shelfJson("Something", "a", icon = "PLAYABLES_BRAND_24"))

        assertFalse(feedShelf(entry, card)!!.isShorts)
    }

    @Test
    fun `a grid shelf carries its title and its brand icon`() {
        // This is the shape a search puts its Shorts rows in, and the title is
        // the channel's own wording rather than the word "Shorts".
        val entry = at(gridShelfJson("Latest Shorts from a channel", "s1", "s2"))

        val shelf = feedShelf(entry, card)!!

        assertEquals("Latest Shorts from a channel", shelf.title)
        assertTrue(shelf.isShorts)
        assertEquals(listOf("s1", "s2"), shelf.items.map { it.id })
    }

    @Test
    fun `a grid shelf without the brand icon is an ordinary shelf`() {
        val entry = at(gridShelfJson("Latest from a channel", "v1", shorts = false))

        val shelf = feedShelf(entry, card)!!

        assertEquals("Latest from a channel", shelf.title)
        assertFalse(shelf.isShorts)
        assertEquals(listOf("v1"), shelf.items.map { it.id })
    }

    @Test
    fun `a plain shelf reads the cards out of its list renderer`() {
        val entry = at(plainShelfJson("Latest from a channel", "v1", "v2"))

        val shelf = feedShelf(entry, card)!!

        assertEquals("Latest from a channel", shelf.title)
        assertFalse(shelf.isShorts)
        assertEquals(listOf("v1", "v2"), shelf.items.map { it.id })
    }

    @Test
    fun `a shelf with nothing readable is no shelf`() {
        assertNull(feedShelf(at(postsShelfJson("Posts")), card))
    }

    @Test
    fun `a bare card is not a shelf and a bare shelf is not a card`() {
        assertNull(feedShelf(at(row("a")), card))
        assertNull(feedEntryCard(at(shelfJson("A shelf", "a")), card))
    }

    // ---- the chip bar ---------------------------------------------------

    @Test
    fun `chips keep their titles, tokens and selection`() {
        val body = at(
            """{"header":{"feedFilterChipBarRenderer":{"contents":[
                 {"chipCloudChipRenderer":{"text":{"runs":[{"text":"All"}]},"isSelected":true}},
                 {"chipCloudChipRenderer":{"text":{"runs":[{"text":"Music"}]},
                  "navigationEndpoint":{"continuationCommand":{"token":"chip-music"}}}}
               ]}}}""",
        )

        val chips = feedChips(body)

        assertEquals(listOf("All", "Music"), chips.map { it.title })
        assertTrue(chips[0].selected)
        assertNull(chips[0].token)
        assertFalse(chips[1].selected)
        assertEquals("chip-music", chips[1].token)
    }

    @Test
    fun `a page without a bar has no chips`() {
        // Continuation answers carry no bar: the bar belongs to the account.
        assertTrue(feedChips(at("""{"continuationItems":[]}""")).isEmpty())
    }

    // ---- the next-page token --------------------------------------------

    @Test
    fun `the next token comes from the terminator, not from another command`() {
        // The page is full of other continuation commands; resuming from one of
        // those would replace the feed instead of continuing it.
        val other = """
            {"richItemRenderer":{"content":{"lockupViewModel":{"contentId":"a",
             "rendererContext":{"commandContext":{"onTap":{"innertubeCommand":
              {"continuationCommand":{"token":"some-other-token"}}}}}}}}}
        """.trimIndent()
        val entries = feedEntries(
            at("""{"continuationItems":[$other,${row("b")},${terminatorJson("next-page")}]}"""),
        )

        assertEquals("next-page", feedNextToken(entries))
    }

    @Test
    fun `the terminator is looked for at the end of the page`() {
        val entries = feedEntries(
            at("""{"continuationItems":[${row("a")},${terminatorJson("next-page")}]}"""),
        )

        assertEquals("next-page", feedNextToken(entries))
    }

    @Test
    fun `a page with no terminator ends the feed`() {
        val entries = feedEntries(at("""{"continuationItems":[${row("a")},${row("b")}]}"""))

        assertNull(feedNextToken(entries))
    }

    @Test
    fun `a terminator with a blank token ends the feed`() {
        val entries = feedEntries(at("""{"continuationItems":[${terminatorJson("")}]}"""))

        assertNull(feedNextToken(entries))
    }

    // ---- the page as one object -----------------------------------------

    @Test
    fun `a page's videos are its blocks' videos without repeats`() {
        val page = dev.wetube.app.data.model.HomePage(
            blocks = listOf(
                FeedBlock.Video(card("a")),
                FeedBlock.Shelf(FeedShelf("A shelf", listOf(card("a"), card("b")))),
            ),
        )

        assertEquals(listOf("a", "b"), page.videos.map { it.id })
    }
}