package dev.wetube.app.data.extras

import dev.wetube.app.data.backend.JsonHttp
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** A single SponsorBlock segment. */
data class SponsorSegment(
    val startMs: Long,
    val endMs: Long,
    val category: String,
    val actionType: String = "skip",
    val uuid: String = "",
)

/**
 * SponsorBlock is a public, crowd-sourced database with a documented API.
 * Nothing is extracted from the YouTube app here.
 */
class SponsorBlockClient(private val http: JsonHttp) {

    suspend fun segments(videoId: String, categories: Set<String>): List<SponsorSegment> {
        if (categories.isEmpty()) return emptyList()
        val cats = categories.joinToString(",") { "\"$it\"" }
        val url = "https://sponsor.ajay.app/api/skipSegments" +
            "?videoID=$videoId&categories=%5B${java.net.URLEncoder.encode(cats, "UTF-8")}%5D"
        return try {
            val list = http.get(url, ListSerializer(SegmentDto.serializer()))
            list.mapNotNull { dto ->
                if (dto.segment.size < 2) return@mapNotNull null
                SponsorSegment(
                    startMs = (dto.segment[0] * 1000).toLong(),
                    endMs = (dto.segment[1] * 1000).toLong(),
                    category = dto.category,
                    actionType = dto.actionType,
                    uuid = dto.UUID,
                )
            }
        } catch (_: Exception) {
            emptyList() // 404 = no segments; treat any failure as "nothing to skip"
        }
    }

    @Serializable
    private data class SegmentDto(
        val UUID: String = "",
        val category: String = "",
        val actionType: String = "skip",
        val segment: List<Double> = emptyList(),
    )
}

/** Return YouTube Dislike: an independent public API for the old dislike count. */
class DislikeClient(private val http: JsonHttp) {

    suspend fun dislikes(videoId: String): Long? = try {
        val v = http.get("https://returnyoutubedislikeapi.com/votes?videoId=$videoId", VoteDto.serializer())
        v.dislikes
    } catch (_: Exception) {
        null
    }

    @Serializable
    private data class VoteDto(
        val likes: Long = 0,
        val dislikes: Long = 0,
        val viewCount: Long = 0,
    )
}
