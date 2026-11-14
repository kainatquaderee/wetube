package dev.wetube.app.player.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The classifier that decides whether an SSDP `LOCATION`/`controlURL` host is
 * on the user's own network.
 *
 * This is the security boundary of discovery: a hostile responder on the LAN
 * controls the `LOCATION` it advertises, and the only thing standing between the
 * app and a request to an arbitrary internet host is that [LanAddress] says
 * "no". So the accept set is pinned to exact RFC1918/link-local literals and
 * everything else — public, loopback, unspecified, multicast, CGNAT, hostnames —
 * is pinned to the reject side.
 */
class LanAddressTest {

    // -- accepted ------------------------------------------------------------

    @Test
    fun `RFC1918 private literals are accepted`() {
        listOf(
            "10.0.0.1",
            "10.255.255.255",
            "172.16.0.1",
            "172.20.10.5",
            "172.31.255.255",
            "192.168.0.1",
            "192.168.68.111",
        ).forEach { host ->
            assertEquals(host, LanScope.PRIVATE, LanAddress.classify(host))
            assertTrue(host, LanAddress.isPrivateOrLinkLocal(host))
        }
    }

    @Test
    fun `IPv4 link-local is accepted`() {
        assertEquals(LanScope.LINK_LOCAL, LanAddress.classify("169.254.0.1"))
        assertEquals(LanScope.LINK_LOCAL, LanAddress.classify("169.254.255.255"))
        assertTrue(LanAddress.isPrivateOrLinkLocal("169.254.1.7"))
    }

    @Test
    fun `private and link-local IPv6 literals are accepted`() {
        // fc00::/7 unique-local
        assertEquals(LanScope.PRIVATE, LanAddress.classify("fc00::1"))
        assertEquals(LanScope.PRIVATE, LanAddress.classify("fd00::1"))
        assertEquals(LanScope.PRIVATE, LanAddress.classify("fdff::ffff"))
        assertEquals(LanScope.PRIVATE, LanAddress.classify("fd00:0:0:0:0:0:0:1"))
        // fe80::/10 link-local
        assertEquals(LanScope.LINK_LOCAL, LanAddress.classify("fe80::1"))
        assertEquals(LanScope.LINK_LOCAL, LanAddress.classify("febf::1"))

        listOf("fc00::1", "fd00::1", "fe80::1").forEach { host ->
            assertTrue(host, LanAddress.isPrivateOrLinkLocal(host))
        }
    }

    @Test
    fun `an IPv4-mapped IPv6 literal is classified by the address it carries`() {
        assertEquals(LanScope.PRIVATE, LanAddress.classify("::ffff:192.168.1.5"))
        assertEquals(LanScope.PRIVATE, LanAddress.classify("::ffff:10.0.0.1"))
        assertEquals(LanScope.LOOPBACK, LanAddress.classify("::ffff:127.0.0.1"))
        assertTrue(LanAddress.isPrivateOrLinkLocal("::ffff:192.168.68.117"))
    }

    @Test
    fun `a bracketed or zone-suffixed literal is unwrapped before classifying`() {
        assertEquals(LanScope.LINK_LOCAL, LanAddress.classify("[fe80::1]"))
        assertEquals(LanScope.LINK_LOCAL, LanAddress.classify("fe80::1%wlan0"))
        assertEquals(LanScope.LINK_LOCAL, LanAddress.classify("[fe80::1%25wlan0]"))
        assertTrue(LanAddress.isPrivateOrLinkLocal("[fd00::abcd]"))
    }

    // -- rejected ------------------------------------------------------------

    @Test
    fun `public IPv4 literals are rejected`() {
        listOf("8.8.8.8", "1.1.1.1", "203.0.113.10", "192.169.0.1", "11.0.0.1").forEach { host ->
            assertEquals(host, LanScope.NON_LOCAL, LanAddress.classify(host))
            assertFalse(host, LanAddress.isPrivateOrLinkLocal(host))
        }
    }

    @Test
    fun `loopback and the unspecified address are rejected`() {
        assertEquals(LanScope.LOOPBACK, LanAddress.classify("127.0.0.1"))
        assertEquals(LanScope.LOOPBACK, LanAddress.classify("127.255.255.255"))
        assertEquals(LanScope.NON_LOCAL, LanAddress.classify("0.0.0.0"))
        assertFalse(LanAddress.isPrivateOrLinkLocal("127.0.0.1"))
        assertFalse(LanAddress.isPrivateOrLinkLocal("0.0.0.0"))
    }

    @Test
    fun `multicast and broadcast are rejected`() {
        listOf("224.0.0.1", "239.255.255.250", "255.255.255.255").forEach { host ->
            assertEquals(host, LanScope.NON_LOCAL, LanAddress.classify(host))
            assertFalse(host, LanAddress.isPrivateOrLinkLocal(host))
        }
    }

    @Test
    fun `carrier-grade NAT is rejected`() {
        // 100.64.0.0/10 is neither RFC1918 nor a literal a TV would live on.
        assertEquals(LanScope.NON_LOCAL, LanAddress.classify("100.64.0.1"))
        assertEquals(LanScope.NON_LOCAL, LanAddress.classify("100.127.255.255"))
        assertFalse(LanAddress.isPrivateOrLinkLocal("100.64.0.1"))
    }

