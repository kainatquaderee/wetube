package dev.wetube.app.player.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SOAP is the only place a video title reaches the wire, so escaping is pinned
 * here, together with the two shapes a device can answer with: a response body
 * and a fault. HTTP status is deliberately not part of this layer — a UPnP fault
 * arrives as HTTP 500 and must still be read.
 */
class UpnpSoapTest {

    @Test
    fun `the envelope carries the SOAP namespaces and the action element`() {
        val body = UpnpSoap.envelope(
            serviceType = AV_TRANSPORT,
            action = "Play",
            args = listOf("InstanceID" to "0", "Speed" to "1"),
        )

        assertTrue(
            body.contains(
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"" +
                    " s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">",
            ),
        )
        assertTrue(body.contains("<u:Play xmlns:u=\"$AV_TRANSPORT\">"))
        assertTrue(body.contains("<InstanceID>0</InstanceID>"))
        assertTrue(body.contains("<Speed>1</Speed>"))
        assertTrue(body.contains("</u:Play>"))
        assertTrue(body.endsWith("</s:Envelope>"))
    }

    @Test
    fun `arguments are XML-escaped`() {
        val body = UpnpSoap.envelope(
            serviceType = AV_TRANSPORT,
            action = "SetAVTransportURI",
            args = listOf("CurrentURI" to "http://cdn.test/a.mp4?x=1&y=2", "CurrentURIMetaData" to "<DIDL/>"),
        )

        assertTrue(body.contains("<CurrentURI>http://cdn.test/a.mp4?x=1&amp;y=2</CurrentURI>"))
        assertTrue(body.contains("<CurrentURIMetaData>&lt;DIDL/&gt;</CurrentURIMetaData>"))
    }

    @Test
    fun `escaping covers all five predefined entities`() {
        assertEquals(
            "A &amp; B &lt; C &gt; D &quot;E&quot; &apos;F&apos;",
            UpnpSoap.escape("A & B < C > D \"E\" 'F'"),
        )
    }

    @Test
    fun `escaping drops characters XML cannot represent but keeps tabs and newlines`() {
        assertEquals("ab", UpnpSoap.escape("a\u0000\u0001b"))
        assertEquals("a\tb\nc\rd", UpnpSoap.escape("a\tb\nc\rd"))
        assertEquals("🔥", UpnpSoap.escape("🔥"))
    }

    @Test
    fun `the SOAPACTION header is quoted the way devices expect`() {
        assertEquals(
            mapOf("SOAPACTION" to "\"$AV_TRANSPORT#SetAVTransportURI\""),
            UpnpSoap.actionHeaders(AV_TRANSPORT, "SetAVTransportURI"),
        )
    }

    @Test
    fun `an envelope with no arguments is valid`() {
        val body = UpnpSoap.envelope(AV_TRANSPORT, "Stop")

        assertTrue(body.contains("<u:Stop xmlns:u=\"$AV_TRANSPORT\"></u:Stop>"))
        assertTrue(body.startsWith("<?xml version=\"1.0\" encoding=\"utf-8\"?>"))
    }

    @Test
    fun `GetPositionInfo exposes RelTime and TrackDuration in milliseconds`() {
        val response = UpnpSoap.parse(POSITION_RESPONSE)

        val values = response as SoapResponse.Values
        val position = UpnpSoap.position(values)

        assertEquals(65_000L, position.positionMs)
        assertEquals(192_000L, position.durationMs)
        assertEquals("NOT_IMPLEMENTED", values.values["TrackMetaData"])
    }

    @Test
    fun `GetTransportInfo exposes the transport state`() {
        val response = UpnpSoap.parse(TRANSPORT_RESPONSE) as SoapResponse.Values

        assertEquals("PLAYING", UpnpSoap.transportState(response))
    }

    @Test
    fun `GetVolume exposes CurrentVolume as an integer`() {
        val response = UpnpSoap.parse(VOLUME_RESPONSE) as SoapResponse.Values

        assertEquals(42, UpnpSoap.volume(response))
    }

    @Test
    fun `an unreadable volume is null rather than zero`() {
        val response = UpnpSoap.parse(
            VOLUME_RESPONSE.replace("<CurrentVolume>42</CurrentVolume>", "<CurrentVolume>NOT_IMPLEMENTED</CurrentVolume>"),
        ) as SoapResponse.Values

        assertNull(UpnpSoap.volume(response))
    }

    @Test
    fun `a UPnP fault is detected and keeps its error code and description`() {
        val response = UpnpSoap.parse(FAULT_RESPONSE)

        val fault = response as SoapResponse.Fault
        assertEquals(714, fault.code)
        assertEquals("Illegal MIME-type", fault.detail)
    }

    @Test
    fun `a bare SOAP fault without a UPnP error block uses the fault string`() {
        val response = UpnpSoap.parse(
            """
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
              <s:Body>
                <s:Fault>
                  <faultcode>s:Client</faultcode>
                  <faultstring>Something went wrong</faultstring>
                </s:Fault>
              </s:Body>
            </s:Envelope>
            """.trimIndent(),
        )

        val fault = response as SoapResponse.Fault
        assertEquals(0, fault.code)
        assertEquals("Something went wrong", fault.detail)
    }

