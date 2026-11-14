package dev.wetube.app.player.remote

import java.util.Locale

/**
 * Where an IP literal lives.
 *
 * Only [PRIVATE] and [LINK_LOCAL] are reachable on the user's own network, which
 * is the only place casting is ever allowed to look. Everything else — a public
 * address, carrier-grade NAT, a benchmark/reserved range, multicast, "this
 * network" — is collapsed into [NON_LOCAL] because the answer we need is a
 * yes/no, and a wrong "yes" means a hostile SSDP responder can point the app at
 * a host on the internet.
 */
internal enum class LanScope { LOOPBACK, LINK_LOCAL, PRIVATE, NON_LOCAL }

/**
 * Classifies bare IPv4/IPv6 literals without ever asking the resolver.
 *
 * The classifier is deliberately name-only: a hostname such as `tv.local` or
 * an attacker-controlled DNS name is *not* a literal and returns `null`, so it
 * can never be fetched. That also means this function never performs a lookup —
 * a lookup is exactly the network access it exists to prevent — which is why the
 * parsing is written out instead of delegated to `InetAddress.getByName`: that
 * call falls through to DNS for anything it fails to recognise as a literal.
 *
 * This is the casting-side counterpart of `core.Urls.isBlockedAddress`. It is
 * intentionally a separate classifier: that function answers "is this host
 * dangerous for a backend request" and blocks private space, while casting lives
 * *only* in private space. Neither is a relaxation of the other.
 */
internal object LanAddress {

    /**
     * True only for RFC1918 / CGNAT-free private and link-local literals.
     *
     * This is the pure classifier: it answers "where does this literal live",
     * with no policy on top. Callers that are about to build or fetch a URL must
     * use [isCastingTarget] instead, which layers the metadata exclusion on it.
     */
    fun isPrivateOrLinkLocal(host: String?): Boolean {
        val scope = classify(host) ?: return false
        return scope == LanScope.PRIVATE || scope == LanScope.LINK_LOCAL
    }

    /**
     * True when [host] is a literal casting may actually be sent to.
     *
     * This is [isPrivateOrLinkLocal] minus one documented exception: the cloud
     * instance-metadata address `169.254.169.254`. It sits inside the link-local
     * block the network rules allow, yet on a hosted or emulated runtime it is
     * the metadata service, so a hostile SSDP responder that points a
     * `controlURL` at it turns every SOAP call into a blind request to that
     * service. A renderer on the user's own LAN never lives there, so it is
     * denied.
     *
     * The IPv4-mapped form `::ffff:169.254.169.254` is caught too: [classify]
     * already reduces a mapped address to the four octets it carries, and
     * [isMetadataAddress] reduces it the same way, so one four-octet comparison
     * covers both spellings. [isPrivateOrLinkLocal] stays the pure classifier
     * for callers that only need the scope.
     */
    fun isCastingTarget(host: String?): Boolean {
        if (!isPrivateOrLinkLocal(host)) return false
        return !isMetadataAddress(host)
    }

    /**
     * The scope of [host] when it is an IP literal, or `null` for a hostname,
     * an empty value or anything that is not a well-formed literal.
     */
    fun classify(host: String?): LanScope? {
        val raw = host?.trim().orEmpty()
        if (raw.isEmpty()) return null
        // A URL authority writes an IPv6 literal in brackets; a zone id may be
        // percent-encoded (`fe80::1%25wlan0`) and never changes the scope.
        val unwrapped = raw.removeSurrounding("[", "]").substringBefore('%').trim()
        if (unwrapped.isEmpty()) return null
        return if (unwrapped.contains(':')) classifyV6(unwrapped) else classifyV4(unwrapped)
    }

    // -- IPv4 ---------------------------------------------------------------

    private fun classifyV4(host: String): LanScope? {
        val octets = classifyV4Octets(host) ?: return null
        return scopeOfV4(octets[0], octets[1])
    }

    /**
     * Whether [host] names the `169.254.169.254` metadata endpoint, in either
     * its dotted or its IPv4-mapped (`::ffff:169.254.169.254`) form.
     *
     * Anything that is not an IP literal — including a hostname that a resolver
     * might one day map there — returns `false`, so this can only narrow the
     * casting allow-list, never widen it.
     */
    private fun isMetadataAddress(host: String?): Boolean {
        val raw = host?.trim().orEmpty()
        if (raw.isEmpty()) return false
        val unwrapped = raw.removeSurrounding("[", "]").substringBefore('%').trim()
        if (unwrapped.isEmpty()) return false

        val octets: IntArray = if (unwrapped.contains(':')) {
            val bytes = parseV6(unwrapped) ?: return false
            val mapped = rowAllZero(bytes, until = 10) &&
                bytes[10] == 0xFF.toByte() && bytes[11] == 0xFF.toByte()
            if (!mapped) return false
            intArrayOf(
                bytes[12].toInt() and 0xFF,
                bytes[13].toInt() and 0xFF,
                bytes[14].toInt() and 0xFF,
                bytes[15].toInt() and 0xFF,
            )
        } else {
            classifyV4Octets(unwrapped) ?: return false
        }

        return octets[0] == 169 && octets[1] == 254 && octets[2] == 169 && octets[3] == 254
    }

