package dev.wetube.app.player.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The per-URL policy discovery applies before a description becomes a target.
 *
 * Every control URL in a description is chosen by whatever answered the sweep,
 * so all three are validated. [NetworkRemoteDiscovery.targetFrom] is `internal`
 * instead of private precisely so this can be pinned here: the class itself
 * needs an Android `Context`, which a plain JVM unit test cannot supply.
 */
class NetworkRemoteDiscoveryTest {

    private fun response(location: String = "http://192.168.1.5:9197/dmr") = SsdpResponse(
        location = location,
        usn = "uuid:dev::urn:schemas-upnp-org:device:MediaRenderer:1",
        st = Ssdp.ST_MEDIA_RENDERER,
        server = null,
        headers = emptyMap(),
    )

    private fun device(
        avTransport: String? = "http://192.168.1.5:9197/av",
        renderingControl: String? = "http://192.168.1.5:9197/rc",
        connectionManager: String? = null,
    ) = UpnpDevice(
        deviceType = "urn:schemas-upnp-org:device:MediaRenderer:1",
        friendlyName = "TV",
        modelName = "M",
        udn = "uuid:dev",
        services = buildList {
            avTransport?.let { add(UpnpService("urn:schemas-upnp-org:service:AVTransport:1", it)) }
            renderingControl?.let { add(UpnpService("urn:schemas-upnp-org:service:RenderingControl:1", it)) }
            connectionManager?.let { add(UpnpService("urn:schemas-upnp-org:service:ConnectionManager:1", it)) }
        },
    )

    @Test
    fun `an off-LAN rendering control URL is dropped but the target survives`() {
        val target = NetworkRemoteDiscovery.targetFrom(
            device(renderingControl = "http://8.8.8.8/rc"),
            response(),
            "192.168.1.5",
        )!!

        assertEquals("http://192.168.1.5:9197/av", target.controlUrl)
        assertNull("volume must not be aimed at a public host", target.renderingControlUrl)
    }

    @Test
    fun `a metadata connection manager URL is dropped but the target survives`() {
        val target = NetworkRemoteDiscovery.targetFrom(
            device(connectionManager = "http://169.254.169.254/cm"),
            response(),
            "192.168.1.5",
        )!!

        assertEquals("http://192.168.1.5:9197/av", target.controlUrl)
        assertNull("the seek probe must not reach the metadata service", target.connectionManagerUrl)
    }

    @Test
    fun `each optional URL is validated on its own`() {
        val target = NetworkRemoteDiscovery.targetFrom(
            device(
                renderingControl = "http://169.254.169.254/rc",
                connectionManager = "http://10.0.0.1/cm",
            ),
            response(),
            "192.168.1.5",
        )!!

        assertNull(target.renderingControlUrl)
        assertEquals("http://10.0.0.1/cm", target.connectionManagerUrl)
    }

    @Test
    fun `an off-LAN AVTransport URL drops the whole target`() {
        assertNull(
            NetworkRemoteDiscovery.targetFrom(
                device(avTransport = "http://169.254.169.254/av"),
                response(),
                "192.168.1.5",
            ),
        )
    }

    @Test
    fun `a device with no AVTransport URL is not a target`() {
        assertNull(
            NetworkRemoteDiscovery.targetFrom(
                device(avTransport = null),
                response(),
                "192.168.1.5",
            ),
        )
    }

    @Test
    fun `a hostname control URL is refused rather than resolved`() {
        assertNull(
            NetworkRemoteDiscovery.targetFrom(
                device(avTransport = "http://renderer.local/av"),
                response(),
                "192.168.1.5",
            ),
        )
        assertNull(
            NetworkRemoteDiscovery.targetFrom(
                device(renderingControl = "http://renderer.local/rc"),
                response(),
                "192.168.1.5",
            )?.renderingControlUrl,
        )
    }

    @Test
    fun `a fully local description keeps every control URL`() {
        val target = NetworkRemoteDiscovery.targetFrom(
            device(connectionManager = "http://192.168.1.5:9197/cm"),
            response(),
            "192.168.1.5",
        )!!

        assertEquals("http://192.168.1.5:9197/av", target.controlUrl)
        assertEquals("http://192.168.1.5:9197/rc", target.renderingControlUrl)
        assertEquals("http://192.168.1.5:9197/cm", target.connectionManagerUrl)
    }
}
