package dev.wetube.app.data.download

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Downloads one muxed progressive file with a plain HTTP request, resuming a
 * leftover `.part` when there is one.
 *
 * Everything is written to `<target>.part` and only moved onto [File] once it is
 * complete, so [File] appears atomically and a half-finished download is never
 * handed to the player as if it were whole. A failure leaves the part file (and
 * the bytes already on disk) in place, so the next attempt continues with a
 * `Range` request instead of starting over.
 */
class FileDownloader(private val http: OkHttpClient) {

    /**
     * Downloads [url] into [target], resuming a leftover `<target>.part`.
     * Returns [target] once complete. Throws on failure, leaving the part file
     * (and its byte count) in place so the next attempt resumes.
     *
     * [onProgress] is called as bytes land, with the running count and the total
     * when the server states one (`null` otherwise). It is called on the IO
     * dispatcher, so it must not block.
     */
    suspend fun download(
        url: String,
        target: File,
        onProgress: (bytesReceived: Long, totalBytes: Long?) -> Unit = { _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        target.parentFile?.let { if (!it.exists()) it.mkdirs() }
        val part = File(target.parentFile, target.name + ".part")
        val existing = part.length()

        // Resume only what was actually written; a zero-length part is no
        // resume at all and asks the server for the whole file.
        var offset = if (existing > 0) existing else 0L
        val request = Request.Builder()
            .url(url)
            .apply { rangeHeader(offset)?.let { header("Range", it) } }
            .build()
        val call = http.newCall(request)

        // Cancelling the coroutine must abort the socket, not just stop reading:
        // a cancelled job completes and this handler tears down the call, so a
        // download the user abandoned stops consuming data immediately.
        currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }

        call.execute().use { response ->
            when (response.code) {
                // The server says the range is unsatisfiable: with a range sent
                // it means the part already holds the whole file, so there is
                // nothing left to fetch.
                416 -> {
                    complete(part, target)
                    return@withContext target
                }

                // Range ignored (or no range was sent): whatever is in the part
                // cannot be trusted to line up with the body, so restart.
                200 -> offset = 0L

                // Appended to the part at the offset already on disk.
                206 -> Unit

                else -> {
                    if (!response.isSuccessful) {
                        throw IOException("Download failed: HTTP ${response.code} for $url")
                    }
                    // Another 2xx (unexpected for a ranged GET): treat it like a
                    // full-body response and restart from zero to stay correct.
                    offset = 0L
                }
            }

            // A 206 states the size of the whole file in Content-Range; a 200
            // states the size of what it is sending in Content-Length. Either
            // can be absent.
            val total: Long? = if (response.code == 206) {
                totalFromContentRange(response.header("Content-Range"))
            } else {
                response.body?.contentLength()?.takeIf { it >= 0 }
            }

            val source = response.body?.byteStream()
                ?: throw IOException("Download failed: no response body for $url")
            var written = 0L
            FileOutputStream(part, /* append = */ offset > 0).use { raw ->
                BufferedOutputStream(raw).use { out ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        // Per-chunk check so an abandoned download stops within
                        // one buffer, long before the next socket read returns.
                        currentCoroutineContext().ensureActive()
                        val read = source.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        written += read
                        onProgress(offset + written, total)
                    }
                    out.flush()
                }
            }
            // Report the final value even when the body was short or empty, so a
            // UI never ends up mid-bar on completion.
            onProgress(offset + written, total)
            complete(part, target)
        }
        target
    }

    /**
     * Moves [part] onto [target]. A rename is atomic and preferred; the copy
     * fallback covers a filesystem that refuses to rename across a mount.
     */
    private fun complete(part: File, target: File) {
        if (target.exists() && !target.delete()) {
            throw IOException("Download failed: cannot replace ${target.name}")
        }
        if (part.renameTo(target)) return
        part.copyTo(target, overwrite = true)
        part.delete()
    }

    private companion object {
        /** 64 KiB: large enough to keep the syscall count down, small enough to stay prompt on cancel. */
        const val BUFFER_BYTES = 64 * 1024
    }
}
