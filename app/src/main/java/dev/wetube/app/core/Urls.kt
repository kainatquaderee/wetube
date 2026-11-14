package dev.wetube.app.core

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.InetAddress
import java.net.URLEncoder
import java.util.Locale

/**
 * URL helpers shared by the settings, backup and backend layers.
 *
 * The independent-instance hosts come from free-text fields and from backup
 * files restored on another device, then get concatenated into request URLs.
 * Everything that turns such a value into a URL goes through here, so the rules
 * live in one place instead of being re-derived at each call site.
 */

/**
 * Built-in independent instances, used whenever a stored host is rejected.
 *
 * The Invidious default was moved off `invidious.materialio.us`, which now
 * answers the public API with HTTP 500 for video lookups: a built-in default
 * that is guaranteed to fail makes the whole Invidious backend look broken
 * before the user can even pick another instance. `yt.chocolatemoo53.com` is a
 * public instance from the official list that answers the API normally. It can
 * go dark like any community host, which is exactly why the failure is now
 * reported honestly instead of being masked behind an empty screen.
 */
const val DEFAULT_INVIDIOUS_HOST: String = "https://yt.chocolatemoo53.com"
const val DEFAULT_PIPED_HOST: String = "https://pipedapi.kavin.rocks"

/** A real hostname tops out at 253 characters; anything longer is not one. */
private const val MAX_HOST_LENGTH = 253

/** Refuse absurdly long raw input before the parser even looks at it. */
private const val MAX_URL_LENGTH = 2048

/**
 * Turns a user-supplied backend base URL into a canonical `https://host[:port]`
 * origin, or `null` when it cannot be trusted.
 *
 * The value reaches us from a Settings text field and from a backup restored on
 * another device, and is then string-concatenated into request URLs. Without a
 * check a crafted value could downgrade traffic to `http://`, embed credentials
 * that leak into the request line, or point the app back at its own device or
 * LAN (blind SSRF). We therefore accept only public, cleartext-free hosts and
 * discard everything else; callers fall back to the built-in default.
 *
 * Returning the origin rather than the raw string also drops any path, query or
 * fragment a caller appended, so the `/api/v1` suffix the backends add is
 * always relative to the bare host.
 */
fun sanitizeBackendHost(raw: String?): String? {
    val candidate = raw?.trim().orEmpty()
    if (candidate.isEmpty() || candidate.length > MAX_URL_LENGTH) return null

    val url = candidate.toHttpUrlOrNull() ?: return null
    if (url.scheme != "https") return null
    // Userinfo in a URL is nearly always an attempt to smuggle credentials or
    // confuse the authority component; no legitimate instance needs it.
    if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
    if (url.host.isEmpty() || url.host.length > MAX_HOST_LENGTH) return null
    if (isBlockedHost(url.host)) return null

    val port = if (url.port == HTTPS_PORT) "" else ":${url.port}"
    // `url.host` is already IDN-normalised and lower-cased by OkHttp. An IPv6
    // literal has to go back into brackets to be a valid authority.
    val host = if (url.host.contains(':')) "[${url.host}]" else url.host
    return "https://$host$port"
}

/**
 * Navigation allow-list for the optional sign-in WebView. Only the https pages
 * the Google login flow actually needs are permitted; everything else (custom
 * schemes, `file:`, `content:`, `intent:`, other sites) is refused by the
 * caller.
 */
fun isAllowedSignInUrl(raw: String?): Boolean {
    val url = raw?.let { runCatching { it.trim().toHttpUrlOrNull() }.getOrNull() } ?: return false
    if (url.scheme != "https") return false
    val host = url.host.lowercase(Locale.US)
    return SIGN_IN_HOSTS.any { host == it || host.endsWith(".$it") }
}

/** Percent-encodes a dynamic value before it is placed in a URL path or query. */
fun encodeUrlValue(value: String): String = URLEncoder.encode(value, "UTF-8")

/** Apex domains the sign-in flow legitimately crosses; subdomains are allowed too. */
private val SIGN_IN_HOSTS = listOf(
    "accounts.google.com",
    "google.com",
    "youtube.com",
    "youtu.be",
    "gstatic.com",
)

private const val HTTPS_PORT = 443

/**
 * True for the names and IP literals that reach the device, its LAN, or a
 * link-local peer. Literals are parsed without DNS: a hostname is never
 * resolved here, because doing so would itself be the network access we are
 * trying to avoid.
 */
private fun isBlockedHost(host: String): Boolean {
    val name = host.lowercase(Locale.US).removeSuffix(".")
    if (name == "localhost" || name.endsWith(".localhost")) return true
    val literal = parseIpLiteral(name) ?: return false
    return isBlockedAddress(literal)
}

private fun parseIpLiteral(host: String): InetAddress? {
    val looksLikeIpv6 = host.contains(':')
    val looksLikeIpv4 = host.isNotEmpty() && host.all { it.isDigit() || it == '.' }
    if (!looksLikeIpv6 && !looksLikeIpv4) return null
    return runCatching { InetAddress.getByName(host) }.getOrNull()
}

private fun isBlockedAddress(address: InetAddress): Boolean {
    if (address.isAnyLocalAddress ||
        address.isLoopbackAddress ||
        address.isLinkLocalAddress ||
        address.isSiteLocalAddress ||
        address.isMulticastAddress
    ) {
        return true
    }
    val bytes = address.address
    return when (bytes.size) {
        4 -> {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            val b2 = bytes[2].toInt() and 0xFF
            // 100.64.0.0/10 carrier-grade NAT, 192.0.0.0/24 IETF assignments,
            // 198.18.0.0/15 benchmarking, 240.0.0.0/4 reserved.
            (b0 == 100 && b1 in 64..127) ||
                (b0 == 192 && b1 == 0 && b2 == 0) ||
                (b0 == 198 && b1 in 18..19) ||
                b0 >= 240
        }
        16 ->
            // fc00::/7 unique-local addresses; isSiteLocalAddress only covers
            // the deprecated fec0::/10 range.
            (bytes[0].toInt() and 0xFE) == 0xFC
        else -> false
    }
}
