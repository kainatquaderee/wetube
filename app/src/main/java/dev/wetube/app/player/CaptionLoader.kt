package dev.wetube.app.player

import dev.wetube.app.data.backend.JsonHttp
import dev.wetube.app.data.model.CaptionTrack
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.net.URLEncoder

/** One line of subtitles, already flattened to plain text. */
data class CaptionCue(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

/**
 * Downloads and parses YouTube's timed-text tracks.
 *
 * The track URL is signed by the player response and is the same one the web
 * player uses. `fmt=json3` gives a tiny JSON document instead of XML, and
 * `tlang=<code>` makes YouTube return its machine translation of the track —
 * that is the "auto-translated captions" feature of the official app, without
 * any third-party translation service.
 */
class CaptionLoader(private val http: JsonHttp) {

    suspend fun load(track: CaptionTrack, translateTo: String = ""): List<CaptionCue> {
        val url = trackUrl(track.baseUrl, translateTo)
        val body = http.getText(
            url,
            mapOf(
                "User-Agent" to
                    "com.google.android.youtube/21.02.35 (Linux; U; Android 11) gzip",
                "Accept-Language" to "en-US,en;q=0.9",
            ),
        )
        if (body.isBlank()) throw IOException("Empty caption track")
        val cues = parseJson(body) ?: parseXml(body)
        if (cues.isNullOrEmpty()) throw IOException("No captions in this track")
        return cues
    }

    private fun trackUrl(baseUrl: String, translateTo: String): String {
        // The signed URL already ends in fmt=srv3; asking for JSON means
        // replacing that value instead of appending a second fmt parameter
        // (YouTube honours the first one it sees).
        val withFormat = if (FMT.containsMatchIn(baseUrl)) {
            baseUrl.replace(FMT) { match -> "${match.groupValues[1]}fmt=json3" }
        } else {
            baseUrl + if ('?' in baseUrl) "&fmt=json3" else "?fmt=json3"
        }
        if (translateTo.isBlank()) return withFormat
        val code = URLEncoder.encode(translateTo, "UTF-8")
        return "$withFormat&tlang=$code"
    }

    private fun parseJson(body: String): List<CaptionCue>? {
        val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: return null
        val events = root["events"] as? JsonArray ?: return emptyList()
        val cues = ArrayList<CaptionCue>(events.size)
        events.forEach event@{ element ->
            val event = element as? JsonObject ?: return@event
            val start = (event["tStartMs"] as? JsonPrimitive)?.longOrNull ?: return@event
            val duration = (event["dDurationMs"] as? JsonPrimitive)?.longOrNull ?: 0L
            val segments = event["segs"] as? JsonArray ?: return@event
            val text = buildString {
                segments.forEach seg@{ segment ->
                    val seg = segment as? JsonObject ?: return@seg
                    if ((seg["aAppend"] as? JsonPrimitive)?.longOrNull == 1L && isNotEmpty()) {
                        append('\n')
                    }
                    append((seg["utf8"] as? JsonPrimitive)?.contentOrNull.orEmpty())
                }
            }.cleanCaption()
            if (text.isNotBlank()) {
                cues.add(CaptionCue(start, start + duration.coerceAtLeast(0L), text))
            }
        }
        return fillMissingEnds(cues)
    }

    private fun parseXml(body: String): List<CaptionCue>? {
        val pattern = Regex(
            """<text[^>]*\bstart="([\d.]+)"[^>]*\b(?:dur="([\d.]+)")?[^>]*>(.*?)</text>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        val cues = pattern.findAll(body).mapNotNull { match ->
            val start = (match.groupValues[1].toDoubleOrNull() ?: return@mapNotNull null)
            val duration = match.groupValues[2].toDoubleOrNull() ?: 0.0
            val text = match.groupValues[3]
                .replace(Regex("<[^>]+>"), " ")
                .cleanCaption()
            if (text.isBlank()) null else {
                CaptionCue((start * 1000).toLong(), ((start + duration) * 1000).toLong(), text)
            }
        }.toList()
        return fillMissingEnds(cues)
    }

    /** A missing duration should run until the next line starts, not to zero. */
    private fun fillMissingEnds(cues: List<CaptionCue>): List<CaptionCue> {
        if (cues.isEmpty()) return cues
        return cues.mapIndexed { index, cue ->
            if (cue.endMs > cue.startMs) return@mapIndexed cue
            val next = cues.getOrNull(index + 1)?.startMs ?: (cue.startMs + 4_000)
            cue.copy(endMs = next.coerceAtLeast(cue.startMs + 500))
        }
    }

    private fun String.cleanCaption(): String = this
        .replace("\r", "")
        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&nbsp;", " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private companion object {
        val FMT = Regex("([?&])fmt=[^&]*")
    }
}
