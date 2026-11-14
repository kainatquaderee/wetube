package dev.wetube.app.player.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Discovery is the part that talks to every device on the network, including a
 * hostile one, so the parser is pinned to the exact request bytes and to
 * rejecting anything that is not a usable 2xx answer.
 */
class SsdpTest {

    @Test
    fun `the renderer search is the exact M-SEARCH from the protocol`() {
        val text = String(Ssdp.mediaRendererSearch, Charsets.US_ASCII)

        assertEquals(
            "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: 239.255.255.250:1900\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 2\r\n" +
                "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
                "\r\n",
            text,
        )
    }

    @Test
    fun `the request is terminated by a blank line so a device can answer`() {
        val text = String(Ssdp.mediaRendererSearch, Charsets.US_ASCII)

        assertTrue(text.endsWith("\r\n\r\n"))
        // Five header lines plus the terminating blank line.
        assertEquals(6, text.count { it == '\n' })
    }

    @Test
    fun `the catch-all sweep asks for ssdp all`() {
        val text = String(Ssdp.search(Ssdp.ST_ALL), Charsets.US_ASCII)

        assertTrue(text.contains("ST: ssdp:all\r\n"))
        assertTrue(text.contains("MX: 2\r\n"))
    }

    @Test
    fun `a normal response is parsed case insensitively`() {
        val response = Ssdp.parse(RENDERER_RESPONSE)

        assertNotNull(response)
        assertEquals("http://192.168.1.42:9197/dmr", response!!.location)
        assertEquals(
            "uuid:12345678-1234-1234-1234-123456789abc::urn:schemas-upnp-org:device:MediaRenderer:1",
            response.usn,
        )
        assertEquals(Ssdp.ST_MEDIA_RENDERER, response.st)
        assertEquals("Linux/4.9 UPnP/1.0 Samsung UPnP SDK/1.0", response.server)
    }

    @Test
    fun `header names are matched whatever their case`() {
        val response = Ssdp.parse(
            "HTTP/1.1 200 OK\r\n" +
                "LoCaTiOn: http://192.168.1.9:1400/xml/device.xml\r\n" +
                "uSn: uuid:abc::urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
                "sT: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n",
        )

        assertNotNull(response)
        assertEquals("http://192.168.1.9:1400/xml/device.xml", response!!.location)
    }

    @Test
    fun `a non-2xx status is ignored`() {
        assertNull(Ssdp.parse(RENDERER_RESPONSE.replaceFirst("200 OK", "404 Not Found")))
        assertNull(Ssdp.parse(RENDERER_RESPONSE.replaceFirst("200 OK", "500 Internal Server Error")))
    }

    @Test
    fun `a response without a LOCATION is ignored`() {
        val withoutLocation = RENDERER_RESPONSE
            .lines()
            .filterNot { it.startsWith("LOCATION", ignoreCase = true) }
            .joinToString("\r\n")

        assertNull(Ssdp.parse(withoutLocation))
    }

    @Test
    fun `a response without a USN or ST is ignored`() {
        val withoutUsn = RENDERER_RESPONSE.lines()
            .filterNot { it.startsWith("USN", ignoreCase = true) }
            .joinToString("\r\n")
        val withoutSt = RENDERER_RESPONSE.lines()
            .filterNot { it.startsWith("ST:", ignoreCase = true) }
            .joinToString("\r\n")

        assertNull(Ssdp.parse(withoutUsn))
        assertNull(Ssdp.parse(withoutSt))
    }

    @Test
    fun `a blank LOCATION is treated as missing`() {
        val blankLocation = RENDERER_RESPONSE.replace(
            "LOCATION: http://192.168.1.42:9197/dmr",
            "LOCATION:    ",
        )

        assertNull(Ssdp.parse(blankLocation))
    }

    @Test
    fun `a notification is not a search response`() {
        assertNull(Ssdp.parse("NOTIFY * HTTP/1.1\r\nLOCATION: http://192.168.1.5:1/x\r\nUSN: uuid:x\r\nST: x\r\n\r\n"))
    }

    @Test
    fun `garbage and empty input are ignored`() {
        assertNull(Ssdp.parse(null))
        assertNull(Ssdp.parse(""))
        assertNull(Ssdp.parse("   "))
        assertNull(Ssdp.parse("HTTP/1.1 maybe ok\r\nLOCATION: http://x/y\r\n"))
    }

    @Test
    fun `a repeated header keeps the first occurrence`() {
        val response = Ssdp.parse(
            "HTTP/1.1 200 OK\r\n" +
                "LOCATION: http://192.168.1.9:1400/first.xml\r\n" +
                "LOCATION: http://192.168.1.9:1400/second.xml\r\n" +
                "USN: uuid:abc::urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
                "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n",
        )

        assertEquals("http://192.168.1.9:1400/first.xml", response!!.location)
    }

    private companion object {
        /** Captured shape of a real Samsung TV answer, header case preserved. */
        const val RENDERER_RESPONSE = "HTTP/1.1 200 OK\r\n" +
            "CACHE-CONTROL: max-age=1800\r\n" +
            "DATE: Fri, 13 Nov 2026 10:00:00 GMT\r\n" +
            "EXT:\r\n" +
            "LOCATION: http://192.168.1.42:9197/dmr\r\n" +
            "SERVER: Linux/4.9 UPnP/1.0 Samsung UPnP SDK/1.0\r\n" +
            "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
            "USN: uuid:12345678-1234-1234-1234-123456789abc::urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
            "\r\n"
    }
}