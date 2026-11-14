package dev.wetube.app.data.backend

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import java.io.IOException

/** Small JSON-over-HTTP helper shared by the backends. */
class JsonHttp(
    private val client: OkHttpClient,
    val json: Json,
) {

    suspend fun <T> get(
        url: String,
        deserializer: DeserializationStrategy<T>,
        headers: Map<String, String> = emptyMap(),
    ): T = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .header("Accept", "application/json")
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.readCapped().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} for $url: ${body.take(180)}")
            }
            if (body.isBlank()) throw IOException("Empty response from $url")
            json.decodeFromString(deserializer, body)
        }
    }

    suspend fun getText(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
            response.body?.readCapped().orEmpty()
        }
    }

    suspend fun postJson(
        url: String,
        body: JsonObject,
        headers: Map<String, String> = emptyMap(),
    ): JsonObject = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.readCapped().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} for $url: ${text.take(200)}")
            }
            json.parseToJsonElement(text) as? JsonObject
                ?: throw IOException("Expected a JSON object from $url")
        }
    }

    /**
     * Like [postJson], but returns the status code and body text for *any*
     * status instead of throwing on non-2xx. Only a transport failure
     * (socket/DNS/timeout) throws [IOException], because [Request.Builder]
     * construction and [okhttp3.Call.execute] are the only steps that can fail
     * without an HTTP answer.
     *
     * Sync needs the code and the body *together*, because a sign-in wall is a
     * property of the response body (a 2xx can still ask for a session), not of
     * the status code alone. Letting [postJson] throw on a 401 would discard the
     * body that says *why* the session was refused, so the sync transport reads
     * both explicitly and classifies them itself. The existing methods are left
     * untouched so the feed paths keep their "throw on failure" contract.
     */
    suspend fun postJsonRaw(
        url: String,
        body: JsonObject,
        headers: Map<String, String> = emptyMap(),
    ): Pair<Int, String> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()

        client.newCall(request).execute().use { response ->
            response.code to response.body?.readCapped().orEmpty()
        }
    }

    /**
     * Reads a whole response body while bounding how much can be materialised
     * into the heap. The cap is enforced by pulling bytes through the buffered
     * source rather than trusting [ResponseBody.contentLength], which is `-1`
     * for a chunked response and can therefore never be used on its own to
     * reject an oversized body.
     *
     * Requesting one byte past the cap either fills the buffer past it (too
     * large → throw) or reaches EOF, so a complete body shorter than the cap is
     * returned in full by [okio.BufferedSource.readUtf8].
     */
    private fun ResponseBody.readCapped(): String {
        val source = source()
        source.request(MAX_BODY_BYTES + 1)
        if (source.buffer.size > MAX_BODY_BYTES) throw IOException("response too large")
        return source.readUtf8()
    }

    private companion object {
        /**
         * Hard cap on a response body read into memory. Every sync body is a
         * small JSON envelope; anything past 8 MiB is abuse, not data.
         */
        const val MAX_BODY_BYTES = 8L * 1024 * 1024
    }
}
