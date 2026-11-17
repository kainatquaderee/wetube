package dev.wetube.app.ui.player

import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.wetube.app.BuildConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.WebExtension

/**
 * One cookie in the shape the engine's cookie store takes.
 *
 * The app keeps its session as the flattened header a `Cookie:` would carry —
 * names and values, nothing else — because that is the only thing Android's
 * WebView hands back. Everything the engine needs beyond that is re-derived
 * here: the cookies all belong to youtube.com, all of them are only ever sent
 * over https, and only the ones YouTube's own scripts read are left visible to
 * them.
 */
internal data class SessionCookie(
    val name: String,
    val value: String,
    val httpOnly: Boolean,
    val sameSite: String,
)

/**
 * The cookies Google marks `HttpOnly`.
 *
 * `SID`, `HSID` and `SSID` are pure session identifiers that no page script
 * has any business reading, and the `__Secure-*PSID` pair is the same secret
 * behind a prefix that makes the browser refuse it outside https. The matching
 * `...PAPISID` cookies are deliberately *not* in this set: YouTube computes its
 * `SAPISIDHASH` authorization header in JavaScript from those, so marking them
 * `HttpOnly` would sign the player out without a word.
 */
private val HttpOnly = Regex("SID|HSID|SSID|__Secure-[13]PSID.*")

/** The `__Secure-3P*` pair exists to be sent cross-site, so it says so. */
private val CrossSite = Regex("__Secure-3P.*")

/**
 * Splits a stored cookie jar into cookies the engine can store.
 *
 * Blank entries and anything that is not `name=value` are dropped rather than
 * guessed at: a cookie with an empty value would be sent as a removal. A name
 * that appears twice keeps its first value, which is the one the browser would
 * have sent.
 */
internal fun sessionCookies(header: String): List<SessionCookie> {
    if (header.isBlank()) return emptyList()
    val cookies = LinkedHashMap<String, SessionCookie>()
    for (part in header.split(';', '\n', '\r')) {
        val pair = part.trim().removePrefix("Cookie:").trim()
        val separator = pair.indexOf('=')
        if (separator <= 0) continue
        val name = pair.substring(0, separator).trim()
        val value = pair.substring(separator + 1).trim()
        if (name.isEmpty() || value.isEmpty()) continue
        cookies.putIfAbsent(
            name,
            SessionCookie(
                name = name,
                value = value,
                httpOnly = HttpOnly.matches(name),
                sameSite = if (CrossSite.matches(name)) "no_restriction" else "unspecified",
            ),
        )
    }
    return cookies.values.toList()
}

/** What the extension reported after a seeding round. */
internal data class SeedReport(
    val stored: Int,
    val removed: Int,
    val failed: List<String>,
) {
    companion object {
        /** Reads the extension's reply; anything malformed is an empty report. */
        fun from(reply: JSONObject): SeedReport {
            val failed = reply.optJSONArray("failed")
            return SeedReport(
                stored = reply.optInt("set", 0),
                removed = reply.optInt("removed", 0),
                failed = buildList {
                    for (index in 0 until (failed?.length() ?: 0)) {
                        failed?.optString(index)?.takeIf(String::isNotEmpty)?.let(::add)
                    }
                },
            )
        }
    }
}

/**
 * Puts the app's session into the engine, and keeps it current.
 *
 * GeckoView has no cookie API of its own — `StorageController` can only clear
 * data, and the loader can only add headers to the one request it starts, which
 * is not the request an authenticated watch page actually makes. The engine's
 * extension API is the one supported way in, so the cookies are written by a
 * bundled extension ([dev.wetube.app]'s `assets/webcookies`) over native
 * messaging.
 *
 * The bridge is process-wide because the runtime is: one extension, one port,
 * and one seeding round at a time. A round that fails or times out is not fatal
 * — the page simply loads with whatever session the profile already had.
 */
