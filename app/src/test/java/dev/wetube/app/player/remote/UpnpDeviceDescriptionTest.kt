package dev.wetube.app.player.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The device description is where a hand-rolled control point usually breaks:
 * the `controlURL` is relative to the `LOCATION` it was fetched from, and the
 * namespace form varies by vendor. Both shapes are pinned here, plus the
 * rejection rules that keep a MediaServer out of the renderer picker.
 */
class UpnpDeviceDescriptionTest {

    @Test
    fun `a Samsung description with relative control URLs is resolved against the location`() {
        val device = UpnpDeviceDescription.parse(SAMSUNG_XML, "http://192.168.1.42:9197/dmr")!!

        assertEquals("urn:schemas-upnp-org:device:MediaRenderer:1", device.deviceType)
        assertEquals("[TV] Living Room TV", device.friendlyName)
        assertEquals("UE55NU8000", device.modelName)
        assertEquals("uuid:12345678-1234-1234-1234-123456789abc", device.udn)
        assertEquals(
            "http://192.168.1.42:9197/AVTransport/1234/control.xml",
            device.avTransportUrl,
        )
        assertEquals(
            "http://192.168.1.42:9197/RenderingControl/1234/control.xml",
            device.renderingControlUrl,
        )
        assertNull(device.connectionManagerUrl)
    }

    @Test
    fun `an absolute control URL is left alone`() {
        val device = UpnpDeviceDescription.parse(LG_XML, "http://192.168.1.77:1350/description.xml")!!

        assertEquals("http://192.168.1.77:1350/control/AVTransport", device.avTransportUrl)
        assertEquals("http://192.168.1.77:1350/control/RenderingControl", device.renderingControlUrl)
    }

    @Test
    fun `namespace prefixes on every element are tolerated`() {
        val device = UpnpDeviceDescription.parse(LG_XML, "http://192.168.1.77:1350/description.xml")!!

        assertEquals("LG OLED55C9", device.friendlyName)
        assertEquals("OLED55C9PUA", device.modelName)
        assertEquals("uuid:77777777-7777-7777-7777-777777777777", device.udn)
    }

    @Test
    fun `a relative control URL without a leading slash is resolved against the description directory`() {
        val xml = SAMSUNG_XML.replace(
            "<controlURL>/AVTransport/1234/control.xml</controlURL>",
            "<controlURL>AVTransport/control.xml</controlURL>",
        )

        val device = UpnpDeviceDescription.parse(xml, "http://192.168.1.42:9197/xml/dmr.xml")!!

        assertEquals("http://192.168.1.42:9197/xml/AVTransport/control.xml", device.avTransportUrl)
    }

    @Test
    fun `a media server is not a renderer`() {
        val serverXml = SAMSUNG_XML.replace(
            "urn:schemas-upnp-org:device:MediaRenderer:1",
            "urn:schemas-upnp-org:device:MediaServer:1",
        )

        assertNull(UpnpDeviceDescription.parse(serverXml, "http://192.168.1.42:9197/dmr"))
    }

    @Test
    fun `malformed xml yields null instead of throwing`() {
        assertNull(UpnpDeviceDescription.parse("<root><device><deviceType>", "http://192.168.1.42/dmr"))
        assertNull(UpnpDeviceDescription.parse("not xml at all", "http://192.168.1.42/dmr"))
        assertNull(UpnpDeviceDescription.parse("", "http://192.168.1.42/dmr"))
        assertNull(UpnpDeviceDescription.parse(null, "http://192.168.1.42/dmr"))
    }

    @Test
    fun `a description without a location cannot resolve its control URLs`() {
        assertNull(UpnpDeviceDescription.parse(SAMSUNG_XML, null))
        assertNull(UpnpDeviceDescription.parse(SAMSUNG_XML, "   "))
        assertNull(UpnpDeviceDescription.parse(SAMSUNG_XML, "not a uri"))
    }

    @Test
    fun `a doctype is refused before it reaches the parser`() {
        val withDoctype = SAMSUNG_XML.replace(
            "<root",
            "<!DOCTYPE root [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>\n<root",
        )

        assertNull(UpnpDeviceDescription.parse(withDoctype, "http://192.168.1.42:9197/dmr"))
    }

    @Test
    fun `a device without a service list is still a valid renderer with no controls`() {
        val withoutServices = SAMSUNG_XML.substringBefore("<serviceList>") + "</device></root>"

        val device = UpnpDeviceDescription.parse(withoutServices, "http://192.168.1.42:9197/dmr")!!

        assertEquals("[TV] Living Room TV", device.friendlyName)
        assertNull(device.avTransportUrl)
        assertTrue(device.services.isEmpty())
    }

