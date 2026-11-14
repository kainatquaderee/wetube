package dev.wetube.app.player.remote

import java.util.Locale

/**
 * SSDP — the discovery half of UPnP. Pure strings and bytes, no sockets: the
 * discovery step owns the multicast socket and feeds whatever it received here.
 *
 * Responses are parsed defensively because they come from any device on the
 * network, including one that is lying. A response is only useful if it carries
 * a `LOCATION` we can fetch, a `USN` that identifies the device and an `ST` that
 * says what it is; everything else is dropped silently.
 */
internal object Ssdp {

    /** The SSDP multicast group every UPnP control point talks to. */
    const val MULTICAST_HOST = "239.255.255.250"

    /** The SSDP multicast port. */
    const val MULTICAST_PORT = 1900

    /** The search target that only media renderers answer. */
    const val ST_MEDIA_RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1"

    /**
     * The catch-all search target. A second sweep asks this because a handful of
     * TVs only answer `ssdp:all`, then are filtered by their device type.
     */
    const val ST_ALL = "ssdp:all"

    /** Default `MX`, i.e. how long devices may take to answer, in seconds. */
    const val DEFAULT_MX = 2

    /** The canonical `M-SEARCH` for renderers, ready to send as-is. */
    val mediaRendererSearch: ByteArray = search(ST_MEDIA_RENDERER)

    /**
     * Builds an `M-SEARCH` datagram for [st].
     *
     * `MX` is deliberately small: the sheet is open and a user is waiting, and
     * the spec lets us cap the randomised response delay.
     */
    fun search(st: String = ST_MEDIA_RENDERER, mx: Int = DEFAULT_MX): ByteArray =
        (
            "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: $MULTICAST_HOST:$MULTICAST_PORT\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: $mx\r\n" +
                "ST: $st\r\n" +
                "\r\n"
            ).toByteArray(Charsets.US_ASCII)

    /**
     * Parses one SSDP response datagram, or returns `null` when it is not a
     * usable 2xx answer from a device that disclosed where to find it.
     *
     * Header names are matched case-insensitively (the spec does not fix their
     * case and devices disagree); the first occurrence of a repeated header wins.
     */
    fun parse(message: String?): SsdpResponse? {
        if (message.isNullOrBlank()) return null

        val lines = message.lines()
        val statusLine = lines.firstOrNull()?.trim().orEmpty()
        if (!statusLine.startsWith("HTTP/", ignoreCase = true)) return null
        val status = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: return null
        if (status !in 200..299) return null

        val headers = LinkedHashMap<String, String>()
        for (line in lines.drop(1)) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) break
            val separator = trimmed.indexOf(':')
            if (separator <= 0) continue
            val name = trimmed.substring(0, separator).trim().lowercase(Locale.US)
            val value = trimmed.substring(separator + 1).trim()
            headers.putIfAbsent(name, value)
        }

        return SsdpResponse(
            location = headers["location"].orEmpty().takeIf { it.isNotBlank() } ?: return null,
            usn = headers["usn"].orEmpty().takeIf { it.isNotBlank() } ?: return null,
            st = headers["st"].orEmpty().takeIf { it.isNotBlank() } ?: return null,
            server = headers["server"]?.takeIf { it.isNotBlank() },
            headers = headers,
        )
    }
}

/** A valid SSDP response, reduced to the fields discovery needs. */
internal data class SsdpResponse(
    /** Absolute `http://` URL of the device description. */
    val location: String,
    /** `uuid:…::urn:…` identity. Used to dedupe one device answering several times. */
    val usn: String,
    /** Search target the device answered as. */
    val st: String,
    /** `SERVER` header, purely informational. */
    val server: String?,
    /** Every header, lower-cased names, for the occasional non-standard field. */
    val headers: Map<String, String>,
)