    /**
     * Parses `a.b.c.d`.
     *
     * A leading zero is rejected outright: several stacks read `010` as octal, so
     * `010.0.0.1` means different addresses to different parsers and must not be
     * trusted to mean "10.0.0.1".
     */
    private fun classifyV4Octets(host: String): IntArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val octets = IntArray(4)
        for (index in 0..3) {
            val part = parts[index]
            if (part.isEmpty() || part.length > 3) return null
            if (part.length > 1 && part[0] == '0') return null
            if (part.any { it !in '0'..'9' }) return null
            val value = part.toInt()
            if (value !in 0..255) return null
            octets[index] = value
        }
        return octets
    }

    private fun scopeOfV4(a: Int, b: Int): LanScope = when {
        a == 127 -> LanScope.LOOPBACK
        a == 10 -> LanScope.PRIVATE
        a == 172 && b in 16..31 -> LanScope.PRIVATE
        a == 192 && b == 168 -> LanScope.PRIVATE
        a == 169 && b == 254 -> LanScope.LINK_LOCAL
        else -> LanScope.NON_LOCAL
    }

    // -- IPv6 ---------------------------------------------------------------

    /** The scope of a colon-bearing literal, or `null` when it is malformed. */
    private fun classifyV6(host: String): LanScope? {
        val bytes = parseV6(host) ?: return null
        val first = bytes[0].toInt() and 0xFF
        val second = bytes[1].toInt() and 0xFF

        // An IPv4-mapped address (`::ffff:192.168.1.5`) is classified by the
        // address it actually carries, not by the v6 envelope. The mapping
        // prefix is the first ten bytes zero followed by 0xFFFF, i.e. bytes 10
        // and 11 both 0xFF (RFC 4291 §2.5.5.2).
        if (rowAllZero(bytes, until = 10) && bytes[10] == 0xFF.toByte() && bytes[11] == 0xFF.toByte()) {
            return scopeOfV4(bytes[12].toInt() and 0xFF, bytes[13].toInt() and 0xFF)
        }

        return when {
            rowAllZero(bytes, until = 15) && bytes[15] == 1.toByte() -> LanScope.LOOPBACK // ::1
            first == 0xFE && (second and 0xC0) == 0x80 -> LanScope.LINK_LOCAL // fe80::/10
            first == 0xFE && (second and 0xC0) == 0xC0 -> LanScope.PRIVATE // fec0::/10 (deprecated)
            (first and 0xFE) == 0xFC -> LanScope.PRIVATE // fc00::/7 unique-local
            else -> LanScope.NON_LOCAL
        }
    }

    private fun rowAllZero(bytes: ByteArray, until: Int): Boolean {
        for (index in 0 until until) if (bytes[index] != 0.toByte()) return false
        return true
    }

    /**
     * Expands an IPv6 literal to its sixteen bytes, or `null` when it is not one.
     *
     * Accepts the compressed `::` form and an embedded dotted quad. A malformed
     * literal must fail here rather than in a resolver, so every field is checked
     * against the grammar before a byte is produced.
     */
    private fun parseV6(input: String): ByteArray? {
        var text = input

        // `::ffff:1.2.3.4` — replace the trailing dotted quad with its two hex
        // groups so the rest of the parse only ever sees hextets.
        val lastField = text.substringAfterLast(':')
        if (lastField.contains('.')) {
            val v4 = classifyV4Octets(lastField) ?: return null
            val replaced = String.format(Locale.US, "%02x%02x:%02x%02x", v4[0], v4[1], v4[2], v4[3])
            text = text.substringBeforeLast(':') + ":" + replaced
        }

        val wildcard = text.indexOf("::")
        if (wildcard != text.lastIndexOf("::")) return null // at most one "::"
        val head = if (wildcard >= 0) text.substring(0, wildcard) else text
        val tail = if (wildcard >= 0) text.substring(wildcard + 2) else ""

        val headGroups = hextets(head) ?: return null
        val tailGroups = hextets(tail) ?: return null

        val groups: List<Int> = if (wildcard < 0) {
            if (headGroups.size != 8) return null
            headGroups
        } else {
            // "::" must stand for at least one group, or the literal is written
            // out in full and should not have used it at all.
            val fill = 8 - headGroups.size - tailGroups.size
            if (fill < 1) return null
            headGroups + List(fill) { 0 } + tailGroups
        }

        return ByteArray(16) { index ->
            val group = groups[index / 2]
            if (index % 2 == 0) (group shr 8).toByte() else group.toByte()
        }
    }

    private fun hextets(text: String): List<Int>? {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<Int>(8)
        for (part in text.split(':')) {
            if (part.isEmpty() || part.length > 4) return null
            if (part.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) return null
            out.add(part.toInt(16))
        }
        return out
    }
}
