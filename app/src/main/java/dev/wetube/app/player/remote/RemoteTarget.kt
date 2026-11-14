package dev.wetube.app.player.remote

/**
 * How a [RemoteTarget] is controlled.
 *
 * DLNA/UPnP-AV is the only protocol we speak today; [CUSTOM] reserves the seam
 * for an open receiver app that would pair with us over its own wire format, so
 * the picker and the playback manager never have to ask which family they are
 * talking to.
 */
enum class RemoteProtocol { DLNA, CUSTOM }

/**
 * What we may ask a renderer to do.
 *
 * Everything defaults to `true` because that is what a plain UPnP media
 * renderer claims, and the probe that would prove otherwise is advisory: a TV
 * that lies here still answers with `s:Fault` at call time, which the renderer
 * turns into [RemoteFailure.Unsupported]. The flags exist so the UI can hide a
 * control up front rather than offer a button that always fails.
 */
data class RemoteCapabilities(
    val canSeek: Boolean = true,
    val hasVolume: Boolean = true,
    val canPause: Boolean = true,
)

/**
 * A device the user can send playback to.
 *
 * [id] is the device's UPnP `UDN` and is the only stable identity across
 * rediscoveries; [friendlyName] is what the picker shows and can be duplicated
 * by two devices on the same network. The control URLs are already absolute
 * — device descriptions routinely ship them relative to the `LOCATION` they were
 * fetched from, and resolving that is [UpnpDeviceDescription]'s job, not the
 * caller's. [address] is the host the `LOCATION` pointed at; it stays in memory
 * for the session and is never logged in release builds.
 *
 * [connectionManagerUrl] is optional and used only by the advisory probe, which
 * reads the renderer's advertised operations to decide whether the seek control
 * is worth offering up front. A device that omits it simply keeps the defaults.
 */
data class RemoteTarget(
    val id: String,
    val friendlyName: String,
    val modelName: String = "",
    val protocol: RemoteProtocol = RemoteProtocol.DLNA,
    val controlUrl: String,
    val renderingControlUrl: String? = null,
    val address: String,
    val capabilities: RemoteCapabilities = RemoteCapabilities(),
    val connectionManagerUrl: String? = null,
)