internal object WebCookieBridge {
    private const val TAG = "WeTube"
    private const val EXTENSION_URI = "resource://android/assets/webcookies/"
    private const val EXTENSION_ID = "session@wetube.app"
    private const val NATIVE_APP = "wetube.session"

    /** Long enough for a cold engine to start the extension, short enough not to stall a page. */
    private const val TIMEOUT_MS = 4_000L

    private val seeding = Mutex()
    private val firstPort = CompletableDeferred<WebExtension.Port>()

    /** The thread the engine's extension API is bound on. */
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var port: WebExtension.Port? = null

    @Volatile
    private var reply: CompletableDeferred<JSONObject>? = null

    private var nextId = 1L

    /**
     * Installs the extension this process will use, if it is not installed yet.
     *
     * Called once per runtime: the runtime cannot be recreated, and the
     * extension outlives the app process, so `ensureBuiltIn` (rather than
     * `installBuiltIn`) keeps restarts from reinstalling it.
     */
    fun prepare(runtime: GeckoRuntime) {
        runtime.webExtensionController
            .ensureBuiltIn(EXTENSION_URI, EXTENSION_ID)
            .accept(
                { extension ->
                    // The delegate is bound on the thread the extension API is
                    // documented for, whatever thread the engine was created on.
                    main.post {
                        extension?.setMessageDelegate(NativePort, NATIVE_APP)
                        if (BuildConfig.DEBUG) Log.d(TAG, "web session: extension ready")
                    }
                },
                { error ->
                    // The player still works, signed in only where the profile
                    // already was, so this is a note rather than a failure.
                    if (BuildConfig.DEBUG) {
                        Log.d(
                            TAG,
                            "web session: extension unavailable (${error?.javaClass?.simpleName})",
                        )
                    }
                },
            )
    }

    /**
     * Replaces the engine's YouTube cookies with [cookies].
     *
     * An empty list clears the profile instead, which is what signing out
     * leaves behind. Returns whether the extension confirmed the round.
     */
    suspend fun seed(cookies: List<SessionCookie>): Boolean = seeding.withLock {
        val channel = port ?: withTimeoutOrNull(TIMEOUT_MS) { firstPort.await() } ?: run {
            if (BuildConfig.DEBUG) Log.d(TAG, "web session: no extension port")
            return false
        }

        val id = nextId++
        val answer = CompletableDeferred<JSONObject>()
        reply = answer
        val payload = JSONObject()
            .put("type", "seed")
            .put("id", id)
            .put("cookies", JSONArray().apply {
                cookies.forEach { cookie ->
                    put(
                        JSONObject()
                            .put("name", cookie.name)
                            .put("value", cookie.value)
                            .put("httpOnly", cookie.httpOnly)
                            .put("sameSite", cookie.sameSite),
                    )
                }
            })
        val sent = runCatching { channel.postMessage(payload) }.isSuccess
        val result = if (sent) withTimeoutOrNull(TIMEOUT_MS) { answer.await() } else null
        reply = null

        if (BuildConfig.DEBUG) {
            if (result == null) {
                Log.d(TAG, "web session: seeding unanswered (${cookies.size} cookie(s))")
            } else {
                // Names only: the values are the account.
                val report = SeedReport.from(result)
                Log.d(
                    TAG,
                    "web session: stored=${report.stored} removed=${report.removed}" +
                        " failed=${report.failed.joinToString(",")}",
                )
            }
        }
        result != null
    }

    private object NativePort : WebExtension.MessageDelegate {
        override fun onConnect(connection: WebExtension.Port) {
            connection.setDelegate(Replies)
            port = connection
            if (!firstPort.isCompleted) firstPort.complete(connection)
        }
    }

    private object Replies : WebExtension.PortDelegate {
        override fun onPortMessage(message: Any, port: WebExtension.Port) {
            val json = message as? JSONObject ?: return
            if (json.optString("type") != "seeded") return
            reply?.complete(json)
        }

        override fun onDisconnect(connection: WebExtension.Port) {
            if (port === connection) port = null
        }
    }
}