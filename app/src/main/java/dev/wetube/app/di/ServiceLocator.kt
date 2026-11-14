package dev.wetube.app.di

import android.content.Context
import dev.wetube.app.BuildConfig
import dev.wetube.app.data.backend.BackendType
import dev.wetube.app.data.backend.ContentFilterBackend
import dev.wetube.app.data.backend.InnerTubeBackend
import dev.wetube.app.data.backend.InvidiousBackend
import dev.wetube.app.data.backend.JsonHttp
import dev.wetube.app.data.backend.PipedBackend
import dev.wetube.app.data.backend.TubeBackend
import dev.wetube.app.data.auth.AuthStore
import dev.wetube.app.data.extras.DislikeClient
import dev.wetube.app.data.extras.SponsorBlockClient
import dev.wetube.app.data.content.AgeGateStore
import dev.wetube.app.data.local.BackupStore
import dev.wetube.app.data.local.LibraryStore
import dev.wetube.app.data.prefs.AppSettings
import dev.wetube.app.data.prefs.SettingsStore
import dev.wetube.app.player.CaptionLoader
import dev.wetube.app.player.CastSessionKeeper
import dev.wetube.app.player.OutputRouter
import dev.wetube.app.player.PlaybackController
import dev.wetube.app.player.remote.NetworkRemoteDiscovery
import dev.wetube.app.player.remote.RemotePlaybackManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * Manual dependency container.
 *
 * Deliberately not a DI framework: the graph is small, explicit construction is
 * easier to follow, and it keeps the sample/build footprint low.
 */
class ServiceLocator(context: Context) {

    private val appContext = context.applicationContext

    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    /**
     * Some Wi-Fi networks publish AAAA records but silently drop IPv6. Android
     * hands those addresses back first, and OkHttp tries them one at a time, so
     * every request can burn its timeouts before IPv4 is attempted. We sort
     * IPv4 first and keep IPv6 as the fallback, which is also what IPv6-only
     * networks need.
     */
    private val ipv4First = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            Dns.SYSTEM.lookup(hostname).sortedBy { it is Inet6Address }
    }

    val http: OkHttpClient = OkHttpClient.Builder()
        .dns(ipv4First)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .apply {
            if (BuildConfig.DEBUG) HttpLog.install(this)
        }
        .build()

    private val jsonHttp = JsonHttp(http, json)

    val auth = AuthStore(appContext, jsonHttp)

    val settings = SettingsStore(appContext)
    val library = LibraryStore(appContext, json)
    val backup = BackupStore(library, settings, json)
    val ageGate = AgeGateStore(appContext)
    val sponsorBlock = SponsorBlockClient(jsonHttp)
    val dislikes = DislikeClient(jsonHttp)
    val playback = PlaybackController(appContext)
    val captionLoader = CaptionLoader(jsonHttp)

    /**
     * Process-scoped coroutines for work that must outlive a screen — the cast
     * poll loop. Created on first use, like the rest of the casting graph.
     */
    private val appScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Default) }

    /**
     * Where playback is: phone or a LAN renderer.
     *
     * Deliberately lazy, all the way down: a user who never opens the device
     * sheet never constructs the SSDP socket, the DLNA transport, the poll
     * scope or a discovery sweep. Everything a cast needs is built on the first
     * touch of the cast UI, and the local player is unaffected until then.
     */
    val output: OutputRouter by lazy {
        val remote = RemotePlaybackManager(
            discovery = NetworkRemoteDiscovery(appContext),
            resolveProgressive = { id -> backend().progressive(id) },
            scope = appScope,
        )
        // The poll loop lives in the process, not in a screen, so the process
        // has to stay alive — and awake — while a TV owns playback. The keeper
        // is bound to the remote manager's session, not to the router's output,
        // so a mid-session failure also releases the keep-alive.
        CastSessionKeeper(appContext, remote.state, appScope).start()
        OutputRouter(local = playback, remote = remote)
    }

    /** Settings plus whichever account the backends were built for. */
    private data class BackendKey(val settings: AppSettings, val account: String)

    @Volatile
    private var cached: Pair<BackendKey, TubeBackend>? = null

    suspend fun backend(): TubeBackend {
        val current = settings.settings.first()
        // Rebuild when the session changes: a signed-in backend sends cookies,
        // a signed-out one must not keep using them.
        val account = auth.account.first()?.cookies.orEmpty()
        val key = BackendKey(current, account)
        cached?.let { (cachedKey, backend) ->
            if (cachedKey == key) return backend
        }
        val backend = ContentFilterBackend(
            when (current.backend) {
                BackendType.INNERTUBE -> InnerTubeBackend(jsonHttp, current.region, auth)
                BackendType.INVIDIOUS -> InvidiousBackend(jsonHttp, current.invidiousHost, current.proxyStreams)
                BackendType.PIPED -> PipedBackend(jsonHttp, current.pipedHost)
            },
            settings,
            ageGate,
        )
        cached = key to backend
        return backend
    }
}
