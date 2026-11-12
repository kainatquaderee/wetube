package dev.wetube.app.data.auth

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.wetube.app.BuildConfig
import dev.wetube.app.data.backend.JsonHttp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.security.MessageDigest

private val Context.authDataStore by preferencesDataStore(name = "wetube_auth")

/**
 * A signed-in Google session, stored as the cookie jar YouTube itself uses.
 *
 * Sign-in is optional: everything except personalised recommendations,
 * subscriptions and age-restricted playback works without it.
 */
data class Account(
    val name: String = "",
    val avatar: String = "",
    val cookies: String = "",
) {
    val signedIn: Boolean get() = sapisid(cookies) != null
    val displayName: String get() = name.ifBlank { "Signed in" }
}

/** On-disk shape of one account; deliberately separate from [Account]. */
@Serializable
private data class StoredAccount(
    val name: String = "",
    val avatar: String = "",
    val cookies: String = "",
)

/**
 * Holds the optional account session.
 *
 * Google closed third-party OAuth for YouTube, so there is no public token flow
 * to implement here. What still works — and what the official web client does —
 * is an ordinary Google login that leaves session cookies behind. We harvest
 * those cookies after the user signs in inside the app's own WebView (or imports
 * a cookies file), then sign every InnerTube request with `SAPISIDHASH`, which
 * is the documented way to authenticate a cookie session.
 *
 * The cookie string is the account: it stays in this app's private DataStore, is
 * excluded from backup and device transfer, and is wiped by "Sign out".
 */
class AuthStore(
    private val context: Context,
    private val http: JsonHttp,
) {

    private val json = http.json
    private val listSerializer = ListSerializer(StoredAccount.serializer())

    private object Keys {
        val accounts = stringPreferencesKey("accounts")
        val active = stringPreferencesKey("active")

        // Single-account format used before multi-account support. Read as a
        // fallback and removed the first time the list is written.
        val legacyCookies = stringPreferencesKey("cookies")
        val legacyName = stringPreferencesKey("name")
        val legacyAvatar = stringPreferencesKey("avatar")
    }

    /**
     * Every account the user added, oldest first, and which one is active.
     *
     * The backend cache in [dev.wetube.app.di.ServiceLocator] is keyed by the
     * active cookie jar, so switching accounts rebuilds the backend and every
     * feed immediately follows the new session.
     */
    val accounts: Flow<List<Account>> = context.authDataStore.data.map { prefs ->
        stored(prefs).map { Account(it.name, it.avatar, it.cookies) }
    }

    val activeIndex: Flow<Int> = context.authDataStore.data.map { prefs ->
        prefs[Keys.active]?.toIntOrNull() ?: 0
    }

    val account: Flow<Account?> = combine(accounts, activeIndex) { list, index ->
        list.getOrNull(index)
    }

    val signedIn: Flow<Boolean> = account.map { it != null }

    private fun stored(prefs: androidx.datastore.preferences.core.Preferences): List<StoredAccount> {
        prefs[Keys.accounts]?.takeIf { it.isNotBlank() }?.let { raw ->
            runCatching { json.decodeFromString(listSerializer, raw) }.getOrNull()?.let { return it }
        }
        val cookies = prefs[Keys.legacyCookies].orEmpty()
        if (sapisid(cookies) == null) return emptyList()
        return listOf(
            StoredAccount(
                name = prefs[Keys.legacyName].orEmpty(),
                avatar = prefs[Keys.legacyAvatar].orEmpty(),
                cookies = cookies,
            ),
        )
    }

    /** Headers that turn an anonymous InnerTube call into an authenticated one. */
    suspend fun headers(): Map<String, String> = headersFor(account.first()?.cookies.orEmpty())

    private fun headersFor(cookies: String): Map<String, String> {
        val key = sapisid(cookies) ?: return emptyMap()
        val stamp = System.currentTimeMillis() / 1000
        val hash = sha1Hex("$stamp $key https://www.youtube.com")
        return mapOf(
            "Cookie" to cookies,
            "Authorization" to "SAPISIDHASH ${stamp}_$hash",
            "X-Goog-AuthUser" to "0",
            "X-Origin" to "https://www.youtube.com",
        )
    }

    /**
     * Add a cookie jar as an account and make it active. Signing in with the
     * same cookies twice switches back to that account instead of duplicating
     * it, which makes "add account" safe to retry.
     */
    suspend fun signIn(rawCookies: String) {
        val cookies = normaliseCookies(rawCookies)
        require(sapisid(cookies) != null) {
            "Those cookies have no YouTube session in them. Sign in to youtube.com in your " +
                "browser first, then export the cookies for youtube.com."
        }

        val existing = accounts.first().indexOfFirst { it.cookies == cookies }
        if (existing >= 0) {
            setActive(existing)
            return
        }

        val info = runCatching { fetchAccount(cookies) }.getOrNull()
        context.authDataStore.edit { prefs ->
            val next = stored(prefs) + StoredAccount(
                name = info?.first.orEmpty(),
                avatar = info?.second.orEmpty(),
                cookies = cookies,
            )
            prefs[Keys.accounts] = json.encodeToString(listSerializer, next)
            prefs[Keys.active] = next.lastIndex.toString()
            prefs.remove(Keys.legacyCookies)
            prefs.remove(Keys.legacyName)
            prefs.remove(Keys.legacyAvatar)
        }
        if (BuildConfig.DEBUG) {
            android.util.Log.d("WeTube", "auth: ${accounts.first().size} account(s)")
        }
    }

    suspend fun switchTo(index: Int) {
        if (index !in accounts.first().indices) return
        setActive(index)
    }

    /** Sign out of the active account; other accounts keep their session. */
    suspend fun signOut() {
        val index = activeIndex.first()
        context.authDataStore.edit { prefs ->
            val current = stored(prefs)
            val next = current.filterIndexed { i, _ -> i != index }
            prefs[Keys.accounts] = json.encodeToString(listSerializer, next)
            prefs[Keys.active] = index.coerceAtMost(next.lastIndex).coerceAtLeast(0).toString()
            prefs.remove(Keys.legacyCookies)
            prefs.remove(Keys.legacyName)
            prefs.remove(Keys.legacyAvatar)
        }
    }

    suspend fun signOutAll() {
        context.authDataStore.edit { it.clear() }
    }

    private suspend fun setActive(index: Int) {
        context.authDataStore.edit { prefs ->
            if (index in stored(prefs).indices) prefs[Keys.active] = index.toString()
        }
    }

    /** Reads the account name and avatar so Settings can show who is signed in. */
    private suspend fun fetchAccount(cookies: String): Pair<String, String>? {
        val authHeaders = headersFor(cookies)
        if (authHeaders.isEmpty()) return null
        val body = buildJsonObject {
            put("context", buildJsonObject {
                put("client", buildJsonObject {
                    put("clientName", "WEB")
                    put("clientVersion", WEB_VERSION)
                    put("hl", "en")
                    put("gl", "US")
                })
            })
        }
        val response = http.postJson(
            "https://www.youtube.com/youtubei/v1/account/account_menu?prettyPrint=false",
            body,
            authHeaders + mapOf(
                "User-Agent" to WEB_UA,
                "X-Youtube-Client-Name" to "1",
                "X-Youtube-Client-Version" to WEB_VERSION,
                "Origin" to "https://www.youtube.com",
            ),
        )
        val name = firstText(response, "accountName").orEmpty()
        val avatar = firstThumbnail(response, "accountPhoto").orEmpty()
        if (BuildConfig.DEBUG) {
            android.util.Log.d("WeTube", "auth: account loaded=${name.isNotBlank()}")
        }
        return name to avatar
    }

    private companion object {
        const val WEB_VERSION = "2.20240101.00.00"
        const val WEB_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    }
}