    @Test
    fun `the response prefix does not matter`() {
        val response = UpnpSoap.parse(
            """
            <m:Envelope xmlns:m="http://schemas.xmlsoap.org/soap/envelope/">
              <m:Body>
                <x:GetTransportInfoResponse xmlns:x="urn:schemas-upnp-org:service:AVTransport:1">
                  <CurrentTransportState>PAUSED_PLAYBACK</CurrentTransportState>
                </x:GetTransportInfoResponse>
              </m:Body>
            </m:Envelope>
            """.trimIndent(),
        ) as SoapResponse.Values

        assertEquals("PAUSED_PLAYBACK", UpnpSoap.transportState(response))
    }

    @Test
    fun `an empty response body is an empty value map, not a crash`() {
        val response = UpnpSoap.parse("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body/></s:Envelope>")

        assertNotNull(response)
        assertTrue(response is SoapResponse.Values)
        assertTrue((response as SoapResponse.Values).values.isEmpty())
    }

    @Test
    fun `a body that is not XML is null`() {
        assertNull(UpnpSoap.parse(null))
        assertNull(UpnpSoap.parse(""))
        assertNull(UpnpSoap.parse("404 Not Found"))
        assertNull(UpnpSoap.parse("<s:Envelope"))
    }

    @Test
    fun `a body carrying an internal doctype is refused before entities expand`() {
        // A billion-laughs style bomb. The shared parser must refuse the whole
        // document rather than expand the nested entities.
        val bomb = """
            <?xml version="1.0"?>
            <!DOCTYPE lolz [
              <!ENTITY lol "lol">
              <!ENTITY lol2 "&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;">
              <!ENTITY lol3 "&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;">
            ]>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
              <s:Body><u:GetTransportInfoResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                <CurrentTransportState>PLAYING</CurrentTransportState>
              </u:GetTransportInfoResponse></s:Body>
            </s:Envelope>
        """.trimIndent()

        assertNull(UpnpSoap.parse(bomb))
    }

    @Test
    fun `a normal response still parses once the doctype guard is in place`() {
        val response = UpnpSoap.parse(TRANSPORT_RESPONSE)

        assertNotNull(response)
        assertEquals("PLAYING", UpnpSoap.transportState(response as SoapResponse.Values))
    }

    @Test
    fun `a deeply nested response is walked without a stack overflow`() {
        // Well inside the 256 KB body cap but deep enough that a recursive walk
        // would raise StackOverflowError, which the catch (Exception) guards in
        // UpnpSoap.parse cannot catch.
        val depth = 50_000
        val xml = buildString {
            append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>")
            repeat(depth) { append("<n>") }
            append("<CurrentTransportState>PLAYING</CurrentTransportState>")
            repeat(depth) { append("</n>") }
            append("</s:Body></s:Envelope>")
        }

        val response = UpnpSoap.parse(xml)

        assertNotNull(response)
        assertEquals("PLAYING", UpnpSoap.transportState(response as SoapResponse.Values))
    }

    private companion object {
        const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"

        val POSITION_RESPONSE = """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
                        s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
              <s:Body>
                <u:GetPositionInfoResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                  <Track>1</Track>
                  <TrackDuration>0:03:12</TrackDuration>
                  <TrackMetaData>NOT_IMPLEMENTED</TrackMetaData>
                  <TrackURI>http://cdn.test/a.mp4</TrackURI>
                  <RelTime>0:01:05</RelTime>
                  <AbsTime>0:01:05</AbsTime>
                  <RelCount>2147483647</RelCount>
                  <AbsCount>2147483647</AbsCount>
                </u:GetPositionInfoResponse>
              </s:Body>
            </s:Envelope>
        """.trimIndent()

        val TRANSPORT_RESPONSE = """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
                        s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
              <s:Body>
                <u:GetTransportInfoResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                  <CurrentTransportState>PLAYING</CurrentTransportState>
                  <CurrentTransportStatus>OK</CurrentTransportStatus>
                  <CurrentSpeed>1</CurrentSpeed>
                </u:GetTransportInfoResponse>
              </s:Body>
            </s:Envelope>
        """.trimIndent()

        val VOLUME_RESPONSE = """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
                        s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
              <s:Body>
                <u:GetVolumeResponse xmlns:u="urn:schemas-upnp-org:service:RenderingControl:1">
                  <CurrentVolume>42</CurrentVolume>
                </u:GetVolumeResponse>
              </s:Body>
            </s:Envelope>
        """.trimIndent()

        val FAULT_RESPONSE = """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
                        s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
              <s:Body>
                <s:Fault>
                  <faultcode>s:Client</faultcode>
                  <faultstring>UPnPError</faultstring>
                  <detail>
                    <UPnPError xmlns="urn:schemas-upnp-org:control-1-0">
                      <errorCode>714</errorCode>
                      <errorDescription>Illegal MIME-type</errorDescription>
                    </UPnPError>
                  </detail>
                </s:Fault>
              </s:Body>
            </s:Envelope>
        """.trimIndent()
    }
}