    @Test
    fun `the private range boundaries are exact`() {
        assertEquals(LanScope.PRIVATE, LanAddress.classify("172.16.0.0"))
        assertEquals(LanScope.PRIVATE, LanAddress.classify("172.31.255.255"))
        // One octet outside on either side of the 172.16.0.0/12 block.
        assertEquals(LanScope.NON_LOCAL, LanAddress.classify("172.15.255.255"))
        assertEquals(LanScope.NON_LOCAL, LanAddress.classify("172.32.0.0"))
        // Just outside the link-local block.
        assertEquals(LanScope.NON_LOCAL, LanAddress.classify("169.253.0.1"))
        assertEquals(LanScope.NON_LOCAL, LanAddress.classify("169.255.0.1"))
    }

    @Test
    fun `public and loopback IPv6 literals are rejected`() {
        assertEquals(LanScope.LOOPBACK, LanAddress.classify("::1"))
        assertEquals(LanScope.NON_LOCAL, LanAddress.classify("::"))
        assertEquals(LanScope.NON_LOCAL, LanAddress.classify("2001:db8::1"))
        assertEquals(LanScope.NON_LOCAL, LanAddress.classify("2001:4860:4860::8888"))
        assertEquals(LanScope.NON_LOCAL, LanAddress.classify("ff02::1"))
        assertEquals(LanScope.NON_LOCAL, LanAddress.classify("2606:4700:4700::1111"))
        assertFalse(LanAddress.isPrivateOrLinkLocal("::1"))
        assertFalse(LanAddress.isPrivateOrLinkLocal("2001:db8::1"))
    }

    @Test
    fun `an IPv4-mapped public address stays rejected`() {
        // Security property, independent of the mapped-prefix defect: a public v4
        // payload must never be accepted, whichever way the envelope is read.
        assertEquals(LanScope.NON_LOCAL, LanAddress.classify("::ffff:8.8.8.8"))
        assertFalse(LanAddress.isPrivateOrLinkLocal("::ffff:8.8.8.8"))
    }

    // -- never a name --------------------------------------------------------

    @Test
    fun `a hostname is never treated as a literal`() {
        // Resolving one would be the very network access this classifier exists
        // to prevent, so anything that is not an IP literal must be null.
        listOf("tv.local", "example.com", "localhost", "renderer", "fd00::g", "999.1.1.1")
            .forEach { host ->
                assertNull(host, LanAddress.classify(host))
                assertFalse(host, LanAddress.isPrivateOrLinkLocal(host))
            }
    }

    @Test
    fun `blank and null hosts are rejected`() {
        assertNull(LanAddress.classify(null))
        assertNull(LanAddress.classify(""))
        assertNull(LanAddress.classify("   "))
        assertFalse(LanAddress.isPrivateOrLinkLocal(null))
        assertFalse(LanAddress.isPrivateOrLinkLocal(""))
    }

    @Test
    fun `a literal with a leading zero is rejected`() {
        // 010 is octal to some stacks and decimal to others; there is no single
        // address it unambiguously names, so it must not be trusted.
        assertNull(LanAddress.classify("010.0.0.1"))
        assertNull(LanAddress.classify("192.168.001.1"))
        assertNull(LanAddress.classify("192.168.1.05"))
        assertFalse(LanAddress.isPrivateOrLinkLocal("010.0.0.1"))
    }

    @Test
    fun `malformed literals are rejected`() {
        listOf("192.168.1", "192.168.1.2.3", "192.168..1", "192.168.1.256", ":::", "1:2:3:4:5:6:7:8:9")
            .forEach { host ->
                assertNull(host, LanAddress.classify(host))
                assertFalse(host, LanAddress.isPrivateOrLinkLocal(host))
            }
    }

    // -- casting targets -----------------------------------------------------

    @Test
    fun `the cloud metadata address is not a casting target`() {
        // 169.254.169.254 sits inside the link-local block, so the pure
        // classifier still calls it link-local; only the casting allow-list
        // excludes it. Both spellings must be denied.
        assertEquals(LanScope.LINK_LOCAL, LanAddress.classify("169.254.169.254"))
        assertTrue(LanAddress.isPrivateOrLinkLocal("169.254.169.254"))
        assertEquals(LanScope.LINK_LOCAL, LanAddress.classify("::ffff:169.254.169.254"))
        assertTrue(LanAddress.isPrivateOrLinkLocal("::ffff:169.254.169.254"))

        assertFalse(LanAddress.isCastingTarget("169.254.169.254"))
        assertFalse(LanAddress.isCastingTarget("::ffff:169.254.169.254"))
        assertFalse(LanAddress.isCastingTarget("[::ffff:169.254.169.254]"))
    }

    @Test
    fun `ordinary private and link-local literals are casting targets`() {
        listOf("192.168.1.5", "10.0.0.9", "fe80::1", "172.16.0.1", "::ffff:192.168.1.5").forEach { host ->
            assertTrue(host, LanAddress.isCastingTarget(host))
        }
    }

    @Test
    fun `public loopback and hostnames are never casting targets`() {
        listOf("8.8.8.8", "203.0.113.10", "127.0.0.1", "tv.local", "example.com", "").forEach { host ->
            assertFalse(host, LanAddress.isCastingTarget(host))
        }
        assertFalse(LanAddress.isCastingTarget(null))
    }
}
