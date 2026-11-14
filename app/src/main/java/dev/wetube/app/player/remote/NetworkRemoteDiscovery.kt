package dev.wetube.app.player.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * [RemoteDiscovery] over SSDP, the discovery half of UPnP.
 *
 * A sweep is deliberately short and repeated. The first and third send the
 * typed `MediaRenderer` search, which is all a well-behaved device answers; the
 * second asks `ssdp:all`, because a handful of TVs only reply to the catch-all
 * and are then filtered by their device type. Results are merged by device
 * identity so a device that answers three sweeps is listed once.
 *
 * Discovery is only reachable while the device sheet is open: [start] runs
 * sweeps on a loop and [stop] ends it, so nothing scans in the background and
 * the multicast lock is never held by a screen the user has left.
 *
 * Two safety rules run on every answer. The host a `LOCATION` points at — and
 * the host of every `controlURL` it resolves to — must be a casting target per
 * [LanAddress.isCastingTarget]; anything else is dropped, so a hostile responder
 * cannot redirect the app at a host on the internet or at the cloud metadata
 * service. An off-LAN AVTransport URL drops the device; an off-LAN volume or
 * connection-manager URL only disables that feature. And a device description
 * is fetched only over the same private address it was advertised from.
 */
class NetworkRemoteDiscovery(
    context: Context,
    private val http: LanHttp = RawSocketLanHttp(),
) : RemoteDiscovery {

    private val appContext = context.applicationContext

    private val _targets = MutableStateFlow<List<RemoteTarget>>(emptyList())
    override val targets: StateFlow<List<RemoteTarget>> = _targets.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    override val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _failure = MutableStateFlow<RemoteFailure?>(null)
    override val failure: StateFlow<RemoteFailure?> = _failure.asStateFlow()

    /** Owns the scanning loop; a plain job, not a thread of its own. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var scanJob: Job? = null

    /** One sweep at a time: the loop and a manual refresh must not share a socket. */
    private val refreshMutex = Mutex()

    override fun start() {
        if (scanJob?.isActive == true) return
        scanJob = scope.launch {
            while (isActive) {
                refresh()
                delay(RESCAN_INTERVAL_MS)
            }
        }
    }

    override fun stop() {
        scanJob?.cancel()
        scanJob = null
        _targets.value = emptyList()
        _failure.value = null
        _scanning.value = false
    }

    override suspend fun refresh(): List<RemoteTarget> = refreshMutex.withLock {
        withContext(Dispatchers.IO) {
            val gate = gate()
            if (gate is LanGate.Blocked) {
                // A network that cannot carry casting is a reason to say so, not
                // an empty list the sheet would read as "no devices".
                _failure.value = gate.failure
                _scanning.value = false
                return@withContext _targets.value
            }
            val wifi = (gate as LanGate.OnLan).wifi

            _scanning.value = true
            try {
                val outcome = scan(wifi)
                coroutineContext.ensureActive()
                when (outcome) {
                    is SweepOutcome.Blocked -> {
                        _failure.value = outcome.failure
                        _targets.value
                    }

                    is SweepOutcome.Found -> {
                        _targets.value = outcome.targets
                        _failure.value = if (outcome.targets.isEmpty()) {
                            RemoteFailure.NoRendererFound
                        } else {
                            null
                        }
                        outcome.targets
                    }
                }
            } finally {
                _scanning.value = false
            }
        }
    }

    /**
     * The network gate, exposed for the cast button's preflight.
     *
     * [refresh] already consults this before opening a socket; surfacing it lets
     * the UI ask the same question without running a whole sweep first.
     */
    override fun castBlockedReason(): RemoteFailure? =
        (gate() as? LanGate.Blocked)?.failure

    // -- sweep --------------------------------------------------------------

    private sealed interface SweepOutcome {
        data class Found(val targets: List<RemoteTarget>) : SweepOutcome
        data class Blocked(val failure: RemoteFailure) : SweepOutcome
    }

    /**
     * Runs the three sweeps and resolves every answer to a target.
     *
     * The lock is taken for the whole sweep and released in `finally`, so a
     * cancelled coroutine or a throwing socket cannot leave it held. On an
     * ethernet-only network the lock is skipped entirely: multicast is already
     * delivered, and taking a Wi-Fi lock there is the one way this can fail.
     */
    private suspend fun scan(wifi: Boolean): SweepOutcome {
        val lock = try {
            acquireMulticastLock(wifi)
        } catch (denied: SecurityException) {
            return SweepOutcome.Blocked(RemoteFailure.PermissionDenied)
        }

        return try {
            val byId = LinkedHashMap<String, RemoteTarget>()
            val seenUsn = HashSet<String>()
            val seenHosts = HashSet<String>()
            for (searchTarget in SWEEPS) {
                for (response in sweep(searchTarget)) {
                    if (!seenUsn.add(response.usn)) continue
                    val host = hostOf(response.location) ?: continue
                    if (!LanAddress.isCastingTarget(host)) continue
                    if (host in seenHosts) continue
                    val target = describe(response, host) ?: continue
                    seenHosts.add(host)
                    byId[target.id] = target
                }
            }
            SweepOutcome.Found(byId.values.toList())
        } finally {
            lock?.release()
        }
    }

    /**
     * One `M-SEARCH` and the answers it collects.
     *
     * The socket's read timeout is the whole response window: the loop stops on
     * the first timeout, which is why `MX` is kept small. Failures are turned
     * into "nobody answered" rather than exceptions — a sweep that could not run
     * is reported by [refresh] through [failure], not by throwing at the sheet.
     */
    private fun sweep(searchTarget: String): List<SsdpResponse> {
        val responses = ArrayList<SsdpResponse>()
        val request = Ssdp.search(searchTarget)
        val group = InetAddress.getByName(Ssdp.MULTICAST_HOST)

        DatagramSocket().use { socket ->
            socket.soTimeout = RESPONSE_WINDOW_MS
            val greeting = DatagramPacket(request, request.size, group, Ssdp.MULTICAST_PORT)
            try {
                socket.send(greeting)
            } catch (failure: IOException) {
                return emptyList()
            }

            val buffer = ByteArray(MAX_DATAGRAM_BYTES)
            while (true) {
                val incoming = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(incoming)
                } catch (timeout: SocketTimeoutException) {
                    break
                } catch (failure: IOException) {
                    break
                }
                val text = String(incoming.data, incoming.offset, incoming.length, Charsets.ISO_8859_1)
                Ssdp.parse(text)?.let(responses::add)
            }
        }
        return responses
    }

    /** Fetches and parses the description behind one answer, or drops it. */
    private suspend fun describe(response: SsdpResponse, host: String): RemoteTarget? {
        val xml = runCatching { http.get(response.location) }.getOrNull() ?: return null
        val device = UpnpDeviceDescription.parse(xml, response.location) ?: return null
        return targetFrom(device, response, host)
    }

    // -- platform -----------------------------------------------------------

    private sealed interface LanGate {
        data class OnLan(val wifi: Boolean) : LanGate
        data class Blocked(val failure: RemoteFailure) : LanGate
    }

    /**
     * Whether the active network can reach a renderer.
     *
     * A renderer is a device on the user's own network, so mobile data and a
     * VPN-only route both mean casting cannot work and are reported as such.
     * Ethernet counts: a TV on the same wired switch is reachable without Wi-Fi.
     */
    private fun gate(): LanGate {
        // Forward-compat seam: a future targetSdk gates LAN sockets behind a
        // runtime permission. Until then this answers `true` on every device,
        // but the check lives here so the socket code never has to learn about
        // permissions.
        if (!LocalNetworkAccess.canScan(appContext)) {
            return LanGate.Blocked(RemoteFailure.PermissionDenied)
        }
        val manager = appContext.getSystemService(ConnectivityManager::class.java)
            ?: return LanGate.Blocked(RemoteFailure.NoNetwork)
        val network = manager.activeNetwork ?: return LanGate.Blocked(RemoteFailure.NoNetwork)
        val capabilities = manager.getNetworkCapabilities(network)
            ?: return LanGate.Blocked(RemoteFailure.NoNetwork)

        val wifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val ethernet = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        if (!wifi && !ethernet) return LanGate.Blocked(RemoteFailure.NotOnLan)
        return LanGate.OnLan(wifi = wifi && !ethernet)
    }

    /**
     * The `MulticastLock` a Wi-Fi sweep needs, or `null` when it must be skipped.
     *
     * Android filters multicast for ordinary sockets to save power; without the
     * lock an `M-SEARCH` is sent but the answers never arrive. The caller holds
     * it for one sweep and releases it in `finally`. A device with no Wi-Fi
     * radio (or one already on ethernet) does not need it. The constructor and
     * [WifiManager.MulticastLock.acquire] both throw [SecurityException] when
     * `CHANGE_WIFI_MULTICAST_STATE` was not granted, which is what the caller
     * turns into [RemoteFailure.PermissionDenied].
     */
    private fun acquireMulticastLock(wifi: Boolean): WifiManager.MulticastLock? {
        if (!wifi) return null
        val manager = appContext.getSystemService(WifiManager::class.java)
            ?: throw SecurityException("No Wi-Fi service on this device")
        return manager.createMulticastLock(MULTICAST_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    internal companion object {
        /** Typed, catch-all, typed: the order that finds the most devices. */
        private val SWEEPS = listOf(Ssdp.ST_MEDIA_RENDERER, Ssdp.ST_ALL, Ssdp.ST_MEDIA_RENDERER)

        /** How long a sweep waits for answers. `MX` is 2, plus a little slack. */
        private const val RESPONSE_WINDOW_MS = 2_500

        /** How long results are trusted before the loop sweeps again. */
        private const val RESCAN_INTERVAL_MS = 10_000L

        /** A single SSDP datagram is a few hundred bytes; this is generous. */
        private const val MAX_DATAGRAM_BYTES = 8 * 1024

        private const val MULTICAST_LOCK_TAG = "wetube-dlna-discovery"

        /**
         * Resolves one parsed description into a [RemoteTarget], or `null` when
         * the device's AVTransport URL does not point at a casting target.
         *
         * Every control URL in a description is chosen by the device, i.e. by
         * whatever answered the sweep, and [RemoteRenderer] POSTs to all three.
         * Only the AVTransport URL decides whether the device is usable at all,
         * so an off-LAN value there drops the whole target. RenderingControl
         * (volume) and ConnectionManager (the advisory seek probe) are optional:
         * when [safeCastingUrl] rejects them they are set to `null`, which
         * disables just that feature rather than letting a hostile description
         * aim a blind request at an internet host or the metadata service.
         *
         * `internal` rather than private so the URL policy can be pinned by a
         * unit test without standing up an Android [android.content.Context].
         */
        internal fun targetFrom(
            device: UpnpDevice,
            response: SsdpResponse,
            host: String,
        ): RemoteTarget? {
            val controlUrl = device.avTransportUrl ?: return null
            if (safeCastingUrl(controlUrl) == null) return null

            val id = device.udn.ifBlank { response.usn }
            val name = device.friendlyName.ifBlank { device.modelName.ifBlank { host } }
            return RemoteTarget(
                id = id,
                friendlyName = name,
                modelName = device.modelName,
                controlUrl = controlUrl,
                renderingControlUrl = safeCastingUrl(device.renderingControlUrl),
                address = host,
                connectionManagerUrl = safeCastingUrl(device.connectionManagerUrl),
            )
        }

        /**
         * Returns [url] unchanged when its host is a casting target per
         * [LanAddress.isCastingTarget], or `null` when it is absent, is not a
         * parsable absolute URL, or points anywhere off the user's own LAN —
         * the cloud-metadata address included. The value is passed through
         * verbatim; only the decision to keep or drop it is made here.
         */
        private fun safeCastingUrl(url: String?): String? {
            val candidate = url ?: return null
            val host = hostOf(candidate) ?: return null
            return candidate.takeIf { LanAddress.isCastingTarget(host) }
        }

        private fun hostOf(url: String): String? =
            runCatching { URI(url.trim()).host }.getOrNull()?.takeIf { it.isNotBlank() }
    }
}
