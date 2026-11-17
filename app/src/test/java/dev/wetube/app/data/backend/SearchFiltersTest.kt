package dev.wetube.app.data.backend

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Base64

/**
 * Pins the search `params` encoding against bytes YouTube itself handed out.
 *
 * Each expected string below is the `sp` the search page produces for that one
 * choice, as recorded by Invidious in `spec/invidious/search/yt_filters_spec.cr`
 * ("extracted from the search page of Youtube with any browser devtools HTML
 * inspector"), with the URL escaping removed. Comparing against captured values
 * rather than against our own output is the point: it says the bytes match a real
 * client instead of matching yesterday's encoder.
 *
 * No captured string exists for a combination in this app's own feature set, so
 * combinations are pinned structurally — decoded bytes, in field order — and the
 * captured single-value strings do the cross-checking of every number.
 */
class SearchFiltersTest {

    private val capturedSingles = listOf(
        SearchFilters(sort = SearchSort.RATING) to "CAE=",
        SearchFilters(sort = SearchSort.UPLOAD_DATE) to "CAI=",
        SearchFilters(sort = SearchSort.VIEW_COUNT) to "CAM=",
        SearchFilters(uploadDate = UploadDate.TODAY) to "EgIIAg==",
        SearchFilters(uploadDate = UploadDate.THIS_WEEK) to "EgIIAw==",
        SearchFilters(uploadDate = UploadDate.THIS_MONTH) to "EgIIBA==",
        SearchFilters(uploadDate = UploadDate.THIS_YEAR) to "EgIIBQ==",
        SearchFilters(type = SearchType.VIDEO) to "EgIQAQ==",
        SearchFilters(duration = SearchDuration.UNDER_FOUR_MINUTES) to "EgIYAQ==",
        SearchFilters(duration = SearchDuration.FOUR_TO_TWENTY_MINUTES) to "EgIYAw==",
        SearchFilters(duration = SearchDuration.OVER_TWENTY_MINUTES) to "EgIYAg==",
        SearchFilters(features = setOf(SearchFeature.HD)) to "EgIgAQ==",
        SearchFilters(features = setOf(SearchFeature.SUBTITLES)) to "EgIoAQ==",
        SearchFilters(features = setOf(SearchFeature.LIVE)) to "EgJAAQ==",
        SearchFilters(features = setOf(SearchFeature.FOUR_K)) to "EgJwAQ==",
    )

    @Test
    fun `no filters encode to no params`() {
        // The empty string is what a search without filters sends; it is also why
        // the backend can pass the result through unconditionally.
        assertEquals("", SearchFilters().toParams())
        assertEquals(0, SearchFilters().activeCount)
    }

    @Test
    fun `a single filter encodes to its captured value`() {
        capturedSingles.forEach { (filters, expected) ->
            assertEquals("$filters", expected, filters.toParams())
        }
    }

    @Test
    fun `a single filter sets only its own field`() {
        // The sort is a top-level field, every other choice lives inside the
        // nested message, so a lone selection is exactly one tag and its value.
        assertEquals(listOf(0x08, 0x02), single(SearchFilters(sort = SearchSort.UPLOAD_DATE)))
        assertEquals(
            listOf(0x12, 0x02, 0x08, 0x03),
            single(SearchFilters(uploadDate = UploadDate.THIS_WEEK)),
        )
        assertEquals(listOf(0x12, 0x02, 0x10, 0x01), single(SearchFilters(type = SearchType.VIDEO)))
        assertEquals(
            listOf(0x12, 0x02, 0x18, 0x01),
            single(SearchFilters(duration = SearchDuration.UNDER_FOUR_MINUTES)),
        )
        assertEquals(
            listOf(0x12, 0x02, 0x20, 0x01),
            single(SearchFilters(features = setOf(SearchFeature.HD))),
        )
    }

    @Test
    fun `sort is a top-level field ahead of the nested filters`() {
        // Field 1 outside, field 2 carrying the rest: the order matters, because
        // protobuf writers emit ascending field numbers.
        assertEquals(
            listOf(0x08, 0x01, 0x12, 0x02, 0x10, 0x01),
            single(SearchFilters(sort = SearchSort.RATING, type = SearchType.VIDEO)),
        )
    }

