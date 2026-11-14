package dev.wetube.app.player.remote

import java.io.IOException
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [RemoteRenderer] over the UPnP AV actions a DLNA media renderer implements.
 *
 * Every verb becomes one SOAP call against the control URL the device itself
 * published, so nothing here hard-codes a model, a vendor or a port. The three
 * timeouts are budgets for the caller's patience, not socket settings: the
 * transport owned by [LanHttp] keeps its own (longer) socket timeouts, so the
 * budget is what a responsive-then-quiet device is measured against and a black
 * hole is still bounded by the socket.
 *
 * Failures are mapped once, here, so nothing above has to read a UPnP error
 * code: `714` (illegal MIME) and `716` (resource not playable) mean the device
 * cannot decode what we sent and become [RemoteFailure.Unsupported]; any other
 * fault keeps its code, and a dead endpoint becomes [RemoteFailure.Unreachable].
 */
class DlnaRenderer(
    private val target: RemoteTarget,
    private val http: LanHttp,
) : RemoteRenderer {

    override suspend fun probe(caps: RemoteCapabilities): RemoteCapabilities {
        // Advisory throughout: anything unreadable keeps the optimistic default
        // and is discovered at call time instead.
        val canSeek = withTimeoutOrNull(PROBE_BUDGET_MS) { advertisedTimeSeek() } ?: caps.canSeek
        val hasVolume = if (target.renderingControlUrl == null) {
            false
        } else {
            withTimeoutOrNull(PROBE_BUDGET_MS) { runCatching { volume() }.getOrNull() } != null
        }
        return caps.copy(canSeek = canSeek, hasVolume = hasVolume)
    }

    override suspend fun load(media: RemoteMedia) {
        val didl = Didl.build(
            url = media.url,
            mime = media.mime,
            title = media.title,
            creator = media.creator,
            artworkUrl = media.artworkUrl,
            audioOnly = media.audioOnly,
        )
        call(
            url = target.controlUrl,
            serviceType = AV_TRANSPORT,
            action = "SetAVTransportURI",
            args = listOf(
                "InstanceID" to DEFAULT_INSTANCE,
                "CurrentURI" to media.url,
                "CurrentURIMetaData" to didl,
            ),
            budgetMs = LOAD_BUDGET_MS,
        )
    }

    override suspend fun play() {
        call(
            target.controlUrl,
            AV_TRANSPORT,
            "Play",
            listOf("InstanceID" to DEFAULT_INSTANCE, "Speed" to "1"),
            TRANSPORT_BUDGET_MS,
        )
    }

    override suspend fun pause() {
        instanceAction("Pause")
    }

    override suspend fun stop() {
        instanceAction("Stop")
    }

    override suspend fun seekTo(positionMs: Long) {
        call(
            target.controlUrl,
            AV_TRANSPORT,
            "Seek",
            listOf(
                "InstanceID" to DEFAULT_INSTANCE,
                "Unit" to "REL_TIME",
                "Target" to UpnpTime.format(positionMs),
            ),
            TRANSPORT_BUDGET_MS,
        )
    }

    override suspend fun position(): RemotePosition {
        val values = call(
            target.controlUrl,
            AV_TRANSPORT,
            "GetPositionInfo",
            listOf("InstanceID" to DEFAULT_INSTANCE),
            TRANSPORT_BUDGET_MS,
        )
        val info = UpnpSoap.position(values)
        return RemotePosition(info.positionMs, info.durationMs)
    }

    override suspend fun transportState(): RemoteTransport {
        val values = call(
            target.controlUrl,
            AV_TRANSPORT,
            "GetTransportInfo",
            listOf("InstanceID" to DEFAULT_INSTANCE),
            TRANSPORT_BUDGET_MS,
        )
        return transportOf(UpnpSoap.transportState(values))
    }

    override suspend fun volume(): Float? {
        val url = target.renderingControlUrl ?: return null
        val values = call(
            url,
            RENDERING_CONTROL,
            "GetVolume",
            listOf("InstanceID" to DEFAULT_INSTANCE, "Channel" to CHANNEL_MASTER),
            TRANSPORT_BUDGET_MS,
        )
        return UpnpSoap.volume(values)?.let { it / 100f }
    }

    override suspend fun setVolume(level: Float) {
        val url = target.renderingControlUrl
            ?: throw RemoteCallException(RemoteFailure.Unsupported)
        val percent = (level.coerceIn(0f, 1f) * 100f).roundToInt()
        call(
            url,
            RENDERING_CONTROL,
            "SetVolume",
            listOf(
                "InstanceID" to DEFAULT_INSTANCE,
                "Channel" to CHANNEL_MASTER,
                "DesiredVolume" to percent.toString(),
            ),
            TRANSPORT_BUDGET_MS,
        )
    }

    // -- internals ----------------------------------------------------------

    private suspend fun instanceAction(action: String) = call(
        target.controlUrl,
        AV_TRANSPORT,
        action,
        listOf("InstanceID" to DEFAULT_INSTANCE),
        TRANSPORT_BUDGET_MS,
    ).let { }

    /**
     * `ConnectionManager.GetProtocolInfo` → whether the renderer advertises
     * time-based seeking. `DLNA.ORG_OP` is two digits: byte-range, then time.
     * We seek by `REL_TIME`, so only the second digit matters.
     */
    private suspend fun advertisedTimeSeek(): Boolean? {
        val url = target.connectionManagerUrl ?: return null
        val values = call(url, CONNECTION_MANAGER, "GetProtocolInfo", emptyList(), PROBE_BUDGET_MS)
        val sink = values.values["Sink"] ?: return null
        val operations = OP_PATTERN.find(sink)?.groupValues?.getOrNull(1) ?: return null
        return operations.length >= 2 && operations[1] == '1'
    }

    /**
     * One SOAP call, with the failure mapping this class exists to centralise.
     *
     * Cancellation is rethrown untouched — a caller that went away is not a
     * renderer failure — and `TimeoutCancellationException` is checked first
     * because it *is* one.
     */
    private suspend fun call(
        url: String,
        serviceType: String,
        action: String,
        args: List<Pair<String, String>>,
        budgetMs: Long,
    ): SoapResponse.Values {
        val body = UpnpSoap.envelope(serviceType, action, args)
        val raw = try {
            withTimeout(budgetMs) {
                http.post(url, UpnpSoap.CONTENT_TYPE, UpnpSoap.actionHeaders(serviceType, action), body)
            }
        } catch (timeout: TimeoutCancellationException) {
            throw RemoteCallException(RemoteFailure.Unreachable(target.address), timeout)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            // A socket error, a malformed URL, a device that closed early: all
            // one thing to the user, and none of them worth an exception type.
            throw RemoteCallException(RemoteFailure.Unreachable(target.address), failure)
        }

        return when (val parsed = UpnpSoap.parse(raw)) {
            // Not XML at all: the endpoint answered, but not as a SOAP service.
            null -> throw RemoteCallException(
                RemoteFailure.Unreachable(target.address),
                IOException("Renderer did not answer with SOAP"),
            )

            is SoapResponse.Fault -> throw RemoteCallException(faultOf(parsed))

            is SoapResponse.Values -> parsed
        }
    }

    /** `714`/`716` are the two codes that mean "this device cannot play this". */
    private fun faultOf(fault: SoapResponse.Fault): RemoteFailure = when (fault.code) {
        ERROR_ILLEGAL_MIME, ERROR_UNABLE_TO_PLAY -> RemoteFailure.Unsupported
        else -> RemoteFailure.SoapFault(fault.code, fault.detail)
    }

    private fun transportOf(state: String?): RemoteTransport =
        when (state?.trim()?.uppercase(Locale.US)) {
            "PLAYING" -> RemoteTransport.PLAYING
            "PAUSED_PLAYBACK", "PAUSED_RECORDING" -> RemoteTransport.PAUSED
            // Both "stopped" and "nothing loaded" are the end of the media from
            // the point of view of a position poll, which is all this feeds.
            "STOPPED", "NO_MEDIA_PRESENT" -> RemoteTransport.STOPPED
            "TRANSITIONING" -> RemoteTransport.TRANSITIONING
            else -> RemoteTransport.OTHER
        }

    private companion object {
        const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
        const val RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:1"
        const val CONNECTION_MANAGER = "urn:schemas-upnp-org:service:ConnectionManager:1"

        const val DEFAULT_INSTANCE = "0"
        const val CHANNEL_MASTER = "Master"

        /** `714` illegal MIME type. */
        const val ERROR_ILLEGAL_MIME = 714

        /** `716` resource not found / not playable. */
        const val ERROR_UNABLE_TO_PLAY = 716

        /** A probe the user is waiting behind, once per connection. */
        const val PROBE_BUDGET_MS = 3_000L

        /** Handing over the URI is the one call a TV may take its time with. */
        const val LOAD_BUDGET_MS = 6_000L

        /** Play/pause/seek/position: a control port that is alive answers fast. */
        const val TRANSPORT_BUDGET_MS = 4_000L

        val OP_PATTERN = Regex("DLNA\\.ORG_OP=([0-9A-Fa-f]+)")
    }
}