    @Test
    fun `service types are matched by name whatever their version`() {
        val versionTwo = SAMSUNG_XML.replace("service:AVTransport:1", "service:AVTransport:2")

        val device = UpnpDeviceDescription.parse(versionTwo, "http://192.168.1.42:9197/dmr")!!

        assertEquals(
            "http://192.168.1.42:9197/AVTransport/1234/control.xml",
            device.avTransportUrl,
        )
    }

    @Test
    fun `a service without a control URL is skipped`() {
        val xml = SAMSUNG_XML.replace(
            "<controlURL>/AVTransport/1234/control.xml</controlURL>",
            "<controlURL>   </controlURL>",
        )

        val device = UpnpDeviceDescription.parse(xml, "http://192.168.1.42:9197/dmr")!!

        assertNull(device.avTransportUrl)
        assertTrue(device.services.none { it.serviceType.contains("AVTransport") })
    }

    @Test
    fun `a description that is not a device element is rejected`() {
        assertNull(
            UpnpDeviceDescription.parse(
                "<root xmlns=\"urn:schemas-upnp-org:device-1-0\"><specVersion/></root>",
                "http://192.168.1.42/dmr",
            ),
        )
    }

    @Test
    fun `the friendly name may be empty without dropping the device`() {
        val xml = SAMSUNG_XML.replace("[TV] Living Room TV", "")

        val device = UpnpDeviceDescription.parse(xml, "http://192.168.1.42:9197/dmr")!!

        assertEquals("", device.friendlyName)
        assertNotNull(device.avTransportUrl)
    }

    @Test
    fun `a deeply nested document is walked without a stack overflow`() {
        // The walker in this file is used on every parsed document. A recursive
        // implementation overflows the JVM stack here; StackOverflowError is an
        // Error, so no catch (Exception) upstream would stop it.
        val depth = 50_000
        val xml = buildString {
            append("<root>")
            repeat(depth) { append("<n>") }
            append("<needle>x</needle>")
            repeat(depth) { append("</n>") }
            append("</root>")
        }

        val root = parseXmlDocument(xml)!!.documentElement

        assertNotNull(root.descendant("needle"))
        assertNull(root.descendant("absent"))
    }

    private companion object {
        /**
         * Captured shape of a Samsung TV description. Every control URL is
         * relative to the `LOCATION` — the real-device case this parser exists
         * for.
         */
        val SAMSUNG_XML = """
            <?xml version="1.0" encoding="utf-8"?>
            <root xmlns="urn:schemas-upnp-org:device-1-0">
              <specVersion>
                <major>1</major>
                <minor>0</minor>
              </specVersion>
              <device>
                <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
                <friendlyName>[TV] Living Room TV</friendlyName>
                <manufacturer>Samsung Electronics</manufacturer>
                <modelName>UE55NU8000</modelName>
                <UDN>uuid:12345678-1234-1234-1234-123456789abc</UDN>
                <serviceList>
                  <service>
                    <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                    <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
                    <SCPDURL>/AVTransport/1234/scpd.xml</SCPDURL>
                    <controlURL>/AVTransport/1234/control.xml</controlURL>
                    <eventSubURL>/AVTransport/1234/event.xml</eventSubURL>
                  </service>
                  <service>
                    <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
                    <serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId>
                    <SCPDURL>/RenderingControl/1234/scpd.xml</SCPDURL>
                    <controlURL>/RenderingControl/1234/control.xml</controlURL>
                    <eventSubURL>/RenderingControl/1234/event.xml</eventSubURL>
                  </service>
                </serviceList>
              </device>
            </root>
        """.trimIndent()

        /** Captured shape of an LG description: fully prefixed, absolute URLs. */
        val LG_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:root xmlns:d="urn:schemas-upnp-org:device-1-0">
              <d:specVersion><d:major>1</d:major><d:minor>0</d:minor></d:specVersion>
              <d:device>
                <d:deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</d:deviceType>
                <d:friendlyName>LG OLED55C9</d:friendlyName>
                <d:manufacturer>LG Electronics</d:manufacturer>
                <d:modelName>OLED55C9PUA</d:modelName>
                <d:UDN>uuid:77777777-7777-7777-7777-777777777777</d:UDN>
                <d:serviceList>
                  <d:service>
                    <d:serviceType>urn:schemas-upnp-org:service:AVTransport:1</d:serviceType>
                    <d:controlURL>http://192.168.1.77:1350/control/AVTransport</d:controlURL>
                  </d:service>
                  <d:service>
                    <d:serviceType>urn:schemas-upnp-org:service:RenderingControl:1</d:serviceType>
                    <d:controlURL>http://192.168.1.77:1350/control/RenderingControl</d:controlURL>
                  </d:service>
                </d:serviceList>
              </d:device>
            </d:root>
        """.trimIndent()
    }
}