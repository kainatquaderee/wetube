package dev.wetube.app.data.backend

import java.util.Base64

/**
 * The search screen's filter selection, stated the way YouTube's search endpoint
 * states it.
 *
 * The endpoint takes one opaque `params` string instead of named arguments: a
 * protobuf message whose field 1 is the sort order and whose field 2 is a nested
 * message of filters. This model is that same tree expressed as types, so the
 * screen never builds bytes and the encoding is testable without a network
 * stack: [toParams] is the only place that knows the wire form.
 *
 * Every default here is YouTube's "not selected" (a zero wire value), which is
 * why an untouched selection encodes to the empty string — the identical request
 * to a search that carries no params at all.
 */
data class SearchFilters(
    val type: SearchType = SearchType.ALL,
    val duration: SearchDuration = SearchDuration.ANY,
    val uploadDate: UploadDate = UploadDate.ANY_TIME,
    val sort: SearchSort = SearchSort.RELEVANCE,
    val features: Set<SearchFeature> = emptySet(),
) {

    /**
     * How many choices narrow the results, features counted one each.
     *
     * The search screen shows this above the results so a filtered list never
     * looks like an unfiltered one. It counts rather than describes because the
     * selection is long (four dropdowns plus toggles) and the row has one line.
     */
    val activeCount: Int
        get() = listOf(
            type != SearchType.ALL,
            duration != SearchDuration.ANY,
            uploadDate != UploadDate.ANY_TIME,
            sort != SearchSort.RELEVANCE,
        ).count { it } + features.size

    /** The endpoint's `params` value for this selection; empty when nothing is on. */
    fun toParams(): String = searchParamsFor(this)
}

/**
 * Content type, numbered by YouTube (`filters.type`).
 *
 * `ALL` is zero because an omitted field is what asks for every type — the same
 * reason nothing else here lists "Any" twice.
 *
 * Only All and Video are modelled. YouTube also has Channel (2), Playlist (3) and
 * Movie (4), but a search for any of those answers with renderers this app's
 * result list cannot draw — it builds video cards only — so offering them would
 * turn a supported-looking tap into a permanent "No results". Adding one back is
 * a constant here; the encoding needs no change.
 */
enum class SearchType(val wireValue: Int) {
    ALL(0),
    VIDEO(1),
}

/**
 * Video length, numbered by YouTube (`filters.duration`).
 *
 * The numbering is not the menu's order: under four minutes is 1, *over* twenty
 * minutes is 2, and 4–20 minutes is 3. The declaration order below follows the
 * menu, so the values are spelled out rather than derived from [ordinal].
 */
enum class SearchDuration(val wireValue: Int) {
    ANY(0),
    UNDER_FOUR_MINUTES(1),
    FOUR_TO_TWENTY_MINUTES(3),
    OVER_TWENTY_MINUTES(2),
}

/**
 * Upload window, numbered by YouTube (`filters.uploadDate`).
 *
 * 1 means "last hour", which this app deliberately does not offer: it is of no
 * use on a result list that a viewer reads rather than watches live.
 */
enum class UploadDate(val wireValue: Int) {
    ANY_TIME(0),
    TODAY(2),
    THIS_WEEK(3),
    THIS_MONTH(4),
    THIS_YEAR(5),
}

/**
 * Result order (the message's field 1).
 *
 * Relevance is the endpoint's default and therefore zero. Rating is 1 and upload
 * date is 2 in YouTube's own numbering, which is why neither is read off the
 * declaration order.
 */
enum class SearchSort(val wireValue: Int) {
    RELEVANCE(0),
    UPLOAD_DATE(2),
    VIEW_COUNT(3),
    RATING(1),
}

/**
 * The toggles under "Features".
 *
 * Each feature is a *separate* boolean field in YouTube's filter message, not a
 * bit inside one value: [field] is that field's number, and each enabled feature
 * is written as its own `1`. Only the four this app offers are modelled;
 * YouTube's message also has 3D (7), purchased (9), 360° (15), location (23),
 * HDR (25) and VR180 (26), which are left out on purpose.
 */
enum class SearchFeature(val field: Int) {
    FOUR_K(14),
    HD(4),
    SUBTITLES(5),
    LIVE(8),
}

/**
 * Encodes [filters] into the `params` value YouTube's `search` endpoint takes.
 *
 * The message is `1: sort, 2: { 1: upload date, 2: type, 3: duration, 4/5/8/14:
 * features }`, written the way protobuf writes it: fields in ascending field
 * number, a zero value omitted (it is the default), the nested message
 * length-prefixed. The result is base64 of those bytes and nothing else — no
 * percent-encoding, because the value travels in a JSON body rather than in a
 * URL; it is the same bytes the web client sends after decoding the `sp` it put
 * in its address bar, which is where every number above comes from.
 *
 * Top-level (not a member) so a unit test can pin the bytes without a network
 * stack, the way the reel sequence seed already is.
 */
internal fun searchParamsFor(filters: SearchFilters): String {
    val nested = mutableListOf<Byte>()
    nested.writeUInt(1, filters.uploadDate.wireValue)
    nested.writeUInt(2, filters.type.wireValue)
    nested.writeUInt(3, filters.duration.wireValue)
    // A set has no order of its own, so the features are written in field order:
    // the same selection must always produce the same string.
    filters.features.map { it.field }.sorted().forEach { nested.writeUInt(it, 1) }

    val message = mutableListOf<Byte>()
    message.writeUInt(1, filters.sort.wireValue)
    if (nested.isNotEmpty()) message.writeMessage(2, nested)
    return Base64.getEncoder().encodeToString(message.toByteArray())
}

/** Writes a protobuf varint. All values written here are non-negative. */
private fun MutableList<Byte>.writeVarint(value: Int) {
    var rest = value
    while (rest > 0x7f) {
        add(((rest and 0x7f) or 0x80).toByte())
        rest = rest ushr 7
    }
    add(rest.toByte())
}

/**
 * Writes one varint field, and nothing at all when [value] is zero.
 *
 * Omitting the default is what makes "no filters" the empty string instead of a
 * message full of zeroes.
 */
private fun MutableList<Byte>.writeUInt(field: Int, value: Int) {
    if (value == 0) return
    writeVarint(field shl 3)
    writeVarint(value)
}

/** Writes a length-delimited field carrying a nested message ([field]'s body). */
private fun MutableList<Byte>.writeMessage(field: Int, body: List<Byte>) {
    writeVarint((field shl 3) or 0x02)
    writeVarint(body.size)
    addAll(body)
}