    @Test
    fun `combinations compose from their parts`() {
        val combined = SearchFilters(
            type = SearchType.VIDEO,
            duration = SearchDuration.FOUR_TO_TWENTY_MINUTES,
            uploadDate = UploadDate.THIS_MONTH,
            features = setOf(SearchFeature.SUBTITLES, SearchFeature.LIVE),
        )
        // Listed in field order, not menu order, because that is how the nested
        // message is written: upload date (1), type (2), duration (3), then the
        // feature fields as they are numbered — subtitles (5) before live (8).
        val parts = listOf(
            SearchFilters(uploadDate = UploadDate.THIS_MONTH),
            SearchFilters(type = SearchType.VIDEO),
            SearchFilters(duration = SearchDuration.FOUR_TO_TWENTY_MINUTES),
            SearchFilters(features = setOf(SearchFeature.SUBTITLES)),
            SearchFilters(features = setOf(SearchFeature.LIVE)),
        )
        assertEquals(parts.flatMap { nested(it.toParams()) }, nested(combined.toParams()))
        assertEquals("EgoIBBABGAMoAUAB", combined.toParams())
    }

    @Test
    fun `a combination with a sort puts the sort first and composes the rest`() {
        val combined = SearchFilters(
            type = SearchType.VIDEO,
            duration = SearchDuration.FOUR_TO_TWENTY_MINUTES,
            uploadDate = UploadDate.THIS_MONTH,
            sort = SearchSort.VIEW_COUNT,
            features = setOf(SearchFeature.SUBTITLES, SearchFeature.LIVE),
        )
        assertEquals("CAMSCggEEAEYAygBQAE=", combined.toParams())
        assertEquals(
            listOf(0x08, 0x03),
            bytes(combined.toParams()).take(2),
        )
    }

    @Test
    fun `every choice at once keeps field order and drops nothing`() {
        // The widest selection the screen can make, with the feature set given in
        // an order that must not leak into the bytes.
        val combined = SearchFilters(
            type = SearchType.VIDEO,
            duration = SearchDuration.OVER_TWENTY_MINUTES,
            uploadDate = UploadDate.TODAY,
            sort = SearchSort.RATING,
            features = linkedSetOf(
                SearchFeature.FOUR_K,
                SearchFeature.LIVE,
                SearchFeature.SUBTITLES,
                SearchFeature.HD,
            ),
        )
        assertEquals("CAESDggCEAEYAiABKAFAAXAB", combined.toParams())
        assertEquals(4 + 4, combined.activeCount)
    }

    @Test
    fun `features encode in the same order whatever the set iterates`() {
        val forwards = SearchFilters(features = linkedSetOf(SearchFeature.HD, SearchFeature.FOUR_K))
        val backwards =
            SearchFilters(features = linkedSetOf(SearchFeature.FOUR_K, SearchFeature.HD))
        assertEquals(forwards.toParams(), backwards.toParams())
        assertEquals("EgQgAXAB", forwards.toParams())
    }

    @Test
    fun `the count follows what is selected`() {
        val none = SearchFilters()
        assertEquals(0, none.activeCount)
        assertEquals(1, none.copy(type = SearchType.VIDEO).activeCount)
        assertEquals(
            2,
            none.copy(uploadDate = UploadDate.TODAY, sort = SearchSort.RATING).activeCount,
        )
        assertEquals(
            3,
            none.copy(
                duration = SearchDuration.OVER_TWENTY_MINUTES,
                features = setOf(SearchFeature.HD, SearchFeature.LIVE),
            ).activeCount,
        )
    }

    /** The decoded bytes, as unsigned ints so they compare readably in failures. */
    private fun bytes(params: String): List<Int> =
        Base64.getDecoder().decode(params).map { it.toInt() and 0xff }

    private fun single(filters: SearchFilters): List<Int> = bytes(filters.toParams())

    /**
     * The nested filter message inside [params], or an empty list when the
     * selection is empty.
     *
     * Only the two shapes this encoder can produce are read: an optional varint
     * sort first, then one length-delimited field 2. Both are single-byte tags
     * here because every value this model can emit is small.
     */
    private fun nested(params: String): List<Int> {
        val all = bytes(params)
        val at = all.indexOf(0x12)
        if (at < 0 || at + 1 >= all.size) return emptyList()
        val length = all[at + 1]
        return all.subList(at + 2, at + 2 + length)
    }
}