/** The session key YouTube signs requests with, once the user has signed in. */
private fun sapisid(cookies: String): String? {
    if (cookies.isBlank()) return null
    val pairs = cookies.split(';')
        .map { it.trim() }
        .filter { it.contains('=') }
        .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
    return pairs["SAPISID"]
        ?: pairs["__Secure-3PAPISID"]
        ?: pairs["__Secure-1PAPISID"]
}

private fun normaliseCookies(raw: String): String {
    val text = raw.trim()
    if (text.isEmpty()) return text

    // Netscape cookies.txt: domain \t flag \t path \t secure \t expiry \t name \t value
    if (text.contains('\t')) {
        return text.lineSequence()
            .mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) return@mapNotNull null
                val parts = trimmed.split('\t')
                if (parts.size < 7) return@mapNotNull null
                val domain = parts[0]
                if (!domain.contains("youtube.com") && !domain.contains("google.com")) {
                    return@mapNotNull null
                }
                "${parts[5]}=${parts[6]}"
            }
            .joinToString("; ")
    }

    return text.removePrefix("Cookie:").trim()
        .split(';', '\n', '\r')
        .map { it.trim() }
        .filter { it.contains('=') }
        .joinToString("; ")
}

private fun sha1Hex(input: String): String =
    MessageDigest.getInstance("SHA-1")
        .digest(input.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

/** First non-blank value stored under [key], anywhere in the renderer tree. */
private fun firstText(root: JsonObject, key: String): String? {
    var found: String? = null
    fun walk(element: JsonElement) {
        if (found != null) return
        when (element) {
            is JsonObject -> {
                val value = element[key]
                val text = when (value) {
                    is JsonPrimitive -> value.contentOrNull
                    is JsonObject -> (value["simpleText"] as? JsonPrimitive)?.contentOrNull
                        ?: (value["runs"] as? JsonArray)
                            ?.joinToString("") { run ->
                                ((run as? JsonObject)?.get("text") as? JsonPrimitive)
                                    ?.contentOrNull.orEmpty()
                            }
                    else -> null
                }
                if (!text.isNullOrBlank()) {
                    found = text
                    return
                }
                element.values.forEach(::walk)
            }
            is JsonArray -> element.forEach(::walk)
            else -> Unit
        }
    }
    walk(root)
    return found
}

private fun firstThumbnail(root: JsonObject, key: String): String? {
    var found: String? = null
    fun walk(element: JsonElement) {
        if (found != null) return
        when (element) {
            is JsonObject -> {
                val photo = element[key] as? JsonObject
                val url = (photo?.get("thumbnails") as? JsonArray)
                    ?.mapNotNull { (it as? JsonObject)?.get("url") as? JsonPrimitive }
                    ?.lastOrNull()
                    ?.contentOrNull
                if (!url.isNullOrBlank()) {
                    found = url
                    return
                }
                element.values.forEach(::walk)
            }
            is JsonArray -> element.forEach(::walk)
            else -> Unit
        }
    }
    walk(root)
    return found
}
